package io.github.jmlambo.vframegen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * VSync and frame generation fight: VSync rounds every present to a screen refresh, so generated
 * frames timed between real ones are shown twice or dropped (heavy stutter), and the present queue
 * backs up. With frame generation on, VSync found on at startup is turned off. Turned on later in
 * a settings screen, it is left alone until the player leaves that screen, and then a prompt asks
 * whether to keep it. Asking then, not while the settings screen is open, keeps every options
 * screen (vanilla, Sodium and others, which cache option values) showing the real value.
 */
public final class VsyncGuard {
    private static boolean seen;          // the VSync setting has been looked at once (startup done)
    private static boolean lastVsync;
    private static boolean promptPending; // turned on during this session: ask after leaving the screen
    private static Screen turnedOnIn;     // the screen it was turned on in
    private static boolean confirming;

    private VsyncGuard() {
    }

    /** Every game loop. */
    public static void enforce(Minecraft mc) {
        if (mc.options == null || confirming) return;
        FrameGenConfig cfg = VFrameGen.config();
        OptionInstance<Boolean> vsync = mc.options.enableVsync();
        boolean on = vsync.get();
        if (!seen) {
            seen = true;
            lastVsync = on;
            if (on && cfg.enabled && !cfg.allowVsync) turnOff(mc, "found on at startup");
            return;
        }
        if (on != lastVsync) {
            lastVsync = on;
            if (on) {
                promptPending = true;
                turnedOnIn = mc.gui.screen();
            } else {
                promptPending = false;
                // Turned off again: the next time it is turned on (with frame generation), ask again.
                if (cfg.allowVsync) {
                    cfg.allowVsync = false;
                    cfg.save();
                }
            }
        }
        if (!promptPending || !on || !cfg.enabled || cfg.allowVsync) {
            promptPending = false;
            return;
        }
        Screen screen = mc.gui.screen();
        if (screen != null && screen == turnedOnIn) return; // still in that settings screen
        promptPending = false;
        turnedOnIn = null;
        confirming = true;
        mc.gui.setScreen(new ConfirmScreen(yes -> {
            confirming = false;
            if (yes) {
                cfg.allowVsync = true;
                cfg.save();
            } else {
                turnOff(mc, "player chose to keep it off");
            }
            mc.gui.setScreen(screen);
        }, Component.translatable("vframegen.vsync.title"), Component.translatable("vframegen.vsync.message"),
                Component.translatable("vframegen.vsync.enable"), Component.translatable("vframegen.vsync.keepOff")));
    }

    private static void turnOff(Minecraft mc, String why) {
        mc.options.enableVsync().set(false);
        mc.options.save();
        lastVsync = false;
        VFrameGen.LOGGER.info("Turned VSync off ({}): it makes frame generation stutter", why);
    }
}
