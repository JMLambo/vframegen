package io.github.jmlambo.vframegen.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import io.github.jmlambo.vframegen.RenderScale;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.io.File;
import java.util.function.Consumer;

/** F2 with render scale: save the finished full-size picture (GUI included), not the small world image. */
@Mixin(Screenshot.class)
public abstract class ScreenshotMixin {
    @WrapOperation(method = "grab(Lnet/minecraft/client/Minecraft;Z)V", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Screenshot;grab(Ljava/io/File;Lcom/mojang/blaze3d/pipeline/RenderTarget;Ljava/util/function/Consumer;)V"))
    private static void vframegen$finishedPicture(File dir, RenderTarget target, Consumer<Component> callback,
                                                  Operation<Void> original) {
        RenderScale.withFinishedPicture(target, () -> original.call(dir, target, callback));
    }
}
