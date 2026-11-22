package io.github.jmlambo.vframegen;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.NVOpticalFlow;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkOpticalFlowImageFormatInfoNV;
import org.lwjgl.vulkan.VkOpticalFlowImageFormatPropertiesNV;
import org.lwjgl.vulkan.VkPhysicalDeviceOpticalFlowPropertiesNV;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * NVIDIA hardware optical flow (VK_NV_optical_flow, RTX 20 and newer). Stage 1: the extension and a queue
 * of the optical-flow family are enabled at device creation (VulkanPhysicalDeviceMixin, VulkanBackendMixin)
 * and the device's limits are logged. Nothing is run on it yet.
 */
public final class OpticalFlow {
    public static final Logger LOGGER = LoggerFactory.getLogger("VFrameGen/OpticalFlow");

    private static int family = -1;
    private static int index = -1;
    private static VkQueue queue;
    private static int minWidth, minHeight, maxWidth, maxHeight, gridSizes;
    private static List<Integer> inputFormats = List.of();
    private static List<Integer> outputFormats = List.of();

    private OpticalFlow() {
    }

    /** Called once the device was created with the extension enabled. */
    public static void enabled(int family, int index) {
        OpticalFlow.family = family;
        OpticalFlow.index = index;
    }

    /** Whether the GPU has hardware optical flow and it was enabled. */
    public static boolean available() {
        return queue != null;
    }

    public static void init(VkDevice device) {
        if (family < 0 || queue != null) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer p = stack.mallocPointer(1);
            VK12.vkGetDeviceQueue(device, family, index, p);
            VkQueue q = new VkQueue(p.get(0), device);

            VkPhysicalDeviceOpticalFlowPropertiesNV props = VkPhysicalDeviceOpticalFlowPropertiesNV.calloc(stack).sType$Default();
            VkPhysicalDeviceProperties2 props2 = VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(props.address());
            VK11.vkGetPhysicalDeviceProperties2(device.getPhysicalDevice(), props2);
            minWidth = props.minWidth();
            minHeight = props.minHeight();
            maxWidth = props.maxWidth();
            maxHeight = props.maxHeight();
            gridSizes = props.supportedOutputGridSizes();
            inputFormats = formats(device, NVOpticalFlow.VK_OPTICAL_FLOW_USAGE_INPUT_BIT_NV, stack);
            outputFormats = formats(device, NVOpticalFlow.VK_OPTICAL_FLOW_USAGE_OUTPUT_BIT_NV, stack);
            queue = q;
            LOGGER.info("Hardware optical flow: family {} index {}, {}x{} to {}x{}, output grids {}, bidirectional {}, "
                            + "input formats {}, output formats {}", family, index, minWidth, minHeight, maxWidth, maxHeight,
                    grids(gridSizes), props.bidirectionalFlowSupported(), inputFormats, outputFormats);
        } catch (Throwable t) {
            LOGGER.warn("Hardware optical flow unavailable", t);
        }
    }

    private static List<Integer> formats(VkDevice device, int usage, MemoryStack stack) {
        VkOpticalFlowImageFormatInfoNV info = VkOpticalFlowImageFormatInfoNV.calloc(stack).sType$Default().usage(usage);
        IntBuffer count = stack.mallocInt(1);
        NVOpticalFlow.vkGetPhysicalDeviceOpticalFlowImageFormatsNV(device.getPhysicalDevice(), info, count, null);
        VkOpticalFlowImageFormatPropertiesNV.Buffer out = VkOpticalFlowImageFormatPropertiesNV.calloc(count.get(0), stack);
        for (VkOpticalFlowImageFormatPropertiesNV f : out) f.sType$Default();
        NVOpticalFlow.vkGetPhysicalDeviceOpticalFlowImageFormatsNV(device.getPhysicalDevice(), info, count, out);
        List<Integer> list = new ArrayList<>();
        for (int i = 0; i < count.get(0); i++) list.add(out.get(i).format());
        return List.copyOf(list);
    }

    private static String grids(int bits) {
        StringBuilder sb = new StringBuilder();
        for (int s = 1, bit = 1; s <= 8; s *= 2, bit <<= 1) if ((bits & bit) != 0) sb.append(sb.isEmpty() ? "" : ",").append(s).append('x').append(s);
        return sb.toString();
    }
}
