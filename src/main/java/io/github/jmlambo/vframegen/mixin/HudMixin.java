package io.github.jmlambo.vframegen.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import io.github.jmlambo.vframegen.FrameGenerator;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The vignette darkens the screen edges as part of the GUI, so frame generation took every
 * darkened pixel for GUI and kept it still. With frame generation on, it is drawn on every shown
 * frame instead (motionblur.fsh), with the color vanilla picked here.
 */
@Mixin(Hud.class)
public abstract class HudMixin {
    @WrapOperation(method = "extractVignette", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blit(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIIII)V"))
    private void vframegen$vignette(GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier texture, int x, int y,
                                    float u, float v, int width, int height, int textureWidth, int textureHeight, int color,
                                    Operation<Void> original) {
        if (!FrameGenerator.INSTANCE.takeVignette(color)) {
            original.call(graphics, pipeline, texture, x, y, u, v, width, height, textureWidth, textureHeight, color);
        }
    }
}
