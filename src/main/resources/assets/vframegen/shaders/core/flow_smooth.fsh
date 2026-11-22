#version 330
#extension GL_ARB_separate_shader_objects : require

// VFrameGen shader optical flow: each vector becomes the median (per component) of its 3x3
// neighbourhood. Block matching gets single blocks wrong (flat areas, repeated textures); a median
// drops those without blurring the edge between two moving regions the way an average would.

uniform sampler2D Src;

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

// Median of 9 values (a fixed exchange network, no sorting loop).
void swap(inout float a, inout float b) { float t = min(a, b); b = max(a, b); a = t; }

float median9(float v[9]) {
    swap(v[1], v[2]); swap(v[4], v[5]); swap(v[7], v[8]);
    swap(v[0], v[1]); swap(v[3], v[4]); swap(v[6], v[7]);
    swap(v[1], v[2]); swap(v[4], v[5]); swap(v[7], v[8]);
    swap(v[0], v[3]); swap(v[5], v[8]); swap(v[4], v[7]);
    swap(v[3], v[6]); swap(v[1], v[4]); swap(v[2], v[5]);
    swap(v[4], v[7]); swap(v[4], v[2]); swap(v[6], v[4]);
    swap(v[4], v[2]);
    return v[4];
}

void main() {
    ivec2 size = textureSize(Src, 0);
    // From texCoord, not gl_FragCoord (see flow_down.fsh).
    ivec2 p = ivec2(texCoord * vec2(size));
    float xs[9], ys[9];
    int i = 0;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec2 v = texelFetch(Src, clamp(p + ivec2(x, y), ivec2(0), size - 1), 0).rg;
            xs[i] = v.x;
            ys[i] = v.y;
            i++;
        }
    }
    fragColor = vec4(median9(xs), median9(ys), 0.0, 1.0);
}
