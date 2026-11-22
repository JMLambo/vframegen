package io.github.jmlambo.vframegen;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import io.github.jmlambo.vframegen.mixin.RenderSystemAccessor;
import io.github.jmlambo.vframegen.mixin.RenderTargetAccessor;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Render scale: the world (and the shader pack, and Distant Horizons) renders into a main target
 * smaller than the window. Right after the world is finished, it is upscaled (edge-aware bicubic,
 * then contrast-adaptive sharpening, or FSR 1 style EASU + RCAS) into full-size textures, and those are swapped into the main
 * target for the GUI and the final blit, so the GUI stays sharp. The low-resolution textures are
 * swapped back right after the blit, before the next frame renders.
 */
public final class RenderScale {
    private static final int UBO_SIZE = 2 * 16;

    private static int fullW, fullH;
    private static GpuFormat colorFormat, depthFormat;
    private static GpuTexture tempTex, displayTex, displayDepth;
    private static GpuTextureView tempView, displayView, displayDepthView;
    private static GpuBuffer ubo;
    private static final Map<String, RenderPipeline> pipelines = new HashMap<>();

    // The main target's own (low-resolution) textures while the full-size ones are swapped in.
    private static RenderTarget swappedTarget;
    private static GpuTexture savedColor, savedDepth;
    private static GpuTextureView savedColorView, savedDepthView;
    private static int savedW, savedH;
    private static boolean broken;
    private static int failures;

    private RenderScale() {
    }

    public static double scale() {
        if (broken) return 1.0;
        double s = DynamicScale.enabled() ? DynamicScale.current() : VFrameGen.config().renderScale;
        return s >= 0.999 ? 1.0 : Math.max(0.25, s);
    }

    /** Size the world renders at, for a window dimension. */
    public static int scaled(int windowSize) {
        double s = scale();
        return s >= 1.0 ? windowSize : Math.max(1, (int) Math.round(windowSize * s));
    }


    /**
     * The world is done: upscale the main target into full-size textures and swap them in.
     * windowW/H: the window size (the GUI's and the final image's size).
     */
    public static void upscaleAndSwap(GameRenderer gr, int windowW, int windowH) {
        if (swappedTarget != null || broken) return;
        // Until the first resource reload is done, only vanilla's shaders can load, and every
        // failed try logs a "Couldn't preload shader" stack trace.
        if (RenderSystemAccessor.vframegen$getCurrentPipelineCache() == null) return;
        RenderTarget main = gr.mainRenderTarget();
        if (main.width == windowW && main.height == windowH) return;
        GpuTexture color = main.getColorTexture();
        GpuTexture depth = main.getDepthTexture();
        if (color == null || depth == null) return;
        try {
            ensure(windowW, windowH, color.getFormat(), depth.getFormat());
            upscale(main.getColorTextureView(), main.width, main.height);
            failures = 0;
        } catch (RuntimeException e) {
            // Shaders are not loaded yet during startup and resource reloads: try again later.
            if (++failures >= 600) {
                broken = true;
                VFrameGen.LOGGER.error("Render scale keeps failing; rendering at full resolution from now on", e);
            } else if (failures == 1) {
                VFrameGen.LOGGER.debug("Render scale not ready yet: {}", e.toString());
            }
            return;
        }
        RenderTargetAccessor acc = (RenderTargetAccessor) main;
        savedColor = acc.vframegen$getColorTexture();
        savedColorView = acc.vframegen$getColorTextureView();
        savedDepth = acc.vframegen$getDepthTexture();
        savedDepthView = acc.vframegen$getDepthTextureView();
        savedW = main.width;
        savedH = main.height;
        acc.vframegen$setColorTexture(displayTex);
        acc.vframegen$setColorTextureView(displayView);
        acc.vframegen$setDepthTexture(displayDepth);
        acc.vframegen$setDepthTextureView(displayDepthView);
        main.width = windowW;
        main.height = windowH;
        swappedTarget = main;
    }

    /** The world's colour as rendered (the low-resolution texture while swapped). */
    public static GpuTexture worldColor(RenderTarget main) {
        return main == swappedTarget ? savedColor : main.getColorTexture();
    }

    /** The world's depth as rendered (the low-resolution texture while swapped). */
    public static GpuTexture worldDepth(RenderTarget main) {
        return main == swappedTarget ? savedDepth : main.getDepthTexture();
    }

