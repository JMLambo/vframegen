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
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * FXAA or SMAA on the world image, after the world (and the render scale upscaler) and before the GUI:
 * the HUD stays sharp, and generated frames are built from the smoothed image. Works with or
 * without frame generation.
 *
 * <p>SMAA is the reference implementation (shaders/include/smaa.glsl, MIT) in its three passes:
 * edges, blending weights (with its precomputed area and search textures), neighbourhood blend.
 */
public final class AntiAliasing {
    private static final int UBO_SIZE = 16;
    private static GpuTexture tempTex;
    private static GpuTextureView tempView;
    private static GpuBuffer ubo;
    private static int w = -1, h = -1;
    private static GpuFormat format;
    private static final Map<String, RenderPipeline> pipelines = new HashMap<>();
    private static final Identifier AREA = Identifier.fromNamespaceAndPath(VFrameGen.MOD_ID, "textures/smaa/area.png");
    private static final Identifier SEARCH = Identifier.fromNamespaceAndPath(VFrameGen.MOD_ID, "textures/smaa/search.png");
    private static GpuTexture edgesTex, weightsTex;
    private static GpuTextureView edgesView, weightsView;
    private static boolean broken;

    private AntiAliasing() {
    }

    /** After the world: smooth the main color target in place. */
    public static void apply(GameRenderer gr) {
        FrameGenConfig.AntiAliasingMode mode = VFrameGen.config().antiAliasing;
        // Free SMAA's two screen-sized targets when it is not in use.
        if (mode != FrameGenConfig.AntiAliasingMode.SMAA) closeSmaaTargets();
        if (broken || mode == FrameGenConfig.AntiAliasingMode.OFF) return;
        // Until the first resource reload is done, mod shaders can't load.
        if (RenderSystemAccessor.vframegen$getCurrentPipelineCache() == null) return;
        RenderTarget main = gr.mainRenderTarget();
        GpuTexture color = main.getColorTexture();
        GpuTextureView colorView = main.getColorTextureView();
        if (color == null || colorView == null) return;
        try {
            boolean smaa = mode == FrameGenConfig.AntiAliasingMode.SMAA;
            int q = Math.clamp(VFrameGen.config().antiAliasingQuality, 1, 4);
            // Fetched before any pass is open: the first fetch uploads the texture.
            GpuTextureView area = null, search = null;
            if (smaa) {
                area = Minecraft.getInstance().getTextureManager().getTexture(AREA).getTextureView();
                search = Minecraft.getInstance().getTextureManager().getTexture(SEARCH).getTextureView();
            }
            ensure(color.getWidth(0), color.getHeight(0), color.getFormat(), smaa);
            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer data = Std140Builder.onStack(stack, UBO_SIZE).putVec4(1f / w, 1f / h, w, h).get();
                encoder.writeToBuffer(ubo.slice(), data);
            }
            var linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
            if (smaa) {
                var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
                try (RenderPass pass = encoder.createRenderPass(() -> "VFrameGen SMAA edges", edgesView,
                        Optional.of(new Vector4f(0f)))) {
                    pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline("smaa_edges", GpuFormat.RGBA8_UNORM, q)));
                    pass.setUniform("Source", colorView, nearest);
                    pass.setUniform("Smaa", ubo);
                    pass.draw(3, 1, 0, 0);
                }
                try (RenderPass pass = encoder.createRenderPass(() -> "VFrameGen SMAA weights", weightsView, Optional.empty())) {
                    pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline("smaa_weights", GpuFormat.RGBA8_UNORM, q)));
                    pass.setUniform("Edges", edgesView, linear);
                    pass.setUniform("Area", area, linear);
                    pass.setUniform("Search", search, linear);
                    pass.setUniform("Smaa", ubo);
                    pass.draw(3, 1, 0, 0);
                }
                try (RenderPass pass = encoder.createRenderPass(() -> "VFrameGen SMAA blend", tempView, Optional.empty())) {
                    pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline("smaa_blend", format, q)));
                    pass.setUniform("Source", colorView, linear);
                    pass.setUniform("Weights", weightsView, linear);
                    pass.setUniform("Smaa", ubo);
                    pass.draw(3, 1, 0, 0);
                }
            } else {
                try (RenderPass pass = encoder.createRenderPass(() -> "VFrameGen FXAA", tempView, Optional.empty())) {
                    pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline("fxaa", format, q)));
                    pass.setUniform("Source", colorView, linear);
                    pass.setUniform("Fxaa", ubo);
                    pass.draw(3, 1, 0, 0);
                }
            }
            encoder.copyTextureToTexture(tempTex, color, 0, 0, 0, 0, 0, w, h);
        } catch (RuntimeException e) {
            broken = true;
            VFrameGen.LOGGER.error("Anti-aliasing failed; it is off for this session", e);
        }
    }

    private static void ensure(int width, int height, GpuFormat color, boolean smaa) {
        if (width == w && height == h && color == format && tempTex != null) {
            if (smaa && edgesTex == null) createSmaaTargets();
            return;
        }
        closeSmaaTargets();
        if (tempView != null) tempView.close();
        if (tempTex != null) tempTex.close();
        GpuDevice device = RenderSystem.getDevice();
        tempTex = device.createTexture("VFrameGen anti-aliasing", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC
                | GpuTexture.USAGE_TEXTURE_BINDING, color, width, height, 1, 1);
        tempView = device.createTextureView(tempTex);
        if (ubo == null) {
            ubo = device.createBuffer(() -> "VFrameGen anti-aliasing uniforms", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, UBO_SIZE);
        }
        w = width;
        h = height;
        format = color;
        if (smaa) createSmaaTargets();
    }

    private static void createSmaaTargets() {
        try {
            createSmaaTargetsUnchecked();
        } catch (RuntimeException e) {
            closeSmaaTargets(); // nothing half-made is left behind
            throw e;
        }
    }

    private static void createSmaaTargetsUnchecked() {
        GpuDevice device = RenderSystem.getDevice();
        int usage = GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING;
        edgesTex = device.createTexture("VFrameGen SMAA edges", usage, GpuFormat.RGBA8_UNORM, w, h, 1, 1);
        edgesView = device.createTextureView(edgesTex);
        weightsTex = device.createTexture("VFrameGen SMAA weights", usage, GpuFormat.RGBA8_UNORM, w, h, 1, 1);
        weightsView = device.createTextureView(weightsTex);
    }

    private static void closeSmaaTargets() {
        if (edgesView != null) edgesView.close();
        if (edgesTex != null) edgesTex.close();
        if (weightsView != null) weightsView.close();
        if (weightsTex != null) weightsTex.close();
        edgesTex = weightsTex = null;
        edgesView = weightsView = null;
    }

    private static final String[] SMAA_PRESETS = {"SMAA_PRESET_LOW", "SMAA_PRESET_MEDIUM", "SMAA_PRESET_HIGH", "SMAA_PRESET_ULTRA"};

    /** quality: 1 (low) to 4 (ultra), SMAA's presets or FXAA's QUALITY define. */
    private static RenderPipeline pipeline(String shader, GpuFormat target, int quality) {
        return pipelines.computeIfAbsent(shader + "_q" + quality + "_" + target.name().toLowerCase(Locale.ROOT), key -> {
            BindGroupLayout.Builder layout = BindGroupLayout.builder();
            switch (shader) {
                case "fxaa" -> layout.withUniform("Source", UniformType.COMBINED_IMAGE_SAMPLER).withUniform("Fxaa", UniformType.UNIFORM_BUFFER);
                case "smaa_edges" -> layout.withUniform("Source", UniformType.COMBINED_IMAGE_SAMPLER).withUniform("Smaa", UniformType.UNIFORM_BUFFER);
                case "smaa_weights" -> layout.withUniform("Edges", UniformType.COMBINED_IMAGE_SAMPLER)
                        .withUniform("Area", UniformType.COMBINED_IMAGE_SAMPLER)
                        .withUniform("Search", UniformType.COMBINED_IMAGE_SAMPLER)
                        .withUniform("Smaa", UniformType.UNIFORM_BUFFER);
                case "smaa_blend" -> layout.withUniform("Source", UniformType.COMBINED_IMAGE_SAMPLER)
                        .withUniform("Weights", UniformType.COMBINED_IMAGE_SAMPLER)
                        .withUniform("Smaa", UniformType.UNIFORM_BUFFER);
                default -> throw new IllegalArgumentException(shader);
            }
            RenderPipeline.Builder builder = RenderPipeline.builder();
            if (shader.startsWith("smaa")) builder.withShaderDefine(SMAA_PRESETS[quality - 1]);
            else builder.withShaderDefine("QUALITY", quality);
            return builder
                    .withLocation(Identifier.fromNamespaceAndPath(VFrameGen.MOD_ID, "pipeline/" + key))
                    .withVertexShader("core/screenquad")
                    .withFragmentShader(Identifier.fromNamespaceAndPath(VFrameGen.MOD_ID, "core/" + shader))
                    .withBindGroupLayout(layout.build())
                    .withColorTargetState(new ColorTargetState(Optional.empty(), target, ColorTargetState.WRITE_ALL))
                    .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                    .build();
        });
    }
}
