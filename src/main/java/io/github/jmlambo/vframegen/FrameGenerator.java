package io.github.jmlambo.vframegen;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.device.SurfaceException;
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
import io.github.jmlambo.vframegen.mixin.MinecraftAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.LockSupport;

/**
 * Generates one extra frame between every two real frames by reprojecting the previous real frame.
 *
 * <p>Everything goes through Mojang's renderpearl API (textures, a render pipeline, the window
 * surface), so the same code runs on the Vulkan and the OpenGL backend. All methods run on the
 * render thread, called from the mixins in {@code io.github.jmlambo.vframegen.mixin}.
 *
 * <p>Per game loop, in order:
 * <ol>
 *   <li>{@link #beforeGameRender}: the camera for the coming real frame has just been extracted.
 *       If there is a usable previous frame, warp it to a camera pose between that frame and this
 *       one, present it, and acquire a fresh swapchain image for the real frame.</li>
 *   <li>{@link #captureProjection}, {@link #captureWorldDepth}, {@link #captureHandDepth},
 *       {@link #captureWorld}: during the real frame, copy what the next generated frame needs.</li>
 *   <li>{@link #captureFinal}: right before the real frame goes to the window, copy it with the GUI.</li>
 *   <li>{@link #afterRealPresent}: timing.</li>
 * </ol>
 */
public final class FrameGenerator {
    public static final FrameGenerator INSTANCE = new FrameGenerator();

    private static final int UBO_SIZE = 4 * 64 + 3 * 16;

    // --- GPU resources --------------------------------------------------------------------------
    private int width = -1;
    private int height = -1;
    private GpuFormat colorFormat;
    private GpuFormat depthFormat;
    private GpuTexture worldTex, noHandTex, depthTex, handDepthTex, finalTex, outTex;
    private GpuTextureView worldView, noHandView, depthView, handDepthView, finalView, outView;
    private GpuBuffer ubo;
    private final Map<GpuFormat, RenderPipeline> pipelines = new EnumMap<>(GpuFormat.class);
    private boolean broken;

    // --- previous real frame ("history") --------------------------------------------------------
    private boolean histValid;
    private boolean histHasFinal;
    private boolean histHasHand;
    private boolean histDepthUsable;
    private final Vector3d histPos = new Vector3d();
    private final Matrix4f histViewRot = new Matrix4f();
    private final Matrix4f histProj = new Matrix4f();
    private long histExtractNs;
    private ClientLevel histLevel;

    // --- the real frame being rendered right now ------------------------------------------------
    private long frameExtractNs;
    private boolean frameProjCaptured;
    private final Matrix4f frameProj = new Matrix4f();
    private boolean frameWorldDepthCaptured;
    private boolean frameHandCaptured;
    private boolean frameWorldCaptured;

    // --- timing ---------------------------------------------------------------------------------
    private long lastRealPresentNs;
    private double emaLoopNs;
    private double emaLatencyNs;
    private long blockedNs;
    private long lastLoopBlockedNs;
    private long blockStartNs;
    private long statsStartNs;
    private int statsReal;
    private int statsGenerated;
    private long secStartNs;
    private int secReal;
    private int secGenerated;
    private int lastSecReal;
    private int lastSecGenerated;
    private final java.util.TreeMap<String, Integer> statsSkips = new java.util.TreeMap<>();

    // --- scratch --------------------------------------------------------------------------------
    private final Matrix4f m0 = new Matrix4f();
    private final Matrix4f m1 = new Matrix4f();
    private final Matrix4f dstProj = new Matrix4f();
    private final Matrix4f dstViewRot = new Matrix4f();
    private final Matrix4f curProj = new Matrix4f();
    private final Quaternionf q0 = new Quaternionf();
    private final Quaternionf q1 = new Quaternionf();
    private final Vector4f v4 = new Vector4f();

    private FrameGenerator() {
    }

    public String backendName() {
        GpuDevice device = RenderSystem.tryGetDevice();
        return device == null ? "?" : device.getDeviceInfo().backendName();
    }

    /** Real and generated frames shown in the last full second, for the F3 screen. */
    public int realFps() {
        return lastSecReal;
    }

    public int generatedFps() {
        return lastSecGenerated;
    }

    /** Forget the previous frame, e.g. after a toggle or a world change. */
    public void invalidate() {
        histValid = false;
        histHasFinal = false;
    }

    // =============================================================================================
    // Generation
    // =============================================================================================

