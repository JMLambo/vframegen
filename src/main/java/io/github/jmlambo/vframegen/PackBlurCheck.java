package io.github.jmlambo.vframegen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * A shader pack's own motion blur smears each real frame, and frame generation then reprojects
 * that smear as if it were geometry: ghosting around everything that moves on screen. This warns
 * once (per pack settings change) when the active Iris pack has it on, and points to VFrameGen's
 * motion blur instead.
 *
 * Iris saves only the options changed from the pack's defaults, in shaderpacks/(pack).txt, so this
 * finds blur the player turned on (Complementary: MOTION_BLUR_EFFECT=1), not a pack with blur on by
 * default.
 */
public final class PackBlurCheck {
    private static final SystemToast.SystemToastId TOAST = new SystemToast.SystemToastId(12000L);
    private static final long CHECK_EVERY_NS = 2_000_000_000L;
    private static long nextCheckNs;
    private static String warnedFor = "";

    private PackBlurCheck() {
    }

    /** Every game loop; reads files at most every two seconds. */
    public static void tick(Minecraft mc) {
        long now = System.nanoTime();
        if (now < nextCheckNs) return;
        nextCheckNs = now + CHECK_EVERY_NS;
        if (!VFrameGen.config().enabled || !Compat.irisShadersActive() || mc.level == null) return;
        Path game = mc.gameDirectory.toPath();
        String pack = read(game.resolve("config/iris.properties")).getProperty("shaderPack", "");
        if (pack.isEmpty()) return;
        Path settings = game.resolve("shaderpacks").resolve(pack + ".txt");
        String key;
        try {
            key = pack + "@" + (Files.exists(settings) ? Files.getLastModifiedTime(settings).toMillis() : 0);
        } catch (IOException | RuntimeException e) {
            return;
        }
        if (key.equals(warnedFor)) return;
        String option = blurOption(read(settings));
        if (option == null) return;
        warnedFor = key;
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
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file)) {
            p.load(r);
        } catch (IOException | RuntimeException ignored) {
        }
        return p;
    }
}
