package com.susinstantswap.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import com.susinstantswap.config.SwapConfig;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
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
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Sus-InstantSwap Fabric 26.1 客户端逻辑。
 *
 * 从 Fabric 1.21.1 v1.2.0 迁移，适配 MC 26.1 API 差异：
 *   - KeyBindingHelper → KeyMappingHelper（Fabric API 重命名）
 *   - ClickType → ContainerInput（MC 26.1 API 变更）
 *   - ItemStack.EMPTY → HashedStack.EMPTY
 *   - mc.getWindow().getWindow() → mc.getWindow().handle()
 *   - mc.player.playNotifySound → mc.player.playSound
 *
 * 关键架构（与 Fabric 1.21.1 一致）：
 *   - 边缘检测 (pressed/released) 替代轮询
 *   - 双层按键检测：KeyMapping.isDown() + GLFW 物理轮询兜底
 *   - isPlayerInventorySlot / unwrapSlot 替代危险反射
 */
public class InstantSwapClient {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final KeyMapping.Category SWAP_CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("susinstantswap", "main"));
    private static KeyMapping SWAP_KEY;
    private static KeyMapping SWAP_IN_GUI_KEY;
    private static SwapConfig config;

    // ── 状态机 ──
    enum SwapState { IDLE, OPEN }
    private static SwapState state = SwapState.IDLE;
    private static long pressStartTime;
    private static boolean longPressConfirmed;

    // ── 边缘检测 ──
    private static boolean prevDown;
    private static boolean prevGuiSwapDown;

    private static boolean configLogged;

    // ── tooltip 抑制 ──
    private static boolean suppressNextTooltip;
    private static int suppressTooltipFrames;

    // ── 反射缓存 ──
    private static Field hoveredSlotField;
    private static boolean hoveredSlotResolved;

    // ── 改键支持 ──
    private static boolean boundKeyLogged;

    // ═══════════════════════════════════════════════════════════
    // 初始化
    // ═══════════════════════════════════════════════════════════

    public static void init() {
        LOGGER.info("[SusInstantSwap] v1.2.0 初始化客户端交换逻辑 (Fabric 26.1)...");
        config = SwapConfig.get();

        SWAP_KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.susinstantswap.swap",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_ALT,
                SWAP_CATEGORY));
        SWAP_IN_GUI_KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.susinstantswap.swap_in_gui",
                InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(),
                SWAP_CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(InstantSwapClient::onClientTick);
        LOGGER.info("[SusInstantSwap] 按键绑定和 Tick 事件已注册 (Fabric 26.1)");
    }

    // ═══════════════════════════════════════════════════════════
    // 每帧 Tick
    // ═══════════════════════════════════════════════════════════

    private static void onClientTick(Minecraft mc) {
        // ── tooltip 抑制帧计数 ──
        if (suppressTooltipFrames > 0) {
            suppressTooltipFrames--;
            if (suppressTooltipFrames == 0) {
                suppressNextTooltip = false;
            }
        }

        // ── 首次日志 ──
        if (!configLogged) {
            configLogged = true;
            LOGGER.info("[SusInstantSwap] 配置: longPressMode={}, holdThresholdMs={}, soundEnabled={}, guiSwapEnabled={}, mouseReposition={}, debug={}",
                    config.longPressMode, config.holdThresholdMs, config.soundEnabled, config.guiSwapEnabled, config.mouseReposition, config.debug);
        }

        // ── 早期退出 ──
        if (mc.player == null || mc.gameMode == null) {
            state = SwapState.IDLE;
            prevDown = false;
            return;
        }

        boolean creative = mc.gameMode.getPlayerMode().isCreative();
        boolean down = isSwapKeyDown();

        // ══ 边缘检测 ══
        boolean pressed = down && !prevDown;
        boolean released = !down && prevDown;
        prevDown = down;

        // ══ GUI 交换检测 ══
        if (config.guiSwapEnabled) {
            boolean triggerGuiSwap = false;
            if (!SWAP_IN_GUI_KEY.isUnbound()) {
                boolean guiDown = isGuiSwapKeyDown();
                triggerGuiSwap = guiDown && !prevGuiSwapDown;
                prevGuiSwapDown = guiDown;
            } else if (pressed) {
                triggerGuiSwap = true; // GUI 键未指定 → 跟随交换键
            }
            if (triggerGuiSwap && mc.screen instanceof AbstractContainerScreen) {
                if (performGuiSwap(mc, creative)) {
                    debugLog("GUI 交换完成");
                    state = SwapState.IDLE;
                    // SWAP_KEY 触发时：交换后关闭界面
                    if (pressed) {
                        mc.player.closeContainer();
                    }
                    prevDown = isSwapKeyDown(); // 重新同步边缘状态
                    return;
                }
            }
            // 纯 GUI 模式：仅指定了界面键，未指定交换键
            if (triggerGuiSwap && SWAP_KEY.isUnbound() && !pressed) {
                return;
            }
        }

        if (pressed) {
            handleKeyPress(mc, creative);
        }
        if (released) {
            handleKeyRelease(mc, creative);
        }

        // ══ OPEN 状态下的 Tick 操作 ══
        if (state == SwapState.OPEN) {
            handleOpenTick(mc, creative);
        }
    }

    // ── 按键按下处理 ──

    private static void handleKeyPress(Minecraft mc, boolean creative) {
        if (state != SwapState.IDLE) return;
        if (!canInteract(mc)) return;

        // 有界面打开 → 模拟原版物品栏键（E键）行为
        if (mc.screen != null) {
            // 排除聊天和暂停界面（不应被干扰）
            if (mc.screen instanceof ChatScreen || mc.screen instanceof PauseScreen) {
                return;
            }
            simulateVanillaInventoryKey(mc);
            debugLog("按键按下 → 模拟原版E键");
            return;
        }

        // ── 游戏中：打开物品栏，进入 OPEN 状态 ──
        openInventoryAndPositionCursor(mc, creative);
        state = SwapState.OPEN;
        pressStartTime = System.currentTimeMillis();
        longPressConfirmed = false;
        debugLog("按键按下→打开" + (creative ? "创造模式" : "生存模式") + "物品栏");
    }

    // ── 按键松开处理 ──

    private static void handleKeyRelease(Minecraft mc, boolean creative) {
        if (state != SwapState.OPEN) return;

        long elapsed = System.currentTimeMillis() - pressStartTime;

        if (!config.longPressMode || elapsed >= config.holdThresholdMs) {
            // 经典模式：松手即交换
            // 长按模式 + 达到阈值：长按交换
            performSwapAndClose(mc, creative);
            debugLog("松手→交换完成 (" + elapsed + "ms)");
        }
        // 长按模式 + 未达阈值：物品栏保持打开（由下一个按键关闭）
        state = SwapState.IDLE;
    }

    // ── OPEN 状态下的 Tick 操作 ──

    private static void handleOpenTick(Minecraft mc, boolean creative) {
        // 外部关闭（ESC/死亡等）
        if (mc.screen == null) {
            debugLog("外部关闭→重置状态");
            state = SwapState.IDLE;
            return;
        }

        if (!config.longPressMode) {
            // 经典模式：OPEN 状态下不做额外操作
            return;
        }

        // ── 长按模式：阈值追踪 ──
        long elapsed = System.currentTimeMillis() - pressStartTime;

        // 达到阈值 → 标记确认
        if (elapsed >= config.holdThresholdMs && isSwapKeyDown()) {
            longPressConfirmed = true;
        }

        // 键已物理松开 → 判定是否长按交换
        if (!isSwapKeyDown()) {
            if (longPressConfirmed) {
                performSwapAndClose(mc, creative);
                debugLog("长按交换完成（Tick检测，" + elapsed + "ms）");
            }
            state = SwapState.IDLE;
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 按键检测（双层：Fabric API + GLFW 物理轮询兜底）
    // ═══════════════════════════════════════════════════════════

    /**
     * 读取 KeyMapping 的实际运行时绑定键（尊重改键）。
     */
    private static InputConstants.Key getBoundKey() {
        try {
            String keyName = SWAP_KEY.saveString();
            InputConstants.Key result = InputConstants.getKey(keyName);
            if (!boundKeyLogged) {
                boundKeyLogged = true;
                LOGGER.info("[SusInstantSwap] 当前绑定键: {} (默认: {})",
                        keyName, SWAP_KEY.getDefaultKey().getName());
            }
            return result;
        } catch (Exception e) {
            LOGGER.warn("[SusInstantSwap] getBoundKey 解析失败: {}", e.getMessage());
            return SWAP_KEY.getDefaultKey();
        }
    }

    /**
     * 检测交换键是否当前按下（支持改键 + OS 拦截兜底）。
     *
     * 两层检测：
     *   1. SWAP_KEY.isDown() — Fabric 标准 API
     *   2. GLFW 物理轮询 — OS 拦截兜底（Windows Alt 键等）
     */
    private static boolean isSwapKeyDown() {
        if (SWAP_KEY == null) return false;

        // 第1层：Fabric KeyMapping.isDown()
        if (SWAP_KEY.isDown()) return true;

        // 第2层：GLFW 物理轮询
        Minecraft mc = Minecraft.getInstance();
        long handle = mc.getWindow().handle();
        InputConstants.Key key = getBoundKey();
        if (key.getType() == InputConstants.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(handle, key.getValue()) == GLFW.GLFW_PRESS;
        }
        return GLFW.glfwGetKey(handle, key.getValue()) == GLFW.GLFW_PRESS;
    }

    /** 检测 GUI 交换键是否按下（同 isSwapKeyDown 的双保险逻辑）。 */
    private static boolean isGuiSwapKeyDown() {
        if (SWAP_IN_GUI_KEY == null) return false;
        if (SWAP_IN_GUI_KEY.isDown()) return true;
        Minecraft mc = Minecraft.getInstance();
        long handle = mc.getWindow().handle();
        InputConstants.Key key = getGuiBoundKey();
        if (key.getType() == InputConstants.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(handle, key.getValue()) == GLFW.GLFW_PRESS;
        }
        return GLFW.glfwGetKey(handle, key.getValue()) == GLFW.GLFW_PRESS;
    }

    /** 读取 GUI 交换键的运行时绑定键 */
    private static InputConstants.Key getGuiBoundKey() {
        try {
            return InputConstants.getKey(SWAP_IN_GUI_KEY.saveString());
        } catch (Exception e) {
            return SWAP_IN_GUI_KEY.getDefaultKey();
        }
    }

    // ═══════════════════════════════════════════════════════════
    // GUI 交换
    // ═══════════════════════════════════════════════════════════

    private static boolean performGuiSwap(Minecraft mc, boolean creative) {
        if (!(mc.screen instanceof AbstractContainerScreen<?> screen)) return false;

        Slot hovered = getHoveredSlot(screen);
        if (hovered == null || !hovered.hasItem()) {
            debugLog("GUI交换: 悬停槽位无物品");
            return false;
        }

        int hoveredIndex = hovered.index;
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
                return false;
            }
            if (performGuiContainerSwap(screen, hoveredIndex, selectedHotbar)) {
                playSwapSound(mc);
                if (mc.player != null) mc.player.closeContainer();
                debugLog("GUI交换: 槽位" + hoveredIndex + " <-> 快捷栏" + selectedHotbar);
                return true;
            }
            return false;
        }

        return false;
    }

    private static boolean performGuiContainerSwap(AbstractContainerScreen<?> screen, int slotIndex, int hotbar) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) return false;

        int containerId = screen.getMenu().containerId;
        int stateId = screen.getMenu().getStateId();
        Int2ObjectOpenHashMap<HashedStack> changedSlots = new Int2ObjectOpenHashMap<>();
        ServerboundContainerClickPacket packet = new ServerboundContainerClickPacket(
                containerId, stateId, (byte) slotIndex, (byte) hotbar,
                ContainerInput.SWAP, changedSlots, HashedStack.EMPTY);
        mc.getConnection().send(packet);
        return true;
    }

    private static boolean performGuiCreativeSwap(Minecraft mc, CreativeModeInventoryScreen screen) {
        if (mc.gameMode == null) return false;

        Slot hovered = getHoveredSlot(screen);
        if (hovered == null || !hovered.hasItem()) return false;

        int selected = mc.player.getInventory().getSelectedSlot();
        int heldSlotIndex = selected + 36;

        // 路径A：创造标签页物品 → 拿取到快捷栏
        if (!isPlayerInventorySlot(hovered, mc)) {
            ItemStack heldItem = mc.player.getInventory().getItem(selected).copy();
            if (!heldItem.isEmpty()) {
                int freeSlot = findFreeBackpackSlot(mc);
                if (freeSlot >= 0) {
                    // 客户端预测：MC 26.1 的 handleCreativeModeItemAdd 不更新本地
                    mc.player.inventoryMenu.getSlot(freeSlot).set(heldItem);
                    mc.gameMode.handleCreativeModeItemAdd(heldItem, freeSlot);
                }
            }
            ItemStack item = hovered.getItem().copyWithCount(1);
            // 客户端预测：先更新本地物品栏
            mc.player.inventoryMenu.getSlot(heldSlotIndex).set(item);
            mc.gameMode.handleCreativeModeItemAdd(item, heldSlotIndex);
            return true;
        }

        // 路径B：玩家背包/快捷栏 → 双向交换
        Slot realSlot = unwrapSlot(hovered);
        int realIndex = (realSlot != null) ? realSlot.index : hovered.index;

        if (realIndex >= 9 && realIndex <= 44 && realIndex != heldSlotIndex) {
            ItemStack targetItem = mc.player.inventoryMenu.getSlot(realIndex).getItem().copy();
            ItemStack heldItem = mc.player.getInventory().getItem(selected).copy();
            // 客户端预测：MC 26.1 的 handleCreativeModeItemAdd 不更新本地
            mc.player.inventoryMenu.getSlot(heldSlotIndex).set(targetItem);
            mc.player.inventoryMenu.getSlot(realIndex).set(heldItem);
            mc.gameMode.handleCreativeModeItemAdd(targetItem, heldSlotIndex);
            if (!heldItem.isEmpty()) {
                mc.gameMode.handleCreativeModeItemAdd(heldItem, realIndex);
            }
            return true;
        }

        return false;
    }

    // ═══════════════════════════════════════════════════════════
    // 鼠标重定位
    // ═══════════════════════════════════════════════════════════

    private static void openInventoryAndPositionCursor(Minecraft mc, boolean creative) {
        Screen screen = createInventoryScreen(mc, creative);
        if (config.mouseReposition) {
            preMoveCursorToWindowCorner(mc);
        }
        mc.setScreen(screen);
        if (config.mouseReposition) {
            positionCursorToUIBottomRight(mc, screen);
        }
    }

    private static void preMoveCursorToWindowCorner(Minecraft mc) {
        long handle = mc.getWindow().handle();
        GLFW.glfwSetCursorPos(handle, mc.getWindow().getWidth() - 10, mc.getWindow().getHeight() - 10);
    }

    private static void positionCursorToUIBottomRight(Minecraft mc, Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?>)) {
            preMoveCursorToWindowCorner(mc);
            return;
        }

        // 使用 Screen 公开的 width/height 字段
        int imageW = (screen instanceof CreativeModeInventoryScreen) ? 195 : 176;
        int imageH = (screen instanceof CreativeModeInventoryScreen) ? 136 : 166;

        int guiRight = (screen.width + imageW) / 2;
        int guiBottom = (screen.height + imageH) / 2;

        long handle = mc.getWindow().handle();
        double guiScale = mc.getWindow().getGuiScale();
        GLFW.glfwSetCursorPos(handle,
                (int) (guiRight * guiScale) - 4,
                (int) (guiBottom * guiScale) - 4);

        // 抑制接下来 2 帧的 tooltip 渲染
        suppressNextTooltip = true;
        suppressTooltipFrames = 2;
    }

    // ═══════════════════════════════════════════════════════════
    // 悬停槽位反射
    // ═══════════════════════════════════════════════════════════

    private static Slot getHoveredSlot(Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?> cs)) return null;

        if (!hoveredSlotResolved) {
            hoveredSlotResolved = true;
            for (String name : new String[]{"hoveredSlot", "focusedSlot"}) {
                try {
                    hoveredSlotField = AbstractContainerScreen.class.getDeclaredField(name);
                    hoveredSlotField.setAccessible(true);
                    LOGGER.info("[SusInstantSwap] hoveredSlot 字段: {}", name);
                    break;
                } catch (NoSuchFieldException ignored) {}
            }
            if (hoveredSlotField == null) {
                for (Field f : AbstractContainerScreen.class.getDeclaredFields()) {
                    if (!Slot.class.isAssignableFrom(f.getType())) continue;
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    hoveredSlotField = f;
                    LOGGER.info("[SusInstantSwap] hoveredSlot 字段(类型匹配): {}", f.getName());
                    break;
                }
            }
        }
        if (hoveredSlotField == null) return null;
        try {
            return (Slot) hoveredSlotField.get(cs);
        } catch (Exception e) {
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 交换执行
    // ═══════════════════════════════════════════════════════════

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
        if (!(mc.screen instanceof InventoryScreen)) {
            LOGGER.warn("[SusInstantSwap] 生存交换失败: 非物品栏屏幕");
            return;
        }

        Slot hovered = getHoveredSlot(mc.screen);
        if (hovered == null || !hovered.hasItem()) {
            LOGGER.warn("[SusInstantSwap] 生存交换失败: 悬停槽位无物品");
            return;
        }

        int slotIndex = hovered.index;
        int hotbar = mc.player.getInventory().getSelectedSlot();
        int hotbarMenuSlot = hotbar + 36;

        if (!isAllowedMenuSlot(slotIndex) || slotIndex == hotbarMenuSlot) {
            return;
        }

        int containerId = mc.player.inventoryMenu.containerId;
        int stateId = mc.player.inventoryMenu.getStateId();
        Int2ObjectOpenHashMap<HashedStack> changedSlots = new Int2ObjectOpenHashMap<>();
        ServerboundContainerClickPacket packet = new ServerboundContainerClickPacket(
                containerId, stateId, (byte) slotIndex, (byte) hotbar,
                ContainerInput.SWAP, changedSlots, HashedStack.EMPTY);

        if (mc.getConnection() != null) {
            mc.getConnection().send(packet);
            playSwapSound(mc);
            LOGGER.info("[SusInstantSwap] 生存交换: 槽位{} <-> 快捷栏{}", slotIndex, hotbar);
        }
    }

    // ── 创造模式交换 ──

    /**
     * 创造模式交换。
     * 使用 isPlayerInventorySlot / unwrapSlot 避免危险反射（Loom remap 安全）。
     */
    private static void performCreativeSwap(Minecraft mc) {
        if (!(mc.screen instanceof CreativeModeInventoryScreen)) {
            LOGGER.warn("[SusInstantSwap] 创造交换失败: 非创造模式物品栏");
            return;
        }
        if (mc.gameMode == null) {
            LOGGER.warn("[SusInstantSwap] 创造交换失败: gameMode=null");
            return;
        }

        Slot hovered = getHoveredSlot(mc.screen);
        if (hovered == null || !hovered.hasItem()) {
            LOGGER.warn("[SusInstantSwap] 创造交换失败: 悬停槽位无物品");
            return;
        }

        int selected = mc.player.getInventory().getSelectedSlot();
        int heldSlotIndex = selected + 36;
        boolean didSwap = false;

        // ── 路径A：创造标签页物品（不属于玩家背包）→ 拿取一个到快捷栏 ──
        if (!isPlayerInventorySlot(hovered, mc)) {
            ItemStack heldItem = mc.player.getInventory().getItem(selected).copy();
            if (!heldItem.isEmpty()) {
                int freeSlot = findFreeBackpackSlot(mc);
                if (freeSlot >= 0) {
                    // 客户端预测：MC 26.1 的 handleCreativeModeItemAdd 不更新本地
                    mc.player.inventoryMenu.getSlot(freeSlot).set(heldItem);
                    mc.gameMode.handleCreativeModeItemAdd(heldItem, freeSlot);
                }
            }
            ItemStack item = hovered.getItem().copyWithCount(1);
            // 客户端预测：先更新本地物品栏
            mc.player.inventoryMenu.getSlot(heldSlotIndex).set(item);
            mc.gameMode.handleCreativeModeItemAdd(item, heldSlotIndex);
            didSwap = true;
            LOGGER.info("[SusInstantSwap] 创造交换(标签页) -> 快捷栏{}", selected);
            playSwapSound(mc);
            return;
        }

        // ── 解包 SlotWrapper → 获取被包裹的真实槽位 ──
        Slot realSlot = unwrapSlot(hovered);
        int realIndex = (realSlot != null) ? realSlot.index : hovered.index;

        // ── 路径B：玩家背包/快捷栏 → 双向交换 ──
        if (realIndex >= 9 && realIndex <= 44 && realIndex != heldSlotIndex) {
            ItemStack targetItem = mc.player.inventoryMenu.getSlot(realIndex).getItem().copy();
            ItemStack heldItem = mc.player.getInventory().getItem(selected).copy();
            // 客户端预测：MC 26.1 的 handleCreativeModeItemAdd 不更新本地
            mc.player.inventoryMenu.getSlot(heldSlotIndex).set(targetItem);
            mc.player.inventoryMenu.getSlot(realIndex).set(heldItem);
            mc.gameMode.handleCreativeModeItemAdd(targetItem, heldSlotIndex);
            if (!heldItem.isEmpty()) {
                mc.gameMode.handleCreativeModeItemAdd(heldItem, realIndex);
            }
            didSwap = true;
            LOGGER.info("[SusInstantSwap] 创造交换(背包): {} <-> 快捷栏{}", realIndex, selected);
        }

        if (didSwap) {
            playSwapSound(mc);
        }
    }

    /** 判断槽位是否属于玩家背包（安全替代 CONTAINER 引用比较） */
    private static boolean isPlayerInventorySlot(Slot slot, Minecraft mc) {
        return slot.container == mc.player.getInventory();
    }

    /**
     * 通用 Slot 解包（Loom remap 安全）。
     * 遍历 slot 类中所有非 static 的 Slot 类型字段，返回被包装的真实槽位。
     * 非包装类返回 null。
     */
    private static Slot unwrapSlot(Slot slot) {
        if (slot.getClass() == Slot.class) return null;
        try {
            for (Field field : slot.getClass().getDeclaredFields()) {
                if (Slot.class.isAssignableFrom(field.getType())
                        && !Modifier.isStatic(field.getModifiers())) {
                    field.setAccessible(true);
                    return (Slot) field.get(slot);
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static int findFreeBackpackSlot(Minecraft mc) {
        for (int menuSlot = 9; menuSlot <= 35; menuSlot++) {
            if (mc.player.inventoryMenu.getSlot(menuSlot).getItem().isEmpty()) {
                return menuSlot;
            }
        }
        return -1;
    }

    // ═══════════════════════════════════════════════════════════
    // 工具方法
    // ═══════════════════════════════════════════════════════════

    private static boolean isVanillaInventoryKey() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options == null) return false;
        return SWAP_KEY.same(mc.options.keyInventory);
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

    private static boolean canInteract(Minecraft mc) {
        GameType mode = mc.gameMode.getPlayerMode();
        return mode == GameType.SURVIVAL || mode == GameType.CREATIVE || mode == GameType.ADVENTURE;
    }

    private static boolean isInventoryScreen(Screen screen) {
        return screen instanceof InventoryScreen
                || screen instanceof CreativeModeInventoryScreen;
    }

    private static Screen createInventoryScreen(Minecraft mc, boolean creative) {
        if (creative) {
            return new CreativeModeInventoryScreen(
                    mc.player, mc.player.connection.enabledFeatures(), false);
        }
        return new InventoryScreen(mc.player);
    }

    // ═══════════════════════════════════════════════════════════
    // 声音 & 调试
    // ═══════════════════════════════════════════════════════════

    private static void playSwapSound(Minecraft mc) {
        if (!config.soundEnabled || mc.player == null) return;
        mc.player.playSound(SoundEvents.ITEM_PICKUP, 0.8f, 1.0f);
    }

    private static void debugLog(String msg) {
        if (config.debug) {
            LOGGER.info("[SusInstantSwap] {}", msg);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Tooltip 抑制（供 Mixin 调用）
    // ═══════════════════════════════════════════════════════════

    /** 由 Mixin 调用：检查当前帧是否需要抑制 tooltip */
    public static boolean isSuppressNextTooltip() {
        return suppressNextTooltip;
    }

    /** 由 Mixin 调用：消耗抑制标记 */
    public static void consumeTooltipSuppress() {
        suppressNextTooltip = false;
        suppressTooltipFrames = 0;
    }
}
