package io.github.jmlambo.vframegen.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.backend.vulkan.DestructionQueue;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandPool;
import com.mojang.renderpearl.backend.vulkan.checkpoints.CheckpointExtension;
import io.github.jmlambo.vframegen.FrameGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * The Vulkan backend's submit() also ends a "submit slot": it bumps the submit counter, signals the
 * frame semaphore, waits until the submit two slots back is done (MAX_SUBMITS_IN_FLIGHT = 2) and
 * resets that slot's command pool. Vanilla submits once per frame, so that keeps about two frames
 * in flight. A generated frame is submitted in the middle of a frame; counting it as a slot would
 * make the CPU wait for GPU work of the same frame (each generated frame cost ~5 ms of stalls).
 *
 * <p>So a generated frame's submit only hands the recorded work to the queue: no new slot, no
 * semaphore signal, no wait, no pool reset, no cleanup rotation. Vanilla's own end-of-frame submit
 * then does all of that once, for everything the frame recorded, as it always does.
 */
@Mixin(VulkanCommandEncoder.class)
public abstract class VulkanCommandEncoderMixin {
    @org.spongepowered.asm.mixin.Shadow
    private long submitSemaphore;
    @org.spongepowered.asm.mixin.Shadow
    private long currentSubmitIndex;
    @org.spongepowered.asm.mixin.Shadow
    @org.spongepowered.asm.mixin.Final
    private com.mojang.renderpearl.backend.vulkan.VulkanDevice device;

    /** Vanilla's submit signals currentSubmitIndex on submitSemaphore: the present thread waits on it. */
    @org.spongepowered.asm.mixin.injection.Inject(method = "submit", at = @At("HEAD"))
    private void vframegen$recordSubmit(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (!FrameGenerator.INSTANCE.submittingGenerated()) {
            io.github.jmlambo.vframegen.PresentThread.realSubmit(device.vkDevice(), submitSemaphore, currentSubmitIndex);
        }
    }

    @WrapOperation(method = "submit", at = @At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanCommandEncoder;signalSemaphore(JJJ)V"))
    private void vframegen$signal(VulkanCommandEncoder self, long semaphore, long value, long stage, Operation<Void> original) {
        if (!FrameGenerator.INSTANCE.submittingGenerated()) original.call(self, semaphore, value, stage);
    }

    @ModifyConstant(method = "submit", constant = @Constant(longValue = 1L))
    private long vframegen$sameSlot(long one) {
        return FrameGenerator.INSTANCE.submittingGenerated() ? 0L : one;
    }

    @WrapOperation(method = "submit", at = @At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanCommandEncoder;awaitSubmitCompletion(JJ)Z"))
    private boolean vframegen$noWait(VulkanCommandEncoder self, long index, long timeout, Operation<Boolean> original) {
        return FrameGenerator.INSTANCE.submittingGenerated() || original.call(self, index, timeout);
    }

    @WrapOperation(method = "submit", at = @At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanCommandPool;reset()V"))
    private void vframegen$keepPool(VulkanCommandPool pool, Operation<Void> original) {
        if (!FrameGenerator.INSTANCE.submittingGenerated()) original.call(pool);
    }

    @WrapOperation(method = "submit", at = @At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/backend/vulkan/DestructionQueue;rotate()Z"))
    private boolean vframegen$keepDestroyQueue(DestructionQueue<?> queue, Operation<Boolean> original) {
        return !FrameGenerator.INSTANCE.submittingGenerated() && original.call(queue);
    }

    @WrapOperation(method = "submit", at = @At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/backend/vulkan/checkpoints/CheckpointExtension$CheckpointStorage;rotate()V"))
    private void vframegen$keepCheckpoints(CheckpointExtension.CheckpointStorage storage, Operation<Void> original) {
        if (!FrameGenerator.INSTANCE.submittingGenerated()) original.call(storage);
    }
}
