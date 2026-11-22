package io.github.jmlambo.vframegen.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.renderer.DebugCrosshairRenderer;
import io.github.jmlambo.vframegen.AntiAliasing;
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
        // Temporal upscaling shifts it by a sub-pixel jitter; generated frames want it without.
        FrameGenerator.INSTANCE.captureProjection(io.github.jmlambo.vframegen.TemporalUpscaler.captureProjection(projection));
        return projection;
    }

    @Inject(method = "renderLevel", at = @At("HEAD"), require = 0)
    private void vframegen$levelStart(CallbackInfo ci) {
        io.github.jmlambo.vframegen.TemporalUpscaler.beginLevel((GameRenderer) (Object) this);
    }

    @Inject(method = "renderLevel", at = @At("RETURN"), require = 0)
    private void vframegen$levelEnd(CallbackInfo ci) {
        io.github.jmlambo.vframegen.TemporalUpscaler.endLevel();
    }

    @Inject(method = "render3dHud", at = @At("HEAD"), require = 0)
    private void vframegen$beforeHud3d(CallbackInfo ci) {
        FrameGenerator.INSTANCE.captureWorldDepth((GameRenderer) (Object) this);
        io.github.jmlambo.vframegen.TemporalUpscaler.captureDepth((GameRenderer) (Object) this);
        FrameGenerator.INSTANCE.crosshairFrameStart();
    }

    // The F3 crosshair turns with the camera: with frame generation on, it is drawn on every shown
    // frame instead (motionblur.fsh), so generated frames don't show the real frame's copy.
    @WrapOperation(method = "render3dHud", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/DebugCrosshairRenderer;render(Lnet/minecraft/client/renderer/state/level/CameraRenderState;ILcom/mojang/renderpearl/api/textures/GpuTextureView;Lcom/mojang/renderpearl/api/textures/GpuTextureView;)V"))
    private void vframegen$crosshair(DebugCrosshairRenderer renderer, CameraRenderState camera, int guiScale,
                                     GpuTextureView color, GpuTextureView depth, Operation<Void> original) {
        if (!FrameGenerator.INSTANCE.takeCrosshair(camera.hudFov, guiScale)) {
            original.call(renderer, camera, guiScale, color, depth);
        }
    }

    // renderItemInHand only submits the hand; it is drawn by the feature dispatcher later in
    // render3dHud, so the hand's depth is only there at the end of the method. The last parameter
    // picks where it went: the separate HUD target (true) or the main depth buffer (false).
    @Inject(method = "render3dHud", at = @At("RETURN"), require = 0)
    private void vframegen$afterHand(CameraRenderState camera, PlayerRenderState player, OptionsRenderState options,
                                     boolean useHudTarget, CallbackInfo ci) {
        FrameGenerator.INSTANCE.captureHandDepth((GameRenderer) (Object) this, useHudTarget);
        io.github.jmlambo.vframegen.TemporalUpscaler.captureHand((GameRenderer) (Object) this, useHudTarget);
    }

    @Inject(method = "render", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/client/renderer/GameRenderer;applyPostEffects()V"))
    private void vframegen$afterWorld(CallbackInfo ci) {
        GameRenderer gr = (GameRenderer) (Object) this;
        WindowRenderState window = gr.gameRenderState().windowRenderState;
        RenderScale.upscaleAndSwap(gr, window.width, window.height);
        AntiAliasing.apply(gr);
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
