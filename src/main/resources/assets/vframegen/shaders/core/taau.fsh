#version 330
#extension GL_ARB_separate_shader_objects : require

// VFrameGen temporal upscaling (TAAU), written for VFrameGen.
//
// Each frame the world renders at a lower resolution with the projection shifted by a fraction of
// a pixel (a different shift every frame), so successive frames sample different spots. This pass
// keeps a full-resolution history and, for every output pixel:
//   1. reconstructs the current frame's colour there from the nearby low-resolution samples,
//      weighted by how close each sample's (shifted) position is to the pixel centre;
//   2. finds where the pixel was in the last frame (depth + camera motion) and reads the history
//      there;
//   3. clips that history to the range of colours around the pixel in the current frame, so
//      anything that changed (disocclusion, moving mobs, lighting) does not ghost;
//   4. blends a little of the current frame in: more where a sample landed close to the pixel.
// Over a few frames the history fills in detail at full resolution. At 100% render scale it is
// plain temporal anti-aliasing.

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
    vec4 Flags;          // x: reversed Z, y: depth zero-to-one, z: PrevDepth valid
    vec4 CamDelta;       // xyz: this camera minus the last one
};

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
    if (Jitter.z > 0.5) {
        fragColor = vec4(current, 0.0);
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
        prevClip = PrevViewProj * vec4(here, 1.0);
    }
    if (prevClip.w <= 1.0e-6) {
        fragColor = vec4(current, 0.0);
        return;
    }
    vec2 prevUv = (prevClip.xy / prevClip.w) * 0.5 + 0.5;
    if (any(lessThan(prevUv, vec2(0.0))) || any(greaterThan(prevUv, vec2(1.0)))) {
        fragColor = vec4(current, 0.0);
        return;
    }
    // The held item was there last frame: its colour is not the world's.
    if (texture(History, prevUv).a > 0.01) {
        fragColor = vec4(current, 0.0);
        return;
    }
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
            fragColor = vec4(current, 0.0);
            return;
        }
    }

    vec3 history = sampleHistory(prevUv);
    vec3 mean = m1 / max(taps, 1.0);
    vec3 sigma = sqrt(max(m2 / max(taps, 1.0) - mean * mean, vec3(0.0)));
    vec3 lo = mean - 1.25 * sigma, hi = mean + 1.25 * sigma;
    history = fromYCoCg(clipToBox(toYCoCg(history), lo, hi));

    // Each frame adds its sample nearest the pixel, by how near it is: averaging all the samples
    // around (the filtered colour above, used only for new pixels) would blur the textures.
    current = nearest;
    vec2 motionPx = (uv - prevUv) * FullSize.xy;
    float motion = clamp(length(motionPx) / 16.0, 0.0, 1.0);
    float alpha = clamp(0.25 * wmax + motion * 0.15, 0.02, 1.0);
    // Blend in a tonemapped space so a single bright sample does not flicker.
    float wc = alpha / (1.0 + dot(current, vec3(0.299, 0.587, 0.114)));
    float wh = (1.0 - alpha) / (1.0 + dot(history, vec3(0.299, 0.587, 0.114)));
    fragColor = vec4((current * wc + history * wh) / (wc + wh), 0.0);
}
