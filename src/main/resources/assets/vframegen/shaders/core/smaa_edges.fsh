#version 330
#extension GL_ARB_separate_shader_objects : require

// VFrameGen anti-aliasing, SMAA pass 1 of 3: luma edge detection (left and top edges per pixel).
// The target is cleared first; pixels without an edge are discarded.

uniform sampler2D Source; // the world (nearest)

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
    vec4 offset[3];
    offset[0] = Metrics.xyxy * vec4(-1.0, 0.0, 0.0, -1.0) + uv.xyxy;
    offset[1] = Metrics.xyxy * vec4( 1.0, 0.0, 0.0,  1.0) + uv.xyxy;
    offset[2] = Metrics.xyxy * vec4(-2.0, 0.0, 0.0, -2.0) + uv.xyxy;
    fragColor = vec4(SMAALumaEdgeDetectionPS(uv, offset, Source), 0.0, 0.0);
}
