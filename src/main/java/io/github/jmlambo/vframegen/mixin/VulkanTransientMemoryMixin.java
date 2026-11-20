package io.github.jmlambo.vframegen.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.backend.util.TransientBlockAllocator;
import io.github.jmlambo.vframegen.FrameGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Each submit rotates the frame's transient memory: CPU blocks are reused at once, GPU blocks once
 * the submit is done. Vanilla submits once, at the end of a frame. A generated frame is submitted in
 * the middle of one, while the game still has data in those blocks it has not used yet; rotating
 * then lets later allocations overwrite it (flickering lighting and shadows with shader packs).
 * So during our own submits the blocks are left alone, and vanilla's end-of-frame submit rotates
 * everything as usual.
 */
@Mixin(targets = "com.mojang.renderpearl.backend.vulkan.VulkanTransientMemory")
public abstract class VulkanTransientMemoryMixin {
    @WrapOperation(method = "endSubmit", require = 1, at = @At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/backend/util/TransientBlockAllocator;rotate()Ljava/lang/Runnable;"))
    private Runnable vframegen$keepBlocks(TransientBlockAllocator<?> allocator, Operation<Runnable> original) {
        if (FrameGenerator.INSTANCE.submittingGenerated()) {
            return () -> {
            };
        }
        return original.call(allocator);
    }
}
