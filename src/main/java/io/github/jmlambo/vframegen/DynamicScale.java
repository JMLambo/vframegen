package io.github.jmlambo.vframegen;

import net.minecraft.client.Minecraft;

/**
 * Dynamic render scale: lowers the render scale when real frames take longer than the target
 * frame rate allows, and raises it again when there is room, between 50% and 100% (the render
 * scale setting is not used while it is on).
 *
 * <p>It measures the render thread's work per real frame (loop start to the frame limiter), not
 * the time between frames, so it still sees headroom while the frame cap holds the frame rate.
 * Each change resizes the world's render targets (a small hitch), so the scale moves in 5% steps,
 * at most once every two seconds: down as soon as frames are too slow, up only after three
 * seconds with plenty of room.
 */
public final class DynamicScale {
    public static final double MIN = 0.5;
    private static final double STEP = 0.05;
    private static final long WINDOW_NS = 1_000_000_000L;
    private static final long MIN_CHANGE_GAP_NS = 2_000_000_000L;

    private static double current = 1.0;
    private static boolean initialized;
    private static long loopStartNs;
    private static double busySumNs;
    private static int busyCount;
    private static long windowStartNs;
    private static long lastChangeNs;
    private static int roomySeconds;
    // Not measured for a moment after joining a world or closing a screen (loading, chunk uploads).
    private static long warmupUntilNs;
    private static final long WARMUP_NS = 3_000_000_000L;

    private DynamicScale() {
    }

    public static boolean enabled() {
        return VFrameGen.config().dynamicRenderScale;
    }

    /** The scale to render at now (only while enabled). */
    public static double current() {
        double max = maxScale();
        if (!initialized) {
            current = max;
            initialized = true;
        }
        return Math.clamp(current, MIN, max);
    }

    private static double maxScale() {
        return 1.0;
    }

    public static void loopStart() {
        loopStartNs = System.nanoTime();
    }

    /** The real frame's work is done (the frame limiter is next). */
    public static void workDone(Minecraft mc) {
        long now = System.nanoTime();
        if (!enabled() || loopStartNs == 0 || mc.level == null || mc.isPaused() || mc.gui.screen() != null) {
            reset(now);
            return;
        }
        if (now < warmupUntilNs) return;
        busySumNs += now - loopStartNs;
        busyCount++;
        if (windowStartNs == 0) windowStartNs = now;
        if (now - windowStartNs < WINDOW_NS || busyCount < 5) return;

        double busyMs = busySumNs / busyCount / 1e6;
        double budgetMs = 1000.0 / realTargetFps();
        double s = current();
        double next = s;
        boolean canChange = now - lastChangeNs >= MIN_CHANGE_GAP_NS;
        if (busyMs > budgetMs * 1.05) {
            roomySeconds = 0;
            // The world's cost goes roughly with its pixel count (scale squared).
            double wanted = s * Math.sqrt(budgetMs * 0.95 / busyMs);
            // At most 15% at a time: a bigger jump is a jarring change in sharpness.
            next = Math.max(s - 3 * STEP, Math.min(s - STEP, Math.floor(wanted / STEP) * STEP));
        } else if (busyMs < budgetMs * 0.75 && s < maxScale()) {
            roomySeconds++;
            if (roomySeconds >= 3) next = s + STEP;
        } else {
            roomySeconds = 0;
        }
        next = Math.clamp(Math.round(next / STEP) * STEP, MIN, maxScale());
        if (canChange && Math.abs(next - s) > 1e-6) {
            if (VFrameGen.config().debugStatsLog) {
                VFrameGen.LOGGER.info("Dynamic render scale: {}% -> {}% ({} ms of work per real frame, budget {} ms)",
                        Math.round(s * 100), Math.round(next * 100), String.format("%.1f", busyMs), String.format("%.1f", budgetMs));
            }
            current = next;
            lastChangeNs = now;
            roomySeconds = 0;
        }
        busySumNs = 0;
        busyCount = 0;
        windowStartNs = now;
    }

    /**
     * Real frames per second to aim for, from the one target frame rate: frame generation makes up
     * the rest (half of the target adaptively, or target / multiplier with a fixed multiplier).
     */
    public static double realTargetFps() {
        FrameGenConfig cfg = VFrameGen.config();
        double target = cfg.targetFps;
        if (cfg.enabled) target /= cfg.adaptive ? 2.0 : Math.clamp(cfg.multiplier, 2, FrameGenConfig.MAX_MULTIPLIER);
        return Math.max(10.0, target);
    }

    private static void reset(long now) {
        warmupUntilNs = now + WARMUP_NS;
        busySumNs = 0;
        busyCount = 0;
        windowStartNs = 0;
        roomySeconds = 0;
    }
}
