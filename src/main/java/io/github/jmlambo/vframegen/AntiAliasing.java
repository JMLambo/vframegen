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
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import io.github.jmlambo.vframegen.mixin.RenderSystemAccessor;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * FXAA on the world image, after the world (and the render scale upscaler) and before the GUI:
 * the HUD stays sharp, and generated frames are built from the smoothed image. Works with or
 * without frame generation.
 */
public final class AntiAliasing {
    private static final int UBO_SIZE = 16;
    private static GpuTexture tempTex;
    private static GpuTextureView tempView;
    private static GpuBuffer ubo;
    private static int w = -1, h = -1;
    private static GpuFormat format;
    private static final Map<GpuFormat, RenderPipeline> pipelines = new HashMap<>();
    private static boolean broken;

    private AntiAliasing() {
    }

    /** After the world: smooth the main color target in place. */
    public static void apply(GameRenderer gr) {
        if (broken || !VFrameGen.config().antiAliasing) return;
        // Until the first resource reload is done, mod shaders can't load.
        if (RenderSystemAccessor.vframegen$getCurrentPipelineCache() == null) return;
        RenderTarget main = gr.mainRenderTarget();
        GpuTexture color = main.getColorTexture();
        GpuTextureView colorView = main.getColorTextureView();
        if (color == null || colorView == null) return;
        try {
            ensure(color.getWidth(0), color.getHeight(0), color.getFormat());
            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer data = Std140Builder.onStack(stack, UBO_SIZE).putVec4(1f / w, 1f / h, 0f, 0f).get();
                encoder.writeToBuffer(ubo.slice(), data);
            }
            try (RenderPass pass = encoder.createRenderPass(() -> "VFrameGen FXAA", tempView, Optional.empty())) {
                pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline(format)));
                pass.setUniform("Source", colorView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
                pass.setUniform("Fxaa", ubo);
                pass.draw(3, 1, 0, 0);
            }
            encoder.copyTextureToTexture(tempTex, color, 0, 0, 0, 0, 0, w, h);
        } catch (RuntimeException e) {
            broken = true;
            VFrameGen.LOGGER.error("Anti-aliasing failed; it is off for this session", e);
        }
    }

    private static void ensure(int width, int height, GpuFormat color) {
        if (width == w && height == h && color == format && tempTex != null) return;
        if (tempView != null) tempView.close();
        if (tempTex != null) tempTex.close();
        GpuDevice device = RenderSystem.getDevice();
        tempTex = device.createTexture("VFrameGen FXAA", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC
                | GpuTexture.USAGE_TEXTURE_BINDING, color, width, height, 1, 1);
        tempView = device.createTextureView(tempTex);
        if (ubo == null) {
            ubo = device.createBuffer(() -> "VFrameGen FXAA uniforms", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, UBO_SIZE);
        }
        w = width;
        h = height;
        format = color;
    }

    private static RenderPipeline pipeline(GpuFormat color) {
        return pipelines.computeIfAbsent(color, f -> RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath(VFrameGen.MOD_ID, "pipeline/fxaa_" + f.name().toLowerCase(Locale.ROOT)))
                .withVertexShader("core/screenquad")
                .withFragmentShader(Identifier.fromNamespaceAndPath(VFrameGen.MOD_ID, "core/fxaa"))
                .withBindGroupLayout(BindGroupLayout.builder()
                        .withUniform("Source", UniformType.COMBINED_IMAGE_SAMPLER)
                        .withUniform("Fxaa", UniformType.UNIFORM_BUFFER)
                        .build())
                .withColorTargetState(new ColorTargetState(Optional.empty(), f, ColorTargetState.WRITE_ALL))
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .build());
    }
}
