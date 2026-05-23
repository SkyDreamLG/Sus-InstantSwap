package com.susinstantswap.config;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Sus-InstantSwap configuration — Forge ForgeConfigSpec based.
 *
 * Config file location: config/susinstantswap-client.toml
 * Changes take effect immediately — no restart needed.
 */
public class SwapConfig {

    public final ForgeConfigSpec.BooleanValue longPressMode;
    public final ForgeConfigSpec.IntValue holdThresholdMs;
    public final ForgeConfigSpec.BooleanValue soundEnabled;
    public final ForgeConfigSpec.BooleanValue debug;
    public final ForgeConfigSpec.BooleanValue mouseReposition;

    public SwapConfig(ForgeConfigSpec.Builder builder) {
        builder.comment("Su's Instant Swap Configuration",
                "",
                "Changes take effect immediately. No restart needed.",
                "You can also edit these in-game via the mod config screen.");

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

        soundEnabled = builder
                .translation("config.susinstantswap.soundEnabled")
                .comment("", "Swap Sound")
                .define("soundEnabled", true);

        mouseReposition = builder
                .translation("config.susinstantswap.mouseReposition")
                .comment("", "Mouse Reposition",
                        "Automatically move cursor to bottom-right when inventory opens")
                .define("mouseReposition", true);

        debug = builder
                .translation("config.susinstantswap.debug")
                .comment("", "Debug Logging")
                .define("debug", false);
    }
}
