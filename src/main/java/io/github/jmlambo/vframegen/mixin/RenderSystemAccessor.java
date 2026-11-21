package io.github.jmlambo.vframegen.mixin;

import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.blaze3d.systems.RenderSystem;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RenderSystem.class)
public interface RenderSystemAccessor {
    /** Null until the first resource reload has loaded every pack's shaders, mods' included. */
    @Accessor("currentPipelineCache")
    static @Nullable PipelineCache vframegen$getCurrentPipelineCache() {
        throw new AssertionError();
    }
}
