#version 330
#extension GL_ARB_separate_shader_objects : require

// VFrameGen shader optical flow (for GPUs without optical flow hardware): one level of a
// coarse-to-fine block search, in the spirit of FSR 3's optical flow but as draw passes (the
// game's renderer has no compute shaders).
//
// Each output texel is one 4x4 block of Cur. Its motion (where that block is in Ref, in this
// level's pixels) is searched in a small window around the coarser level's answer (Prev, twice
// as many pixels here), plus that answer from the neighbouring blocks and "no motion". The best
// match (sum of absolute differences, plus a small penalty for leaving the prediction) is then
// refined to a fraction of a pixel with a parabola through its neighbours.

uniform sampler2D Cur;   // brightness of the frame the flow is measured for
uniform sampler2D Ref;   // brightness of the other frame
uniform sampler2D Prev;  // the coarser level's flow (only read when Params.x is set)

layout(std140) uniform FlowMatch {
    vec4 Params; // x: has a coarser level, y: search radius (pixels), z: output scale (last level: to measured-image pixels)
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

const int BLOCK = 4;

float sad(ivec2 base, ivec2 v) {
    ivec2 last = textureSize(Ref, 0) - 1;
    float s = 0.0;
    for (int y = 0; y < BLOCK; y++) {
        for (int x = 0; x < BLOCK; x++) {
            ivec2 p = base + ivec2(x, y);
            s += abs(texelFetch(Cur, clamp(p, ivec2(0), last), 0).r - texelFetch(Ref, clamp(p + v, ivec2(0), last), 0).r);
        }
    }
    return s;
}

void main() {
    // From texCoord, not gl_FragCoord (see flow_down.fsh): rows must match the texture's.
    ivec2 block = ivec2(texCoord * vec2(textureSize(Cur, 0) / BLOCK));
    ivec2 base = block * BLOCK;
    vec2 size = vec2(textureSize(Cur, 0));
    vec2 uv = (vec2(base) + BLOCK * 0.5) / size;

    // Predictions: the coarser level here and at the neighbouring blocks (in this level's pixels).
    vec2 pred[5];
    int predCount = 1;
    pred[0] = vec2(0.0);
    if (Params.x > 0.5) {
        vec2 step = vec2(BLOCK) / size;
        pred[1] = texture(Prev, uv).rg * 2.0;
        pred[2] = texture(Prev, uv + vec2(step.x, 0.0)).rg * 2.0;
        pred[3] = texture(Prev, uv - vec2(step.x, 0.0)).rg * 2.0;
        pred[4] = texture(Prev, uv + vec2(0.0, step.y)).rg * 2.0;
        predCount = 5;
    }
    vec2 center = Params.x > 0.5 ? pred[1] : vec2(0.0);

    ivec2 best = ivec2(round(center));
    float bestCost = 1.0e9;
    for (int i = 0; i < predCount; i++) {
        ivec2 v = ivec2(round(pred[i]));
        float c = sad(base, v) + 0.02 * length(vec2(v) - center);
        if (c < bestCost) { bestCost = c; best = v; }
    }
    int r = int(Params.y);
    ivec2 start = best;
    for (int y = -r; y <= r; y++) {
        for (int x = -r; x <= r; x++) {
            ivec2 v = start + ivec2(x, y);
            float c = sad(base, v) + 0.02 * length(vec2(v) - center);
            if (c < bestCost) { bestCost = c; best = v; }
        }
    }

    // Fraction of a pixel: the minimum of a parabola through the costs either side.
    float c0 = sad(base, best);
    float cxm = sad(base, best - ivec2(1, 0)), cxp = sad(base, best + ivec2(1, 0));
    float cym = sad(base, best - ivec2(0, 1)), cyp = sad(base, best + ivec2(0, 1));
    float dx = cxm + cxp - 2.0 * c0, dy = cym + cyp - 2.0 * c0;
    vec2 sub = vec2(dx > 1.0e-5 ? clamp(0.5 * (cxm - cxp) / dx, -0.5, 0.5) : 0.0,
                    dy > 1.0e-5 ? clamp(0.5 * (cym - cyp) / dy, -0.5, 0.5) : 0.0);

    fragColor = vec4((vec2(best) + sub) * Params.z, bestCost, 1.0);
}
