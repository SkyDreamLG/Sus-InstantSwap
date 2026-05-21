package com.susinstantswap.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Sus-InstantSwap configuration — NeoForge ModConfigSpec based.
 *
 * Config file location: config/susinstantswap-client.toml
 * Compatible with Configured mod for in-game editing.
 * All comment text is in English for universal readability;
 * the in-game config screen uses Minecraft i18n keys.
 */
public class SwapConfig {

    public final ModConfigSpec.BooleanValue longPressMode;
    public final ModConfigSpec.IntValue holdThresholdMs;
    public final ModConfigSpec.BooleanValue soundEnabled;
    public final ModConfigSpec.BooleanValue debug;
    public final ModConfigSpec.BooleanValue mouseReposition;

    public SwapConfig(ModConfigSpec.Builder builder) {
        builder.comment(
                "Su's Instant Swap v1.1.0 Configuration",
                "",
                "Long/Short Press Mode:",
                "  ON  (true):  short Alt press (< holdThresholdMs) toggles the vanilla inventory,",
                "               long press activates instant swap",
                "  OFF (false): hold Alt to open inventory, release to swap (v1.0.3 classic behavior)",
                "",
                "Changes take effect immediately — no restart needed.",
                "Compatible with Configured mod for in-game editing."
        );

        // ── Long/Short Press ──
        builder.push("long_short_press");

        longPressMode = builder
                .translation("config.susinstantswap.longPressMode")
                .comment(
                        "Toggle long/short press mode.",
                        "  true  = short press toggles inventory / long press swaps",
                        "  false = classic mode (hold to open, release to swap)"
                )
                .define("longPressMode", true);

        holdThresholdMs = builder
                .translation("config.susinstantswap.holdThresholdMs")
                .comment(
                        "Long press threshold in milliseconds.",
                        "Press duration above this value is treated as a long press.",
                        "Range: 50 – 1000 ms"
                )
                .defineInRange("holdThresholdMs", 200, 50, 1000);

        builder.pop();

        // ── Sound ──
        builder.push("sound");

        soundEnabled = builder
                .translation("config.susinstantswap.soundEnabled")
                .comment("Enable / disable the swap sound effect.")
                .define("soundEnabled", true);

        builder.pop();

        // ── Debug ──
        builder.push("debug");

        debug = builder
                .translation("config.susinstantswap.debug")
                .comment("Enable / disable debug logging to the game log.")
                .define("debug", false);

        builder.pop();

        // ── Mouse ──
        builder.push("mouse");

        mouseReposition = builder
                .translation("config.susinstantswap.mouseReposition")
                .comment(
                        "When opening the inventory, automatically move the mouse",
                        "to the bottom-right corner of the UI.",
                        "  true  = move mouse",
                        "  false = keep original position"
                )
                .define("mouseReposition", true);

        builder.pop();
    }
}
