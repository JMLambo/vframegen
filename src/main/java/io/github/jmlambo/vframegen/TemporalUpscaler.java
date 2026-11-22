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
 * displayed image. Shader packs have their own temporal anti-aliasing and jitter; with one on,
 * this falls back to the edge-adaptive upscaler.
 */
public final class TemporalUpscaler {
    private static final int UBO_SIZE = 64 * 2 + 16 * 4;
    // Halton (2, 3): well spread sub-pixel positions, 8 per cycle.
    private static final float[][] JITTER = new float[8][2];
    static {
        for (int i = 0; i < 8; i++) {
            JITTER[i][0] = halton(i + 1, 2) - 0.5f;
            JITTER[i][1] = halton(i + 1, 3) - 0.5f;
        }
    }

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

    private static GpuTexture depthCopy, handCopy;
    private static GpuTextureView depthView, handView;
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
        RenderTarget main = gr.mainRenderTarget();
        lowW = main.width;
        lowH = main.height;
        frameIndex = (frameIndex + 1) & 7;
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
        if (!jittered) return projection;
        Matrix4f plain = jitterMatrix(new Matrix4f(), -1f).mul(projection);
        curProj.set(plain);
        curProjCaptured = true;
        return plain;
    }

    /** Before the held item: the world's depth. */
    public static void captureDepth(GameRenderer gr) {
        if (!jittered) return;
        GpuTexture src = gr.mainRenderTarget().getDepthTexture();
        if (src == null) return;
        ensureLow(src);
        depthCaptured = copy(src, depthCopy);
    }

    /** After the held item: its depth, in the HUD target or the main depth buffer. */
    public static void captureHand(GameRenderer gr, boolean usedHudTarget) {
        if (!jittered || !depthCaptured) return;
        RenderTarget hud = ((GameRendererAccessor) gr).vframegen$hud3DTarget();
        RenderTarget target = usedHudTarget && hud != null ? hud : gr.mainRenderTarget();
        GpuTexture src = target.getDepthTexture();
        if (src == null || src.getFormat() != handCopy.getFormat()) return;
        handCaptured = copy(src, handCopy);
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
            boolean zeroToOne = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
            boolean reversedZ = ndcDepth(curProj, 1f) > ndcDepth(curProj, 100f);

            int src = current, dst = current ^ 1;
            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer data = Std140Builder.onStack(stack, UBO_SIZE)
                        .putMat4f(curInv)
                        .putMat4f(prev)
                        .putVec4(lowW, lowH, 1f / lowW, 1f / lowH)
                        .putVec4(fullW, fullH, 1f / fullW, 1f / fullH)
                        .putVec4(jitterX, jitterY, reset ? 1f : 0f, handCaptured ? 1f : 0f)
                        .putVec4(reversedZ ? 1f : 0f, zeroToOne ? 1f : 0f, 0f, 0f)
                        .get();
                encoder.writeToBuffer(ubo.slice(), data);
            }
            GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            GpuSampler linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
            try (RenderPass pass = encoder.createRenderPass(() -> "VFrameGen TAAU", historyView[dst], Optional.empty())) {
                pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline(colorFormat)));
                pass.setUniform("Color", colorLow, nearest);
                pass.setUniform("Depth", depthView, nearest);
                pass.setUniform("Hand", handCaptured ? handView : depthView, nearest);
                pass.setUniform("History", historyView[src], linear);
                pass.setUniform("Taau", ubo);
                pass.draw(3, 1, 0, 0);
            }
            current = dst;
            prevViewProj.set(viewProj);
            prevPos.set(pos);
            prevLevel = level;
            hasPrev = true;
            ran = true;
            jittered = false; // once per world frame (GUI-only frames call the upscaler too)
            return historyView[dst];
        } catch (RuntimeException e) {
            broken = true;
            VFrameGen.LOGGER.error("Temporal upscaling failed; using the edge-adaptive upscaler for this session", e);
            return null;
        }
    }

    /** The resolved texture (after {@link #resolve} returned non-null). */
    public static GpuTexture resolvedTexture() {
        return history[current];
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
        if (depthCopy != null && depthCopy.getWidth(0) == w && depthCopy.getHeight(0) == h
                && depthCopy.getFormat() == depthSrc.getFormat()) return;
        closeLow();
        GpuDevice device = RenderSystem.getDevice();
        int usage = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING;
        depthCopy = device.createTexture("VFrameGen TAAU depth", usage, depthSrc.getFormat(), w, h, 1, 1);
        handCopy = device.createTexture("VFrameGen TAAU hand depth", usage, depthSrc.getFormat(), w, h, 1, 1);
        depthView = device.createTextureView(depthCopy);
        handView = device.createTextureView(handCopy);
    }

    private static void closeLow() {
        if (depthView != null) depthView.close();
        if (handView != null) handView.close();
        if (depthCopy != null) depthCopy.close();
        if (handCopy != null) handCopy.close();
        depthView = handView = null;
        depthCopy = handCopy = null;
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
                        .withUniform("Hand", UniformType.COMBINED_IMAGE_SAMPLER)
                        .withUniform("History", UniformType.COMBINED_IMAGE_SAMPLER)
                        .withUniform("Taau", UniformType.UNIFORM_BUFFER)
                        .build())
                .withColorTargetState(new ColorTargetState(Optional.empty(), f, ColorTargetState.WRITE_ALL))
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .build());
    }
}
