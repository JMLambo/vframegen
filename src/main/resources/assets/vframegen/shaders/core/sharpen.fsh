#version 330
#extension GL_ARB_separate_shader_objects : require

// VFrameGen render scale, pass 2: contrast-adaptive sharpening at full resolution. Sharpens
// less where local contrast is already high, so edges do not get halos.

uniform sampler2D Source; // upscaled image, nearest filtering

layout(std140) uniform Upscale {
    vec4 Src;
    vec4 Dst;    // xy: 1/size, z: sharpness 0..1
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    vec2 px = Dst.xy;
    vec3 c = texture(Source, texCoord).rgb;
    if (Dst.z <= 0.0) {
        fragColor = vec4(c, 1.0);
        return;
    }
    vec3 n = texture(Source, texCoord + vec2(0.0, px.y)).rgb;
    vec3 s = texture(Source, texCoord - vec2(0.0, px.y)).rgb;
    vec3 e = texture(Source, texCoord + vec2(px.x, 0.0)).rgb;
    vec3 w = texture(Source, texCoord - vec2(px.x, 0.0)).rgb;

    vec3 lo = min(c, min(min(n, s), min(e, w)));
    vec3 hi = max(c, max(max(n, s), max(e, w)));
    // How much room there is before clipping, relative to the local peak.
    vec3 amp = sqrt(clamp(min(lo, 1.0 - hi) / max(hi, vec3(1.0e-4)), 0.0, 1.0));
    float strength = mix(8.0, 4.5, Dst.z);
    vec3 k = -amp / strength;
    vec3 outc = (c + k * (n + s + e + w)) / (1.0 + 4.0 * k);
    fragColor = vec4(clamp(outc, 0.0, 1.0), 1.0);
}
