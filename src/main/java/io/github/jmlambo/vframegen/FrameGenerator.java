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
import org.joml.Vector3f;
import org.joml.Matrix3f;
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

    /**
     * With a shader pack, anything nearer than this (blocks) is taken to be the held item. Iris
     * draws the hand with its depth squeezed to 1/8 (HandRenderer.DEPTH), so the hand reads far
     * nearer than any block can be; 0.5 also caught blocks the player stands against, which then
     * stayed unwarped and jumped between real frames.
     */
    private static final float LOCKED_DISTANCE = 0.2f;
    // + entity count, and per source FrameSlot.MAX_ENTITIES x (box min, box max, motion).
    private static final int UBO_SIZE = 7 * 64 + 6 * 16 + 2 * FrameSlot.MAX_ENTITIES * 5 * 16 + 2 * 64 + 3 * 16;

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
    // Motion blur (motionblur.fsh): its output, and the camera of the frame shown before.
    private GpuTexture blurTex;
    // Vulkan with a present queue: buffers generated frames wait in until due (PresentQueue).
    private final GpuTexture[] pendingTex = new GpuTexture[PresentQueue.SLOTS];
    private GpuTextureView blurView;
    private GpuBuffer blurUbo;
    private static final int BLUR_UBO_SIZE = 3 * 64 + 11 * 16 + FrameSlot.MAX_ENTITIES * 3 * 16;
    // Vanilla's vignette, taken from this real frame's GUI (drawn by motionblur.fsh).
    private static final Identifier VIGNETTE = Identifier.withDefaultNamespace("textures/misc/vignette.png");
    // vignetteOn is what shown frames use; vignetteNext is set while this loop's GUI is extracted,
    // and becomes vignetteOn when this loop's real frame is shown. Generated frames drawn at the
    // start of a loop (present thread off) keep the previous real frame's vignette.
    private boolean vignetteOn;
    private boolean vignetteNext;
    private int vignetteNextArgb;
    private boolean vignetteLogged;
    private float vignetteR, vignetteG, vignetteB;
    // The F3 crosshair, taken from the latest real frame's 3D HUD (drawn by motionblur.fsh).
    private boolean crosshairOn;
    private float crosshairFov = 70f;
    private int crosshairGuiScale = 1;
    private final Map<GpuFormat, RenderPipeline> blurPipelines = new HashMap<>();
    private final Vector3d prevShownPos = new Vector3d();
    private final Matrix4f prevShownViewRot = new Matrix4f();
    private final Matrix4f prevShownProj = new Matrix4f();
    private boolean prevShownValid;
    // The real frame the last generated frame was built from (its depth), and that frame's camera.
    private FrameSlot genSrc;
    private final Vector3d genPos = new Vector3d();
    // GPU time of the reprojection pass, from timestamp queries read back a few frames later.
    private final ArrayDeque<GpuQueryPool> gpuTimers = new ArrayDeque<>();
    private boolean gpuTimersUnsupported;
    private long gpuPassNs;
    private int gpuPassCount;
    // Smoothed GPU time of one generated frame (0: not measured yet), for adaptive mode's budget.
    private double gpuPassAvgNs;
    private int gpuPassStale; // timers still pending from before the last invalidate()
    // Smoothed render-thread time to hand one generated frame over (blit, submit, present). Small
    // while the GPU keeps up; when it is overloaded the hand-over waits for it, so this grows: the
    // copies, presents and other programs' GPU work (a screen recorder) the pass timer can't see.
    private double genWallAvgNs;

    private void noteGenWall(long ns) {
        genWallAvgNs = genWallAvgNs <= 0 ? ns : genWallAvgNs + (ns - genWallAvgNs) * 0.05;
    }
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
    // The camera view the world was drawn with under Aetherium (see captureOpaqueDepth).
    private final Matrix4f frameDrawnView = new Matrix4f();
    private boolean frameDrawnViewCaptured;
    /** The depth was taken before water and other translucents were drawn (captureOpaqueDepth). */
    private boolean frameOpaqueDepthCaptured;
    /** The depth was also taken before entities were drawn (captureTerrainDepth). */
    private boolean frameTerrainDepthCaptured;
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
                if (lastScreenReal && !real) { gapRgSum += g; gapRgN++; unevenRg += g; unevenRgN++; }
                if (!lastScreenReal && real) { gapGrSum += g; gapGrN++; unevenGr += g; unevenGrN++; }
                gapScrMin = Math.min(gapScrMin, g);
                gapScrMax = Math.max(gapScrMax, g);
                gapBuckets[g < 4 ? 0 : g < 8 ? 1 : g < 12 ? 2 : g < 20 ? 3 : g < 30 ? 4 : 5]++;
            }
        }
        lastScreenNs = Math.max(t, lastScreenNs);
        lastScreenReal = real;
        checkUneven(t);
    }

    // Without the present thread (OpenGL), generated frames can only go out on time when the GPU has
    // idle time between frames. With none they go out late, just before the next real frame (a
    // Radeon 660M: 24 ms after a real frame, then the real one 1.5 ms later), and motion judders
    // (moving mobs look torn; the screen can flicker in brightness). Judged on the gaps on screen:
    // an RTX 4050 on OpenGL missed the exact due times as often but its gaps were even (6.3 / 7.6 ms).
    // Said once per session, in the log.
    private double unevenRg, unevenGr;
    private int unevenRgN, unevenGrN, unevenWindows;
    private long unevenWindowStart;
    private boolean unevenWarned;

    private void checkUneven(long now) {
        if (unevenWarned) return;
        if (unevenWindowStart == 0) unevenWindowStart = now;
        if (now - unevenWindowStart < 5_000_000_000L) return;
        boolean bad = false;
        if (!presentThreadActive() && unevenRgN >= 50 && unevenGrN >= 50) {
            double rg = unevenRg / unevenRgN, gr = unevenGr / unevenGrN;
            bad = Math.max(rg, gr) > 3.0 * Math.max(0.1, Math.min(rg, gr));
            unevenWindows = bad ? unevenWindows + 1 : 0;
            if (unevenWindows >= 2) {
                unevenWarned = true;
                VFrameGen.LOGGER.warn("Generated frames cannot be shown evenly here: on screen, {} ms after a real frame, then {} ms "
                                + "until the next one ({} backend; the GPU has no time to spare between frames). Motion may judder, "
                                + "moving mobs may look torn and the screen may flicker in brightness. Frame generation is smoothest "
                                + "on Vulkan, where generated frames are shown from their own thread; on a GPU this busy, upscaling "
                                + "and anti-aliasing without frame generation may play better.",
                        String.format(Locale.ROOT, "%.1f", rg), String.format(Locale.ROOT, "%.1f", gr), backendName());
            }
        }
        if (!bad) unevenWindows = 0;
        unevenRg = unevenGr = 0;
        unevenRgN = unevenGrN = 0;
        unevenWindowStart = now;
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
        // The auto cap's samples carry over between seconds; not across a pause or settings change.
        fitCount = missCount = roomySeconds = badSeconds = 0;
        stepCheckSeconds = capHoldSeconds = 0;
        capHoldNext = 30;
        missBeforeStep = -1;
        sliding = false;
        minSlackMs = Double.MAX_VALUE;
        // A settings change can change what a generated frame costs: measure it again.
        gpuPassAvgNs = 0;
        genWallAvgNs = 0;
        gpuPassStale = gpuTimers.size();
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
    /** The frames per real frame the Vulkan swapchain was last sized for; -1 before the first one (or on OpenGL). */
    public static volatile int surfaceFrames = -1;

    /** After a settings screen saved: drop generated frames, and resize the swapchain if the multiplier changed. */
    public static void afterConfigSaved(Minecraft mc) {
        INSTANCE.invalidate();
        if (surfaceFrames >= 0 && VFrameGen.config().maxFramesPerReal() != surfaceFrames) {
            recreateSurface(mc);
        }
    }

    public static void recreateSurface(Minecraft mc) {
        MinecraftAccessor acc = (MinecraftAccessor) mc;
        acc.vframegen$setSurfaceIsInvalid(true);
        acc.vframegen$setWindowSurfaceNeedsReconfiguring(true);
    }

    private void fail(RuntimeException e) {
        broken = true;
        invalidate();
        // A draw that failed while binding textures leaves the game's OpenGL texture unit selected out of
        // range, and the next texture upload of the game itself (text, say) crashed on it.
        try {
            if ("OpenGL".equalsIgnoreCase(backendName())) com.mojang.renderpearl.backend.opengl.GlStateManager._activeTexture(33984);
        } catch (Throwable ignored) {
        }
        VFrameGen.LOGGER.error("Frame generation failed and has been switched off for this session", e);
    }

    // =============================================================================================
    // Game loop hooks
    // =============================================================================================

    /** Start of a game loop (vanilla has acquired this loop's swapchain image). */
    public void loopStart(Minecraft mc) {
        StallProbe.loopStart();
        VsyncGuard.enforce(mc);
        PackBlurCheck.tick(mc);
        Compat.aetheriumFrameStart();
        frameProjCaptured = false;
        frameDrawnViewCaptured = false;
        frameWorldDepthCaptured = false;
        frameOpaqueDepthCaptured = false;
        frameTerrainDepthCaptured = false;
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
    // Checking a step down: seconds left, the late share before it, and a pause after a useless one.
    private int stepCheckSeconds, capHoldSeconds;
    private double missBeforeStep = -1;
    private int capHoldNext = 30;
    // Stepping down: the adjustment from before the first step, to go back to if it does not help.
    private boolean sliding;
    private int slideStartAdjust;

    private void adaptCap() {
        // Changing the cap resets vanilla's limiter and the pacing estimate (one hitch each time),
        // so it changes rarely: down after two bad seconds in a row, up after ten roomy ones.
        // The wait after a useless step runs on wall-clock seconds (this runs once a second). A step is
        // judged only once enough generated frames were counted (below), which on a slow GPU takes
        // several seconds per tick.
        if (capHoldSeconds > 0) capHoldSeconds--;
        int total = fitCount + missCount;
        if (total >= 10 && lastAutoCap > 0) {
            double missRate = (double) missCount / total;
            boolean bad = missCount * 5 > total; // more than 20% early or late
            // (Some slack measured: frames made at loop start have none.)
            boolean roomy = missCount == 0 && minSlackMs < Double.MAX_VALUE && minSlackMs > 4.0;
            badSeconds = bad ? badSeconds + 1 : 0;
            roomySeconds = roomy ? roomySeconds + 1 : 0;
            // Generated frames fit again: the way down so far is kept.
            if (!bad && stepCheckSeconds == 0) sliding = false;
            if (stepCheckSeconds > 0 && --stepCheckSeconds == 0 && missRate > missBeforeStep * 0.75) {
                // The last step down did not make generated frames fit (the GPU, not the idle
                // time, is the limit): it only cost real frames. Undo the whole way down since
                // generated frames last fit (keeping steps that only seemed to help, since the late
                // share is noisy, ratcheted it down), and wait before trying again. Without this a
                // GPU-bound system went all the way down to the floor.
                capAdjust = slideStartAdjust;
                // Longer each time it does not help (each change is a small hitch): 30 s up to 5 min.
                capHoldSeconds = capHoldNext;
                capHoldNext = Math.min(300, capHoldNext * 2);
                badSeconds = 0;
                sliding = false;
                if (VFrameGen.config().debugPacingLog) VFrameGen.LOGGER.info("Auto frame cap: lowering did not help ({}% -> {}% late), back up",
                        Math.round(missBeforeStep * 100), Math.round(missRate * 100));
            } else if (badSeconds >= 2) {
                if (capHoldSeconds == 0 && stepCheckSeconds == 0 && lastAutoCap - 2 >= capFloor) {
                    if (!sliding) slideStartAdjust = capAdjust;
                    sliding = true;
                    capAdjust += 2;
                    missBeforeStep = missRate;
                    stepCheckSeconds = 3; // judged on the third second at the new cap
                }
                badSeconds = 0;
            } else if (stepCheckSeconds == 0 && missBeforeStep >= 0 && capAdjust > 0 && capHoldSeconds == 0) {
                // A step that held: the next useless one waits the shortest time again.
                capHoldNext = 30;
                missBeforeStep = -1;
            }
            if (roomySeconds >= 10 && capAdjust > 0) {
                capAdjust -= 2;
                roomySeconds = 0;
            }
            fitCount = missCount = 0;
            minSlackMs = Double.MAX_VALUE;
        }
        // Fewer than ten generated frames this second (adaptive's GPU budget can allow only a few
        // on a slow GPU): keep counting into the next second, so the cap can still come back up.
    }

    /**
     * The refresh rate of the monitor the window is on, rounded up to a whole number (179.96 Hz
     * reads 180), or 60 if unknown. (Its current mode: the window's "active video mode" is the
     * fullscreen setting and read 60 Hz on a 120 Hz screen.)
     */
    public static int detectedRefreshHz(Minecraft mc) {
        float refresh = 0f;
        try {
            refresh = mc.getWindow().findBestMonitor().currentMode().getRefreshRate();
        } catch (RuntimeException ignored) {
        }
        if (!(refresh >= 30f)) return 60;
        // Up, but not past a rate that is whole up to rounding error (60.0001 stays 60).
        return (int) Math.ceil(refresh - 0.05f);
    }

    public int frameLimit(Minecraft mc, int limit) {
        if (limit < 260 || !active() || !VFrameGen.config().autoCap) return limit;
        // Fixed multiplier: only interpolation needs the cap. Adaptive caps at the target in both modes.
        if (!interpolated() && !VFrameGen.config().adaptive) return limit;
        if (mc.level == null || mc.isPaused()) return limit;
        float refresh = VFrameGen.config().displayHz > 0 ? VFrameGen.config().displayHz : detectedRefreshHz(mc);
        int m = Math.clamp(VFrameGen.config().multiplier, 2, FrameGenConfig.MAX_MULTIPLIER);
        // Adaptive: real frames never beyond the target (frame generation makes up the rest).
        int full = VFrameGen.config().adaptive ? VFrameGen.config().targetFps
                : Math.max(1, (int) Math.floor(refresh / m));
        capFloor = Math.min(full, Math.max(20, full / 2));
        // With the present thread no idle time is needed: just never make more frames than the
        // display can show (the extra ones would be dropped unevenly).
        // The idle-time adjustment is measured (and recovered) only while interpolated frames are
        // generated. With adaptive generation at 1 frame per real frame nothing measures it, so
        // drop it: otherwise a lowered cap could hold the real frame rate below the target forever.
        if (VFrameGen.config().adaptive && adaptiveMultiplier() <= 1.0 + 1.0e-6) {
            capAdjust = 0;
            // Nothing is being stepped any more: a pending check would otherwise restore the
            // adjustment that was just dropped.
            stepCheckSeconds = 0;
            sliding = false;
            missBeforeStep = -1;
        }
        // A screen that stops generation: no frames to make room for (the adjustment is kept for later).
        boolean screenBlocks = VFrameGen.config().disableWhileScreenOpen && mc.gui.screen() != null;
        int cap = presentThreadActive() || !interpolated() || screenBlocks ? full : Math.max(capFloor, full - capAdjust);
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
                        "mode=interpolated multiplier=%d real interval %.2f ms, A on screen %.2f ms after present() A->B ms=%.2f shaders=%s%n",
                        frames, realIntervalNs / 1e6, (genStartNs - lastRealPresentNs) / 1e6,
                        (held.extractNs - shown.extractNs) / 1e6, Compat.shaderPackActive()));
                DebugCapture.save(shown.finalImage, "0-real-A");
                saveCapturedFlow();
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
        GpuTextureView shownView = v;
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
        // This loop's GUI has been extracted: its vignette (or none) applies from this real frame on.
        vignetteOn = vignetteNext;
        vignetteNext = false;
        vignetteR = ((vignetteNextArgb >> 16) & 0xFF) / 255f;
        vignetteG = ((vignetteNextArgb >> 8) & 0xFF) / 255f;
        vignetteB = (vignetteNextArgb & 0xFF) / 255f;
        return blurReal(shownView, vanillaView);
    }

    /** Motion blur for the real frame about to be shown (view): the slot it is, or vanilla's. */
    private GpuTextureView blurReal(GpuTextureView view, GpuTextureView vanillaView) {
        if (view == null || !active()) {
            prevShownValid = false;
            return view;
        }
        // Shown is the frame on screen now: the held one, or (vanilla view) the newest one.
        FrameSlot s = shown;
        if (s == null || !s.valid || (view != vanillaView && view != s.finalView)) {
            prevShownValid = false;
            return view;
        }
        try {
            return motionBlur(RenderSystem.getDevice().createCommandEncoder(), view, s, s.pos, s.viewRot, s.proj);
        } catch (RuntimeException e) {
            fail(e);
            return view;
        }
    }

    /**
     * Blur color (a frame about to be shown, for the camera pos / viewRot / proj) along the camera's
     * motion since the frame shown before it, using src's depth. Returns what to show: color itself
     * when blur is off or there is no previous frame to blur from.
     */
    private GpuTextureView motionBlur(CommandEncoder encoder, GpuTextureView color, FrameSlot src,
                                      Vector3d pos, Matrix4f viewRot, Matrix4f proj) {
        FrameGenConfig cfg = VFrameGen.config();
        boolean hadPrev = prevShownValid;
        Matrix4f prevViewProj = new Matrix4f(prevShownProj).mul(prevShownViewRot).translate(
                (float) (src.pos.x - prevShownPos.x), (float) (src.pos.y - prevShownPos.y), (float) (src.pos.z - prevShownPos.z));
        double jump = prevShownPos.distance(pos);
        long now = System.nanoTime();
        float shownDt = prevShownNs == 0 ? 0f : Math.min((now - prevShownNs) / 1.0e9f, 0.1f);
        prevShownNs = now;
        prevShownPos.set(pos);
        prevShownViewRot.set(viewRot);
        prevShownProj.set(proj);
        prevShownValid = true;
        // A teleport or respawn is not motion.
        boolean blur = cfg.motionBlur && hadPrev && jump <= cfg.maxCameraJumpBlocks;
        if ((!blur && !crosshairOn && !vignetteOn) || src == null || blurView == null) return color;
        if (!src.fixedMaskValid) {
            // An effect was switched on after this frame was captured (its mask is only drawn when
            // something reads it): draw it now, before this pass opens.
            if (!src.hasFinal) return color;
            try {
                buildFixedMask(src);
            } catch (RuntimeException e) {
                fail(e);
                return color;
            }
        }

        GpuDevice device = RenderSystem.getDevice();
        boolean zeroToOne = device.getDeviceInfo().isZZeroToOne();
        boolean reversedZ = ndcDepthAt(src.proj, 1.0f) > ndcDepthAt(src.proj, 100.0f);
        boolean handBuffer = src.hasHand && !Compat.shaderPackActive();
        boolean nearLock = !handBuffer && src.firstPerson;
        Matrix4f srcInvViewProj = new Matrix4f(src.proj).mul(src.viewRot).invert();
        Matrix4f curViewProj = new Matrix4f(proj).mul(viewRot).translate(
                (float) (src.pos.x - pos.x), (float) (src.pos.y - pos.y), (float) (src.pos.z - pos.z));
        try (MemoryStack stack = MemoryStack.stackPush()) {
            Std140Builder ub = Std140Builder.onStack(stack, BLUR_UBO_SIZE)
                    .putMat4f(srcInvViewProj).putMat4f(curViewProj).putMat4f(prevViewProj)
                    .putVec4(blur ? cfg.motionBlurStrength : 0f, reversedZ ? 1f : 0f, zeroToOne ? 1f : 0f, cfg.guiDiffThreshold)
                    .putVec4(1f / width, 1f / height, handBuffer ? 1f : 0f, nearLock ? 1f : 0f);
            putCrosshair(ub, viewRot);
            ub.putVec4(vignetteR, vignetteG, vignetteB, vignetteOn ? 1f : 0f);
            putBlurEntities(ub, src, shownDt, blur);
            ByteBuffer data = ub.get();
            encoder.writeToBuffer(blurUbo.slice(), data);
        }
        GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        // Before the pass: the first lookup loads (uploads) the texture, which can't happen inside one.
        GpuTextureView vignette = vignetteOn ? Minecraft.getInstance().getTextureManager().getTexture(VIGNETTE).getTextureView() : color;
        try (RenderPass pass = encoder.createRenderPass(() -> "VFrameGen motion blur", blurView, Optional.empty())) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(blurPipeline(colorFormat)));
            // Nearest, like the GUI / hand tests: a filtered tap next to the GUI would blend it in.
            pass.setUniform("Color", color, nearest);
            pass.setUniform("Fixed", src.fixedMaskView, nearest);
            pass.setUniform("Depth", src.depthView, nearest);
            pass.setUniform("Terrain", src.terrainDepthView, nearest);
            pass.setUniform("Vignette", vignette, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            pass.setUniform("MotionBlur", blurUbo);
            pass.draw(3, 1, 0, 0);
        }
        return blurView;
    }

    /**
     * Vanilla is about to draw the vignette with this color: true when this draws it instead
     * (frame gen on). Called while the GUI is extracted, every real frame with a vignette.
     */
    public boolean takeVignette(int argb) {
        if (!active()) return false;
        if (!vignetteLogged) {
            vignetteLogged = true;
            VFrameGen.LOGGER.info("Drawing the vignette on every shown frame");
        }
        vignetteNext = true;
        vignetteNextArgb = argb;
        return true;
    }

    /** Called at the start of each real frame's 3D HUD. */
    public void crosshairFrameStart() {
        crosshairOn = false;
    }

    /** Vanilla is about to draw the F3 crosshair: true when this draws it instead (frame gen on). */
    public boolean takeCrosshair(float hudFov, int guiScale) {
        if (!active()) return false;
        crosshairOn = true;
        crosshairFov = hudFov;
        crosshairGuiScale = guiScale;
        return true;
    }

    // Vanilla's crosshair: three axes from a point 1 block ahead, 0.01 * GUI scale long, turned by
    // the camera rotation (its rotateX / rotateY and mirrored scale are the view rotation), seen
    // through the 3D HUD projection.
    private static final float[][] CROSSHAIR_AXES = {{1f, 0f, 0f}, {0f, 1f, 0f}, {0f, 0f, 1f}};
    private static final float[][] CROSSHAIR_COLORS = {{1f, 0f, 0f}, {0f, 1f, 0f}, {0.5f, 0.5f, 1f}};

    private void putCrosshair(Std140Builder ub, Matrix4f viewRot) {
        if (!crosshairOn) {
            for (int i = 0; i < 7; i++) ub.putVec4(0f, 0f, 0f, 0f);
            return;
        }
        Matrix4f hudProj = new Matrix4f().perspective((float) Math.toRadians(crosshairFov), (float) width / height, 0.05f, 100f);
        Matrix3f rot = viewRot.normalize3x3(new Matrix3f());
        float len = 0.01f * crosshairGuiScale;
        Vector3f origin = new Vector3f(0f, 0f, -1f);
        Vector3f[] tips = new Vector3f[3];
        Integer[] order = {0, 1, 2};
        for (int i = 0; i < 3; i++) {
            float[] a = CROSSHAIR_AXES[i];
            tips[i] = rot.transform(new Vector3f(a[0], a[1], a[2])).mul(len).add(origin);
        }
        // Farthest first, so the nearest axis is drawn on top.
        java.util.Arrays.sort(order, (x, y) -> Float.compare(tips[x].z, tips[y].z));
        Vector4f o = hudProj.transform(new Vector4f(origin, 1f));
        ub.putVec4(o.x / o.w * 0.5f + 0.5f, o.y / o.w * 0.5f + 0.5f, 1f, 1f);
        for (int i : order) {
            Vector4f c = hudProj.transform(new Vector4f(tips[i], 1f));
            ub.putVec4(c.x / c.w * 0.5f + 0.5f, c.y / c.w * 0.5f + 0.5f, 0f, 0f);
        }
        for (int i : order) {
            float[] c = CROSSHAIR_COLORS[i];
            ub.putVec4(c[0], c[1], c[2], 1f);
        }
    }

    private long prevShownNs;

    /**
     * Moving entities, for motion blur: where each one's box is (relative to src's camera) and how
     * far it moved since the frame shown before. A mob the camera follows stays sharp.
     */
    private void putBlurEntities(Std140Builder ub, FrameSlot src, float dt, boolean on) {
        int n = on ? src.entityCount : 0;
        ub.putVec4(n, src.hasTerrainDepth ? 1f : 0f, 0f, 0f);
        for (int i = 0; i < FrameSlot.MAX_ENTITIES; i++) {
            if (i < n) {
                float ox = (float) (src.entityPos[i * 3] - src.pos.x);
                float oy = (float) (src.entityPos[i * 3 + 1] - src.pos.y);
                float oz = (float) (src.entityPos[i * 3 + 2] - src.pos.z);
                ub.putVec4(ox + src.entityBox[i * 6], oy + src.entityBox[i * 6 + 1], oz + src.entityBox[i * 6 + 2], 0f);
                ub.putVec4(ox + src.entityBox[i * 6 + 3], oy + src.entityBox[i * 6 + 4], oz + src.entityBox[i * 6 + 5], 0f);
                ub.putVec4(src.entityVel[i * 3] * dt, src.entityVel[i * 3 + 1] * dt, src.entityVel[i * 3 + 2] * dt, 0f);
            } else {
                ub.putVec4(0f, 0f, 0f, 0f).putVec4(0f, 0f, 0f, 0f).putVec4(0f, 0f, 0f, 0f);
            }
        }
    }

    private RenderPipeline blurPipeline(GpuFormat format) {
        return blurPipelines.computeIfAbsent(format, f -> RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath(VFrameGen.MOD_ID, "pipeline/motionblur_" + f.name().toLowerCase(Locale.ROOT)))
                .withVertexShader("core/screenquad")
                .withFragmentShader(Identifier.fromNamespaceAndPath(VFrameGen.MOD_ID, "core/motionblur"))
                .withBindGroupLayout(BindGroupLayout.builder()
                        .withUniform("Color", UniformType.COMBINED_IMAGE_SAMPLER)
                        .withUniform("Fixed", UniformType.COMBINED_IMAGE_SAMPLER)
                        .withUniform("Depth", UniformType.COMBINED_IMAGE_SAMPLER)
                        .withUniform("Terrain", UniformType.COMBINED_IMAGE_SAMPLER)
                        .withUniform("Vignette", UniformType.COMBINED_IMAGE_SAMPLER)
                        .withUniform("MotionBlur", UniformType.UNIFORM_BUFFER)
                        .build())
                .withColorTargetState(new ColorTargetState(Optional.empty(), f, ColorTargetState.WRITE_ALL))
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .build());
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

    // Adaptive mode: frames shown per real frame, the smoothed time between real frames, and the
    // fraction of a frame carried over to the next pair.
    private int adaptiveFrames = 2;
    private double adaptiveIntervalNs;
    private double adaptiveCarry;

    /**
     * Frames shown per real frame (1 = none generated) for a real frame that took intervalNs: the
     * multiplier, or in adaptive mode what reaches the target frame rate. That is rarely a whole
     * number (3.3x, say), so each pair of real frames gets the whole part and the remainder is
     * carried over: 3, 3, 4, 3, ... averages exactly 3.3, and the frame rate shown is the target
     * instead of the next whole multiple above it.
     */
    // Adaptive mode's ceiling while generated frames are being dropped (drawn, then no swapchain
    // image when due): each one cost GPU time a real frame could have used and was never shown, so
    // asking for fewer gives real frames that time back at no loss on screen. Fractional, like the
    // multiplier itself (3.4x alternates 3 and 4): lowered a tenth while drops happen, raised a
    // tenth after half a second without any, so it settles just under where drops start.
    private double dropCeiling = FrameGenConfig.ADAPTIVE_MAX_MULTIPLIER;
    private long dropCeilingLoweredNs, dropCeilingRaisedNs;
    private static final double DROP_CEILING_STEP = 0.1;
    // At most one step down per quarter second (drops come in bursts; per pair it sank far too
    // fast), one step up per half second without drops.
    private static final long DROP_CEILING_LOWER_NS = 250_000_000L;
    private static final long DROP_CEILING_RAISE_NS = 500_000_000L;

    // Share of the time between real frames that generated frames may take on the GPU. The GPU
    // that draws them also draws the real frames, so where one costs as much as a few ms (a laptop
    // iGPU at 1080p), asking for more and more of them to reach the target only slowed the real
    // frames, which asked for even more: 60 real fps fell to 15. With this share the time between
    // real frames settles at most 1 / (1 - share) of what it was (real fps keeps 75% or more).
    // No floor: where even one generated frame per real frame is too much, only some pairs get
    // one (1.4x), down to none.
    private static final double GPU_BUDGET_SHARE = 0.25;

    /** The most frames per real frame whose generated frames fit in that GPU budget. */
    private double gpuBudgetCeiling() {
        // What one generated frame costs: its pass on the GPU, plus any wait to hand it over.
        // Without the GPU timer (timestamp queries unsupported) the cost is unknown: the hand-over
        // wait alone would undercount it, so no budget rather than a wrong one.
        if (gpuPassAvgNs <= 0 || adaptiveIntervalNs <= 0) return FrameGenConfig.ADAPTIVE_MAX_MULTIPLIER;
        double cost = gpuPassAvgNs + Math.max(0, genWallAvgNs);
        return Math.clamp(1.0 + GPU_BUDGET_SHARE * adaptiveIntervalNs / cost, 1.0, FrameGenConfig.ADAPTIVE_MAX_MULTIPLIER);
    }

    private double adaptiveCeiling() {
        long now = System.nanoTime();
        if (PresentThread.takeDropped() > 0) {
            if (now - dropCeilingLoweredNs > DROP_CEILING_LOWER_NS) {
                // From the ceiling itself: from the whole multiplier just used, one drop during a
                // 4 of a 4.6 average went straight to 3.9.
                dropCeiling = Math.max(2.0, dropCeiling - DROP_CEILING_STEP);
                dropCeilingLoweredNs = now;
            }
            dropCeilingRaisedNs = now;
        } else if (dropCeiling < FrameGenConfig.ADAPTIVE_MAX_MULTIPLIER && now - dropCeilingRaisedNs > DROP_CEILING_RAISE_NS) {
            dropCeiling = Math.min(FrameGenConfig.ADAPTIVE_MAX_MULTIPLIER, dropCeiling + DROP_CEILING_STEP);
            dropCeilingRaisedNs = now;
        }
        return dropCeiling;
    }

    private int framesPerReal(long intervalNs) {
        FrameGenConfig cfg = VFrameGen.config();
        if (!cfg.adaptive) return Math.clamp(cfg.multiplier, 2, FrameGenConfig.MAX_MULTIPLIER);
        double most = adaptiveCeiling();
        if (intervalNs > 0 && intervalNs < 250_000_000L) {
            adaptiveIntervalNs = adaptiveIntervalNs <= 0 ? intervalNs
                    : adaptiveIntervalNs + (intervalNs - adaptiveIntervalNs) * 0.1;
        }
        if (adaptiveIntervalNs <= 0) return adaptiveFrames;
        most = Math.min(most, gpuBudgetCeiling());
        double ideal = Math.clamp(cfg.targetFps * adaptiveIntervalNs / 1e9, 1.0, most);
        adaptiveCarry += ideal;
        adaptiveFrames = (int) Math.clamp(Math.floor(adaptiveCarry + 1.0e-6), 1, (int) Math.ceil(most));
        adaptiveCarry = Math.clamp(adaptiveCarry - adaptiveFrames, 0.0, 1.0);
        return adaptiveFrames;
    }

    /** Frames per real frame adaptive mode is averaging now (for the F3 screen and the stats). */
    public double adaptiveMultiplier() {
        FrameGenConfig cfg = VFrameGen.config();
        if (!cfg.adaptive || adaptiveIntervalNs <= 0) return 0;
        return Math.clamp(cfg.targetFps * adaptiveIntervalNs / 1e9, 1.0, Math.min(dropCeiling, gpuBudgetCeiling()));
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

    /** F8: the optical flow of the captured pair (A to B), the one its generated frames use, if measured. */
    private void saveCapturedFlow() {
        OpticalFlow.Pair pair = flowFor(shown, held);
        if (pair != null) DebugCapture.save(pair.forward, "9-optical-flow");
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
        GpuTextureView shownView = genSrc != null ? motionBlur(encoder, outView, genSrc, genPos, dstViewRot, dstProj) : outView;
        surface.blitFromTexture(encoder, shownView);
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
        noteGenWall(t2 - t0);
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

    /** One reprojection draw into target. variant: 0 full resolution, 1 at the render scale (LOWRES), 2 the full-resolution GUI pass on top of its upscale (COMPOSITE). */
    private void reprojectPass(CommandEncoder encoder, GpuTextureView target, int variant, FrameSlot a, FrameSlot b,
                               GpuSampler linear, GpuSampler nearest) {
        try (RenderPass pass = encoder.createRenderPass(() -> variant == 1 ? "VFrameGen (render scale)" : variant == 2 ? "VFrameGen (full-resolution GUI)" : "VFrameGen", target, Optional.empty())) {
            // The flow textures only where they are used: the full-resolution pass after a render-scale frame
            // never reads them, and without a flow they would only be placeholders (2 of the 12 slots
            // the game's own OpenGL renderer has).
            boolean flowTex = b != null && variant != 2 && flow != null;
            pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline(colorFormat, b != null, variant, flowTex)));
            bindSource(pass, "A", a, linear, nearest);
            if (b != null) {
                bindSource(pass, "B", b, linear, nearest);
                if (flowTex) {
                    pass.setUniform("FlowA", flow.backwardView, linear);
                    pass.setUniform("FlowB", flow.forwardView, linear);
                }
            }
            if (variant == 2) pass.setUniform("Upscaled", upView, nearest);
            pass.setUniform("FrameGen", ubo);
            pass.draw(3, 1, 0, 0);
        }
    }

    // Generated frames at the render scale: the search (most of their GPU time) runs on the render
    // scale's pixels, the result is upscaled like a real frame, and a cheap full-resolution pass puts
    // the GUI and held item back on top, sharp.
    private GpuTexture lowTex, upTmpTex, upTex;
    private GpuTextureView lowView, upTmpView, upView;
    private int lowW, lowH, upW, upH;
    private GpuFormat lowFormat;

    private boolean generateAtRenderScale(boolean twoSources) {
        if (renderWidth >= width && renderHeight >= height) return false;
        // With two real frames its last pass reads 13 textures, one more than the game's own OpenGL renderer has
        // slots for (one real frame, low-latency mode: 7).
        if (twoSources && !TextureSlots.fitsUpscaled()) return false;
        FrameGenConfig cfg = VFrameGen.config();
        return cfg.upscaler != FrameGenConfig.Upscaler.TEMPORAL
                || cfg.generatedUpscaler != FrameGenConfig.GeneratedUpscaler.FULL_RESOLUTION;
    }

    /** Edge-adaptive or bicubic, the same as the real frames (Temporal: the generated-frame setting). */
    private static boolean generatedEdgeAdaptive() {
        FrameGenConfig cfg = VFrameGen.config();
        return switch (cfg.upscaler) {
            case BICUBIC -> false;
            case EDGE_ADAPTIVE -> true;
            case TEMPORAL -> cfg.generatedUpscaler != FrameGenConfig.GeneratedUpscaler.BICUBIC;
        };
    }

    private void ensureLowRes(GpuDevice device) {
        int usage = GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING;
        if (lowTex == null || lowW != renderWidth || lowH != renderHeight || lowFormat != colorFormat) {
            if (lowView != null) lowView.close();
            if (lowTex != null) lowTex.close();
            lowTex = device.createTexture("VFrameGen generated (render scale)", usage | GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC, colorFormat, renderWidth, renderHeight, 1, 1);
            lowView = device.createTextureView(lowTex);
            lowW = renderWidth;
            lowH = renderHeight;
        }
        if (upTex == null || upW != width || upH != height || lowFormat != colorFormat) {
            if (upTmpView != null) upTmpView.close();
            if (upTmpTex != null) upTmpTex.close();
            if (upView != null) upView.close();
            if (upTex != null) upTex.close();
            upTmpTex = device.createTexture("VFrameGen generated upscale", usage, colorFormat, width, height, 1, 1);
            upTmpView = device.createTextureView(upTmpTex);
            upTex = device.createTexture("VFrameGen generated upscaled", usage | GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC, colorFormat, width, height, 1, 1);
            upView = device.createTextureView(upTex);
            upW = width;
            upH = height;
        }
        lowFormat = colorFormat;
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
        boolean shaderPack = Compat.shaderPackActive();
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
        // Motion blur takes depth from the frame that builds most of the picture.
        genSrc = b != null && t >= 0.5f ? b : a;
        genPos.set(dstPos);
        boolean lowRes = generateAtRenderScale(b != null);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            Std140Builder ub = Std140Builder.onStack(stack, UBO_SIZE).putMat4f(dstInvViewProjRot);
            putSource(ub, a, dstViewProjRot, dstPos);
            putSource(ub, bb, dstViewProjRot, dstPos);
            ub.putVec4(cfg.guiDiffThreshold, cfg.debugTint ? 1f : 0f, cfg.sharpness, debugView ? 1f : 0f)
                    .putVec4(reversedZ ? 1f : 0f, zeroToOne ? 1f : 0f, 1f / width, 1f / height);
            putFlags(ub, a, shaderPack, useDepth, zeroToOne);
            putFlags(ub, bb, shaderPack, useDepth, zeroToOne);
            ub.putVec4(t, 0f, 0f, 0f);
            // Moving entities: only between two real frames (interpolated), where both positions are known.
            int countA = b != null ? putEntities(null, a, b, t, dstViewProjRot, dstPos) : 0;
            int countB = b != null ? putEntities(null, b, a, 1f - t, dstViewProjRot, dstPos) : 0;
            ub.putVec4(countA, countB, a.hasTerrainDepth ? 1f : 0f, (b != null ? b : a).hasTerrainDepth ? 1f : 0f);
            putEntities(ub, a, b, t, dstViewProjRot, dstPos);
            putEntities(ub, b != null ? b : a, b != null ? a : null, 1f - t, dstViewProjRot, dstPos);
            flow = b != null && VFrameGen.config().opticalFlow && TextureSlots.fitsFlow() ? flowFor(a, b) : null;
            ub.putMat4f(otherViewProj(a, bb)).putMat4f(otherViewProj(bb, a));
            // A flow texel times (fw, fh) is a uv offset.
            float fw = flow != null ? flow.pixelsPerUnit / flow.width : 0f, fh = flow != null ? flow.pixelsPerUnit / flow.height : 0f;
            // Forward and backward flow may disagree by a pixel (of the measured image) and still be trusted.
            ub.putVec4(flow != null ? 1f : 0f, 1f, fw, fh);
            // Measured with the camera turning and nothing else moving: below 2.5 pixels (or with the two
            // directions more than a pixel apart) the flow moved 1-7% of the screen that depth already
            // had right; above it, 0.05%, while burning mobs' fire was still followed.
            ub.putVec4(2.5f, 48f, flow != null ? flow.width : 0f, flow != null ? flow.height : 0f);
            ub.putVec4(lowRes ? (float) renderWidth / width : 1f,
                    lowRes && cfg.upscaler != FrameGenConfig.Upscaler.TEMPORAL ? 1f : 0f,
                    TemporalUpscaler.wanted() ? 1f : 0f, 0f);
            ByteBuffer data = ub.get();
            encoder.writeToBuffer(ubo.slice(), data);
        }

        GpuSampler linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        GpuQueryPool timer = startGpuTimer(device, encoder);
        if (lowRes) {
            ensureLowRes(device);
            reprojectPass(encoder, lowView, 1, a, b, linear, nearest);
            // Anti-aliased before the upscale (on the render scale's pixels, half the cost or less):
            // the real frames get it after theirs, but without it the generated frames' edges came out
            // jagged next to the real frames' smooth ones, and distant detail flickered.
            AntiAliasing.applyGenerated(lowTex, lowView);
            RenderScale.upscaleGenerated(encoder, lowView, renderWidth, renderHeight, upTmpView, upView,
                    width, height, colorFormat, generatedEdgeAdaptive());
            reprojectPass(encoder, outView, 2, a, b, linear, nearest);
        } else {
            reprojectPass(encoder, outView, 0, a, b, linear, nearest);
        }
        if (timer != null) encoder.writeTimestamp(timer, 1);
        return encoder;
    }

    /**
     * Entities of source {@code src} that also are in {@code other}: their box (relative to src's
     * camera) and how far they move from src to the output frame, a fraction {@code f} of the way to
     * other. Written to ub when it isn't null (always all MAX_ENTITIES entries, unused ones empty).
     * Returns how many there are. Root motion only: works the same for any model or animation mod
     * (EMF/ETF, Fresh Animations), since the model itself is never looked at.
     *
     * <p>Per entity: box min (w: its screen motion x, source to output, in uv), box max (w: y),
     * motion, and its screen rectangle in the output frame. The shader starts an extra search
     * from (output pixel - screen motion) inside that rectangle: an entity that moved farther than
     * its own width would otherwise not be found at all.
     */
    private int putEntities(Std140Builder ub, FrameSlot src, FrameSlot other, float f, Matrix4f dstViewProjRot, Vector3d dstPos) {
        int n = 0;
        Matrix4f srcViewProj = entSrcViewProj;
        Matrix4f dstViewProj = entDstViewProj;
        if (ub != null) {
            srcViewProj.set(src.proj).mul(src.viewRot);
            dstViewProj.set(dstViewProjRot).translate(
                    (float) (src.pos.x - dstPos.x), (float) (src.pos.y - dstPos.y), (float) (src.pos.z - dstPos.z));
        }
        if (other != null) {
            for (int i = 0; i < src.entityCount && n < FrameSlot.MAX_ENTITIES; i++) {
                int j = indexOf(other, src.entityIds[i]);
                if (j < 0) continue;
                double dx = other.entityPos[j * 3] - src.entityPos[i * 3];
                double dy = other.entityPos[j * 3 + 1] - src.entityPos[i * 3 + 1];
                double dz = other.entityPos[j * 3 + 2] - src.entityPos[i * 3 + 2];
                // Body rotation too (setting entityRotation, experimental): it turns the mob as well.
                float turn = VFrameGen.config().entityRotation
                        ? (float) Math.toRadians(net.minecraft.util.Mth.wrapDegrees(other.entityYaw[j] - src.entityYaw[i])) : 0f;
                double moved2 = dx * dx + dy * dy + dz * dz;
                if ((moved2 < 1.0e-8 && Math.abs(turn) < 1.0e-4f) || moved2 > 64.0) continue; // still, or teleported
                if (ub != null) {
                    float ox = (float) (src.entityPos[i * 3] - src.pos.x);
                    float oy = (float) (src.entityPos[i * 3 + 1] - src.pos.y);
                    float oz = (float) (src.entityPos[i * 3 + 2] - src.pos.z);
                    float[] bx = src.entityBox;
                    float mx = (float) dx * f, my = (float) dy * f, mz = (float) dz * f;
                    float rot = turn * f, rc = (float) Math.cos(rot), rs = (float) Math.sin(rot);
                    float x0 = ox + bx[i * 6], y0 = oy + bx[i * 6 + 1], z0 = oz + bx[i * 6 + 2];
                    float x1 = ox + bx[i * 6 + 3], y1 = oy + bx[i * 6 + 4], z1 = oz + bx[i * 6 + 5];
                    // Screen motion of the box centre, and the moved box's rectangle in the output.
                    float cx = (x0 + x1) * 0.5f, cy = (y0 + y1) * 0.5f, cz = (z0 + z1) * 0.5f;
                    org.joml.Vector4f cs = srcViewProj.transform(entCs.set(cx, cy, cz, 1f));
                    org.joml.Vector4f cd = dstViewProj.transform(entCd.set(cx + mx, cy + my, cz + mz, 1f));
                    float su = 0f, sv = 0f;
                    float rx0 = 2f, ry0 = 2f, rx1 = -1f, ry1 = -1f; // empty unless the whole box is in front
                    if (cs.w > 1.0e-4f && cd.w > 1.0e-4f) {
                        su = (cd.x / cd.w - cs.x / cs.w) * 0.5f;
                        sv = (cd.y / cd.w - cs.y / cs.w) * 0.5f;
                        boolean front = true;
                        float ux0 = 2f, uy0 = 2f, ux1 = -1f, uy1 = -1f;
                        for (int k = 0; k < 8 && front; k++) {
                            // The corner turned about the entity's position, then moved.
                            float kx = ((k & 1) == 0 ? x0 : x1) - ox, kz = ((k & 4) == 0 ? z0 : z1) - oz;
                            org.joml.Vector4f c = dstViewProj.transform(entCorner.set(
                                    ox + kx * rc - kz * rs + mx, ((k & 2) == 0 ? y0 : y1) + my, oz + kx * rs + kz * rc + mz, 1f));
                            if (c.w <= 1.0e-4f) {
                                front = false;
                                break;
                            }
                            float u = c.x / c.w * 0.5f + 0.5f, v = c.y / c.w * 0.5f + 0.5f;
                            ux0 = Math.min(ux0, u);
                            uy0 = Math.min(uy0, v);
                            ux1 = Math.max(ux1, u);
                            uy1 = Math.max(uy1, v);
                        }
                        if (front) {
                            rx0 = ux0;
                            ry0 = uy0;
                            rx1 = ux1;
                            ry1 = uy1;
                        }
                    }
                    ub.putVec4(x0, y0, z0, su);
                    ub.putVec4(x1, y1, z1, sv);
                    ub.putVec4(mx, my, mz, rot);
                    ub.putVec4(rx0, ry0, rx1, ry1);
                    ub.putVec4(ox, oy, oz, 0f);
                }
                n++;
            }
        }
        if (ub != null) {
            for (int i = n; i < FrameSlot.MAX_ENTITIES; i++) {
                ub.putVec4(0f, 0f, 0f, 0f).putVec4(-1f, -1f, -1f, 0f).putVec4(0f, 0f, 0f, 0f).putVec4(2f, 2f, -1f, -1f)
                        .putVec4(0f, 0f, 0f, 0f);
            }
        }
        return n;
    }

    // Scratch for putEntities (render thread only), so it allocates nothing per frame.
    private final Matrix4f entSrcViewProj = new Matrix4f(), entDstViewProj = new Matrix4f();
    private final org.joml.Vector4f entCs = new org.joml.Vector4f(), entCd = new org.joml.Vector4f(), entCorner = new org.joml.Vector4f();

    private static int indexOf(FrameSlot s, int id) {
        for (int i = 0; i < s.entityCount; i++) if (s.entityIds[i] == id) return i;
        return -1;
    }

    // Reused every frame: the nearest entities so far, kept sorted by distance.
    private final net.minecraft.world.entity.Entity[] nearEntities = new net.minecraft.world.entity.Entity[FrameSlot.MAX_ENTITIES];
    private final double[] nearDist = new double[FrameSlot.MAX_ENTITIES];
    private final float[] nearTick = new float[FrameSlot.MAX_ENTITIES];

    private static final double ENTITY_SIDE_PAD = 0.3;

    /**
     * How far an entity's box may reach past its hitbox on one side (axis 0 = x, 2 = z; dir -1 or
     * 1): ENTITY_SIDE_PAD, or less when a block is nearer, stopping just short of its face.
     */
    private static double sidePad(Minecraft mc, net.minecraft.world.entity.Entity e,
            net.minecraft.world.phys.AABB hb, int axis, int dir) {
        double edge = axis == 0 ? (dir > 0 ? hb.maxX : hb.minX) : (dir > 0 ? hb.maxZ : hb.minZ);
        double lo = dir > 0 ? edge : edge - ENTITY_SIDE_PAD, hi = dir > 0 ? edge + ENTITY_SIDE_PAD : edge;
        net.minecraft.world.phys.AABB slab = axis == 0
                ? new net.minecraft.world.phys.AABB(lo, hb.minY + 0.02, hb.minZ, hi, hb.maxY, hb.maxZ)
                : new net.minecraft.world.phys.AABB(hb.minX, hb.minY + 0.02, lo, hb.maxX, hb.maxY, hi);
        double pad = ENTITY_SIDE_PAD;
        for (net.minecraft.world.phys.shapes.VoxelShape shape : mc.level.getBlockCollisions(e, slab)) {
            for (net.minecraft.world.phys.AABB b : shape.toAabbs()) {
                if (!b.intersects(slab)) continue;
                double face = axis == 0 ? (dir > 0 ? b.minX - edge : edge - b.maxX)
                        : (dir > 0 ? b.minZ - edge : edge - b.maxZ);
                pad = Math.min(pad, face - 0.02);
            }
        }
        return Math.max(pad, -0.05); // slightly inside the hitbox when it touches the block
    }

    /** The entities near the camera this frame, nearest first (FrameSlot.MAX_ENTITIES at most). */
    private void captureEntities(FrameSlot c, boolean firstPerson) {
        c.entityCount = 0;
        Minecraft mc = Minecraft.getInstance();
        // Only interpolation uses them (low latency has no next frame to take motion from).
        if (mc.level == null || !interpolated()) return;
        var ticks = mc.level.tickRateManager();
        var delta = mc.getDeltaTracker();
        net.minecraft.world.entity.Entity camera = mc.getCameraEntity();
        int n = 0;
        for (net.minecraft.world.entity.Entity e : mc.level.entitiesForRendering()) {
            if (e == camera && firstPerson) continue;
            // The partial tick its renderer uses (frozen entities stay put during /tick freeze).
            float pt = delta.getGameTimeDeltaPartialTick(!ticks.isEntityFrozen(e));
            double x = net.minecraft.util.Mth.lerp(pt, e.xo, e.getX()) - c.pos.x;
            double y = net.minecraft.util.Mth.lerp(pt, e.yo, e.getY()) - c.pos.y;
            double z = net.minecraft.util.Mth.lerp(pt, e.zo, e.getZ()) - c.pos.z;
            double d2 = x * x + y * y + z * z;
            if (d2 > 96.0 * 96.0) continue;
            if (n == FrameSlot.MAX_ENTITIES && d2 >= nearDist[n - 1]) continue;
            int k = n < FrameSlot.MAX_ENTITIES ? n++ : n - 1;
            while (k > 0 && nearDist[k - 1] > d2) {
                nearDist[k] = nearDist[k - 1];
                nearEntities[k] = nearEntities[k - 1];
                nearTick[k] = nearTick[k - 1];
                k--;
            }
            nearDist[k] = d2;
            nearEntities[k] = e;
            nearTick[k] = pt;
        }
        for (int k = 0; k < n; k++) {
            net.minecraft.world.entity.Entity e = nearEntities[k];
            nearEntities[k] = null;
            float pt = nearTick[k];
            double px = net.minecraft.util.Mth.lerp(pt, e.xo, e.getX());
            double py = net.minecraft.util.Mth.lerp(pt, e.yo, e.getY());
            double pz = net.minecraft.util.Mth.lerp(pt, e.zo, e.getZ());
            // The box around the entity's position, padded: models (and model mods such as
            // Fresh Animations) reach past the hitbox. Not below its feet, and on each side only up
            // to the nearest block there: the floor it walks on and walls next to it would move with
            // it (flicker).
            net.minecraft.world.phys.AABB world = e.getBoundingBox();
            net.minecraft.world.phys.AABB hit = world.move(-e.getX(), -e.getY(), -e.getZ());
            net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(
                    hit.minX - sidePad(mc, e, world, 0, -1), hit.minY + 0.02, hit.minZ - sidePad(mc, e, world, 2, -1),
                    hit.maxX + sidePad(mc, e, world, 0, 1), hit.maxY + 0.35, hit.maxZ + sidePad(mc, e, world, 2, 1));
            int i = c.entityCount++;
            c.entityIds[i] = e.getId();
            c.entityPos[i * 3] = px;
            c.entityPos[i * 3 + 1] = py;
            c.entityPos[i * 3 + 2] = pz;
            c.entityBox[i * 6] = (float) box.minX;
            c.entityBox[i * 6 + 1] = (float) box.minY;
            c.entityBox[i * 6 + 2] = (float) box.minZ;
            c.entityBox[i * 6 + 3] = (float) box.maxX;
            c.entityBox[i * 6 + 4] = (float) box.maxY;
            c.entityBox[i * 6 + 5] = (float) box.maxZ;
            // Per tick to per second, at the tick rate (frozen: no motion).
            float tps = ticks.isEntityFrozen(e) ? 0f : ticks.tickrate();
            c.entityVel[i * 3] = (float) ((e.getX() - e.xo) * tps);
            c.entityVel[i * 3 + 1] = (float) ((e.getY() - e.yo) * tps);
            c.entityVel[i * 3 + 2] = (float) ((e.getZ() - e.zo) * tps);
            // The body's rotation as drawn (living entities turn their body; others their whole model).
            c.entityYaw[i] = e instanceof net.minecraft.world.entity.LivingEntity le
                    ? net.minecraft.util.Mth.rotLerp(pt, le.yBodyRotO, le.yBodyRot)
                    : net.minecraft.util.Mth.rotLerp(pt, e.yRotO, e.getYRot());
        }
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
            long pass = Math.max(0, t1.getAsLong() - t0.getAsLong());
            gpuPassNs += pass;
            gpuPassCount++;
            if (gpuPassStale > 0) gpuPassStale--; // measured before invalidate(): the old workload
            else gpuPassAvgNs = gpuPassAvgNs <= 0 ? pass : gpuPassAvgNs + (pass - gpuPassAvgNs) * 0.05;
        }
    }










    /** Mark the held item's pixels in slot c, once, for every generated frame that uses it. */
    private void buildMask(FrameSlot c) {
        GpuDevice device = RenderSystem.getDevice();
        boolean shaderPack = Compat.shaderPackActive();
        boolean zeroToOne = device.getDeviceInfo().isZZeroToOne();
        boolean handBuffer = c.hasHand && !shaderPack;
        boolean nearLock = !handBuffer && c.firstPerson;
        boolean reversedZ = ndcDepthAt(c.proj, 1.0f) > ndcDepthAt(c.proj, 100.0f);
        CommandEncoder encoder = device.createCommandEncoder();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer data = Std140Builder.onStack(stack, MASK_UBO_SIZE)
                    .putVec4(handBuffer ? 1f : 0f, nearLock ? 1f : 0f, depthAt(c.proj, LOCKED_DISTANCE, zeroToOne), 0f)
                    .putVec4(reversedZ ? 1f : 0f, c.hasHand && Compat.aetheriumShadersActive() ? 1f : 0f, 1f / width, 1f / height)
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

    private RenderPipeline fixedMaskPipeline;
    private GpuBuffer fixedMaskUbo;

    /**
     * Mark slot c's GUI pixels (r) and GUI-or-held-item pixels (g), once per real frame, so the
     * motion blur on every shown frame reads one texel per tap instead of comparing two full
     * images and the hand mask each time.
     */
    private void buildFixedMask(FrameSlot c) {
        GpuDevice device = RenderSystem.getDevice();
        boolean handBuffer = c.hasHand && !Compat.shaderPackActive();
        boolean nearLock = !handBuffer && c.firstPerson;
        CommandEncoder encoder = device.createCommandEncoder();
        if (fixedMaskUbo == null) {
            fixedMaskUbo = device.createBuffer(() -> "VFrameGen GUI mask uniforms", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 16);
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            encoder.writeToBuffer(fixedMaskUbo.slice(), Std140Builder.onStack(stack, 16)
                    .putVec4(VFrameGen.config().guiDiffThreshold, handBuffer ? 1f : 0f, nearLock ? 1f : 0f, 0f).get());
        }
        if (fixedMaskPipeline == null) {
            fixedMaskPipeline = RenderPipeline.builder()
                    .withLocation(Identifier.fromNamespaceAndPath(VFrameGen.MOD_ID, "pipeline/guimask"))
                    .withVertexShader("core/screenquad")
                    .withFragmentShader(Identifier.fromNamespaceAndPath(VFrameGen.MOD_ID, "core/guimask"))
                    .withBindGroupLayout(BindGroupLayout.builder()
                            .withUniform("World", UniformType.COMBINED_IMAGE_SAMPLER)
                            .withUniform("Final", UniformType.COMBINED_IMAGE_SAMPLER)
                            .withUniform("Mask", UniformType.COMBINED_IMAGE_SAMPLER)
                            .withUniform("GuiMaskParams", UniformType.UNIFORM_BUFFER)
                            .build())
                    .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
                    .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                    .build();
        }
        GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        try (RenderPass pass = encoder.createRenderPass(() -> "VFrameGen GUI mask", c.fixedMaskView, Optional.empty())) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(fixedMaskPipeline));
            pass.setUniform("World", c.worldView, nearest);
            pass.setUniform("Final", c.finalView, nearest);
            pass.setUniform("Mask", c.maskView, nearest);
            pass.setUniform("GuiMaskParams", fixedMaskUbo);
            pass.draw(3, 1, 0, 0);
        }
        c.fixedMaskValid = true;
    }

    /** A source's matrices; positions are relative to that source's camera. */
    private static void putSource(Std140Builder ub, FrameSlot s, Matrix4f dstViewProjRot, Vector3d dstPos) {
        Matrix4f viewProjRot = new Matrix4f(s.proj).mul(s.viewRot);
        Matrix4f invViewProj = new Matrix4f(viewProjRot).invert();
        Matrix4f dstViewProj = new Matrix4f(dstViewProjRot).translate(
                (float) (s.pos.x - dstPos.x), (float) (s.pos.y - dstPos.y), (float) (s.pos.z - dstPos.z));
        ub.putMat4f(invViewProj).putMat4f(dstViewProj).putMat4f(viewProjRot);
    }

    private void putFlags(Std140Builder ub, FrameSlot s, boolean shaderPack, boolean useDepth, boolean zeroToOne) {
        // A shader pack draws the held item itself, so vanilla's hand buffers mean nothing with one on.
        boolean handBuffer = s.hasHand && !shaderPack;
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
        pass.setUniform("Terrain" + id, s.terrainDepthView, nearest);
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

    /** Position relative to s's camera -> clip space of other (optical flow: where depth puts a pixel there). */
    private static Matrix4f otherViewProj(FrameSlot s, FrameSlot other) {
        return new Matrix4f(other.proj).mul(other.viewRot).translate(
                (float) (s.pos.x - other.pos.x), (float) (s.pos.y - other.pos.y), (float) (s.pos.z - other.pos.z));
    }

    private OpticalFlow.Pair flow;

    /** Shader optical flow where there is no optical flow hardware. */
    private static boolean useShaderFlow() {
        return !OpticalFlow.available();
    }

    /** The optical flow measured between real frames a and b, from whichever path measures it, or null. */
    private static OpticalFlow.Pair flowFor(FrameSlot a, FrameSlot b) {
        return useShaderFlow() ? ShaderFlow.forPair(a.noHand, b.noHand) : OpticalFlow.forPair(a.noHand, b.noHand);
    }
    private RenderPipeline pipeline(GpuFormat format, boolean interpolate, int variant, boolean flowTex) {
        String key = format.name() + (interpolate ? "_interpolate" : "") + (flowTex ? "_flow" : "") + (variant == 1 ? "_lowres" : variant == 2 ? "_composite" : "");
        return pipelines.computeIfAbsent(key, k -> {
            BindGroupLayout.Builder layout = BindGroupLayout.builder();
            for (String id : interpolate ? new String[]{"A", "B"} : new String[]{"A"}) {
                for (String tex : new String[]{"World", "Depth", "Mask", "NoHand", "Final", "Terrain"}) {
                    layout.withUniform(tex + id, UniformType.COMBINED_IMAGE_SAMPLER);
                }
            }
            if (interpolate && flowTex) {
                layout.withUniform("FlowA", UniformType.COMBINED_IMAGE_SAMPLER);
                layout.withUniform("FlowB", UniformType.COMBINED_IMAGE_SAMPLER);
            }
            if (variant == 2) layout.withUniform("Upscaled", UniformType.COMBINED_IMAGE_SAMPLER);
            layout.withUniform("FrameGen", UniformType.UNIFORM_BUFFER);
            RenderPipeline.Builder builder = RenderPipeline.builder()
                    .withLocation(Identifier.fromNamespaceAndPath(VFrameGen.MOD_ID, "pipeline/reproject_" + k.toLowerCase(Locale.ROOT)))
                    .withVertexShader("core/screenquad")
                    .withFragmentShader(Identifier.fromNamespaceAndPath(VFrameGen.MOD_ID, "core/reproject"))
                    .withBindGroupLayout(layout.build())
                    .withColorTargetState(new ColorTargetState(Optional.empty(), format, ColorTargetState.WRITE_ALL))
                    .withPrimitiveTopology(PrimitiveTopology.TRIANGLES);
            if (interpolate) builder.withShaderDefine("INTERPOLATE");
            if (interpolate && flowTex) builder.withShaderDefine("FLOWTEX");
            if (variant == 1) builder.withShaderDefine("LOWRES");
            if (variant == 2) builder.withShaderDefine("COMPOSITE");
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

    /**
     * Whether the depth before entities is needed (captureTerrainDepth). Only interpolation moves
     * entities. With Iris, entities are drawn in a pass of their own so the depth can be kept in
     * between (IrisPipelineMixin draws them while its opaque targets are still in use). With
     * Vitrail, entities stay in the level's own pass — moving them makes them disappear — and the
     * depth is instead copied in place, just before that pass (LevelRendererMixin).
     */
    public boolean wantsTerrainDepth() {
        return active() && interpolated();
    }

    /**
     * Whether the level renderer should keep the depth from before translucents (see
     * captureOpaqueDepth). Not with Iris: it keeps that depth itself (irisDepthNoTranslucents).
     * With Vitrail it is wanted, but taken in place rather than by deferring the translucents into
     * a pass of VFrameGen's (see LevelRendererMixin and Compat.vitrailShadersActive).
     */
    public boolean wantsOpaqueDepth() {
        return active() && !Compat.irisShadersActive();
    }

    /**
     * Right before water and other translucents are drawn: the depth of the opaque world. Generated
     * frames move each pixel by its depth, and both transparency modes leave the water surface's
     * depth in the depth buffer, so blocks seen through water moved as if they were at the surface
     * (several times too far when the surface is close) and snapped back at each real frame. With
     * this depth they move right; the water surface on top of them is mostly a flat texture.
     */
    public void captureOpaqueDepth(GameRenderer gr) {
        FrameSlot c = prepare(gr);
        if (c == null) return;
        frameOpaqueDepthCaptured = copy(gr.mainRenderTarget().getDepthTexture(), c.depth);
        if (Compat.aetheriumShadersActive()) {
            // Aetherium puts view bobbing and the hurt tilt into the camera's view instead of the
            // projection, and takes them out again before the held item: read it while it is in.
            frameDrawnView.set(gr.gameRenderState().levelRenderState.cameraRenderState.viewRotationMatrix);
            frameDrawnViewCaptured = true;
        }
    }

    /**
     * After the opaque terrain, before entities and other solid features are drawn. Pixels whose
     * depth differs from this in the final depth were drawn by an entity (or a block entity): only
     * those move with an entity, not the blocks around it inside its padded box.
     */
    public void captureTerrainDepth(GameRenderer gr) {
        FrameSlot c = prepare(gr);
        if (c == null) return;
        frameTerrainDepthCaptured = copy(gr.mainRenderTarget().getDepthTexture(), c.terrainDepth);
    }

    /** Right before the held item is drawn: the depth buffer still has the whole world in it. */
    public void captureWorldDepth(GameRenderer gr) {
        FrameSlot c = prepare(gr);
        // Aetherium finishes the pack's image after the held item: both are taken in captureWorld.
        if (c == null || Compat.aetheriumShadersActive()) return;
        RenderTarget main = gr.mainRenderTarget();
        boolean depth = frameOpaqueDepthCaptured || copy(main.getDepthTexture(), c.depth);
        frameWorldDepthCaptured = depth && copy(main.getColorTexture(), c.noHand);
    }

    /** Right after the held item is drawn, into a depth buffer that was cleared for it. */
    public void captureHandDepth(GameRenderer gr, boolean usedHudTarget) {
        boolean aetherium = Compat.aetheriumShadersActive();
        if (aetherium ? prepare(gr) == null : !frameWorldDepthCaptured || building == null) return;
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
        if (Compat.aetheriumShadersActive()) {
            // The finished image. The depth from before translucents is already there, and the
            // held item's mask reads its own copy (captureHandDepth).
            if (!(frameOpaqueDepthCaptured || copy(RenderScale.worldDepth(main), c.depth))
                    || !copy(RenderScale.worldColor(main), c.noHand)) {
                c.valid = false;
                return;
            }
        } else if (!frameWorldDepthCaptured) {
            // render3dHud did not run (or another mod replaced it): no separate hand pass. With
            // render scale the world's own (smaller) textures are the swapped-out ones.
            if (!(frameOpaqueDepthCaptured || copy(RenderScale.worldDepth(main), c.depth))
                    || !copy(RenderScale.worldColor(main), c.noHand)) {
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
        captureEntities(c, cam.isFirstPerson);
        if (frameProjCaptured) c.projBob.set(frameProj);
        else currentProjection(gr, gr.gameRenderState(), cam, c.projBob);
        c.projPlain.set(cam.projectionMatrix);
        if (frameProjCaptured) {
            c.proj.set(frameProj);
        } else {
            currentProjection(gr, gr.gameRenderState(), cam, c.proj);
        }
        if (frameDrawnViewCaptured) {
            // The same effects, folded into the projection as without Aetherium.
            Matrix4f effects = new Matrix4f(frameDrawnView).mul(new Matrix4f(cam.viewRotationMatrix).invert());
            c.projBob.mul(effects);
            c.proj.mul(effects);
        }
        c.extractNs = frameExtractNs != 0 ? frameExtractNs : System.nanoTime();
        c.hasHand = frameHandCaptured;
        c.hasTerrainDepth = frameTerrainDepthCaptured;
        c.level = Minecraft.getInstance().level;
        c.hasFinal = false;
        c.valid = true;
        // Interpolated mode only: the flow is between two real frames, and only that mode has both.
        if (interpolated() && TextureSlots.fitsFlow() && (VFrameGen.config().opticalFlow || VFrameGen.config().debugOpticalFlow)) {
            if (useShaderFlow()) ShaderFlow.measure(c.noHand);
            else OpticalFlow.measure(c.noHand);
        }
        buildMask(c);
        // With a shader pack the depth buffer has the water surface in it (see captureOpaqueDepth);
        // Iris keeps the depth from before translucents. Taken after buildMask, which needs the
        // held item's depth (not in that copy); the mask keeps the item locked.
        if (Compat.irisShadersActive()) {
            GpuTexture opaque = Compat.irisDepthNoTranslucents();
            if (opaque != null) drawDepth(opaque, c);
        }
    }

    private GpuTexture irisDepthSource;
    private GpuTextureView irisDepthSourceView;

    /** Iris' textures can't be copied from, only sampled: draw the depth in with vanilla's depth blit. */
    private void drawDepth(GpuTexture src, FrameSlot c) {
        if (src.getWidth(0) != c.depth.getWidth(0) || src.getHeight(0) != c.depth.getHeight(0)) return;
        if ((src.usage() & GpuTexture.USAGE_TEXTURE_BINDING) == 0) return;
        if (src != irisDepthSource) {
            if (irisDepthSourceView != null) irisDepthSourceView.close();
            irisDepthSource = src;
            irisDepthSourceView = RenderSystem.getDevice().createTextureView(src);
        }
        var descriptor = com.mojang.renderpearl.api.commands.RenderPassDescriptor.builder(() -> "VFrameGen depth before translucents")
                .withDepthAttachment(c.depthView, java.util.OptionalDouble.empty()).build();
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(descriptor)) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(net.minecraft.client.renderer.RenderPipelines.INTEGRATE_DEPTH));
            pass.setUniform("InSampler", irisDepthSourceView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            pass.draw(3, 1, 0, 0);
        }
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
        c.fixedMaskValid = false;
        try {
            // Only what reads it needs it: motion blur, the vignette and the F3 crosshair.
            if (VFrameGen.config().motionBlur || vignetteNext || crosshairOn) buildFixedMask(c);
        } catch (RuntimeException e) {
            fail(e);
        }
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

    private int pendingWidth, pendingHeight;
    private GpuFormat pendingFormat;

    /**
     * The pending buffers depend only on the window size and colour format. They used to be remade
     * with everything else, so also when the render resolution changed (dynamic render scale, every
     * few seconds), while generated frames were still queued for them: those were then shown from
     * a buffer never drawn into (black) or from memory already reused by another texture (garbage).
     */
    private void createPendingTextures(GpuDevice device, int w, int h, GpuFormat color) {
        if (w == pendingWidth && h == pendingHeight && color == pendingFormat
                && (pendingTex[0] != null || !PresentQueue.available())) return;
        // Nothing queued may be shown from the old buffers.
        PresentThread.dropPending();
        PresentQueue.setSlotImages(null, 0, 0);
        pendingWidth = w;
        pendingHeight = h;
        pendingFormat = color;
        for (int i = 0; i < pendingTex.length; i++) {
            if (pendingTex[i] != null) pendingTex[i].close();
            pendingTex[i] = null;
        }
        if (!PresentQueue.available()) return;
        long[] images = new long[pendingTex.length];
        for (int i = 0; i < pendingTex.length; i++) {
            pendingTex[i] = device.createTexture("VFrameGen pending generated frame " + i,
                    GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC, color, w, h, 1, 1);
            if (!(pendingTex[i] instanceof com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture vk)) return;
            images[i] = vk.vkImage();
        }
        PresentQueue.setSlotImages(images, w, h);
    }

    /**
     * Present queue: draw generated frame k of the pair into a pending buffer and hand it to the
     * present thread, which takes a swapchain image for it only when it is due. False when no
     * buffer is free (all still waiting to be shown): the rest of the pair is skipped.
     */
    private boolean stageGenerated(Minecraft mc, int k, String where) {
        int slot = PresentQueue.takeSlot();
        if (slot < 0) return false;
        nextFrame = k;
        long t0 = System.nanoTime();
        CommandEncoder encoder = renderInterpolated(k / (double) frames);
        if (VFrameGen.config().debugFrameLog) {
            DebugCapture.sequenceFrame(outTex, "gen", String.format(Locale.ROOT, "f=%.3f (%s, pending %d) pos %.4f %.4f %.4f",
                    lastGenF, where, slot, lastGenPos.x, lastGenPos.y, lastGenPos.z));
        }
        GpuTextureView shownView = genSrc != null ? motionBlur(encoder, outView, genSrc, genPos, dstViewRot, dstProj) : outView;
        encoder.copyTextureToTexture(shownView.texture(), pendingTex[slot], 0, 0, 0, 0, 0, width, height);
        long value = PresentQueue.nextTimelineValue();
        ((com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder) ((com.mojang.renderpearl.frontend.FrontendCommandEncoder) encoder).backend())
                .signalSemaphore(PresentQueue.timeline(), value, 0x10000L);
        submittingGenerated = true;
        try {
            encoder.submit();
        } finally {
            submittingGenerated = false;
        }
        PresentThread.deferPending(slot, value, k, frames);
        long t1 = System.nanoTime();
        costSubmitNs += t1 - t0;
        genCostNs += t1 - t0;
        noteGenWall(t1 - t0);
        notePresent();
        screenEvent(System.nanoTime() + (gpuPassCount == 0 ? 1_000_000L : gpuPassNs / gpuPassCount), false);
        statsGenerated++;
        secGenerated++;
        MinecraftAccessor counter = (MinecraftAccessor) mc;
        counter.vframegen$setFrames(counter.vframegen$getFrames() + 1);
        if (capture) DebugCapture.save(outTex, k + "-generated");
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
        if (blurView != null) blurView.close();
        if (blurTex != null) blurTex.close();
        blurTex = device.createTexture("VFrameGen motion blur",
                GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_SRC,
                color, w, h, 1, 1);
        blurView = device.createTextureView(blurTex);
        createPendingTextures(device, w, h, color);
        if (blurUbo == null) {
            blurUbo = device.createBuffer(() -> "VFrameGen motion blur uniforms", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, BLUR_UBO_SIZE);
        }
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
                captureLog.append(String.format(Locale.ROOT, "mode=interpolated (present thread) multiplier=%d A->B ms=%.2f shaders=%s%n",
                        frames, (held.extractNs - shown.extractNs) / 1e6, Compat.shaderPackActive()));
                DebugCapture.save(shown.finalImage, "0-real-A");
                saveCapturedFlow();
            }
            genStartNs = System.nanoTime();
            if (PresentQueue.ready()) {
                for (int k = 1; k < frames; k++) {
                    // Keep the queue about one real frame's worth deep. Swapchain images used to cap
                    // it; without that the render thread ran two or three real frames ahead of the
                    // screen (input lag, and the game slowed down and caught up in waves). Skipped
                    // before drawing, so it costs no GPU time.
                    if (PresentThread.queued() > frames + 1) {
                        statsSkips.merge("queue-full", frames - k, Integer::sum);
                        break;
                    }
                    if (!stageGenerated(mc, k, "present queue")) {
                        statsSkips.merge("no-buffer", frames - k, Integer::sum);
                        break;
                    }
                    StallProbe.mark("gen frame");
                }
            } else for (int k = 1; k < frames; k++) {
                // No free image: skip the rest of this pair's generated frames rather than
                // wait, so frame generation never slows the real frames down.
                if (!PresentThread.imageFree()) {
                    statsSkips.merge("no-image", frames - k, Integer::sum);
                    break;
                }
                PresentThread.setAcquiringGenerated(true);
                try {
                    surface.acquireNextTexture();
                } catch (SurfaceException e) {
                    recreateSurface(mc);
                    return;
                } finally {
                    PresentThread.setAcquiringGenerated(false);
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
                    (presentThreadActive() ? ", " + PresentThread.stats() : "")
                            + (VFrameGen.config().adaptive ? String.format(Locale.ROOT, ", adaptive ceiling %.1fx", dropCeiling) : "")
                            + (statsSkips.isEmpty() ? "" : ", skipped " + statsSkips),
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
