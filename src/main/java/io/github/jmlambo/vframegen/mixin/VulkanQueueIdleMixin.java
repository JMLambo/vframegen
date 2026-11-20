package io.github.jmlambo.vframegen.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.backend.vulkan.VulkanQueue;
import io.github.jmlambo.vframegen.PresentThread;
import org.lwjgl.vulkan.VkQueue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(VulkanQueue.class)
public abstract class VulkanQueueIdleMixin {
    @WrapOperation(method = "waitIdle", at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VK12;vkQueueWaitIdle(Lorg/lwjgl/vulkan/VkQueue;)I"))
    private int vframegen$waitIdle(VkQueue queue, Operation<Integer> original) {
        PresentThread.drain();
        PresentThread.LOCK.lock();
        try {
            return original.call(queue);
        } finally {
            PresentThread.LOCK.unlock();
        }
    }
}
