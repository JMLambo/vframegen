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
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.locks.LockSupport;

/**
 * Shows generated frames between real ones, built by reprojecting real frames.
 *
 * <p>Everything goes through Mojang's renderpearl API (textures, a render pipeline, the window
 * surface), so the same code runs on the Vulkan and the OpenGL backend. All methods run on the
 * render thread, called from the mixins in {@code io.github.jmlambo.vframegen.mixin}.
 *
 * <p><b>Interpolated mode</b> (default). Each real frame is held back for one game loop. During the
 * next loop, while the game works on the frame after, the frames between the one on screen (A)
 * and the held one (B) are built from both and shown at checkpoints spread through the loop; at
 * the end of the loop B is shown in place of the frame vanilla just finished, which is held next.
 * Needs three slots: A, B, and C, the frame being rendered.
 *
 * <p><b>Low-latency mode</b>. Nothing is held. Right after the camera for the coming real frame is
 * known, the last real frame is warped towards it and shown, then the real frame renders.
 */
public final class FrameGenerator {
    public static final FrameGenerator INSTANCE = new FrameGenerator();

    /** With a shader pack, anything nearer than this (blocks) is taken to be the held item. */
    private static final float LOCKED_DISTANCE = 0.5f;
    private static final int UBO_SIZE = 7 * 64 + 5 * 16;

    /** Points in the game loop where generated frames can be shown. */
    public enum Checkpoint {
        LOOP_START, AFTER_EXTRACT, AFTER_FEATURES, BEFORE_FRAME_GRAPH, AFTER_FRAME_GRAPH, AFTER_LEVEL, BEFORE_GUI, BEFORE_BLIT
    }

    /** What vanilla's blit + present show at the end of this loop. */
    public enum Present { VANILLA, HELD, NOTHING }

    // --- GPU resources --------------------------------------------------------------------------
    private int width = -1;
    private int height = -1;
    private GpuFormat colorFormat;
    private GpuFormat depthFormat;
    private final FrameSlot[] slots = {new FrameSlot("slot 0"), new FrameSlot("slot 1"), new FrameSlot("slot 2")};
    private GpuTexture outTex;
    private GpuTextureView outView;
    private GpuBuffer ubo;
    private final Map<String, RenderPipeline> pipelines = new HashMap<>();
    private boolean broken;

    // --- which slot is what -----------------------------------------------------------------------
    private FrameSlot shown;     // A: last real frame on screen
    private FrameSlot held;      // B: next real frame, not shown yet (interpolated mode)
    private FrameSlot building;  // C: the frame being rendered in this loop

    // --- the real frame being rendered right now ------------------------------------------------
    private long frameExtractNs;
    private boolean frameProjCaptured;
    private final Matrix4f frameProj = new Matrix4f();
    private boolean frameWorldDepthCaptured;
    private boolean frameHandCaptured;
    private boolean frameWorldCaptured;
    private Present present = Present.VANILLA;

    // --- interpolation within this loop -------------------------------------------------------------
    private boolean interpActive;
    private int nextFrame;                 // next generated frame (1..M-1) to show this loop
    private int frames;                    // M, the multiplier, fixed for the loop
    // Where each checkpoint usually falls in the loop (0..1), and the last loop it was reached in:
    // a checkpoint that another mod made unreachable is not waited for.
    private final double[] checkpointFrac = {0.0, 0.3, 0.4, 0.45, 0.75, 0.8, 0.85, 1.0};
    private final long[] checkpointSeen = new long[Checkpoint.values().length];
    private long loopCount;
    private long waitBudgetNs;
    private boolean capture;

    // --- timing ---------------------------------------------------------------------------------
    private long lastRealPresentNs;
    private double emaLoopNs;
    private long blockedNs;
    private long lastLoopBlockedNs;
    private long blockStartNs;
    private long lastAnyPresentNs;
    private double gapSumMs;
    private double gapMaxMs;
    private int gapCount;
    private long statsStartNs;
    private int statsReal;
    private int statsGenerated;
    private long secStartNs;
    private int secReal;
    private int secGenerated;
    private int lastSecReal;
    private int lastSecGenerated;
    private final TreeMap<String, Integer> statsSkips = new TreeMap<>();
    private final StringBuilder captureLog = new StringBuilder();
    private boolean debugView;