    public void beforeGameRender(Minecraft mc) {
        frameExtractNs = System.nanoTime();
        frameProjCaptured = false;
        frameWorldDepthCaptured = false;
        frameHandCaptured = false;
        frameWorldCaptured = false;

        lastLoopBlockedNs = blockedNs;
        blockedNs = 0;

        if (broken || !VFrameGen.config().enabled) return;
        try {
            tryGenerate(mc);
        } catch (RuntimeException e) {
            broken = true;
            VFrameGen.LOGGER.error("Frame generation failed and has been switched off for this session", e);
        }
    }

    private void tryGenerate(Minecraft mc) {
        FrameGenConfig cfg = VFrameGen.config();
        if (!histValid || !histHasFinal) { skip(histValid ? "no-final" : "no-history"); return; }
        if (mc.level == null || mc.player == null || mc.level != histLevel) {
            invalidate();
            skip("level");
            return;
        }
        if (mc.isPaused()) { skip("paused"); return; }
        if (cfg.disableWhileScreenOpen && mc.gui.screen() != null) { skip("screen"); return; }

        GpuSurface surface = mc.windowSurface();
        if (!surface.isAcquired()) { skip("not-acquired"); return; }

        GameRenderer gr = mc.gameRenderer;
        GameRenderState state = gr.gameRenderState();
        if (!state.shouldRenderLevel) { skip("no-level"); return; }
        RenderTarget main = gr.mainRenderTarget();
        if (main.width != width || main.height != height) { skip("size"); return; }

        CameraRenderState cam = state.levelRenderState.cameraRenderState;
        PlayerRenderState player = state.levelRenderState.playerRenderState;
        if (cam == null || !cam.initialized || cam.isPanoramicMode) { skip("camera"); return; }
        if (player.nauseaEffectIntensity > 0 || player.portalEffectIntensity > 0) { skip("nausea"); return; }

        long loopNs = frameExtractNs - histExtractNs;
        if (loopNs <= 0 || loopNs > cfg.maxRealFrameAgeMs * 1_000_000L) { skip(loopNs <= 0 ? "same-frame" : "slow-frame"); return; }

        double jumpX = cam.pos.x - histPos.x, jumpY = cam.pos.y - histPos.y, jumpZ = cam.pos.z - histPos.z;
        double jump = Math.sqrt(jumpX * jumpX + jumpY * jumpY + jumpZ * jumpZ);
        if (jump > cfg.maxCameraJumpBlocks) { skip("jump"); return; }

        histViewRot.getNormalizedRotation(q0);
        cam.viewRotationMatrix.getNormalizedRotation(q1);
        double turnDeg = Math.toDegrees(2.0 * Math.acos(Math.min(1.0, Math.abs(q0.dot(q1)))));
        if (cfg.maxCameraTurnDegrees > 0 && turnDeg > cfg.maxCameraTurnDegrees) { skip("turn"); return; }

        // The projection the coming real frame will use (view bobbing included).
        currentProjection(gr, state, cam, curProj);

        pace(cfg);

        // Pose: real frames show a camera that is emaLatency old by the time they are on screen.
        // Show the generated frame with the same delay, which puts it between the two real poses.
        long now = System.nanoTime();
        double f = (now - emaLatencyNs - histExtractNs) / (double) loopNs;
        f = Math.clamp(f, 0.0, 1.0);

        Vector3d dstPos = new Vector3d(histPos).lerp(new Vector3d(cam.pos.x, cam.pos.y, cam.pos.z), f);
        q0.slerp(q1, (float) f, q1);
        dstViewRot.rotation(q1);
        lerp(histProj, curProj, (float) f, dstProj);

        renderAndPresent(mc, surface, dstPos);
    }

    private void skip(String reason) {
        statsSkips.merge(reason, 1, Integer::sum);
    }

    private void currentProjection(GameRenderer gr, GameRenderState state, CameraRenderState cam, Matrix4f out) {
        out.set(cam.projectionMatrix);
        PoseStack pose = new PoseStack();
        GameRendererAccessor acc = (GameRendererAccessor) gr;
        acc.vframegen$bobHurt(cam, pose);
        if (state.optionsRenderState.bobView) {
            acc.vframegen$bobView(cam, pose);
        }
        out.mul(pose.last().pose());
    }

    /** BALANCED pacing: wait until the middle of the real-frame interval, if waiting is free. */
    private void pace(FrameGenConfig cfg) {
        if (cfg.pacing != FrameGenConfig.Pacing.BALANCED || lastRealPresentNs == 0 || emaLoopNs <= 0) return;
        long target = lastRealPresentNs + (long) (emaLoopNs * 0.5);
        long wait = target - System.nanoTime();
        // Only wait as long as the last loop spent blocked on the GPU/swapchain anyway.
        long budget = (long) (lastLoopBlockedNs * 0.9);
        wait = Math.min(wait, budget);
        wait = Math.min(wait, (long) (emaLoopNs * 0.5));
        if (wait <= 0) return;
        long end = System.nanoTime() + wait;
        while (true) {
            long left = end - System.nanoTime();
            if (left <= 0) break;
            if (left > 1_500_000L) {
                LockSupport.parkNanos(left - 1_000_000L);
            } else {
                Thread.onSpinWait();
            }
        }
    }

