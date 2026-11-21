#version 330
#extension GL_ARB_separate_shader_objects : require

// VFrameGen render scale, edge-adaptive upscaler, pass 1: a GLSL port of the EASU pass of AMD
// FidelityFX Super Resolution 1.0 (see the notice at the end). Per output pixel it reads the 12
// source texels around it, estimates the local edge direction and how strong it is from their
// luma, and filters with a Lanczos-like kernel stretched along the edge. Edges stay sharp
// without the jaggies of a plain bicubic, and the result is clamped to the nearest 2x2 texels
// so it does not ring.

uniform sampler2D Source; // low-resolution world (read with texelFetch)

layout(std140) uniform Upscale {
    vec4 Src;    // xy: 1/size, zw: size
    vec4 Dst;    // xy: 1/size, z: sharpness
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

vec3 fetch(ivec2 p) {
    return texelFetch(Source, clamp(p, ivec2(0), ivec2(Src.zw) - 1), 0).rgb;
}

// Luma approximation used by EASU: green counts twice as much as red and blue.
float luma(vec3 c) {
    return c.g + 0.5 * (c.r + c.b);
}

// Edge direction and length from one 2x2 quad of the 12 taps, weighted bilinearly by where the
// output pixel is (w). l is the "+" around the quad's centre texel: a above, b left, c centre,
// d right, e below.
void accumulateEdge(inout vec2 dir, inout float len, float w, float la, float lb, float lc, float ld, float le) {
    float dc = ld - lc;
    float cb = lc - lb;
    float lenX = max(abs(dc), abs(cb));
    lenX = lenX > 0.0 ? 1.0 / lenX : 0.0;
    float dirX = ld - lb;
    dir.x += dirX * w;
    lenX = clamp(abs(dirX) * lenX, 0.0, 1.0);
    len += lenX * lenX * w;

    float ec = le - lc;
    float ca = lc - la;
    float lenY = max(abs(ec), abs(ca));
    lenY = lenY > 0.0 ? 1.0 / lenY : 0.0;
    float dirY = le - la;
    dir.y += dirY * w;
    lenY = clamp(abs(dirY) * lenY, 0.0, 1.0);
    len += lenY * lenY * w;
}

// One tap of the kernel: off is the texel's offset from the output position.
void accumulateTap(inout vec3 sum, inout float wsum, vec2 off, vec2 dir, vec2 len2, float lob, float clp, vec3 c) {
    // Rotate into the edge's frame, then scale: long across the edge, short along it.
    vec2 v = vec2(off.x * dir.x + off.y * dir.y, off.x * -dir.y + off.y * dir.x) * len2;
    float d2 = min(dot(v, v), clp);
    // Lanczos-2 approximation: a windowed polynomial instead of sin/cos.
    float wB = 2.0 / 5.0 * d2 - 1.0;
    float wA = lob * d2 - 1.0;
    wB *= wB;
    wA *= wA;
    wB = 25.0 / 16.0 * wB - (25.0 / 16.0 - 1.0);
    float w = wB * wA;
    sum += c * w;
    wsum += w;
}

void main() {
    vec2 pp = texCoord * Src.zw - 0.5;
    vec2 fp = floor(pp);
    pp -= fp;
    ivec2 p = ivec2(fp);

    //     b c
    //   e f g h
    //   i j k l
    //     n o
    vec3 b = fetch(p + ivec2(0, -1));
    vec3 c = fetch(p + ivec2(1, -1));
    vec3 e = fetch(p + ivec2(-1, 0));
    vec3 f = fetch(p);
    vec3 g = fetch(p + ivec2(1, 0));
    vec3 h = fetch(p + ivec2(2, 0));
    vec3 i = fetch(p + ivec2(-1, 1));
    vec3 j = fetch(p + ivec2(0, 1));
    vec3 k = fetch(p + ivec2(1, 1));
    vec3 l = fetch(p + ivec2(2, 1));
    vec3 n = fetch(p + ivec2(0, 2));
    vec3 o = fetch(p + ivec2(1, 2));

    float bL = luma(b), cL = luma(c), eL = luma(e), fL = luma(f), gL = luma(g), hL = luma(h);
    float iL = luma(i), jL = luma(j), kL = luma(k), lL = luma(l), nL = luma(n), oL = luma(o);

    vec2 dir = vec2(0.0);
    float len = 0.0;
    accumulateEdge(dir, len, (1.0 - pp.x) * (1.0 - pp.y), bL, eL, fL, gL, jL);
    accumulateEdge(dir, len, pp.x * (1.0 - pp.y), cL, fL, gL, hL, kL);
    accumulateEdge(dir, len, (1.0 - pp.x) * pp.y, fL, iL, jL, kL, nL);
    accumulateEdge(dir, len, pp.x * pp.y, gL, jL, kL, lL, oL);

    // Normalize the direction; a flat area has none (any direction works).
    float dirR = dot(dir, dir);
    if (dirR < 1.0 / 32768.0) {
        dir = vec2(1.0, 0.0);
    } else {
        dir *= inversesqrt(dirR);
    }
    // Edge strength 0..1, shaped.
    len = len * 0.5;
    len *= len;
    // Stretch the kernel along diagonal edges (so it covers the same texels), and narrow it
    // across strong edges; the lobe goes from soft (0.5) to sharper (0.21) as the edge gets stronger.
    float stretch = dot(dir, dir) / max(abs(dir.x), abs(dir.y));
    vec2 len2 = vec2(1.0 + (stretch - 1.0) * len, 1.0 - 0.5 * len);
    float lob = 0.5 + ((1.0 / 4.0 - 0.04) - 0.5) * len;
    float clp = 1.0 / lob;

    vec3 sum = vec3(0.0);
    float wsum = 0.0;
    accumulateTap(sum, wsum, vec2(0.0, -1.0) - pp, dir, len2, lob, clp, b);
    accumulateTap(sum, wsum, vec2(1.0, -1.0) - pp, dir, len2, lob, clp, c);
    accumulateTap(sum, wsum, vec2(-1.0, 1.0) - pp, dir, len2, lob, clp, i);
    accumulateTap(sum, wsum, vec2(0.0, 1.0) - pp, dir, len2, lob, clp, j);
    accumulateTap(sum, wsum, vec2(0.0, 0.0) - pp, dir, len2, lob, clp, f);
    accumulateTap(sum, wsum, vec2(-1.0, 0.0) - pp, dir, len2, lob, clp, e);
    accumulateTap(sum, wsum, vec2(1.0, 1.0) - pp, dir, len2, lob, clp, k);
    accumulateTap(sum, wsum, vec2(2.0, 1.0) - pp, dir, len2, lob, clp, l);
    accumulateTap(sum, wsum, vec2(2.0, 0.0) - pp, dir, len2, lob, clp, h);
    accumulateTap(sum, wsum, vec2(1.0, 0.0) - pp, dir, len2, lob, clp, g);
    accumulateTap(sum, wsum, vec2(1.0, 2.0) - pp, dir, len2, lob, clp, o);
    accumulateTap(sum, wsum, vec2(0.0, 2.0) - pp, dir, len2, lob, clp, n);

    vec3 lo = min(min(f, g), min(j, k));
    vec3 hi = max(max(f, g), max(j, k));
    vec3 outc = wsum > 1.0e-6 ? sum / wsum : f;
    fragColor = vec4(clamp(outc, lo, hi), 1.0);
}

// Based on AMD FidelityFX Super Resolution 1.0 (FSR 1), EASU:
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
