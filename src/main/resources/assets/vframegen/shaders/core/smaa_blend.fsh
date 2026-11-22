#version 330
#extension GL_ARB_separate_shader_objects : require

// VFrameGen anti-aliasing, SMAA pass 3 of 3: blends each pixel with its neighbours by the weights.

uniform sampler2D Source;  // the world (linear)
uniform sampler2D Weights; // pass 2 (linear)

layout(std140) uniform Smaa {
    vec4 Metrics; // 1/width, 1/height, width, height
};

#define SMAA_RT_METRICS Metrics
#define SMAA_GLSL_3
#define SMAA_PRESET_HIGH
#define SMAA_INCLUDE_VS 0
#define SMAA_INCLUDE_PS 1
#include <vframegen:smaa.glsl>

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    vec2 uv = texCoord;
    vec4 offset = Metrics.xyxy * vec4(1.0, 0.0, 0.0, 1.0) + uv.xyxy;
    fragColor = vec4(SMAANeighborhoodBlendingPS(uv, offset, Source, Weights).rgb, 1.0);
}
