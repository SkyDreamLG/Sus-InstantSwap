package com.susinstantswap;

import com.mojang.logging.LogUtils;
import com.susinstantswap.client.InstantSwapClient;
import com.susinstantswap.config.SwapConfig;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.slf4j.Logger;

@Mod(value = "susinstantswap", dist = Dist.CLIENT)
public class SusInstantSwapMod {

    public static final String MOD_ID = "susinstantswap";
    private static final Logger LOGGER = LogUtils.getLogger();

    public SusInstantSwapMod(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("[SusInstantSwap] v1.1.0 — constructor called (loading started)");
        modEventBus.register(this);

        // Build ModConfigSpec (compatible with Configured mod for in-game editing).
        // IMPORTANT: do not call config.get() in the constructor —
        // config values are only available after ModConfigEvent.Loading.
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        SwapConfig config = new SwapConfig(builder);
        ModConfigSpec spec = builder.build();
        modContainer.registerConfig(ModConfig.Type.CLIENT, spec);
        LOGGER.info("[SusInstantSwap] ModConfigSpec registered as CLIENT type");

        InstantSwapClient.init(config);
        LOGGER.info("[SusInstantSwap] Client logic initialized");

        LOGGER.info("[SusInstantSwap] v1.1.0 — mod loaded");
    }

    @SubscribeEvent
    public void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        InstantSwapClient.registerKey(event);
    }
}
