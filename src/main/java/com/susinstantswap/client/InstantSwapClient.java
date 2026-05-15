package com.susinstantswap.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import com.susinstantswap.api.HandlerRegistry;
import com.susinstantswap.api.InstantSwapHandler;
import com.susinstantswap.api.SwapSlotInfo;
import com.susinstantswap.config.InstantSwapConfig;
import com.susinstantswap.integration.VanillaInventorySwapHandler;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

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
    /** 上次使用的优先 handler 名称，用于检测配置变更并减少重复日志 */
    private static String lastPreferredHandlerName;

    public static void init() {
        LOGGER.info("[SusInstantSwap] 初始化客户端交换逻辑...");
        SWAP_KEY = new KeyMapping("key.susinstantswap.swap",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_ALT,
                "key.categories.susinstantswap");
        NeoForge.EVENT_BUS.register(InstantSwapClient.class);
        LOGGER.info("[SusInstantSwap] 已注册客户端事件到 NeoForge 总线");
        List<InstantSwapHandler> handlers = HandlerRegistry.getHandlers();
        LOGGER.info("[SusInstantSwap] 已注册 {} 个交换处理器", handlers.size());
        for (InstantSwapHandler handler : handlers) {
            LOGGER.info("[SusInstantSwap]   - {}", handler.getName());
        }
        LOGGER.info("[SusInstantSwap] 每次按键时实时读取配置文件，修改后在游戏内即刻生效");
        LOGGER.info("[SusInstantSwap] 可在 Mods 配置界面中修改 preferredHandler，或直接编辑配置文件");
        List<String> handlerNames = handlers.stream()
                .map(InstantSwapHandler::getName)
                .collect(Collectors.toList());
        LOGGER.info("[SusInstantSwap] 可选值: " + String.join(", ", handlerNames));
    }

    /**
     * 根据当前配置解析优先 handler。
     * 每次调用都会重新读取配置文件，确保游戏内修改即刻生效。
     *
     * @return 优先 handler，如果没有配置或配置的名称无效则返回 null
     */
    private static InstantSwapHandler resolvePreferredHandler() {
        String preferredName = InstantSwapConfig.getPreferredHandlerName();

        // 只在配置变更时输出日志
        if (!Objects.equals(preferredName, lastPreferredHandlerName)) {
            lastPreferredHandlerName = preferredName;
            if (preferredName == null) {
                LOGGER.info("[SusInstantSwap] 未配置优先处理器，将按注册顺序自动选择");
            } else {
                LOGGER.info("[SusInstantSwap] 优先处理器配置已改为: '{}'", preferredName);
            }
        }

        if (preferredName == null) {
            return null;
        }

        InstantSwapHandler handler = HandlerRegistry.findByName(preferredName);
        if (handler == null) {
            LOGGER.warn("[SusInstantSwap] 配置了优先处理器 '{}'，但该处理器未注册，将回退到默认行为", preferredName);
        }
        return handler;
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
            // 实时读取配置，确保游戏内修改即刻生效
            InstantSwapHandler preferred = resolvePreferredHandler();
            Screen screen = null;

            // 1. 优先尝试配置的 handler
            if (preferred != null) {
                screen = preferred.onKeyDown(mc);
                if (screen != null) {
                    LOGGER.info("[SusInstantSwap] 优先处理器 '{}' 接管交换流程", preferred.getName());
                    activeHandler = preferred;
                }
            }

            // 2. 优先 handler 未接管，按注册顺序遍历其他 handler
            if (screen == null) {
                for (InstantSwapHandler handler : HandlerRegistry.getHandlers()) {
                    // 跳过已经尝试过的优先 handler
                    if (handler == preferred) continue;
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
                boolean success = false;
                LOGGER.info("[SusInstantSwap] 按键释放，Handler '{}' 执行交换", activeHandler.getName());
                try {
                    success = activeHandler.onKeyUp(mc, mc.screen);
                } catch (Exception e) {
                    LOGGER.error("[SusInstantSwap] Handler '{}' 执行交换时发生异常", activeHandler.getName(), e);
                }

                // 如果第三方 handler 交换失败，尝试用原版逻辑重试
                if (!success && !(activeHandler instanceof VanillaInventorySwapHandler)) {
                    LOGGER.info("[SusInstantSwap] Handler '{}' 交换失败，尝试用原版逻辑重试", activeHandler.getName());

                    // 询问 handler 是否提供修正后的槽位信息
                    SwapSlotInfo fallbackSlots = activeHandler.getFallbackSwapSlots(mc, mc.screen);
                    if (fallbackSlots != null) {
                        LOGGER.info("[SusInstantSwap] 使用 handler 提供的修正槽位: menuSlot={}, hotbar={}",
                                fallbackSlots.menuSlotIndex(), fallbackSlots.hotbarSlotIndex());
                        try {
                            boolean vanillaSuccess = VanillaInventorySwapHandler.performFallbackSwap(
                                    mc, mc.screen, fallbackSlots);
                            if (vanillaSuccess) {
                                LOGGER.info("[SusInstantSwap] 原版 fallback 交换成功 (使用修正槽位)");
                            } else {
                                LOGGER.warn("[SusInstantSwap] 原版 fallback 交换也失败了");
                            }
                        } catch (Exception e) {
                            LOGGER.error("[SusInstantSwap] 原版 fallback 交换时发生异常", e);
                        }
                    } else {
                        VanillaInventorySwapHandler vanillaHandler = new VanillaInventorySwapHandler();
                        try {
                            boolean vanillaSuccess = vanillaHandler.onKeyUp(mc, mc.screen);
                            if (vanillaSuccess) {
                                LOGGER.info("[SusInstantSwap] 原版 fallback 交换成功");
                            } else {
                                LOGGER.warn("[SusInstantSwap] 原版 fallback 交换也失败了");
                            }
                        } catch (Exception e) {
                            LOGGER.error("[SusInstantSwap] 原版 fallback 交换时发生异常", e);
                        }
                    }
                }

                mc.player.closeContainer();
            }
            openedByUs = false;
            activeHandler = null;
        }
    }
}