#version 330
#extension GL_ARB_separate_shader_objects : require

// VFrameGen anti-aliasing, SMAA pass 2 of 3: blending weights from the edge shapes, using SMAA's
// precomputed area and search textures.

uniform sampler2D Edges;  // pass 1 (linear)
uniform sampler2D Area;   // textures/smaa/area.png (linear)
uniform sampler2D Search; // textures/smaa/search.png (linear)

layout(std140) uniform Smaa {
    vec4 Metrics; // 1/width, 1/height, width, height
};

#define SMAA_RT_METRICS Metrics
#define SMAA_GLSL_3
// SMAA_PRESET_LOW / _MEDIUM / _HIGH / _ULTRA comes from the pipeline (the quality setting).
#define SMAA_INCLUDE_VS 0
#define SMAA_INCLUDE_PS 1
#include <vframegen:smaa.glsl>

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    vec2 uv = texCoord;
    vec2 pixcoord = uv * Metrics.zw;
    vec4 offset[3];
    offset[0] = Metrics.xyxy * vec4(-0.25, -0.125, 1.25, -0.125) + uv.xyxy;
    offset[1] = Metrics.xyxy * vec4(-0.125, -0.25, -0.125, 1.25) + uv.xyxy;
    offset[2] = Metrics.xxyy * vec4(-2.0, 2.0, -2.0, 2.0) * float(SMAA_MAX_SEARCH_STEPS)
            + vec4(offset[0].xz, offset[1].yw);
    fragColor = SMAABlendingWeightCalculationPS(uv, pixcoord, offset, Edges, Area, Search, vec4(0.0));
}
