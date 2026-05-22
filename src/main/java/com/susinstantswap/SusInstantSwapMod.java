package com.susinstantswap;

import com.mojang.logging.LogUtils;
import com.susinstantswap.client.InstantSwapClient;
import com.susinstantswap.config.SwapConfig;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.common.ForgeConfigSpec;
import org.slf4j.Logger;

@Mod("susinstantswap")
public class SusInstantSwapMod {

    public static final String MOD_ID = "susinstantswap";
    private static final Logger LOGGER = LogUtils.getLogger();

    public static SwapConfig CONFIG;
    public static ForgeConfigSpec CONFIG_SPEC;

    public SusInstantSwapMod() {
        LOGGER.info("[SusInstantSwap] v1.1.0-Forge1.20.1 — constructor called (loading started)");

        // 构建配置
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        CONFIG = new SwapConfig(builder);
        CONFIG_SPEC = builder.build();

        // 注册配置
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, CONFIG_SPEC);
        LOGGER.info("[SusInstantSwap] ForgeConfigSpec registered as CLIENT type");

        // 注册事件监听器
        IEventBus modEventBus = MinecraftForge.EVENT_BUS;
        modEventBus.register(this);

        // 初始化客户端逻辑
        InstantSwapClient.init(CONFIG);
        LOGGER.info("[SusInstantSwap] Client logic initialized");

        LOGGER.info("[SusInstantSwap] v1.1.0-Forge1.20.1 — mod loaded");
    }

    @SubscribeEvent
    public void onRegisterKeyMappings(net.minecraftforge.client.event.RegisterKeyMappingsEvent event) {
        InstantSwapClient.registerKey(event);
    }
}
