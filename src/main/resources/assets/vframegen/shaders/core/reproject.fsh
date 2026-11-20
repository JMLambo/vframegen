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
#ifdef INTERPOLATE
uniform sampler2D WorldB;
uniform sampler2D DepthB;
uniform sampler2D MaskB;
uniform sampler2D NoHandB;
uniform sampler2D FinalB;
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
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

const int R_GUI = 1, R_HAND = 2, R_LOCKED = 3, R_HIT = 4, R_FILL = 5, R_PAST_HAND = 6, R_ROTATION = 7, R_OFFSCREEN = 8;

struct Warp {
    vec3 color;
    float near;    // bigger = nearer, comparable between A and B
    int reason;
    bool hit;      // a real match for this pixel (not a fill, not off screen)
    vec3 still;    // this source's world at the output pixel, not warped
};

bool isSky(float d) {
    return Params2.x > 0.5 ? d <= 1.0e-7 : d >= 1.0 - 1.0e-7;
}

float nearness(float d) {
    return Params2.x > 0.5 ? d : 1.0 - d;
}

// The view ray through an output pixel, mapped into a source frame. Two points on the ray, not
// one: view bobbing puts a small translation into the projection matrix.
vec2 projectDirection(vec2 uvDst, mat4 srcViewProjRot) {
    vec2 ndc = uvDst * 2.0 - 1.0;
    vec4 a = DstInvViewProjRot * vec4(ndc, 1.0, 1.0);
    vec4 b = DstInvViewProjRot * vec4(ndc, 0.5, 1.0);
    vec3 dir = b.xyz / b.w - a.xyz / a.w;
    if (Params2.x < 0.5) dir = -dir; // without reversed Z, NDC z = 1 is the far end
    vec4 clip = srcViewProjRot * vec4(dir, 0.0);
    // Behind the source camera: not in that frame at all.
    if (clip.w <= 1.0e-6) return vec2(-1.0);
    return (clip.xy / clip.w) * 0.5 + 0.5;
}

// Where a source pixel lands in the output. z is 0 when it lands behind the output camera (or the
// depth unprojects to nothing): never a match.
vec3 landingSpot(vec2 uvSrc, float depth, mat4 invViewProj, mat4 dstViewProj) {
    float ndcZ = Params2.y > 0.5 ? depth : depth * 2.0 - 1.0;
    vec4 p = invViewProj * vec4(uvSrc * 2.0 - 1.0, ndcZ, 1.0);
    if (abs(p.w) < 1.0e-12) return vec3(-1.0, -1.0, 0.0);
    vec4 clip = dstViewProj * vec4(p.xyz / p.w, 1.0);
    if (clip.w <= 1.0e-6) return vec3(-1.0, -1.0, 0.0);
    return vec3((clip.xy / clip.w) * 0.5 + 0.5, 1.0);
}

// Held item, from the mask built once per real frame (lockmask.fsh).
bool nearLocked(sampler2D mask, vec2 uv, vec4 flags) {
    return flags.y > 0.5 && texture(mask, uv).g > 0.5;
}

bool offscreen(vec2 uv) {
    return any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)));
}

vec3 sceneColor(sampler2D world, sampler2D noHand, vec2 uv, vec4 flags) {
    return flags.x > 0.5 ? texture(noHand, uv).rgb : texture(world, uv).rgb;
}

Warp warp(vec2 uvDst, sampler2D world, sampler2D depthTex, sampler2D mask, sampler2D noHand, sampler2D finalTex,
          mat4 invViewProj, mat4 dstViewProj, mat4 viewProjRot, vec4 flags) {
    Warp w;
    w.near = -1.0;
    w.hit = true;

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

    vec2 skyUv = projectDirection(uvDst, viewProjRot);
    vec2 uvSrc = skyUv;
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
        vec2 bestMiss = skyUv;
        float bestMissNear = 0.0;
        bool haveMiss = false;
        for (int chain = 0; chain < 2; chain++) {
            vec2 uv = chain == 0 ? skyUv : uvDst;
            for (int i = 0; i < 6; i++) {
                vec2 uvc = clamp(uv, 0.0, 1.0);
                float d = texture(depthTex, uvc).r;
                bool sky = isSky(d);
                vec3 spot = sky ? vec3(uvDst + (uvc - skyUv), 1.0) : landingSpot(uvc, d, invViewProj, dstViewProj);
                if (spot.z < 0.5) break; // behind the output camera: this chain can't go on
                vec2 landed = spot.xy;
                float errPx = length((landed - uvDst) * screen);
                float near = sky ? -1.0 : nearness(d);
                vec2 cand = sky ? skyUv : uvc;
                if (errPx < 1.0) {
                    // Two matches at different depths: an edge, whichever was found first.
                    if (haveHit && abs(near - bestHitNear) > 1.0e-4) contested = true;
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
        w.near = haveHit ? bestHitNear : bestMissNear;
        contested = contested || !haveHit;
        w.hit = haveHit;
        w.reason = haveHit ? R_HIT : R_FILL;
    }
    if (offscreen(skyUv) && offscreen(uvSrc)) {
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
    vec3 c = sceneColor(world, noHand, uv, flags);
    if (Params.z > 0.0) {
        vec2 px = Params2.zw;
        vec3 blur = sceneColor(world, noHand, uv + vec2(px.x, 0.0), flags)
                  + sceneColor(world, noHand, uv - vec2(px.x, 0.0), flags)
                  + sceneColor(world, noHand, uv + vec2(0.0, px.y), flags)
                  + sceneColor(world, noHand, uv - vec2(0.0, px.y), flags);
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
              : vec3(0.5);                                   // grey: depth match
    float lum = dot(color, vec3(0.299, 0.587, 0.114));
    return mix(vec3(lum * 0.6), tint, reason == R_HIT ? 0.0 : 0.55);
}

void main() {
    vec2 uvDst = texCoord;
    Warp a = warp(uvDst, WorldA, DepthA, MaskA, NoHandA, FinalA, AInvViewProj, ADstViewProj, AViewProjRot, AFlags);
    vec3 color = a.color;
    int reason = a.reason;

#ifdef INTERPOLATE
    Warp b = warp(uvDst, WorldB, DepthB, MaskB, NoHandB, FinalB, BInvViewProj, BDstViewProj, BViewProjRot, BFlags);
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
            reason = p.reason;
        } else if (o.hit) {
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
