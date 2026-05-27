package com.susinstantswap.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import com.susinstantswap.SusInstantSwapMod;
import com.susinstantswap.config.SwapConfig;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.network.HashedStack;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderTooltipEvent;
import net.minecraftforge.event.TickEvent;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Sus-InstantSwap Forge 26.1 核心客户端逻辑。
 *
 * FG7 / Forge 26.1 适配：
 *   - 事件总线：静态 BUS 模式（TickEvent.ClientTickEvent.BUS.addListener）
 *   - 构造函数注入：FMLJavaModLoadingContext 通过 SusInstantSwapMod 传入
 *   - EventBus 只支持 Consumer/Predicate（移除了 register(Object)）
 *   - 配置注册：context.registerConfig()
 *   - KeyMapping 注册：RegisterKeyMappingsEvent.BUS.addListener()
 */
public class InstantSwapClient {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final KeyMapping.Category CATEGORY = new KeyMapping.Category(net.minecraft.resources.Identifier.fromNamespaceAndPath("susinstantswap", "key_categories"));
    private static KeyMapping SWAP_KEY;
    private static KeyMapping SWAP_IN_GUI_KEY;

    // ── 状态机 ──
    enum SwapState { IDLE, OPEN }
    private static SwapState state = SwapState.IDLE;
    private static long pressStartTime;
    private static boolean longPressConfirmed;

    // ── 边缘检测 ──
    private static boolean prevDown;
    private static boolean prevGuiSwapDown;

    // ── tooltip 抑制 ──
    private static boolean suppressNextTooltip;
    private static int suppressTooltipFrames;

    private static boolean configLogged;
    private static boolean inMenuContext = false;

    // ── 反射缓存 ──
    private static Field hoveredSlotField;
    private static boolean hoveredSlotResolved;

    // ── 改键支持 ──
    private static boolean boundKeyLogged;

    // ═══════════════════════════════════════════════════════════
    // 初始化
    // ═══════════════════════════════════════════════════════════

    public static void init() {
        LOGGER.info("[SusInstantSwap] v1.3.0 初始化客户端交换逻辑 (Forge 26.1)...");

        SWAP_KEY = new KeyMapping(
                "key.susinstantswap.swap",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_ALT,
                CATEGORY
        );

        SWAP_IN_GUI_KEY = new KeyMapping(
                "key.susinstantswap.swap_in_gui",
                InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(),
                CATEGORY
        );

        // 注册 TickEvent.Post（静态 BUS — Post = tick end）
        TickEvent.ClientTickEvent.Post.BUS.addListener(InstantSwapClient::onClientTick);

        // 注册 RenderTooltipEvent.Pre（CancellableEventBus — Predicate 模式）
        RenderTooltipEvent.Pre.BUS.addListener(InstantSwapClient::onRenderTooltip);

        LOGGER.info("[SusInstantSwap] 事件已注册到静态 BUS (Forge 26.1)");
    }

