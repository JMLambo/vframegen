#version 330
#extension GL_ARB_separate_shader_objects : require

// VFrameGen: builds a frame for a camera pose that was never rendered, from real frames.
//
// Low-latency mode: one source, the last real frame (A), warped towards the camera of the frame
// being rendered right now.
// Interpolated mode (#define INTERPOLATE): two sources, the real frame on screen (A) and the next
// one, held back (B). Each is warped to the in-between camera and the results are merged: where
// one source did not see a spot (screen edge, behind an object) the other usually did.
//
// Warping a source: for each output pixel, find the source pixel that lands on it. That is a
// fixed-point problem (the landing spot depends on the depth at the source pixel), solved with a
// few iterations from two starting points; the sky (no depth) is a pure rotation.
//
// Works on every renderpearl backend (Vulkan, OpenGL): both use zero-to-one depth (or say they
// don't, Params2.y) and the same texture/NDC orientation.

uniform sampler2D WorldA;      // world, before the GUI
uniform sampler2D DepthA;      // world depth, before the held item
uniform sampler2D MaskA;       // held item mask: r hand buffer, g near-depth lock (grown)
uniform sampler2D NoHandA;     // world before the held item (vanilla only)
uniform sampler2D FinalA;      // what was shown: world + GUI
uniform sampler2D TerrainA;    // depth before entities were drawn (EntCount.z)
#ifdef INTERPOLATE
uniform sampler2D WorldB;
uniform sampler2D DepthB;
uniform sampler2D MaskB;
uniform sampler2D NoHandB;
uniform sampler2D FinalB;
uniform sampler2D TerrainB;
// Hardware optical flow (NVIDIA), FlowParams.x: per pixel of A, where it went in B (FlowA), and per
// pixel of B, where it was in A (FlowB). RG16 snorm; times FlowParams.zw it is a uv offset.
uniform sampler2D FlowA;
uniform sampler2D FlowB;
#endif

