package io.github.jmlambo.vframegen.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RenderTarget.class)
public interface RenderTargetAccessor {
    @Accessor("colorTexture")
    GpuTexture vframegen$getColorTexture();

    @Accessor("colorTexture")
    void vframegen$setColorTexture(GpuTexture t);

    @Accessor("colorTextureView")
    GpuTextureView vframegen$getColorTextureView();

    @Accessor("colorTextureView")
    void vframegen$setColorTextureView(GpuTextureView v);

    @Accessor("depthTexture")
    GpuTexture vframegen$getDepthTexture();

    @Accessor("depthTexture")
    void vframegen$setDepthTexture(GpuTexture t);

    @Accessor("depthTextureView")
    GpuTextureView vframegen$getDepthTextureView();

    @Accessor("depthTextureView")
    void vframegen$setDepthTextureView(GpuTextureView v);
}
