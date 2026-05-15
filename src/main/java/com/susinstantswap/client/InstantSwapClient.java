package com.susinstantswap.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import com.susinstantswap.api.HandlerRegistry;
import com.susinstantswap.api.InstantSwapHandler;
import com.susinstantswap.config.InstantSwapConfig;
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
 * <p>
 * 优先弹出策略：
 * </p>
 * <ol>
 *   <li>如果配置文件指定了优先 handler 名称且存在，则优先尝试该 handler</li>
 *   <li>如果优先 handler 不处理（返回 null），回退到按注册顺序遍历</li>
 *   <li>如果配置的名称无效，输出警告日志并回退到默认遍历</li>
 *   <li>如果没有任何 handler 处理，按键不会触发任何操作</li>
 * </ol>
 * <p>
 * 配置读取在首次按键时执行，而非 init() 中，因为 NeoForge 配置
 * 在模组构造器之后才加载。
 * </p>
 *
 * @see InstantSwapHandler
 * @see HandlerRegistry
 * @see InstantSwapConfig
 */
public class InstantSwapClient {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static KeyMapping SWAP_KEY;
    private static boolean openedByUs;
    /** 当前接管交换流程的 handler */
    private static InstantSwapHandler activeHandler;
    /** 缓存的优先 handler（null = 无配置或回退到自动），用于减少日志重复 */
    private static InstantSwapHandler cachedPreferredHandler;
    private static boolean preferredHandlerResolved;

    public static void init() {
        LOGGER.info("[SusInstantSwap] 初始化客户端交换逻辑...");
        SWAP_KEY = new KeyMapping("key.susinstantswap.swap",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_ALT,
                "key.categories.susinstantswap");
        NeoForge.EVENT_BUS.register(InstantSwapClient.class);
        LOGGER.info("[SusInstantSwap] 已注册客户端事件到 NeoForge 总线");
        LOGGER.info("[SusInstantSwap] 已发现 {} 个交换处理器", HandlerRegistry.getHandlers().size());
        LOGGER.info("[SusInstantSwap] 优先处理器配置将在首次按键时解析（确保配置文件已加载）");
    }

    /**
     * 解析并缓存优先 handler。
     * 在首次按键时调用（此时配置文件已加载完成）。
     * 之后按键事件直接使用缓存结果。
     */
    private static void resolvePreferredHandler() {
        if (preferredHandlerResolved) return;
        preferredHandlerResolved = true;
        LOGGER.info("[SusInstantSwap] 开始解析优先处理器配置...");

        String preferredName = InstantSwapConfig.getPreferredHandlerName();
        if (preferredName == null) {
            LOGGER.info("[SusInstantSwap] 未配置优先处理器，将按注册顺序自动选择");
            cachedPreferredHandler = null;
            return;
        }

        cachedPreferredHandler = HandlerRegistry.findByName(preferredName);
        if (cachedPreferredHandler == null) {
            LOGGER.warn("[SusInstantSwap] 配置了优先处理器 '{}'，但该处理器未注册，将回退到默认行为", preferredName);
        } else {
            LOGGER.info("[SusInstantSwap] 已设置优先处理器: '{}'", preferredName);
        }
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

        // 首次按键时解析优先处理器配置（此时配置文件已加载）
        if (down && !preferredHandlerResolved) {
            resolvePreferredHandler();
        }

        // 按键按下：尝试寻找一个 handler 来接管
        if (down && !openedByUs && mc.screen == null) {
            Screen screen = null;

            // 1. 优先尝试配置的 handler
            if (cachedPreferredHandler != null) {
                screen = cachedPreferredHandler.onKeyDown(mc);
                if (screen != null) {
                    LOGGER.info("[SusInstantSwap] 优先处理器 '{}' 接管交换流程", cachedPreferredHandler.getName());
                    activeHandler = cachedPreferredHandler;
                }
            }

            // 2. 优先 handler 未接管，按注册顺序遍历其他 handler
            if (screen == null) {
                for (InstantSwapHandler handler : HandlerRegistry.getHandlers()) {
                    // 跳过已经尝试过的优先 handler
                    if (handler == cachedPreferredHandler) continue;
                    screen = handler.onKeyDown(mc);
                    if (screen != null) {
                        LOGGER.info("[SusInstantSwap] Handler '{}' 接管交换流程", handler.getName());
                        activeHandler = handler;
                        break;
                    }
                }
            }

            if (screen != null) {
                mc.setScreen(screen);
                openedByUs = true;
            } else {
                // 没有 handler 接管
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