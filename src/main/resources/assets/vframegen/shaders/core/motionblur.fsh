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
//
// The F3 crosshair (the axis gizmo) is drawn here too, on top, for this frame's camera rotation:
// it turns with the camera, so the copy in a real frame's GUI would lag behind in generated frames.
// The real frame is rendered without it while frame generation is on.
//
// Vanilla's vignette (the darkened screen edges) is applied here too, for the same reason: it is
// drawn with the GUI, so the GUI test took every darkened pixel for GUI and kept it unwarped (an
// oval of moving world in the middle of a still frame). The real frame's GUI is drawn without it.

uniform sampler2D Color;  // the frame to show (world + GUI)
uniform sampler2D World;  // Src world before the GUI
uniform sampler2D Final;  // Src world + GUI
uniform sampler2D Depth;  // Src depth
uniform sampler2D Mask;   // Src held item mask (lockmask.fsh)
uniform sampler2D Terrain; // Src depth before entities were drawn (BlurEnt.y)
uniform sampler2D Vignette; // vanilla's vignette texture (bound to Color when there is none)

layout(std140) uniform MotionBlur {
    mat4 SrcInvViewProj; // Src NDC -> position relative to Src's camera (rotation only)
    mat4 CurViewProj;    // position relative to Src's camera -> this frame's clip
    mat4 PrevViewProj;   // position relative to Src's camera -> the previous shown frame's clip
    vec4 Params;   // x: strength (fraction of the frame interval), y: reversed Z, z: depth zero-to-one, w: GUI threshold
    vec4 Params2;  // x: 1/width, y: 1/height, z: hand buffer valid, w: near-depth hand lock
    vec4 Cross;          // xy: crosshair centre (uv), z: draw it, w: line width scale
    vec4 CrossTip[3];    // xy: axis end (uv), farthest first
    vec4 CrossColor[3];
    vec4 VignetteColor;  // rgb: vanilla's vignette color, w: apply it
    vec4 BlurEnt;        // x: entities, y: Terrain valid
    vec4 Ent[96];        // per entity: box min, box max (relative to Src's camera), motion since the previous shown frame
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

const int SAMPLES = 8;

bool isSky(float d) {
    return Params.y > 0.5 ? d <= 1.0e-7 : d >= 1.0 - 1.0e-7;
}

bool guiPixel(vec2 uv) {
    vec3 diff = abs(texture(Final, uv).rgb - texture(World, uv).rgb);
    return max(diff.r, max(diff.g, diff.b)) > Params.w;
}

// GUI or held item at uv: kept sharp, and never sampled into the world.
bool fixedPixel(vec2 uv) {
    if (guiPixel(uv)) return true;
    vec2 m = texture(Mask, uv).rg;
    return (Params2.z > 0.5 && m.r > 0.5) || (Params2.w > 0.5 && m.g > 0.5);
}

vec2 toUv(vec4 clip) {
    return (clip.xy / clip.w) * 0.5 + 0.5;
}

float segmentDistancePx(vec2 p, vec2 a, vec2 b) {
    vec2 size = 1.0 / Params2.xy;
    p *= size;
    a *= size;
    b *= size;
    vec2 ab = b - a;
    float t = clamp(dot(p - a, ab) / max(dot(ab, ab), 1.0e-6), 0.0, 1.0);
    return length(p - a - ab * t);
}

// Like vanilla's: a black outline under each axis, then the axes, the nearest on top.
vec3 crosshair(vec3 color, vec2 uv) {
    if (Cross.z < 0.5) return color;
    float outline = 2.0 * Cross.w, fill = 1.0 * Cross.w;
    for (int i = 0; i < 3; i++) {
        float d = segmentDistancePx(uv, Cross.xy, CrossTip[i].xy);
        color = mix(color, vec3(0.0), clamp(outline + 0.5 - d, 0.0, 1.0));
    }
    for (int i = 0; i < 3; i++) {
        float d = segmentDistancePx(uv, Cross.xy, CrossTip[i].xy);
        color = mix(color, CrossColor[i].rgb, clamp(fill + 0.5 - d, 0.0, 1.0));
    }
    return color;
}

// How far the entity this pixel shows moved since the previous shown frame (zero for the world).
// Only pixels an entity drew (their depth changed when entities were drawn), not blocks beside it.
vec3 entityMotion(vec2 uv, float d, vec3 p) {
    int n = int(BlurEnt.x);
    if (n == 0) return vec3(0.0);
    if (BlurEnt.y > 0.5 && abs(texture(Terrain, uv).r - d) <= 1.0e-7) return vec3(0.0);
    for (int i = 0; i < n; i++) {
        if (all(greaterThanEqual(p, Ent[i * 3].xyz)) && all(lessThanEqual(p, Ent[i * 3 + 1].xyz))) {
            return Ent[i * 3 + 2].xyz;
        }
    }
    return vec3(0.0);
}

vec3 blurred(vec2 uv, vec3 center) {
    if (Params.x <= 0.0 || fixedPixel(uv)) return center;

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
    // A moving entity was that much farther back when the previous frame was shown.
    vec3 moved = p.w > 0.5 ? entityMotion(uv, d, p.xyz) : vec3(0.0);
    vec4 prev = PrevViewProj * vec4(p.xyz - moved, p.w);
    if (cur.w <= 1.0e-6 || prev.w <= 1.0e-6) {
        return center;
    }
    // Screen motion over the shutter, in uv. Soft limit so a fast turn smears instead of tearing.
    vec2 motion = (toUv(cur) - toUv(prev)) * Params.x;
    float len = length(motion);
    motion *= 0.25 / (0.25 + len);
    if (length(motion / Params2.xy) < 0.5) {
        return center;
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
    return sum / weight;
}

void main() {
    vec2 uv = texCoord;
    vec3 color = blurred(uv, texture(Color, uv).rgb);
    // Under the GUI and behind the held item, like vanilla's (drawn before the GUI, depth-tested
    // against the hand).
    if (!fixedPixel(uv)) color = crosshair(color, uv);
    // Vanilla blends it as dst * (1 - texture * color), over the world and the hand, under the GUI.
    if (VignetteColor.w > 0.5 && !guiPixel(uv)) {
        color *= 1.0 - texture(Vignette, uv).rgb * VignetteColor.rgb;
    }
    fragColor = vec4(color, 1.0);
}
