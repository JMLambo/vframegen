package io.github.jmlambo.vframegen.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.backend.util.TransientBlockAllocator;
import io.github.jmlambo.vframegen.FrameGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The OpenGL backend's side of {@link VulkanTransientMemoryMixin}. */
@Mixin(targets = "com.mojang.renderpearl.backend.opengl.GlTransientMemory")
public abstract class GlTransientMemoryMixin {
    @WrapOperation(method = "rotate", require = 0, at = @At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/backend/util/TransientBlockAllocator;rotate()Ljava/lang/Runnable;"))
    private Runnable vframegen$keepBlocks(TransientBlockAllocator<?> allocator, Operation<Runnable> original) {
        if (FrameGenerator.INSTANCE.submittingGenerated()) {
            return () -> {
            };
        }
        return original.call(allocator);
    }
}
