package com.susinstantswap.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes MouseHandler's internal xpos/ypos fields so we can set them
 * directly before the first render frame.
 * Loom automatically remaps the field names from Mojang to intermediary.
 */
@Mixin(MouseHandler.class)
public interface MouseHandlerAccessor {

    @Accessor("xpos")
    void setXpos(double xpos);

    @Accessor("ypos")
    void setYpos(double ypos);
}
