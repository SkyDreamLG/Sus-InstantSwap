package com.susinstantswap;

import com.mojang.logging.LogUtils;
import com.susinstantswap.client.InstantSwapClient;
import com.susinstantswap.api.HandlerRegistry;
import com.susinstantswap.config.InstantSwapConfig;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.slf4j.Logger;

@Mod(value = "susinstantswap", dist = Dist.CLIENT)
public class SusInstantSwapMod {

    public static final String MOD_ID = "susinstantswap";
    private static final Logger LOGGER = LogUtils.getLogger();

    public SusInstantSwapMod(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("[SusInstantSwap] 模组构造器被调用 (开始加载)");
        modEventBus.register(this);
        LOGGER.info("[SusInstantSwap] 已注册模组主类到事件总线");

        if (FMLEnvironment.dist == Dist.CLIENT) {
            LOGGER.info("[SusInstantSwap] 当前环境为客户端，开始初始化");
            // 1. 通过 ServiceLoader 自动扫描所有附属模组的 handler
            HandlerRegistry.discover();
            // 2. 基于已发现的 handler 构建配置规格（为下拉菜单提供选项列表）
            InstantSwapConfig.createSpec();
            // 3. 注册配置文件（客户端配置）
            modContainer.registerConfig(ModConfig.Type.CLIENT, InstantSwapConfig.SPEC);
            LOGGER.info("[SusInstantSwap] 已注册客户端配置文件");
            // 4. 注册配置界面扩展点（启用游戏内 Mods 菜单的 Config 按钮）
            modContainer.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
            LOGGER.info("[SusInstantSwap] 已注册配置界面扩展点");
            // 5. 初始化客户端按键监听
            InstantSwapClient.init();
        } else {
            LOGGER.warn("[SusInstantSwap] 非客户端环境，跳过客户端初始化");
        }
        LOGGER.info("[SusInstantSwap] 模组加载完成");
    }

    @SubscribeEvent
    public void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        LOGGER.info("[SusInstantSwap] 触发按键映射注册事件");
        InstantSwapClient.registerKey(event);
    }
}