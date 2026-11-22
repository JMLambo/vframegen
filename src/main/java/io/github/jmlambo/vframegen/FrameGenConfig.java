package io.github.jmlambo.vframegen;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/** Settings, stored in config/vframegen.properties. */
public final class FrameGenConfig {
    public enum Mode { INTERPOLATED, LOW_LATENCY }
    public enum DepthMode { AUTO, DEPTH, ROTATION_ONLY }
    public enum Pacing { OFF, BALANCED }
    public enum Upscaler { BICUBIC, EDGE_ADAPTIVE }
    public enum AntiAliasingMode { OFF, FXAA, SMAA }

    public static final int MAX_MULTIPLIER = 6;
    /** Vanilla's frame limiter only applies limits below 260 (260 means unlimited). */
    public static final int MAX_TARGET_FPS = 250;

    /** The most frames shown per real frame with these settings (sizes the swapchain). */
    public int maxFramesPerReal() {
        return adaptive ? MAX_MULTIPLIER : Math.clamp(multiplier, 2, MAX_MULTIPLIER);
    }

    public boolean enabled = true;
    public int multiplier = 2;
    public boolean adaptive = false;
    public int targetFps = 120;
    public Mode mode = Mode.INTERPOLATED;
    public boolean disableWhileScreenOpen = true;
    public DepthMode depthMode = DepthMode.AUTO;
    public Pacing pacing = Pacing.BALANCED;
    public double maxCameraJumpBlocks = 4.0;
    public double maxCameraTurnDegrees = 45.0;
    public int maxRealFrameAgeMs = 100;
    public float guiDiffThreshold = 0.004f;
    public float sharpness = 0.0f;
    public AntiAliasingMode antiAliasing = AntiAliasingMode.OFF;
    public int antiAliasingQuality = 3;
    public boolean motionBlur = false;
    public float motionBlurStrength = 0.5f;
    public double renderScale = 1.0;
    public boolean dynamicRenderScale = false;
    public boolean autoCap = true;
    public boolean presentThread = true;
    public boolean allowVsync = false;
    public int displayHz = 0;
    public double upscaleSharpness = 0.5;
    public Upscaler upscaler = Upscaler.BICUBIC;
    public boolean debugTint = false;
    public boolean debugCaptures = false;
    public boolean debugFrameLog = false;
    public boolean debugStatsLog = false;
    public boolean debugPacingLog = false;
    public boolean debugStallLog = false;

