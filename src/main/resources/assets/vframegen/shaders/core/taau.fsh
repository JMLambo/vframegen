#version 330
#extension GL_ARB_separate_shader_objects : require

// VFrameGen temporal upscaling (TAAU), written for VFrameGen.
//
// Each frame the world renders at a lower resolution with the projection shifted by a fraction of
// a pixel (a different shift every frame), so successive frames sample different spots. This pass
// keeps a full-resolution history and, for every output pixel:
//   1. reconstructs the current frame's colour there from the nearby low-resolution samples,
//      weighted by how close each sample's (shifted) position is to the pixel centre;
//   2. finds where the pixel was in the last frame (depth + camera motion, and the motion of a
//      moving entity when the pixel is on one) and reads the history there;
//   3. clips that history to the range of colours around the pixel in the current frame, so
//      anything that changed (disocclusion, moving mobs, lighting) does not ghost;
//   4. blends the current frame in by how much each holds: the history keeps a per-pixel weight
//      (alpha) of the samples it gathered, so newly uncovered pixels sharpen within a few frames
//      and settled ones stay steady.
// Over a few frames the history fills in detail at full resolution (the jitter cycle is longer at
// lower render scales). At 100% render scale it is plain temporal anti-aliasing.

uniform sampler2D Color;   // this frame, low resolution (texelFetch; linear for the held item)
uniform sampler2D Depth;   // its depth, before the held item
uniform sampler2D PrevDepth; // last frame's depth (before the held item)
uniform sampler2D Hand;    // held item depth (sky where there is none)
uniform sampler2D History; // last output, full resolution (linear)

layout(std140) uniform Taau {
    mat4 CurInvViewProj; // this frame's NDC (no jitter) -> position relative to its camera
    mat4 PrevViewProj;   // position relative to this frame's camera -> last frame's clip
    mat4 PrevInvViewProj; // last frame's NDC (no jitter) -> position relative to its camera
    vec4 LowSize;        // xy: size, zw: 1/size
    vec4 FullSize;       // xy: size, zw: 1/size
    vec4 Jitter;         // xy: this frame's shift in low-res pixels, z: reset, w: hand valid
    vec4 Flags;          // x: reversed Z, y: depth zero-to-one, z: PrevDepth valid, w: entity count
    vec4 CamDelta;       // xyz: this camera minus the last one
    vec4 Ent[48];        // per moving entity: box min, box max (relative to this camera), its motion since the last frame
};

// History alpha: 1 = held item; otherwise the sample weight the pixel has gathered
// (its confidence), times ALPHA_SCALE, up to MAX_FRAMES.
const float MAX_FRAMES = 8.0;
const float ALPHA_SCALE = 0.9 / MAX_FRAMES;

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

bool isSky(float d) {
    return Flags.x > 0.5 ? d <= 1.0e-7 : d >= 1.0 - 1.0e-7;
}

// History read with a Catmull-Rom filter (9 taps folded into 5 bilinear ones): bilinear would
// blur it a little more every frame the camera moves.
vec3 sampleHistory(vec2 uv) {
    vec2 pos = uv * FullSize.xy;
    vec2 c1 = floor(pos - 0.5) + 0.5;
    vec2 f = pos - c1;
    vec2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f));
    vec2 w1 = 1.0 + f * f * (-2.5 + 1.5 * f);
    vec2 w2 = f * (0.5 + f * (2.0 - 1.5 * f));
    vec2 w3 = f * f * (-0.5 + 0.5 * f);
    vec2 w12 = w1 + w2;
    vec2 c0 = (c1 - 1.0) * FullSize.zw;
    vec2 c3 = (c1 + 2.0) * FullSize.zw;
    vec2 c12 = (c1 + w2 / w12) * FullSize.zw;
    vec3 r = texture(History, vec2(c12.x, c0.y)).rgb * (w12.x * w0.y)
           + texture(History, vec2(c0.x, c12.y)).rgb * (w0.x * w12.y)
           + texture(History, c12).rgb * (w12.x * w12.y)
           + texture(History, vec2(c3.x, c12.y)).rgb * (w3.x * w12.y)
           + texture(History, vec2(c12.x, c3.y)).rgb * (w12.x * w3.y);
    float wsum = w12.x * w0.y + w0.x * w12.y + w12.x * w12.y + w3.x * w12.y + w12.x * w3.y;
    return max(r / wsum, vec3(0.0));
}

