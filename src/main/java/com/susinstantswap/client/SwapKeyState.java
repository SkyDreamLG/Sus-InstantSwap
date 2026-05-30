package com.susinstantswap.client;

/**
 * Shared state between mixins and InstantSwapClient.
 */
public final class SwapKeyState {
    public static volatile boolean inventoryKeyHeld = false;
    public static volatile long pressStartNanos = 0;
    public static volatile boolean longPressConfirmed = false;
    /** Swap completed — countdown to close (0=idle, 1=survival, 2=creative). */
    public static volatile int closePendingTicks = 0;
    /** Master switch — when false, the entire mod is disabled. Synced from config. */
    public static volatile boolean modEnabled = true;
    private SwapKeyState() {}
}