    // --- scratch --------------------------------------------------------------------------------
    private final Matrix4f dstProj = new Matrix4f();
    private final Matrix4f dstViewRot = new Matrix4f();
    private final Matrix4f curProj = new Matrix4f();
    private final Quaternionf q0 = new Quaternionf();
    private final Quaternionf q1 = new Quaternionf();
    private final Quaternionf q2 = new Quaternionf();
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

    /** Forget all kept frames, e.g. after a toggle, a settings change or a world change. */
    public void invalidate() {
        for (FrameSlot s : slots) {
            s.valid = false;
            s.hasFinal = false;
        }
        shown = null;
        held = null;
        building = null;
        interpActive = false;
    }

    private boolean interpolated() {
        return VFrameGen.config().mode == FrameGenConfig.Mode.INTERPOLATED;
    }

    private boolean active() {
        return !broken && VFrameGen.config().enabled;
    }

    private void fail(RuntimeException e) {
        broken = true;
        invalidate();
        VFrameGen.LOGGER.error("Frame generation failed and has been switched off for this session", e);
    }

    // =============================================================================================
    // Game loop hooks
    // =============================================================================================

    /** Start of a game loop (the swapchain image for it is already acquired). */
    public void loopStart(Minecraft mc) {
        frameProjCaptured = false;
        frameWorldDepthCaptured = false;
        frameHandCaptured = false;
        frameWorldCaptured = false;
        building = null;
        lastLoopBlockedNs = blockedNs;
        blockedNs = 0;
        interpActive = false;
        nextFrame = 1;
        loopCount++;
        if (!active() || !interpolated()) return;
        try {
            interpActive = canInterpolate(mc);
            frames = Math.clamp(VFrameGen.config().multiplier, 2, FrameGenConfig.MAX_MULTIPLIER);
            waitBudgetNs = (long) (lastLoopBlockedNs * 0.9);
            if (interpActive && DebugCapture.begin()) {
                capture = true;
                captureLog.setLength(0);
                captureLog.append(String.format(Locale.ROOT,
                        "mode=interpolated multiplier=%d emaLoopMs=%.2f blockedMs=%.2f A->B ms=%.2f iris=%s handA=%s%n",
                        frames, emaLoopNs / 1e6, lastLoopBlockedNs / 1e6, (held.extractNs - shown.extractNs) / 1e6,
                        Compat.irisShadersActive(), shown.hasHand));
                DebugCapture.save(shown.finalImage, "0-real-A");
            }
            checkpoint(mc, Checkpoint.LOOP_START);
        } catch (RuntimeException e) {
            fail(e);
        }
    }

    /** The camera for the coming real frame has been extracted; it has not been rendered yet. */
    public void afterExtract(Minecraft mc) {
        frameExtractNs = System.nanoTime();
        if (!active()) return;
        try {
            if (interpolated()) {
                checkpoint(mc, Checkpoint.AFTER_EXTRACT);
            } else {
                generateLowLatency(mc);
            }
        } catch (RuntimeException e) {
            fail(e);
        }
    }

    /** A point in the loop between two steps of the real frame, where a generated frame may go out. */
    public void checkpoint(Checkpoint point) {
        if (!active() || !interpolated()) return;
        try {
            checkpoint(Minecraft.getInstance(), point);
        } catch (RuntimeException e) {
            fail(e);
        }
    }

