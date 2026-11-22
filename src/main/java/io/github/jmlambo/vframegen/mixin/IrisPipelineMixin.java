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
 * drawn here, as Iris starts the held item: it has closed the opaque pass by now but still sends
 * draws to its opaque targets. Before the hand, because that is when Iris samples the centre depth
 * (depth of field focus) and copies the depth without the hand (depthtex2): later, both missed the
 * entities. In translucent mode they came out see-through.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap = false)
public abstract class IrisPipelineMixin {
    @Inject(method = "beginHand", at = @At("HEAD"), require = 0)
    private void vframegen$entitiesBeforeHand(CallbackInfo ci) {
        vframegen$drawEntities();
    }

    // In case the held item is skipped: still before the pack's deferred passes.
    @Inject(method = "beginTranslucents", at = @At("HEAD"), require = 0)
    private void vframegen$entitiesBeforeTranslucents(CallbackInfo ci) {
        vframegen$drawEntities();
    }

    @org.spongepowered.asm.mixin.Unique
    private void vframegen$drawEntities() {
        if (Minecraft.getInstance().levelRenderer instanceof DeferredSolidAccess access) {
            // Not with a phase left over (the held item's program, when the hand was drawn first).
            try {
                getClass().getMethod("removePhaseIfNeeded").invoke(this);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
            access.vframegen$runDeferredSolid();
        }
    }
}
