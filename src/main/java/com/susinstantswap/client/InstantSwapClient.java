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
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import java.util.List;
import java.util.Objects;

/**
 * SusInstantSwap 客户端核心类。
 * <p>
 * 负责监听交换按键事件，按配置决定优先使用原版还是第三方 handler。
 * </p>
 *
 * <h3>按键策略</h3>
 * <ol>
 *   <li><b>按键按下</b>：若当前无屏幕打开：
 *       <ul>
 *         <li>如果 {@code preferThirdParty=true}：通过
 *             {@link HandlerRegistry#getEquippedThirdPartyHandler} 查找穿戴的第三方 handler，
 *             找到则使用，否则回退到原版 handler</li>
 *         <li>如果 {@code preferThirdParty=false}：始终使用原版 handler</li>
 *       </ul>
 *       记录实际打开屏幕的 handler。</li>
 *   <li><b>按键释放</b>：由记录的 handler 执行交换。失败时：
 *       <ul>
 *         <li>第三方 handler 失败 → 原版 fallback（优先用 getFallbackSwapSlots 修正槽位）</li>
 *         <li>原版 handler 失败 → 检查穿戴的第三方 handler，有则尝试</li>
 *       </ul></li>
 * </ol>
 * <p>
 * 配置读取在每次按键时实时执行，而非 init() 中。
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
    /** 当前接管交换流程的 handler（按下时确定，释放时使用） */
    private static InstantSwapHandler activeHandler;
    /** 上次使用的 preferThirdParty 值，用于检测配置变更并减少重复日志 */
    private static Boolean lastPreferThirdParty;

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
            String type = handler instanceof VanillaInventorySwapHandler ? "原版" : "第三方";
            LOGGER.info("[SusInstantSwap]   - [{}] {}", type, handler.getName());
        }
        LOGGER.info("[SusInstantSwap] 每次按键时实时读取配置文件，修改后在游戏内即刻生效");
        LOGGER.info("[SusInstantSwap] 可在 Mods 配置界面中修改 preferThirdParty，或直接编辑配置文件");
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

    /**
     * 尝试用给定的 handler 打开 GUI，返回打开的 Screen（或 null）。
     */
    private static Screen tryOpenScreen(InstantSwapHandler handler, Minecraft mc) {
        try {
            return handler.onKeyDown(mc);
        } catch (Exception e) {
            LOGGER.error("[SusInstantSwap] Handler '{}' onKeyDown 异常", handler.getName(), e);
            return null;
        }
    }

    /**
     * 执行 handler 的交换逻辑，失败时按规则 fallback。
     */
    private static void executeSwap(InstantSwapHandler handler, Minecraft mc, Screen screen) {
        LOGGER.info("[SusInstantSwap] 按键释放，Handler '{}' 执行交换", handler.getName());
        boolean success = false;
        try {
            success = handler.onKeyUp(mc, screen);
        } catch (Exception e) {
            LOGGER.error("[SusInstantSwap] Handler '{}' 执行交换时发生异常", handler.getName(), e);
        }

        if (!success && !(handler instanceof VanillaInventorySwapHandler)) {
            // 第三方 handler 失败，fallback 到原版
            LOGGER.info("[SusInstantSwap] Handler '{}' 交换失败，尝试用原版逻辑重试", handler.getName());
            fallbackToVanilla(handler, mc, screen);
        } else if (!success) {
            // 原版 handler 失败，检查是否有穿戴的第三方 handler 可 fallback
            InstantSwapHandler equipped = HandlerRegistry.getEquippedThirdPartyHandler(mc);
            if (equipped != null) {
                LOGGER.info("[SusInstantSwap] 原版交换失败，尝试用第三方 '{}' 重试", equipped.getName());
                try {
                    equipped.onKeyUp(mc, screen);
                } catch (Exception e) {
                    LOGGER.error("[SusInstantSwap] 第三方 fallback 异常", e);
                }
            } else {
                LOGGER.warn("[SusInstantSwap] 原版 fallback 交换也失败了");
            }
        }
    }

    /**
     * 原版 fallback：优先用 handler 提供的修正槽位，否则走原版默认逻辑。
     */
    private static void fallbackToVanilla(InstantSwapHandler failedHandler, Minecraft mc, Screen screen) {
        SwapSlotInfo fallbackSlots = failedHandler.getFallbackSwapSlots(mc, screen);
        if (fallbackSlots != null) {
            LOGGER.info("[SusInstantSwap] 使用 handler 提供的修正槽位: menuSlot={}, hotbar={}",
                    fallbackSlots.menuSlotIndex(), fallbackSlots.hotbarSlotIndex());
            try {
                VanillaInventorySwapHandler.performFallbackSwap(mc, screen, fallbackSlots);
            } catch (Exception e) {
                LOGGER.error("[SusInstantSwap] 原版 fallback 交换时发生异常", e);
            }
        } else {
            VanillaInventorySwapHandler vanillaHandler = HandlerRegistry.getVanillaHandler();
            if (vanillaHandler != null) {
                try {
                    boolean vanillaSuccess = vanillaHandler.onKeyUp(mc, screen);
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
    }

    /**
     * 玩家加入世界时发送适配器安装提醒。
     * 仅在没有第三方适配器注册时提醒。
     */
    @SubscribeEvent
    public static void onPlayerLoggedIn(ClientPlayerNetworkEvent.LoggingIn event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        mc.player.displayClientMessage(
                Component.translatable("susinstantswap.message.adapter_reminder"), false);
        LOGGER.info("[SusInstantSwap] 已向玩家发送适配器安装提醒");
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null) {
            return;
        }

        boolean down = isKeyPhysicallyDown(mc);

        // 检测配置变更
        boolean preferThirdParty = InstantSwapConfig.isPreferThirdParty();
        if (!Objects.equals(preferThirdParty, lastPreferThirdParty)) {
            lastPreferThirdParty = preferThirdParty;
            LOGGER.info("[SusInstantSwap] preferThirdParty 配置已改为: {}", preferThirdParty);
        }

        // 按键按下：按配置顺序尝试打开 GUI
        if (down && !openedByUs && mc.screen == null) {
            InstantSwapHandler vanilla = HandlerRegistry.getVanillaHandler();

            InstantSwapHandler screenOpener = null;
            Screen screen = null;

            if (preferThirdParty) {
                // preferThirdParty=true：查找穿戴的第三方 handler
                InstantSwapHandler equipped = HandlerRegistry.getEquippedThirdPartyHandler(mc);
                if (equipped != null) {
                    screen = tryOpenScreen(equipped, mc);
                    if (screen != null) {
                        screenOpener = equipped;
                        LOGGER.info("[SusInstantSwap] 检测到背包已穿戴 '{}'，由该 handler 接管交换流程",
                                equipped.getName());
                    }
                }
                // 无穿戴第三方或第三方不处理 → 回退原版
                if (screen == null && vanilla != null) {
                    screen = tryOpenScreen(vanilla, mc);
                    if (screen != null) {
                        screenOpener = vanilla;
                        LOGGER.info("[SusInstantSwap] 无穿戴背包，使用原版 handler '{}'", vanilla.getName());
                    }
                }
            } else {
                // preferThirdParty=false：始终使用原版
                if (vanilla != null) {
                    screen = tryOpenScreen(vanilla, mc);
                    if (screen != null) {
                        screenOpener = vanilla;
                        LOGGER.info("[SusInstantSwap] Handler '{}' 打开 GUI", vanilla.getName());
                    }
                }
            }

            if (screen != null) {
                mc.setScreen(screen);
                openedByUs = true;
                activeHandler = screenOpener;
            } else {
                openedByUs = true;
                activeHandler = null;
                LOGGER.debug("[SusInstantSwap] 没有 handler 打开 GUI");
            }
        }

        // 按键释放：由记录的 handler 执行交换
        if (!down && openedByUs) {
            Screen currentScreen = mc.screen;
            if (currentScreen != null && activeHandler != null) {
                executeSwap(activeHandler, mc, currentScreen);
                mc.player.closeContainer();
            } else if (currentScreen != null) {
                // 没有 activeHandler（如被外部调用打开屏幕），回退到原版
                VanillaInventorySwapHandler vh = HandlerRegistry.getVanillaHandler();
                if (vh != null) {
                    LOGGER.info("[SusInstantSwap] 未记录 handler，回退到原版交换逻辑");
                    try {
                        boolean ok = vh.onKeyUp(mc, currentScreen);
                        if (ok) {
                            LOGGER.info("[SusInstantSwap] 原版 fallback 交换成功");
                        } else {
                            LOGGER.warn("[SusInstantSwap] 原版 fallback 交换也失败了");
                        }
                    } catch (Exception e) {
                        LOGGER.error("[SusInstantSwap] 原版 fallback 交换时发生异常", e);
                    }
                }
                mc.player.closeContainer();
            }
            openedByUs = false;
            activeHandler = null;
        }
    }
}