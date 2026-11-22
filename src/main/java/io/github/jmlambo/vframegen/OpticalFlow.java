package io.github.jmlambo.vframegen;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.NVOpticalFlow;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandBufferSubmitInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkOpticalFlowExecuteInfoNV;
import org.lwjgl.vulkan.VkOpticalFlowImageFormatInfoNV;
import org.lwjgl.vulkan.VkOpticalFlowImageFormatPropertiesNV;
import org.lwjgl.vulkan.VkOpticalFlowSessionCreateInfoNV;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceOpticalFlowPropertiesNV;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreSubmitInfo;
import org.lwjgl.vulkan.VkSemaphoreTypeCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreWaitInfo;
import org.lwjgl.vulkan.VkSubmitInfo2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * NVIDIA hardware optical flow (VK_NV_optical_flow, RTX 20 and newer).
 *
 * <p>Stage 1: the extension and a queue of the optical-flow family are enabled at device creation
 * (VulkanPhysicalDeviceMixin, VulkanBackendMixin). Stage 2 (debugOpticalFlow): every real frame's
 * world image is copied into one of two input images, the hardware measures the motion from the
 * previous frame to this one, and the result is copied into a game texture ({@link #result()}) that
 * F8 saves. Stage 3 (opticalFlow): generated frames between those two frames add the motion the
 * flow saw and depth reprojection could not (reproject.fsh, flowResidual).
 *
 * <p>Order on the GPU, all on one timeline semaphore: the game's queue copies the frame in and
 * signals; the optical-flow queue waits, measures and signals; the game's queue waits and copies the
 * flow out. Render thread only.
 */
public final class OpticalFlow {
    public static final Logger LOGGER = LoggerFactory.getLogger("VFrameGen/OpticalFlow");

    private static final int FORMAT_INPUT = 44;            // VK_FORMAT_B8G8R8A8_UNORM
    private static final int GRID = 4;                     // one vector per 4x4 pixels
    private static final int LAYOUT_UNDEFINED = 0;
    private static final int LAYOUT_GENERAL = 1;
    private static final long STAGE_ALL = 0x10000L;        // VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT
    private static final long ACCESS_READ_WRITE = 0x8000L | 0x10000L; // MEMORY_READ | MEMORY_WRITE

    private static int family = -1;
    private static int index = -1;
    private static int graphicsFamily = -1;
    private static VkDevice device;
    private static VkQueue queue;
    private static long commandPool;
    private static VkCommandBuffer[] commands;
    private static long[] commandDone; // per command buffer: the timeline value its last measurement signals
    private static int nextCommand;
    private static long timeline;
    private static long timelineValue;
    private static int minWidth, minHeight, maxWidth, maxHeight, gridSizes;
    private static List<Integer> inputFormats = List.of();
    private static List<Integer> outputFormats = List.of();
    private static boolean failed;

    // Sized to the world image: two inputs (this frame and the last), the flow vectors, and the
    // game texture the flow is copied into.
    private static int width, height;
    private static final long[] input = new long[2];
    private static final long[] inputMemory = new long[2];
    private static long flow, flowMemory;          // per pixel of the new frame: where it was in the old one
    private static long backFlow, backFlowMemory;  // per pixel of the old frame: where it went in the new one
    private static final long[] session = new long[2]; // session[i]: input[i] against input[1 - i]
    /** The flow of one pair of real frames, copied into game textures (RG16 snorm). */
    public static final class Pair {
        public GpuTexture forward;  // at the newer frame, towards the older (FlowB)
        public GpuTexture backward; // at the older frame, towards the newer (FlowA)
        public com.mojang.renderpearl.api.textures.GpuTextureView forwardView, backwardView;
        GpuTexture older, newer;    // the world images it was measured between
        boolean valid;
        boolean copied;             // copied out of the optical-flow images yet
        long measuredValue;         // timeline value the measurement signals
    }

    // Generated frames of a pair may still be drawn after the next real frame was measured.
    private static final Pair[] pairs = {new Pair(), new Pair(), new Pair()};
    private static int nextPair;
    private static Pair pending; // measured, not copied out yet (the next measurement overwrites the images)
    private static GpuTexture lastWorld;
    private static boolean layoutsSet;
    private static int current = -1; // input of the last frame, or -1 when there is none yet

    private OpticalFlow() {
    }

    /** Called once the device was created with the extension enabled. */
    public static void enabled(int family, int index, int graphicsFamily) {
        OpticalFlow.family = family;
        OpticalFlow.index = index;
        OpticalFlow.graphicsFamily = graphicsFamily;
    }

    /** Whether the GPU has hardware optical flow and it was enabled. */
    public static boolean available() {
        return queue != null && !failed;
    }

    /** The flow measured between these two world images (FrameSlot.noHand), or null. */
    public static Pair forPair(GpuTexture older, GpuTexture newer) {
        if (!available()) return null;
        for (Pair p : pairs) {
            if (p.valid && p.older == older && p.newer == newer) {
                copyOut(p);
                return p.copied ? p : null;
            }
        }
        return null;
    }

    /** RG16 snorm to pixels: the flow is in 1/32 pixel (S10.5). */
    public static final float SNORM_TO_PIXELS = 32767f / 32f;

    public static void init(VkDevice dev) {
        if (family < 0 || queue != null) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer p = stack.mallocPointer(1);
            VK12.vkGetDeviceQueue(dev, family, index, p);
            VkQueue q = new VkQueue(p.get(0), dev);

            VkPhysicalDeviceOpticalFlowPropertiesNV props = VkPhysicalDeviceOpticalFlowPropertiesNV.calloc(stack).sType$Default();
            VkPhysicalDeviceProperties2 props2 = VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(props.address());
            VK11.vkGetPhysicalDeviceProperties2(dev.getPhysicalDevice(), props2);
            minWidth = props.minWidth();
            minHeight = props.minHeight();
            maxWidth = props.maxWidth();
            maxHeight = props.maxHeight();
            gridSizes = props.supportedOutputGridSizes();
            inputFormats = formats(dev, NVOpticalFlow.VK_OPTICAL_FLOW_USAGE_INPUT_BIT_NV, stack);
            outputFormats = formats(dev, NVOpticalFlow.VK_OPTICAL_FLOW_USAGE_OUTPUT_BIT_NV, stack);
            LOGGER.info("Hardware optical flow: family {} index {}, {}x{} to {}x{}, output grids {}, bidirectional {}, "
                            + "input formats {}, output formats {}", family, index, minWidth, minHeight, maxWidth, maxHeight,
                    grids(gridSizes), props.bidirectionalFlowSupported(), inputFormats, outputFormats);
            // Checked before anything is allocated: without them the option is not offered at all.
            if (!inputFormats.contains(FORMAT_INPUT) || !outputFormats.contains(NVOpticalFlow.VK_FORMAT_R16G16_S10_5_NV)
                    || (gridSizes & NVOpticalFlow.VK_OPTICAL_FLOW_GRID_SIZE_4X4_BIT_NV) == 0
                    || !props.bidirectionalFlowSupported()) {
                LOGGER.info("Hardware optical flow: formats, grid size or both-direction flow not supported, not used");
                return;
            }

            LongBuffer handle = stack.mallocLong(1);
            VkCommandPoolCreateInfo pool = VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                    .flags(0x2).queueFamilyIndex(family); // RESET_COMMAND_BUFFER
            check(VK12.vkCreateCommandPool(dev, pool, null, handle), "command pool");
            commandPool = handle.get(0);
            // A few in flight at once; each is reused only after the timeline passed its submit.
            commands = new VkCommandBuffer[4];
            PointerBuffer buffers = stack.mallocPointer(commands.length);
            check(VK12.vkAllocateCommandBuffers(dev, VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                    .commandPool(commandPool).level(0).commandBufferCount(commands.length), buffers), "command buffers");
            for (int i = 0; i < commands.length; i++) commands[i] = new VkCommandBuffer(buffers.get(i), dev);
            commandDone = new long[commands.length];
            VkSemaphoreTypeCreateInfo type = VkSemaphoreTypeCreateInfo.calloc(stack).sType$Default().semaphoreType(1).initialValue(0);
            check(VK12.vkCreateSemaphore(dev, VkSemaphoreCreateInfo.calloc(stack).sType$Default().pNext(type.address()), null, handle), "timeline");
            timeline = handle.get(0);

            device = dev;
            queue = q;
        } catch (Throwable t) {
            LOGGER.warn("Hardware optical flow unavailable", t);
            queue = null;
        }
    }

    /**
     * Render thread, once a real frame's world image (without the hand) is captured: measure the
     * motion from the previous frame to this one.
     */
    public static void measure(GpuTexture world) {
        if (!available() || !(world instanceof VulkanGpuTexture vkWorld)) return;
        int w = world.getWidth(0), h = world.getHeight(0);
        if (w < minWidth || h < minHeight || w > maxWidth || h > maxHeight) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (w != width || h != height || flow == 0) {
                if (!destroyImages()) {
                    LOGGER.warn("Hardware optical flow: the GPU did not finish within a second, turned off");
                    failed = true;
                    return;
                }
                createImages(w, h, stack);
            }
            // The images are about to be measured into again: keep what is still in them.
            if (pending != null) copyOut(pending);
            int next = current < 0 ? 0 : 1 - current;

            // Game queue: copy the frame in (a blit converts the game's RGBA to the BGRA the hardware takes).
            var encoder = RenderSystem.getDevice().createCommandEncoder();
            VulkanCommandEncoder vk = (VulkanCommandEncoder) ((FrontendCommandEncoder) encoder).backend();
            VkCommandBuffer cb = vk.allocateAndBeginTransientCommandBuffer();
            if (!layoutsSet) {
                barrier(cb, stack, input[0], LAYOUT_UNDEFINED);
                barrier(cb, stack, input[1], LAYOUT_UNDEFINED);
                barrier(cb, stack, flow, LAYOUT_UNDEFINED);
                barrier(cb, stack, backFlow, LAYOUT_UNDEFINED);
                layoutsSet = true;
            }
            VulkanCommandEncoder.memoryBarrier(cb, stack);
            VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
            region.srcSubresource().aspectMask(1).layerCount(1);
            region.srcOffsets(1).set(w, h, 1);
            region.dstSubresource().aspectMask(1).layerCount(1);
            region.dstOffsets(1).set(w, h, 1);
            VK12.vkCmdBlitImage(cb, vkWorld.vkImage(), LAYOUT_GENERAL, input[next], LAYOUT_GENERAL, region, 0); // NEAREST
            VulkanCommandEncoder.memoryBarrier(cb, stack);
            VK12.vkEndCommandBuffer(cb);
            vk.execute(cb);
            long copied = ++timelineValue;
            vk.signalSemaphore(timeline, copied, STAGE_ALL);
            // This image now holds a new frame: flows measured with its old contents no longer apply.
            for (Pair p : pairs) if (p.older == world || p.newer == world) p.valid = false;
            GpuTexture older = lastWorld;
            lastWorld = world;
            // Not submitted here (a submit costs the render thread about a millisecond): the copy goes
            // with the game's next submission. The optical-flow queue's wait may be queued before
            // that signal (timeline semaphores allow it).
            if (current < 0) {
                current = next;
                return;
            }

            // Optical-flow queue: measure input[next] against the previous frame.
            int slot = nextCommand;
            VkCommandBuffer of = commands[slot];
            // Reused only once its last measurement has finished (a pending buffer can't be reset).
            if (commandDone[slot] > 0 && !waitTimeline(commandDone[slot], stack)) {
                LOGGER.warn("Hardware optical flow: the GPU did not finish a measurement within a second, turned off");
                failed = true;
                return;
            }
            nextCommand = (slot + 1) % commands.length;
            VK12.vkResetCommandBuffer(of, 0);
            VK12.vkBeginCommandBuffer(of, VkCommandBufferBeginInfo.calloc(stack).sType$Default().flags(0x1));
            VulkanCommandEncoder.memoryBarrier(of, stack);
            NVOpticalFlow.vkCmdOpticalFlowExecuteNV(of, session[next], VkOpticalFlowExecuteInfoNV.calloc(stack).sType$Default());
            VulkanCommandEncoder.memoryBarrier(of, stack);
            VK12.vkEndCommandBuffer(of);
            long measured = ++timelineValue;
            VkSemaphoreSubmitInfo.Buffer wait = VkSemaphoreSubmitInfo.calloc(1, stack);
            wait.get(0).sType$Default().semaphore(timeline).value(copied).stageMask(STAGE_ALL);
            VkSemaphoreSubmitInfo.Buffer signal = VkSemaphoreSubmitInfo.calloc(1, stack);
            signal.get(0).sType$Default().semaphore(timeline).value(measured).stageMask(STAGE_ALL);
            VkCommandBufferSubmitInfo.Buffer cmds = VkCommandBufferSubmitInfo.calloc(1, stack);
            cmds.get(0).sType$Default().commandBuffer(of);
            VkSubmitInfo2.Buffer submit = VkSubmitInfo2.calloc(1, stack);
            submit.get(0).sType$Default().pWaitSemaphoreInfos(wait).pCommandBufferInfos(cmds).pSignalSemaphoreInfos(signal);
            // Every queue submit goes under the present thread's lock (repository rule).
            PresentThread.LOCK.lock();
            try {
                check(KHRSynchronization2.vkQueueSubmit2KHR(queue, submit, 0L), "optical flow submit");
            } finally {
                PresentThread.LOCK.unlock();
            }
            commandDone[slot] = measured;
            current = next;

            // Game queue: wait for the measurement and copy the flows into the game's textures.
            Pair pair = pairs[nextPair];
            pair.valid = false;
            nextPair = (nextPair + 1) % pairs.length;
            int gw = (w + GRID - 1) / GRID, gh = (h + GRID - 1) / GRID;
            if (pair.forward == null || pair.forward.getWidth(0) != gw || pair.forward.getHeight(0) != gh) {
                if (pair.forward != null) {
                    pair.forwardView.close();
                    pair.backwardView.close();
                    pair.forward.close();
                    pair.backward.close();
                }
                pair.forward = flowTexture("forward", gw, gh);
                pair.backward = flowTexture("backward", gw, gh);
                pair.forwardView = RenderSystem.getDevice().createTextureView(pair.forward);
                pair.backwardView = RenderSystem.getDevice().createTextureView(pair.backward);
            }
            // Copied out only when a generated frame first needs it (copyOut): waiting here put the
            // whole measurement on the real frame's GPU time.
            pair.measuredValue = measured;
            pair.copied = false;
            pending = pair;
            pair.older = older;
            pair.newer = world;
            pair.valid = older != null;
        } catch (Throwable t) {
            LOGGER.warn("Hardware optical flow failed, turned off", t);
            failed = true;
        }
    }

    /** Game queue: once the measurement is done, copy the flows into the pair's game textures. */
    private static void copyOut(Pair pair) {
        if (pair.copied || pair != pending || failed) return;
        pending = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int gw = pair.forward.getWidth(0), gh = pair.forward.getHeight(0);
            var out = RenderSystem.getDevice().createCommandEncoder();
            VulkanCommandEncoder vkOut = (VulkanCommandEncoder) ((FrontendCommandEncoder) out).backend();
            vkOut.waitSemaphore(timeline, pair.measuredValue, STAGE_ALL);
            VkCommandBuffer copy = vkOut.allocateAndBeginTransientCommandBuffer();
            VulkanCommandEncoder.memoryBarrier(copy, stack);
            VkImageCopy.Buffer c = VkImageCopy.calloc(1, stack);
            c.srcSubresource().aspectMask(1).layerCount(1);
            c.dstSubresource().aspectMask(1).layerCount(1);
            c.extent().set(gw, gh, 1);
            VK12.vkCmdCopyImage(copy, flow, LAYOUT_GENERAL, ((VulkanGpuTexture) pair.forward).vkImage(), LAYOUT_GENERAL, c);
            VK12.vkCmdCopyImage(copy, backFlow, LAYOUT_GENERAL, ((VulkanGpuTexture) pair.backward).vkImage(), LAYOUT_GENERAL, c);
            VulkanCommandEncoder.memoryBarrier(copy, stack);
            VK12.vkEndCommandBuffer(copy);
            vkOut.execute(copy);
            vkOut.signalSemaphore(timeline, ++timelineValue, STAGE_ALL);
            // Goes with the generated frame's own submit.
            pair.copied = true;
        } catch (Throwable t) {
            LOGGER.warn("Hardware optical flow failed, turned off", t);
            failed = true;
        }
    }

    private static void createImages(int w, int h, MemoryStack stack) {
        width = w;
        height = h;
        int gw = (w + GRID - 1) / GRID, gh = (h + GRID - 1) / GRID;
        for (int i = 0; i < 2; i++) {
            input[i] = image(w, h, FORMAT_INPUT, 0x2 | 0x4, NVOpticalFlow.VK_OPTICAL_FLOW_USAGE_INPUT_BIT_NV, stack); // TRANSFER_DST | SAMPLED
            inputMemory[i] = bind(input[i], stack);
        }
        flow = image(gw, gh, NVOpticalFlow.VK_FORMAT_R16G16_S10_5_NV, 0x1 | 0x8, // TRANSFER_SRC | STORAGE
                NVOpticalFlow.VK_OPTICAL_FLOW_USAGE_OUTPUT_BIT_NV, stack);
        flowMemory = bind(flow, stack);
        backFlow = image(gw, gh, NVOpticalFlow.VK_FORMAT_R16G16_S10_5_NV, 0x1 | 0x8,
                NVOpticalFlow.VK_OPTICAL_FLOW_USAGE_OUTPUT_BIT_NV, stack);
        backFlowMemory = bind(backFlow, stack);
        for (int i = 0; i < 2; i++) {
            VkOpticalFlowSessionCreateInfoNV info = VkOpticalFlowSessionCreateInfoNV.calloc(stack).sType$Default()
                    .width(w).height(h)
                    .imageFormat(FORMAT_INPUT)
                    .flowVectorFormat(NVOpticalFlow.VK_FORMAT_R16G16_S10_5_NV)
                    .outputGridSize(NVOpticalFlow.VK_OPTICAL_FLOW_GRID_SIZE_4X4_BIT_NV)
                    .performanceLevel(NVOpticalFlow.VK_OPTICAL_FLOW_PERFORMANCE_LEVEL_FAST_NV)
                    .flags(NVOpticalFlow.VK_OPTICAL_FLOW_SESSION_CREATE_BOTH_DIRECTIONS_BIT_NV);
            LongBuffer handle = stack.mallocLong(1);
            check(NVOpticalFlow.vkCreateOpticalFlowSessionNV(device, info, null, handle), "session");
            session[i] = handle.get(0);
            check(NVOpticalFlow.vkBindOpticalFlowSessionImageNV(device, session[i],
                    NVOpticalFlow.VK_OPTICAL_FLOW_SESSION_BINDING_POINT_INPUT_NV, view(input[i], FORMAT_INPUT, stack), LAYOUT_GENERAL), "bind input");
            check(NVOpticalFlow.vkBindOpticalFlowSessionImageNV(device, session[i],
                    NVOpticalFlow.VK_OPTICAL_FLOW_SESSION_BINDING_POINT_REFERENCE_NV, view(input[1 - i], FORMAT_INPUT, stack), LAYOUT_GENERAL), "bind reference");
            check(NVOpticalFlow.vkBindOpticalFlowSessionImageNV(device, session[i],
                    NVOpticalFlow.VK_OPTICAL_FLOW_SESSION_BINDING_POINT_FLOW_VECTOR_NV,
                    view(flow, NVOpticalFlow.VK_FORMAT_R16G16_S10_5_NV, stack), LAYOUT_GENERAL), "bind flow");
            check(NVOpticalFlow.vkBindOpticalFlowSessionImageNV(device, session[i],
                    NVOpticalFlow.VK_OPTICAL_FLOW_SESSION_BINDING_POINT_BACKWARD_FLOW_VECTOR_NV,
                    view(backFlow, NVOpticalFlow.VK_FORMAT_R16G16_S10_5_NV, stack), LAYOUT_GENERAL), "bind backward flow");
        }
        layoutsSet = false;
        current = -1;
        lastWorld = null;
        LOGGER.info("Hardware optical flow: measuring {}x{} frames, {}x{} vectors", w, h, gw, gh);
    }

    private static GpuTexture flowTexture(String name, int w, int h) {
        // Same bits as the S10.5 flow; read as snorm so the shader can filter it.
        return RenderSystem.getDevice().createTexture("VFrameGen optical flow " + name,
                GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_TEXTURE_BINDING,
                GpuFormat.RG16_SNORM, w, h, 1, 1);
    }

    private static final List<Long> views = new ArrayList<>();

    private static long view(long image, int format, MemoryStack stack) {
        org.lwjgl.vulkan.VkImageViewCreateInfo info = org.lwjgl.vulkan.VkImageViewCreateInfo.calloc(stack).sType$Default()
                .image(image).viewType(1).format(format); // 2D
        info.subresourceRange().aspectMask(1).levelCount(1).layerCount(1);
        LongBuffer handle = stack.mallocLong(1);
        check(VK12.vkCreateImageView(device, info, null, handle), "image view");
        views.add(handle.get(0));
        return handle.get(0);
    }

    private static long image(int w, int h, int format, int usage, int flowUsage, MemoryStack stack) {
        VkOpticalFlowImageFormatInfoNV flowInfo = VkOpticalFlowImageFormatInfoNV.calloc(stack).sType$Default().usage(flowUsage);
        VkImageCreateInfo info = VkImageCreateInfo.calloc(stack).sType$Default().pNext(flowInfo.address())
                .imageType(1).format(format).mipLevels(1).arrayLayers(1).samples(1).tiling(0).usage(usage)
                .initialLayout(LAYOUT_UNDEFINED);
        info.extent().set(w, h, 1);
        if (graphicsFamily >= 0 && graphicsFamily != family) {
            info.sharingMode(1).pQueueFamilyIndices(stack.ints(graphicsFamily, family)); // CONCURRENT
        }
        LongBuffer handle = stack.mallocLong(1);
        check(VK12.vkCreateImage(device, info, null, handle), "image");
        return handle.get(0);
    }

    private static long bind(long image, MemoryStack stack) {
        VkMemoryRequirements req = VkMemoryRequirements.calloc(stack);
        VK12.vkGetImageMemoryRequirements(device, image, req);
        VkPhysicalDeviceMemoryProperties mem = VkPhysicalDeviceMemoryProperties.calloc(stack);
        VK12.vkGetPhysicalDeviceMemoryProperties(device.getPhysicalDevice(), mem);
        int type = -1;
        for (int i = 0; i < mem.memoryTypeCount(); i++) {
            if ((req.memoryTypeBits() & (1 << i)) != 0 && (mem.memoryTypes(i).propertyFlags() & 0x1) != 0) { // DEVICE_LOCAL
                type = i;
                break;
            }
        }
        if (type < 0) throw new IllegalStateException("no device memory for optical flow");
        LongBuffer handle = stack.mallocLong(1);
        check(VK12.vkAllocateMemory(device, VkMemoryAllocateInfo.calloc(stack).sType$Default()
                .allocationSize(req.size()).memoryTypeIndex(type), null, handle), "memory");
        check(VK12.vkBindImageMemory(device, image, handle.get(0), 0), "bind memory");
        return handle.get(0);
    }

    /**
     * Frees the images and sessions once the GPU is done with them. False (nothing freed) when it
     * did not finish within a second: then they may still be in use, and are left alone.
     */
    private static boolean destroyImages() {
        if (device == null) return true;
        if (timelineValue > 0) {
            // The last signals may still be waiting for the game's next submission: submit now.
            RenderSystem.getDevice().createCommandEncoder().submit();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                if (!waitTimeline(timelineValue, stack)) return false;
            }
        }
        for (int i = 0; i < 2; i++) {
            if (session[i] != 0) NVOpticalFlow.vkDestroyOpticalFlowSessionNV(device, session[i], null);
            session[i] = 0;
        }
        for (long v : views) VK12.vkDestroyImageView(device, v, null);
        views.clear();
        for (int i = 0; i < 2; i++) {
            if (input[i] != 0) VK12.vkDestroyImage(device, input[i], null);
            if (inputMemory[i] != 0) VK12.vkFreeMemory(device, inputMemory[i], null);
            input[i] = inputMemory[i] = 0;
        }
        if (flow != 0) VK12.vkDestroyImage(device, flow, null);
        if (flowMemory != 0) VK12.vkFreeMemory(device, flowMemory, null);
        if (backFlow != 0) VK12.vkDestroyImage(device, backFlow, null);
        if (backFlowMemory != 0) VK12.vkFreeMemory(device, backFlowMemory, null);
        flow = flowMemory = backFlow = backFlowMemory = 0;
        for (Pair p : pairs) p.valid = false; // the textures are kept (resized when next used)
        pending = null;
        return true;
    }

    /** Waits (up to a second) until the GPU has reached value on the timeline; false if it did not. */
    private static boolean waitTimeline(long value, MemoryStack stack) {
        VkSemaphoreWaitInfo info = VkSemaphoreWaitInfo.calloc(stack).sType$Default()
                .semaphoreCount(1).pSemaphores(stack.longs(timeline)).pValues(stack.longs(value));
        return VK12.vkWaitSemaphores(device, info, 1_000_000_000L) == 0;
    }

    private static void barrier(VkCommandBuffer cb, MemoryStack stack, long image, int oldLayout) {
        VkImageMemoryBarrier2.Buffer b = VkImageMemoryBarrier2.calloc(1, stack).sType$Default();
        b.srcStageMask(STAGE_ALL).srcAccessMask(0).dstStageMask(STAGE_ALL).dstAccessMask(ACCESS_READ_WRITE)
                .oldLayout(oldLayout).newLayout(LAYOUT_GENERAL).srcQueueFamilyIndex(-1).dstQueueFamilyIndex(-1).image(image);
        b.subresourceRange().aspectMask(1).levelCount(1).layerCount(1);
        KHRSynchronization2.vkCmdPipelineBarrier2KHR(cb, VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(b));
    }

    private static void check(int result, String what) {
        if (result != 0) throw new IllegalStateException("Optical flow " + what + ": VkResult " + result);
    }

    private static List<Integer> formats(VkDevice device, int usage, MemoryStack stack) {
        VkOpticalFlowImageFormatInfoNV info = VkOpticalFlowImageFormatInfoNV.calloc(stack).sType$Default().usage(usage);
        IntBuffer count = stack.mallocInt(1);
        // A failed query (or VK_INCOMPLETE) gives no formats: optical flow then stays off.
        if (NVOpticalFlow.vkGetPhysicalDeviceOpticalFlowImageFormatsNV(device.getPhysicalDevice(), info, count, null) != 0) return List.of();
        VkOpticalFlowImageFormatPropertiesNV.Buffer out = VkOpticalFlowImageFormatPropertiesNV.calloc(count.get(0), stack);
        for (VkOpticalFlowImageFormatPropertiesNV f : out) f.sType$Default();
        if (NVOpticalFlow.vkGetPhysicalDeviceOpticalFlowImageFormatsNV(device.getPhysicalDevice(), info, count, out) != 0) return List.of();
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
