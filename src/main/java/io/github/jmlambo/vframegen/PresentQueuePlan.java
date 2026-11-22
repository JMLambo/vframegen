package io.github.jmlambo.vframegen;

/** VulkanPhysicalDevice (VulkanPhysicalDeviceMixin): the extra queues planned on this device, if any. */
public interface PresentQueuePlan {
    /** Graphics family of the extra queue, or -1 when the family had none to spare. */
    int vframegen$presentFamily();

    int vframegen$presentIndex();

    /** Optical-flow family (VK_NV_optical_flow), or -1 when the device has none. */
    int vframegen$flowFamily();

    int vframegen$flowIndex();

    /** The game's graphics family (the optical-flow images are shared with it). */
    int vframegen$graphicsFamily();

    /** Device extensions to enable for optical flow (empty when unsupported). */
    java.util.List<String> vframegen$flowExtensions();
}
