package io.github.jmlambo.vframegen;

/**
 * One-click presets: set frame generation, render scale, upscaling and anti-aliasing together.
 * The preset shown is the one the current settings match (CUSTOM when none does), so changing any
 * of these settings by hand turns it to Custom. The target frame rate and motion blur are left
 * alone (they depend on the display and on taste); Quality also turns on optical flow.
 */
public final class Presets {
    public enum Preset { CUSTOM, LOW_END, BALANCED, QUALITY }

    private Presets() {
    }

    /** Sets the preset's values (CUSTOM changes nothing). */
    public static void apply(FrameGenConfig c, Preset p) {
        switch (p) {
            case LOW_END -> {
                // As much help as possible: the resolution follows the load, frame generation fills in.
                c.enabled = true;
                c.adaptive = true;
                c.dynamicRenderScale = true;
                c.renderScale = 1.0;
                c.upscaler = FrameGenConfig.Upscaler.BICUBIC; // the cheapest
                c.antiAliasing = FrameGenConfig.AntiAliasingMode.FXAA;
                c.antiAliasingQuality = 2;
            }
            case BALANCED -> {
                c.enabled = true;
                c.adaptive = true;
                c.dynamicRenderScale = true;
                c.renderScale = 1.0;
                c.upscaler = FrameGenConfig.Upscaler.EDGE_ADAPTIVE;
                c.antiAliasing = FrameGenConfig.AntiAliasingMode.SMAA;
                c.antiAliasingQuality = 3;
            }
            case QUALITY -> {
                // Full resolution, frame generation only for smoothness.
                c.enabled = true;
                c.adaptive = true;
                c.dynamicRenderScale = false;
                c.renderScale = 1.0;
                c.upscaler = FrameGenConfig.Upscaler.TEMPORAL; // temporal anti-aliasing at 100%
                c.antiAliasing = FrameGenConfig.AntiAliasingMode.OFF; // temporal anti-aliases
                c.antiAliasingQuality = 3;
                // Generated frames also follow motion depth can't see. Only used where the GPU
                // supports it; the other presets leave the setting as it is.
                c.opticalFlow = true;
            }
            case CUSTOM -> {
            }
        }
    }

    /** The preset the settings match, or CUSTOM. */
    public static Preset match(FrameGenConfig c) {
        for (Preset p : Preset.values()) {
            if (p == Preset.CUSTOM) continue;
            FrameGenConfig probe = copyOfCovered(c);
            apply(probe, p);
            if (sameCovered(probe, c)) return p;
        }
        return Preset.CUSTOM;
    }

    private static FrameGenConfig copyOfCovered(FrameGenConfig c) {
        FrameGenConfig n = new FrameGenConfig();
        n.enabled = c.enabled;
        n.adaptive = c.adaptive;
        n.dynamicRenderScale = c.dynamicRenderScale;
        n.renderScale = c.renderScale;
        n.upscaler = c.upscaler;
        n.antiAliasing = c.antiAliasing;
        n.antiAliasingQuality = c.antiAliasingQuality;
        n.opticalFlow = c.opticalFlow;
        return n;
    }

    private static boolean sameCovered(FrameGenConfig a, FrameGenConfig b) {
        return a.enabled == b.enabled && a.adaptive == b.adaptive && a.dynamicRenderScale == b.dynamicRenderScale
                // The render scale setting is not used while dynamic scale is on.
                && (a.dynamicRenderScale || Math.abs(a.renderScale - b.renderScale) < 1e-6) && a.upscaler == b.upscaler
                && a.antiAliasing == b.antiAliasing && a.antiAliasingQuality == b.antiAliasingQuality
                && a.opticalFlow == b.opticalFlow;
    }
}
