package io.github.jmlambo.vframegen.mixin;

import io.github.jmlambo.vframegen.Compat;
import io.github.jmlambo.vframegen.DeferredSolidAccess;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import io.github.jmlambo.vframegen.FrameGenerator;
import io.github.jmlambo.vframegen.TemporalUpscaler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Keeps the depth of the opaque world, from before water and other translucents are drawn, for
 * generated frames (FrameGenerator.captureOpaqueDepth).
 *
 * <p>Improved Transparency draws translucents in executeOit, after the opaque pass is closed: the
 * depth is copied at its start. Classic transparency draws them in the same render pass as the
 * opaque world, where nothing can be copied: they are moved into a pass of their own, right after
 * it, with the same targets and state, and the depth is copied in between.
 */
// Priority below the default (1000): applied first, so our wrappers sit inside other mods' wrappers
// of the same calls. The deferred calls below keep the Operation they get and call it after the
// handler returned; outside Iris's beginTranslucents wrapper that Operation was Iris's, whose
// @Local reference is gone by then ("Use of a disposed LocalRef", crash on joining a world with the
// official Iris and shaders off). Inside it, the Operation is the plain vanilla call.
@Mixin(value = LevelRenderer.class, priority = 500)
public abstract class LevelRendererMixin implements DeferredSolidAccess {
    @Shadow
    @Final
    private LevelTargetBundle targets;

    @Unique
    private ChunkSectionsToRender vframegen$deferredSections;
    @Unique
    private FeatureRenderDispatcher.PreparedFrame vframegen$deferredFeatures;
    // The call itself, so other mods' wrappers around it still run (once, with the new pass).
    @Unique
    private Operation<Void> vframegen$deferredCall;

    @Shadow
    private com.mojang.renderpearl.api.textures.GpuSampler chunkLayerSampler;

    // The terrain sampler: made with temporal upscaling's mip bias (lower-resolution rendering
    // would otherwise pick blurrier mip levels, and the history could not get the detail back).
    @WrapOperation(method = "lambda$addMainPass$0", require = 0, at = @At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/api/device/GpuDevice;createSampler(Lcom/mojang/renderpearl/api/textures/AddressMode;Lcom/mojang/renderpearl/api/textures/AddressMode;Lcom/mojang/renderpearl/api/textures/FilterMode;Lcom/mojang/renderpearl/api/textures/FilterMode;ILjava/util/OptionalDouble;)Lcom/mojang/renderpearl/api/textures/GpuSampler;"))
    private com.mojang.renderpearl.api.textures.GpuSampler vframegen$biasedTerrainSampler(
            com.mojang.renderpearl.api.device.GpuDevice device, com.mojang.renderpearl.api.textures.AddressMode u,
            com.mojang.renderpearl.api.textures.AddressMode v, com.mojang.renderpearl.api.textures.FilterMode min,
            com.mojang.renderpearl.api.textures.FilterMode mag, int anisotropy, java.util.OptionalDouble maxLod,
            Operation<com.mojang.renderpearl.api.textures.GpuSampler> original) {
        TemporalUpscaler.beginTerrainSampler();
        try {
            return original.call(device, u, v, min, mag, anisotropy, maxLod);
        } finally {
            TemporalUpscaler.endTerrainSampler();
        }
    }

    @Inject(method = "lambda$addMainPass$0", at = @At("HEAD"), require = 0)
    private void vframegen$resetTerrainSampler(CallbackInfo ci) {
        if (TemporalUpscaler.terrainSamplerStale() && chunkLayerSampler != null) {
            chunkLayerSampler.close();
            chunkLayerSampler = null;
        }
    }

    @Unique
    private FeatureRenderDispatcher.PreparedFrame vframegen$deferredSolid;
    @Unique
    private Operation<Void> vframegen$deferredSolidCall;

    // Entities and other solid features are drawn in a pass of their own, after the terrain's, so
    // the depth of the terrain alone can be kept in between (FrameGenerator.captureTerrainDepth).
    @WrapOperation(method = "executeSolid", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;executeSolid(Lcom/mojang/renderpearl/api/commands/RenderPass;)V"))
    private void vframegen$deferSolidFeatures(FeatureRenderDispatcher.PreparedFrame features, RenderPass pass,
            Operation<Void> original) {
        if (!FrameGenerator.INSTANCE.wantsTerrainDepth()) {
            original.call(features, pass);
            return;
        }
        if (Compat.vitrailShadersActive()) {
            // Vitrail serves entities through the level's own pass; a pass of ours makes them
            // disappear. Its command encoder closes the level pass for a copy and opens it again
            // on the next draw, so the terrain depth can be taken here without moving anything.
            FrameGenerator.INSTANCE.captureTerrainDepth(Minecraft.getInstance().gameRenderer);
            original.call(features, pass);
            return;
        }
        vframegen$deferredSolid = features;
        vframegen$deferredSolidCall = original;
    }

    @Override
    public void vframegen$runDeferredSolid() {
        FeatureRenderDispatcher.PreparedFrame features = vframegen$deferredSolid;
        Operation<Void> call = vframegen$deferredSolidCall;
        if (features == null) return;
        vframegen$deferredSolid = null;
        vframegen$deferredSolidCall = null;
        FrameGenerator.INSTANCE.captureTerrainDepth(Minecraft.getInstance().gameRenderer);
        RenderTarget main = targets.main.get();
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Solid features", main.getColorTextureView(), Optional.empty(),
                main.getDepthTextureView(), OptionalDouble.empty())) {
            RenderSystem.bindDefaultUniforms(pass);
            call.call(features, pass);
        }
    }

