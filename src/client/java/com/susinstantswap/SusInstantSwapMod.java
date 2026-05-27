package com.susinstantswap;

import com.susinstantswap.client.InstantSwapClient;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Environment(EnvType.CLIENT)
public class SusInstantSwapMod implements ClientModInitializer {

    public static final String MOD_ID = "susinstantswap";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        LOGGER.info("[SusInstantSwap] v1.3.0-Fabric1.21.1 — 客户端初始化");
        InstantSwapClient.init();
        LOGGER.info("[SusInstantSwap] v1.3.0-Fabric1.21.1 — 初始化完成");
    }
}
