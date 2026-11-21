package io.github.jmlambo.vframegen;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSemaphoreWaitInfo;

import java.util.ArrayDeque;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Vulkan only: shows frames at their time from a thread of its own, the way FSR 3 / DLSS frame
 * generation pace frames.
 *
 * <p>The render thread still records everything (the real frame, the generated frames, the blits
 * into swapchain images) and submits it as before. Only the final vkQueuePresentKHR calls are
 * handed over here: a present only flips an image whose GPU work is already queued, so it can be
 * made exactly when wanted while the render thread is long into the next frame.
 *
 * <p>Pacing: a real frame is presented at once (the display shows it when the GPU has finished it).
 * This thread then waits for that moment (the frame's submission signals a timeline semaphore) and
 * presents generated frame k of M at that moment + k/M of the measured interval between real
 * frames.
 *
 * <p>The queue and the swapchain are shared with the render thread: every submit, present, wait-idle
 * and acquire on them takes {@link #LOCK} (see the mixins), and swapchain recreation drains this
 * thread first.
 */
public final class PresentThread {
    public static final ReentrantLock LOCK = new ReentrantLock();

    private static final int VK_SUCCESS = 0;
    private static final int VK_SUBOPTIMAL_KHR = 1000001003;
    private static final int VK_ERROR_OUT_OF_DATE_KHR = -1000001004;

    /** What the next present() on the render thread is. */
    public enum Kind { ASAP, REAL, GENERATED }

    private record Job(Kind kind, VkQueue queue, long swapchain, int imageIndex, long waitSemaphore,
                       int k, int of, VkDevice device, long timeline, long timelineValue) {
    }

    private static final ArrayDeque<Job> jobs = new ArrayDeque<>();
    private static final Object signal = new Object();
    private static Thread thread;
    private static volatile boolean busy;
    private static volatile boolean surfaceLost;

    // Set by the render thread for the present() it is about to make.
    private static Kind nextKind = Kind.ASAP;
    private static int nextK, nextOf;
    // The last real (vanilla end-of-frame) submission: its timeline semaphore and value.
    private static VkDevice device;
    private static long timeline;
    private static long timelineValue;

    // Present thread state.
    private static long lastRealShownNs;
    private static double realIntervalNs;
    private static volatile long lastShownNs;
    private static volatile double lateMsSum;
    private static volatile int presentedReal, presentedGenerated;

    private PresentThread() {
    }

    /** On with Vulkan unless turned off in the config. */
    public static boolean enabled() {
        return VFrameGen.config().presentThread;
    }

    public static void setNext(Kind kind, int k, int of) {
        nextKind = kind;
        nextK = k;
        nextOf = of;
    }

    /** Vanilla's end-of-frame submit is about to signal value on timeline (render thread). */
    public static void realSubmit(VkDevice dev, long semaphore, long value) {
        device = dev;
        timeline = semaphore;
        timelineValue = value;
    }

    // Images in the swapchain (set when it is configured).
    private static volatile int imageCount;

    // Images the presentation engine may still hold after their present returned, beyond the one
    // on screen. Vulkan has no portable signal for when it releases one, so this starts at 0, grows
    // whenever a generated frame's acquire still had to wait, and gives one image back after a few
    // seconds without such a wait (checked on every attempt, so it recovers even when attempts are
    // turned down). It never grows so far that a generated frame cannot be made with nothing queued.
    private static final long RESERVE_RECOVERY_NS = 3_000_000_000L;
    private static volatile int engineHeld;
    private static volatile long lastAcquireWaitNs;

    public static void setImageCount(int count) {
        imageCount = count;
        engineHeld = 0;
    }

    /** A generated frame's acquire waited this long (render thread). */
    public static void noteGeneratedAcquire(long waitedNs) {
        if (waitedNs <= 1_000_000L) return;
        lastAcquireWaitNs = System.nanoTime();
        // With nothing queued: 1 on screen + engineHeld + this frame + 1 for the next real frame.
        int most = Math.min(2, Math.max(0, imageCount - 3));
        if (engineHeld < most) {
            engineHeld++;
            if (VFrameGen.config().debugPacingLog) {
                VFrameGen.LOGGER.info("Present thread: keeping {} more swapchain image(s) free (acquire waited {} ms)",
                        engineHeld, String.format(java.util.Locale.ROOT, "%.1f", waitedNs / 1e6));
            }
        }
    }

    /**
     * Whether a generated frame can get a swapchain image without waiting: one image is on screen,
     * every present still queued here holds one, the presentation engine may hold a few more (see
     * engineHeld), and one is kept free so the next real frame never waits either. Without this the render thread blocked in acquire whenever many generated
     * frames were queued (6x at a low real frame rate), and frame generation lowered the real
     * frame rate by half.
     */
    public static boolean imageFree() {
        int count = imageCount;
        if (count <= 0) return true;
        if (engineHeld > 0 && System.nanoTime() - lastAcquireWaitNs > RESERVE_RECOVERY_NS) {
            engineHeld--;
            lastAcquireWaitNs = System.nanoTime();
        }
        int held;
        synchronized (signal) {
            held = jobs.size() + (busy ? 1 : 0);
        }
        return held + 1 + engineHeld + 2 <= count;
    }

    /** True once if a deferred present found the swapchain out of date: vanilla reconfigures it. */
    public static boolean takeSurfaceLost() {
        boolean lost = surfaceLost;
        surfaceLost = false;
        return lost;
    }

    /**
     * Instead of vkQueuePresentKHR on the render thread: queue it. Returns VK_SUCCESS; a failure
     * shows up later through {@link #takeSurfaceLost()} (and the next acquire).
     */
    public static int defer(VkQueue queue, VkPresentInfoKHR info) {
        Job job = new Job(nextKind, queue, info.pSwapchains().get(0), info.pImageIndices().get(0),
                info.waitSemaphoreCount() > 0 ? info.pWaitSemaphores().get(0) : 0L, nextK, nextOf,
                device, timeline, timelineValue);
        nextKind = Kind.ASAP;
        start();
        synchronized (signal) {
            jobs.addLast(job);
            maxQueued = Math.max(maxQueued, jobs.size());
            signal.notifyAll();
        }
        return VK_SUCCESS;
    }

    /** Present everything still queued, now (before the swapchain is recreated or destroyed). */
    public static void drain() {
        if (thread == null) return;
        synchronized (signal) {
            draining = true;
            signal.notifyAll();
            long end = System.nanoTime() + 2_000_000_000L;
            while ((!jobs.isEmpty() || busy) && System.nanoTime() < end) {
                try {
                    signal.wait(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            draining = false;
        }
    }

    private static volatile boolean draining;
    private static volatile int maxQueued;

    /** For the stats line: presents made, and how late generated frames were on average. */
    public static String stats() {
        int g = presentedGenerated, r = presentedReal;
        double late = g == 0 ? 0 : lateMsSum / g;
        int q = maxQueued;
        presentedGenerated = presentedReal = 0;
        lateMsSum = 0;
        maxQueued = 0;
        return String.format(java.util.Locale.ROOT, "present thread: %d real, %d generated, generated %.2f ms late on average, real interval %.1f ms, most frames waiting %d",
                r, g, late, realIntervalNs / 1e6, q);
    }

    private static synchronized void start() {
        if (thread != null) return;
        thread = new Thread(PresentThread::run, "VFrameGen present");
        thread.setDaemon(true);
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
        VFrameGen.LOGGER.info("Present thread started");
    }

    private static void run() {
        while (true) {
            Job job;
            synchronized (signal) {
                while (jobs.isEmpty()) {
                    try {
                        signal.wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                job = jobs.pollFirst();
                busy = true;
            }
            try {
                handle(job);
            } catch (Throwable t) {
                VFrameGen.LOGGER.error("Present thread: present failed", t);
            } finally {
                synchronized (signal) {
                    busy = false;
                    signal.notifyAll();
                }
            }
        }
    }

    private static int lastOf = 2;

    private static void handle(Job job) {
        if (job.kind == Kind.GENERATED) lastOf = Math.max(2, job.of);
        // Never two frames back to back: after a stall the late real frame and the frame generated
        // before it arrive together, and showing both at once jumped a whole frame of motion (a
        // "teleport"). Keep most of the normal spacing between any two presents instead.
        if (!draining && lastShownNs != 0 && realIntervalNs > 0) {
            long minGap = (long) (realIntervalNs / lastOf * 0.8);
            waitUntil(Math.min(lastShownNs + minGap, System.nanoTime() + minGap));
        }
        if (job.kind == Kind.GENERATED && !draining && lastRealShownNs != 0) {
            double interval = realIntervalNs > 0 ? realIntervalNs : 16_666_667.0;
            long due = lastRealShownNs + (long) (interval * job.k / Math.max(2, job.of));
            // Never hold a frame for long: if the timing is off (a hitch), show it now.
            due = Math.min(due, System.nanoTime() + (long) interval);
            waitUntil(due);
            lateMsSum += Math.max(0, System.nanoTime() - due) / 1e6;
        }
        present(job);
        long now = System.nanoTime();
        if (job.kind == Kind.REAL) {
            presentedReal++;
            // When the GPU finishes this frame it is on screen: the generated frames after it are
            // timed from then. (The present call itself returns long before.)
            long shown = now;
            if (job.device != null && job.timeline != 0 && !draining) {
                if (waitTimeline(job.device, job.timeline, job.timelineValue, 200_000_000L)) {
                    shown = System.nanoTime();
                }
            }
            if (lastRealShownNs != 0) {
                long d = shown - lastRealShownNs;
                if (d > 0 && d < 250_000_000L) {
                    realIntervalNs = realIntervalNs <= 0 ? d : realIntervalNs + (d - realIntervalNs) * 0.1;
                }
            }
            lastRealShownNs = shown;
        } else if (job.kind == Kind.GENERATED) {
            presentedGenerated++;
        }
        lastShownNs = now;
    }

    private static void present(Job job) {
        int result;
        LOCK.lock();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPresentInfoKHR info = VkPresentInfoKHR.calloc(stack).sType$Default()
                    .swapchainCount(1)
                    .pSwapchains(stack.longs(job.swapchain))
                    .pImageIndices(stack.ints(job.imageIndex));
            if (job.waitSemaphore != 0) info.pWaitSemaphores(stack.longs(job.waitSemaphore));
            result = KHRSwapchain.vkQueuePresentKHR(job.queue, info);
        } finally {
            LOCK.unlock();
        }
        if (result == VK_ERROR_OUT_OF_DATE_KHR || result == VK_SUBOPTIMAL_KHR) {
            surfaceLost = true;
        } else if (result != VK_SUCCESS) {
            VFrameGen.LOGGER.warn("Present thread: vkQueuePresentKHR returned {}", result);
            surfaceLost = true;
        }
    }

    private static boolean waitTimeline(VkDevice device, long semaphore, long value, long timeoutNs) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSemaphoreWaitInfo info = VkSemaphoreWaitInfo.calloc(stack).sType$Default()
                    .semaphoreCount(1)
                    .pSemaphores(stack.longs(semaphore))
                    .pValues(stack.longs(value));
            return VK12.vkWaitSemaphores(device, info, timeoutNs) == VK_SUCCESS;
        }
    }

    private static void waitUntil(long target) {
        while (!draining) {
            long left = target - System.nanoTime();
            if (left <= 0) return;
            if (left > 1_500_000L) LockSupport.parkNanos(left - 1_000_000L);
            else Thread.onSpinWait();
        }
    }
}
