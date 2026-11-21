package io.github.jmlambo.vframegen;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
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
import java.util.ArrayDeque;
import java.util.OptionalLong;
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
 * <p><b>Interpolated mode</b> (default). Each real frame is held back for one game loop: at the end
 * of a loop, vanilla's present shows the held frame (A) and the one just finished (B) is held. The
 * frames between A and B are built from both. Nothing is drawn or submitted while the world (or a
 * shader pack) renders. On Vulkan they are handed to {@link PresentThread}, which shows each at
 * its time; otherwise they go out in the frame limiter's sleep (timed from a GPU fence on A), or at
 * the very start of the next loop, with the frame rate capped so there is room for them.
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

    /** What vanilla's blit + present show at the end of this loop. */
    public enum Present { VANILLA, HELD }

    // --- GPU resources --------------------------------------------------------------------------
    // Output size (the window). With render scale, depth and the pre-hand image are smaller.
    private int width = -1;
    private int height = -1;
    private int renderWidth = -1;
    private int renderHeight = -1;
    private GpuFormat colorFormat;
    private GpuFormat depthFormat;
    private final FrameSlot[] slots = {new FrameSlot("slot 0"), new FrameSlot("slot 1"), new FrameSlot("slot 2")};
    private GpuTexture outTex;
    private GpuTextureView outView;
    private GpuBuffer ubo;
    private GpuBuffer maskUbo;
    private static final int MASK_UBO_SIZE = 2 * 16;
    private RenderPipeline maskPipeline;
    // GPU time of the reprojection pass, from timestamp queries read back a few frames later.
    private final ArrayDeque<GpuQueryPool> gpuTimers = new ArrayDeque<>();
    private boolean gpuTimersUnsupported;
    private long gpuPassNs;
    private int gpuPassCount;
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

    // --- pacing (interpolated mode) ----------------------------------------------------------------
    // After vanilla's present puts the held frame B on screen, the frames between A (= shown, B
    // before it) ... are shown while the game is not rendering the world: in the frame limiter's
    // sleep, or failing that at the very start of the next loop. Never while the world renders: a
    // shader pack is in the middle of its own work then.
    private boolean genPlanned;            // frames between shown and held still to show
    private int nextFrame;                 // next generated frame (1..M-1)
    private int frames;                    // M, the multiplier, fixed per pair of real frames
    private long genStartNs;               // when the real frame before them was on screen
    private long lastStartNs;
    private double realIntervalNs;         // average time between real frames reaching the screen
    private double realDoneLagNs;          // from vanilla's present() returning to the GPU finishing it
    private com.mojang.renderpearl.api.commands.GpuFence realFence;
    private long limiterExitNs;
    private boolean noAcquireAfterPresent;
    private boolean capture;

    // --- debug: estimated on-screen times (temporary) ---------------------------------------------
    // Real frames: when the GPU finished them (fence), else present() + the measured lag.
    // Generated frames: when presented (the GPU is otherwise idle then), but never before the real
    // frame ahead of them. Gaps between consecutive ones are logged once a second.
    private long lastScreenNs;
    private boolean lastScreenReal;
    private double gapRgSum, gapGrSum, gapScrMin = Double.MAX_VALUE, gapScrMax;
    private int gapRgN, gapGrN;
    private final int[] gapBuckets = new int[6]; // <4, 4-8, 8-12, 12-20, 20-30, >=30 ms

    private void screenEvent(long t, boolean real) {
        if (lastScreenNs != 0 && t >= lastScreenNs) {
            double g = (t - lastScreenNs) / 1e6;
            if (g < 250) {
                if (lastScreenReal && !real) { gapRgSum += g; gapRgN++; }
                if (!lastScreenReal && real) { gapGrSum += g; gapGrN++; }
                gapScrMin = Math.min(gapScrMin, g);
                gapScrMax = Math.max(gapScrMax, g);
                gapBuckets[g < 4 ? 0 : g < 8 ? 1 : g < 12 ? 2 : g < 20 ? 3 : g < 30 ? 4 : 5]++;
            }
        }
        lastScreenNs = Math.max(t, lastScreenNs);
        lastScreenReal = real;
    }

    private void logPacing() {
        if (gapRgN + gapGrN == 0 || !VFrameGen.config().debugPacingLog) {
            gapRgSum = gapGrSum = 0;
            gapRgN = gapGrN = 0;
            gapScrMin = Double.MAX_VALUE;
            gapScrMax = 0;
            java.util.Arrays.fill(gapBuckets, 0);
            return;
        }
        VFrameGen.LOGGER.info("[pacing debug] on-screen gaps: real->generated avg {} ms ({}), generated->real avg {} ms ({}), min {} max {} ms, buckets <4:{} 4-8:{} 8-12:{} 12-20:{} 20-30:{} 30+:{}",
                String.format(Locale.ROOT, "%.1f", gapRgN == 0 ? 0 : gapRgSum / gapRgN), gapRgN,
                String.format(Locale.ROOT, "%.1f", gapGrN == 0 ? 0 : gapGrSum / gapGrN), gapGrN,
                String.format(Locale.ROOT, "%.1f", gapScrMin), String.format(Locale.ROOT, "%.1f", gapScrMax),
                gapBuckets[0], gapBuckets[1], gapBuckets[2], gapBuckets[3], gapBuckets[4], gapBuckets[5]);
        gapRgSum = gapGrSum = 0;
        gapRgN = gapGrN = 0;
        gapScrMin = Double.MAX_VALUE;
        gapScrMax = 0;
        java.util.Arrays.fill(gapBuckets, 0);
    }

    // --- timing and stats -----------------------------------------------------------------------
    private long lastRealPresentNs;
    private double lastGenF;
    private final Vector3d lastGenPos = new Vector3d();
    private double emaLoopNs;
    private long lastAnyPresentNs;
    private long genCostNs;
    private boolean submittingGenerated;
    private long costSubmitNs, costPresentNs, costAcquireNs;
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
        genPlanned = false;
    }


    /** Frames are shown by {@link PresentThread} (Vulkan): no cap, no sleeping on the render thread. */
    private boolean presentThreadActive() {
        return PresentThread.enabled() && "Vulkan".equalsIgnoreCase(backendName());
    }

    private boolean interpolated() {
        return VFrameGen.config().mode == FrameGenConfig.Mode.INTERPOLATED;
    }

    private boolean active() {
        return !broken && VFrameGen.config().enabled;
    }

    /**
     * Have vanilla recreate the swapchain at the next loop, as it does after a failed acquire.
     * (Invalid alone only stops acquiring and presenting: nothing would ever reconfigure it.)
     */
    public static void recreateSurface(Minecraft mc) {
        MinecraftAccessor acc = (MinecraftAccessor) mc;
        acc.vframegen$setSurfaceIsInvalid(true);
        acc.vframegen$setWindowSurfaceNeedsReconfiguring(true);
    }

    private void fail(RuntimeException e) {
        broken = true;
        invalidate();
        VFrameGen.LOGGER.error("Frame generation failed and has been switched off for this session", e);
    }

    // =============================================================================================
    // Game loop hooks
    // =============================================================================================

    /** Start of a game loop (vanilla has acquired this loop's swapchain image). */
    public void loopStart(Minecraft mc) {
        StallProbe.loopStart();
        frameProjCaptured = false;
        frameWorldDepthCaptured = false;
        frameHandCaptured = false;
        frameWorldCaptured = false;
        building = null;
        if (presentThreadActive() && PresentThread.takeSurfaceLost()) {
            recreateSurface(mc);
        }
        if (!active() || !interpolated() || !genPlanned || presentThreadActive()) return;
        // Generated frames that found no room in the frame limiter's sleep (no cap, or the game
        // could not keep it): show them now, before this loop records any world rendering.
        try {
            showGenerated(mc, true);
        } catch (RuntimeException e) {
            fail(e);
        }
        genPlanned = false;
    }

    /** The camera for the coming real frame has been extracted; it has not been rendered yet. */
    public void afterExtract(Minecraft mc) {
        StallProbe.mark("extract");
        frameExtractNs = System.nanoTime();
        if (!active() || interpolated()) return;
        try {
            generateLowLatency(mc);
        } catch (RuntimeException e) {
            fail(e);
        }
    }

    /**
     * Minecraft's frame limiter is about to sleep until the next loop may start. Nothing renders
     * now: the generated frames between the frame vanilla just put on screen and the held one go
     * out here, each at its time.
     */
    public void beforeLimiterSleep(Minecraft mc, int limit) {
        if (!active() || !interpolated() || !genPlanned || presentThreadActive()) return;
        long nominal = 1_000_000_000L / Math.max(1, limit);
        long sleepEnd = limiterExitNs != 0 ? limiterExitNs + nominal : System.nanoTime();
        try {
            showGenerated(mc, false, sleepEnd);
        } catch (RuntimeException e) {
            fail(e);
        }
    }

    /**
     * The frame rate limit vanilla is about to apply. With frame generation on and no limit set,
     * real frames are capped at refresh rate / multiplier: generated frames need idle time.
     */
    private int lastAutoCap;
    // Adaptive auto cap: generated frames must fit in the idle time between real frames (the
    // limiter's sleep). Counted per second; the cap comes down while they do not, and goes back up
    // when there is room to spare.
    private int capAdjust;
    private int fitCount, missCount, roomySeconds, badSeconds;
    private double minSlackMs = Double.MAX_VALUE;
    private int capFloor = 30;

    private void adaptCap() {
        // Changing the cap resets vanilla's limiter and the pacing estimate (one hitch each time),
        // so it changes rarely: down after two bad seconds in a row, up after ten roomy ones.
        int total = fitCount + missCount;
        if (total >= 10 && lastAutoCap > 0) {
            boolean bad = missCount * 5 > total; // more than 20% early or late
            boolean roomy = missCount == 0 && minSlackMs > 4.0;
            badSeconds = bad ? badSeconds + 1 : 0;
            roomySeconds = roomy ? roomySeconds + 1 : 0;
            if (badSeconds >= 2) {
                if (lastAutoCap - 2 >= capFloor) capAdjust += 2;
                badSeconds = 0;
            } else if (roomySeconds >= 10 && capAdjust > 0) {
                capAdjust -= 2;
                roomySeconds = 0;
            }
        }
        fitCount = missCount = 0;
        minSlackMs = Double.MAX_VALUE;
    }

    public int frameLimit(Minecraft mc, int limit) {
        if (limit < 260 || !active() || !VFrameGen.config().autoCap) return limit;
        // Fixed multiplier: only interpolation needs the cap. Adaptive caps at the target in both modes.
        if (!interpolated() && !VFrameGen.config().adaptive) return limit;
        if (mc.level == null || mc.isPaused()) return limit;
        // The monitor the window is on (its current mode). The window's "active video mode" is the
        // fullscreen mode setting and read 60 Hz on a 120 Hz screen.
        float refresh = 0f;
        try {
            refresh = mc.getWindow().findBestMonitor().currentMode().getRefreshRate();
        } catch (RuntimeException ignored) {
        }
        if (VFrameGen.config().displayHz > 0) refresh = VFrameGen.config().displayHz;
        if (!(refresh >= 30f)) refresh = 60f;
        int m = Math.clamp(VFrameGen.config().multiplier, 2, FrameGenConfig.MAX_MULTIPLIER);
        // Adaptive: real frames never beyond the target (frame generation makes up the rest).
        int full = VFrameGen.config().adaptive ? VFrameGen.config().targetFps
                : Math.max(1, (int) Math.floor(refresh / m));
        capFloor = Math.min(full, Math.max(20, full / 2));
        // With the present thread no idle time is needed: just never make more frames than the
        // display can show (the extra ones would be dropped unevenly).
        int cap = presentThreadActive() ? full : Math.max(capFloor, full - capAdjust);
        if (cap != lastAutoCap) {
            lastAutoCap = cap;
            if (VFrameGen.config().debugPacingLog) VFrameGen.LOGGER.info("Auto frame cap: {} real fps ({} Hz display / {}x, minus {} so generated frames fit)", cap, refresh, m, capAdjust);
        }
        return cap;
    }

    /** The frame limiter's sleep is over: the next loop starts. */
    public void afterLimiterSleep() {
        limiterExitNs = System.nanoTime();
    }

    private void showGenerated(Minecraft mc, boolean loopStart) {
        showGenerated(mc, loopStart, Long.MAX_VALUE);
    }

    /**
     * Show the generated frames between shown (A) and held (B) that are due by {@code until}.
     * loopStart: vanilla has acquired this loop's image (present, then acquire again for it), and
     * the frames are shown now even if late. Otherwise (the limiter sleep) one image is acquired
     * per frame and none is left acquired, or vanilla's next loop would not start.
     */
    private void showGenerated(Minecraft mc, boolean loopStart, long until) {
        GpuSurface surface = mc.windowSurface();
        if (!canInterpolate(mc, loopStart)) {
            genPlanned = false;
            return;
        }
        if (genStartNs == 0) {
            // When A (presented at the end of the last loop) really reached the screen: the GPU
            // finishes it well after present() returns, by many ms with a shader pack.
            long now = System.nanoTime();
            long wait = Math.min(until, now + 100_000_000L) - now;
            if (realFence != null && !loopStart && wait > 0 && realFence.awaitCompletion(wait)) {
                now = System.nanoTime();
                realDoneLagNs = ema(realDoneLagNs, now - lastRealPresentNs);
                genStartNs = now;
            } else {
                genStartNs = lastRealPresentNs + (long) realDoneLagNs;
            }
            screenEvent(genStartNs, true);
            if (lastStartNs != 0) {
                long d = genStartNs - lastStartNs;
                if (d > 0 && d < 250_000_000L) realIntervalNs = ema(realIntervalNs, d);
            }
            lastStartNs = genStartNs;
            if (!capture && DebugCapture.begin()) {
                capture = true;
                captureLog.setLength(0);
                captureLog.append(String.format(Locale.ROOT,
                        "mode=interpolated multiplier=%d real interval %.2f ms, A on screen %.2f ms after present() A->B ms=%.2f iris=%s%n",
                        frames, realIntervalNs / 1e6, (genStartNs - lastRealPresentNs) / 1e6,
                        (held.extractNs - shown.extractNs) / 1e6, Compat.irisShadersActive()));
                DebugCapture.save(shown.finalImage, "0-real-A");
            }
            if (VFrameGen.config().debugFrameLog) DebugCapture.sequenceNote(String.format(Locale.ROOT, "previous real frame on screen %.2f ms after present(), interval %.2f ms",
                    (genStartNs - lastRealPresentNs) / 1e6, realIntervalNs / 1e6));
        }
        // Time between real frames: vanilla presents once per loop, so the loop period. (Measuring
        // it between pairs of frames that were generated between misses pairs that were skipped.)
        long interval = (long) (emaLoopNs > 0 ? emaLoopNs : realIntervalNs);
        boolean pace = VFrameGen.config().pacing == FrameGenConfig.Pacing.BALANCED;
        while (nextFrame < frames) {
            long due = genStartNs + interval * nextFrame / frames;
            // Not in this sleep: it goes out at the start of the next loop (judged there).
            if (!loopStart && due > until - 300_000L) return;
            if (!loopStart) minSlackMs = Math.min(minSlackMs, (until - due) / 1e6);
            if (pace) {
                // At the start of a loop, waiting holds the real frame back: only a little.
                waitUntilAlways(loopStart ? Math.min(due, System.nanoTime() + interval / (2L * frames)) : due);
            }
            // On time or not, wherever it went out: that is what the auto cap adapts to.
            long errNs = System.nanoTime() - due;
            if (errNs > -2_000_000L && errNs < 3_000_000L) fitCount++;
            else missCount++;
            if (!loopStart) {
                try {
                    surface.acquireNextTexture();
                } catch (SurfaceException e) {
                    recreateSurface(mc);
                    genPlanned = false;
                    return;
                }
            }
            noAcquireAfterPresent = !loopStart;
            boolean ok;
            try {
                ok = interpolateAndPresent(mc, surface, nextFrame / (double) frames, loopStart ? "loop start" : "limiter sleep");
            } catch (RuntimeException e) {
                if (!loopStart && surface.isAcquired()) {
                    try {
                        surface.present(); // never leave an image acquired here
                    } catch (RuntimeException ignored) {
                    }
                }
                throw e;
            } finally {
                noAcquireAfterPresent = false;
            }
            if (!ok) {
                genPlanned = false;
                return;
            }
            nextFrame++;
        }
        genPlanned = false;
        if (capture) {
            debugView = true;
            renderInterpolated((frames - 1) / (double) frames);
            debugView = false;
            DebugCapture.save(outTex, "8-debug-view");
            saveValidation();
        }
    }

    private static void waitUntilAlways(long target) {
        while (true) {
            long left = target - System.nanoTime();
            if (left <= 0) return;
            if (left > 1_500_000L) LockSupport.parkNanos(left - 1_000_000L);
            else Thread.onSpinWait();
        }
    }

    /**
     * Vanilla is about to blit its finished frame to the window. Keeps that frame, shows any
     * generated frames still due, and decides what vanilla's blit and present should show.
     *
     * @return the view vanilla should blit, or null to blit and present nothing this loop
     */
    public GpuTextureView beforeBlit(Minecraft mc, GpuTextureView vanillaView) {
        StallProbe.mark("world+gui");
        FrameSlot heldBefore = held, shownBefore = shown;
        GpuTextureView v = beforeBlit0(mc, vanillaView);
        if (presentThreadActive()) PresentThread.setNext(PresentThread.Kind.REAL, 0, 0);
        // Signalled when the GPU has finished this loop's submission (the real frame and its blit).
        if (realFence != null) realFence.close();
        realFence = null;
        if (v != null && active() && interpolated()) {
            try {
                realFence = RenderSystem.getDevice().createCommandEncoder().createFence();
            } catch (RuntimeException ignored) {
            }
        }
        if (!VFrameGen.config().debugFrameLog) {
            // (frame log off: nothing to record)
        } else if (v == null) {
            DebugCapture.sequenceFrame(null, "none", "nothing presented");
        } else if (present == Present.HELD && shown != null) {
            DebugCapture.sequenceFrame(shown.finalImage, "real", String.format(Locale.ROOT, "held frame, pos %.4f %.4f %.4f",
                    shown.pos.x, shown.pos.y, shown.pos.z));
        } else if (shownBefore != null && v == shownBefore.finalView) {
            DebugCapture.sequenceFrame(shownBefore.finalImage, "real-again", String.format(Locale.ROOT,
                    "previous frame shown again, pos %.4f %.4f %.4f", shownBefore.pos.x, shownBefore.pos.y, shownBefore.pos.z));
        } else {
            FrameSlot f = shown;
            DebugCapture.sequenceFrame(f != null ? f.finalImage : null, "real-direct", f == null ? "vanilla" : String.format(Locale.ROOT,
                    "newest frame directly, pos %.4f %.4f %.4f (held was %s)", f.pos.x, f.pos.y, f.pos.z, heldBefore == null ? "none" : "dropped"));
        }
        return v;
    }

    private GpuTextureView beforeBlit0(Minecraft mc, GpuTextureView vanillaView) {
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

            if (finished == null || !mayHold(mc, finished)) {
                // Menus, pauses, teleports: show the newest frame straight away, drop the held one.
                endCapture(null);
                shown = finished;
                held = null;
                return vanillaView;
            }
            // Always move forward: the held frame is next on screen whether or not frames can be
            // generated between it and the new one (canInterpolate checks that next loop). Going
            // back to the older frame here, after a generated frame past it, made the view jump back.
            if (held != null && held.usable()) {
                endCapture(held);
                FrameSlot b = held;
                shown = b;
                held = finished;
                present = Present.HELD;
                // Next: the frames between b and the new one, after b is on screen.
                genPlanned = true;
                genStartNs = 0;
                nextFrame = 1;
                frames = framesPerReal(finished.extractNs - b.extractNs);
                if (frames < 2) genPlanned = false; // the real frames already reach the target
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

    // Adaptive mode: frames shown per real frame, and the smoothed time between real frames.
    private int adaptiveFrames = 2;
    private double adaptiveIntervalNs;

    /**
     * Frames shown per real frame (1 = none generated) for a real frame that took intervalNs: the
     * multiplier, or in adaptive mode what reaches the target frame rate. That changes only when
     * the ideal count is clearly past the next whole number, so it does not flip every frame.
     */
    private int framesPerReal(long intervalNs) {
        FrameGenConfig cfg = VFrameGen.config();
        if (!cfg.adaptive) return Math.clamp(cfg.multiplier, 2, FrameGenConfig.MAX_MULTIPLIER);
        if (intervalNs > 0 && intervalNs < 250_000_000L) {
            adaptiveIntervalNs = adaptiveIntervalNs <= 0 ? intervalNs
                    : adaptiveIntervalNs + (intervalNs - adaptiveIntervalNs) * 0.1;
        }
        if (adaptiveIntervalNs <= 0) return adaptiveFrames;
        double ideal = cfg.targetFps * adaptiveIntervalNs / 1e9;
        if (Math.abs(ideal - adaptiveFrames) > 0.6) {
            adaptiveFrames = (int) Math.clamp(Math.round(ideal), 1, FrameGenConfig.MAX_MULTIPLIER);
        }
        return adaptiveFrames;
    }

    /** True while a generated frame is being submitted (see VulkanTransientMemoryMixin). */
    public boolean submittingGenerated() {
        return submittingGenerated;
    }


    // =============================================================================================
    // Interpolated mode
    // =============================================================================================


    private boolean canInterpolate(Minecraft mc, boolean needAcquired) {
        FrameGenConfig cfg = VFrameGen.config();
        if (shown == null || held == null) { skip(shown == null ? "no-history" : "starting"); return false; }
        if (!shown.usable() || !held.usable()) { skip("no-history"); return false; }
        if (mc.level == null || shown.level != mc.level || held.level != mc.level) { skip("level"); return false; }
        if (mc.isPaused()) { skip("paused"); return false; }
        if (cfg.disableWhileScreenOpen && mc.gui.screen() != null) { skip("screen"); return false; }
        if (needAcquired && !mc.windowSurface().isAcquired()) { skip("not-acquired"); return false; }
        if (!windowMatches(mc.gameRenderer)) { skip("size"); return false; }
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


    private boolean interpolateAndPresent(Minecraft mc, GpuSurface surface, double f, String where) {
        if (capture) {
            captureLog.append(String.format(Locale.ROOT, "gen %d: f=%.3f at %s, %.2f ms after A was on screen%n",
                    nextFrame, f, where, (System.nanoTime() - genStartNs) / 1e6));
        }
        CommandEncoder encoder = renderInterpolated(f);
        boolean ok = presentGenerated(mc, surface, encoder, where);
        if (capture) DebugCapture.save(outTex, nextFrame + "-generated");
        return ok;
    }

    /**
     * Test frame for the capture: A warped all the way to B's camera, from A alone. With a correct
     * depth buffer and projection it matches B except where B sees what A could not.
     */
    private void saveValidation() {
        FrameSlot a = shown, b = held;
        if (a == null || b == null) return;
        Matrix4f aProj = new Matrix4f(a.proj), bProj = new Matrix4f(b.proj);
        dstViewRot.set(b.viewRot);
        for (int v = 0; v < 2; v++) {
            a.proj.set(v == 0 ? a.projBob : a.projPlain);
            b.proj.set(v == 0 ? b.projBob : b.projPlain);
            dstProj.set(b.proj);
            renderPass(new Vector3d(b.pos), a, null, 0f);
            DebugCapture.save(outTex, v == 0 ? "7-A-warped-to-B-bob" : "7-A-warped-to-B-plain");
        }
        a.proj.set(aProj);
        b.proj.set(bProj);
        DebugCapture.save(b.world, "7-B-world");
        captureLog.append(String.format(Locale.ROOT, "A bob proj %s%nB bob proj %s%nA plain proj %s%n", a.projBob, b.projBob, a.projPlain));
        captureLog.append(String.format(Locale.ROOT, "A pos %.3f %.3f %.3f  B pos %.3f %.3f %.3f%nA proj %s%nB proj %s%n",
                a.pos.x, a.pos.y, a.pos.z, b.pos.x, b.pos.y, b.pos.z, a.proj, b.proj));
    }

    private CommandEncoder renderInterpolated(double f) {
        FrameSlot a = shown, b = held;
        Vector3d dstPos = new Vector3d(a.pos).lerp(b.pos, f);
        lastGenF = f;
        lastGenPos.set(dstPos);
        a.rotation(q0).slerp(b.rotation(q1), (float) f, q2);
        viewBetween(a.viewRot, b.viewRot, (float) f, q2, dstViewRot);
        lerp(a.proj, b.proj, (float) f, dstProj);
        return renderPass(dstPos, a, b, (float) f);
    }

    /**
     * The camera's view matrix between two frames. It is not a pure rotation: view bobbing (and
     * the hurt shake) add a small offset, up and down as you walk. Taking only the rotation left
     * every generated frame without it, a few pixels above or below the real frames around it.
     */
    private static void viewBetween(Matrix4f a, Matrix4f b, float t, Quaternionf rot, Matrix4f out) {
        org.joml.Vector3f ta = a.getTranslation(new org.joml.Vector3f());
        org.joml.Vector3f tb = b.getTranslation(new org.joml.Vector3f());
        out.rotation(rot).setTranslation(ta.lerp(tb, t));
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
        if (!windowMatches(gr)) { skip("size"); return; }
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
        int m = framesPerReal(loopNs);
        if (m < 2) { skip("target"); return; }
        long budget = (long) (emaLoopNs * 0.5);
        capture = DebugCapture.begin();
        if (capture) {
            captureLog.setLength(0);
            captureLog.append(String.format(Locale.ROOT, "mode=low-latency multiplier=%d loopMs=%.2f%n",
                    m, loopNs / 1e6));
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
            viewBetween(a.viewRot, cam.viewRotationMatrix, (float) f, q2, dstViewRot);
            lerp(a.proj, curProj, (float) f, dstProj);
            if (capture) {
                captureLog.append(String.format(Locale.ROOT, "gen %d: f=%.3f, %.2f ms after the last real frame%n",
                        k, f, (System.nanoTime() - lastRealPresentNs) / 1e6));
            }
            boolean ok = presentGenerated(mc, surface, renderPass(dstPos, a, null, 0f), "low latency");
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
    private boolean presentGenerated(Minecraft mc, GpuSurface surface, CommandEncoder encoder, String where) {
        long t0 = System.nanoTime();
        try {
            return presentGenerated0(mc, surface, encoder, where);
        } finally {
            genCostNs += System.nanoTime() - t0;
        }
    }

    private boolean presentGenerated0(Minecraft mc, GpuSurface surface, CommandEncoder encoder, String where) {
        long t0 = System.nanoTime();
        if (VFrameGen.config().debugFrameLog) {
            DebugCapture.sequenceFrame(outTex, "gen", String.format(Locale.ROOT, "f=%.3f (%s) pos %.4f %.4f %.4f",
                    lastGenF, where, lastGenPos.x, lastGenPos.y, lastGenPos.z));
        }
        surface.blitFromTexture(encoder, outView);
        submittingGenerated = true;
        try {
            encoder.submit();
        } finally {
            submittingGenerated = false;
        }
        long t1 = System.nanoTime();
        surface.present();
        long t2 = System.nanoTime();
        costSubmitNs += t1 - t0;
        costPresentNs += t2 - t1;
        notePresent();
        screenEvent(System.nanoTime() + (gpuPassCount == 0 ? 1_000_000L : gpuPassNs / gpuPassCount), false);
        statsGenerated++;
        secGenerated++;
        // Count it in vanilla's FPS (F3 and anything that reads Minecraft.getFps()).
        MinecraftAccessor counter = (MinecraftAccessor) mc;
        counter.vframegen$setFrames(counter.vframegen$getFrames() + 1);
        if (noAcquireAfterPresent) return true;
        try {
            long t3 = System.nanoTime();
            surface.acquireNextTexture();
            costAcquireNs += System.nanoTime() - t3;
            return true;
        } catch (SurfaceException e) {
            // Same as vanilla: the surface is reconfigured next loop.
            recreateSurface(mc);
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
            // Shader packs keep a real depth buffer (verified with Iris + Complementary), so AUTO
            // reprojects with depth too.
            case AUTO -> true;
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
        GpuQueryPool timer = startGpuTimer(device, encoder);
        try (RenderPass pass = encoder.createRenderPass(() -> "VFrameGen", outView, Optional.empty())) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline(colorFormat, b != null)));
            bindSource(pass, "A", a, linear, nearest);
            if (b != null) bindSource(pass, "B", b, linear, nearest);
            pass.setUniform("FrameGen", ubo);
            pass.draw(3, 1, 0, 0);
        }
        if (timer != null) encoder.writeTimestamp(timer, 1);
        return encoder;
    }

    private GpuQueryPool startGpuTimer(GpuDevice device, CommandEncoder encoder) {
        readGpuTimers();
        if (gpuTimersUnsupported || gpuTimers.size() >= 8) return null;
        try {
            GpuQueryPool pool = device.createTimestampQueryPool(2);
            try {
                encoder.writeTimestamp(pool, 0);
            } catch (RuntimeException e) {
                pool.close();
                throw e;
            }
            gpuTimers.add(pool);
            return pool;
        } catch (RuntimeException e) {
            gpuTimersUnsupported = true;
            VFrameGen.LOGGER.info("GPU timestamps unavailable: {}", e.toString());
            return null;
        }
    }

    private void readGpuTimers() {
        while (!gpuTimers.isEmpty()) {
            GpuQueryPool pool = gpuTimers.peek();
            OptionalLong t0 = pool.getValue(0);
            OptionalLong t1 = pool.getValue(1);
            if (t0.isEmpty() || t1.isEmpty()) return;
            gpuTimers.poll();
            pool.close();
            gpuPassNs += Math.max(0, t1.getAsLong() - t0.getAsLong());
            gpuPassCount++;
        }
    }










    /** Mark the held item's pixels in slot c, once, for every generated frame that uses it. */
    private void buildMask(FrameSlot c) {
        GpuDevice device = RenderSystem.getDevice();
        boolean irisShaders = Compat.irisShadersActive();
        boolean zeroToOne = device.getDeviceInfo().isZZeroToOne();
        boolean handBuffer = c.hasHand && !irisShaders;
        boolean nearLock = !handBuffer && c.firstPerson;
        boolean reversedZ = ndcDepthAt(c.proj, 1.0f) > ndcDepthAt(c.proj, 100.0f);
        CommandEncoder encoder = device.createCommandEncoder();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer data = Std140Builder.onStack(stack, MASK_UBO_SIZE)
                    .putVec4(handBuffer ? 1f : 0f, nearLock ? 1f : 0f, depthAt(c.proj, LOCKED_DISTANCE, zeroToOne), 0f)
                    .putVec4(reversedZ ? 1f : 0f, zeroToOne ? 1f : 0f, 1f / width, 1f / height)
                    .get();
            encoder.writeToBuffer(maskUbo.slice(), data);
        }
        if (maskPipeline == null) {
            maskPipeline = RenderPipeline.builder()
                    .withLocation(Identifier.fromNamespaceAndPath(VFrameGen.MOD_ID, "pipeline/lockmask"))
                    .withVertexShader("core/screenquad")
                    .withFragmentShader(Identifier.fromNamespaceAndPath(VFrameGen.MOD_ID, "core/lockmask"))
                    .withBindGroupLayout(BindGroupLayout.builder()
                            .withUniform("Depth", UniformType.COMBINED_IMAGE_SAMPLER)
                            .withUniform("HandDepth", UniformType.COMBINED_IMAGE_SAMPLER)
                            .withUniform("MaskParams", UniformType.UNIFORM_BUFFER)
                            .build())
                    .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
                    .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                    .build();
        }
        GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        try (RenderPass pass = encoder.createRenderPass(() -> "VFrameGen hand mask", c.maskView, Optional.empty())) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(maskPipeline));
            pass.setUniform("Depth", c.depthView, nearest);
            pass.setUniform("HandDepth", c.handDepthView, nearest);
            pass.setUniform("MaskParams", maskUbo);
            pass.draw(3, 1, 0, 0);
        }
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
        // Needs the depth buffer, not depth reprojection: also on in rotation-only mode.
        boolean nearLock = !handBuffer && s.firstPerson;
        ub.putVec4(handBuffer ? 1f : 0f, nearLock ? 1f : 0f, depthAt(s.proj, LOCKED_DISTANCE, zeroToOne), useDepth ? 1f : 0f);
    }

    private static void bindSource(RenderPass pass, String id, FrameSlot s, GpuSampler linear, GpuSampler nearest) {
        pass.setUniform("World" + id, s.worldView, linear);
        pass.setUniform("Depth" + id, s.depthView, nearest);
        pass.setUniform("Mask" + id, s.maskView, nearest);
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
                for (String tex : new String[]{"World", "Depth", "Mask", "NoHand", "Final"}) {
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
        frameWorldDepthCaptured = copy(main.getDepthTexture(), c.depth) && copy(main.getColorTexture(), c.noHand);
    }

    /** Right after the held item is drawn, into a depth buffer that was cleared for it. */
    public void captureHandDepth(GameRenderer gr, boolean usedHudTarget) {
        if (!frameWorldDepthCaptured || building == null) return;
        RenderTarget hud = ((GameRendererAccessor) gr).vframegen$hud3DTarget();
        RenderTarget target = usedHudTarget && hud != null ? hud : gr.mainRenderTarget();
        GpuTexture handDepth = target.getDepthTexture();
        if (handDepth == null || handDepth.getFormat() != depthFormat
                || handDepth.getWidth(0) != renderWidth || handDepth.getHeight(0) != renderHeight) {
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
            // render3dHud did not run (or another mod replaced it): no separate hand pass. With
            // render scale the world's own (smaller) textures are the swapped-out ones.
            if (!copy(RenderScale.worldDepth(main), c.depth) || !copy(RenderScale.worldColor(main), c.noHand)) {
                c.valid = false;
                return;
            }
        }
        if (!copy(main.getColorTexture(), c.world)) {
            c.valid = false;
            return;
        }
        frameWorldCaptured = true;

        CameraRenderState cam = gr.gameRenderState().levelRenderState.cameraRenderState;
        c.pos.set(cam.pos.x, cam.pos.y, cam.pos.z);
        c.viewRot.set(cam.viewRotationMatrix);
        c.firstPerson = cam.isFirstPerson;
        if (frameProjCaptured) c.projBob.set(frameProj);
        else currentProjection(gr, gr.gameRenderState(), cam, c.projBob);
        c.projPlain.set(cam.projectionMatrix);
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
        buildMask(c);
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
        if (!copy(main.getColorTexture(), c.finalImage)) {
            c.valid = false;
            return null;
        }
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
            var window = gr.gameRenderState().windowRenderState;
            ensureResources(window.width, window.height, RenderScale.scaled(window.width), RenderScale.scaled(window.height),
                    color.getFormat(), depth.getFormat());
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

    private boolean windowMatches(GameRenderer gr) {
        var window = gr.gameRenderState().windowRenderState;
        return window.width == width && window.height == height;
    }

    /** Copy a whole texture; false (and nothing copied) if the sizes differ. */
    private boolean copy(GpuTexture src, GpuTexture dst) {
        int w = dst.getWidth(0);
        int h = dst.getHeight(0);
        if (src.getWidth(0) != w || src.getHeight(0) != h) return false;
        RenderSystem.getDevice().createCommandEncoder()
                .copyTextureToTexture(src, dst, 0, 0, 0, 0, 0, w, h);
        return true;
    }

    private void ensureResources(int w, int h, int rw, int rh, GpuFormat color, GpuFormat depth) {
        if (w == width && h == height && rw == renderWidth && rh == renderHeight
                && color == colorFormat && depth == depthFormat && outTex != null) return;
        // In low-latency mode only two slots are ever used; still allocate lazily per slot? Keep it
        // simple: all three, so switching modes needs no reallocation.
        for (FrameSlot s : slots) s.create(w, h, rw, rh, color, depth);
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
        if (maskUbo == null) {
            maskUbo = device.createBuffer(() -> "VFrameGen mask uniforms", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, MASK_UBO_SIZE);
        }
        width = w;
        height = h;
        renderWidth = rw;
        renderHeight = rh;
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
    /**
     * Present thread mode, right after vanilla's present was handed over: render the frames between
     * the frame just presented and the held one, put each in a swapchain image and hand it over too.
     * The present thread shows them at their time.
     */
    public void generateAfterPresent(Minecraft mc) {
        if (!presentThreadActive() || !active() || !interpolated() || !genPlanned) return;
        genPlanned = false;
        try {
            if (!canInterpolate(mc, false)) return;
            GpuSurface surface = mc.windowSurface();
            if (surface.isAcquired()) return;
            if (!capture && DebugCapture.begin()) {
                capture = true;
                captureLog.setLength(0);
                captureLog.append(String.format(Locale.ROOT, "mode=interpolated (present thread) multiplier=%d A->B ms=%.2f iris=%s%n",
                        frames, (held.extractNs - shown.extractNs) / 1e6, Compat.irisShadersActive()));
                DebugCapture.save(shown.finalImage, "0-real-A");
            }
            genStartNs = System.nanoTime();
            for (int k = 1; k < frames; k++) {
                try {
                    surface.acquireNextTexture();
                } catch (SurfaceException e) {
                    recreateSurface(mc);
                    return;
                }
                StallProbe.mark("gen acquire");
                nextFrame = k;
                PresentThread.setNext(PresentThread.Kind.GENERATED, k, frames);
                noAcquireAfterPresent = true;
                boolean ok;
                try {
                    ok = interpolateAndPresent(mc, surface, k / (double) frames, "present thread");
                } finally {
                    noAcquireAfterPresent = false;
                }
                if (!ok) return;
                StallProbe.mark("gen frame");
            }
            if (capture) {
                debugView = true;
                renderInterpolated((frames - 1) / (double) frames);
                debugView = false;
                DebugCapture.save(outTex, "8-debug-view");
                saveValidation();
            }
        } catch (RuntimeException e) {
            fail(e);
            // A swapchain image may still be acquired: have vanilla recreate the surface.
            recreateSurface(mc);
        }
    }

    public void afterRealPresent() {
        StallProbe.mark("vanilla present");
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
            logPacing();
            adaptCap();
            secReal = 0;
            secGenerated = 0;
            secStartNs = now;
        }
        if (statsStartNs == 0) statsStartNs = now;
        if (now - statsStartNs >= 5_000_000_000L) {
            double secs = (now - statsStartNs) / 1e9;
            if (VFrameGen.config().debugStatsLog) VFrameGen.LOGGER.info("Real {} fps, generated {} fps, shown {} fps ({}, {}), frame gap avg {} ms max {} ms, submit+present {} ms/real frame (submit {} present {} acquire {}), GPU pass {} ms/generated frame, real frame on screen {} ms after present, real interval {} ms{}{}",
                    Math.round(statsReal / secs), Math.round(statsGenerated / secs),
                    Math.round((statsReal + statsGenerated) / secs), backendName(),
                    interpolated() ? "interpolated" : "low latency",
                    String.format(Locale.ROOT, "%.1f", gapCount == 0 ? 0 : gapSumMs / gapCount),
                    String.format(Locale.ROOT, "%.1f", gapMaxMs),
                    String.format(Locale.ROOT, "%.1f", statsReal == 0 ? 0 : genCostNs / 1e6 / statsReal),
                    String.format(Locale.ROOT, "%.1f", statsReal == 0 ? 0 : costSubmitNs / 1e6 / statsReal),
                    String.format(Locale.ROOT, "%.1f", statsReal == 0 ? 0 : costPresentNs / 1e6 / statsReal),
                    String.format(Locale.ROOT, "%.1f", statsReal == 0 ? 0 : costAcquireNs / 1e6 / statsReal),
                    gpuPassCount == 0 ? "?" : String.format(Locale.ROOT, "%.2f", gpuPassNs / 1e6 / gpuPassCount),
                    String.format(Locale.ROOT, "%.1f", realDoneLagNs / 1e6),
                    String.format(Locale.ROOT, "%.1f", realIntervalNs / 1e6),
                    (presentThreadActive() ? ", " + PresentThread.stats() : "") + (statsSkips.isEmpty() ? "" : ", skipped " + statsSkips),
                    broken ? " [disabled after error]" : "");
            statsSkips.clear();
            gapSumMs = 0;
            gapMaxMs = 0;
            gapCount = 0;
            genCostNs = 0;
            costSubmitNs = costPresentNs = costAcquireNs = 0;
            gpuPassNs = 0;
            gpuPassCount = 0;
            statsStartNs = now;
            statsReal = 0;
            statsGenerated = 0;
        }
    }

    private static double ema(double avg, double sample) {
        return avg <= 0 ? sample : avg + (sample - avg) * 0.1;
    }
}
