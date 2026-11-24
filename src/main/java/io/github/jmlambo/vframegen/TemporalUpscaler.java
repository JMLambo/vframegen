package io.github.jmlambo.vframegen;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import io.github.jmlambo.vframegen.mixin.GameRendererAccessor;
import io.github.jmlambo.vframegen.mixin.RenderSystemAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Temporal upscaling (TAAU, upscaler TEMPORAL; shaders/core/taau.fsh): the world renders with a
 * sub-pixel shift that changes every frame, and a full-resolution history accumulates the
 * samples. At 100% render scale it is temporal anti-aliasing.
 *
 * <p>Per frame: {@link #beginLevel} shifts the camera projection (restored in {@link #endLevel}),
 * {@link #captureDepth} and {@link #captureHand} copy the world's depth (before the held item) and
 * the held item's depth, and {@link #resolve} writes the new history, which then becomes the
 * displayed image. The history follows the camera and the nearest moving entities. Shader packs
 * have their own temporal anti-aliasing and jitter; with one on, this falls back to the
 * edge-adaptive upscaler.
 */
public final class TemporalUpscaler {
    // Moving entities whose motion the history follows (nearest first).
    static final int MAX_ENTITIES = 16;
    private static final int UBO_SIZE = 64 * 3 + 16 * 5 + 16 * 3 * MAX_ENTITIES;
    // Halton (2, 3) sub-pixel positions. The cycle is longer at lower render scales (as in FSR 2:
    // 8 per output pixel's share of a low-res pixel), so every output pixel gets several samples.
    private static final int MAX_PHASES = 32;
    private static final float[][] JITTER = new float[MAX_PHASES][2];
    static {
        for (int i = 0; i < MAX_PHASES; i++) {
            JITTER[i][0] = halton(i + 1, 2) - 0.5f;
            JITTER[i][1] = halton(i + 1, 3) - 0.5f;
        }
    }

    // This frame's moving entities (relative to the camera: box, motion since the last frame) and
    // the last frame's positions by id, to measure that motion.
    private static int entityCount;
    private static final float[] entityData = new float[MAX_ENTITIES * 9];
    private static it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<double[]> prevEntityPos = new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>();
    private static it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<double[]> nextEntityPos = new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>();
    // Position records, reused from frame to frame.
    private static final java.util.ArrayDeque<double[]> spare = new java.util.ArrayDeque<>();

    private static boolean jittered;   // this frame's projection is shifted
    private static boolean ran;        // this frame was resolved
    private static int frameIndex;
    private static float jitterX, jitterY;
    private static int lowW, lowH;
    private static final Matrix4f savedProjection = new Matrix4f();
    private static CameraRenderState jitteredCamera;
    private static final Matrix4f curProj = new Matrix4f();
    private static boolean curProjCaptured;
    private static final Matrix4f prevViewProj = new Matrix4f();
    private static final Vector3d prevPos = new Vector3d();
    private static boolean hasPrev;
    private static Level prevLevel;

    // World depth, this frame's and the last one's (to reject history of surfaces that were hidden).
    private static final GpuTexture[] depthCopies = new GpuTexture[2];
    private static final GpuTextureView[] depthViews = new GpuTextureView[2];
    private static int depthIndex;
    private static boolean prevDepthValid;
    private static final Matrix4f prevInvViewProj = new Matrix4f();
    private static GpuTexture handCopy;
    private static GpuTextureView handView;
    // The projection hook has run at least once: until then nothing jitters (a jitter without the
    // capture that removes it again would just shift the picture).
    private static boolean projectionHookSeen;
    private static boolean depthCaptured, handCaptured;
    private static GpuTexture[] history = new GpuTexture[2];
    private static GpuTextureView[] historyView = new GpuTextureView[2];
    private static int current;
    private static int histW = -1, histH = -1;
    private static GpuFormat histFormat;
    private static GpuBuffer ubo;
    private static final Map<GpuFormat, RenderPipeline> pipelines = new HashMap<>();
    private static boolean broken;

    private TemporalUpscaler() {
    }

    private static float halton(int index, int base) {
        float f = 1f, r = 0f;
        while (index > 0) {
            f /= base;
            r += f * (index % base);
            index /= base;
        }
        return r;
    }

    // Mip bias of the terrain sampler: log2(render scale) while temporal upscaling runs, so the
    // world picks the texture detail of the full resolution. Applied when the sampler is made
    // (backend mixins read pendingMipBias then); it is made again when the bias changes.
    private static float wantedBias, appliedBias, pendingBias;

    public static float pendingMipBias() {
        return pendingBias;
    }

    public static void beginTerrainSampler() {
        pendingBias = wantedBias;
    }

    public static void endTerrainSampler() {
        appliedBias = pendingBias;
        pendingBias = 0f;
    }

    public static boolean terrainSamplerStale() {
        return Math.abs(wantedBias - appliedBias) > 1.0e-3;
    }

    /** Whether temporal upscaling is selected and possible now. */
    public static boolean wanted() {
        return !broken && VFrameGen.config().upscaler == FrameGenConfig.Upscaler.TEMPORAL
                && !Compat.irisShadersActive()
                && RenderSystemAccessor.vframegen$getCurrentPipelineCache() != null;
    }

    /** This frame was resolved (anti-aliased): FXAA/SMAA are not needed on top. */
    public static boolean ranThisFrame() {
        return ran;
    }

    /** Start of the world: shift the projection by this frame's sub-pixel offset. */
    public static void beginLevel(GameRenderer gr) {
        ran = false;
        jittered = false;
        depthCaptured = handCaptured = curProjCaptured = false;
        if (!wanted()) {
            hasPrev = false;
            wantedBias = 0f;
            return;
        }
        double scale = RenderScale.scale();
        // A little less than the full log2: a touch of extra sharpness would shimmer.
        wantedBias = scale < 0.999 ? (float) (Math.log(scale) / Math.log(2) * 0.9) : 0f;
        if (!projectionHookSeen) return;
        RenderTarget main = gr.mainRenderTarget();
        lowW = main.width;
        lowH = main.height;
        int phases = (int) Math.clamp(Math.ceil(8.0 / (scale * scale)), 8, MAX_PHASES);
        frameIndex = (frameIndex + 1) % phases;
        jitterX = JITTER[frameIndex][0];
        jitterY = JITTER[frameIndex][1];
        CameraRenderState cam = gr.gameRenderState().levelRenderState.cameraRenderState;
        savedProjection.set(cam.projectionMatrix);
        cam.projectionMatrix.set(jitterMatrix(new Matrix4f(), 1f).mul(savedProjection));
        jitteredCamera = cam;
        jittered = true;
    }

    /** End of the world: the camera's own projection back (it is read later, unshifted). */
    public static void endLevel() {
        if (jitteredCamera != null) {
            jitteredCamera.projectionMatrix.set(savedProjection);
            jitteredCamera = null;
        }
    }

    // NDC shift by the jitter (sign 1) or back (sign -1).
    private static Matrix4f jitterMatrix(Matrix4f out, float sign) {
        return out.translation(sign * 2f * jitterX / lowW, sign * 2f * jitterY / lowH, 0f);
    }

    /** The world's projection as uploaded (shifted, with view bobbing): keep it, unshifted. */
    public static Matrix4f captureProjection(Matrix4f projection) {
        projectionHookSeen = true;
        if (!jittered) return projection;
        Matrix4f plain = jitterMatrix(new Matrix4f(), -1f).mul(projection);
        curProj.set(plain);
        curProjCaptured = true;
        return plain;
    }

    /** Before the held item: the world's depth. */
    public static void captureDepth(GameRenderer gr) {
        if (!jittered) return;
        try {
            GpuTexture src = gr.mainRenderTarget().getDepthTexture();
            if (src == null) return;
            ensureLow(src);
            depthIndex ^= 1;
            depthCaptured = copy(src, depthCopies[depthIndex]);
        } catch (RuntimeException e) {
            disable(e);
        }
    }

    /** After the held item: its depth, in the HUD target or the main depth buffer. */
    public static void captureHand(GameRenderer gr, boolean usedHudTarget) {
        if (!jittered || !depthCaptured) return;
        try {
            RenderTarget hud = ((GameRendererAccessor) gr).vframegen$hud3DTarget();
            RenderTarget target = usedHudTarget && hud != null ? hud : gr.mainRenderTarget();
            GpuTexture src = target.getDepthTexture();
            if (src == null || src.getFormat() != handCopy.getFormat()) return;
            handCaptured = copy(src, handCopy);
        } catch (RuntimeException e) {
            disable(e);
        }
    }

    /** Off for the rest of the session (the spatial upscalers take over), with nothing half-made left. */
    public static void disable(RuntimeException e) {
        if (!broken) VFrameGen.LOGGER.error("Temporal upscaling failed; using the edge-adaptive upscaler for this session", e);
        broken = true;
        jittered = false;
        depthCaptured = handCaptured = curProjCaptured = false;
        endLevel();
        closeLow();
    }

    /**
     * Resolve this frame into the history at (fullW, fullH) and return the view of it, or null
     * if it can't run this frame (then the caller upscales as usual).
     */
    public static GpuTextureView resolve(GameRenderer gr, GpuTextureView colorLow, GpuFormat colorFormat, int fullW, int fullH) {
        if (!jittered || !depthCaptured || !curProjCaptured) {
            jittered = false;
            return null;
        }
        try {
            boolean reset = ensureHistory(fullW, fullH, colorFormat);
            CameraRenderState cam = gr.gameRenderState().levelRenderState.cameraRenderState;
            Matrix4f viewProj = new Matrix4f(curProj).mul(cam.viewRotationMatrix);
            Vector3d pos = new Vector3d(cam.pos.x, cam.pos.y, cam.pos.z);
            Level level = Minecraft.getInstance().level;
            if (!hasPrev || level != prevLevel || pos.distance(prevPos) > 8.0) reset = true;
            Matrix4f prev = new Matrix4f(prevViewProj).translate(
                    (float) (pos.x - prevPos.x), (float) (pos.y - prevPos.y), (float) (pos.z - prevPos.z));
            Matrix4f curInv = new Matrix4f(viewProj).invert();
            boolean usePrevDepth = prevDepthValid && !reset;
            boolean zeroToOne = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
            boolean reversedZ = ndcDepth(curProj, 1f) > ndcDepth(curProj, 100f);
            captureEntities(cam, reset);

            int src = current, dst = current ^ 1;
            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                Std140Builder builder = Std140Builder.onStack(stack, UBO_SIZE)
                        .putMat4f(curInv)
                        .putMat4f(prev)
                        .putMat4f(prevInvViewProj)
                        .putVec4(lowW, lowH, 1f / lowW, 1f / lowH)
                        .putVec4(fullW, fullH, 1f / fullW, 1f / fullH)
                        .putVec4(jitterX, jitterY, reset ? 1f : 0f, handCaptured ? 1f : 0f)
                        .putVec4(reversedZ ? 1f : 0f, zeroToOne ? 1f : 0f, usePrevDepth ? 1f : 0f, entityCount)
                        .putVec4((float) (pos.x - prevPos.x), (float) (pos.y - prevPos.y), (float) (pos.z - prevPos.z), 0f);
                for (int i = 0; i < MAX_ENTITIES * 3; i++) {
                    builder.putVec4(entityData[i * 3], entityData[i * 3 + 1], entityData[i * 3 + 2], 0f);
                }
                ByteBuffer data = builder.get();
                encoder.writeToBuffer(ubo.slice(), data);
            }
            GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            GpuSampler linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
            try (RenderPass pass = encoder.createRenderPass(() -> "VFrameGen TAAU", historyView[dst], Optional.empty())) {
                pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline(colorFormat)));
                pass.setUniform("Color", colorLow, linear);
                pass.setUniform("Depth", depthViews[depthIndex], nearest);
                pass.setUniform("PrevDepth", depthViews[depthIndex ^ 1], nearest);
                pass.setUniform("Hand", handCaptured ? handView : depthViews[depthIndex], nearest);
                pass.setUniform("History", historyView[src], linear);
                pass.setUniform("Taau", ubo);
                pass.draw(3, 1, 0, 0);
            }
            current = dst;
            prevViewProj.set(viewProj);
            prevInvViewProj.set(curInv);
            prevDepthValid = true;
            prevPos.set(pos);
            prevLevel = level;
            hasPrev = true;
            ran = true;
            jittered = false; // once per world frame (GUI-only frames call the upscaler too)
            return historyView[dst];
        } catch (RuntimeException e) {
            disable(e);
            return null;
        }
    }

    private static final net.minecraft.world.entity.Entity[] near = new net.minecraft.world.entity.Entity[MAX_ENTITIES];
    private static final double[] nearDist = new double[MAX_ENTITIES];

    /**
     * The nearest entities that moved since the last frame: their box (padded hitbox, relative to
     * the camera) and that motion. The shader decides per pixel whether it shows the entity or what
     * is around it (whichever matches the last frame's depth), so the box can be loose.
     */
    private static void captureEntities(CameraRenderState cam, boolean reset) {
        entityCount = 0;
        nextEntityPos.clear();
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            var ticks = mc.level.tickRateManager();
            var delta = mc.getDeltaTracker();
            net.minecraft.world.entity.Entity camera = mc.getCameraEntity();
            int n = 0;
            for (net.minecraft.world.entity.Entity e : mc.level.entitiesForRendering()) {
                if (e == camera && cam.isFirstPerson) continue;
                // The partial tick its renderer uses (frozen entities stay put during /tick freeze).
                float pt = delta.getGameTimeDeltaPartialTick(!ticks.isEntityFrozen(e));
                double x = net.minecraft.util.Mth.lerp(pt, e.xo, e.getX());
                double y = net.minecraft.util.Mth.lerp(pt, e.yo, e.getY());
                double z = net.minecraft.util.Mth.lerp(pt, e.zo, e.getZ());
                double dx = x - cam.pos.x, dy = y - cam.pos.y, dz = z - cam.pos.z;
                double d2 = dx * dx + dy * dy + dz * dz;
                if (d2 > 96.0 * 96.0) continue;
                double[] rec = spare.isEmpty() ? new double[3] : spare.pop();
                rec[0] = x;
                rec[1] = y;
                rec[2] = z;
                nextEntityPos.put(e.getId(), rec);
                double[] before = prevEntityPos.get(e.getId());
                if (reset || before == null) continue;
                double mx = x - before[0], my = y - before[1], mz = z - before[2];
                double m2 = mx * mx + my * my + mz * mz;
                if (m2 < 1.0e-8 || m2 > 64.0) continue;
                if (n == MAX_ENTITIES && d2 >= nearDist[n - 1]) continue;
                int k = n < MAX_ENTITIES ? n++ : n - 1;
                while (k > 0 && nearDist[k - 1] > d2) {
                    nearDist[k] = nearDist[k - 1];
                    near[k] = near[k - 1];
                    k--;
                }
                nearDist[k] = d2;
                near[k] = e;
            }
            for (int k = 0; k < n; k++) {
                net.minecraft.world.entity.Entity e = near[k];
                near[k] = null;
                double[] now = nextEntityPos.get(e.getId());
                double[] before = prevEntityPos.get(e.getId());
                // Models reach past the hitbox (heads, arms, model mods): padded.
                net.minecraft.world.phys.AABB hit = e.getBoundingBox().move(-e.getX(), -e.getY(), -e.getZ());
                float ox = (float) (now[0] - cam.pos.x), oy = (float) (now[1] - cam.pos.y), oz = (float) (now[2] - cam.pos.z);
                int j = entityCount++ * 9;
                entityData[j] = ox + (float) hit.minX - 0.35f;
                entityData[j + 1] = oy + (float) hit.minY - 0.05f;
                entityData[j + 2] = oz + (float) hit.minZ - 0.35f;
                entityData[j + 3] = ox + (float) hit.maxX + 0.35f;
                entityData[j + 4] = oy + (float) hit.maxY + 0.4f;
                entityData[j + 5] = oz + (float) hit.maxZ + 0.35f;
                entityData[j + 6] = (float) (now[0] - before[0]);
                entityData[j + 7] = (float) (now[1] - before[1]);
                entityData[j + 8] = (float) (now[2] - before[2]);
            }
        }
        java.util.Arrays.fill(entityData, entityCount * 9, entityData.length, 0f);
        spare.addAll(prevEntityPos.values());
        prevEntityPos.clear();
        var swap = prevEntityPos;
        prevEntityPos = nextEntityPos;
        nextEntityPos = swap;
    }

    private static float ndcDepth(Matrix4f proj, float distance) {
        Vector4f v = proj.transform(new Vector4f(0f, 0f, -distance, 1f));
        return v.z / v.w;
    }

    private static boolean copy(GpuTexture src, GpuTexture dst) {
        if (src.getWidth(0) != dst.getWidth(0) || src.getHeight(0) != dst.getHeight(0)) return false;
        RenderSystem.getDevice().createCommandEncoder()
                .copyTextureToTexture(src, dst, 0, 0, 0, 0, 0, dst.getWidth(0), dst.getHeight(0));
        return true;
    }

    private static void ensureLow(GpuTexture depthSrc) {
        int w = depthSrc.getWidth(0), h = depthSrc.getHeight(0);
        GpuTexture d = depthCopies[0];
        if (d != null && d.getWidth(0) == w && d.getHeight(0) == h && d.getFormat() == depthSrc.getFormat()) return;
        closeLow();
        GpuDevice device = RenderSystem.getDevice();
        int usage = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING;
        for (int i = 0; i < 2; i++) {
            depthCopies[i] = device.createTexture("VFrameGen TAAU depth " + i, usage, depthSrc.getFormat(), w, h, 1, 1);
            depthViews[i] = device.createTextureView(depthCopies[i]);
        }
        handCopy = device.createTexture("VFrameGen TAAU hand depth", usage, depthSrc.getFormat(), w, h, 1, 1);
        handView = device.createTextureView(handCopy);
    }

    private static void closeLow() {
        for (int i = 0; i < 2; i++) {
            if (depthViews[i] != null) depthViews[i].close();
            if (depthCopies[i] != null) depthCopies[i].close();
            depthViews[i] = null;
            depthCopies[i] = null;
        }
        if (handView != null) handView.close();
        if (handCopy != null) handCopy.close();
        handView = null;
        handCopy = null;
        prevDepthValid = false;
    }

    /** True when the history was (re)created: nothing to accumulate yet. */
    private static boolean ensureHistory(int w, int h, GpuFormat format) {
        if (w == histW && h == histH && format == histFormat && history[0] != null) return false;
        for (int i = 0; i < 2; i++) {
            if (historyView[i] != null) historyView[i].close();
            if (history[i] != null) history[i].close();
        }
        GpuDevice device = RenderSystem.getDevice();
        int usage = GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING
                | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST;
        for (int i = 0; i < 2; i++) {
            history[i] = device.createTexture("VFrameGen TAAU history " + i, usage, format, w, h, 1, 1);
            historyView[i] = device.createTextureView(history[i]);
        }
        if (ubo == null) {
            ubo = device.createBuffer(() -> "VFrameGen TAAU uniforms", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, UBO_SIZE);
        }
        histW = w;
        histH = h;
        histFormat = format;
        hasPrev = false;
        return true;
    }

    private static RenderPipeline pipeline(GpuFormat format) {
        return pipelines.computeIfAbsent(format, f -> RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath(VFrameGen.MOD_ID, "pipeline/taau_" + f.name().toLowerCase(Locale.ROOT)))
                .withVertexShader("core/screenquad")
                .withFragmentShader(Identifier.fromNamespaceAndPath(VFrameGen.MOD_ID, "core/taau"))
                .withBindGroupLayout(BindGroupLayout.builder()
                        .withUniform("Color", UniformType.COMBINED_IMAGE_SAMPLER)
                        .withUniform("Depth", UniformType.COMBINED_IMAGE_SAMPLER)
                        .withUniform("PrevDepth", UniformType.COMBINED_IMAGE_SAMPLER)
                        .withUniform("Hand", UniformType.COMBINED_IMAGE_SAMPLER)
                        .withUniform("History", UniformType.COMBINED_IMAGE_SAMPLER)
                        .withUniform("Taau", UniformType.UNIFORM_BUFFER)
                        .build())
                .withColorTargetState(new ColorTargetState(Optional.empty(), f, ColorTargetState.WRITE_ALL))
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .build());
    }
}
