package com.susinstantswap;

import com.mojang.logging.LogUtils;
import com.susinstantswap.client.InstantSwapClient;
import com.susinstantswap.config.ForgeConfigScreen;
import com.susinstantswap.config.SwapConfig;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * Su's Instant Swap v2.0 — Forge 26.1 main class.
 * Client-side only mod.
 */
@Mod("susinstantswap")
public class SusInstantSwapMod {

    public static final String MOD_ID = "susinstantswap";
    private static final Logger LOGGER = LogUtils.getLogger();

    public static SwapConfig CONFIG;
    public static ForgeConfigSpec CONFIG_SPEC;

    public SusInstantSwapMod(FMLJavaModLoadingContext context) {
        LOGGER.info("[SusInstantSwap] v2.0 Forge 26.1");

        // 1. Build config spec
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        CONFIG = new SwapConfig(builder);
        CONFIG_SPEC = builder.build();

        // 2. Register config (Forge loads file → updates spec)
        context.registerConfig(ModConfig.Type.CLIENT, CONFIG_SPEC);

        // 3. Sync spec → runtime (after registerConfig, so file values are loaded)
        CONFIG.syncToRuntime();

        // 4. Register config screen (MC 26.1 GUI API)
        ModLoadingContext.get().registerExtensionPoint(
                ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory(ForgeConfigScreen::new)
        );

        // 5. Register key bindings (FG7 pattern)
        RegisterKeyMappingsEvent.BUS.addListener(InstantSwapClient::registerKey);

        // 6. Init client logic
        InstantSwapClient.init();
    }
}
