package io.github.jmlambo.vframegen.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import io.github.jmlambo.vframegen.FrameGenerator;
import io.github.jmlambo.vframegen.RenderScale;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hooks into the main loop's frame: loop start, what gets blitted and presented, the frame limiter. */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    private static final String ACQUIRE = "Lcom/mojang/renderpearl/api/device/GpuSurface;acquireNextTexture()V";
    private static final String BLIT = "Lcom/mojang/renderpearl/api/device/GpuSurface;blitFromTexture(Lcom/mojang/renderpearl/api/commands/CommandEncoder;Lcom/mojang/renderpearl/api/textures/GpuTextureView;)V";
    private static final String SUBMIT = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;submit()V";
    private static final String PRESENT = "Lcom/mojang/renderpearl/api/device/GpuSurface;present()V";

    @Inject(method = "renderFrame", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/DeltaTracker$Timer;advanceRealTime(J)V"))
    private void vframegen$loopStart(boolean advanceGameTime, CallbackInfo ci) {
        RenderScale.restore();
        io.github.jmlambo.vframegen.DynamicScale.loopStart();
        FrameGenerator.INSTANCE.loopStart((Minecraft) (Object) this);
    }

    @Inject(method = "renderFrame", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render()V"))
    private void vframegen$afterExtract(boolean advanceGameTime, CallbackInfo ci) {
        RenderScale.restore();
        FrameGenerator.INSTANCE.afterExtract((Minecraft) (Object) this);
    }

    /** Keep the finished frame; blit the held one instead (interpolated mode), or nothing. */
    @WrapOperation(method = "renderFrame", at = @At(value = "INVOKE", target = BLIT))
    private void vframegen$blit(GpuSurface surface, CommandEncoder encoder, GpuTextureView view, Operation<Void> original) {
        GpuTextureView shown = FrameGenerator.INSTANCE.beforeBlit((Minecraft) (Object) this, view);
        if (shown != null) {
            original.call(surface, encoder, shown);
        }
        RenderScale.restore();
    }

    @WrapOperation(method = "renderFrame", at = @At(value = "INVOKE", target = PRESENT))
    private void vframegen$present(GpuSurface surface, Operation<Void> original) {
        original.call(surface);
        FrameGenerator.INSTANCE.afterRealPresent();
        FrameGenerator.INSTANCE.generateAfterPresent((Minecraft) (Object) this);
    }

    /** Auto cap: vanilla only runs its limiter below 260 (unlimited); see FrameGenerator.frameLimit. */
    @com.llamalad7.mixinextras.injector.ModifyExpressionValue(method = "renderFrame", at = @At(value = "FIELD",
            opcode = org.objectweb.asm.Opcodes.GETFIELD,
            target = "Lnet/minecraft/client/renderer/state/GameRenderState;framerateLimit:I"))
    private int vframegen$frameLimit(int limit) {
        io.github.jmlambo.vframegen.DynamicScale.workDone((Minecraft) (Object) this);
        return FrameGenerator.INSTANCE.frameLimit((Minecraft) (Object) this, limit);
    }

    /** The frame limiter's sleep: generated frames go out in it. */
    @WrapOperation(method = "renderFrame", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/FramerateLimiter;limitDisplayFPS(I)V"))
    private void vframegen$limiter(int limit, Operation<Void> original) {
        FrameGenerator.INSTANCE.beforeLimiterSleep((Minecraft) (Object) this, limit);
        original.call(limit);
        FrameGenerator.INSTANCE.afterLimiterSleep();
    }

    @Inject(method = "renderFrame", at = {
            @At(value = "INVOKE", target = ACQUIRE),
            @At(value = "INVOKE", target = SUBMIT)})
    private void vframegen$beforeAcquireOrSubmit(boolean advanceGameTime, CallbackInfo ci) {
        io.github.jmlambo.vframegen.StallProbe.mark("before acquire/submit");
    }

    @Inject(method = "renderFrame", at = {
            @At(value = "INVOKE", target = ACQUIRE, shift = At.Shift.AFTER),
            @At(value = "INVOKE", target = SUBMIT, shift = At.Shift.AFTER)})
    private void vframegen$afterAcquireOrSubmit(boolean advanceGameTime, CallbackInfo ci) {
        io.github.jmlambo.vframegen.StallProbe.mark("acquire/submit done");
    }
}