layout(std140) uniform FrameGen {
    mat4 DstInvViewProjRot; // output NDC -> view direction (rotation only)
    mat4 AInvViewProj;      // A NDC -> position relative to A's camera
    mat4 ADstViewProj;      // position relative to A's camera -> output clip
    mat4 AViewProjRot;      // direction -> A clip
    mat4 BInvViewProj;
    mat4 BDstViewProj;
    mat4 BViewProjRot;
    vec4 Params;   // x: GUI diff threshold, y: debug tint, z: sharpness, w: debug view
    vec4 Params2;  // x: reversed Z, y: depth range zero-to-one, z: 1/width, w: 1/height
    vec4 AFlags;   // x: hand buffer valid, y: near-depth hand lock, z: its depth threshold, w: use depth
    vec4 BFlags;
    vec4 Mix;      // x: position between A (0) and B (1)
    vec4 EntCount; // x: entities in A, y: in B, z/w: TerrainA/B valid
    // Per source, 32 x (box min (w: screen motion x), box max (w: y), motion to the output frame,
    // screen rectangle in the output), relative to its camera.
    vec4 EntA[128];
    vec4 EntB[128];
    mat4 AToB;      // position relative to A's camera -> B clip (optical flow)
    mat4 BToA;      // position relative to B's camera -> A clip
    vec4 FlowParams; // x: optical flow on, y: trusted forward/backward disagreement (pixels^2), zw: snorm -> uv
    vec4 FlowLimits; // x: smallest residual used (pixels), y: largest
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

const int R_GUI = 1, R_HAND = 2, R_LOCKED = 3, R_HIT = 4, R_FILL = 5, R_PAST_HAND = 6, R_ROTATION = 7, R_OFFSCREEN = 8;

const int R_FLOW = 9;

struct Warp {
    vec3 color;
    float near;    // bigger = nearer, comparable between A and B
    int reason;
    bool hit;      // a real match for this pixel (not a fill, not off screen)
    vec3 still;    // this source's world at the output pixel, not warped
    bool entity;   // the match is on a moving entity
    bool flowed;   // optical flow moved the match (motion depth could not see)
};

bool isSky(float d) {
    return Params2.x > 0.5 ? d <= 1.0e-7 : d >= 1.0 - 1.0e-7;
}

float nearness(float d) {
    return Params2.x > 0.5 ? d : 1.0 - d;
}

// The view ray through an output pixel, mapped into a source frame. Two points on the ray, not
// one: view bobbing puts a small translation into the projection matrix.
// z is 0 when the direction is behind the source camera: not in that frame at all.
vec3 projectDirection(vec2 uvDst, mat4 srcViewProjRot) {
    vec2 ndc = uvDst * 2.0 - 1.0;
    vec4 a = DstInvViewProjRot * vec4(ndc, 1.0, 1.0);
    vec4 b = DstInvViewProjRot * vec4(ndc, 0.5, 1.0);
    vec3 dir = b.xyz / b.w - a.xyz / a.w;
    if (Params2.x < 0.5) dir = -dir; // without reversed Z, NDC z = 1 is the far end
    vec4 clip = srcViewProjRot * vec4(dir, 0.0);
    if (clip.w <= 1.0e-6) return vec3(-1.0, -1.0, 0.0);
    return vec3((clip.xy / clip.w) * 0.5 + 0.5, 1.0);
}

// A point (relative to the source camera) inside a moving entity's box moves with it as well.
// Root motion only, so it is the same for any model or animation mod.
vec3 entityMotion(vec3 p, bool fromB) {
    int n = int(fromB ? EntCount.y : EntCount.x);
    for (int i = 0; i < n; i++) {
        vec3 lo = fromB ? EntB[i * 4].xyz : EntA[i * 4].xyz;
        vec3 hi = fromB ? EntB[i * 4 + 1].xyz : EntA[i * 4 + 1].xyz;
        if (all(greaterThanEqual(p, lo)) && all(lessThanEqual(p, hi))) {
            return fromB ? EntB[i * 4 + 2].xyz : EntA[i * 4 + 2].xyz;
        }
    }
    return vec3(0.0);
}

// Whether an entity drew this source pixel: the depth there changed when entities were drawn
// (blocks next to an entity, inside its padded box, did not). Without that depth: the box only.
bool drawnByEntity(vec2 uv, float d, bool fromB) {
#ifdef INTERPOLATE
    if (fromB) {
        if (EntCount.w < 0.5) return true;
        return abs(texture(TerrainB, uv).r - d) > 1.0e-7;
    }
#endif
    if (EntCount.z < 0.5) return true;
    return abs(texture(TerrainA, uv).r - d) > 1.0e-7;
}

bool offscreen(vec2 uv);

#ifdef INTERPOLATE
// Motion the depth warp cannot see (limbs, particles, water, animated textures), from the hardware
// optical flow: where the flow says this source pixel is in the other frame, minus where depth
// (camera and entity root motion) puts it, as a uv offset over the whole step to the other frame.
// Zero where the forward and backward flows disagree (edges inside a 4x4 block, sky, smoke).
vec2 flowResidual(vec2 uvSrc, vec3 pos, bool byEntity, bool fromB) {
    if (FlowParams.x < 0.5) return vec2(0.0);
    vec2 f = (fromB ? texture(FlowB, uvSrc).rg : texture(FlowA, uvSrc).rg) * FlowParams.zw;
    // Off the image the reverse lookup would read an edge texel instead: not verifiable.
    if (offscreen(uvSrc + f)) return vec2(0.0);
    vec2 back = (fromB ? texture(FlowA, uvSrc + f).rg : texture(FlowB, uvSrc + f).rg) * FlowParams.zw;
    // Disagreement in pixels of the measured image (FlowParams.zw is snorm -> uv per axis).
    vec2 errPx = (f + back) / FlowParams.zw * (32767.0 / 32.0);
    if (dot(errPx, errPx) > FlowParams.y) return vec2(0.0);
    // Entity root motion to the output frame covers a fraction of the step; over the whole step:
    float frac = fromB ? 1.0 - Mix.x : Mix.x;
    vec3 moved = byEntity ? pos + entityMotion(pos, fromB) / max(frac, 1.0e-3) : pos;
    vec4 clip = (fromB ? BToA : AToB) * vec4(moved, 1.0);
    if (clip.w <= 1.0e-6) return vec2(0.0);
    vec2 predicted = (clip.xy / clip.w) * 0.5 + 0.5;
    vec2 r = uvSrc + f - predicted;
    // Small residuals are measurement noise; very large ones, a wrong match.
    float px = length(r / Params2.zw);
    if (px < FlowLimits.x || px > FlowLimits.y) return vec2(0.0);
    return r;
}
#endif

// Where a source pixel lands in the output. z is 0 when it lands behind the output camera (or the
// depth unprojects to nothing): never a match.
vec3 landingSpot(vec2 uvSrc, float depth, mat4 invViewProj, mat4 dstViewProj, bool fromB) {
    float ndcZ = Params2.y > 0.5 ? depth : depth * 2.0 - 1.0;
    vec4 p = invViewProj * vec4(uvSrc * 2.0 - 1.0, ndcZ, 1.0);
    if (abs(p.w) < 1.0e-12) return vec3(-1.0, -1.0, 0.0);
    vec3 pos = p.xyz / p.w;
    bool byEntity = drawnByEntity(uvSrc, depth, fromB);
#ifdef INTERPOLATE
    vec2 residual = flowResidual(uvSrc, pos, byEntity, fromB);
#endif
    if (byEntity) pos += entityMotion(pos, fromB);
    vec4 clip = dstViewProj * vec4(pos, 1.0);
    if (clip.w <= 1.0e-6) return vec3(-1.0, -1.0, 0.0);
    vec2 landed = (clip.xy / clip.w) * 0.5 + 0.5;
#ifdef INTERPOLATE
    landed += residual * (fromB ? 1.0 - Mix.x : Mix.x);
#endif
    return vec3(landed, 1.0);
}

// Held item, from the mask built once per real frame (lockmask.fsh).
bool nearLocked(sampler2D mask, vec2 uv, vec4 flags) {
    return flags.y > 0.5 && texture(mask, uv).g > 0.5;
}

bool offscreen(vec2 uv) {
    return any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)));
}

