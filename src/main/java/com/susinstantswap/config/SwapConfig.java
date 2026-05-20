package com.susinstantswap.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Sus_InstantSwap 配置文件 —— 基于 NeoForge ModConfigSpec。
 *
 * 配置文件位置：config/susinstantswap-client.toml
 * 兼容 Configured 等模组在游戏内修改配置。
 */
public class SwapConfig {

    public final ModConfigSpec.BooleanValue longPressMode;
    public final ModConfigSpec.IntValue holdThresholdMs;
    public final ModConfigSpec.BooleanValue soundEnabled;
    public final ModConfigSpec.BooleanValue debug;
    public final ModConfigSpec.BooleanValue mouseReposition;

    public SwapConfig(ModConfigSpec.Builder builder) {
        builder.comment("探囊取物 (Su's Instant Swap) v1.1.0 配置",
                "",
                "长短按模式 (Long/Short Press Mode):",
                "  ON (true):  短按 Alt (< holdThresholdMs) 切换原版物品栏，长按启用即时交换",
                "  OFF (false): 按住 Alt 打开物品栏，松开执行交换（v1.0.3 经典行为）",
                "",
                "修改后即时生效，无需重启游戏。",
                "Compatible with Configured mod for in-game editing.");

        longPressMode = builder
                .translation("config.susinstantswap.longPressMode")
                .comment("长短按模式开关 (Long/Short Press Mode)",
                        "true = 短按切换物品栏/长按交换",
                        "false = 经典模式（按住打开，松开交换）")
                .define("longPressMode", true);

        holdThresholdMs = builder
                .translation("config.susinstantswap.holdThresholdMs")
                .comment("长按判定阈值 (Long Press Threshold)，单位：毫秒",
                        "按下超过此时长视为长按，否则为短按",
                        "范围 (Range): 50-1000ms")
                .defineInRange("holdThresholdMs", 200, 50, 1000);

        soundEnabled = builder
                .translation("config.susinstantswap.soundEnabled")
                .comment("交换音效 (Swap Sound)")
                .define("soundEnabled", true);

        debug = builder
                .translation("config.susinstantswap.debug")
                .comment("调试日志 (Debug Logging)")
                .define("debug", false);

        mouseReposition = builder
                .translation("config.susinstantswap.mouseReposition")
                .comment("鼠标重定位 (Mouse Reposition)",
                        "打开物品栏时自动将鼠标移动到 UI 右下角",
                        "true = 移动，false = 保持原位")
                .define("mouseReposition", true);
    }
}
