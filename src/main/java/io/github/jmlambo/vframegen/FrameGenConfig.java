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

    public static final int MAX_MULTIPLIER = 6;

    public boolean enabled = true;
    public int multiplier = 2;
    public Mode mode = Mode.INTERPOLATED;
    public boolean disableWhileScreenOpen = true;
    public DepthMode depthMode = DepthMode.AUTO;
    public Pacing pacing = Pacing.BALANCED;
    public double maxCameraJumpBlocks = 4.0;
    public double maxCameraTurnDegrees = 45.0;
    public int maxRealFrameAgeMs = 100;
    public float guiDiffThreshold = 0.004f;
    public float sharpness = 0.0f;
    public boolean debugTint = false;

    private static final String HEADER = """
            VFrameGen settings. Toggle in game with the key bound under Controls -> VFrameGen (F9 by default).

            enabled                 generate frames between real frames.
            multiplier              2 to 6: frames shown per real frame (2 = one generated frame per
                                    real one). Costs GPU time per extra frame, no extra VRAM.
            disableWhileScreenOpen  only generate while no menu or inventory is open.
            mode                    INTERPOLATED or LOW_LATENCY. INTERPOLATED holds each real frame back by
                                    one frame and builds the in-between frames from both neighbours: no
                                    smearing at the screen edges, even pacing, about one real frame more
                                    input lag. LOW_LATENCY builds them from the last real frame only.
            depthMode               AUTO, DEPTH or ROTATION_ONLY. DEPTH reprojects with the depth buffer
                                    (correct parallax when moving). ROTATION_ONLY ignores depth: exact for
                                    turning, approximate for moving. AUTO uses ROTATION_ONLY while an Iris
                                    shader pack is active (their depth buffer is not the vanilla one).
            pacing                  OFF or BALANCED. BALANCED holds the generated frame back to the middle
                                    of the real-frame interval when the GPU is the bottleneck anyway.
            maxCameraJumpBlocks     show no generated frame across a camera jump larger than this (teleports).
            maxCameraTurnDegrees    show no generated frame when the camera turned more than this in one frame.
            maxRealFrameAgeMs       below this real frame rate (in ms per frame) generation pauses.
            guiDiffThreshold        how different a pixel must be after the GUI pass to count as GUI.
            sharpness               unsharp mask applied to generated frames, 0 = off.
            debugTint               tint generated frames magenta, to see which frames are generated.
            """;

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("vframegen.properties");
    }

    public static FrameGenConfig load() {
        FrameGenConfig c = new FrameGenConfig();
        Path p = path();
        if (Files.exists(p)) {
            Properties props = new Properties();
            try (Reader r = Files.newBufferedReader(p)) {
                props.load(r);
                c.enabled = bool(props, "enabled", c.enabled);
                c.multiplier = Math.clamp((int) dbl(props, "multiplier", c.multiplier), 2, MAX_MULTIPLIER);
                c.disableWhileScreenOpen = bool(props, "disableWhileScreenOpen", c.disableWhileScreenOpen);
                c.mode = enumOf(props, "mode", c.mode, Mode.class);
                c.depthMode = enumOf(props, "depthMode", c.depthMode, DepthMode.class);
                c.pacing = enumOf(props, "pacing", c.pacing, Pacing.class);
                c.maxCameraJumpBlocks = dbl(props, "maxCameraJumpBlocks", c.maxCameraJumpBlocks);
                c.maxCameraTurnDegrees = dbl(props, "maxCameraTurnDegrees", c.maxCameraTurnDegrees);
                c.maxRealFrameAgeMs = (int) dbl(props, "maxRealFrameAgeMs", c.maxRealFrameAgeMs);
                c.guiDiffThreshold = (float) dbl(props, "guiDiffThreshold", c.guiDiffThreshold);
                c.sharpness = (float) dbl(props, "sharpness", c.sharpness);
                c.debugTint = bool(props, "debugTint", c.debugTint);
            } catch (IOException | RuntimeException e) {
                VFrameGen.LOGGER.warn("Could not read {}, using defaults", p, e);
            }
        }
        c.save();
        return c;
    }

    public void save() {
        java.util.TreeMap<String, String> values = new java.util.TreeMap<>();
        values.put("enabled", Boolean.toString(enabled));
        values.put("multiplier", Integer.toString(multiplier));
        values.put("disableWhileScreenOpen", Boolean.toString(disableWhileScreenOpen));
        values.put("mode", mode.name());
        values.put("depthMode", depthMode.name());
        values.put("pacing", pacing.name());
        values.put("maxCameraJumpBlocks", Double.toString(maxCameraJumpBlocks));
        values.put("maxCameraTurnDegrees", Double.toString(maxCameraTurnDegrees));
        values.put("maxRealFrameAgeMs", Integer.toString(maxRealFrameAgeMs));
        values.put("guiDiffThreshold", Float.toString(guiDiffThreshold));
        values.put("sharpness", Float.toString(sharpness));
        values.put("debugTint", Boolean.toString(debugTint));
        StringBuilder out = new StringBuilder();
        for (String line : HEADER.split("\n", -1)) {
            out.append(line.isEmpty() ? "#" : "# " + line).append('\n');
        }
        values.forEach((k, v) -> out.append(k).append('=').append(v).append('\n'));
        try {
            Files.createDirectories(path().getParent());
            Files.writeString(path(), out);
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
            return Double.parseDouble(v.trim());
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
