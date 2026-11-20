#version 330
#extension GL_ARB_separate_shader_objects : require

// VFrameGen render scale, pass 1: Catmull-Rom bicubic upscale (5 bilinear taps), clamped to the
// 2x2 source neighbourhood so edges do not ring.

uniform sampler2D Source; // low-resolution world, linear filtering

layout(std140) uniform Upscale {
    vec4 Src;    // xy: 1/size, zw: size
    vec4 Dst;    // xy: 1/size, z: sharpness
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    vec2 pos = texCoord * Src.zw;
    vec2 center = floor(pos - 0.5) + 0.5;
    vec2 f = pos - center;

    // Catmull-Rom weights.
    vec2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f));
    vec2 w1 = 1.0 + f * f * (-2.5 + 1.5 * f);
    vec2 w2 = f * (0.5 + f * (2.0 - 1.5 * f));
    vec2 w3 = f * f * (-0.5 + 0.5 * f);
    // Merge the middle two taps into one bilinear fetch.
    vec2 w12 = w1 + w2;
    vec2 off12 = w2 / w12;

    vec2 t0 = (center - 1.0) * Src.xy;
    vec2 t3 = (center + 2.0) * Src.xy;
    vec2 t12 = (center + off12) * Src.xy;

    vec3 c = texture(Source, vec2(t12.x, t0.y)).rgb * (w12.x * w0.y)
           + texture(Source, vec2(t0.x, t12.y)).rgb * (w0.x * w12.y)
           + texture(Source, vec2(t12.x, t12.y)).rgb * (w12.x * w12.y)
           + texture(Source, vec2(t3.x, t12.y)).rgb * (w3.x * w12.y)
           + texture(Source, vec2(t12.x, t3.y)).rgb * (w12.x * w3.y);
    float wsum = w12.x * w0.y + w0.x * w12.y + w12.x * w12.y + w3.x * w12.y + w12.x * w3.y;
    c /= wsum;

    // Anti-ringing: stay inside the range of the four nearest source texels.
    vec2 base = center * Src.xy;
    vec3 a = texture(Source, base).rgb;
    vec3 b = texture(Source, base + vec2(Src.x, 0.0)).rgb;
    vec3 d = texture(Source, base + vec2(0.0, Src.y)).rgb;
    vec3 e = texture(Source, base + Src.xy).rgb;
    vec3 lo = min(min(a, b), min(d, e));
    vec3 hi = max(max(a, b), max(d, e));
    fragColor = vec4(clamp(c, lo, hi), 1.0);
}
