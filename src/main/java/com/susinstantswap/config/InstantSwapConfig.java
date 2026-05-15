package com.susinstantswap.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.StringUtils;

/**
 * SusInstantSwap 客户端配置。
 * <p>
 * 配置文件位于 {@code config/susinstantswap-client.toml}。
 * 由 NeoForge 自动生成和管理。
 * </p>
 */
public final class InstantSwapConfig {

    public static final ModConfigSpec SPEC;

    /** 优先弹出的交换处理器名称，留空则按注册顺序自动选择 */
    public static ModConfigSpec.ConfigValue<String> PREFERRED_HANDLER;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment("SusInstantSwap 客户端配置",
                "-----------------------------",
                "preferredHandler: 设置优先弹出的背包模组处理器名称。",
                "  留空则自动按注册顺序选择（通常就是原版物品栏）。",
                "  可填写 handler 的 getName() 返回值，例如: \"VanillaInventory\"。",
                "  如果填写的名称不存在，会在日志中输出警告并回退到默认行为。",
                "-----------------------------");

        PREFERRED_HANDLER = builder
                .comment("优先弹出的交换处理器名称（留空 = 自动选择）")
                .define("preferredHandler", "");

        SPEC = builder.build();
    }

    private InstantSwapConfig() {
    }

    /**
     * 返回配置的优先 handler 名称。如果为空字符串则返回 null。
     */
    public static String getPreferredHandlerName() {
        String name = PREFERRED_HANDLER.get();
        if (StringUtils.isBlank(name)) {
            return null;
        }
        return name.trim();
    }
}