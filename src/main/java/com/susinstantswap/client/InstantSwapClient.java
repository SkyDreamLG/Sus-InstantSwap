package com.susinstantswap.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import com.susinstantswap.config.SwapConfig;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderTooltipEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

public class InstantSwapClient {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final KeyMapping.Category SWAP_CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("susinstantswap", "main"));
    private static KeyMapping SWAP_KEY;
    private static KeyMapping SWAP_IN_GUI_KEY;
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
        LOGGER.info("[SusInstantSwap] v1.2.0 初始化客户端交换逻辑...");
        config = cfg;
        SWAP_KEY = new KeyMapping("key.susinstantswap.swap",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_ALT,
                SWAP_CATEGORY);
        SWAP_IN_GUI_KEY = new KeyMapping("key.susinstantswap.swap_in_gui",
                InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(),
                SWAP_CATEGORY);
        NeoForge.EVENT_BUS.register(InstantSwapClient.class);
    }

    public static void registerKey(RegisterKeyMappingsEvent event) {
        event.register(SWAP_KEY);
        event.register(SWAP_IN_GUI_KEY);
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
            LOGGER.info("[SusInstantSwap] 配置: longPressMode={}, holdThresholdMs={}, soundEnabled={}, guiSwapEnabled={}, debug={}, mouseReposition={}",
                    config.longPressMode.get(), config.holdThresholdMs.get(),
                    config.soundEnabled.get(), config.guiSwapEnabled.get(),
                    config.debug.get(), config.mouseReposition.get());
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

        boolean creative = mc.gameMode.getPlayerMode().isCreative();

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

        int action = event.getAction();
        if (action != GLFW.GLFW_PRESS && action != GLFW.GLFW_RELEASE) return;

        boolean keyDown = (action == GLFW.GLFW_PRESS);
        boolean creative = mc.gameMode.getPlayerMode().isCreative();

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
                    // SWAP_KEY 触发时：交换后关闭界面
                    // SWAP_IN_GUI_KEY 触发时：仅交换不关闭
                    if (isSwapKey) {
                        mc.player.closeContainer();
                    }
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
        InputConstants.Key boundKey = SWAP_KEY.getKey();
        if (boundKey.getType() != InputConstants.Type.KEYSYM) return;
        if (event.getKey() != boundKey.getValue()) return;

        if (keyDown) {
            if (!canInteract(mc)) return;

            if (state == SwapState.IDLE) {
                // 有界面打开 → 模拟原版物品栏键（E键）行为
                if (mc.screen != null) {
                    // 排除聊天和暂停界面（不应被干扰）
                    if (mc.screen instanceof ChatScreen || mc.screen instanceof PauseScreen) {
                        return;
                    }
                    simulateVanillaInventoryKey(mc);
                    debugLog("KeyEvent: 模拟原版E键");
                    return;
                }

                // 无界面 → 打开物品栏
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
        return GLFW.glfwGetKey(mc.getWindow().handle(), boundKey.getValue()) == GLFW.GLFW_PRESS;
    }

    /** 判断按键事件是否匹配 SWAP_KEY */
    private static boolean isSwapKeyEvent(InputEvent.Key event) {
        InputConstants.Key boundKey = SWAP_KEY.getKey();
        return boundKey.getType() == InputConstants.Type.KEYSYM
                && event.getKey() == boundKey.getValue();
    }

    /** 判断按键事件是否匹配 SWAP_IN_GUI_KEY */
    private static boolean isGuiSwapKeyEvent(InputEvent.Key event) {
        InputConstants.Key boundKey = SWAP_IN_GUI_KEY.getKey();
        return boundKey.getType() == InputConstants.Type.KEYSYM
                && event.getKey() == boundKey.getValue();
    }

    /** 检查按键映射是否未指定（玩家在控制菜单中未绑定任何键） */
    private static boolean isKeyUnassigned(KeyMapping mapping) {
        return mapping.isUnbound();
    }

    /**
     * 模拟原版物品栏键（E键）行为。
     * <p>
     * MC 26.1 中 Screen.keyPressed 接受 KeyEvent 参数，无法直接模拟按键。
     * 改用 removed() 方式：先让当前屏幕自行清理（EMI 配方界面会在此恢复物品栏父界面），
     * 如果屏幕未被替换，则关闭容器/物品栏。
     */
    private static void simulateVanillaInventoryKey(Minecraft mc) {
        Screen prevScreen = mc.screen;
        prevScreen.removed();
        if (mc.screen == prevScreen) {
            mc.player.closeContainer();
        }
    }

    // ── 界面中更换 ──

    /**
     * 在玩家物品栏中执行即时交换并关闭界面。
     * 仅支持玩家物品栏和创造模式物品栏；其他容器（箱子/熔炉等）不参与 GUI 交换。
     */
    private static boolean performGuiSwap(Minecraft mc, boolean creative) {
        if (!(mc.screen instanceof AbstractContainerScreen<?> screen)) return false;

        Slot hoveredSlot = screen.getSlotUnderMouse();
        if (hoveredSlot == null || !hoveredSlot.hasItem()) {
            debugLog("GUI交换: 悬停槽位无物品, 跳过");
            return false;
        }

        int hoveredIndex = hoveredSlot.index;
        int selectedHotbar = mc.player.getInventory().getSelectedSlot();

        // ── 创造模式物品栏 ──
        if (screen instanceof CreativeModeInventoryScreen) {
            if (!creative) return false;
            if (performGuiCreativeSwap(mc, (CreativeModeInventoryScreen) screen)) {
                playSwapSound(mc);
                if (mc.player != null) mc.player.closeContainer();
                return true;
            }
            return false;
        }

        // ── 生存/冒险物品栏：仅允许背包+快捷栏（9-44）──
        if (screen instanceof InventoryScreen) {
            int hotbarMenuSlot = selectedHotbar + 36;
            if (!isAllowedMenuSlot(hoveredIndex) || hoveredIndex == hotbarMenuSlot) {
                debugLog("GUI交换: 不允许的槽位 (悬停=" + hoveredIndex + ")");
                return false;
            }
            if (performGuiContainerSwap(screen, hoveredIndex, selectedHotbar)) {
                playSwapSound(mc);
                if (mc.player != null) mc.player.closeContainer();
                debugLog("GUI交换完成: 槽位" + hoveredIndex + " <-> 快捷栏" + selectedHotbar);
                return true;
            }
            return false;
        }

        // 其他容器：不支持 GUI 交换
        return false;
    }

    /**
     * 发送 ContainerInput.SWAP 数据包，将悬停槽位与热键栏槽位交换。
     * 使用 screen.getMenu() 获取菜单信息，兼容玩家物品栏和创造模式物品栏。
     */
    private static boolean performGuiContainerSwap(AbstractContainerScreen<?> screen, int hoveredIndex, int selectedHotbar) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) return false;

        int hotbarMenuSlot = selectedHotbar + 36;

        // 客户端预测：立即交换物品（保证显示即时更新）
        ItemStack hoveredItem = screen.getMenu().getSlot(hoveredIndex).getItem().copy();
        ItemStack hotbarItem = screen.getMenu().getSlot(hotbarMenuSlot).getItem().copy();
        screen.getMenu().getSlot(hoveredIndex).set(hotbarItem);
        screen.getMenu().getSlot(hotbarMenuSlot).set(hoveredItem);

        int containerId = screen.getMenu().containerId;
        int stateId = screen.getMenu().getStateId();
        Int2ObjectOpenHashMap<HashedStack> changedSlots = new Int2ObjectOpenHashMap<>();
        ServerboundContainerClickPacket packet = new ServerboundContainerClickPacket(
                containerId, stateId, (byte) hoveredIndex, (byte) selectedHotbar,
                ContainerInput.SWAP, changedSlots, HashedStack.EMPTY);
        mc.getConnection().send(packet);
        return true;
    }

    /** 创造模式物品栏的 GUI 交换 */
    private static boolean performGuiCreativeSwap(Minecraft mc, CreativeModeInventoryScreen creativeScreen) {
        if (mc.gameMode == null) return false;

        Slot hoveredSlot = creativeScreen.getSlotUnderMouse();
        if (hoveredSlot == null || !hoveredSlot.hasItem()) return false;

        int selected = mc.player.getInventory().getSelectedSlot();
        int heldSlotIndex = selected + 36;

        if (hoveredSlot.container == CreativeModeInventoryScreen.CONTAINER) {
            // 从创造物品栏拿取物品
            ItemStack heldItem = mc.player.getInventory().getItem(selected).copy();
            ItemStack item = hoveredSlot.getItem().copyWithCount(1);

            if (!heldItem.isEmpty()) {
                int freeBackpackSlot = findFreeBackpackSlot(mc);
                if (freeBackpackSlot >= 0) {
                    mc.player.inventoryMenu.getSlot(freeBackpackSlot).set(heldItem);
                    mc.gameMode.handleCreativeModeItemAdd(heldItem, freeBackpackSlot);
                }
            }
            mc.player.inventoryMenu.getSlot(heldSlotIndex).set(item);
            mc.gameMode.handleCreativeModeItemAdd(item, heldSlotIndex);
            return true;
        }

        if (hoveredSlot instanceof CreativeModeInventoryScreen.SlotWrapper wrapper) {
            int targetMenuSlot = wrapper.target.index;
            if (isAllowedMenuSlot(targetMenuSlot) && targetMenuSlot != heldSlotIndex) {
                // 使用 screen 的菜单读取槽位物品（screen.getMenu() 即为当前显示的容器菜单）
                ItemStack targetItem = creativeScreen.getMenu().getSlot(targetMenuSlot).getItem().copy();
                ItemStack heldItem = creativeScreen.getMenu().getSlot(heldSlotIndex).getItem().copy();
                // 客户端预测：先更新本地物品栏，再发数据包
                creativeScreen.getMenu().getSlot(heldSlotIndex).set(targetItem);
                creativeScreen.getMenu().getSlot(targetMenuSlot).set(heldItem);
                mc.gameMode.handleCreativeModeItemAdd(targetItem, heldSlotIndex);
                mc.gameMode.handleCreativeModeItemAdd(heldItem, targetMenuSlot);
                return true;
            }
            return false;
        }

        // 快捷栏内部交换
        int containerSlot = hoveredSlot.getContainerSlot();
        if (containerSlot >= 0 && containerSlot <= 8 && containerSlot != selected) {
            int hotbarMenuSlot = containerSlot + 36;
            ItemStack hotbarItem = mc.player.getInventory().getItem(containerSlot).copy();
            ItemStack heldItem = mc.player.getInventory().getItem(selected).copy();
            // 客户端预测：先更新本地物品栏，再发数据包
            mc.player.inventoryMenu.getSlot(heldSlotIndex).set(hotbarItem);
            mc.player.inventoryMenu.getSlot(hotbarMenuSlot).set(heldItem);
            mc.gameMode.handleCreativeModeItemAdd(hotbarItem, heldSlotIndex);
            mc.gameMode.handleCreativeModeItemAdd(heldItem, hotbarMenuSlot);
            return true;
        }

        return false;
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
        long handle = mc.getWindow().handle();
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
            long handle = mc.getWindow().handle();
            int width = mc.getWindow().getWidth();
            int height = mc.getWindow().getHeight();
            GLFW.glfwSetCursorPos(handle, width - 10, height - 10);
            return;
        }

        long handle = mc.getWindow().handle();
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
        int selectedHotbar = mc.player.getInventory().getSelectedSlot();
        int hotbarMenuSlot = selectedHotbar + 36;

        if (!isAllowedMenuSlot(hoveredIndex) || hoveredIndex == hotbarMenuSlot) {
            LOGGER.warn("[SusInstantSwap] 生存模式交换失败: 不允许的槽位 (悬停={}, 快捷栏={})",
                    hoveredIndex, hotbarMenuSlot);
            return;
        }

        // 客户端预测：读取交换前的物品，立即在客户端交换（保证显示即时更新）
        ItemStack hoveredItem = mc.player.inventoryMenu.getSlot(hoveredIndex).getItem().copy();
        ItemStack hotbarItem = mc.player.inventoryMenu.getSlot(hotbarMenuSlot).getItem().copy();
        mc.player.inventoryMenu.getSlot(hoveredIndex).set(hotbarItem);
        mc.player.inventoryMenu.getSlot(hotbarMenuSlot).set(hoveredItem);

        int containerId = mc.player.inventoryMenu.containerId;
        int stateId = mc.player.inventoryMenu.getStateId();
        Int2ObjectOpenHashMap<HashedStack> changedSlots = new Int2ObjectOpenHashMap<>();
        ServerboundContainerClickPacket packet = new ServerboundContainerClickPacket(
                containerId, stateId, (byte) hoveredIndex, (byte) selectedHotbar,
                ContainerInput.SWAP, changedSlots, HashedStack.EMPTY);
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

        int selected = mc.player.getInventory().getSelectedSlot();
        int heldSlotIndex = selected + 36;
        boolean didSwap = false;

        if (hoveredSlot.container == CreativeModeInventoryScreen.CONTAINER) {
            ItemStack heldItem = mc.player.getInventory().getItem(selected).copy();
            ItemStack item = hoveredSlot.getItem().copyWithCount(1);

            // 如果手持有物品，优先放入背包（槽位 9-35），背包满时才销毁
            if (!heldItem.isEmpty()) {
                int freeBackpackSlot = findFreeBackpackSlot(mc);
                if (freeBackpackSlot >= 0) {
                    // 客户端预测：先更新本地物品栏，再发数据包
                    mc.player.inventoryMenu.getSlot(freeBackpackSlot).set(heldItem);
                    mc.gameMode.handleCreativeModeItemAdd(heldItem, freeBackpackSlot);
                    LOGGER.info("[SusInstantSwap] 创造模式: 手持物品已移入背包槽位{}", freeBackpackSlot);
                } else {
                    LOGGER.info("[SusInstantSwap] 创造模式: 背包已满，手持物品将被销毁");
                }
            }

            // 客户端预测：先更新本地物品栏，再发数据包
            mc.player.inventoryMenu.getSlot(heldSlotIndex).set(item);
            mc.gameMode.handleCreativeModeItemAdd(item, heldSlotIndex);
            didSwap = true;
            LOGGER.info("[SusInstantSwap] 创造模式交换: 从创造物品栏拿取物品到快捷栏{}", selected);

        } else if (hoveredSlot instanceof CreativeModeInventoryScreen.SlotWrapper wrapper) {
            int targetMenuSlot = wrapper.target.index;
            if (isAllowedMenuSlot(targetMenuSlot) && targetMenuSlot != heldSlotIndex) {
                ItemStack targetItem = mc.player.inventoryMenu.getSlot(targetMenuSlot).getItem().copy();
                ItemStack heldItem = mc.player.inventoryMenu.getSlot(heldSlotIndex).getItem().copy();
                // 客户端预测：先更新本地物品栏，再发数据包
                mc.player.inventoryMenu.getSlot(heldSlotIndex).set(targetItem);
                mc.player.inventoryMenu.getSlot(targetMenuSlot).set(heldItem);
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
                    // 客户端预测：先更新本地物品栏，再发数据包
                    mc.player.inventoryMenu.getSlot(heldSlotIndex).set(hotbarItem);
                    mc.player.inventoryMenu.getSlot(hotbarMenuSlot).set(heldItem);
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
        mc.player.playSound(SoundEvents.ITEM_PICKUP, 0.8f, 1.0f);
    }

    // ── 调试日志 ──

    private static void debugLog(String msg) {
        if (config.debug.get()) {
            LOGGER.info("[SusInstantSwap] {}", msg);
        }
    }
}
