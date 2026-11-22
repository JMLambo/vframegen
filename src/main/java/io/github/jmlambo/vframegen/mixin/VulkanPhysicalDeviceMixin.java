package io.github.jmlambo.vframegen.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanPhysicalDevice;
import io.github.jmlambo.vframegen.PresentQueuePlan;
import it.unimi.dsi.fastutil.ints.Int2IntArrayMap;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntMaps;
import it.unimi.dsi.fastutil.ints.IntIntPair;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.IntBuffer;

/**
 * One more queue of the graphics family for VFrameGen's present thread (PresentQueue), when the
 * family has one to spare. The game creates and uses only its own.
 */
@Mixin(VulkanPhysicalDevice.class)
public abstract class VulkanPhysicalDeviceMixin implements PresentQueuePlan {
    @org.spongepowered.asm.mixin.Unique
    private int vframegen$family = -1;
    @org.spongepowered.asm.mixin.Unique
    private int vframegen$index = -1;

    @org.spongepowered.asm.mixin.Unique
    private int vframegen$flowFamily = -1;
    @org.spongepowered.asm.mixin.Unique
    private int vframegen$flowIndex = -1;
    @org.spongepowered.asm.mixin.Unique
    private java.util.List<String> vframegen$flowExtensions = java.util.List.of();

    @Override
    public int vframegen$flowFamily() {
        return vframegen$flowFamily;
    }

    @Override
    public int vframegen$flowIndex() {
        return vframegen$flowIndex;
    }

    @Override
    public java.util.List<String> vframegen$flowExtensions() {
        return vframegen$flowExtensions;
    }

    @Override
    public int vframegen$presentFamily() {
        return vframegen$family;
    }

    @Override
    public int vframegen$presentIndex() {
        return vframegen$index;
    }

    @Shadow @Final @Mutable
    private Int2IntMap queueFamilyCreateInfoMap;
    @Shadow @Final
    private IntIntPair graphicsQueueFamilyAndIndex;
    @Shadow @Final
    private VkPhysicalDevice vkPhysicalDevice;

    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void vframegen$presentQueue(VkPhysicalDevice physical, CallbackInfo ci) {
        try {
            vframegen$planFlow();
        } catch (Throwable t) {
            io.github.jmlambo.vframegen.OpticalFlow.LOGGER.warn("Optical flow check failed", t);
        }
        if (graphicsQueueFamilyAndIndex == null) return;
        int family = graphicsQueueFamilyAndIndex.leftInt();
        int used = queueFamilyCreateInfoMap.get(family);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer count = stack.mallocInt(1);
            VK12.vkGetPhysicalDeviceQueueFamilyProperties(vkPhysicalDevice, count, null);
            VkQueueFamilyProperties.Buffer props = VkQueueFamilyProperties.calloc(count.get(0), stack);
            VK12.vkGetPhysicalDeviceQueueFamilyProperties(vkPhysicalDevice, count, props);
            if (family >= props.capacity() || props.get(family).queueCount() <= used) return;
        }
        Int2IntMap map = new Int2IntArrayMap(queueFamilyCreateInfoMap);
        map.put(family, used + 1);
        queueFamilyCreateInfoMap = Int2IntMaps.unmodifiable(map);
        vframegen$family = family;
        vframegen$index = used;
    }

    /** A queue of the optical-flow family when the device has VK_NV_optical_flow (NVIDIA RTX 20 and newer). */
    @org.spongepowered.asm.mixin.Unique
    private void vframegen$planFlow() {
        java.util.Set<String> available = new java.util.HashSet<>();
        // The list is too big for the LWJGL stack (about 260 bytes per extension), so it goes on the heap.
        try (MemoryStack stack = MemoryStack.stackPush();
             org.lwjgl.vulkan.VkExtensionProperties.Buffer exts = vframegen$extensions(stack)) {
            for (org.lwjgl.vulkan.VkExtensionProperties e : exts) available.add(e.extensionNameString());
        }
        String flow = org.lwjgl.vulkan.NVOpticalFlow.VK_NV_OPTICAL_FLOW_EXTENSION_NAME;
        if (!available.contains(flow)) return;
        int family = -1;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer count = stack.mallocInt(1);
            VK12.vkGetPhysicalDeviceQueueFamilyProperties(vkPhysicalDevice, count, null);
            VkQueueFamilyProperties.Buffer props = VkQueueFamilyProperties.calloc(count.get(0), stack);
            VK12.vkGetPhysicalDeviceQueueFamilyProperties(vkPhysicalDevice, count, props);
            for (int i = 0; i < props.capacity(); i++) {
                if ((props.get(i).queueFlags() & org.lwjgl.vulkan.NVOpticalFlow.VK_QUEUE_OPTICAL_FLOW_BIT_NV) != 0
                        && props.get(i).queueCount() > queueFamilyCreateInfoMap.get(i)) {
                    family = i;
                    break;
                }
            }
        }
        if (family < 0) return;
        java.util.List<String> extensions = new java.util.ArrayList<>(java.util.List.of(flow));
        // Dependencies of VK_NV_optical_flow; already core on Vulkan 1.3, listed only when the driver offers them.
        for (String dep : new String[]{"VK_KHR_synchronization2", "VK_KHR_format_feature_flags2"}) {
            if (available.contains(dep)) extensions.add(dep);
        }
        int used = queueFamilyCreateInfoMap.get(family);
        Int2IntMap map = new Int2IntArrayMap(queueFamilyCreateInfoMap);
        map.put(family, used + 1);
        queueFamilyCreateInfoMap = Int2IntMaps.unmodifiable(map);
        vframegen$flowFamily = family;
        vframegen$flowIndex = used;
        vframegen$flowExtensions = java.util.List.copyOf(extensions);
    }

    @org.spongepowered.asm.mixin.Unique
    private org.lwjgl.vulkan.VkExtensionProperties.Buffer vframegen$extensions(MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        VK12.vkEnumerateDeviceExtensionProperties(vkPhysicalDevice, (java.nio.ByteBuffer) null, count, null);
        org.lwjgl.vulkan.VkExtensionProperties.Buffer exts = org.lwjgl.vulkan.VkExtensionProperties.calloc(count.get(0));
        VK12.vkEnumerateDeviceExtensionProperties(vkPhysicalDevice, (java.nio.ByteBuffer) null, count, exts);
        return exts;
    }
}