    /**
     * Vanilla is about to blit its finished frame to the window. Keeps that frame, shows any
     * generated frames still due, and decides what vanilla's blit and present should show.
     *
     * @return the view vanilla should blit, or null to blit and present nothing this loop
     */
    public GpuTextureView beforeBlit(Minecraft mc, GpuTextureView vanillaView) {
        present = Present.VANILLA;
        try {
            FrameSlot finished = captureFinal(mc);
            if (!active()) {
                shown = finished;
                held = null;
                return vanillaView;
            }
            if (!interpolated()) {
                shown = finished;
                held = null;
                endCapture(null);
                return vanillaView;
            }

            checkpoint(mc, Checkpoint.BEFORE_BLIT);

            if (finished == null || !mayHold(mc, finished)) {
                // Menus, pauses, teleports: show the newest frame straight away, drop the held one.
                endCapture(null);
                shown = finished;
                held = null;
                return vanillaView;
            }
            if (held != null && held.usable() && continuous(held, finished)) {
                endCapture(held);
                FrameSlot b = held;
                shown = b;
                held = finished;
                present = Present.HELD;
                return b.finalView;
            }
            endCapture(null);
            if (shown == null || !shown.usable()) {
                shown = finished;
                held = null;
                return vanillaView;
            }
            // Start holding: show A once more (something must be presented, or vanilla skips the
            // next loop because the swapchain image is still acquired); the new frame shows next loop.
            held = finished;
            return shown.finalView;
        } catch (RuntimeException e) {
            fail(e);
            present = Present.VANILLA;
            return vanillaView;
        }
    }

    public boolean shouldPresent() {
        return present != Present.NOTHING;
    }

    // =============================================================================================
    // Interpolated mode
    // =============================================================================================

    private boolean canInterpolate(Minecraft mc) {
        FrameGenConfig cfg = VFrameGen.config();
        if (shown == null || held == null) { skip(shown == null ? "no-history" : "starting"); return false; }
        if (!shown.usable() || !held.usable()) { skip("no-history"); return false; }
        if (mc.level == null || shown.level != mc.level || held.level != mc.level) { skip("level"); return false; }
        if (mc.isPaused()) { skip("paused"); return false; }
        if (cfg.disableWhileScreenOpen && mc.gui.screen() != null) { skip("screen"); return false; }
        if (!mc.windowSurface().isAcquired()) { skip("not-acquired"); return false; }
        RenderTarget main = mc.gameRenderer.mainRenderTarget();
        if (main.width != width || main.height != height) { skip("size"); return false; }
        if (!continuous(shown, held)) { skip("jump"); return false; }
        return true;
    }

    /** May the frame that just finished be held back (instead of shown at once)? */
    private boolean mayHold(Minecraft mc, FrameSlot finished) {
        FrameGenConfig cfg = VFrameGen.config();
        if (!finished.usable()) return false;
        if (mc.isPaused()) return false;
        if (cfg.disableWhileScreenOpen && mc.gui.screen() != null) return false;
        return mc.level != null && finished.level == mc.level;
    }

    /** Close enough in time and camera to generate frames between. */
    private boolean continuous(FrameSlot a, FrameSlot b) {
        FrameGenConfig cfg = VFrameGen.config();
        long dt = b.extractNs - a.extractNs;
        if (dt <= 0 || dt > cfg.maxRealFrameAgeMs * 1_000_000L) return false;
        if (a.pos.distance(b.pos) > cfg.maxCameraJumpBlocks) return false;
        a.rotation(q0);
        b.rotation(q1);
        double turnDeg = Math.toDegrees(2.0 * Math.acos(Math.min(1.0, Math.abs(q0.dot(q1)))));
        return cfg.maxCameraTurnDegrees <= 0 || turnDeg <= cfg.maxCameraTurnDegrees;
    }