// The world at uv, without the held item. World and NoHand differ only where the hand was, so
// NoHand is read only there: with render scale it is the low-resolution image from before the
// upscaler, and reading it everywhere made generated frames soft next to the upscaled real frames
// (fine textures such as water flickered between sharp and soft: shaking).
vec3 sceneColor(sampler2D world, sampler2D noHand, sampler2D mask, vec2 uv, vec4 flags) {
    bool behindHand = false;
    if (flags.x > 0.5) {
        // Within a pixel of the hand too (the whole 3x3 around uv): a filtered World read there
        // would pick up its edge.
        vec2 px = Params2.zw;
        for (int y = -1; y <= 1 && !behindHand; y++) {
            for (int x = -1; x <= 1; x++) {
                if (texture(mask, uv + vec2(x, y) * px).r > 0.5) {
                    behindHand = true;
                    break;
                }
            }
        }
    }
    return behindHand ? texture(noHand, uv).rgb : texture(world, uv).rgb;
}

Warp warp(vec2 uvDst, sampler2D world, sampler2D depthTex, sampler2D mask, sampler2D noHand, sampler2D finalTex,
          mat4 invViewProj, mat4 dstViewProj, mat4 viewProjRot, vec4 flags, bool fromB) {
    Warp w;
    w.near = -1.0;
    w.hit = true;
    w.entity = false;
    w.flowed = false;

    // GUI / HUD: wherever the GUI changed the image, this source shows the GUI as it was.
    vec3 finalPx = texture(finalTex, uvDst).rgb;
    vec3 worldPx = texture(world, uvDst).rgb;
    w.still = worldPx;
    vec3 diff = abs(finalPx - worldPx);
    if (max(diff.r, max(diff.g, diff.b)) > Params.x) {
        w.color = finalPx;
        w.reason = R_GUI;
        return w;
    }
    // Held item: it moves with the camera, so it is not reprojected.
    vec2 m = texture(mask, uvDst).rg;
    if ((flags.x > 0.5 && m.r > 0.5) || (flags.y > 0.5 && m.g > 0.5)) {
        w.color = worldPx;
        w.reason = flags.x > 0.5 ? R_HAND : R_LOCKED;
        return w;
    }

    vec3 skyDir = projectDirection(uvDst, viewProjRot);
    vec2 skyUv = skyDir.xy;
    // Behind the source camera there is no rotation answer: no seed from it, no sky sample, and a
    // pixel with no match falls back to the unwarped one. (Merely off screen is still searched.)
    bool skyBehind = skyDir.z < 0.5;
    vec2 uvSrc = skyBehind ? uvDst : skyUv;
    bool contested = false;
    w.reason = R_ROTATION;
    if (flags.w > 0.5) {
        // Two searches: from the rotation-only answer and from "nothing moved". Every step is a
        // candidate. One that lands within a pixel is a real match, and of those the nearest is
        // visible. With no match this spot was hidden in this source: fill from the background.
        vec2 screen = 1.0 / Params2.zw;
        vec2 bestHit = skyUv;
        float bestHitNear = 0.0;
        bool haveHit = false;
        float minHitNear = 2.0, maxHitNear = -2.0;
        vec2 bestMiss = skyBehind ? uvDst : skyUv;
        float bestMissNear = 0.0;
        bool haveMiss = false;
        // Extra starting points: where a moving entity whose output rectangle covers this pixel
        // came from. Without them, an entity that moved farther than its own width is never found
        // (both other searches start on the background it left).
        vec2 seeds[4];
        int seedCount = 0;
        int ents = int(fromB ? EntCount.y : EntCount.x);
        for (int e = 0; e < ents && seedCount < 4; e++) {
            vec4 rect = fromB ? EntB[e * 4 + 3] : EntA[e * 4 + 3];
            if (uvDst.x < rect.x || uvDst.y < rect.y || uvDst.x > rect.z || uvDst.y > rect.w) continue;
            vec2 motion = vec2(fromB ? EntB[e * 4].w : EntA[e * 4].w, fromB ? EntB[e * 4 + 1].w : EntA[e * 4 + 1].w);
            seeds[seedCount++] = uvDst - motion;
        }
        for (int chain = skyBehind ? 1 : 0; chain < 2 + seedCount; chain++) {
            vec2 uv = chain == 0 ? skyUv : chain == 1 ? uvDst : seeds[chain - 2];
            for (int i = 0; i < 6; i++) {
                vec2 uvc = clamp(uv, 0.0, 1.0);
                float d = texture(depthTex, uvc).r;
                bool sky = isSky(d);
                vec3 spot = sky ? vec3(uvDst + (uvc - skyUv), 1.0) : landingSpot(uvc, d, invViewProj, dstViewProj, fromB);
                if (spot.z < 0.5) break; // behind the output camera: this chain can't go on
                vec2 landed = spot.xy;
                float errPx = length((landed - uvDst) * screen);
                float near = sky ? -1.0 : nearness(d);
                vec2 cand = sky ? skyUv : uvc;
                if (sky && skyBehind) break; // not a usable sample
                if (errPx < 1.0) {
                    // Matches at different depths: an edge, whatever order they were found in.
                    minHitNear = min(minHitNear, near);
                    maxHitNear = max(maxHitNear, near);
                    if (!haveHit || near > bestHitNear) {
                        bestHit = cand;
                        bestHitNear = near;
                        haveHit = true;
                    }
                } else if (!haveMiss || near < bestMissNear) {
                    bestMiss = cand;
                    bestMissNear = near;
                    haveMiss = true;
                }
                if (sky) break;
                uv += uvDst - landed;
            }
        }
        uvSrc = haveHit ? bestHit : bestMiss;
        if (haveHit) {
            float d = texture(depthTex, clamp(uvSrc, 0.0, 1.0)).r;
            if (!isSky(d)) {
                float ndcZ = Params2.y > 0.5 ? d : d * 2.0 - 1.0;
                vec4 p = invViewProj * vec4(uvSrc * 2.0 - 1.0, ndcZ, 1.0);
#ifdef INTERPOLATE
                w.flowed = abs(p.w) > 1.0e-12 && flowResidual(clamp(uvSrc, 0.0, 1.0), p.xyz / p.w,
                        drawnByEntity(clamp(uvSrc, 0.0, 1.0), d, fromB), fromB) != vec2(0.0);
#endif
                w.entity = abs(p.w) > 1.0e-12 && drawnByEntity(clamp(uvSrc, 0.0, 1.0), d, fromB) && any(notEqual(entityMotion(p.xyz / p.w, fromB), vec3(0.0)));
            }
        }
        w.near = haveHit ? bestHitNear : bestMissNear;
        contested = !haveHit || maxHitNear - minHitNear > 1.0e-4;
        w.hit = haveHit;
        w.reason = haveHit ? R_HIT : R_FILL;
    }
    if ((offscreen(skyUv) && offscreen(uvSrc)) || (skyBehind && w.reason == R_ROTATION)) {
        w.hit = false;
        w.reason = R_OFFSCREEN;
    }

    vec2 uv = clamp(uvSrc, vec2(0.0), vec2(1.0));
    if (nearLocked(mask, uv, flags)) {
        // That would copy the held item into the world next to it. The world behind it is not in
        // this source, so carry on past the item along the same motion.
        w.reason = R_PAST_HAND;
        w.hit = false;
        vec2 stepUv = normalize((uv - uvDst) / Params2.zw + vec2(1.0e-4)) * 6.0 * Params2.zw;
        for (int i = 0; i < 24 && nearLocked(mask, uv, flags); i++) {
            uv = clamp(uv + stepUv, vec2(0.0), vec2(1.0));
        }
        // Still on the item (screen edge, or a wide item): the world here, unwarped, rather than a
        // copy of the item.
        if (nearLocked(mask, uv, flags)) uv = uvDst;
    }
    if (contested) {
        // At a depth edge, bilinear filtering would blend foreground into background.
        vec2 size = 1.0 / Params2.zw;
        uv = (floor(uv * size) + 0.5) / size;
    }
    vec3 c = sceneColor(world, noHand, mask, uv, flags);
    if (Params.z > 0.0) {
        vec2 px = Params2.zw;
        vec3 blur = sceneColor(world, noHand, mask, uv + vec2(px.x, 0.0), flags)
                  + sceneColor(world, noHand, mask, uv - vec2(px.x, 0.0), flags)
                  + sceneColor(world, noHand, mask, uv + vec2(0.0, px.y), flags)
                  + sceneColor(world, noHand, mask, uv - vec2(0.0, px.y), flags);
        c = clamp(c + (c - blur * 0.25) * Params.z, 0.0, 1.0);
    }
    w.color = c;
    return w;
}

