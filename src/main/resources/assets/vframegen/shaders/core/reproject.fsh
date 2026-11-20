#version 330
#extension GL_ARB_separate_shader_objects : require

// Depth-aware reprojection for VFrameGen.
//
// Takes the last real frame (world colour + depth, and the final image with the GUI on top)
// and warps it to a camera pose between that frame and the next one. Works on any renderpearl
// backend (Vulkan or OpenGL): both use zero-to-one depth and the same texture/NDC orientation.
//
// For each output pixel we look for the source pixel that lands on it. That is a fixed-point
// problem (the landing spot depends on the depth at the source pixel), solved with a few
// iterations starting from the rotation-only answer, which is exact for the sky.

uniform sampler2D WorldSampler;   // last real frame, world only (before GUI)
uniform sampler2D DepthSampler;   // its depth, captured before the held item was drawn
uniform sampler2D HandDepthSampler; // depth of the held item pass (cleared to the far value first)
uniform sampler2D FinalSampler;   // last real frame, world + GUI

layout(std140) uniform FrameGen {
    mat4 SrcInvViewProj;     // src NDC -> position relative to the src camera
    mat4 DstViewProj;        // position relative to the src camera -> dst clip
    mat4 SrcViewProjRot;     // direction -> src clip (rotation only, for points at infinity)
    mat4 DstInvViewProjRot;  // dst NDC -> direction (rotation only)
    vec4 Params;             // x: hand mask valid, y: gui diff threshold, z: use depth, w: debug tint
    vec4 Params2;            // x: reversed Z (1/0), y: sharpness, z: 1/width, w: 1/height
    vec4 Params3;            // x: depth range is zero-to-one (1/0)
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

bool isSky(float d) {
    return Params2.x > 0.5 ? d <= 1.0e-7 : d >= 1.0 - 1.0e-7;
}


vec2 projectDirection(vec2 uvDst) {
    // The view ray through this pixel, as the difference of two points on it. (A single point is
    // not a direction: view bobbing puts a small translation in the projection matrix.)
    vec2 ndc = uvDst * 2.0 - 1.0;
    vec4 a = DstInvViewProjRot * vec4(ndc, 1.0, 1.0);
    vec4 b = DstInvViewProjRot * vec4(ndc, 0.5, 1.0);
    vec3 dir = b.xyz / b.w - a.xyz / a.w;
    if (Params2.x < 0.5) dir = -dir; // without reversed Z, NDC z = 1 is the far end
    vec4 clip = SrcViewProjRot * vec4(dir, 0.0);
    return (clip.xy / clip.w) * 0.5 + 0.5;
}

vec2 landingSpot(vec2 uvSrc, float depth) {
    float ndcZ = Params3.x > 0.5 ? depth : depth * 2.0 - 1.0;
    vec4 p = SrcInvViewProj * vec4(uvSrc * 2.0 - 1.0, ndcZ, 1.0);
    vec4 clip = DstViewProj * vec4(p.xyz / p.w, 1.0);
    return (clip.xy / clip.w) * 0.5 + 0.5;
}

vec3 sharpen(vec2 uv, vec3 center) {
    if (Params2.y <= 0.0) return center;
    vec2 px = Params2.zw;
    vec3 blur = texture(WorldSampler, uv + vec2(px.x, 0.0)).rgb
              + texture(WorldSampler, uv - vec2(px.x, 0.0)).rgb
              + texture(WorldSampler, uv + vec2(0.0, px.y)).rgb
              + texture(WorldSampler, uv - vec2(0.0, px.y)).rgb;
    return clamp(center + (center - blur * 0.25) * Params2.y, 0.0, 1.0);
}

void main() {
    vec2 uvDst = texCoord;

    // GUI / HUD: wherever the GUI changed the image, keep the real frame's pixel as is.
    vec4 finalPx = texture(FinalSampler, uvDst);
    vec4 worldPx = texture(WorldSampler, uvDst);
    vec3 diff = abs(finalPx.rgb - worldPx.rgb);
    if (max(diff.r, max(diff.g, diff.b)) > Params.y) {
        fragColor = vec4(finalPx.rgb, 1.0);
        return;
    }

    // Held item: it moves with the camera, so it is not reprojected.
    if (Params.x > 0.5 && !isSky(texture(HandDepthSampler, uvDst).r)) {
        fragColor = vec4(worldPx.rgb, 1.0);
        return;
    }

    vec2 skyUv = projectDirection(uvDst);
    vec2 uvSrc = skyUv;
    bool contested = false;
    if (Params.z > 0.5) {
        // Two searches: one from the rotation-only answer, one from "nothing moved". Every step is
        // a candidate. A candidate that lands within a pixel of this one is a real match, and of
        // those the nearest is visible (it is in front). With no match at all, this pixel was
        // hidden in the last frame, so it is filled from the farthest candidate, the background,
        // instead of stretching the foreground over it.
        vec2 screen = 1.0 / Params2.zw;
        vec2 bestHit = skyUv;
        float bestHitDepth = 0.0;
        bool haveHit = false;
        vec2 bestMiss = skyUv;
        float bestMissDepth = 0.0;
        bool haveMiss = false;

        for (int chain = 0; chain < 2; chain++) {
            vec2 uv = chain == 0 ? skyUv : uvDst;
            for (int i = 0; i < 6; i++) {
                vec2 uvc = clamp(uv, 0.0, 1.0);
                float d = texture(DepthSampler, uvc).r;
                bool sky = isSky(d);
                vec2 landed = sky ? uvDst + (uvc - skyUv) : landingSpot(uvc, d);
                float errPx = length((landed - uvDst) * screen);
                // Depth as "nearness": bigger is nearer, whichever way the depth buffer runs.
                float near = sky ? -1.0 : (Params2.x > 0.5 ? d : 1.0 - d);
                vec2 cand = sky ? skyUv : uvc;
                if (errPx < 1.0) {
                    if (!haveHit || near > bestHitDepth) {
                        if (haveHit && abs(near - bestHitDepth) > 1.0e-4) contested = true;
                        bestHit = cand;
                        bestHitDepth = near;
                        haveHit = true;
                    }
                } else if (!haveMiss || near < bestMissDepth) {
                    bestMiss = cand;
                    bestMissDepth = near;
                    haveMiss = true;
                }
                if (sky) break;
                uv += uvDst - landed;
            }
        }
        uvSrc = haveHit ? bestHit : bestMiss;
        contested = contested || !haveHit;
    }

    vec2 uv = clamp(uvSrc, vec2(0.0), vec2(1.0));
    if (contested) {
        // At a depth edge, bilinear filtering would blend foreground into background: snap to a texel.
        vec2 size = 1.0 / Params2.zw;
        uv = (floor(uv * size) + 0.5) / size;
    }
    vec3 color = sharpen(uv, texture(WorldSampler, uv).rgb);
    if (Params.w > 0.5) {
        color = mix(color, vec3(1.0, 0.0, 1.0), 0.15);
    }
    fragColor = vec4(color, 1.0);
}