    /**
     * 注册按键绑定（由 SusInstantSwapMod 通过 RegisterKeyMappingsEvent.BUS 调用）。
     */
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(SWAP_KEY);
        event.register(SWAP_IN_GUI_KEY);
        LOGGER.info("[SusInstantSwap] Key binding registered via BUS");
    }

    // ═══════════════════════════════════════════════════════════
    // 每帧 Tick
    // ═══════════════════════════════════════════════════════════

    private static void onClientTick(TickEvent.ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();

        if (mc.player == null) {
            inMenuContext = false;
        } else if (mc.screen instanceof PauseScreen) {
            inMenuContext = true;
        } else if (mc.screen == null) {
            inMenuContext = false;
        }

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
            // 防御性同步：确保 Runtime 已从 Spec 加载（belt-and-suspenders）
            SusInstantSwapMod.CONFIG.syncToRuntime();
            LOGGER.info("[SusInstantSwap] 配置: longPressMode={}, holdThresholdMs={}, soundEnabled={}, mouseReposition={}, guiSwapEnabled={}, emptySlotSwapEnabled={}, debug={}",
                    SwapConfig.longPressModeRuntime, SwapConfig.holdThresholdMsRuntime,
                    SwapConfig.soundEnabledRuntime, SwapConfig.mouseRepositionRuntime, SwapConfig.guiSwapEnabledRuntime, SwapConfig.emptySlotSwapEnabledRuntime, SwapConfig.debugRuntime);
        }

        // ── 早期退出 ──
        if (mc.player == null || mc.gameMode == null) {
            state = SwapState.IDLE;
            prevDown = false;
            prevGuiSwapDown = false;
            return;
        }

        boolean creative = mc.player.hasInfiniteMaterials();

        // ── 按键检测：双保险 ──
        boolean down = isSwapKeyDown();

        // ══ 边缘检测 ══
        boolean pressed = down && !prevDown;
        boolean released = !down && prevDown;
        prevDown = down;

        // ── GUI 键边缘检测 ──
        if (SwapConfig.guiSwapEnabledRuntime && !SWAP_IN_GUI_KEY.isUnbound()) {
            boolean guiDown = isGuiSwapKeyDown();
            boolean guiPressed = guiDown && !prevGuiSwapDown;
            prevGuiSwapDown = guiDown;
            if (guiPressed && mc.screen instanceof AbstractContainerScreen) {
                if (performGuiSwap(mc, creative)) {
                    debugLog("GUI 交换完成（专用键）");
                    state = SwapState.IDLE;
                    prevDown = isSwapKeyDown();
                    return;
                }
            }
        }

        if (pressed) {
            handleKeyPress(mc, creative);
        }
        if (released) {
            handleKeyRelease(mc, creative);
        }

        if (state == SwapState.OPEN) {
            handleOpenTick(mc, creative);
        }
    }

    // ── 按键按下处理 ──

    private static void handleKeyPress(Minecraft mc, boolean creative) {
        if (mc.screen != null && mc.screen.getFocused() instanceof EditBox) {
            if (SWAP_KEY.same(mc.options.keyInventory)) {
                while (mc.options.keyInventory.consumeClick()) { }
            }
            return;
        }

        if (SwapConfig.guiSwapEnabledRuntime && SWAP_IN_GUI_KEY.isUnbound()
                && mc.screen instanceof AbstractContainerScreen) {
            if (performGuiSwap(mc, creative)) {
                debugLog("GUI 交换完成（跟随即时键）+ 关闭界面");
                state = SwapState.IDLE;
                mc.player.closeContainer();
                return;
            }
        }

        if (state != SwapState.IDLE) return;
        if (!canInteract(mc)) return;
        if (mc.screen != null && isExcludedScreen(mc.screen)) { return; }

        // 有界面打开 → 模拟原版物品栏键（E键）行为
        if (mc.screen != null) {
            if (isExcludedScreen(mc.screen)) {
                return;
            }
            simulateVanillaInventoryKey(mc);
            debugLog("按键按下 → 模拟原版E键");
            return;
        }

        openInventoryAndPositionCursor(mc, creative);
        state = SwapState.OPEN;
        pressStartTime = System.currentTimeMillis();
        longPressConfirmed = false;
        debugLog("按键按下 → 打开" + (creative ? "创造模式" : "生存模式") + "物品栏");
    }

    // ── 按键释放处理 ──

    private static void handleKeyRelease(Minecraft mc, boolean creative) {
        if (state != SwapState.OPEN) return;

        long elapsed = System.currentTimeMillis() - pressStartTime;

        if (!SwapConfig.longPressModeRuntime || elapsed >= SwapConfig.holdThresholdMsRuntime) {
            performSwapAndClose(mc, creative);
            debugLog("松手 → 交换完成 (" + elapsed + "ms)");
        }
        state = SwapState.IDLE;
    }

    // ── OPEN 状态下 Tick 操作 ──

    private static void handleOpenTick(Minecraft mc, boolean creative) {
        if (mc.screen == null) {
            debugLog("外部关闭 → 重置状态");
            state = SwapState.IDLE;
            return;
        }

        if (!SwapConfig.longPressModeRuntime) {
            return;
        }

        long elapsed = System.currentTimeMillis() - pressStartTime;

        if (elapsed >= SwapConfig.holdThresholdMsRuntime && isSwapKeyDown()) {
            longPressConfirmed = true;
        }

        if (!isSwapKeyDown()) {
            if (longPressConfirmed) {
                performSwapAndClose(mc, creative);
                debugLog("长按交换完成（Tick OS 拦截兜底，" + elapsed + "ms）");
            }
            state = SwapState.IDLE;
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Tooltip 抑制（CancellableEventBus — 返回 false 取消渲染）
    // ═══════════════════════════════════════════════════════════

    private static boolean onRenderTooltip(RenderTooltipEvent.Pre event) {
        if (suppressNextTooltip) {
            suppressNextTooltip = false;
            suppressTooltipFrames = 0;
            return false; // 返回 false 取消事件
        }
        return true; // 返回 true 允许事件继续
    }

    // ═══════════════════════════════════════════════════════════
    // 工具方法
    // ═══════════════════════════════════════════════════════════

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

    private static Screen createInventoryScreen(Minecraft mc, boolean creative) {
        if (creative) {
            return new CreativeModeInventoryScreen(
                    mc.player, mc.player.connection.enabledFeatures(), false);
        }
        return new InventoryScreen(mc.player);
    }

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

    private static boolean isSwapKeyDown() {
        if (SWAP_KEY == null) return false;
        if (SWAP_KEY.isDown()) return true;

        Minecraft mc = Minecraft.getInstance();
        long handle = mc.getWindow().handle();
        InputConstants.Key key = getBoundKey();
        if (key.getType() == InputConstants.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(handle, key.getValue()) == GLFW.GLFW_PRESS;
        }
        return GLFW.glfwGetKey(handle, key.getValue()) == GLFW.GLFW_PRESS;
    }

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

    private static boolean isExcludedScreen(Screen screen) {
        if (screen instanceof ChatScreen) return true;
        return inMenuContext;
    }

    private static boolean performGuiSwap(Minecraft mc, boolean creative) {
        if (!(mc.screen instanceof AbstractContainerScreen<?> screen)) return false;

        Slot hovered = getHoveredSlot(screen);
        if (hovered == null || (!hovered.hasItem() && !SwapConfig.emptySlotSwapEnabledRuntime)) {
            debugLog("GUI交换: 悬停槽位无物品, 跳过");
            return false;
        }

        int hoveredIndex = hovered.index;
        int selectedHotbar = mc.player.getInventory().getSelectedSlot();

        if (screen instanceof CreativeModeInventoryScreen) {
            if (!creative) return false;
            if (performGuiCreativeSwap(mc, (CreativeModeInventoryScreen) screen)) {
                playSwapSound(mc);
                return true;
            }
            return false;
        }

        if (screen instanceof InventoryScreen) {
            int hotbarMenuSlot = selectedHotbar + 36;
            if (!isAllowedMenuSlot(hoveredIndex) || hoveredIndex == hotbarMenuSlot) {
                debugLog("GUI交换: 不允许的槽位 (悬停=" + hoveredIndex + ")");
                return false;
            }
            if (performGuiContainerSwap(screen, hoveredIndex, selectedHotbar)) {
                playSwapSound(mc);
                LOGGER.info("[SusInstantSwap] GUI交换: 槽位{} <-> 快捷栏{}", hoveredIndex, selectedHotbar);
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
        Int2ObjectOpenHashMap<HashedStack> changed = new Int2ObjectOpenHashMap<>();
        ServerboundContainerClickPacket packet = new ServerboundContainerClickPacket(
                containerId, stateId, (short) slotIndex, (byte) hotbar,
                ContainerInput.SWAP, changed, HashedStack.EMPTY);
        mc.getConnection().send(packet);
        return true;
    }

    private static boolean performGuiCreativeSwap(Minecraft mc, CreativeModeInventoryScreen screen) {
        if (mc.player == null || mc.gameMode == null) return false;

        Slot hovered = getHoveredSlot(screen);
        if (hovered == null || (!hovered.hasItem() && !SwapConfig.emptySlotSwapEnabledRuntime)) return false;

        int selected = mc.player.getInventory().getSelectedSlot();
        int heldSlotIndex = selected + 36;

        if (!isPlayerInventorySlot(hovered, mc)) {
            // 路径A：创造标签页物品 → 拿取一个到快捷栏
            ItemStack heldItem = mc.player.getInventory().getItem(selected).copy();
            if (!heldItem.isEmpty()) {
                int freeSlot = findFreeBackpackSlot(mc);
                if (freeSlot >= 0) {
                    // 客户端预测：先更新本地
                    mc.player.inventoryMenu.getSlot(freeSlot).set(heldItem);
                    mc.gameMode.handleCreativeModeItemAdd(heldItem, freeSlot);
                }
            }
            ItemStack item = hovered.getItem().copyWithCount(1);
            // 客户端预测：先更新本地
            mc.player.inventoryMenu.getSlot(heldSlotIndex).set(item);
            mc.gameMode.handleCreativeModeItemAdd(item, heldSlotIndex);
            LOGGER.info("[SusInstantSwap] GUI交换(创造标签页) -> 快捷栏{}", selected);
            return true;
        }

        Slot realSlot = unwrapSlot(hovered);
        int realIndex = (realSlot != null) ? realSlot.index : hovered.index;

        if (realIndex >= 9 && realIndex <= 44 && realIndex != heldSlotIndex) {
            // 路径B：玩家背包/快捷栏 → 双向交换（客户端预测先行）
            ItemStack targetItem = mc.player.inventoryMenu.getSlot(realIndex).getItem().copy();
            ItemStack heldItem = mc.player.getInventory().getItem(selected).copy();
            // 客户端预测：先更新本地
            mc.player.inventoryMenu.getSlot(heldSlotIndex).set(targetItem);
            mc.gameMode.handleCreativeModeItemAdd(targetItem, heldSlotIndex);
            if (!heldItem.isEmpty()) {
                mc.player.inventoryMenu.getSlot(realIndex).set(heldItem);
                mc.gameMode.handleCreativeModeItemAdd(heldItem, realIndex);
            }
            LOGGER.info("[SusInstantSwap] GUI交换(创造背包): {} <-> 快捷栏{}", realIndex, selected);
            return true;
        }

        // 快捷栏内部交换：getContainerSlot() 返回 0-8（客户端预测先行）
        int containerSlot = hovered.getContainerSlot();
        if (containerSlot >= 0 && containerSlot <= 8 && containerSlot != selected) {
            int hotbarMenuSlot = containerSlot + 36;
            ItemStack hotbarItem = mc.player.getInventory().getItem(containerSlot).copy();
            ItemStack heldItem = mc.player.getInventory().getItem(selected).copy();
            mc.player.inventoryMenu.getSlot(heldSlotIndex).set(hotbarItem);
            mc.player.inventoryMenu.getSlot(hotbarMenuSlot).set(heldItem);
            mc.gameMode.handleCreativeModeItemAdd(hotbarItem, heldSlotIndex);
            mc.gameMode.handleCreativeModeItemAdd(heldItem, hotbarMenuSlot);
            return true;
        }

        return false;
    }

    // ═══════════════════════════════════════════════════════════
    // 鼠标重定位
    // ═══════════════════════════════════════════════════════════

    private static void openInventoryAndPositionCursor(Minecraft mc, boolean creative) {
        Screen screen = createInventoryScreen(mc, creative);
        if (SwapConfig.mouseRepositionRuntime) {
            preMoveCursorToWindowCorner(mc);
        }
        mc.setScreen(screen);
        if (SwapConfig.mouseRepositionRuntime) {
            positionCursorToUIBottomRight(mc, screen);
        }
    }

    private static void preMoveCursorToWindowCorner(Minecraft mc) {
        long handle = mc.getWindow().handle();
        GLFW.glfwSetCursorPos(handle,
                mc.getWindow().getWidth() - 10,
                mc.getWindow().getHeight() - 10);
    }

    private static void positionCursorToUIBottomRight(Minecraft mc, Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?>)) {
            preMoveCursorToWindowCorner(mc);
            return;
        }

        int imageW = (screen instanceof CreativeModeInventoryScreen) ? 195 : 176;
        int imageH = (screen instanceof CreativeModeInventoryScreen) ? 136 : 166;

        int guiRight = (screen.width + imageW) / 2;
        int guiBottom = (screen.height + imageH) / 2;

        long handle = mc.getWindow().handle();
        double guiScale = mc.getWindow().getGuiScale();
        GLFW.glfwSetCursorPos(handle,
                (int) (guiRight * guiScale) - 4,
                (int) (guiBottom * guiScale) - 4);

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

    private static boolean isAllowedMenuSlot(int menuSlot) {
        return menuSlot >= 9 && menuSlot <= 44;
    }

    private static void performSurvivalSwap(Minecraft mc) {
        if (!(mc.screen instanceof InventoryScreen)) {
            LOGGER.warn("[SusInstantSwap] 生存交换失败: 非物品栏屏幕");
            return;
        }

        Slot hovered = getHoveredSlot(mc.screen);
        if (hovered == null || (!hovered.hasItem() && !SwapConfig.emptySlotSwapEnabledRuntime)) {
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
        Int2ObjectOpenHashMap<HashedStack> changed = new Int2ObjectOpenHashMap<>();
        ServerboundContainerClickPacket packet = new ServerboundContainerClickPacket(
                containerId, stateId, (short) slotIndex, (byte) hotbar,
                ContainerInput.SWAP, changed, HashedStack.EMPTY);

        if (mc.getConnection() != null) {
            mc.getConnection().send(packet);
            playSwapSound(mc);
            LOGGER.info("[SusInstantSwap] 生存交换: 槽位{} <-> 快捷栏{}", slotIndex, hotbar);
        }
    }

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
        if (hovered == null || (!hovered.hasItem() && !SwapConfig.emptySlotSwapEnabledRuntime)) {
            LOGGER.warn("[SusInstantSwap] 创造交换失败: 悬停槽位无物品");
            return;
        }

        int selected = mc.player.getInventory().getSelectedSlot();
        int heldSlotIndex = selected + 36;
        boolean didSwap = false;

        if (!isPlayerInventorySlot(hovered, mc)) {
            // 路径A：创造标签页物品 → 拿取一个到快捷栏
            ItemStack heldItem = mc.player.getInventory().getItem(selected).copy();
            if (!heldItem.isEmpty()) {
                int freeSlot = findFreeBackpackSlot(mc);
                if (freeSlot >= 0) {
                    // 客户端预测：先更新本地
                    mc.player.inventoryMenu.getSlot(freeSlot).set(heldItem);
                    mc.gameMode.handleCreativeModeItemAdd(heldItem, freeSlot);
                }
            }
            ItemStack item = hovered.getItem().copyWithCount(1);
            // 客户端预测：先更新本地
            mc.player.inventoryMenu.getSlot(heldSlotIndex).set(item);
            mc.gameMode.handleCreativeModeItemAdd(item, heldSlotIndex);
            didSwap = true;
            LOGGER.info("[SusInstantSwap] 创造交换(标签页) -> 快捷栏{}", selected);
            playSwapSound(mc);
            return;
        }

        Slot realSlot = unwrapSlot(hovered);
        int realIndex = (realSlot != null) ? realSlot.index : hovered.index;

        if (realIndex >= 9 && realIndex <= 44 && realIndex != heldSlotIndex) {
            // 路径B：玩家背包/快捷栏 → 双向交换（客户端预测先行）
            ItemStack targetItem = mc.player.inventoryMenu.getSlot(realIndex).getItem().copy();
            ItemStack heldItem = mc.player.getInventory().getItem(selected).copy();
            // 客户端预测：先更新本地
            mc.player.inventoryMenu.getSlot(heldSlotIndex).set(targetItem);
            mc.gameMode.handleCreativeModeItemAdd(targetItem, heldSlotIndex);
            if (!heldItem.isEmpty()) {
                mc.player.inventoryMenu.getSlot(realIndex).set(heldItem);
                mc.gameMode.handleCreativeModeItemAdd(heldItem, realIndex);
            }
            didSwap = true;
            LOGGER.info("[SusInstantSwap] 创造交换(背包): {} <-> 快捷栏{}", realIndex, selected);
        }

        // 快捷栏内部交换：getContainerSlot() 返回 0-8（客户端预测先行）
        int containerSlot = hovered.getContainerSlot();
        if (containerSlot >= 0 && containerSlot <= 8 && containerSlot != selected) {
            int hotbarMenuSlot = containerSlot + 36;
            ItemStack hotbarItem = mc.player.getInventory().getItem(containerSlot).copy();
            ItemStack heldItem = mc.player.getInventory().getItem(selected).copy();
            mc.player.inventoryMenu.getSlot(heldSlotIndex).set(hotbarItem);
            mc.player.inventoryMenu.getSlot(hotbarMenuSlot).set(heldItem);
            mc.gameMode.handleCreativeModeItemAdd(hotbarItem, heldSlotIndex);
            mc.gameMode.handleCreativeModeItemAdd(heldItem, hotbarMenuSlot);
            didSwap = true;
        }

        if (didSwap) {
            playSwapSound(mc);
        }
    }

    private static boolean isPlayerInventorySlot(Slot slot, Minecraft mc) {
        return slot.container == mc.player.getInventory();
    }

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
    // 声音 & 调试
    // ═══════════════════════════════════════════════════════════

    private static void playSwapSound(Minecraft mc) {
        if (!SwapConfig.soundEnabledRuntime || mc.player == null) return;
        mc.player.playSound(SoundEvents.ITEM_PICKUP, 0.8f, 1.0f);
    }

    private static void debugLog(String msg) {
        if (SwapConfig.debugRuntime) {
            LOGGER.info("[SusInstantSwap] {}", msg);
        }
    }
}