    /**
     * Show the generated frames that are due at this point of the loop. Frame k of M is meant for
     * k/M of the way through the loop; each one goes out at the checkpoint closest to that time.
     */
    private void checkpoint(Minecraft mc, Checkpoint point) {
        if (!interpActive) return;
        GpuSurface surface = mc.windowSurface();
        int i = point.ordinal();
        boolean last = point == Checkpoint.BEFORE_BLIT;
        double loop = emaLoopNs > 0 ? emaLoopNs : 16_000_000.0;
        double nowFrac = (System.nanoTime() - lastRealPresentNs) / loop;
        checkpointFrac[i] = checkpointSeen[i] == 0 ? nowFrac : checkpointFrac[i] + (nowFrac - checkpointFrac[i]) * 0.1;
        checkpointSeen[i] = loopCount;
        double nextFrac = 1.0;
        if (last) {
            nextFrac = Double.MAX_VALUE;
        } else {
            for (int j = i + 1; j < checkpointFrac.length - 1; j++) {
                if (checkpointSeen[j] != 0 && loopCount - checkpointSeen[j] <= 8) {
                    nextFrac = checkpointFrac[j];
                    break;
                }
            }
        }

        while (nextFrame < frames && surface.isAcquired()) {
            double due = nextFrame / (double) frames;
            nowFrac = (System.nanoTime() - lastRealPresentNs) / loop;
            boolean go = last || due <= nowFrac || (due - nowFrac) < (nextFrac - due);
            if (!go) break;
            if (due > nowFrac) {
                waitBudgetNs -= waitUntil(lastRealPresentNs + (long) (due * loop), waitBudgetNs);
            }
            if (!interpolateAndPresent(mc, surface, nextFrame / (double) frames, point)) {
                interpActive = false;
                return;
            }
            nextFrame++;
        }
        if (last && capture && nextFrame > 1) {
            debugView = true;
            renderInterpolated((frames - 1) / (double) frames);
            debugView = false;
            DebugCapture.save(outTex, "8-debug-view");
        }
    }

    private boolean interpolateAndPresent(Minecraft mc, GpuSurface surface, double f, Checkpoint point) {
        if (capture) {
            captureLog.append(String.format(Locale.ROOT, "gen %d: f=%.3f at %s, %.2f ms after A was shown%n",
                    nextFrame, f, point, (System.nanoTime() - lastRealPresentNs) / 1e6));
        }
        CommandEncoder encoder = renderInterpolated(f);
        boolean ok = presentGenerated(mc, surface, encoder);
        if (capture) DebugCapture.save(outTex, nextFrame + "-generated");
        return ok;
    }

    private CommandEncoder renderInterpolated(double f) {
        FrameSlot a = shown, b = held;
        Vector3d dstPos = new Vector3d(a.pos).lerp(b.pos, f);
        a.rotation(q0).slerp(b.rotation(q1), (float) f, q2);
        dstViewRot.rotation(q2);
        lerp(a.proj, b.proj, (float) f, dstProj);
        return renderPass(dstPos, a, b, (float) f);
    }

    private void endCapture(FrameSlot nowShowing) {
        if (!capture) return;
        if (nowShowing != null) DebugCapture.save(nowShowing.finalImage, "9-real-B");
        DebugCapture.writeLog(captureLog.toString());
        DebugCapture.end();
        capture = false;
    }

    // =============================================================================================
    // Low-latency mode
    // =============================================================================================

