package io.github.jmlambo.vframegen;

import it.unimi.dsi.fastutil.longs.LongList;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandBufferSubmitInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreSubmitInfo;
import org.lwjgl.vulkan.VkSemaphoreTypeCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo2;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

/**
 * Vulkan only: a second queue of the graphics family, owned by the present thread, so a generated
 * frame takes a swapchain image only when it is due.
 *
 * <p>Without it each generated frame was copied into a swapchain image as soon as it was made and
 * held that image while it waited for its time. With a GPU-bound game (a shader pack) the render
 * thread runs a frame ahead, so the generated frames of two real frames waited at once, the
 * swapchain (8 images at most on NVIDIA) ran out, and the last generated frames of every pair were
 * skipped. Now a generated frame waits in a buffer of its own (a "pending" texture). When it is due,
 * the present thread acquires an image, blits the buffer into it on this queue and presents it, so
 * an image is held for about a millisecond. The blit cannot go on the game's queue: there it would
 * wait behind the next real frame's work.
 *
 * <p>Used only when the graphics family has a queue to spare (NVIDIA has 16); otherwise generated
 * frames go through the swapchain as before.
 */
public final class PresentQueue {
    /**
     * Pending buffers. FrameGenerator keeps the present queue at most one pair (plus one) deep, so
     * at the highest (adaptive) multiplier one pair's generated frames, plus a couple whose blit is
     * still finishing, fit; more were never used once the queue was capped.
     */
    public static final int SLOTS = FrameGenConfig.ADAPTIVE_MAX_MULTIPLIER + 2;

    private static final int VK_SUCCESS = 0;
    private static final int VK_TIMEOUT = 2;
    private static final int VK_SUBOPTIMAL_KHR = 1000001003;
    private static final int VK_ERROR_OUT_OF_DATE_KHR = -1000001004;
    private static final int LAYOUT_GENERAL = 1;
    private static final int LAYOUT_TRANSFER_DST = 7;
    private static final int LAYOUT_PRESENT_SRC = 1000001002;
    private static final long STAGE_TRANSFER = 0x1000L;       // VK_PIPELINE_STAGE_2_TRANSFER_BIT
    private static final long STAGE_ALL = 0x10000L;           // VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT
    private static final long ACCESS_TRANSFER_WRITE = 0x1000L;

    // Chosen when the physical device is set up (VulkanPhysicalDeviceMixin), before the device.
    private static int plannedFamily = -1;
    private static int plannedIndex = -1;

    private static VkDevice device;
    private static VkQueue queue;
    private static long commandPool;
    private static long timeline;          // signalled by the game's queue when a pending frame is drawn
    private static long timelineValue;     // render thread only

    private static final long[] slotImage = new long[SLOTS];
    private static int slotWidth, slotHeight; // size of the pending buffers
    private static final VkCommandBuffer[] slotCommands = new VkCommandBuffer[SLOTS];
    private static final long[] slotFence = new long[SLOTS];
    private static final long[] slotAcquire = new long[SLOTS];
    // A slot is busy from the frame drawn into it until its blit has finished on this queue.
    private static final boolean[] slotBusy = new boolean[SLOTS];
    // Written by the present thread inside PresentThread.LOCK: atomic, so it never needs this class's
    // monitor there (waitIdle takes the monitor, then LOCK; the opposite order deadlocked).
    private static final java.util.concurrent.atomic.AtomicIntegerArray slotSubmitted = new java.util.concurrent.atomic.AtomicIntegerArray(SLOTS);
    private static int nextSlot;

    // The window's swapchain (VulkanSurfaceMixin), and one present semaphore per image of it.
    private static long swapchain;
    private static long[] images = new long[0];
    private static long[] presentSemaphores = new long[0];
    private static int swapchainWidth, swapchainHeight;

    private PresentQueue() {
    }

    /** VulkanPhysicalDeviceMixin: the graphics family has a queue beyond the game's at index. */
    public static void plan(int family, int index) {
        plannedFamily = family;
        plannedIndex = index;
    }

