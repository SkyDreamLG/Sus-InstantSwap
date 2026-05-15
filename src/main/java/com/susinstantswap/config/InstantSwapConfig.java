package com.susinstantswap.config;

import com.mojang.logging.LogUtils;
import com.susinstantswap.api.HandlerRegistry;
import com.susinstantswap.api.InstantSwapHandler;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;

import java.util.List;
import java.util.StringJoiner;

/**
 * SusInstantSwap 客户端配置。
 * <p>
 * 配置文件位于 {@code config/susinstantswap-client.toml}。
 * 由 NeoForge 自动生成和管理。
 * </p>
 * <p>
 * 游戏内通过"Mods"菜单 → 选择本模组 → "Config"按钮进入配置界面。
 * </p>
 */
public final class InstantSwapConfig {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 配置规格，在 {@link #createSpec()} 中构建 */
    public static ModConfigSpec SPEC;
    /** 优先弹出的交换处理器名称 */
    public static ModConfigSpec.ConfigValue<String> PREFERRED_HANDLER;

    private InstantSwapConfig() {
    }

    /**
     * 创建配置规格。
     * <p>
     * 必须在 {@link HandlerRegistry#discover()} 之后调用，
     * 以便在注释中列出所有已发现的 handler 名称。
     * </p>
     *
     * @return 构建好的 ModConfigSpec 实例
     */
    public static ModConfigSpec createSpec() {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        // 构建注释（包含当前可用的 handler 列表）
        List<InstantSwapHandler> handlers = HandlerRegistry.getHandlers();
        StringJoiner names = new StringJoiner(", ");
        for (InstantSwapHandler h : handlers) {
            names.add("\"" + h.getName() + "\"");
        }

        // 默认值使用内置的 VanillaInventory
        String defaultHandlerName = "VanillaInventory";

        String commentText = "优先弹出的交换处理器名称。\n"
                + "当前已注册的处理器: " + names.toString() + "。\n"
                + "请输入上述名称之一，无效的名称将无法保存。";

        builder.comment("SusInstantSwap 客户端配置",
                "-----------------------------",
                "preferredHandler: 设置优先弹出的背包模组处理器。",
                "  当前可用: " + names.toString(),
                "-----------------------------");

        PREFERRED_HANDLER = builder
                .comment(commentText)
                .translation("susinstantswap.config.preferred_handler")
                .define("preferredHandler", defaultHandlerName, o -> {
                    if (o instanceof String s) {
                        for (InstantSwapHandler h : HandlerRegistry.getHandlers()) {
                            if (h.getName().equals(s)) return true;
                        }
                    }
                    return false;
                });

        SPEC = builder.build();
        return SPEC;
    }

    /**
     * 返回配置的优先 handler 名称。
     * 如果为空、空白或未注册，则返回 null 并记录警告。
     *
     * @return handler 名称，或 null 表示回退到自动选择
     */
    public static String getPreferredHandlerName() {
        if (SPEC == null) {
            return null;
        }
        String name = PREFERRED_HANDLER.get();
        if (StringUtils.isBlank(name)) {
            LOGGER.warn("[SusInstantSwap] 配置的优先处理器名称为空，回退到自动选择");
            return null;
        }

        InstantSwapHandler found = HandlerRegistry.findByName(name.trim());
        if (found == null) {
            LOGGER.warn("[SusInstantSwap] 配置的优先处理器 '{}' 未注册，回退到自动选择", name);
            return null;
        }

        return name.trim();
    }
}