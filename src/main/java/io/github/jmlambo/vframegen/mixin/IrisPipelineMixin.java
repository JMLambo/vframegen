package io.github.jmlambo.vframegen.mixin;

import io.github.jmlambo.vframegen.DeferredSolidAccess;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * With a shader pack, the entities held back from the terrain's pass (LevelRendererMixin) are
 * drawn here: Iris has closed the opaque pass by now but still sends draws to its opaque targets,
 * and runs the pack's deferred lighting only after this. Drawn any later, they went to its
 * translucent targets and came out see-through.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap = false)
public abstract class IrisPipelineMixin {
    @Inject(method = "beginTranslucents", at = @At("HEAD"), require = 0)
    private void vframegen$entitiesBeforeTranslucents(CallbackInfo ci) {
        if (Minecraft.getInstance().levelRenderer instanceof DeferredSolidAccess access) {
            // The held item was drawn just before: its program must not be used for entities.
            try {
                getClass().getMethod("removePhaseIfNeeded").invoke(this);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
            access.vframegen$runDeferredSolid();
        }
    }
}
