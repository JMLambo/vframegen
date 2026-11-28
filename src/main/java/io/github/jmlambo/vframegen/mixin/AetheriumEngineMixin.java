package io.github.jmlambo.vframegen.mixin;

import io.github.jmlambo.vframegen.Compat;
import io.github.jmlambo.vframegen.DeferredSolidAccess;
import io.github.jmlambo.vframegen.FrameGenerator;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * With an Aetherium shader pack, the held-back entities are drawn and the depth from before
 * translucents is kept here: the level's opaque pass has just closed, the pack has not shaded it
 * yet and the translucents come next.
 */
@Pseudo
@Mixin(targets = "dev.spacebod.aetherium.shaders.engine.ShaderPackEngine", remap = false)
public abstract class AetheriumEngineMixin {
    @Inject(method = "afterOpaque", at = @At("HEAD"), require = 0)
    private void vframegen$depthBeforeTranslucents(CallbackInfo ci) {
        if (!Compat.aetheriumShadersActive()) return;
        Compat.aetheriumDrawing();
        // The entities held back from the terrain's pass (LevelRendererMixin): drawn now, before
        // the pack shades the solid world, with the terrain's depth kept in between.
        if (Minecraft.getInstance().levelRenderer instanceof DeferredSolidAccess access) {
            access.vframegen$runDeferredSolid();
        }
        if (FrameGenerator.INSTANCE.wantsOpaqueDepth()) {
            FrameGenerator.INSTANCE.captureOpaqueDepth(Minecraft.getInstance().gameRenderer);
        }
    }
}
