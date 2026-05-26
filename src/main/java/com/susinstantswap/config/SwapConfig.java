package com.susinstantswap.config;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Sus-InstantSwap Forge 1.21.1 配置。
 *
 * 双层设计：
 *   ForgeConfigSpec 层 — 负责文件持久化（config/susinstantswap-client.toml）
 *   Runtime 层        — 运行时可变值，配置界面直接修改，InstantSwapClient 实时读取
 *
 * 从 Fabric 1.20.1 Gson 配置移植，适配 Forge 配置系统。
 */
public class SwapConfig {

    // ═══════════ ForgeConfigSpec 层（文件持久化）═══════════
    public final ForgeConfigSpec.BooleanValue longPressMode;
    public final ForgeConfigSpec.IntValue holdThresholdMs;
    public final ForgeConfigSpec.BooleanValue soundEnabled;
    public final ForgeConfigSpec.BooleanValue mouseReposition;
    public final ForgeConfigSpec.BooleanValue guiSwapEnabled;
    public final ForgeConfigSpec.BooleanValue debug;

    // ═══════════ Runtime 层（即时可变，游戏内修改立即生效）═══════════
    public static boolean longPressModeRuntime = true;
    public static int holdThresholdMsRuntime = 200;
    public static boolean soundEnabledRuntime = true;
    public static boolean mouseRepositionRuntime = true;
    public static boolean guiSwapEnabledRuntime = false;
    public static boolean debugRuntime = false;

    public SwapConfig(ForgeConfigSpec.Builder builder) {
        builder.comment("Su's Instant Swap Configuration",
                "",
                "Changes take effect immediately. No restart needed.",
                "You can also edit the config file directly at config/susinstantswap-client.toml");

        longPressMode = builder
                .comment("",
                        "Long/Short Press Mode",
                        "  true  = Short press toggles inventory, long press swaps (default)",
                        "  false = Classic mode (hold to open, release to swap)")
                .define("longPressMode", true);

        holdThresholdMs = builder
                .comment("",
                        "Long Press Threshold (ms). Range: 50 ~ 1000")
                .defineInRange("holdThresholdMs", 200, 50, 1000);

        soundEnabled = builder
                .comment("",
                        "Swap Sound — play item pickup sound on swap")
                .define("soundEnabled", true);

        mouseReposition = builder
                .comment("",
                        "Mouse Reposition — auto-move cursor to bottom-right when inventory opens")
                .define("mouseReposition", true);

        guiSwapEnabled = builder
                .comment("",
                        "GUI Swap — when enabled, press the swap key while hovering over an item",
                        "in an inventory/container screen to swap and close the screen immediately")
                .define("guiSwapEnabled", false);

        debug = builder
                .comment("",
                        "Debug Logging — print detailed swap info to game log")
                .define("debug", false);
    }

    /**
     * 从 ForgeConfigSpec 同步到 Runtime 层（registerConfig 之后调用）。
     */
    public void syncToRuntime() {
        longPressModeRuntime = longPressMode.get();
        holdThresholdMsRuntime = holdThresholdMs.get();
        soundEnabledRuntime = soundEnabled.get();
        mouseRepositionRuntime = mouseReposition.get();
        guiSwapEnabledRuntime = guiSwapEnabled.get();
        debugRuntime = debug.get();
    }

    /**
     * 从 Runtime 层同步到 ForgeConfigSpec（配置界面保存时调用）。
     */
    public void syncToSpec() {
        longPressMode.set(longPressModeRuntime);
        holdThresholdMs.set(holdThresholdMsRuntime);
        soundEnabled.set(soundEnabledRuntime);
        mouseReposition.set(mouseRepositionRuntime);
        guiSwapEnabled.set(guiSwapEnabledRuntime);
        debug.set(debugRuntime);
    }
}