    private static final String HEADER = """
            VFrameGen settings. Toggle in game with the key bound under Controls -> VFrameGen (F9 by default).

            enabled                 generate frames between real frames.
            multiplier              2 to 6: frames shown per real frame (2 = one generated frame per
                                    real one). Costs GPU time per extra frame, no extra VRAM.
            adaptive                instead of a fixed multiplier, pick frames per real frame (1 to 6)
                                    to reach targetFps. Real frames are capped at targetFps.
            targetFps               30 to 250: frames per second shown in adaptive mode.
            disableWhileScreenOpen  only generate while no menu or inventory is open.
            mode                    INTERPOLATED or LOW_LATENCY. INTERPOLATED holds each real frame back by
                                    one frame and builds the in-between frames from both neighbours: no
                                    smearing at the screen edges, even pacing, about one real frame more
                                    input lag. LOW_LATENCY builds them from the last real frame only.
            depthMode               AUTO, DEPTH or ROTATION_ONLY. DEPTH reprojects with the depth buffer
                                    (correct parallax when moving). ROTATION_ONLY ignores depth: exact for
                                    turning, approximate for moving. AUTO is DEPTH; ROTATION_ONLY is a
                                    fallback for shader packs whose depth buffer does not match.
            pacing                  OFF or BALANCED. BALANCED holds the generated frame back to the middle
                                    of the real-frame interval when the GPU is the bottleneck anyway.
            maxCameraJumpBlocks     show no generated frame across a camera jump larger than this (teleports).
            maxCameraTurnDegrees    show no generated frame when the camera turned more than this in one frame.
            maxRealFrameAgeMs       below this real frame rate (in ms per frame) generation pauses.
            guiDiffThreshold        how different a pixel must be after the GUI pass to count as GUI.
            sharpness               unsharp mask applied to generated frames, 0 = off.
            antiAliasing            OFF, FXAA or SMAA on the world, before the GUI (most shader packs have their own).
                                    FXAA is the cheapest; SMAA keeps edges and textures sharper.
            antiAliasingQuality     1 (low) to 4 (ultra): higher finds fainter and longer edges, at more GPU time.
            motionBlur              camera motion blur on every frame shown, along that frame's own motion
                                    (turn the shader pack's motion blur off: it ghosts with frame generation).
            motionBlurStrength      0.1 to 1.0: blur length as a fraction of the time between shown frames.
            displayHz               refresh rate the auto cap divides (0 = ask the system; some virtual
                                    displays report 60 Hz when they run faster).
            allowVsync              keep VSync on with frame generation (set when you confirm turning it
                                    on; otherwise VSync is turned off, since it makes frames stutter).
            presentThread           Vulkan: show frames from a thread of their own, each at its exact time
                                    (needs a restart to change). No frame cap needed.
            autoCap                 with Max Framerate unlimited, cap real frames at the display's refresh
                                    rate / multiplier while frames are generated, so there is always
                                    idle time to show generated frames evenly in.
            renderScale             0.5 to 1.0: the world (and shader pack) renders at this fraction of the
                                    window size and is upscaled; the GUI stays at full resolution.
                                    0.75 shades about 56% of the pixels.
            dynamicRenderScale      lower the render scale (50% to 100%, renderScale unused) when real frames are
                                    too slow for targetFps (half of it while frame generation is on, or targetFps /
                                    multiplier with a fixed multiplier), and raise it again with room to spare.
            upscaler                BICUBIC or EDGE_ADAPTIVE. BICUBIC: Catmull-Rom with contrast-adaptive
                                    sharpening. EDGE_ADAPTIVE: FSR 1 style (EASU + RCAS), sharper
                                    edges without jaggies, a little more GPU time.
            upscaleSharpness        0 to 1: contrast-adaptive sharpening after the upscale.
            debugTint               tint generated frames magenta, to see which frames are generated.
            debugCaptures           F8 (or the controller button) saves the next frames as images.
            debugFrameLog           keep a log of the last ~10 s of presented frames; F8 saves it too.
            debugStatsLog           every 5 s, log frame rates, timings and why frames were skipped.
            debugPacingLog          every second, log how evenly frames reached the screen.
            debugStallLog           log a breakdown of every game loop that took over 35 ms.
            """;

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("vframegen.properties");
    }

    public static FrameGenConfig load() {
        FrameGenConfig c = new FrameGenConfig();
        Path p = path();
        boolean readFailed = false;
        if (Files.exists(p)) {
            Properties props = new Properties();
            try (Reader r = Files.newBufferedReader(p)) {
                props.load(r);
                c.enabled = bool(props, "enabled", c.enabled);
                c.multiplier = Math.clamp((int) dbl(props, "multiplier", c.multiplier), 2, MAX_MULTIPLIER);
                c.adaptive = bool(props, "adaptive", c.adaptive);
                c.targetFps = Math.clamp((int) dbl(props, "targetFps", c.targetFps), 30, MAX_TARGET_FPS);
                c.disableWhileScreenOpen = bool(props, "disableWhileScreenOpen", c.disableWhileScreenOpen);
                c.mode = enumOf(props, "mode", c.mode, Mode.class);
                c.depthMode = enumOf(props, "depthMode", c.depthMode, DepthMode.class);
                c.pacing = enumOf(props, "pacing", c.pacing, Pacing.class);
                c.maxCameraJumpBlocks = dbl(props, "maxCameraJumpBlocks", c.maxCameraJumpBlocks);
                c.maxCameraTurnDegrees = dbl(props, "maxCameraTurnDegrees", c.maxCameraTurnDegrees);
                c.maxRealFrameAgeMs = (int) dbl(props, "maxRealFrameAgeMs", c.maxRealFrameAgeMs);
                c.guiDiffThreshold = (float) dbl(props, "guiDiffThreshold", c.guiDiffThreshold);
                c.sharpness = (float) dbl(props, "sharpness", c.sharpness);
                // Older versions stored true/false (true was FXAA).
                String aa = props.getProperty("antiAliasing", "").trim();
                c.antiAliasing = aa.equalsIgnoreCase("true") ? AntiAliasingMode.FXAA : aa.equalsIgnoreCase("false")
                        ? AntiAliasingMode.OFF : enumOf(props, "antiAliasing", c.antiAliasing, AntiAliasingMode.class);
                c.antiAliasingQuality = (int) Math.clamp(dbl(props, "antiAliasingQuality", c.antiAliasingQuality), 1, 4);
                c.motionBlur = bool(props, "motionBlur", c.motionBlur);
                c.motionBlurStrength = (float) Math.clamp(dbl(props, "motionBlurStrength", c.motionBlurStrength), 0.1, 1.0);
                c.debugTint = bool(props, "debugTint", c.debugTint);
                c.debugCaptures = bool(props, "debugCaptures", c.debugCaptures);
                c.debugFrameLog = bool(props, "debugFrameLog", c.debugFrameLog);
                c.debugStatsLog = bool(props, "debugStatsLog", c.debugStatsLog);
                c.debugPacingLog = bool(props, "debugPacingLog", c.debugPacingLog);
                c.debugStallLog = bool(props, "debugStallLog", c.debugStallLog);
                c.autoCap = bool(props, "autoCap", c.autoCap);
                c.presentThread = bool(props, "presentThread", c.presentThread);
                c.allowVsync = bool(props, "allowVsync", c.allowVsync);
                c.displayHz = Math.max(0, Math.min(500, (int) dbl(props, "displayHz", c.displayHz)));
                c.renderScale = Math.clamp(dbl(props, "renderScale", c.renderScale), 0.5, 1.0);
                c.dynamicRenderScale = bool(props, "dynamicRenderScale", c.dynamicRenderScale);
                c.upscaler = enumOf(props, "upscaler", c.upscaler, Upscaler.class);
                c.upscaleSharpness = Math.clamp(dbl(props, "upscaleSharpness", c.upscaleSharpness), 0.0, 1.0);
            } catch (IOException | RuntimeException e) {
                VFrameGen.LOGGER.warn("Could not read {}, using defaults without overwriting it", p, e);
                readFailed = true;
            }
        }
        if (!readFailed) c.save();
        return c;
    }

    public void save() {
        java.util.TreeMap<String, String> values = new java.util.TreeMap<>();
        values.put("enabled", Boolean.toString(enabled));
        values.put("multiplier", Integer.toString(multiplier));
        values.put("adaptive", Boolean.toString(adaptive));
        values.put("targetFps", Integer.toString(targetFps));
        values.put("disableWhileScreenOpen", Boolean.toString(disableWhileScreenOpen));
        values.put("mode", mode.name());
        values.put("depthMode", depthMode.name());
        values.put("pacing", pacing.name());
        values.put("maxCameraJumpBlocks", Double.toString(maxCameraJumpBlocks));
        values.put("maxCameraTurnDegrees", Double.toString(maxCameraTurnDegrees));
        values.put("maxRealFrameAgeMs", Integer.toString(maxRealFrameAgeMs));
        values.put("guiDiffThreshold", Float.toString(guiDiffThreshold));
        values.put("sharpness", Float.toString(sharpness));
        values.put("antiAliasing", antiAliasing.name());
        values.put("antiAliasingQuality", Integer.toString(antiAliasingQuality));
        values.put("motionBlur", Boolean.toString(motionBlur));
        values.put("motionBlurStrength", Float.toString(motionBlurStrength));
        values.put("debugTint", Boolean.toString(debugTint));
        values.put("debugCaptures", Boolean.toString(debugCaptures));
        values.put("debugFrameLog", Boolean.toString(debugFrameLog));
        values.put("debugStatsLog", Boolean.toString(debugStatsLog));
        values.put("debugPacingLog", Boolean.toString(debugPacingLog));
        values.put("debugStallLog", Boolean.toString(debugStallLog));
        values.put("autoCap", Boolean.toString(autoCap));
        values.put("presentThread", Boolean.toString(presentThread));
        values.put("allowVsync", Boolean.toString(allowVsync));
        values.put("displayHz", Integer.toString(displayHz));
        values.put("renderScale", Double.toString(renderScale));
        values.put("dynamicRenderScale", Boolean.toString(dynamicRenderScale));
        values.put("upscaleSharpness", Double.toString(upscaleSharpness));
        values.put("upscaler", upscaler.name());
        StringBuilder out = new StringBuilder();
        for (String line : HEADER.split("\n", -1)) {
            out.append(line.isEmpty() ? "#" : "# " + line).append('\n');
        }
        values.forEach((k, v) -> out.append(k).append('=').append(v).append('\n'));
        try {
            Files.createDirectories(path().getParent());
            Path tmp = path().resolveSibling(path().getFileName() + ".tmp");
            Files.writeString(tmp, out);
            try {
                Files.move(tmp, path(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, path(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            VFrameGen.LOGGER.warn("Could not write {}", path(), e);
        }
    }

    private static boolean bool(Properties p, String k, boolean def) {
        String v = p.getProperty(k);
        return v == null ? def : Boolean.parseBoolean(v.trim());
    }

    private static double dbl(Properties p, String k, double def) {
        String v = p.getProperty(k);
        if (v == null) return def;
        try {
            double d = Double.parseDouble(v.trim());
            return Double.isFinite(d) ? d : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static <E extends Enum<E>> E enumOf(Properties p, String k, E def, Class<E> type) {
        String v = p.getProperty(k);
        if (v == null) return def;
        try {
            return Enum.valueOf(type, v.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return def;
        }
    }
}
