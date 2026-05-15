package com.susinstantswap.config;

import com.mojang.logging.LogUtils;
import com.susinstantswap.api.HandlerRegistry;
import com.susinstantswap.api.InstantSwapHandler;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.slf4j.Logger;

import java.util.List;
import java.util.stream.Collectors;

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
    /** 是否优先使用第三方适配器（开关型） */
    public static ModConfigSpec.BooleanValue PREFER_THIRD_PARTY;
    /** 是否允许与空槽位交换（开关型） */
    public static ModConfigSpec.BooleanValue ALLOW_SWAP_WITH_EMPTY_SLOT;

    private InstantSwapConfig() {
    }

    /**
     * 创建配置规格。
     * <p>
     * 必须在 {@link HandlerRegistry#discover()} 之后调用，
     * 以便在注释中列出第三方 handler 信息。
     * </p>
     *
     * @return 构建好的 ModConfigSpec 实例
     */
    public static ModConfigSpec createSpec() {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        var thirdParties = HandlerRegistry.getThirdPartyHandlers();
        String thirdPartyInfo;
        if (!thirdParties.isEmpty()) {
            thirdPartyInfo = "当前检测到第三方适配器: " +
                    thirdParties.stream().map(InstantSwapHandler::getName).collect(Collectors.joining(", "));
        } else {
            thirdPartyInfo = "当前未安装任何第三方适配器，此选项无效，将始终使用原版逻辑";
        }

        String commentText = "是否优先使用第三方背包模组适配器（如 Traveler's Backpack）。\n"
                + " - true: 优先第三方适配器，第三方失败时回退到原版逻辑\n"
                + " - false: 优先原版逻辑，原版失败时回退到第三方适配器\n"
                + thirdPartyInfo;

        builder.comment("SusInstantSwap 客户端配置",
                "-----------------------------",
                "preferThirdParty: 是否优先使用第三方背包模组适配器。",
                "  " + thirdPartyInfo,
                "-----------------------------");

        PREFER_THIRD_PARTY = builder
                .comment(commentText)
                .translation("susinstantswap.config.prefer_third_party")
                .define("preferThirdParty", true);

        ALLOW_SWAP_WITH_EMPTY_SLOT = builder
                .comment("""
                        是否允许与空槽位交换。
                         - true: 允许，把主手物品放入空槽位
                         - false: 不允许，保持原逻辑并提示玩家""")
                .translation("susinstantswap.config.allow_swap_with_empty_slot")
                .define("allowSwapWithEmptySlot", false);

        SPEC = builder.build();
        return SPEC;
    }

    /**
     * 返回是否优先使用第三方适配器。
     * 如果配置尚未初始化则返回 false。
     *
     * @return true 表示优先第三方
     */
    public static boolean isPreferThirdParty() {
        if (SPEC == null || PREFER_THIRD_PARTY == null) {
            return false;
        }
        return PREFER_THIRD_PARTY.get();
    }

    /**
     * 返回是否允许与空槽位交换。
     * 如果配置尚未初始化则返回 false（即不允许空交换，保持安全默认值）。
     *
     * @return true 表示允许
     */
    public static boolean isAllowSwapWithEmptySlot() {
        if (SPEC == null || ALLOW_SWAP_WITH_EMPTY_SLOT == null) {
            return false;
        }
        return ALLOW_SWAP_WITH_EMPTY_SLOT.get();
    }
}
