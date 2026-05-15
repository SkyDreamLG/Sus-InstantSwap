package com.susinstantswap.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import com.susinstantswap.api.HandlerRegistry;
import com.susinstantswap.api.InstantSwapHandler;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

/**
 * SusInstantSwap 客户端核心类。
 * <p>
 * 负责监听交换按键事件，并通过 {@link HandlerRegistry} 将交换流程
 * 分发给已注册的 {@link InstantSwapHandler} 实现。
 * </p>
 *
 * @see InstantSwapHandler
 * @see HandlerRegistry
 */
public class InstantSwapClient {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static KeyMapping SWAP_KEY;
    private static boolean openedByUs;
    /** 当前接管交换流程的 handler */
    private static InstantSwapHandler activeHandler;

    public static void init() {
        LOGGER.info("[SusInstantSwap] 初始化客户端交换逻辑...");
        SWAP_KEY = new KeyMapping("key.susinstantswap.swap",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_ALT,
                "key.categories.susinstantswap");
        NeoForge.EVENT_BUS.register(InstantSwapClient.class);
        LOGGER.info("[SusInstantSwap] 已注册客户端事件到 NeoForge 总线");
        LOGGER.info("[SusInstantSwap] 已发现 {} 个交换处理器", HandlerRegistry.getHandlers().size());
    }

    public static void registerKey(RegisterKeyMappingsEvent event) {
        LOGGER.info("[SusInstantSwap] 注册交换按键到按键映射表");
        event.register(SWAP_KEY);
    }

    private static boolean isKeyPhysicallyDown(Minecraft mc) {
        long handle = mc.getWindow().getWindow();
        InputConstants.Key key = SWAP_KEY.getKey();
        if (key.getType() == InputConstants.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(handle, key.getValue()) == 1;
        }
        return InputConstants.isKeyDown(handle, key.getValue());
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null) {
            return;
        }

        boolean down = isKeyPhysicallyDown(mc);

        // 按键按下：尝试寻找一个 handler 来接管
        if (down && !openedByUs && mc.screen == null) {
            for (InstantSwapHandler handler : HandlerRegistry.getHandlers()) {
                Screen screen = handler.onKeyDown(mc);
                if (screen != null) {
                    LOGGER.info("[SusInstantSwap] Handler '{}' 接管交换流程", handler.getName());
                    mc.setScreen(screen);
                    activeHandler = handler;
                    openedByUs = true;
                    break;
                }
            }
            // 如果没有 handler 接管，也标记 openedByUs 以避免重复尝试
            if (!openedByUs) {
                openedByUs = true;
                LOGGER.debug("[SusInstantSwap] 没有 handler 接管本次按键");
            }
        }

        // 按键释放：交由之前接管的 handler 执行交换
        if (!down && openedByUs) {
            if (activeHandler != null && mc.screen != null) {
                LOGGER.info("[SusInstantSwap] 按键释放，Handler '{}' 执行交换", activeHandler.getName());
                try {
                    activeHandler.onKeyUp(mc, mc.screen);
                } catch (Exception e) {
                    LOGGER.error("[SusInstantSwap] Handler '{}' 执行交换时发生异常", activeHandler.getName(), e);
                }
                mc.player.closeContainer();
            }
            openedByUs = false;
            activeHandler = null;
        }
    }
}