    /** Put the main target's own textures back. */
    public static void restore() {
        RenderTarget main = swappedTarget;
        if (main == null) return;
        swappedTarget = null;
        RenderTargetAccessor acc = (RenderTargetAccessor) main;
        acc.vframegen$setColorTexture(savedColor);
        acc.vframegen$setColorTextureView(savedColorView);
        acc.vframegen$setDepthTexture(savedDepth);
        acc.vframegen$setDepthTextureView(savedDepthView);
        main.width = savedW;
        main.height = savedH;
        savedColor = savedDepth = null;
        savedColorView = savedDepthView = null;
    }

    private static void ensure(int w, int h, GpuFormat color, GpuFormat depth) {
        if (w == fullW && h == fullH && color == colorFormat && depth == depthFormat && displayTex != null) return;
        fullW = fullH = -1;
        release();
        GpuDevice device = RenderSystem.getDevice();
        int usage = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC
                | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT;
        tempTex = device.createTexture("VFrameGen upscale", usage, color, w, h, 1, 1);
        displayTex = device.createTexture("VFrameGen upscaled main color", usage, color, w, h, 1, 1);
        displayDepth = device.createTexture("VFrameGen upscaled main depth", usage, depth, w, h, 1, 1);
        tempView = device.createTextureView(tempTex);
        displayView = device.createTextureView(displayTex);
        displayDepthView = device.createTextureView(displayDepth);
        if (ubo == null) {
            ubo = device.createBuffer(() -> "VFrameGen upscale uniforms", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, UBO_SIZE);
        }
        fullW = w;
        fullH = h;
        colorFormat = color;
        depthFormat = depth;
        VFrameGen.LOGGER.info("Render scale buffers: {}x{} {}", w, h, color);
    }

    private static void release() {
        for (GpuTextureView v : new GpuTextureView[]{tempView, displayView, displayDepthView}) {
            if (v != null) v.close();
        }
        for (GpuTexture t : new GpuTexture[]{tempTex, displayTex, displayDepth}) {
            if (t != null) t.close();
        }
        tempView = displayView = displayDepthView = null;
        tempTex = displayTex = displayDepth = null;
    }

    private static void upscale(GpuTextureView src, int srcW, int srcH) {
        GpuDevice device = RenderSystem.getDevice();
        CommandEncoder encoder = device.createCommandEncoder();
        float sharp = (float) Math.max(0.0, Math.min(1.0, VFrameGen.config().upscaleSharpness));
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer data = Std140Builder.onStack(stack, UBO_SIZE)
                    .putVec4(1f / srcW, 1f / srcH, srcW, srcH)
                    .putVec4(1f / fullW, 1f / fullH, sharp, 0f)
                    .get();
            encoder.writeToBuffer(ubo.slice(), data);
        }
        GpuSampler linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        boolean edgeAdaptive = VFrameGen.config().upscaler == FrameGenConfig.Upscaler.EDGE_ADAPTIVE;
        try (RenderPass pass = encoder.createRenderPass(() -> "VFrameGen upscale", tempView, Optional.empty())) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline(edgeAdaptive ? "easu" : "upscale", colorFormat)));
            pass.setUniform("Source", src, edgeAdaptive ? nearest : linear);
            pass.setUniform("Upscale", ubo);
            pass.draw(3, 1, 0, 0);
        }
        try (RenderPass pass = encoder.createRenderPass(() -> "VFrameGen sharpen", displayView, Optional.empty())) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline(edgeAdaptive ? "rcas" : "sharpen", colorFormat)));
            pass.setUniform("Source", tempView, nearest);
            pass.setUniform("Upscale", ubo);
            pass.draw(3, 1, 0, 0);
        }
    }

    private static RenderPipeline pipeline(String shader, GpuFormat format) {
        String key = shader + "_" + format.name().toLowerCase(Locale.ROOT);
        return pipelines.computeIfAbsent(key, k -> RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath(VFrameGen.MOD_ID, "pipeline/" + k))
                .withVertexShader("core/screenquad")
                .withFragmentShader(Identifier.fromNamespaceAndPath(VFrameGen.MOD_ID, "core/" + shader))
                .withBindGroupLayout(BindGroupLayout.builder()
                        .withUniform("Source", UniformType.COMBINED_IMAGE_SAMPLER)
                        .withUniform("Upscale", UniformType.UNIFORM_BUFFER)
                        .build())
                .withColorTargetState(new ColorTargetState(Optional.empty(), format, ColorTargetState.WRITE_ALL))
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .build());
    }
}