    private void generateLowLatency(Minecraft mc) {
        FrameGenConfig cfg = VFrameGen.config();
        FrameSlot a = shown;
        if (a == null || !a.usable()) { skip("no-history"); return; }
        if (mc.level == null || mc.player == null || mc.level != a.level) { skip("level"); return; }
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

        long loopNs = frameExtractNs - a.extractNs;
        if (loopNs <= 0 || loopNs > cfg.maxRealFrameAgeMs * 1_000_000L) { skip("slow-frame"); return; }
        Vector3d curPos = new Vector3d(cam.pos.x, cam.pos.y, cam.pos.z);
        if (curPos.distance(a.pos) > cfg.maxCameraJumpBlocks) { skip("jump"); return; }
        a.rotation(q0);
        cam.viewRotationMatrix.getNormalizedRotation(q1);
        double turnDeg = Math.toDegrees(2.0 * Math.acos(Math.min(1.0, Math.abs(q0.dot(q1)))));
        if (cfg.maxCameraTurnDegrees > 0 && turnDeg > cfg.maxCameraTurnDegrees) { skip("turn"); return; }

        currentProjection(gr, state, cam, curProj);
        int m = Math.clamp(cfg.multiplier, 2, FrameGenConfig.MAX_MULTIPLIER);
        long budget = (long) (lastLoopBlockedNs * 0.9);
        capture = DebugCapture.begin();
        if (capture) {
            captureLog.setLength(0);
            captureLog.append(String.format(Locale.ROOT, "mode=low-latency multiplier=%d loopMs=%.2f blockedMs=%.2f%n",
                    m, loopNs / 1e6, lastLoopBlockedNs / 1e6));
            DebugCapture.save(a.finalImage, "0-real");
        }
        Vector3d dstPos = null;
        for (int k = 1; k < m; k++) {
            double f = k / (double) m;
            if (cfg.pacing == FrameGenConfig.Pacing.BALANCED && emaLoopNs > 0) {
                budget -= waitUntil(lastRealPresentNs + (long) (emaLoopNs * f), budget);
            }
            dstPos = new Vector3d(a.pos).lerp(curPos, f);
            q0.slerp(q1, (float) f, q2);
            dstViewRot.rotation(q2);
            lerp(a.proj, curProj, (float) f, dstProj);
            if (capture) {
                captureLog.append(String.format(Locale.ROOT, "gen %d: f=%.3f, %.2f ms after the last real frame%n",
                        k, f, (System.nanoTime() - lastRealPresentNs) / 1e6));
            }
            boolean ok = presentGenerated(mc, surface, renderPass(dstPos, a, null, 0f));
            if (capture) DebugCapture.save(outTex, k + "-generated");
            if (!ok) break;
        }
        if (capture && dstPos != null) {
            debugView = true;
            renderPass(dstPos, a, null, 0f);
            debugView = false;
            DebugCapture.save(outTex, "8-debug-view");
        }
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

    // =============================================================================================
    // Drawing and presenting
    // =============================================================================================

    /**
     * Pacing: wait until {@code target}, but only for as long as the render thread would have
     * been blocked on the GPU anyway (BALANCED), so real frames do not slow down.
     *
     * @return how long it waited, in ns
     */
    private long waitUntil(long target, long budget) {
        if (VFrameGen.config().pacing != FrameGenConfig.Pacing.BALANCED) return 0;
        long start = System.nanoTime();
        long wait = Math.min(target - start, budget);
        if (wait <= 0) return 0;
        long end = start + wait;
        while (true) {
            long left = end - System.nanoTime();
            if (left <= 0) break;
            if (left > 1_500_000L) {
                LockSupport.parkNanos(left - 1_000_000L);
            } else {
                Thread.onSpinWait();
            }
        }
        return System.nanoTime() - start;
    }

    /** @return false when no swapchain image could be acquired afterwards */
    private boolean presentGenerated(Minecraft mc, GpuSurface surface, CommandEncoder encoder) {
        surface.blitFromTexture(encoder, outView);
        encoder.submit();
        surface.present();
        notePresent();
        statsGenerated++;
        secGenerated++;
        // Count it in vanilla's FPS (F3 and anything that reads Minecraft.getFps()).
        MinecraftAccessor counter = (MinecraftAccessor) mc;
        counter.vframegen$setFrames(counter.vframegen$getFrames() + 1);
        try {
            surface.acquireNextTexture();
            return true;
        } catch (SurfaceException e) {
            // Same as vanilla: the surface is reconfigured next loop.
            ((MinecraftAccessor) mc).vframegen$setSurfaceIsInvalid(true);
            return false;
        }
    }

    /**
     * Draw the frame for camera dstPos / dstViewRot / dstProj into outTex, from source a alone, or
     * from a and b blended at t (interpolated mode).
     */
    private CommandEncoder renderPass(Vector3d dstPos, FrameSlot a, FrameSlot b, float t) {
        FrameGenConfig cfg = VFrameGen.config();
        GpuDevice device = RenderSystem.getDevice();
        CommandEncoder encoder = device.createCommandEncoder();
        boolean zeroToOne = device.getDeviceInfo().isZZeroToOne();
        boolean irisShaders = Compat.irisShadersActive();
        boolean useDepth = switch (cfg.depthMode) {
            case DEPTH -> true;
            case ROTATION_ONLY -> false;
            case AUTO -> !irisShaders;
        };
        boolean reversedZ = ndcDepthAt(a.proj, 1.0f) > ndcDepthAt(a.proj, 100.0f);

        Matrix4f dstViewProjRot = new Matrix4f(dstProj).mul(dstViewRot);
        Matrix4f dstInvViewProjRot = new Matrix4f(dstViewProjRot).invert();
        FrameSlot bb = b != null ? b : a;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            Std140Builder ub = Std140Builder.onStack(stack, UBO_SIZE).putMat4f(dstInvViewProjRot);
            putSource(ub, a, dstViewProjRot, dstPos);
            putSource(ub, bb, dstViewProjRot, dstPos);
            ub.putVec4(cfg.guiDiffThreshold, cfg.debugTint ? 1f : 0f, cfg.sharpness, debugView ? 1f : 0f)
                    .putVec4(reversedZ ? 1f : 0f, zeroToOne ? 1f : 0f, 1f / width, 1f / height);
            putFlags(ub, a, irisShaders, useDepth, zeroToOne);
            putFlags(ub, bb, irisShaders, useDepth, zeroToOne);
            ub.putVec4(t, 0f, 0f, 0f);
            ByteBuffer data = ub.get();
            encoder.writeToBuffer(ubo.slice(), data);
        }

        GpuSampler linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        try (RenderPass pass = encoder.createRenderPass(() -> "VFrameGen", outView, Optional.empty())) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline(colorFormat, b != null)));
            bindSource(pass, "A", a, linear, nearest);
            if (b != null) bindSource(pass, "B", b, linear, nearest);
            pass.setUniform("FrameGen", ubo);
            pass.draw(3, 1, 0, 0);
        }
        return encoder;
    }

    /** A source's matrices; positions are relative to that source's camera. */
    private static void putSource(Std140Builder ub, FrameSlot s, Matrix4f dstViewProjRot, Vector3d dstPos) {
        Matrix4f viewProjRot = new Matrix4f(s.proj).mul(s.viewRot);
        Matrix4f invViewProj = new Matrix4f(viewProjRot).invert();
        Matrix4f dstViewProj = new Matrix4f(dstViewProjRot).translate(
                (float) (s.pos.x - dstPos.x), (float) (s.pos.y - dstPos.y), (float) (s.pos.z - dstPos.z));
        ub.putMat4f(invViewProj).putMat4f(dstViewProj).putMat4f(viewProjRot);
    }

    private void putFlags(Std140Builder ub, FrameSlot s, boolean irisShaders, boolean useDepth, boolean zeroToOne) {
        // Iris draws the held item itself, so vanilla's hand buffers mean nothing with a pack on.
        boolean handBuffer = s.hasHand && !irisShaders;
        boolean nearLock = !handBuffer && s.firstPerson && useDepth;
        ub.putVec4(handBuffer ? 1f : 0f, nearLock ? 1f : 0f, depthAt(s.proj, LOCKED_DISTANCE, zeroToOne), useDepth ? 1f : 0f);
    }

    private static void bindSource(RenderPass pass, String id, FrameSlot s, GpuSampler linear, GpuSampler nearest) {
        pass.setUniform("World" + id, s.worldView, linear);
        pass.setUniform("Depth" + id, s.depthView, nearest);
        pass.setUniform("HandDepth" + id, s.handDepthView, nearest);
        pass.setUniform("NoHand" + id, s.noHandView, linear);
        pass.setUniform("Final" + id, s.finalView, nearest);
    }

    /** Depth-buffer value at distance d in front of the camera, for projection proj. */
    private float depthAt(Matrix4f proj, float d, boolean zeroToOne) {
        float ndc = ndcDepthAt(proj, d);
        return zeroToOne ? ndc : ndc * 0.5f + 0.5f;
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

    private RenderPipeline pipeline(GpuFormat format, boolean interpolate) {
        String key = format.name() + (interpolate ? "_interpolate" : "");
        return pipelines.computeIfAbsent(key, k -> {
            BindGroupLayout.Builder layout = BindGroupLayout.builder();
            for (String id : interpolate ? new String[]{"A", "B"} : new String[]{"A"}) {
                for (String tex : new String[]{"World", "Depth", "HandDepth", "NoHand", "Final"}) {
                    layout.withUniform(tex + id, UniformType.COMBINED_IMAGE_SAMPLER);
                }
            }
            layout.withUniform("FrameGen", UniformType.UNIFORM_BUFFER);
            RenderPipeline.Builder builder = RenderPipeline.builder()
                    .withLocation(Identifier.fromNamespaceAndPath(VFrameGen.MOD_ID, "pipeline/reproject_" + k.toLowerCase(Locale.ROOT)))
                    .withVertexShader("core/screenquad")
                    .withFragmentShader(Identifier.fromNamespaceAndPath(VFrameGen.MOD_ID, "core/reproject"))
                    .withBindGroupLayout(layout.build())
                    .withColorTargetState(new ColorTargetState(Optional.empty(), format, ColorTargetState.WRITE_ALL))
                    .withPrimitiveTopology(PrimitiveTopology.TRIANGLES);
            if (interpolate) builder.withShaderDefine("INTERPOLATE");
            return builder.build();
        });
    }

    // =============================================================================================
    // Capture during the real frame (into slot C)
    // =============================================================================================

    /** The exact world projection of this real frame (view bobbing and nausea included). */
    public void captureProjection(Matrix4f proj) {
        frameProj.set(proj);
        frameProjCaptured = true;
    }

    /** Right before the held item is drawn: the depth buffer still has the whole world in it. */
    public void captureWorldDepth(GameRenderer gr) {
        FrameSlot c = prepare(gr);
        if (c == null) return;
        RenderTarget main = gr.mainRenderTarget();
        copy(main.getDepthTexture(), c.depth);
        copy(main.getColorTexture(), c.noHand);
        frameWorldDepthCaptured = true;
    }

    /** Right after the held item is drawn, into a depth buffer that was cleared for it. */
    public void captureHandDepth(GameRenderer gr, boolean usedHudTarget) {
        if (!frameWorldDepthCaptured || building == null) return;
        RenderTarget hud = ((GameRendererAccessor) gr).vframegen$hud3DTarget();
        RenderTarget target = usedHudTarget && hud != null ? hud : gr.mainRenderTarget();
        GpuTexture handDepth = target.getDepthTexture();
        if (handDepth == null || handDepth.getFormat() != depthFormat
                || handDepth.getWidth(0) != width || handDepth.getHeight(0) != height) {
            return;
        }
        copy(handDepth, building.handDepth);
        frameHandCaptured = true;
    }

    /** After the world, the held item and post effects are drawn, before the GUI. */
    public void captureWorld(GameRenderer gr) {
        FrameSlot c = prepare(gr);
        if (c == null) return;
        RenderTarget main = gr.mainRenderTarget();
        if (!frameWorldDepthCaptured) {
            // render3dHud did not run (or another mod replaced it): no separate hand pass.
            copy(main.getDepthTexture(), c.depth);
            copy(main.getColorTexture(), c.noHand);
        }
        copy(main.getColorTexture(), c.world);
        frameWorldCaptured = true;

        CameraRenderState cam = gr.gameRenderState().levelRenderState.cameraRenderState;
        c.pos.set(cam.pos.x, cam.pos.y, cam.pos.z);
        c.viewRot.set(cam.viewRotationMatrix);
        c.firstPerson = cam.isFirstPerson;
        if (frameProjCaptured) {
            c.proj.set(frameProj);
        } else {
            currentProjection(gr, gr.gameRenderState(), cam, c.proj);
        }
        c.extractNs = frameExtractNs != 0 ? frameExtractNs : System.nanoTime();
        c.hasHand = frameHandCaptured;
        c.level = Minecraft.getInstance().level;
        c.hasFinal = false;
        c.valid = true;
    }

    /** The finished real frame, GUI included, right before it is blitted to the window. */
    private FrameSlot captureFinal(Minecraft mc) {
        FrameSlot c = building;
        building = null;
        if (c == null || !frameWorldCaptured || !active()) {
            if (c != null) c.valid = false;
            return null;
        }
        RenderTarget main = mc.gameRenderer.mainRenderTarget();
        if (main.width != width || main.height != height) {
            c.valid = false;
            return null;
        }
        copy(main.getColorTexture(), c.finalImage);
        c.hasFinal = true;
        if (capture && !interpolated()) {
            DebugCapture.save(main.getColorTexture(), "9-real-next");
        }
        return c;
    }

    /** The slot this frame is captured into: one that is neither on screen nor held. */
    private FrameSlot prepare(GameRenderer gr) {
        if (!active()) return null;
        if (building != null) return building;
        RenderTarget main = gr.mainRenderTarget();
        GpuTexture color = main.getColorTexture();
        GpuTexture depth = main.getDepthTexture();
        if (color == null || depth == null) return null;
        try {
            ensureResources(main.width, main.height, color.getFormat(), depth.getFormat());
        } catch (RuntimeException e) {
            broken = true;
            VFrameGen.LOGGER.error("Could not create frame generation resources; frame generation is off for this session", e);
            return null;
        }
        for (FrameSlot s : slots) {
            if (s != shown && s != held) {
                s.valid = false;
                s.hasFinal = false;
                building = s;
                return s;
            }
        }
        return null;
    }

    private void copy(GpuTexture src, GpuTexture dst) {
        RenderSystem.getDevice().createCommandEncoder()
                .copyTextureToTexture(src, dst, 0, 0, 0, 0, 0, width, height);
    }

    private void ensureResources(int w, int h, GpuFormat color, GpuFormat depth) {
        if (w == width && h == height && color == colorFormat && depth == depthFormat && outTex != null) return;
        // In low-latency mode only two slots are ever used; still allocate lazily per slot? Keep it
        // simple: all three, so switching modes needs no reallocation.
        for (FrameSlot s : slots) s.create(w, h, color, depth);
        if (outView != null) outView.close();
        if (outTex != null) outTex.close();
        GpuDevice device = RenderSystem.getDevice();
        outTex = device.createTexture("VFrameGen output",
                GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_SRC,
                color, w, h, 1, 1);
        outView = device.createTextureView(outTex);
        if (ubo == null) {
            ubo = device.createBuffer(() -> "VFrameGen uniforms", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, UBO_SIZE);
        }
        width = w;
        height = h;
        colorFormat = color;
        depthFormat = depth;
        shown = null;
        held = null;
        VFrameGen.LOGGER.info("Frame generation buffers: {}x{} {} / {} on {}", w, h, color, depth, backendName());
    }

    // =============================================================================================
    // Timing and stats
    // =============================================================================================

    private void skip(String reason) {
        statsSkips.merge(reason, 1, Integer::sum);
    }

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

    private void notePresent() {
        long now = System.nanoTime();
        if (lastAnyPresentNs != 0) {
            double gap = (now - lastAnyPresentNs) / 1e6;
            if (gap < 250) {
                gapSumMs += gap;
                gapMaxMs = Math.max(gapMaxMs, gap);
                gapCount++;
            }
        }
        lastAnyPresentNs = now;
    }

    /** After vanilla's present (only when it presented something). */
    public void afterRealPresent() {
        notePresent();
        long now = System.nanoTime();
        if (lastRealPresentNs != 0) {
            emaLoopNs = ema(emaLoopNs, now - lastRealPresentNs);
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
            VFrameGen.LOGGER.info("Real {} fps, generated {} fps, shown {} fps ({}, {}), frame gap avg {} ms max {} ms{}{}",
                    Math.round(statsReal / secs), Math.round(statsGenerated / secs),
                    Math.round((statsReal + statsGenerated) / secs), backendName(),
                    interpolated() ? "interpolated" : "low latency",
                    String.format(Locale.ROOT, "%.1f", gapCount == 0 ? 0 : gapSumMs / gapCount),
                    String.format(Locale.ROOT, "%.1f", gapMaxMs),
                    statsSkips.isEmpty() ? "" : ", skipped " + statsSkips,
                    broken ? " [disabled after error]" : "");
            statsSkips.clear();
            gapSumMs = 0;
            gapMaxMs = 0;
            gapCount = 0;
            statsStartNs = now;
            statsReal = 0;
            statsGenerated = 0;
        }
    }

    private static double ema(double avg, double sample) {
        return avg <= 0 ? sample : avg + (sample - avg) * 0.1;
    }
}
