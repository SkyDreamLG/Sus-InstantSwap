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
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import org.slf4j.Logger;

@Mod(value = "susinstantswap", dist = Dist.CLIENT)
public class SusInstantSwapMod {

    public static final String MOD_ID = "susinstantswap";
    private static final Logger LOGGER = LogUtils.getLogger();

    // 保存配置实例供其他类访问
    public static SwapConfig CONFIG;
    public static ModConfigSpec CONFIG_SPEC;

    public SusInstantSwapMod(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("[SusInstantSwap] v1.2.0 — constructor called (loading started)");

        // 构建配置
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        CONFIG = new SwapConfig(builder);
        CONFIG_SPEC = builder.build();

        // 注册配置
        modContainer.registerConfig(ModConfig.Type.CLIENT, CONFIG_SPEC);
        LOGGER.info("[SusInstantSwap] ModConfigSpec registered as CLIENT type");

        // 【关键修复】注册配置屏幕扩展点
        registerConfigScreen(modContainer);

        // 注册事件监听器
        modEventBus.register(this);

        // 初始化客户端逻辑（注意：配置值此时可能还未完全加载）
        InstantSwapClient.init(CONFIG);
        LOGGER.info("[SusInstantSwap] Client logic initialized");

        LOGGER.info("[SusInstantSwap] v1.2.0 — mod loaded");
    }

    /**
     * 注册配置屏幕扩展点，让游戏能显示配置界面
     */
    private void registerConfigScreen(ModContainer modContainer) {
        // 确保只在客户端注册（虽然类已经是 Dist.CLIENT，但这是个好习惯）
        if (FMLEnvironment.dist == Dist.CLIENT) {
            modContainer.registerExtensionPoint(IConfigScreenFactory.class,
                    (modContainer1, screen) -> new ConfigurationScreen(modContainer1, screen));
            LOGGER.info("[SusInstantSwap] Config screen extension point registered");
        }
    }

    @SubscribeEvent
    public void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        InstantSwapClient.registerKey(event);
    }
}