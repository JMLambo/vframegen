package io.github.jmlambo.vframegen.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import io.github.jmlambo.vframegen.FrameGenerator;
import io.github.jmlambo.vframegen.RenderScale;
import net.minecraft.client.renderer.state.WindowRenderState;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import org.joml.Matrix4f;
import org.objectweb.asm.Opcodes;
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

    @Inject(method = "render", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/client/renderer/GameRenderer;applyPostEffects()V"))
    private void vframegen$afterWorld(CallbackInfo ci) {
        GameRenderer gr = (GameRenderer) (Object) this;
        WindowRenderState window = gr.gameRenderState().windowRenderState;
        RenderScale.upscaleAndSwap(gr, window.width, window.height);
        FrameGenerator.INSTANCE.captureWorld(gr);
    }

    // Render scale: the main target (and with it the world, the shader pack and DH) follows the
    // scaled window size. Only inside render(); the GUI still lays out for the real window.
    @ModifyExpressionValue(method = "render", at = @At(value = "FIELD", opcode = Opcodes.GETFIELD,
            target = "Lnet/minecraft/client/renderer/state/WindowRenderState;width:I"))
    private int vframegen$scaledWidth(int width) {
        return RenderScale.scaled(width);
    }

    @ModifyExpressionValue(method = "render", at = @At(value = "FIELD", opcode = Opcodes.GETFIELD,
            target = "Lnet/minecraft/client/renderer/state/WindowRenderState;height:I"))
    private int vframegen$scaledHeight(int height) {
        return RenderScale.scaled(height);
    }

    /** No world this frame (menus): still give the GUI a full-size target. */
    @Inject(method = "render", at = @At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;clearDepthTexture(Lcom/mojang/renderpearl/api/textures/GpuTexture;D)V"))
    private void vframegen$beforeGuiDepthClear(CallbackInfo ci) {
        GameRenderer gr = (GameRenderer) (Object) this;
        WindowRenderState window = gr.gameRenderState().windowRenderState;
        RenderScale.upscaleAndSwap(gr, window.width, window.height);
    }
}
