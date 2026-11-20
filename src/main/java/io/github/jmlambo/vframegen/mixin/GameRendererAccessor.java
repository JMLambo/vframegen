package io.github.jmlambo.vframegen.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
    @Accessor("hud3DTarget")
    RenderTarget vframegen$hud3DTarget();

    @Invoker("bobHurt")
    void vframegen$bobHurt(CameraRenderState camera, PoseStack poseStack);

    @Invoker("bobView")
    void vframegen$bobView(CameraRenderState camera, PoseStack poseStack);
}
