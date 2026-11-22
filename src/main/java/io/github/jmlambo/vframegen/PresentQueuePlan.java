package io.github.jmlambo.vframegen;

/** VulkanPhysicalDevice (VulkanPhysicalDeviceMixin): the extra queue planned on this device, if any. */
public interface PresentQueuePlan {
    /** Graphics family of the extra queue, or -1 when the family had none to spare. */
    int vframegen$presentFamily();

    int vframegen$presentIndex();
}
