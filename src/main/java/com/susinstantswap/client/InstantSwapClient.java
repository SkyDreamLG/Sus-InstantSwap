package com.susinstantswap.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import com.susinstantswap.config.SwapConfig;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderTooltipEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import java.util.Objects;

public class InstantSwapClient {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static KeyMapping SWAP_KEY;
    private static SwapConfig config;

    /** 状态机：IDLE → OPEN → IDLE */
    enum SwapState { IDLE, OPEN }
    private static SwapState state = SwapState.IDLE;
    private static long pressStartTime;
    /** 长按已确认（elapsed ≥ threshold 且 key 仍按着），用于区分 OS 拦截释放场景 */
    private static boolean longPressConfirmed;
    /** 光标重定位后抑制下一帧 tooltip 渲染，避免闪烁。 */
    private static boolean suppressNextTooltip;
    /** tooltip 抑制剩余帧数，防止 flag 未消费时泄露到后续帧。 */
    private static int suppressTooltipFrames;
    private static boolean configLogged = false;

    // ── 初始化 ──

    public static void init(SwapConfig cfg) {
        LOGGER.info("[SusInstantSwap] v1.1.1 初始化客户端交换逻辑...");
        config = cfg;
        SWAP_KEY = new KeyMapping("key.susinstantswap.swap",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_ALT,
                "key.categories.susinstantswap");
        NeoForge.EVENT_BUS.register(InstantSwapClient.class);
    }

    public static void registerKey(RegisterKeyMappingsEvent event) {
        event.register(SWAP_KEY);
    }

    // ── 每帧 Tick：外部关闭检测、OS 拦截兜底、config 日志 ──

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();

        // 清理已过期的 tooltip 抑制标记（防止泄露到正常游戏画面）
        if (suppressTooltipFrames > 0) {
            suppressTooltipFrames--;
            if (suppressTooltipFrames == 0) {
                suppressNextTooltip = false;
            }
        }

        // 首次 tick 输出配置值（此时配置已加载完成）
        if (!configLogged) {
            configLogged = true;
            LOGGER.info("[SusInstantSwap] 配置: longPressMode={}, holdThresholdMs={}, soundEnabled={}, debug={}, mouseReposition={}",
                    config.longPressMode.get(), config.holdThresholdMs.get(),
                    config.soundEnabled.get(), config.debug.get(),
                    config.mouseReposition.get());
        }

        if (mc.player == null || mc.gameMode == null) {
            state = SwapState.IDLE;
            return;
        }

        // 外部关闭检测：ESC/死亡等导致界面被关闭
        if (state == SwapState.OPEN && mc.screen == null) {
            debugLog("界面被外部关闭，重置状态");
            state = SwapState.IDLE;
        }

        boolean creative = mc.gameMode.hasInfiniteItems();