    @Inject(method = "executeOit", at = @At("HEAD"), require = 0)
    private void vframegen$depthBeforeOit(CallbackInfo ci) {
        vframegen$runDeferredSolid();
        if (FrameGenerator.INSTANCE.wantsOpaqueDepth()) {
            FrameGenerator.INSTANCE.captureOpaqueDepth(Minecraft.getInstance().gameRenderer);
        }
    }

    @WrapOperation(method = "lambda$addMainPass$0", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;executeClassicTransparency(Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;Lcom/mojang/renderpearl/api/commands/RenderPass;)V"))
    private void vframegen$deferClassicTransparency(LevelRenderer self, ChunkSectionsToRender sections,
            FeatureRenderDispatcher.PreparedFrame features, RenderPass pass, Operation<Void> original) {
        if (!FrameGenerator.INSTANCE.wantsOpaqueDepth()) {
            original.call(self, sections, features, pass);
            return;
        }
        if (Compat.vitrailShadersActive()) {
            // Vitrail can close its level pass for a copy (its command encoder does that for every
            // copy and clear), so the depth can be taken right here and the translucents stay in
            // the game's own pass. Deferring them into a pass of ours would make Vitrail open its
            // pack pass inside that one and crash.
            FrameGenerator.INSTANCE.captureOpaqueDepth(Minecraft.getInstance().gameRenderer);
            original.call(self, sections, features, pass);
            return;
        }
        vframegen$deferredSections = sections;
        vframegen$deferredFeatures = features;
        vframegen$deferredCall = original;
    }

    // The opaque pass is closed by here; executeOit (skipped in classic mode) would come next.
    @Inject(method = "lambda$addMainPass$0", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;executeOutline(Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;)V"))
    private void vframegen$runDeferredTransparency(CallbackInfo ci) {
        vframegen$runDeferredSolid();
        ChunkSectionsToRender sections = vframegen$deferredSections;
        FeatureRenderDispatcher.PreparedFrame features = vframegen$deferredFeatures;
        Operation<Void> call = vframegen$deferredCall;
        if (sections == null) return;
        vframegen$deferredSections = null;
        vframegen$deferredFeatures = null;
        vframegen$deferredCall = null;
        FrameGenerator.INSTANCE.captureOpaqueDepth(Minecraft.getInstance().gameRenderer);
        RenderTarget main = targets.main.get();
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Main (translucent)", main.getColorTextureView(), Optional.empty(),
                main.getDepthTextureView(), OptionalDouble.empty())) {
            RenderSystem.bindDefaultUniforms(pass);
            call.call((LevelRenderer) (Object) this, sections, features, pass);
        }
    }
}
