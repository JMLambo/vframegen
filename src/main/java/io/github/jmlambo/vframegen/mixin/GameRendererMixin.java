package io.github.jmlambo.vframegen.mixin;

import io.github.jmlambo.vframegen.FrameGenerator;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Copies what the next generated frame needs while the real frame renders. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @ModifyArg(method = "renderLevel", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"))
    private Matrix4f vframegen$captureProjection(Matrix4f projection) {
        FrameGenerator.INSTANCE.captureProjection(projection);
        return projection;
    }

    @Inject(method = "render3dHud", at = @At("HEAD"), require = 0)
    private void vframegen$beforeHud3d(CallbackInfo ci) {
        FrameGenerator.INSTANCE.captureWorldDepth((GameRenderer) (Object) this);
    }

    @Inject(method = "render3dHud", require = 0, at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/client/renderer/GameRenderer;renderItemInHand(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lcom/mojang/renderpearl/api/textures/GpuTextureView;)V"))
    private void vframegen$afterHand(CallbackInfo ci) {
        FrameGenerator.INSTANCE.captureHandDepth((GameRenderer) (Object) this);
    }

    @Inject(method = "render", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/client/renderer/GameRenderer;applyPostEffects()V"))
    private void vframegen$afterWorld(CallbackInfo ci) {
        FrameGenerator.INSTANCE.captureWorld((GameRenderer) (Object) this);
    }
}