        // ── OPEN 状态：长按确认 + 释放丢失兜底 ──
        if (state == SwapState.OPEN) {
            long elapsed = System.currentTimeMillis() - pressStartTime;
            boolean keyDown = isSwapKeyDown();

            if (config.longPressMode.get()) {
                // 达到阈值且键仍按着 → 标记为长按
                if (elapsed >= config.holdThresholdMs.get() && keyDown) {
                    longPressConfirmed = true;
                }

                if (!keyDown) {
                    // 键已松开（正常 RELEASE 或 OS 拦截）
                    if (longPressConfirmed) {
                        performSwapAndClose(mc, creative);
                        debugLog("Tick: 长按完成（" + elapsed + "ms）");
                    }
                    // 短按：物品栏已开，无额外操作
                    state = SwapState.IDLE;
                    return;
                }
            } else {
                // 经典模式：键松开即交换
                if (!keyDown) {
                    performSwapAndClose(mc, creative);
                    state = SwapState.IDLE;
                    debugLog("Tick: 经典模式交换完成");
                    return;
                }
            }

        }
    }

    // ── 按键事件：事件驱动，不丢短按 ──

    @SubscribeEvent
    public static void onKeyInput(InputEvent.Key event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null) return;

        // 只处理我们绑定的按键（尊重用户在控制菜单的改键）
        InputConstants.Key boundKey = SWAP_KEY.getKey();
        if (boundKey.getType() != InputConstants.Type.KEYSYM) return;
        if (event.getKey() != boundKey.getValue()) return;
        int action = event.getAction();
        if (action != GLFW.GLFW_PRESS && action != GLFW.GLFW_RELEASE) return;

        boolean keyDown = (action == GLFW.GLFW_PRESS);
        boolean creative = mc.gameMode.hasInfiniteItems();

        // ── 检测哪个按键被触发 ──
        boolean isSwapKey = isSwapKeyEvent(event);
        boolean isGuiSwapKey = isGuiSwapKeyEvent(event);

        // ── GUI 交换 (仅响应 PRESS，与状态无关) ──
        if (keyDown && config.guiSwapEnabled.get()) {
            // 判断是否为有效的 GUI 交换触发键：
            //   - SWAP_IN_GUI_KEY 已指定 → 用它的按键
            //   - SWAP_IN_GUI_KEY 未指定 → 跟随 SWAP_KEY
            boolean triggerGuiSwap = isGuiSwapKey
                    || (isSwapKey && isKeyUnassigned(SWAP_IN_GUI_KEY));

            if (triggerGuiSwap && mc.screen instanceof AbstractContainerScreen) {
                if (performGuiSwap(mc, creative)) {
                    debugLog("KeyEvent: 界面中更换完成");
                    state = SwapState.IDLE;
                    return;
                }
            }

            // 纯界面模式：只指定了界面中更换键，未指定即时交换键
            if (isGuiSwapKey && isKeyUnassigned(SWAP_KEY)) {
                return;
            }
        }

        // ── 正常的即时交换逻辑 ──
        // 只处理 SWAP_KEY 触发的按键事件（尊重用户在控制菜单的改键）
        if (!isSwapKeyEvent(event)) return;

        if (keyDown) {
            // ── 按键按下：立即打开物品栏 ──
            if (!canInteract(mc)) return;

            boolean alreadyOnInventory = isInventoryScreen(mc.screen);

            if (state == SwapState.IDLE) {
                // 物品栏已打开：按键只关闭物品栏，不进入交换流程
                if (alreadyOnInventory) {
                    if (!isVanillaInventoryKey()) {
                        mc.setScreen(null);
                    }
                    debugLog("KeyEvent: 物品栏已打开 → 关闭物品栏");
                    return;
                }

                // 容器界面（箱子、木桶、工作台等）→ 关闭之（模拟原版 E 键行为）
                // 非容器界面（聊天栏、模组菜单、暂停等）→ 不响应，避免干扰打字
                if (mc.screen instanceof AbstractContainerScreen) {
                    mc.player.closeContainer();
                    debugLog("KeyEvent: 关闭容器界面（模拟原版E键）");
                    return;
                }
                if (mc.screen != null) {
                    return;
                }

                // 立即打开物品栏（长短按模式均如此）
                openInventoryAndPositionCursor(mc, creative);
                state = SwapState.OPEN;
                pressStartTime = System.currentTimeMillis();
                longPressConfirmed = false;
                debugLog("KeyEvent: 打开物品栏 → OPEN" +
                        (config.longPressMode.get() ? "" : "（经典模式）"));
            }
        } else {
            // ── 按键松开：判定长短按 ──
            if (state == SwapState.OPEN) {
                long elapsed = System.currentTimeMillis() - pressStartTime;
                if (!config.longPressMode.get()
                        || elapsed >= config.holdThresholdMs.get()) {
                    // 经典模式 或 长按模式阈值已到 → 执行交换
                    performSwapAndClose(mc, creative);
                    debugLog("KeyEvent: 交换完成 (" + elapsed + "ms)");
                }
                // 短按：物品栏已打开，无需额外操作
                state = SwapState.IDLE;
            }
        }
    }

    // ── 工具方法 ──

    /**
     * 光标重定位后抑制首帧 tooltip 渲染，消除闪烁。
     */
    @SubscribeEvent
    public static void onRenderTooltip(RenderTooltipEvent.Pre event) {
        if (suppressNextTooltip) {
            event.setCanceled(true);
            suppressNextTooltip = false;
            suppressTooltipFrames = 0;
        }
    }

    /**
     * 通过 GLFW 轮询检查交换按键是否物理按下。
     * 用于检测 OS 拦截 RELEASE 事件的情况（如 Windows 的 Alt 键激活菜单栏）。
     */
    private static boolean isSwapKeyDown() {
        Minecraft mc = Minecraft.getInstance();
        InputConstants.Key boundKey = SWAP_KEY.getKey();
        if (boundKey.getType() != InputConstants.Type.KEYSYM) return false;
        return GLFW.glfwGetKey(mc.getWindow().getWindow(), boundKey.getValue()) == GLFW.GLFW_PRESS;
    }

    /**
     * 检查当前绑定的交换键是否与原版"打开物品栏"键冲突。
     * 若冲突（如都绑定了 E），短按切换由原版处理，我们不重复操作。
     */
    private static boolean isVanillaInventoryKey() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options == null) return false;
        return SWAP_KEY.same(mc.options.keyInventory);
    }

    private static boolean canInteract(Minecraft mc) {
        GameType mode = mc.gameMode.getPlayerMode();
        return mode == GameType.SURVIVAL || mode == GameType.CREATIVE || mode == GameType.ADVENTURE;
    }

    /** 判断当前 Screen 是否为物品栏界面（原版或创造） */
    private static boolean isInventoryScreen(Screen screen) {
        return screen instanceof InventoryScreen
                || screen instanceof CreativeModeInventoryScreen;
    }

    /** 根据游戏模式创建对应的物品栏 Screen */
    private static Screen createInventoryScreen(Minecraft mc, boolean creative) {
        if (creative) {
            return new CreativeModeInventoryScreen(
                    mc.player, mc.player.connection.enabledFeatures(), false);
        }
        return new InventoryScreen(mc.player);
    }

    /**
     * 打开物品栏并定位光标到 UI 右下角（受 mouseReposition 配置控制）。
     * 注：鼠标闪烁问题暂未解决。
     */
    private static void openInventoryAndPositionCursor(Minecraft mc, boolean creative) {
        Screen screen = createInventoryScreen(mc, creative);
        // 先移动鼠标到窗口右下角（安全区域），防止界面打开时槽位高亮闪烁
        preMoveCursorToWindowCorner(mc);
        mc.setScreen(screen);
        // 再精细定位到 UI 右下角（同一帧内，tooltip 抑制逻辑保留）
        positionCursorIfEnabled(mc, screen);
    }

    /**
     * 界面打开前预先将鼠标移到窗口右下角（安全区域，不包含槽位）。
     * 注：鼠标闪烁问题暂未解决，此预移动无法完全消除首帧高亮。
     */
    private static void preMoveCursorToWindowCorner(Minecraft mc) {
        if (!config.mouseReposition.get()) return;
        long handle = mc.getWindow().getWindow();
        int width = mc.getWindow().getWidth();
        int height = mc.getWindow().getHeight();
        GLFW.glfwSetCursorPos(handle, width - 10, height - 10);
    }

    /** 受 mouseReposition 配置控制的定位 + 清除 hoveredSlot 防止 tooltip 闪烁 */
    private static void positionCursorIfEnabled(Minecraft mc, Screen screen) {
        if (!config.mouseReposition.get()) return;
        positionCursorToUIBottomRight(mc, screen);
    }

    /** 移动鼠标光标到物品栏 UI 右下角，并清除悬停槽位防止 tooltip 闪烁 */
    private static void positionCursorToUIBottomRight(Minecraft mc, Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
            long handle = mc.getWindow().getWindow();
            int width = mc.getWindow().getWidth();
            int height = mc.getWindow().getHeight();
            GLFW.glfwSetCursorPos(handle, width - 10, height - 10);
            return;
        }

        long handle = mc.getWindow().getWindow();
        double guiScale = mc.getWindow().getGuiScale();

        int guiRight = containerScreen.getGuiLeft() + containerScreen.getXSize();
        int guiBottom = containerScreen.getGuiTop() + containerScreen.getYSize();
        int pixelX = (int) (guiRight * guiScale) - 5;
        int pixelY = (int) (guiBottom * guiScale) - 5;

        GLFW.glfwSetCursorPos(handle, pixelX, pixelY);
        // 抑制下一帧 tooltip：光标瞬移会触发 slot 悬停，导致闪烁
        suppressNextTooltip = true;
        // 2 帧后自动清除，防止标记泄露到正常画面
        suppressTooltipFrames = 2;
    }

    /** 松开按键后执行交换并关闭界面 */
    private static void performSwapAndClose(Minecraft mc, boolean creative) {
        if (creative) {
            performCreativeSwap(mc);
        } else {
            performSurvivalSwap(mc);
        }
        if (mc.player != null) {
            mc.player.closeContainer();
        }
    }

    // ── 生存模式交换 ──

    private static boolean isAllowedMenuSlot(int menuSlot) {
        return menuSlot >= 9 && menuSlot <= 44;
    }

    private static void performSurvivalSwap(Minecraft mc) {
        if (!(mc.screen instanceof InventoryScreen inventoryScreen)) {
            LOGGER.warn("[SusInstantSwap] 生存模式交换失败: 当前屏幕不是物品栏");
            return;
        }

        Slot hoveredSlot = inventoryScreen.getSlotUnderMouse();
        if (hoveredSlot == null || !hoveredSlot.hasItem()) {
            LOGGER.warn("[SusInstantSwap] 生存模式交换失败: 悬停槽位无物品");
            return;
        }

        int hoveredIndex = hoveredSlot.index;
        int selectedHotbar = mc.player.getInventory().selected;
        int hotbarMenuSlot = selectedHotbar + 36;

        if (!isAllowedMenuSlot(hoveredIndex) || hoveredIndex == hotbarMenuSlot) {
            LOGGER.warn("[SusInstantSwap] 生存模式交换失败: 不允许的槽位 (悬停={}, 快捷栏={})",
                    hoveredIndex, hotbarMenuSlot);
            return;
        }

        int containerId = mc.player.inventoryMenu.containerId;
        int stateId = mc.player.inventoryMenu.getStateId();
        Int2ObjectOpenHashMap<ItemStack> changedSlots = new Int2ObjectOpenHashMap<>();
        ServerboundContainerClickPacket packet = new ServerboundContainerClickPacket(
                containerId, stateId, hoveredIndex, selectedHotbar,
                ClickType.SWAP, ItemStack.EMPTY, changedSlots);
        if (mc.getConnection() == null) {
            LOGGER.warn("[SusInstantSwap] 生存模式交换失败: 连接为null");
            return;
        }
        mc.getConnection().send(packet);

        playSwapSound(mc);
        LOGGER.info("[SusInstantSwap] 生存模式交换完成: 槽位{} <-> 快捷栏{}",
                hoveredIndex, selectedHotbar);
    }

    // ── 创造模式交换 ──

    private static void performCreativeSwap(Minecraft mc) {
        if (!(mc.screen instanceof CreativeModeInventoryScreen creativeScreen)) {
            LOGGER.warn("[SusInstantSwap] 创造模式交换失败: 当前屏幕不是创造模式物品栏");
            return;
        }

        // 统一 gameMode null 检查，下文中可安全调用
        if (mc.gameMode == null) {
            LOGGER.warn("[SusInstantSwap] 创造模式交换失败: gameMode 为 null");
            return;
        }

        Slot hoveredSlot = creativeScreen.getSlotUnderMouse();
        if (hoveredSlot == null || !hoveredSlot.hasItem()) {
            LOGGER.warn("[SusInstantSwap] 创造模式交换失败: 悬停槽位无物品");
            return;
        }

        int selected = mc.player.getInventory().selected;
        int heldSlotIndex = selected + 36;
        boolean didSwap = false;

        if (hoveredSlot.container == CreativeModeInventoryScreen.CONTAINER) {
            ItemStack heldItem = mc.player.getInventory().getItem(selected).copy();
            ItemStack item = hoveredSlot.getItem().copyWithCount(1);

            // 如果手持有物品，优先放入背包（槽位 9-35），背包满时才销毁
            if (!heldItem.isEmpty()) {
                int freeBackpackSlot = findFreeBackpackSlot(mc);
                if (freeBackpackSlot >= 0) {
                    mc.gameMode.handleCreativeModeItemAdd(heldItem, freeBackpackSlot);
                    LOGGER.info("[SusInstantSwap] 创造模式: 手持物品已移入背包槽位{}", freeBackpackSlot);
                } else {
                    LOGGER.info("[SusInstantSwap] 创造模式: 背包已满，手持物品将被销毁");
                }
            }

            mc.gameMode.handleCreativeModeItemAdd(item, heldSlotIndex);
            didSwap = true;
            LOGGER.info("[SusInstantSwap] 创造模式交换: 从创造物品栏拿取物品到快捷栏{}", selected);

        } else if (hoveredSlot instanceof CreativeModeInventoryScreen.SlotWrapper wrapper) {
            int targetMenuSlot = wrapper.target.index;
            if (isAllowedMenuSlot(targetMenuSlot) && targetMenuSlot != heldSlotIndex) {
                ItemStack targetItem = mc.player.inventoryMenu.getSlot(targetMenuSlot).getItem().copy();
                ItemStack heldItem = mc.player.inventoryMenu.getSlot(heldSlotIndex).getItem().copy();
                mc.gameMode.handleCreativeModeItemAdd(targetItem, heldSlotIndex);
                mc.gameMode.handleCreativeModeItemAdd(heldItem, targetMenuSlot);
                didSwap = true;
                LOGGER.info("[SusInstantSwap] 创造模式交换: 背包槽位{} <-> 快捷栏{}",
                        targetMenuSlot, selected);
            }
        } else {
            int containerSlot = hoveredSlot.getContainerSlot();
            if (containerSlot >= 0 && containerSlot <= 8) {
                int hotbarMenuSlot = containerSlot + 36;
                if (hotbarMenuSlot != heldSlotIndex) {
                    ItemStack hotbarItem = mc.player.getInventory().getItem(containerSlot).copy();
                    ItemStack heldItem = mc.player.getInventory().getItem(selected).copy();
                    mc.gameMode.handleCreativeModeItemAdd(hotbarItem, heldSlotIndex);
                    mc.gameMode.handleCreativeModeItemAdd(heldItem, hotbarMenuSlot);
                    didSwap = true;
                    LOGGER.info("[SusInstantSwap] 创造模式交换: 快捷栏槽位{} <-> 快捷栏{}",
                            containerSlot, selected);
                }
            }
        }

        if (didSwap) {
            playSwapSound(mc);
        }
    }

    /**
     * 在背包（inventoryMenu 槽位 9-35，对应玩家背包）中找到第一个空槽位。
     * 返回 inventoryMenu 中的槽位索引（9-35），找不到返回 -1。
     */
    private static int findFreeBackpackSlot(Minecraft mc) {
        // inventoryMenu 槽位布局：0=制作格, 1-4=盔甲, 5=副手,
        // 9-35=背包, 36-44=快捷栏
        for (int menuSlot = 9; menuSlot <= 35; menuSlot++) {
            if (mc.player.inventoryMenu.getSlot(menuSlot).getItem().isEmpty()) {
                return menuSlot;
            }
        }
        return -1;
    }

    // ── 声音 ──

    private static void playSwapSound(Minecraft mc) {
        if (!config.soundEnabled.get() || mc.player == null) return;
        mc.player.playNotifySound(SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.8f, 1.0f);
    }

    // ── 调试日志 ──

    private static void debugLog(String msg) {
        if (config.debug.get()) {
            LOGGER.info("[SusInstantSwap] {}", msg);
        }
    }
}
