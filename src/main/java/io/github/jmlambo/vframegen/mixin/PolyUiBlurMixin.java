package io.github.jmlambo.vframegen.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.Minecraft;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * OneClient's menu blur (polycompose) sizes its backdrop from the main render target while the menu
 * is laid out. With render scale, the target has the small world size then (the full-size textures
 * are only in while the GUI is drawn), so the blur covered just the top left part of the screen.
 * Give it the window's size, which the target has by the time the blur is drawn.
 */
@Pseudo
@Mixin(targets = "org.polyfrost.compose.minecraft.render.blaze.UiBlur", remap = false)
public abstract class PolyUiBlurMixin {
    @ModifyExpressionValue(method = {"request", "extract", "getBackdropWidth"}, require = 0, at = @At(value = "FIELD",
            target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;width:I", opcode = Opcodes.GETFIELD))
    private int vframegen$windowWidth(int width) {
        return Minecraft.getInstance().getWindow().getWidth();
    }

    @ModifyExpressionValue(method = {"request", "extract", "getBackdropHeight"}, require = 0, at = @At(value = "FIELD",
            target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;height:I", opcode = Opcodes.GETFIELD))
    private int vframegen$windowHeight(int height) {
        return Minecraft.getInstance().getWindow().getHeight();
    }
}
