package io.github.jmlambo.vframegen.mixin;

import io.github.jmlambo.vframegen.FrameGenerator;
import io.github.jmlambo.vframegen.FrameGenerator.Checkpoint;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Checkpoints inside world rendering, where most of a frame's time goes with shader packs and
 * Distant Horizons. Each sits between two steps with no render pass open. Optional: if another mod
 * replaces a step, that checkpoint is simply never reached.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    private static final String RENDER = "render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZZ)V";
    private static final String EXECUTE = "Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;execute(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder$Inspector;)V";

    @Inject(method = RENDER, require = 0, at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/client/renderer/LevelRenderer;submitFeatures(Lnet/minecraft/client/renderer/state/level/LevelRenderState;Lnet/minecraft/client/renderer/SubmitNodeCollector;Z)V"))
    private void vframegen$afterFeatures(CallbackInfo ci) {
        FrameGenerator.INSTANCE.checkpoint(Checkpoint.AFTER_FEATURES);
    }

    @Inject(method = RENDER, require = 0, at = @At(value = "INVOKE", target = EXECUTE))
    private void vframegen$beforeFrameGraph(CallbackInfo ci) {
        FrameGenerator.INSTANCE.checkpoint(Checkpoint.BEFORE_FRAME_GRAPH);
    }

    @Inject(method = RENDER, require = 0, at = @At(value = "INVOKE", target = EXECUTE, shift = At.Shift.AFTER))
    private void vframegen$afterFrameGraph(CallbackInfo ci) {
        FrameGenerator.INSTANCE.checkpoint(Checkpoint.AFTER_FRAME_GRAPH);
    }
}
