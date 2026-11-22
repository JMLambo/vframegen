package io.github.jmlambo.vframegen.mixin;

import io.github.jmlambo.vframegen.TemporalUpscaler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Temporal upscaling: the terrain sampler's mip bias (TemporalUpscaler.creatingTerrainSampler). */
@Pseudo
@Mixin(targets = "com.mojang.renderpearl.backend.vulkan.VulkanGpuSampler")
public abstract class VulkanSamplerMixin {
    @ModifyArg(method = "<init>", require = 0, at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VkSamplerCreateInfo;mipLodBias(F)Lorg/lwjgl/vulkan/VkSamplerCreateInfo;"))
    private float vframegen$mipBias(float bias) {
        return bias + TemporalUpscaler.pendingMipBias();
    }
}
