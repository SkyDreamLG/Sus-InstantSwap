package com.susinstantswap;

import com.mojang.logging.LogUtils;
import com.susinstantswap.client.InstantSwapClient;
import com.susinstantswap.config.SwapConfig;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.bus.EventBus;
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import org.slf4j.Logger;

@Mod("susinstantswap")
public class SusInstantSwapMod {

    public static final String MOD_ID = "susinstantswap";
    private static final Logger LOGGER = LogUtils.getLogger();

    public static SwapConfig CONFIG;
    public static ForgeConfigSpec CONFIG_SPEC;

    public SusInstantSwapMod(EventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("[SusInstantSwap] v1.1.0-Forge26.1 — constructor called (loading started)");

        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        CONFIG = new SwapConfig(builder);
        CONFIG_SPEC = builder.build();

        modContainer.addConfig(new ModConfig(ModConfig.Type.CLIENT, CONFIG_SPEC, modContainer));
        LOGGER.info("[SusInstantSwap] ForgeConfigSpec registered as CLIENT type");

        RegisterKeyMappingsEvent.BUS.addListener(this::onRegisterKeyMappings);

        InstantSwapClient.init(CONFIG);
        LOGGER.info("[SusInstantSwap] Client logic initialized");

        LOGGER.info("[SusInstantSwap] v1.1.0-Forge26.1 — mod loaded");
    }

    @SubscribeEvent
    public void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        InstantSwapClient.registerKey(event);
    }
}
