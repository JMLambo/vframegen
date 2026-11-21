#version 330
#extension GL_ARB_separate_shader_objects : require

// VFrameGen motion blur: camera motion blur for every frame shown, real or generated.
//
// A shader pack's own motion blur smears each real frame over a whole real-frame interval, and
// that smear is then reprojected as if it were geometry (ghosting), and is several times too long
// for a generated frame. This pass blurs each shown frame along its own motion instead: from the
// camera of the frame shown before it to its own camera, so the length matches what is on screen
// at any multiplier.
//
// The depth comes from the real frame the image was built from (Src), at the same screen spot.
// For a generated frame that is off by a fraction of a frame's motion, which a blur does not show.
// GUI and the held item are not blurred, and are not smeared into the world next to them.

uniform sampler2D Color;  // the frame to show (world + GUI)
uniform sampler2D World;  // Src world before the GUI
uniform sampler2D Final;  // Src world + GUI
uniform sampler2D Depth;  // Src depth
uniform sampler2D Mask;   // Src held item mask (lockmask.fsh)

layout(std140) uniform MotionBlur {
    mat4 SrcInvViewProj; // Src NDC -> position relative to Src's camera (rotation only)
    mat4 CurViewProj;    // position relative to Src's camera -> this frame's clip
    mat4 PrevViewProj;   // position relative to Src's camera -> the previous shown frame's clip
    vec4 Params;   // x: strength (fraction of the frame interval), y: reversed Z, z: depth zero-to-one, w: GUI threshold
    vec4 Params2;  // x: 1/width, y: 1/height, z: hand buffer valid, w: near-depth hand lock
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

const int SAMPLES = 8;

bool isSky(float d) {
    return Params.y > 0.5 ? d <= 1.0e-7 : d >= 1.0 - 1.0e-7;
}

// GUI or held item at uv: kept sharp, and never sampled into the world.
bool fixedPixel(vec2 uv) {
    vec3 diff = abs(texture(Final, uv).rgb - texture(World, uv).rgb);
    if (max(diff.r, max(diff.g, diff.b)) > Params.w) return true;
    vec2 m = texture(Mask, uv).rg;
    return (Params2.z > 0.5 && m.r > 0.5) || (Params2.w > 0.5 && m.g > 0.5);
}

vec2 toUv(vec4 clip) {
    return (clip.xy / clip.w) * 0.5 + 0.5;
}

void main() {
    vec2 uv = texCoord;
    vec3 center = texture(Color, uv).rgb;
    if (fixedPixel(uv)) {
        fragColor = vec4(center, 1.0);
        return;
    }

    float d = texture(Depth, uv).r;
    vec2 ndc = uv * 2.0 - 1.0;
    vec4 p;
    if (isSky(d)) {
        // A direction: w = 0, so only the camera's rotation moves it.
        float farZ = Params.y > 0.5 ? 0.0 : 1.0;
        if (Params.z < 0.5) farZ = farZ * 2.0 - 1.0;
        vec4 a = SrcInvViewProj * vec4(ndc, farZ, 1.0);
        vec4 b = SrcInvViewProj * vec4(ndc, Params.z > 0.5 ? 0.5 : 0.0, 1.0);
        p = vec4(a.xyz / a.w - b.xyz / b.w, 0.0);
    } else {
        float ndcZ = Params.z > 0.5 ? d : d * 2.0 - 1.0;
        vec4 q = SrcInvViewProj * vec4(ndc, ndcZ, 1.0);
        p = vec4(q.xyz / q.w, 1.0);
    }
    vec4 cur = CurViewProj * p;
    vec4 prev = PrevViewProj * p;
    if (cur.w <= 1.0e-6 || prev.w <= 1.0e-6) {
        fragColor = vec4(center, 1.0);
        return;
    }
    // Screen motion over the shutter, in uv. Soft limit so a fast turn smears instead of tearing.
    vec2 motion = (toUv(cur) - toUv(prev)) * Params.x;
    float len = length(motion);
    motion *= 0.25 / (0.25 + len);
    if (length(motion / Params2.xy) < 0.5) {
        fragColor = vec4(center, 1.0);
        return;
    }

    // Centred on the pixel, from where it was towards where it is, with a per-pixel offset so the
    // steps show as noise rather than copies.
    float dither = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    vec3 sum = center;
    float weight = 1.0;
    for (int i = 0; i < SAMPLES; i++) {
        float s = (float(i) + dither) / float(SAMPLES) - 0.5;
        vec2 suv = uv + motion * s;
        if (any(lessThan(suv, vec2(0.0))) || any(greaterThan(suv, vec2(1.0)))) continue;
        if (fixedPixel(suv)) continue;
        sum += texture(Color, suv).rgb;
        weight += 1.0;
    }
    fragColor = vec4(sum / weight, 1.0);
}
