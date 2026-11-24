#version 330
#extension GL_ARB_separate_shader_objects : require

// VFrameGen: once per real frame, after its GUI is drawn, mark what the motion blur keeps sharp,
// so the blur on every shown frame reads one texel per tap instead of three.
// r: GUI (the final image differs from the world before the GUI).
// g: GUI or held item (lockmask.fsh).

uniform sampler2D World; // world before the GUI
uniform sampler2D Final; // world + GUI
uniform sampler2D Mask;  // held item mask

layout(std140) uniform GuiMaskParams {
    vec4 Params; // x: GUI threshold, y: hand buffer valid, z: near-depth hand lock
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    vec2 uv = texCoord;
    vec3 diff = abs(texture(Final, uv).rgb - texture(World, uv).rgb);
    float gui = max(diff.r, max(diff.g, diff.b)) > Params.x ? 1.0 : 0.0;
    vec2 m = texture(Mask, uv).rg;
    float hand = ((Params.y > 0.5 && m.r > 0.5) || (Params.z > 0.5 && m.g > 0.5)) ? 1.0 : 0.0;
    fragColor = vec4(gui, max(gui, hand), 0.0, 1.0);
}
