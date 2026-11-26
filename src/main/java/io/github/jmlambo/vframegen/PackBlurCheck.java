package io.github.jmlambo.vframegen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * A shader pack's own motion blur smears each real frame, and frame generation then reprojects
 * that smear as if it were geometry: ghosting around everything that moves on screen. This warns
 * once (per pack settings change) when the active pack has it on, and points to VFrameGen's motion
 * blur instead.
 *
 * Both loaders store a pack's changed options in the same {@code shaderpacks/(pack).txt} file,
 * which is why this works with Iris and Vitrail alike (Complementary: MOTION_BLUR_EFFECT=1).
 * Iris names its pack in {@code config/iris.properties}; Vitrail in {@code vitrail/pack.txt}, with
 * {@code vitrail/options.txt} able to force a setting over the pack's own file. Only options the
 * player turned on are found here, not a pack that has blur on by default.
 */
public final class PackBlurCheck {
    private static final SystemToast.SystemToastId TOAST = new SystemToast.SystemToastId(12000L);
    private static final long CHECK_EVERY_NS = 2_000_000_000L;
    private static long nextCheckNs;
    // Per pack: the settings file time it was warned for, so switching packs back and forth with
    // nothing changed does not warn again.
    private static final Map<String, Long> warned = new HashMap<>();

    private PackBlurCheck() {
    }

    /** Every game loop; reads files at most every two seconds. */
    public static void tick(Minecraft mc) {
        long now = System.nanoTime();
        if (now < nextCheckNs) return;
        nextCheckNs = now + CHECK_EVERY_NS;
        if (!VFrameGen.config().enabled || !Compat.shaderPackActive() || mc.level == null) return;
        Path game = mc.gameDirectory.toPath();
        boolean vitrail = Compat.vitrailShadersActive();
        Properties choice = read(game.resolve(vitrail ? "vitrail/pack.txt" : "config/iris.properties"));
        String pack = choice.getProperty(vitrail ? "pack" : "shaderPack", "");
        if (pack.isEmpty()) return;
        if (vitrail && ("none".equalsIgnoreCase(pack)
                || !Boolean.parseBoolean(choice.getProperty("enabled", "true")))) return;
        Path settings = game.resolve("shaderpacks").resolve(pack + ".txt");
        Path forced = vitrail ? game.resolve("vitrail/options.txt") : null;
        long modified;
        try {
            modified = (Files.exists(settings) ? Files.getLastModifiedTime(settings).toMillis() : 0)
                    + (forced != null && Files.exists(forced)
                            ? Files.getLastModifiedTime(forced).toMillis() : 0);
        } catch (IOException | RuntimeException e) {
            return;
        }
        String key = (vitrail ? "vitrail:" : "iris:") + pack;
        Long seen = warned.get(key);
        if (seen != null && seen == modified) return;
        Properties effective = new Properties();
        effective.putAll(readPackSettings(settings));
        if (forced != null) effective.putAll(read(forced));
        String option = blurOption(effective);
        if (option == null) return;
        warned.put(key, modified);
        VFrameGen.LOGGER.info("Shader pack {} has motion blur on ({}); it ghosts with frame generation", pack, option);
        SystemToast.addOrUpdate(mc.gui.toastManager(), TOAST,
                Component.translatable("vframegen.packBlur.title"),
                Component.translatable("vframegen.packBlur.message"));
    }

    /** The option that turns the pack's motion blur on, or null. */
    static String blurOption(Properties p) {
        for (String name : p.stringPropertyNames()) {
            String n = name.toUpperCase(Locale.ROOT).replace("_", "");
            // MOTION_BLUR, MOTION_BLUR_EFFECT, MOTIONBLUR; not MOTION_BLUR_STRENGTH and the like.
            if (!n.equals("MOTIONBLUR") && !n.equals("MOTIONBLUREFFECT")) continue;
            String v = p.getProperty(name).trim();
            if (v.equalsIgnoreCase("true") || v.equals("1")) return name + "=" + v;
        }
        return null;
    }

    private static Properties read(Path file) {
        return read(file, StandardCharsets.UTF_8);
    }

    /** OptiFine-style pack settings are ISO-8859-1 (Vitrail writes them that way too). */
    private static Properties readPackSettings(Path file) {
        return read(file, StandardCharsets.ISO_8859_1);
    }

    private static Properties read(Path file, java.nio.charset.Charset charset) {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, charset)) {
            p.load(r);
        } catch (IOException | RuntimeException ignored) {
        }
        return p;
    }
}
