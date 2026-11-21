#version 330
#extension GL_ARB_separate_shader_objects : require

// VFrameGen render scale, edge-adaptive upscaler, pass 2: a GLSL port of the RCAS pass of AMD
// FidelityFX Super Resolution 1.0 (see the notice at the end). Sharpens with a 5-tap "+" whose
// negative lobe is limited per pixel so the result never leaves the range of its neighbours:
// no halos and no clipping, which the plain CAS pass allows a little of.

uniform sampler2D Source; // upscaled image (read with texelFetch)

layout(std140) uniform Upscale {
    vec4 Src;
    vec4 Dst;    // xy: 1/size, z: sharpness 0..1
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

// Limit of the negative lobe (as in FSR 1), so flat noise is not amplified without bound.
const float LOBE_LIMIT = 0.25 - 1.0 / 16.0;

void main() {
    ivec2 size = textureSize(Source, 0);
    ivec2 p = clamp(ivec2(texCoord * vec2(size)), ivec2(0), size - 1);
    vec3 e = texelFetch(Source, p, 0).rgb;
    if (Dst.z <= 0.0) {
        fragColor = vec4(e, 1.0);
        return;
    }
    //   b
    // d e f
    //   h
    vec3 b = texelFetch(Source, clamp(p + ivec2(0, -1), ivec2(0), size - 1), 0).rgb;
    vec3 d = texelFetch(Source, clamp(p + ivec2(-1, 0), ivec2(0), size - 1), 0).rgb;
    vec3 f = texelFetch(Source, clamp(p + ivec2(1, 0), ivec2(0), size - 1), 0).rgb;
    vec3 h = texelFetch(Source, clamp(p + ivec2(0, 1), ivec2(0), size - 1), 0).rgb;

    vec3 mn4 = min(min(b, d), min(f, h));
    vec3 mx4 = max(max(b, d), max(f, h));
    // The strongest lobe that keeps the result inside [min, max] of the neighbourhood.
    vec3 hitMin = min(mn4, e) / max(4.0 * mx4, vec3(1.0e-5));
    vec3 hitMax = (1.0 - max(mx4, e)) / min(4.0 * mn4 - 4.0, vec3(-1.0e-5));
    vec3 lobeRGB = max(-hitMin, hitMax);
    // Sharpness 0..1 maps to FSR's 2..0 stops (0 = strongest).
    float sharpness = exp2(-2.0 * (1.0 - clamp(Dst.z, 0.0, 1.0)));
    float lobe = max(-LOBE_LIMIT, min(max(lobeRGB.r, max(lobeRGB.g, lobeRGB.b)), 0.0)) * sharpness;
    vec3 outc = (lobe * (b + d + f + h) + e) / (4.0 * lobe + 1.0);
    fragColor = vec4(clamp(outc, 0.0, 1.0), 1.0);
}

// Based on AMD FidelityFX Super Resolution 1.0 (FSR 1), RCAS:
// Copyright (c) 2021 Advanced Micro Devices, Inc. All rights reserved.
// Permission is hereby granted, free of charge, to any person obtaining a copy of this software
// and associated documentation files (the "Software"), to deal in the Software without
// restriction, including without limitation the rights to use, copy, modify, merge, publish,
// distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the
// Software is furnished to do so, subject to the following conditions: The above copyright notice
// and this permission notice shall be included in all copies or substantial portions of the
// Software. THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
// INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR
// PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR
// ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
// ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.
