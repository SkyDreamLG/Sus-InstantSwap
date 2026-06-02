package com.susinstantswap.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import com.susinstantswap.client.InstantSwapClient;
import com.susinstantswap.client.SwapKeyState;
import com.susinstantswap.config.SwapConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * MC 26.1: keyPressed(III)Z → keyPressed(KeyEvent)Z.
 * Intercepts AbstractContainerScreen key handling for:
 *  1. REPEAT inventory key (long press) → block everything (prevents flicker + unwanted GUI swap)
 *  2. GUI swap key (bound) → ALWAYS perform swap regardless of long-press state
 *  3. E key when swap unbound (fresh press only) → perform GUI swap
 *  4. Fresh inventory key → let vanilla handle (normal open/close)
 *
 * Since ScreenEvent.KeyPressed.Pre records are immutable (eventbus 7.0.1),
 * we use a Mixin — the only reliable way to cancel key processing.
 */
@Mixin(value = AbstractContainerScreen.class, remap = false)
public class ScreenKeyMixin {

    @Inject(method = "keyPressed(Lnet/minecraft/client/input/KeyEvent;)Z",
            at = @At("HEAD"), cancellable = true, remap = false)
    private void onKeyPressed(KeyEvent keyEvent, CallbackInfoReturnable<Boolean> cir) {
        if (!SwapKeyState.modEnabled) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.options == null || mc.player == null) return;

        InputConstants.Key pressed = InputConstants.Type.KEYSYM.getOrCreate(keyEvent.key());
        boolean isInvKey = pressed.equals(mc.options.keyInventory.getKey());

        // 1. REPEAT inventory key → block everything during long press
        //    This prevents both screen flicker AND unwanted GUI swap.
        //    (ScreenKeyMixin only fires when a screen is already open,
        //     so by now inventoryKeyHeld is already set from the initial E press.)
        if (isInvKey && SwapKeyState.inventoryKeyHeld) {
            cir.setReturnValue(false);
            return;
        }

        // 2. GUI swap — bound key always, E key only when unbound (fresh press)
        if (InstantSwapClient.tryPerformGuiSwap((AbstractContainerScreen<?>) (Object) this, pressed)) {
            cir.setReturnValue(true);
        }
        // 3. Fresh inventory key → let vanilla handle (normal open/close)
    }
}
