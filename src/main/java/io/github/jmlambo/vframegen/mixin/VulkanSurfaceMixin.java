package io.github.jmlambo.vframegen.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSurface;
import io.github.jmlambo.vframegen.PresentThread;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSurfaceCapabilitiesKHR;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.IntBuffer;
import java.util.concurrent.locks.LockSupport;

/**
 * The window's swapchain, shared with VFrameGen's present thread: presents are handed to that
 * thread, acquires never block while holding the shared lock, and the swapchain is only recreated
 * or destroyed once that thread has presented everything it holds.
 */
@Mixin(VulkanGpuSurface.class)
public abstract class VulkanSurfaceMixin {
    @WrapOperation(method = "present", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkQueuePresentKHR(Lorg/lwjgl/vulkan/VkQueue;Lorg/lwjgl/vulkan/VkPresentInfoKHR;)I"))
    private int vframegen$present(VkQueue queue, VkPresentInfoKHR info, Operation<Integer> original) {
        if (PresentThread.enabled()) return PresentThread.defer(queue, info);
        PresentThread.LOCK.lock();
        try {
            return original.call(queue, info);
        } finally {
            PresentThread.LOCK.unlock();
        }
    }

    @WrapOperation(method = "acquireNextTexture", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkAcquireNextImageKHR(Lorg/lwjgl/vulkan/VkDevice;JJJJLjava/nio/IntBuffer;)I"))
    private int vframegen$acquire(VkDevice device, long swapchain, long timeout, long semaphore, long fence,
                                  IntBuffer index, Operation<Integer> original) {
        // The swapchain may not be used by two threads at once, and an acquire can wait for the
        // present thread to give an image back: poll without waiting while holding the lock.
        long end = System.nanoTime() + timeout;
        while (true) {
            int result;
            PresentThread.LOCK.lock();
            try {
                result = original.call(device, swapchain, 0L, semaphore, fence, index);
            } finally {
                PresentThread.LOCK.unlock();
            }
            if (result != 1 /* VK_NOT_READY */ && result != 2 /* VK_TIMEOUT */) return result;
            if (System.nanoTime() >= end) return 2;
            LockSupport.parkNanos(100_000L);
        }
    }

    @Inject(method = {"configure", "close"}, at = @At("HEAD"))
    private void vframegen$drainBeforeRecreate(CallbackInfo ci) {
        PresentThread.drain();
    }

    @Inject(method = "configure", at = @At("HEAD"))
    private void vframegen$logMode(com.mojang.renderpearl.api.device.GpuSurface.Configuration config, CallbackInfo ci) {
        io.github.jmlambo.vframegen.VFrameGen.LOGGER.info("Swapchain: {}x{} present mode {} (supported: {})",
                config.width(), config.height(), config.presentMode(), ((VulkanGpuSurface) (Object) this).supportedPresentModes());
    }

    /**
     * More images: with presents made later, a real frame and all its generated frames wait in the
     * present thread at once, plus the image on screen and the next real frame's. Too few and the
     * render thread waits for the present thread on every generated frame, which at 4x cost more
     * than half of each loop.
     */
    @ModifyExpressionValue(method = "configure", at = @At(value = "INVOKE", target = "Ljava/lang/Math;max(II)I"))
    private int vframegen$moreImages(int count, @Local VkSurfaceCapabilitiesKHR caps) {
        if (!PresentThread.enabled()) return count;
        int multiplier = Math.clamp(io.github.jmlambo.vframegen.VFrameGen.config().multiplier, 2,
                io.github.jmlambo.vframegen.FrameGenConfig.MAX_MULTIPLIER);
        int max = caps.maxImageCount() == 0 ? 16 : caps.maxImageCount();
        return Math.max(count, Math.min(count + multiplier, max));
    }
}
