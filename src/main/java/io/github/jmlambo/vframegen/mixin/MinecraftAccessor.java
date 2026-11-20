package io.github.jmlambo.vframegen.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Minecraft.class)
public interface MinecraftAccessor {
    @Accessor("surfaceIsInvalid")
    void vframegen$setSurfaceIsInvalid(boolean invalid);

    @Accessor("windowSurfaceNeedsReconfiguring")
    void vframegen$setWindowSurfaceNeedsReconfiguring(boolean reconfigure);

    /** Vanilla's per-second frame counter, which becomes the F3 FPS. */
    @Accessor("frames")
    int vframegen$getFrames();

    @Accessor("frames")
    void vframegen$setFrames(int frames);
}
