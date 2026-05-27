package com.susinstantswap;

import com.mojang.logging.LogUtils;
import com.susinstantswap.client.InstantSwapClient;
import com.susinstantswap.config.ForgeConfigScreen;
import com.susinstantswap.config.SwapConfig;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * Su's Instant Swap (探囊取物) — Forge 26.1 主类。
 * 纯客户端模组。
 */
@Mod("susinstantswap")
public class SusInstantSwapMod {

    public static final String MOD_ID = "susinstantswap";
    private static final Logger LOGGER = LogUtils.getLogger();

    public static SwapConfig CONFIG;
    public static ForgeConfigSpec CONFIG_SPEC;

    public SusInstantSwapMod(FMLJavaModLoadingContext context) {
        LOGGER.info("[SusInstantSwap] v1.3.0-Forge26.1 — loading started");

        // ── 步骤1：构建配置 ──
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        CONFIG = new SwapConfig(builder);
        CONFIG_SPEC = builder.build();
        LOGGER.info("[SusInstantSwap] Config built");

        // ── 步骤2：注册配置（Forge 会自动从文件加载，更新 CONFIG_SPEC）──
        context.registerConfig(ModConfig.Type.CLIENT, CONFIG_SPEC);
        LOGGER.info("[SusInstantSwap] Config registered (CLIENT type)");

        // ── 步骤3：同步到 Runtime（在 registerConfig 之后调用，确保读取到文件值）──
        CONFIG.syncToRuntime();
        LOGGER.info("[SusInstantSwap] Config synced to Runtime");

        // ── 步骤4：注册配置界面 ──
        ModLoadingContext.get().registerExtensionPoint(
                ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory(ForgeConfigScreen::new)
        );
        LOGGER.info("[SusInstantSwap] Config screen registered");

        // ── 步骤5：注册按键绑定（通过 RegisterKeyMappingsEvent.BUS）──
        RegisterKeyMappingsEvent.BUS.addListener(InstantSwapClient::registerKeys);

        // ── 步骤6：注册客户端事件（InstantSwapClient）──
        InstantSwapClient.init();
        LOGGER.info("[SusInstantSwap] Client logic initialized");

        LOGGER.info("[SusInstantSwap] v1.3.0-Forge26.1 — loaded");
    }
}
