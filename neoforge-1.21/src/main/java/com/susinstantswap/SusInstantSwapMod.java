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
        LOGGER.info("[SusInstantSwap] v1.1.0 模组构造器被调用 (开始加载)");
        modEventBus.register(this);

        // 构建 ModConfigSpec（兼容 Configured 模组在游戏内修改配置）
        // ★ 注意：构造器中不能调用 config.get()，配置在 ModConfigEvent.Loading 后才可用
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        SwapConfig config = new SwapConfig(builder);
        ModConfigSpec spec = builder.build();
        modContainer.registerConfig(ModConfig.Type.CLIENT, spec);
        LOGGER.info("[SusInstantSwap] ModConfigSpec 已注册为 CLIENT 类型");

        InstantSwapClient.init(config);
        LOGGER.info("[SusInstantSwap] 客户端逻辑初始化完成");

        LOGGER.info("[SusInstantSwap] v1.1.0 模组加载完成");
    }

    @SubscribeEvent
    public void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        InstantSwapClient.registerKey(event);
    }
}
