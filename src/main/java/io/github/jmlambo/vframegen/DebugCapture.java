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

    private DebugCapture() {
    }

    public static void request() {
        requested = true;
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
        if (dir == null) return;
        GpuFormat format = texture.getFormat();
        if (format != GpuFormat.RGBA8_UNORM) {
            VFrameGen.LOGGER.warn("Cannot save {}: format {} is not RGBA8", name, format);
            return;
        }
        int w = texture.getWidth(0);
        int h = texture.getHeight(0);
        Path file = dir.resolve(name + ".png");
        GpuBuffer buffer = RenderSystem.getDevice().createBuffer(() -> "VFrameGen capture " + name,
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, (long) w * h * 4);
        RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(texture, buffer, 0, () -> {
            try (GpuBufferSlice.MappedView view = buffer.map(true, false);
                 NativeImage image = new NativeImage(w, h, false)) {
                ByteBuffer data = view.data().order(ByteOrder.LITTLE_ENDIAN);
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        // RGBA bytes read little-endian are ABGR; force opaque.
                        int abgr = data.getInt((y * w + x) * 4) | 0xFF000000;
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
}
