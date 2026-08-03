package dev.aether.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MouseHandler.class)
public interface AccessorMouseHandler {
    @Accessor("accumulatedDX")
    void aether$setAccumulatedDX(double value);

    @Accessor("accumulatedDY")
    void aether$setAccumulatedDY(double value);

    @Accessor("ignoreFirstMove")
    void aether$setIgnoreFirstMove(boolean value);
}
