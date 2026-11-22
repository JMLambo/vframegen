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
}
