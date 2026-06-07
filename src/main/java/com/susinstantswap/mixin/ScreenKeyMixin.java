package com.susinstantswap.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import com.susinstantswap.SwapLog;
import com.susinstantswap.client.InstantSwapClient;
import com.susinstantswap.client.SwapKeyState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Intercepts Screen.keyPressed() for the inventory key on any
 * AbstractContainerScreen.
 *
 * <p>Two scenarios:</p>
 * <ul>
 *   <li><b>REPEAT (inventoryKeyHeld already true):</b> Block close to
 *       prevent flicker during long press. The screen stays open
 *       while the key is held.</li>
 *   <li><b>FRESH press (inventoryKeyHeld false):</b> Try GUI swap
 *       first. If not, let vanilla handle normally (E closes the
 *       screen — standard vanilla behavior).</li>
 * </ul>
 */
@Mixin(value = AbstractContainerScreen.class, remap = false)
public class ScreenKeyMixin {

    @Inject(method = "keyPressed(III)Z", at = @At("HEAD"), cancellable = true)
    private void onKeyPressed(int keyCode, int scanCode, int modifiers,
                              CallbackInfoReturnable<Boolean> cir) {
        if (!SwapKeyState.modEnabled) return;
        if (Minecraft.getInstance().options == null) return;

        InputConstants.Key pressed = InputConstants.getKey(keyCode, scanCode);
        if (!SwapKeyState.isTargetKey(pressed)) return;

        if (SwapKeyState.inventoryKeyHeld) {
            // REPEAT — block close to prevent flicker during long press
            SwapLog.debug("ScreenKeyMixin: repeat key blocked (inventoryKeyHeld=true, key={})", pressed.getName());
            cir.setReturnValue(false);
            return;
        }

        // Fresh press — try GUI swap, otherwise let vanilla handle
        SwapLog.debug("ScreenKeyMixin: fresh press, trying GUI swap...");
        if (InstantSwapClient.tryPerformGuiSwap((AbstractContainerScreen<?>) (Object) this)) {
            SwapLog.debug("ScreenKeyMixin: GUI swap succeeded, intercepting event");
            cir.setReturnValue(true);
        } else {
            SwapLog.debug("ScreenKeyMixin: GUI swap not performed, passing to vanilla");
        }
        // If GUI swap didn't fire: don't intercept — let vanilla close the screen
    }
}
