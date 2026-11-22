package io.github.jmlambo.vframegen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * On a fresh install: measure the first few seconds in a world and pick a preset and target frame
 * rate from them, then say so in a toast. Runs once (FrameGenConfig.firstRunPending).
 *
 * <p>It measures the render thread's work per real frame at the settings as they are (as
 * DynamicScale does), after a warm-up for loading. The target frame rate becomes the display's
 * refresh rate (up to the maximum); the preset depends on how many real frames the PC manages:
 * Quality near the target, Balanced near half of it (frame generation makes up the rest),
 * Low-end below that.
 */
public final class FirstRunSetup {
    private static final long WARMUP_NS = 8_000_000_000L;
    private static final long MEASURE_NS = 5_000_000_000L;
    private static final SystemToast.SystemToastId TOAST = new SystemToast.SystemToastId(8000L);

    private static long inWorldSinceNs;
    private static double busySumNs;
    private static int busyCount;

    private FirstRunSetup() {
    }

    public static void workDone(Minecraft mc) {
        FrameGenConfig cfg = VFrameGen.config();
        if (!cfg.firstRunPending) return;
        long now = System.nanoTime();
        long start = DynamicScale.loopStartNs();
        if (mc.level == null || mc.isPaused() || mc.gui.screen() != null || start == 0) {
            inWorldSinceNs = 0;
            busySumNs = 0;
            busyCount = 0;
            return;
        }
        if (inWorldSinceNs == 0) inWorldSinceNs = now;
        long inWorld = now - inWorldSinceNs;
        if (inWorld < WARMUP_NS) return;
        busySumNs += now - start;
        busyCount++;
        if (inWorld < WARMUP_NS + MEASURE_NS || busyCount < 20) return;

        double busyMs = busySumNs / busyCount / 1e6;
        double realFps = 1000.0 / busyMs;
        int target = Math.clamp(FrameGenerator.detectedRefreshHz(mc), 60, FrameGenConfig.MAX_TARGET_FPS);
        Presets.Preset preset = realFps >= target * 0.8 ? Presets.Preset.QUALITY
                : realFps >= target * 0.4 ? Presets.Preset.BALANCED
                : Presets.Preset.LOW_END;
        Presets.apply(cfg, preset);
        cfg.targetFps = target;
        cfg.firstRunPending = false;
        cfg.save();
        FrameGenerator.afterConfigSaved(mc);
        VFrameGen.LOGGER.info("First run: about {} real fps at {} Hz; picked the {} preset, target {} fps",
                Math.round(realFps), target, preset, target);
        SystemToast.addOrUpdate(mc.gui.toastManager(), TOAST,
                Component.translatable("vframegen.firstRun.title"),
                Component.translatable("vframegen.firstRun.message",
                        Component.translatable("vframegen.config.preset." + preset.name().toLowerCase(Locale.ROOT)),
                        target));
    }
}