    private void renderAndPresent(Minecraft mc, GpuSurface surface, Vector3d dstPos) {
        FrameGenConfig cfg = VFrameGen.config();
        GpuDevice device = RenderSystem.getDevice();
        CommandEncoder encoder = device.createCommandEncoder();
        boolean zeroToOne = device.getDeviceInfo().isZZeroToOne();

        // src: previous real frame, camera at histPos. Everything is relative to histPos.
        Matrix4f srcViewProjRot = new Matrix4f(histProj).mul(histViewRot);
        Matrix4f srcInvViewProj = new Matrix4f(srcViewProjRot).invert();
        Matrix4f dstViewProjRot = new Matrix4f(dstProj).mul(dstViewRot);
        Matrix4f dstInvViewProjRot = new Matrix4f(dstViewProjRot).invert();
        Matrix4f dstViewProj = new Matrix4f(dstViewProjRot).translate(
                (float) (histPos.x - dstPos.x), (float) (histPos.y - dstPos.y), (float) (histPos.z - dstPos.z));

        boolean reversedZ = ndcDepthAt(histProj, 1.0f) > ndcDepthAt(histProj, 100.0f);
        boolean useDepth = histDepthUsable && switch (cfg.depthMode) {
            case DEPTH -> true;
            case ROTATION_ONLY -> false;
            case AUTO -> !Compat.irisShadersActive();
        };

        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer data = Std140Builder.onStack(stack, UBO_SIZE)
                    .putMat4f(srcInvViewProj)
                    .putMat4f(dstViewProj)
                    .putMat4f(srcViewProjRot)
                    .putMat4f(dstInvViewProjRot)
                    .putVec4(histHasHand ? 1f : 0f, cfg.guiDiffThreshold, useDepth ? 1f : 0f, cfg.debugTint ? 1f : 0f)
                    .putVec4(reversedZ ? 1f : 0f, cfg.sharpness, 1f / width, 1f / height)
                    .putVec4(zeroToOne ? 1f : 0f, 0f, 0f, 0f)
                    .get();
            encoder.writeToBuffer(ubo.slice(), data);
        }

