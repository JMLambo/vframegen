package io.github.jmlambo.vframegen.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Minecraft.class)
public interface MinecraftAccessor {
    @Accessor("surfaceIsInvalid")
    void vframegen$setSurfaceIsInvalid(boolean invalid);
}
