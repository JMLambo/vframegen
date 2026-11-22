package io.github.jmlambo.vframegen.mixin;

import io.github.jmlambo.vframegen.TemporalUpscaler;
import org.lwjgl.opengl.GL33C;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Temporal upscaling: the terrain sampler's mip bias (TemporalUpscaler.creatingTerrainSampler). */
@Pseudo
@Mixin(targets = "com.mojang.renderpearl.backend.opengl.GlSampler")
public abstract class GlSamplerMixin {
    @Shadow
    @Final
    private int id;

    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void vframegen$mipBias(CallbackInfo ci) {
        float bias = TemporalUpscaler.pendingMipBias();
        if (bias != 0f) GL33C.glSamplerParameterf(id, GL33C.GL_TEXTURE_LOD_BIAS, bias);
    }
}
