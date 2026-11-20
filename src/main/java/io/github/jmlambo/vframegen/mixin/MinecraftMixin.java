package io.github.jmlambo.vframegen.mixin;

import io.github.jmlambo.vframegen.FrameGenerator;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hooks into the main loop's frame: generated frame before the real one, plus timing. */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    private static final String ACQUIRE = "Lcom/mojang/renderpearl/api/device/GpuSurface;acquireNextTexture()V";
    private static final String BLIT = "Lcom/mojang/renderpearl/api/device/GpuSurface;blitFromTexture(Lcom/mojang/renderpearl/api/commands/CommandEncoder;Lcom/mojang/renderpearl/api/textures/GpuTextureView;)V";
    private static final String SUBMIT = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;submit()V";
    private static final String PRESENT = "Lcom/mojang/renderpearl/api/device/GpuSurface;present()V";

    @Inject(method = "renderFrame", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render()V"))
    private void vframegen$beforeGameRender(boolean advanceGameTime, CallbackInfo ci) {
        FrameGenerator.INSTANCE.beforeGameRender((Minecraft) (Object) this);
    }

    @Inject(method = "renderFrame", at = @At(value = "INVOKE", target = BLIT))
    private void vframegen$beforeBlit(boolean advanceGameTime, CallbackInfo ci) {
        FrameGenerator.INSTANCE.captureFinal((Minecraft) (Object) this);
    }

    @Inject(method = "renderFrame", at = @At(value = "INVOKE", target = PRESENT, shift = At.Shift.AFTER))
    private void vframegen$afterPresent(boolean advanceGameTime, CallbackInfo ci) {
        FrameGenerator.INSTANCE.afterRealPresent();
    }

    @Inject(method = "renderFrame", at = {
            @At(value = "INVOKE", target = ACQUIRE),
            @At(value = "INVOKE", target = SUBMIT),
            @At(value = "INVOKE", target = PRESENT)})
    private void vframegen$beginBlocking(boolean advanceGameTime, CallbackInfo ci) {
        FrameGenerator.INSTANCE.beginBlocking();
    }

    @Inject(method = "renderFrame", at = {
            @At(value = "INVOKE", target = ACQUIRE, shift = At.Shift.AFTER),
            @At(value = "INVOKE", target = SUBMIT, shift = At.Shift.AFTER),
            @At(value = "INVOKE", target = PRESENT, shift = At.Shift.AFTER)})
    private void vframegen$endBlocking(boolean advanceGameTime, CallbackInfo ci) {
        FrameGenerator.INSTANCE.endBlocking();
    }
}
