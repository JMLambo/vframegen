package io.github.jmlambo.vframegen;

import java.lang.reflect.Array;
import java.lang.reflect.Field;

/**
 * How many texture slots one draw can read. Minecraft's own OpenGL renderer keeps a table of 12
 * (renderpearl's GlStateManager.TEXTURES) and fails with an ArrayIndexOutOfBoundsException on a
 * 13th; Sodium and Iris raise it. Other backends have no such table. A generated frame reads 12
 * textures (6 per real frame), 14 with optical flow, and 13 for the full-resolution pass on top of a
 * frame drawn at the render scale (interpolation only: with one real frame, low-latency mode, it is 7).
 */
public final class TextureSlots {
    /** Two real frames: world, depth, hand mask, world without the hand, final, terrain depth, each. */
    public static final int GENERATED_FRAME = 12;
    /** Plus the forward and backward optical flow. */
    public static final int WITH_FLOW = 14;
    /** Plus the upscaled picture (the full-resolution pass after a render-scale frame). */
    public static final int WITH_UPSCALED = 13;

    private static int count = -1;

    private TextureSlots() {
    }

    /** The slots a draw can read on the active backend (unknown until the device exists: no limit). */
    public static int count() {
        if (count >= 0) return count;
        String backend = FrameGenerator.INSTANCE.backendName();
        if ("?".equals(backend)) return Integer.MAX_VALUE;
        int n = Integer.MAX_VALUE;
        if ("OpenGL".equalsIgnoreCase(backend)) {
            n = GENERATED_FRAME; // assume the vanilla table if it cannot be read
            try {
                Field f = Class.forName("com.mojang.renderpearl.backend.opengl.GlStateManager").getDeclaredField("TEXTURES");
                f.setAccessible(true);
                n = Array.getLength(f.get(null));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                VFrameGen.LOGGER.warn("Could not read the OpenGL texture slot count; assuming {}", n, e);
            }
        }
        count = n;
        VFrameGen.LOGGER.info("Texture slots per draw on {}: {}", backend, n == Integer.MAX_VALUE ? "unlimited" : n);
        return n;
    }

    public static boolean fitsFlow() {
        return count() >= WITH_FLOW;
    }

    public static boolean fitsUpscaled() {
        return count() >= WITH_UPSCALED;
    }
}