vec3 toYCoCg(vec3 c) {
    return vec3(dot(c, vec3(0.25, 0.5, 0.25)), dot(c, vec3(0.5, 0.0, -0.5)), dot(c, vec3(-0.25, 0.5, -0.25)));
}

vec3 fromYCoCg(vec3 c) {
    return vec3(c.x + c.y - c.z, c.x + c.z, c.x - c.y - c.z);
}

// Clip toward the box centre (not a per-channel clamp: keeps the hue).
vec3 clipToBox(vec3 h, vec3 lo, vec3 hi) {
    vec3 center = 0.5 * (hi + lo);
    vec3 extent = 0.5 * (hi - lo) + 1.0e-4;
    vec3 v = h - center;
    vec3 a = abs(v / extent);
    float m = max(a.x, max(a.y, a.z));
    return m > 1.0 ? center + v / m : h;
}

// How far last frame's depth at p's spot then is from p's distance then (relative): small when p
// was visible there. p is relative to this frame's camera.
float depthError(vec3 p) {
    vec4 c = PrevViewProj * vec4(p, 1.0);
    if (c.w <= 1.0e-6) return 1.0e9;
    vec2 puv = (c.xy / c.w) * 0.5 + 0.5;
    if (any(lessThan(puv, vec2(0.0))) || any(greaterThan(puv, vec2(1.0)))) return 1.0e9;
    float s = texelFetch(PrevDepth, clamp(ivec2(puv * LowSize.xy), ivec2(0), ivec2(LowSize.xy) - 1), 0).r;
    if (isSky(s)) return 1.0e9;
    float pz = Flags.y > 0.5 ? s : s * 2.0 - 1.0;
    vec4 q = PrevInvViewProj * vec4(puv * 2.0 - 1.0, pz, 1.0);
    float expected = length(p + CamDelta.xyz);
    return abs(length(q.xyz / q.w) - expected) / max(expected, 0.05);
}