        GpuSampler linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        try (RenderPass pass = encoder.createRenderPass(() -> "VFrameGen reprojection", outView, Optional.empty())) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline(colorFormat)));
            pass.setUniform("WorldSampler", worldView, linear);
            pass.setUniform("DepthSampler", depthView, nearest);
            pass.setUniform("HandDepthSampler", handDepthView, nearest);
            pass.setUniform("FinalSampler", finalView, nearest);
            pass.setUniform("NoHandSampler", noHandView, linear);
            pass.setUniform("FrameGen", ubo);
            pass.draw(3, 1, 0, 0);
        }

        surface.blitFromTexture(encoder, outView);
        encoder.submit();
        surface.present();
        statsGenerated++;
        secGenerated++;
        // Count it in vanilla's FPS (F3 and anything that reads Minecraft.getFps()).
        MinecraftAccessor counter = (MinecraftAccessor) mc;
        counter.vframegen$setFrames(counter.vframegen$getFrames() + 1);

        try {
            surface.acquireNextTexture();
        } catch (SurfaceException e) {
            // Same as vanilla: the frame is skipped and the surface reconfigured next loop.
            ((MinecraftAccessor) mc).vframegen$setSurfaceIsInvalid(true);
            invalidate();
        }
    }

    private float ndcDepthAt(Matrix4f proj, float distance) {
        proj.transform(v4.set(0f, 0f, -distance, 1f));
        return v4.z / v4.w;
    }

    private static void lerp(Matrix4f a, Matrix4f b, float t, Matrix4f out) {
        out.set(
                a.m00() + (b.m00() - a.m00()) * t, a.m01() + (b.m01() - a.m01()) * t, a.m02() + (b.m02() - a.m02()) * t, a.m03() + (b.m03() - a.m03()) * t,
                a.m10() + (b.m10() - a.m10()) * t, a.m11() + (b.m11() - a.m11()) * t, a.m12() + (b.m12() - a.m12()) * t, a.m13() + (b.m13() - a.m13()) * t,
                a.m20() + (b.m20() - a.m20()) * t, a.m21() + (b.m21() - a.m21()) * t, a.m22() + (b.m22() - a.m22()) * t, a.m23() + (b.m23() - a.m23()) * t,
                a.m30() + (b.m30() - a.m30()) * t, a.m31() + (b.m31() - a.m31()) * t, a.m32() + (b.m32() - a.m32()) * t, a.m33() + (b.m33() - a.m33()) * t);
    }

    private RenderPipeline pipeline(GpuFormat format) {
        return pipelines.computeIfAbsent(format, fmt -> RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath(VFrameGen.MOD_ID, "pipeline/reproject_" + fmt.name().toLowerCase()))
                .withVertexShader("core/screenquad")
                .withFragmentShader(Identifier.fromNamespaceAndPath(VFrameGen.MOD_ID, "core/reproject"))
                .withBindGroupLayout(BindGroupLayout.builder()
                        .withUniform("WorldSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                        .withUniform("DepthSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                        .withUniform("HandDepthSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                        .withUniform("FinalSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                        .withUniform("NoHandSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                        .withUniform("FrameGen", UniformType.UNIFORM_BUFFER)
                        .build())
                .withColorTargetState(new ColorTargetState(Optional.empty(), fmt, ColorTargetState.WRITE_ALL))
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .build());
    }

    // =============================================================================================
    // Capture during the real frame
    // =============================================================================================

    /** The exact world projection of this real frame (view bobbing and nausea included). */
    public void captureProjection(Matrix4f proj) {
        frameProj.set(proj);
        frameProjCaptured = true;
    }

    /** Right before the held item is drawn: the depth buffer still has the whole world in it. */
    public void captureWorldDepth(GameRenderer gr) {
        if (!prepare(gr)) return;
        RenderTarget main = gr.mainRenderTarget();
        copy(main.getDepthTexture(), depthTex);
        copy(main.getColorTexture(), noHandTex);
        frameWorldDepthCaptured = true;
    }

    /** Right after the held item is drawn, into a depth buffer that was cleared for it. */
    public void captureHandDepth(GameRenderer gr, boolean usedHudTarget) {
        if (!frameWorldDepthCaptured) return;
        RenderTarget hud = ((GameRendererAccessor) gr).vframegen$hud3DTarget();
        RenderTarget target = usedHudTarget && hud != null ? hud : gr.mainRenderTarget();
        GpuTexture handDepth = target.getDepthTexture();
        if (handDepth == null || handDepth.getFormat() != depthFormat
                || handDepth.getWidth(0) != width || handDepth.getHeight(0) != height) {
            return;
        }
        copy(handDepth, handDepthTex);
        frameHandCaptured = true;
    }

    /** After the world, the held item and post effects are drawn, before the GUI. */
    public void captureWorld(GameRenderer gr) {
        if (!prepare(gr)) return;
        RenderTarget main = gr.mainRenderTarget();
        if (!frameWorldDepthCaptured) {
            // render3dHud did not run (or another mod replaced it): no separate hand pass.
            copy(main.getDepthTexture(), depthTex);
        }
        copy(main.getColorTexture(), worldTex);
        frameWorldCaptured = true;

        CameraRenderState cam = gr.gameRenderState().levelRenderState.cameraRenderState;
        histPos.set(cam.pos.x, cam.pos.y, cam.pos.z);
        histViewRot.set(cam.viewRotationMatrix);
        if (frameProjCaptured) {
            histProj.set(frameProj);
        } else {
            currentProjection(gr, gr.gameRenderState(), cam, histProj);
        }
        histExtractNs = frameExtractNs != 0 ? frameExtractNs : System.nanoTime();
        histHasHand = frameHandCaptured;
        histDepthUsable = true;
        histLevel = Minecraft.getInstance().level;
        histHasFinal = false;
        histValid = true;
    }

    /** The finished real frame, GUI included, right before it is blitted to the window. */
    public void captureFinal(Minecraft mc) {
        if (!frameWorldCaptured || broken) {
            histHasFinal = false;
            if (!frameWorldCaptured) histValid = false;
            return;
        }
        RenderTarget main = mc.gameRenderer.mainRenderTarget();
        if (main.width != width || main.height != height) {
            invalidate();
            return;
        }
        copy(main.getColorTexture(), finalTex);
        histHasFinal = true;
    }

    private boolean prepare(GameRenderer gr) {
        if (broken || !VFrameGen.config().enabled) return false;
        RenderTarget main = gr.mainRenderTarget();
        GpuTexture color = main.getColorTexture();
        GpuTexture depth = main.getDepthTexture();
        if (color == null || depth == null) return false;
        try {
            ensureResources(main.width, main.height, color.getFormat(), depth.getFormat());
            return true;
        } catch (RuntimeException e) {
            broken = true;
            VFrameGen.LOGGER.error("Could not create frame generation resources; frame generation is off for this session", e);
            return false;
        }
    }

    private void copy(GpuTexture src, GpuTexture dst) {
        RenderSystem.getDevice().createCommandEncoder()
                .copyTextureToTexture(src, dst, 0, 0, 0, 0, 0, width, height);
    }

    private void ensureResources(int w, int h, GpuFormat color, GpuFormat depth) {
        if (w == width && h == height && color == colorFormat && depth == depthFormat && worldTex != null) return;
        releaseTextures();
        GpuDevice device = RenderSystem.getDevice();
        int sampled = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING;
        worldTex = device.createTexture("VFrameGen world", sampled, color, w, h, 1, 1);
        finalTex = device.createTexture("VFrameGen final", sampled, color, w, h, 1, 1);
        noHandTex = device.createTexture("VFrameGen world without hand", sampled, color, w, h, 1, 1);
        depthTex = device.createTexture("VFrameGen depth", sampled, depth, w, h, 1, 1);
        handDepthTex = device.createTexture("VFrameGen hand depth", sampled, depth, w, h, 1, 1);
        outTex = device.createTexture("VFrameGen output",
                GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_SRC,
                color, w, h, 1, 1);
        worldView = device.createTextureView(worldTex);
        finalView = device.createTextureView(finalTex);
        noHandView = device.createTextureView(noHandTex);
        depthView = device.createTextureView(depthTex);
        handDepthView = device.createTextureView(handDepthTex);
        outView = device.createTextureView(outTex);
        if (ubo == null) {
            ubo = device.createBuffer(() -> "VFrameGen uniforms", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, UBO_SIZE);
        }
        width = w;
        height = h;
        colorFormat = color;
        depthFormat = depth;
        invalidate();
        VFrameGen.LOGGER.info("Frame generation buffers: {}x{} {} / {} on {}", w, h, color, depth, backendName());
    }

    private void releaseTextures() {
        for (GpuTextureView v : new GpuTextureView[]{worldView, noHandView, finalView, depthView, handDepthView, outView}) {
            if (v != null) v.close();
        }
        for (GpuTexture t : new GpuTexture[]{worldTex, noHandTex, finalTex, depthTex, handDepthTex, outTex}) {
            if (t != null) t.close();
        }
        worldView = noHandView = finalView = depthView = handDepthView = outView = null;
        worldTex = noHandTex = finalTex = depthTex = handDepthTex = outTex = null;
    }

    // =============================================================================================
    // Timing
    // =============================================================================================

    /** Around vanilla's acquire / submit / present: time the render thread spends waiting. */
    public void beginBlocking() {
        blockStartNs = System.nanoTime();
    }

    public void endBlocking() {
        if (blockStartNs != 0) {
            blockedNs += System.nanoTime() - blockStartNs;
            blockStartNs = 0;
        }
    }

    public void afterRealPresent() {
        long now = System.nanoTime();
        if (lastRealPresentNs != 0) {
            emaLoopNs = ema(emaLoopNs, now - lastRealPresentNs);
        }
        if (frameExtractNs != 0) {
            emaLatencyNs = ema(emaLatencyNs, now - frameExtractNs);
        }
        lastRealPresentNs = now;

        statsReal++;
        secReal++;
        if (secStartNs == 0) secStartNs = now;
        if (now - secStartNs >= 1_000_000_000L) {
            lastSecReal = secReal;
            lastSecGenerated = secGenerated;
            secReal = 0;
            secGenerated = 0;
            secStartNs = now;
        }
        if (statsStartNs == 0) statsStartNs = now;
        if (now - statsStartNs >= 5_000_000_000L) {
            double secs = (now - statsStartNs) / 1e9;
            VFrameGen.LOGGER.info("Real {} fps, generated {} fps, shown {} fps ({}){}{}",
                    Math.round(statsReal / secs), Math.round(statsGenerated / secs),
                    Math.round((statsReal + statsGenerated) / secs), backendName(),
                    statsSkips.isEmpty() ? "" : ", skipped " + statsSkips,
                    broken ? " [disabled after error]" : "");
            statsSkips.clear();
            statsStartNs = now;
            statsReal = 0;
            statsGenerated = 0;
        }
    }

    private static double ema(double avg, double sample) {
        return avg <= 0 ? sample : avg + (sample - avg) * 0.1;
    }
}
