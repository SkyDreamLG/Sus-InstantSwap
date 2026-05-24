package com.susinstantswap;

import com.mojang.logging.LogUtils;
import com.susinstantswap.client.InstantSwapClient;
import com.susinstantswap.config.ForgeConfigScreen;
import com.susinstantswap.config.SwapConfig;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * Su's Instant Swap (探囊取物) — Forge 1.20.1 主类。
 * 纯客户端模组。
 *
 * 事件总线说明：
 *   MOD  event bus — RegisterKeyMappingsEvent（按键注册）
 *   FORGE event bus — ClientTickEvent / RenderTooltipEvent（由 InstantSwapClient 处理）
 */
@Mod("susinstantswap")
public class SusInstantSwapMod {

    public static final String MOD_ID = "susinstantswap";
    private static final Logger LOGGER = LogUtils.getLogger();

    public static SwapConfig CONFIG;
    public static ForgeConfigSpec CONFIG_SPEC;

    public SusInstantSwapMod() {
        LOGGER.info("[SusInstantSwap] v1.1.1-Forge1.20.1 — loading started");

        // ── 步骤1：构建配置 ──
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        CONFIG = new SwapConfig(builder);
        CONFIG_SPEC = builder.build();
        CONFIG.syncToRuntime();  // 将 ForgeConfigSpec 值同步到 Runtime 层
        LOGGER.info("[SusInstantSwap] Config built & synced to Runtime");

        // ── 步骤2：注册配置 ──
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, CONFIG_SPEC);
        LOGGER.info("[SusInstantSwap] Config registered (CLIENT type)");

        // ── 步骤3：注册配置界面 ──
        ModLoadingContext.get().registerExtensionPoint(
                ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory(ForgeConfigScreen::new)
        );
        LOGGER.info("[SusInstantSwap] Config screen registered");

        // ── 步骤4：MOD event bus — 注册按键绑定 ──
        FMLJavaModLoadingContext.get().getModEventBus().register(this);

        // ── 步骤5：FORGE event bus — 注册 tick / tooltip 事件（InstantSwapClient）──
        InstantSwapClient.init(CONFIG);
        LOGGER.info("[SusInstantSwap] Client logic initialized");

        LOGGER.info("[SusInstantSwap] v1.1.1-Forge1.20.1 — loaded");
    }

    /**
     * 注册自定义按键绑定（默认 Left Alt）。
     * 此事件在 MOD event bus 上触发。
     */
    @SubscribeEvent
    public void onRegisterKeyMappings(net.minecraftforge.client.event.RegisterKeyMappingsEvent event) {
        InstantSwapClient.registerKey(event);
        LOGGER.info("[SusInstantSwap] Key binding registered");
    }
}
