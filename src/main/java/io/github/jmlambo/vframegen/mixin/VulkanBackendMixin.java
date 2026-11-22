package io.github.jmlambo.vframegen.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanPhysicalDevice;
import io.github.jmlambo.vframegen.OpticalFlow;
import io.github.jmlambo.vframegen.PresentQueuePlan;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceOpticalFlowFeaturesNV;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.ArrayList;
import java.util.List;

/**
 * Enables VK_NV_optical_flow on the game's device when VulkanPhysicalDeviceMixin planned an optical-flow
 * queue. The memory comes from the game's own stack frame, so it lives until the game reads it back.
 */
@Mixin(VulkanBackend.class)
public abstract class VulkanBackendMixin {
    @WrapOperation(method = "createDevice(Lcom/mojang/renderpearl/backend/vulkan/init/FeatureSet;Lcom/mojang/renderpearl/backend/vulkan/VulkanPhysicalDevice;)Lorg/lwjgl/vulkan/VkDevice;",
            require = 0, at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VK12;vkCreateDevice(Lorg/lwjgl/vulkan/VkPhysicalDevice;Lorg/lwjgl/vulkan/VkDeviceCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Lorg/lwjgl/PointerBuffer;)I"))
    private static int vframegen$opticalFlow(VkPhysicalDevice physical, VkDeviceCreateInfo info,
            VkAllocationCallbacks allocator, PointerBuffer device, Operation<Integer> original,
            @Local(argsOnly = true) VulkanPhysicalDevice pearl) {
        if (pearl instanceof PresentQueuePlan plan && plan.vframegen$flowFamily() >= 0) {
            MemoryStack stack = MemoryStack.stackGet();
            List<String> names = new ArrayList<>();
            PointerBuffer old = info.ppEnabledExtensionNames();
            if (old != null) for (int i = 0; i < old.remaining(); i++) names.add(old.getStringUTF8(old.position() + i));
            List<String> added = new ArrayList<>();
            for (String ext : plan.vframegen$flowExtensions()) if (!names.contains(ext)) added.add(ext);
            PointerBuffer all = stack.mallocPointer(names.size() + added.size());
            for (String n : names) all.put(stack.UTF8(n));
            for (String n : added) all.put(stack.UTF8(n));
            all.flip();
            info.ppEnabledExtensionNames(all);
            VkPhysicalDeviceOpticalFlowFeaturesNV features = VkPhysicalDeviceOpticalFlowFeaturesNV.calloc(stack)
                    .sType$Default().opticalFlow(true).pNext(info.pNext());
            info.pNext(features.address());
            int result = original.call(physical, info, allocator, device);
            if (result == 0) {
                OpticalFlow.enabled(plan.vframegen$flowFamily(), plan.vframegen$flowIndex(), plan.vframegen$graphicsFamily());
                return result;
            }
            // The driver refused (the feature and extension were checked, so this should not
            // happen): create the game's own device so the game still starts. Directly, not through
            // original again, so whatever else wraps this call does not run twice. The planned extra
            // queue stays in the create info; it is harmless without the extension.
            OpticalFlow.LOGGER.warn("Device creation with optical flow failed ({}), creating it without", result);
            info.pNext(features.pNext());
            info.ppEnabledExtensionNames(old);
            return org.lwjgl.vulkan.VK12.vkCreateDevice(physical, info, allocator, device);
        }
        return original.call(physical, info, allocator, device);
    }
}
