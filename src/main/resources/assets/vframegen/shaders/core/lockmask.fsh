#version 330
#extension GL_ARB_separate_shader_objects : require

// VFrameGen: once per real frame, mark the pixels of the held item so the reprojection pass
// reads one texel instead of re-testing depth around every pixel it looks at.
// r: held item from vanilla's separate hand depth buffer.
// g: held item by near depth, grown by 3 px (TAA blends the item's colour into its surroundings).

uniform sampler2D Depth;
uniform sampler2D HandDepth;

layout(std140) uniform MaskParams {
    vec4 Flags;   // x: hand buffer valid, y: near-depth lock, z: its depth threshold
    vec4 Params2; // x: reversed Z, y: Aetherium held item (see nearAt), z: 1/width, w: 1/height
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

bool isSky(float d) {
    return Params2.x > 0.5 ? d <= 1.0e-7 : d >= 1.0 - 1.0e-7;
}

bool nearAt(vec2 uv) {
    // Aetherium keeps the world's depth under the held item and squeezes the item's own into
    // 0.4375..0.5625, where the world only gets within about 10 cm of the camera.
    if (Params2.y > 0.5) {
        float h = texture(HandDepth, uv).r;
        return h > 0.4374 && h < 0.5626;
    }
    float d = texture(Depth, uv).r;
    return Params2.x > 0.5 ? d > Flags.z : d < Flags.z;
}

void main() {
    vec2 uv = texCoord;
    float hand = 0.0;
    if (Flags.x > 0.5 && !isSky(texture(HandDepth, uv).r)) hand = 1.0;
    float locked = 0.0;
    if (Flags.y > 0.5) {
        // Every texel within 3 px (a disc), so a thin item edge leaves no gaps.
        for (int y = -3; y <= 3 && locked < 0.5; y++) {
            for (int x = -3; x <= 3; x++) {
                if (x * x + y * y > 10) continue;
                if (nearAt(uv + vec2(x, y) * Params2.zw)) {
                    locked = 1.0;
                    break;
                }
            }
        }
    }
    fragColor = vec4(hand, locked, 0.0, 1.0);
}
