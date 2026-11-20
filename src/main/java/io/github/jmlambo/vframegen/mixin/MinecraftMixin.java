package io.github.jmlambo.vframegen.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import io.github.jmlambo.vframegen.FrameGenerator;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hooks into the main loop's frame: checkpoints for generated frames, and what gets presented. */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    private static final String ACQUIRE = "Lcom/mojang/renderpearl/api/device/GpuSurface;acquireNextTexture()V";
    private static final String BLIT = "Lcom/mojang/renderpearl/api/device/GpuSurface;blitFromTexture(Lcom/mojang/renderpearl/api/commands/CommandEncoder;Lcom/mojang/renderpearl/api/textures/GpuTextureView;)V";
    private static final String SUBMIT = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;submit()V";
    private static final String PRESENT = "Lcom/mojang/renderpearl/api/device/GpuSurface;present()V";

    @Inject(method = "renderFrame", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/DeltaTracker$Timer;advanceRealTime(J)V"))
    private void vframegen$loopStart(boolean advanceGameTime, CallbackInfo ci) {
        FrameGenerator.INSTANCE.loopStart((Minecraft) (Object) this);
    }

    @Inject(method = "renderFrame", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render()V"))
    private void vframegen$afterExtract(boolean advanceGameTime, CallbackInfo ci) {
        FrameGenerator.INSTANCE.afterExtract((Minecraft) (Object) this);
    }

    /** Keep the finished frame; blit the held one instead (interpolated mode), or nothing. */
    @WrapOperation(method = "renderFrame", at = @At(value = "INVOKE", target = BLIT))
    private void vframegen$blit(GpuSurface surface, CommandEncoder encoder, GpuTextureView view, Operation<Void> original) {
        GpuTextureView shown = FrameGenerator.INSTANCE.beforeBlit((Minecraft) (Object) this, view);
        if (shown != null) {
            original.call(surface, encoder, shown);
        }
    }

    @WrapOperation(method = "renderFrame", at = @At(value = "INVOKE", target = PRESENT))
    private void vframegen$present(GpuSurface surface, Operation<Void> original) {
        if (!FrameGenerator.INSTANCE.shouldPresent()) return;
        FrameGenerator.INSTANCE.beginBlocking();
        original.call(surface);
        FrameGenerator.INSTANCE.endBlocking();
        FrameGenerator.INSTANCE.afterRealPresent();
    }

    @Inject(method = "renderFrame", at = {
            @At(value = "INVOKE", target = ACQUIRE),
            @At(value = "INVOKE", target = SUBMIT)})
    private void vframegen$beginBlocking(boolean advanceGameTime, CallbackInfo ci) {
        FrameGenerator.INSTANCE.beginBlocking();
    }

    @Inject(method = "renderFrame", at = {
            @At(value = "INVOKE", target = ACQUIRE, shift = At.Shift.AFTER),
            @At(value = "INVOKE", target = SUBMIT, shift = At.Shift.AFTER)})
    private void vframegen$endBlocking(boolean advanceGameTime, CallbackInfo ci) {
        FrameGenerator.INSTANCE.endBlocking();
    }
}
