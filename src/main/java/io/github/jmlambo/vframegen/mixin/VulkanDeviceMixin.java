package io.github.jmlambo.vframegen.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import io.github.jmlambo.vframegen.PresentQueue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Takes VFrameGen's present queue (see VulkanPhysicalDeviceMixin) once the device exists. */
@Mixin(VulkanDevice.class)
public abstract class VulkanDeviceMixin {
    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void vframegen$presentQueue(com.mojang.renderpearl.backend.vulkan.VulkanInstance instance,
            com.mojang.renderpearl.backend.vulkan.VulkanPhysicalDevice physical,
            com.mojang.renderpearl.backend.vulkan.init.FeatureSet features, org.lwjgl.vulkan.VkDevice vkDevice,
            long vma, com.mojang.renderpearl.backend.vulkan.checkpoints.CheckpointExtension checkpoints, CallbackInfo ci) {
        if (physical instanceof io.github.jmlambo.vframegen.PresentQueuePlan plan && plan.vframegen$presentFamily() >= 0) {
            PresentQueue.plan(plan.vframegen$presentFamily(), plan.vframegen$presentIndex());
            PresentQueue.init(vkDevice);
        }
    }
}
