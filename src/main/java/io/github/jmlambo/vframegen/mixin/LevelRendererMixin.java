package io.github.jmlambo.vframegen.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import io.github.jmlambo.vframegen.FrameGenerator;
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
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
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

    @Inject(method = "executeOit", at = @At("HEAD"), require = 0)
    private void vframegen$depthBeforeOit(CallbackInfo ci) {
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
        vframegen$deferredSections = sections;
        vframegen$deferredFeatures = features;
        vframegen$deferredCall = original;
    }

    // The opaque pass is closed by here; executeOit (skipped in classic mode) would come next.
    @Inject(method = "lambda$addMainPass$0", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;executeOutline(Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;)V"))
    private void vframegen$runDeferredTransparency(CallbackInfo ci) {
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
