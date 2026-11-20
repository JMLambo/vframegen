package io.github.jmlambo.vframegen.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.jmlambo.vframegen.PresentThread;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSubmitInfo2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Queue submissions share the queue with VFrameGen's present thread. */
@Mixin(targets = "com.mojang.renderpearl.backend.vulkan.VulkanQueue$Submission")
public abstract class VulkanQueueMixin {
    @WrapOperation(method = "close", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/KHRSynchronization2;vkQueueSubmit2KHR(Lorg/lwjgl/vulkan/VkQueue;Lorg/lwjgl/vulkan/VkSubmitInfo2$Buffer;J)I"))
    private int vframegen$submit(VkQueue queue, VkSubmitInfo2.Buffer infos, long fence, Operation<Integer> original) {
        PresentThread.LOCK.lock();
        try {
            return original.call(queue, infos, fence);
        } finally {
            PresentThread.LOCK.unlock();
        }
    }
}
