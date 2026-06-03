package com.susinstantswap.mixin;

import com.susinstantswap.client.InstantSwapClient;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * MC 1.20.x: suppress tooltips after cursor reposition.
 *
 * Blocks hover detection on slots during suppression window.
 * No hovered slot → no highlight → no tooltip.
 *
 * Both overloads needed: render() calls (int,int,int,int,double,double),
 * while creativeSwap and other code calls (Slot,double,double).
 */
@Mixin(AbstractContainerScreen.class)
public class TooltipSuppressMixin {

    @Inject(method = "isHovering(Lnet/minecraft/world/inventory/Slot;DD)Z",
            at = @At("HEAD"), cancellable = true)
    private void onIsHoveringSlot(Slot slot, double mouseX, double mouseY,
                                   CallbackInfoReturnable<Boolean> cir) {
        if (InstantSwapClient.isTooltipSuppressed()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "isHovering(IIIIDD)Z",
            at = @At("HEAD"), cancellable = true)
    private void onIsHoveringInt(int x, int y, int width, int height,
                                  double mouseX, double mouseY,
                                  CallbackInfoReturnable<Boolean> cir) {
        if (InstantSwapClient.isTooltipSuppressed()) {
            cir.setReturnValue(false);
        }
    }
}
