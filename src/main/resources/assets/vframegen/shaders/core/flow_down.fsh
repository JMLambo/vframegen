#version 330
#extension GL_ARB_separate_shader_objects : require

// VFrameGen shader optical flow: the next (half-size) pyramid level, the average of 2x2 texels.

uniform sampler2D Src;

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    // From texCoord, not gl_FragCoord: on Vulkan the game flips render targets vertically
    // relative to texture rows, and every level must use the same rows.
    ivec2 p = ivec2(texCoord * vec2(textureSize(Src, 0) / 2)) * 2;
    ivec2 last = textureSize(Src, 0) - 1;
    float s = texelFetch(Src, min(p, last), 0).r + texelFetch(Src, min(p + ivec2(1, 0), last), 0).r
            + texelFetch(Src, min(p + ivec2(0, 1), last), 0).r + texelFetch(Src, min(p + ivec2(1, 1), last), 0).r;
    fragColor = vec4(s * 0.25, 0.0, 0.0, 1.0);
}