vec3 debugColor(vec3 color, int reason) {
    vec3 tint = reason == R_GUI ? vec3(0.2, 1.0, 0.2)        // green: GUI kept
              : reason == R_HAND ? vec3(0.2, 0.4, 1.0)       // blue: hand buffer
              : reason == R_LOCKED ? vec3(1.0, 0.1, 0.1)     // red: near-depth hand lock
              : reason == R_FILL ? vec3(1.0, 0.0, 1.0)       // magenta: hidden in source, filled
              : reason == R_PAST_HAND ? vec3(1.0, 0.6, 0.0)  // orange: stepped past the hand
              : reason == R_ROTATION ? vec3(0.0, 0.8, 0.8)   // cyan: rotation only
              : reason == R_OFFSCREEN ? vec3(1.0, 1.0, 0.0)  // yellow: off screen in source
              : reason == R_FLOW ? vec3(1.0, 1.0, 1.0)       // white: moved by optical flow
              : vec3(0.5);                                   // grey: depth match
    float lum = dot(color, vec3(0.299, 0.587, 0.114));
    return mix(vec3(lum * 0.6), tint, reason == R_HIT ? 0.0 : reason == R_FLOW ? 0.7 : 0.55);
}

void main() {
    vec2 uvDst = texCoord;
    Warp a = warp(uvDst, WorldA, DepthA, MaskA, NoHandA, FinalA, AInvViewProj, ADstViewProj, AViewProjRot, AFlags, false);
    vec3 color = a.color;
    int reason = a.reason;

#ifdef INTERPOLATE
    Warp b = warp(uvDst, WorldB, DepthB, MaskB, NoHandB, FinalB, BInvViewProj, BDstViewProj, BViewProjRot, BFlags, true);
    float t = Mix.x;
    bool aFixed = a.reason == R_GUI || a.reason == R_HAND || a.reason == R_LOCKED;
    bool bFixed = b.reason == R_GUI || b.reason == R_HAND || b.reason == R_LOCKED;
    if (aFixed || bFixed) {
        // GUI or held item in either source: the source nearer in time decides the whole pixel
        // (its hand, its GUI, or its world there). Mixing would show both hand positions at once.
        bool useB = t >= 0.5;
        Warp n = useB ? b : a;
        bool nFixed = useB ? bFixed : aFixed;
        color = n.color;
        reason = n.reason;
        if (!nFixed && !n.hit) {
            // Where the other frame's hand was, and this one's warp found nothing (it lands on
            // its own hand edge): the world there barely moves in a fraction of a frame, so show
            // it unwarped instead of a fill that streaks.
            color = n.still;
            reason = R_ROTATION;
        }
    } else {
        // One frame builds the picture: the one nearer in time (B from halfway on). Choosing per
        // pixel between A and B made edges a patchwork of both frames, which with a shader pack's
        // TAA (soft, jittered edges that differ frame to frame) showed as every block edge
        // shimmering. The other frame only fills what the main one could not see (uncovered
        // behind a moving edge, or off its screen).
        bool useB = t >= 0.5;
        Warp p = useB ? b : a;
        Warp o = useB ? a : b;
        if (p.hit) {
            color = p.color;
            reason = p.flowed ? R_FLOW : p.reason;
        } else if (o.hit && !o.entity) {
            // (Not a moving entity from the other frame: its limbs are in another pose there, and
            // filling from it showed both sets of legs.)
            color = o.color;
            reason = o.reason;
        } else {
            // Neither: fill from the main frame. Next to the held item (the search stepped past
            // it) the unwarped world is steadier than the fill.
            color = p.reason == R_PAST_HAND ? p.still : p.color;
            reason = p.reason;
        }
    }
#endif

    if (Params.w > 0.5) {
        color = debugColor(color, reason);
    } else if (Params.y > 0.5) {
        color = mix(color, vec3(1.0, 0.0, 1.0), 0.15);
    }
    fragColor = vec4(color, 1.0);
}
