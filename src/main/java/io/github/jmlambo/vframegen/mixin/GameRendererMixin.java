package io.github.jmlambo.vframegen.mixin;

import io.github.jmlambo.vframegen.FrameGenerator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
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

    // renderItemInHand only submits the hand; it is drawn by the feature dispatcher later in
    // render3dHud, so the hand's depth is only there at the end of the method. The last parameter
    // picks where it went: the separate HUD target (true) or the main depth buffer (false).
    @Inject(method = "render3dHud", at = @At("RETURN"), require = 0)
    private void vframegen$afterHand(CameraRenderState camera, PlayerRenderState player, OptionsRenderState options,
                                     boolean useHudTarget, CallbackInfo ci) {
        FrameGenerator.INSTANCE.captureHandDepth((GameRenderer) (Object) this, useHudTarget);
    }

    /** Checkpoint: the world is drawn (the bulk of a frame's work, with shaders). */
    @Inject(method = "renderLevel", require = 0, at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/client/renderer/LevelRenderer;render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZZ)V"))
    private void vframegen$afterLevel(CallbackInfo ci) {
        FrameGenerator.INSTANCE.checkpoint(FrameGenerator.Checkpoint.AFTER_LEVEL);
    }

    /** Checkpoint: about to draw the GUI. */
    @Inject(method = "render", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/render/GuiRenderer;render()V"))
    private void vframegen$beforeGui(CallbackInfo ci) {
        FrameGenerator.INSTANCE.checkpoint(FrameGenerator.Checkpoint.BEFORE_GUI);
    }

    @Inject(method = "render", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/client/renderer/GameRenderer;applyPostEffects()V"))
    private void vframegen$afterWorld(CallbackInfo ci) {
        FrameGenerator.INSTANCE.captureWorld((GameRenderer) (Object) this);
    }
}
