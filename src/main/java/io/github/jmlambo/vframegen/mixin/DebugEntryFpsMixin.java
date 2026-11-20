package io.github.jmlambo.vframegen.mixin;

import io.github.jmlambo.vframegen.FrameGenerator;
import io.github.jmlambo.vframegen.VFrameGen;
import net.minecraft.client.gui.components.debug.DebugEntryFps;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Under the F3 FPS line (which counts every frame shown), add how it splits into real and generated. */
@Mixin(DebugEntryFps.class)
public abstract class DebugEntryFpsMixin {
    @Inject(method = "display", at = @At("TAIL"), require = 0)
    private void vframegen$addSplit(DebugScreenDisplayer displayer, Level level, LevelChunk clientChunk,
                                    LevelChunk serverChunk, CallbackInfo ci) {
        FrameGenerator gen = FrameGenerator.INSTANCE;
        String state = VFrameGen.config().enabled ? "" : " (off)";
        displayer.addPriorityLine("VFrameGen: " + gen.realFps() + " real + " + gen.generatedFps() + " generated" + state);
    }
}
