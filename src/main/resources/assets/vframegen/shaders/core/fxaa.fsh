#version 330
#extension GL_ARB_separate_shader_objects : require

// VFrameGen anti-aliasing: FXAA (fast approximate anti-aliasing, after Timothy Lottes' algorithm),
// written for VFrameGen. It runs on the world image before the GUI, so text and the HUD stay
// sharp, and generated frames are built from the smoothed image.
//
// Per pixel: find whether it sits on an edge (local luma contrast), which way the edge runs, how
// far it extends both ways (a short search along it), and from that how much of the neighbour
// across the edge to blend in. A second, sub-pixel term softens single-pixel detail (thin lines,
// specks) that the edge search cannot follow.

uniform sampler2D Source; // the world, sampled with linear filtering

layout(std140) uniform Fxaa {
    vec4 Px; // xy: 1 / size
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

const float EDGE_THRESHOLD = 0.125;      // contrast relative to the brightest neighbour
const float EDGE_THRESHOLD_MIN = 0.0312; // ignore contrast below this (dark areas)
const float SUBPIXEL_QUALITY = 0.75;
const int SEARCH_STEPS = 12;
const float STEP_SIZE[12] = float[](1.0, 1.0, 1.0, 1.0, 1.0, 1.5, 2.0, 2.0, 2.0, 2.0, 4.0, 8.0);

float luma(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

float lumaAt(vec2 uv) {
    return luma(texture(Source, uv).rgb);
}

void main() {
    vec2 uv = texCoord;
    vec2 px = Px.xy;
    vec3 center = texture(Source, uv).rgb;

    float lM = luma(center);
    float lN = lumaAt(uv + vec2(0.0, -px.y));
    float lS = lumaAt(uv + vec2(0.0, px.y));
    float lW = lumaAt(uv + vec2(-px.x, 0.0));
    float lE = lumaAt(uv + vec2(px.x, 0.0));
    float lMin = min(lM, min(min(lN, lS), min(lW, lE)));
    float lMax = max(lM, max(max(lN, lS), max(lW, lE)));
    float range = lMax - lMin;
    if (range < max(EDGE_THRESHOLD_MIN, lMax * EDGE_THRESHOLD)) {
        fragColor = vec4(center, 1.0);
        return;
    }

    float lNW = lumaAt(uv + vec2(-px.x, -px.y));
    float lNE = lumaAt(uv + vec2(px.x, -px.y));
    float lSW = lumaAt(uv + vec2(-px.x, px.y));
    float lSE = lumaAt(uv + vec2(px.x, px.y));

    // Edge direction: a horizontal edge has its contrast across rows.
    float edgeHorz = abs(lNW + lSW - 2.0 * lW) + 2.0 * abs(lN + lS - 2.0 * lM) + abs(lNE + lSE - 2.0 * lE);
    float edgeVert = abs(lNW + lNE - 2.0 * lN) + 2.0 * abs(lW + lE - 2.0 * lM) + abs(lSW + lSE - 2.0 * lS);
    bool horz = edgeHorz >= edgeVert;

    // Which side of the pixel the edge is on: the neighbour with the bigger step.
    float l1 = horz ? lN : lW;
    float l2 = horz ? lS : lE;
    float g1 = l1 - lM;
    float g2 = l2 - lM;
    bool side1 = abs(g1) >= abs(g2);
    float gradient = 0.25 * max(abs(g1), abs(g2));
    float stepLen = horz ? px.y : px.x;
    float edgeLuma;
    if (side1) {
        stepLen = -stepLen;
        edgeLuma = 0.5 * (l1 + lM);
    } else {
        edgeLuma = 0.5 * (l2 + lM);
    }

    // Walk along the edge (between this pixel and that neighbour) both ways until it ends.
    vec2 edgeUv = uv;
    if (horz) edgeUv.y += stepLen * 0.5;
    else edgeUv.x += stepLen * 0.5;
    vec2 dir = horz ? vec2(px.x, 0.0) : vec2(0.0, px.y);
    vec2 uv1 = edgeUv - dir;
    vec2 uv2 = edgeUv + dir;
    float end1 = lumaAt(uv1) - edgeLuma;
    float end2 = lumaAt(uv2) - edgeLuma;
    bool done1 = abs(end1) >= gradient;
    bool done2 = abs(end2) >= gradient;
    for (int i = 0; i < SEARCH_STEPS && !(done1 && done2); i++) {
        if (!done1) {
            uv1 -= dir * STEP_SIZE[i];
            end1 = lumaAt(uv1) - edgeLuma;
            done1 = abs(end1) >= gradient;
        }
        if (!done2) {
            uv2 += dir * STEP_SIZE[i];
            end2 = lumaAt(uv2) - edgeLuma;
            done2 = abs(end2) >= gradient;
        }
    }

    // The nearer end decides: blend more the closer this pixel is to that end, but only if the
    // edge bends the right way there (otherwise this pixel is on the flat side of a step).
    float d1 = horz ? uv.x - uv1.x : uv.y - uv1.y;
    float d2 = horz ? uv2.x - uv.x : uv2.y - uv.y;
    bool nearer1 = d1 < d2;
    float edgeLength = d1 + d2;
    float offset = -min(d1, d2) / edgeLength + 0.5;
    bool centerDarker = lM < edgeLuma;
    bool bendsRight = ((nearer1 ? end1 : end2) < 0.0) != centerDarker;
    float edgeOffset = bendsRight ? offset : 0.0;

    // Sub-pixel: how much this pixel stands out from the 3x3 average.
    float average = (2.0 * (lN + lS + lW + lE) + lNW + lNE + lSW + lSE) / 12.0;
    float sub = clamp(abs(average - lM) / range, 0.0, 1.0);
    sub = (-2.0 * sub + 3.0) * sub * sub;
    float subOffset = sub * sub * SUBPIXEL_QUALITY;

    float finalOffset = max(edgeOffset, subOffset);
    vec2 finalUv = uv;
    if (horz) finalUv.y += finalOffset * stepLen;
    else finalUv.x += finalOffset * stepLen;
    fragColor = vec4(texture(Source, finalUv).rgb, 1.0);
}