    /** VulkanDeviceMixin: the device exists; take the queue planned for it. */
    public static synchronized void init(VkDevice dev) {
        if (plannedFamily < 0) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer q = stack.mallocPointer(1);
            VK12.vkGetDeviceQueue(dev, plannedFamily, plannedIndex, q);
            if (q.get(0) == 0) return;
            VkCommandPoolCreateInfo pool = VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                    .flags(0x2) // VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT
                    .queueFamilyIndex(plannedFamily);
            LongBuffer handle = stack.mallocLong(1);
            if (VK12.vkCreateCommandPool(dev, pool, null, handle) != VK_SUCCESS) return;
            commandPool = handle.get(0);
            VkSemaphoreTypeCreateInfo type = VkSemaphoreTypeCreateInfo.calloc(stack).sType$Default()
                    .semaphoreType(1) // VK_SEMAPHORE_TYPE_TIMELINE
                    .initialValue(0);
            VkSemaphoreCreateInfo timelineInfo = VkSemaphoreCreateInfo.calloc(stack).sType$Default().pNext(type.address());
            if (VK12.vkCreateSemaphore(dev, timelineInfo, null, handle) != VK_SUCCESS) return;
            timeline = handle.get(0);
            VkCommandBufferAllocateInfo alloc = VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                    .commandPool(commandPool).level(0).commandBufferCount(SLOTS);
            PointerBuffer buffers = stack.mallocPointer(SLOTS);
            if (VK12.vkAllocateCommandBuffers(dev, alloc, buffers) != VK_SUCCESS) return;
            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack).sType$Default().flags(0x1); // signalled
            VkSemaphoreCreateInfo binary = VkSemaphoreCreateInfo.calloc(stack).sType$Default();
            for (int i = 0; i < SLOTS; i++) {
                slotCommands[i] = new VkCommandBuffer(buffers.get(i), dev);
                if (VK12.vkCreateFence(dev, fenceInfo, null, handle) != VK_SUCCESS) return;
                slotFence[i] = handle.get(0);
                if (VK12.vkCreateSemaphore(dev, binary, null, handle) != VK_SUCCESS) return;
                slotAcquire[i] = handle.get(0);
            }
            device = dev;
            queue = new VkQueue(q.get(0), dev);
            VFrameGen.LOGGER.info("Present queue: family {} index {} (generated frames take a swapchain image only when due)",
                    plannedFamily, plannedIndex);
        } catch (RuntimeException e) {
            VFrameGen.LOGGER.warn("Present queue unavailable", e);
        }
    }

    /**
     * Whether pending buffers are worth making: the device has the queue, the present thread is on
     * and the swapchain is plain 8-bit sRGB (otherwise ready() is false and they would go unused).
     */
    public static boolean available() {
        return queue != null && PresentThread.enabled() && plainFormat;
    }

    /** Whether generated frames can wait in pending buffers (a queue, a swapchain, buffers). */
    public static synchronized boolean ready() {
        if (queue == null || swapchain == 0 || images.length == 0 || !plainFormat) return false;
        // The buffers are remade for a new window size a little after the swapchain is.
        if (slotWidth != swapchainWidth || slotHeight != swapchainHeight) return false;
        for (long image : slotImage) if (image == 0) return false;
        return true;
    }

    /** FrameGenerator: the pending buffers (their VkImage handles, all w x h), or null when released. */
    public static synchronized void setSlotImages(long[] vkImages, int w, int h) {
        waitIdle();
        slotWidth = w;
        slotHeight = h;
        for (int i = 0; i < SLOTS; i++) {
            slotImage[i] = vkImages == null ? 0 : vkImages[i];
            slotBusy[i] = false;
            slotSubmitted.set(i, 0);
        }
    }

    // VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_B8G8R8A8_UNORM: the game's own (SDR) swapchain formats.
    private static final int FORMAT_RGBA8 = 37, FORMAT_BGRA8 = 44;
    private static volatile boolean plainFormat;
    private static int loggedFormat = -1;

    /** VulkanSurfaceMixin, after the swapchain was (re)created. */
    public static synchronized void onSwapchain(long handle, LongList swapchainImages, int width, int height, int format, int colorSpace) {
        if (device == null) return;
        // Only the plain 8-bit formats the game itself picks. Another mod's swapchain (an HDR mod's
        // 10-bit or floating-point one, with its own colour encoding) gets its frames converted by
        // that mod; a raw blit of ours into it showed every generated frame in wildly wrong colours
        // (flashing). Then generated frames go through the game's own path, as before.
        // Read from the create call itself (an HDR mod changes them there, not in the game's field);
        // unknown (-1) counts as not plain. Colour space 0: VK_COLOR_SPACE_SRGB_NONLINEAR_KHR.
        plainFormat = (format == FORMAT_RGBA8 || format == FORMAT_BGRA8) && colorSpace == 0;
        int key = format * 1009 + colorSpace;
        if (key != loggedFormat) {
            loggedFormat = key;
            if (!plainFormat) {
                VFrameGen.LOGGER.info("Present queue off: the swapchain has format {} colour space {} (not plain 8-bit sRGB, likely an HDR mod)",
                        format, colorSpace);
            }
        }
        waitIdle();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (long s : presentSemaphores) VK12.vkDestroySemaphore(device, s, null);
            images = swapchainImages.toLongArray();
            presentSemaphores = new long[images.length];
            VkSemaphoreCreateInfo binary = VkSemaphoreCreateInfo.calloc(stack).sType$Default();
            LongBuffer h = stack.mallocLong(1);
            for (int i = 0; i < images.length; i++) {
                VK12.vkCreateSemaphore(device, binary, null, h);
                presentSemaphores[i] = h.get(0);
            }
        }
        swapchain = handle;
        swapchainWidth = width;
        swapchainHeight = height;
    }

    /** Before the swapchain is recreated or destroyed: nothing of this queue may still use it. */
    public static synchronized void waitIdle() {
        if (queue == null) return;
        PresentThread.LOCK.lock();
        try {
            VK12.vkQueueWaitIdle(queue);
        } finally {
            PresentThread.LOCK.unlock();
        }
        for (int i = 0; i < SLOTS; i++) {
            if (slotSubmitted.get(i) != 0) {
                slotSubmitted.set(i, 0);
                slotBusy[i] = false;
            }
        }
    }

    /**
     * Render thread: a pending buffer to draw the next generated frame into, or -1 when all are
     * still waiting to be shown (then the frame is skipped, as when no image was free).
     */
    public static synchronized int takeSlot() {
        for (int n = 0; n < SLOTS; n++) {
            int i = (nextSlot + n) % SLOTS;
            if (slotBusy[i] && slotSubmitted.get(i) != 0 && VK12.vkGetFenceStatus(device, slotFence[i]) == VK_SUCCESS) {
                slotBusy[i] = false;
                slotSubmitted.set(i, 0);
            }
            if (!slotBusy[i]) {
                slotBusy[i] = true;
                nextSlot = (i + 1) % SLOTS;
                return i;
            }
        }
        return -1;
    }

    /** Present thread: a pending frame was dropped (never blitted); its buffer is free again. */
    public static synchronized void release(int slot) {
        if (slot >= 0 && slotSubmitted.get(slot) == 0) slotBusy[slot] = false;
    }

    public static long timeline() {
        return timeline;
    }

    /** Render thread: the timeline value the next pending frame's drawing signals. */
    public static long nextTimelineValue() {
        return ++timelineValue;
    }

    /**
     * Present thread, when the frame is due: acquire a swapchain image, blit the pending buffer into
     * it (flipped, as the game's own blit does) once the game's queue has drawn it, and present it.
     * Returns the VkResult: VK_SUCCESS (or SUBOPTIMAL) when shown, VK_TIMEOUT when no image was free.
     */
    static int show(int slot, long drawnValue, VkQueue gameQueue) {
        long sc;
        long[] imgs;
        long[] presentSems;
        int w, h, srcW, srcH;
        synchronized (PresentQueue.class) {
            sc = swapchain;
            imgs = images;
            presentSems = presentSemaphores;
            // Like the game's own blit: the smaller of the two, so neither image is read or written
            // past its edge while one of them has a new size (the window moved to another screen)
            // and the other does not yet.
            srcW = slotWidth;
            srcH = slotHeight;
            w = Math.min(swapchainWidth, srcW);
            h = Math.min(swapchainHeight, srcH);
        }
        if (sc == 0 || slotImage[slot] == 0) return VK_ERROR_OUT_OF_DATE_KHR;
        PresentThread.LOCK.lock();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // The fence of this slot's last blit was waited for before the slot was handed out.
            VK12.vkResetFences(device, slotFence[slot]);
            slotSubmitted.set(slot, 1); // every path below submits something that signals the fence
            IntBuffer index = stack.mallocInt(1);
            int acquired = KHRSwapchain.vkAcquireNextImageKHR(device, sc, 4_000_000L, slotAcquire[slot], 0L, index);
            if (acquired != VK_SUCCESS && acquired != VK_SUBOPTIMAL_KHR) {
                // No image (none free within 4 ms, or the swapchain is out of date): the frame is
                // dropped; signal the fence with an empty submit so the slot is freed.
                VK12.vkQueueSubmit(queue, (org.lwjgl.vulkan.VkSubmitInfo.Buffer) null, slotFence[slot]);
                return acquired;
            }
            int i = index.get(0);
            long image = imgs[i];
            VkCommandBuffer cb = slotCommands[slot];
            VK12.vkResetCommandBuffer(cb, 0);
            VK12.vkBeginCommandBuffer(cb, VkCommandBufferBeginInfo.calloc(stack).sType$Default().flags(0x1));
            barrier(cb, stack, image, 0, LAYOUT_TRANSFER_DST, 0L, 0L, STAGE_TRANSFER, ACCESS_TRANSFER_WRITE);
            VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
            region.srcSubresource().aspectMask(1).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.srcOffsets(0).set(0, 0, 0);
            region.srcOffsets(1).set(w, h, 1);
            region.dstSubresource().aspectMask(1).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.dstOffsets(0).set(0, h, 0);
            region.dstOffsets(1).set(w, 0, 1);
            VK12.vkCmdBlitImage(cb, slotImage[slot], LAYOUT_GENERAL, image, LAYOUT_TRANSFER_DST, region, 0);
            barrier(cb, stack, image, LAYOUT_TRANSFER_DST, LAYOUT_PRESENT_SRC, STAGE_TRANSFER, ACCESS_TRANSFER_WRITE, STAGE_ALL, 0L);
            VK12.vkEndCommandBuffer(cb);

            VkSemaphoreSubmitInfo.Buffer waits = VkSemaphoreSubmitInfo.calloc(2, stack);
            // Waited at every stage (as the game's own blit does): the layout transition of the
            // image is in the first barrier, which has no source stage, so a wait at the transfer
            // stage alone let it run while the display was still reading the image (black frames).
            waits.get(0).sType$Default().semaphore(slotAcquire[slot]).stageMask(STAGE_ALL);
            waits.get(1).sType$Default().semaphore(timeline).value(drawnValue).stageMask(STAGE_ALL);
            VkSemaphoreSubmitInfo.Buffer signal = VkSemaphoreSubmitInfo.calloc(1, stack);
            signal.get(0).sType$Default().semaphore(presentSems[i]).stageMask(STAGE_ALL);
            VkCommandBufferSubmitInfo.Buffer cmds = VkCommandBufferSubmitInfo.calloc(1, stack);
            cmds.get(0).sType$Default().commandBuffer(cb);
            VkSubmitInfo2.Buffer submit = VkSubmitInfo2.calloc(1, stack);
            submit.get(0).sType$Default().pWaitSemaphoreInfos(waits).pCommandBufferInfos(cmds).pSignalSemaphoreInfos(signal);
            int submitted = KHRSynchronization2.vkQueueSubmit2KHR(queue, submit, slotFence[slot]);
            if (submitted != VK_SUCCESS) return submitted;

            VkPresentInfoKHR present = VkPresentInfoKHR.calloc(stack).sType$Default()
                    .pWaitSemaphores(stack.longs(presentSems[i]))
                    .swapchainCount(1)
                    .pSwapchains(stack.longs(sc))
                    .pImageIndices(stack.ints(i));
            // Presented on the game's queue (the present only waits for the blit's semaphore): on a
            // laptop panel driven by the integrated GPU (PRIME), frames presented from this second
            // queue reached the screen black.
            return KHRSwapchain.vkQueuePresentKHR(gameQueue != null ? gameQueue : queue, present);
        } finally {
            PresentThread.LOCK.unlock();
        }
    }

    private static void barrier(VkCommandBuffer cb, MemoryStack stack, long image, int oldLayout, int newLayout,
                                long srcStage, long srcAccess, long dstStage, long dstAccess) {
        VkImageMemoryBarrier2.Buffer b = VkImageMemoryBarrier2.calloc(1, stack).sType$Default();
        b.srcStageMask(srcStage).srcAccessMask(srcAccess).dstStageMask(dstStage).dstAccessMask(dstAccess)
                .oldLayout(oldLayout).newLayout(newLayout).srcQueueFamilyIndex(-1).dstQueueFamilyIndex(-1).image(image);
        b.subresourceRange().aspectMask(1).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
        KHRSynchronization2.vkCmdPipelineBarrier2KHR(cb, VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(b));
    }
}
