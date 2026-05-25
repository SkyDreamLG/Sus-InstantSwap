package com.susinstantswap.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Sus-InstantSwap configuration — NeoForge ModConfigSpec based.
 *
 * Config file location: config/susinstantswap-client.toml
 * Compatible with Configured mod for in-game editing.
 * Changes take effect immediately — no restart needed.
 */
public class SwapConfig {

    public final ModConfigSpec.BooleanValue longPressMode;
    public final ModConfigSpec.IntValue holdThresholdMs;
    public final ModConfigSpec.BooleanValue soundEnabled;
    public final ModConfigSpec.BooleanValue guiSwapEnabled;
    public final ModConfigSpec.BooleanValue debug;
    public final ModConfigSpec.BooleanValue mouseReposition;
    public SwapConfig(ModConfigSpec.Builder builder) {
        builder.comment("Su's Instant Swap Configuration",
                "",
                "Changes take effect immediately. No restart needed.",
                "You can also edit these in-game via the mod config screen.");

        // All options at root level — no sub-sections.

        // -----------------------------
        //  Long/Short Press Mode
        // -----------------------------
        longPressMode = builder
                .translation("config.susinstantswap.longPressMode")
                .comment("", "Long/Short Press Mode",
                        "  true  = Short press toggles inventory, long press swaps",
                        "  false = Classic mode (hold to open, release to swap)")
                .define("longPressMode", true);

        holdThresholdMs = builder
                .translation("config.susinstantswap.holdThresholdMs")
                .comment("", "Long Press Threshold (ms). Range: 50 ~ 1000")
                .defineInRange("holdThresholdMs", 200, 50, 1000);

        // -----------------------------
        //  Sound
        // -----------------------------
        soundEnabled = builder
                .translation("config.susinstantswap.soundEnabled")
                .comment("", "Swap Sound")
                .define("soundEnabled", true);

        // -----------------------------
        //  Mouse Reposition
        // -----------------------------
        mouseReposition = builder
                .translation("config.susinstantswap.mouseReposition")
                .comment("", "Mouse Reposition",
                        "Automatically move cursor to bottom-right when inventory opens")
                .define("mouseReposition", true);

        // -----------------------------
        //  GUI Swap
        // -----------------------------
        guiSwapEnabled = builder
                .translation("config.susinstantswap.guiSwapEnabled")
                .comment("", "GUI Swap",
                        "When enabled, pressing the swap key while hovering",
                        "over an item in any inventory/container screen",
                        "will swap the item and close the screen.",
                        "Can use a dedicated key binding (Swap in GUI) or",
                        "follow the Instant Swap key.")
                .define("guiSwapEnabled", true);

        // -----------------------------
        //  Debug
        // -----------------------------
        debug = builder
                .translation("config.susinstantswap.debug")
                .comment("", "Debug Logging")
                .define("debug", false);

    }
}