void main() {
    vec2 uv = texCoord;
    vec2 lowPos = uv * LowSize.xy;               // in low-res pixels, unjittered
    vec2 scale = FullSize.xy * LowSize.zw;       // output pixels per low-res pixel
    ivec2 maxTexel = ivec2(LowSize.xy) - 1;
    // Texel t shows the point at (t + 0.5 - jitter) in unjittered low-res pixels.
    ivec2 centre = ivec2(floor(lowPos + Jitter.xy));

    vec3 sum = vec3(0.0);
    float wsum = 0.0, wmax = 0.0;
    vec3 nearest = vec3(0.0);
    vec3 m1 = vec3(0.0), m2 = vec3(0.0);
    float closest = Flags.x > 0.5 ? 0.0 : 1.0;
    ivec2 closestTexel = clamp(centre, ivec2(0), maxTexel);
    // The held item is drawn without the jitter: this pixel is on it if the texel under it (no
    // jitter) is. Its texels are left out of the world's reconstruction below.
    bool hand = Jitter.w > 0.5 && !isSky(texelFetch(Hand, clamp(ivec2(lowPos), ivec2(0), maxTexel), 0).r);
    float taps = 0.0;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            ivec2 t = clamp(centre + ivec2(x, y), ivec2(0), maxTexel);
            if (Jitter.w > 0.5 && !isSky(texelFetch(Hand, t, 0).r)) continue;
            taps += 1.0;
            vec3 c = max(texelFetch(Color, t, 0).rgb, vec3(0.0));
            vec2 d = (vec2(t) + 0.5 - Jitter.xy - lowPos) * scale; // in output pixels
            float w = exp(-2.29 * dot(d, d));
            sum += c * w;
            wsum += w;
            if (w > wmax) {
                wmax = w;
                nearest = c;
            }
            vec3 ycc = toYCoCg(c);
            m1 += ycc;
            m2 += ycc * ycc;
            float dz = texelFetch(Depth, t, 0).r;
            if (Flags.x > 0.5 ? dz > closest : dz < closest) {
                closest = dz;
                closestTexel = t;
            }
        }
    }
    vec3 current = wsum > 1.0e-4 ? sum / wsum : texture(Color, uv).rgb;
    if (taps < 0.5) nearest = current;

    // The held item is drawn without the jitter, so its samples sit at the texel centres: read it
    // as is (rebuilding it from jittered positions would shake it a little every frame).
    // Alpha 1 marks it in the history, so it is never accumulated into the world (an afterimage
    // where it was, once it moves away).
    if (hand) {
        fragColor = vec4(texture(Color, uv).rgb, 1.0);
        return;
    }
    // A new pixel starts with the filtered colour and one sample's worth of confidence.
    vec4 fresh = vec4(current, ALPHA_SCALE * max(wmax, 0.05));
    if (Jitter.z > 0.5) {
        fragColor = fresh;
        return;
    }

    // Where this pixel was last frame, by the nearest depth around it (keeps edges of near
    // objects from smearing the background over them).
    vec2 ndc = uv * 2.0 - 1.0;
    vec4 prevClip;
    vec3 here = vec3(0.0);
    bool sky = isSky(closest);
    if (sky) {
        float farZ = Flags.x > 0.5 ? 0.0 : 1.0;
        if (Flags.y < 0.5) farZ = farZ * 2.0 - 1.0;
        vec4 a = CurInvViewProj * vec4(ndc, farZ, 1.0);
        vec4 b = CurInvViewProj * vec4(ndc, Flags.y > 0.5 ? 0.5 : 0.0, 1.0);
        prevClip = PrevViewProj * vec4(a.xyz / a.w - b.xyz / b.w, 0.0);
    } else {
        float ndcZ = Flags.y > 0.5 ? closest : closest * 2.0 - 1.0;
        vec4 p = CurInvViewProj * vec4(ndc, ndcZ, 1.0);
        here = p.xyz / p.w;
        // Inside a moving entity's box: the pixel shows either the entity (it was where the entity
        // was, minus its motion) or what is around it (it stayed put). The one that agrees with
        // last frame's depth wins; without that depth, the entity.
        int count = int(Flags.w + 0.5);
        for (int i = 0; i < count; i++) {
            vec3 lo = Ent[i * 3].xyz, hi = Ent[i * 3 + 1].xyz;
            if (any(lessThan(here, lo)) || any(greaterThan(here, hi))) continue;
            vec3 moved = here - Ent[i * 3 + 2].xyz;
            if (Flags.z < 0.5 || depthError(moved) < depthError(here)) here = moved;
            break;
        }
        prevClip = PrevViewProj * vec4(here, 1.0);
    }
    if (prevClip.w <= 1.0e-6) {
        fragColor = fresh;
        return;
    }
    vec2 prevUv = (prevClip.xy / prevClip.w) * 0.5 + 0.5;
    if (any(lessThan(prevUv, vec2(0.0))) || any(greaterThan(prevUv, vec2(1.0)))) {
        fragColor = fresh;
        return;
    }
    // The held item was there last frame (any of the texels the history read blends with a
    // weight above zero): its colour is not the world's.
    vec2 hp = prevUv * FullSize.xy - 0.5;
    ivec2 ht = ivec2(floor(hp));
    vec2 hf = hp - vec2(ht);
    ivec2 hmax = ivec2(FullSize.xy) - 1;
    float handPrev = texelFetch(History, clamp(ht, ivec2(0), hmax), 0).a;
    if (hf.x > 1.0e-3) handPrev = max(handPrev, texelFetch(History, clamp(ht + ivec2(1, 0), ivec2(0), hmax), 0).a);
    if (hf.y > 1.0e-3) handPrev = max(handPrev, texelFetch(History, clamp(ht + ivec2(0, 1), ivec2(0), hmax), 0).a);
    if (hf.x > 1.0e-3 && hf.y > 1.0e-3) handPrev = max(handPrev, texelFetch(History, clamp(ht + ivec2(1, 1), ivec2(0), hmax), 0).a);
    if (handPrev > 0.95) {
        fragColor = fresh;
        return;
    }
    float frames = texture(History, prevUv).a / ALPHA_SCALE;
    // Something else was in front last frame (newly uncovered): the history there shows that, not
    // this surface. Compared by distance from the last camera, with some slack (jitter, slopes).
    if (Flags.z > 0.5 && !sky) {
        // The farthest depth around that spot: at the edge of something nearer, one sample lands on
        // it one frame and misses it the next (jitter), which made edges flicker. Only when the
        // whole neighbourhood was nearer is this surface really newly uncovered.
        ivec2 pt = ivec2(prevUv * LowSize.xy);
        float pd = Flags.x > 0.5 ? 1.0 : 0.0;
        for (int y = -1; y <= 1; y++) {
            for (int x = -1; x <= 1; x++) {
                float s = texelFetch(PrevDepth, clamp(pt + ivec2(x, y), ivec2(0), maxTexel), 0).r;
                pd = Flags.x > 0.5 ? min(pd, s) : max(pd, s);
            }
        }
        // Only something nearer counts: farther (or sky) there is just a moving silhouette edge.
        bool rejected = false;
        if (!isSky(pd)) {
            float pz = Flags.y > 0.5 ? pd : pd * 2.0 - 1.0;
            vec4 q = PrevInvViewProj * vec4(prevUv * 2.0 - 1.0, pz, 1.0);
            float seen = length(q.xyz / q.w);
            float expected = length(here + CamDelta.xyz);
            rejected = seen < expected - (0.05 * expected + 0.15);
        }
        if (rejected) {
            fragColor = fresh;
            return;
        }
    }

    vec3 history = sampleHistory(prevUv);
    vec3 mean = m1 / max(taps, 1.0);
    vec3 sigma = sqrt(max(m2 / max(taps, 1.0) - mean * mean, vec3(0.0)));
    vec3 lo = mean - 1.25 * sigma, hi = mean + 1.25 * sigma;
    vec3 raw = toYCoCg(history);
    vec3 clipped = clipToBox(raw, lo, hi);
    history = fromYCoCg(clipped);
    // The more the history had to be pulled in, the less it is trusted from here on: it drops
    // its confidence (lighting changes, smears) and recovers within a few frames.
    float pulled = clamp(length(raw - clipped) / (length(hi - lo) * 0.5 + 0.02), 0.0, 1.0);

    // Each frame adds its sample nearest the pixel, by how near it is: averaging all the samples
    // around would blur the textures. While the pixel has little history (just uncovered), the
    // filtered colour stands in, so it starts smooth and sharpens as samples come in.
    vec2 motionPx = (uv - prevUv) * FullSize.xy;
    float motion = clamp(length(motionPx) / 16.0, 0.0, 1.0);
    float held = frames * (1.0 - 0.75 * pulled);
    float w = max(wmax, 0.05);
    current = mix(current, nearest, clamp(held / 2.0, 0.0, 1.0));
    // Weighted by samples: the history counts as the sample weight it gathered, at most a few
    // frames' worth (fewer while moving, where the history read itself blurs a little).
    held = min(held, mix(6.0, 2.0, motion));
    float alpha = clamp(w / (w + held), 0.02, 1.0);
    // Blend in a tonemapped space so a single bright sample does not flicker.
    float wc = alpha / (1.0 + dot(current, vec3(0.299, 0.587, 0.114)));
    float wh = (1.0 - alpha) / (1.0 + dot(history, vec3(0.299, 0.587, 0.114)));
    fragColor = vec4((current * wc + history * wh) / (wc + wh), ALPHA_SCALE * min(held + w, MAX_FRAMES));
}
