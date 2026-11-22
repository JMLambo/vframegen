package io.github.jmlambo.vframegen;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Util;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * F8: save one burst of frames as PNGs into {@code <game dir>/vframegen-debug/<time>/}:
 * the last real frame, each generated frame after it, a debug view of how each pixel was made,
 * and the next real frame.
 */
public final class DebugCapture {
    private static boolean requested;
    private static Path dir;
    private static boolean sequenceRequested;

    private DebugCapture() {
    }

    public static void request() {
        requested = VFrameGen.config().debugCaptures;
        sequenceRequested = VFrameGen.config().debugFrameLog;
    }

    /** An extra line in the sequence log (not a frame). */
    static void sequenceNote(String text) {
        if (!VFrameGen.config().debugFrameLog) return;
        record(String.format(java.util.Locale.ROOT, "   t=%.2f ms %s", System.nanoTime() / 1e6 % 1_000_000, text));
    }

    // The last few seconds of presented frames are always kept (text only, cheap). F8 saves them,
    // so what happened just before the key press is in the file.
    private static final int RING = 2600; // about 10 s at 120 shown fps, plus notes
    private static final java.util.ArrayDeque<String> ring = new java.util.ArrayDeque<>();
    private static int frameNumber;

    private static void record(String line) {
        if (ring.size() >= RING) ring.pollFirst();
        ring.addLast(line);
    }

    /** A frame is about to be presented. */
    static void sequenceFrame(GpuTexture texture, String kind, String info) {
        if (!VFrameGen.config().debugFrameLog) {
            if (!ring.isEmpty()) ring.clear();
            return;
        }
        record(String.format(java.util.Locale.ROOT, "%05d-%s t=%.2f ms %s", frameNumber++ % 100000, kind,
                System.nanoTime() / 1e6 % 1_000_000, info));
        if (!sequenceRequested) return;
        sequenceRequested = false;
        Path out = FabricLoader.getInstance().getGameDir().resolve("vframegen-debug")
                .resolve(Util.getFilenameFormattedDateTime() + "-sequence");
        try {
            Files.createDirectories(out);
            Files.writeString(out.resolve("sequence.txt"), String.join("\n", ring) + "\n");
            VFrameGen.LOGGER.info("Saved the last {} presented frames to {}", ring.size(), out);
        } catch (Exception e) {
            VFrameGen.LOGGER.warn("Could not save the frame log", e);
        }
    }

    /** Called when a burst of generated frames starts. True if this burst should be captured. */
    static boolean begin() {
        if (!requested) return false;
        requested = false;
        dir = FabricLoader.getInstance().getGameDir().resolve("vframegen-debug").resolve(Util.getFilenameFormattedDateTime());
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            VFrameGen.LOGGER.warn("Could not create {}", dir, e);
            dir = null;
            return false;
        }
        VFrameGen.LOGGER.info("Capturing frames to {}", dir);
        return true;
    }

    static void end() {
        dir = null;
    }

    static boolean active() {
        return dir != null;
    }

    static void writeLog(String text) {
        if (dir == null) return;
        try {
            Files.writeString(dir.resolve("timing.txt"), text);
        } catch (Exception e) {
            VFrameGen.LOGGER.warn("Could not write timing log", e);
        }
    }

    /** Read a colour texture back from the GPU and write it as a PNG, asynchronously. */
    static void save(GpuTexture texture, String name) {
        save(dir, texture, name);
    }

    private static void save(Path dir, GpuTexture texture, String name) {
        if (dir == null) return;
        GpuFormat format = texture.getFormat();
        // 8-bit, or half float (some setups render the main target in RGBA16F): clamped to 0..1.
        boolean half = format == GpuFormat.RGBA16_FLOAT;
        // Optical flow (RG16_SINT, pixels * 32): shown as colour, grey = still, red/green = x/y motion.
        boolean flow = format == GpuFormat.RG16_SINT;
        if (format != GpuFormat.RGBA8_UNORM && !half && !flow) {
            VFrameGen.LOGGER.warn("Cannot save {}: format {} is not RGBA8 or RGBA16F", name, format);
            return;
        }
        int w = texture.getWidth(0);
        int h = texture.getHeight(0);
        int bpp = half ? 8 : 4;
        Path file = dir.resolve(name + ".png");
        GpuBuffer buffer = RenderSystem.getDevice().createBuffer(() -> "VFrameGen capture " + name,
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, (long) w * h * bpp);
        RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(texture, buffer, 0, () -> {
            try (GpuBufferSlice.MappedView view = buffer.map(true, false);
                 NativeImage image = new NativeImage(w, h, false)) {
                ByteBuffer data = view.data().order(ByteOrder.LITTLE_ENDIAN);
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int abgr;
                        if (flow) {
                            int o = (y * w + x) * 4;
                            int r = Math.clamp(128 + data.getShort(o) / 8, 0, 255), g = Math.clamp(128 + data.getShort(o + 2) / 8, 0, 255);
                            abgr = 0xFF000000 | (128 << 16) | (g << 8) | r;
                        } else if (half) {
                            int o = (y * w + x) * 8;
                            int r = unit(data.getShort(o)), g = unit(data.getShort(o + 2)), b = unit(data.getShort(o + 4));
                            abgr = 0xFF000000 | (b << 16) | (g << 8) | r;
                        } else {
                            // RGBA bytes read little-endian are ABGR; force opaque.
                            abgr = data.getInt((y * w + x) * 4) | 0xFF000000;
                        }
                        image.setPixelABGR(x, y, abgr);
                    }
                }
                image.writeToFile(file);
            } catch (Exception e) {
                VFrameGen.LOGGER.warn("Could not save {}", file, e);
            } finally {
                buffer.close();
            }
        }, 0);
    }

    /** A half float, clamped to 0..1, as a byte. */
    private static int unit(short half) {
        float v = Float.float16ToFloat(half);
        return v > 0f ? Math.min(255, Math.round(Math.min(v, 1f) * 255f)) : 0;
    }
}
