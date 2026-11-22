#version 330
#extension GL_ARB_separate_shader_objects : require

// VFrameGen shader optical flow: brightness of the world image, at the measuring resolution
// (bilinear, so a 2x smaller target averages 2x2 pixels).

uniform sampler2D Src;

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    vec3 c = texture(Src, texCoord).rgb;
    fragColor = vec4(dot(c, vec3(0.299, 0.587, 0.114)), 0.0, 0.0, 1.0);
}
