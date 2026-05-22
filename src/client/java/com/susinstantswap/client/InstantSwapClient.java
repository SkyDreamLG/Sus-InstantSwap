package com.susinstantswap.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import com.susinstantswap.config.SwapConfig;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import java.lang.reflect.Field;

public class InstantSwapClient {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static KeyMapping SWAP_KEY;
    private static SwapConfig config;

    enum SwapState { IDLE, OPEN }
    private static SwapState state = SwapState.IDLE;
    private static long pressStartTime;
    private static boolean longPressConfirmed;
    private static boolean configLogged = false;

    // Reflection: AbstractContainerScreen hovered slot
    private static Field hoveredSlotField = null;
    private static boolean hoveredSlotFieldResolved = false;

    // ── 初始化 ──

    public static void init() {
        LOGGER.info("[SusInstantSwap] v1.1.0 初始化客户端交换逻辑 (Fabric 1.21)...");
        config = SwapConfig.get();

        SWAP_KEY = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.susinstantswap.swap",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_ALT,
                "key.categories.susinstantswap"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(InstantSwapClient::onClientTick);
        LOGGER.info("[SusInstantSwap] 按键绑定和 Tick 事件已注册 (Fabric)");
    }

    // ── 按键检测 ──

    private static boolean isSwapKeyDown() {
        Minecraft mc = Minecraft.getInstance();
        long handle = mc.getWindow().getWindow();
        InputConstants.Key boundKey = SWAP_KEY.getDefaultKey();
        if (boundKey.getType() == InputConstants.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(handle, boundKey.getValue()) == GLFW.GLFW_PRESS;
        }
        return InputConstants.isKeyDown(handle, boundKey.getValue());
    }

    // ── 悬停槽位反射 ──

    private static Slot getHoveredSlot(Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?> cs)) return null;
        if (!hoveredSlotFieldResolved) {
            hoveredSlotFieldResolved = true;
            for (String name : new String[]{"hoveredSlot", "focusedSlot"}) {
                try {
                    Field f = AbstractContainerScreen.class.getDeclaredField(name);
                    f.setAccessible(true);
                    hoveredSlotField = f;
                    LOGGER.info("[SusInstantSwap] 悬停槽位: {}", name);
                    break;
                } catch (NoSuchFieldException ignored) {}
            }
            if (hoveredSlotField == null) {
                for (Field field : AbstractContainerScreen.class.getDeclaredFields()) {
                    if (!Slot.class.isAssignableFrom(field.getType())) continue;
                    int mod = field.getModifiers();
                    if (java.lang.reflect.Modifier.isStatic(mod)) continue;
                    if (java.lang.reflect.Modifier.isPrivate(mod)) continue;
                    field.setAccessible(true);
                    hoveredSlotField = field;
                    LOGGER.info("[SusInstantSwap] 悬停槽位: {} (类型匹配)", field.getName());
                    break;
                }
            }
        }
        if (hoveredSlotField == null) return null;
        try { return (Slot) hoveredSlotField.get(cs); } catch (Exception e) { return null; }
    }

    // ── 每帧 Tick：状态机、外部关闭检测、长按兜底 ──

    private static void onClientTick(Minecraft mc) {
        if (mc.player == null || mc.gameMode == null) {
            state = SwapState.IDLE;
            return;
        }

        if (!configLogged) {
            configLogged = true;
            LOGGER.info("[SusInstantSwap] 配置: longPressMode={}, holdThresholdMs={}, soundEnabled={}, debug={}, mouseReposition={}",
                    config.longPressMode, config.holdThresholdMs, config.soundEnabled, config.debug, config.mouseReposition);
        }

        // 外部关闭检测
        if (state == SwapState.OPEN && mc.screen == null) {
            debugLog("界面被外部关闭，重置状态");
            state = SwapState.IDLE;
        }

        boolean creative = mc.gameMode.hasInfiniteItems();

        // ── OPEN 状态：长按确认 + 按键释放丢失兜底 ──
        if (state == SwapState.OPEN) {
            long elapsed = System.currentTimeMillis() - pressStartTime;
            boolean keyDown = isSwapKeyDown();

            if (config.longPressMode) {
                if (elapsed >= config.holdThresholdMs && keyDown) {
                    longPressConfirmed = true;
                }
                if (!keyDown) {
                    if (longPressConfirmed) {
                        performSwapAndClose(mc, creative);
                        debugLog("Tick: 长按完成（" + elapsed + "ms）");
                    }
                    state = SwapState.IDLE;
                    return;
                }
            } else {
                // 经典模式：Tick 检测释放
                if (!keyDown) {
                    performSwapAndClose(mc, creative);
                    state = SwapState.IDLE;
                    debugLog("Tick: 经典模式交换完成");
                    return;
                }
            }
            return;
        }

        // ── IDLE 状态：按键按下 → 打开物品栏 ──
        boolean keyDown = isSwapKeyDown();

        if (keyDown && state == SwapState.IDLE) {
            if (!canInteract(mc)) return;

            boolean alreadyOnInventory = isInventoryScreen(mc.screen);

            if (alreadyOnInventory) {
                if (!isVanillaInventoryKey()) {
                    mc.setScreen(null);
                }
                debugLog("KeyEvent: 物品栏已打开 -> 关闭物品栏");
                return;
            }

            if (mc.screen instanceof AbstractContainerScreen) {
                mc.player.closeContainer();
                debugLog("KeyEvent: 关闭容器界面");
                return;
            }
            if (mc.screen != null) return;

            openInventoryAndPositionCursor(mc, creative);
            state = SwapState.OPEN;
            pressStartTime = System.currentTimeMillis();
            longPressConfirmed = false;
            debugLog("KeyEvent: 打开物品栏 -> OPEN" + (config.longPressMode ? "" : "（经典模式）"));
        }
    }

    // ── 工具方法 ──

    private static boolean isVanillaInventoryKey() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options == null) return false;
        return SWAP_KEY.same(mc.options.keyInventory);
    }

    private static boolean canInteract(Minecraft mc) {
        GameType mode = mc.gameMode.getPlayerMode();
        return mode == GameType.SURVIVAL || mode == GameType.CREATIVE || mode == GameType.ADVENTURE;
    }

    private static boolean isInventoryScreen(Screen screen) {
        return screen instanceof InventoryScreen || screen instanceof CreativeModeInventoryScreen;
    }

    private static Screen createInventoryScreen(Minecraft mc, boolean creative) {
        if (creative) {
            return new CreativeModeInventoryScreen(mc.player, mc.player.connection.enabledFeatures(), false);
        }
        return new InventoryScreen(mc.player);
    }

    // ── 鼠标重定位 ──

    private static void openInventoryAndPositionCursor(Minecraft mc, boolean creative) {
        Screen screen = createInventoryScreen(mc, creative);
        preMoveCursorToWindowCorner(mc);
        mc.setScreen(screen);
        positionCursorIfEnabled(mc, screen);
    }

    private static void preMoveCursorToWindowCorner(Minecraft mc) {
        if (!config.mouseReposition) return;
        long handle = mc.getWindow().getWindow();
        int width = mc.getWindow().getWidth();
        int height = mc.getWindow().getHeight();
        GLFW.glfwSetCursorPos(handle, width - 10, height - 10);
    }

    private static void positionCursorIfEnabled(Minecraft mc, Screen screen) {
        if (!config.mouseReposition) return;
        positionCursorToUIBottomRight(mc, screen);
    }

    private static void positionCursorToUIBottomRight(Minecraft mc, Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?> cs)) {
            long handle = mc.getWindow().getWindow();
            GLFW.glfwSetCursorPos(handle, mc.getWindow().getWidth() - 10, mc.getWindow().getHeight() - 10);
            return;
        }
        try {
            // Use reflection for protected fields in Mojang mappings
            Field leftPos = AbstractContainerScreen.class.getDeclaredField("leftPos");
            Field topPos = AbstractContainerScreen.class.getDeclaredField("topPos");
            Field imageWidth = AbstractContainerScreen.class.getDeclaredField("imageWidth");
            Field imageHeight = AbstractContainerScreen.class.getDeclaredField("imageHeight");
            leftPos.setAccessible(true);
            topPos.setAccessible(true);
            imageWidth.setAccessible(true);
            imageHeight.setAccessible(true);

            long handle = mc.getWindow().getWindow();
            double guiScale = mc.getWindow().getGuiScale();
            int guiRight = leftPos.getInt(cs) + imageWidth.getInt(cs);
            int guiBottom = topPos.getInt(cs) + imageHeight.getInt(cs);
            int px = (int) (guiRight * guiScale) - 5;
            int py = (int) (guiBottom * guiScale) - 5;
            GLFW.glfwSetCursorPos(handle, px, py);
        } catch (Exception ignored) {}
    }

    // ── 交换执行 ──

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

    // ── 生存模式交换 ──

    private static void performSurvivalSwap(Minecraft mc) {
        Screen screen = mc.screen;
        if (!(screen instanceof InventoryScreen)) {
            LOGGER.warn("[SusInstantSwap] 生存交换失败: screen={}", screen);
            return;
        }

        Slot hovered = getHoveredSlot(screen);
        if (hovered == null || !hovered.hasItem()) {
            LOGGER.warn("[SusInstantSwap] 生存交换失败: 无悬停物品");
            return;
        }

        int slotIndex = hovered.index;
        int hotbar = mc.player.getInventory().selected;
        int hotbarMenuSlot = hotbar + 36;

        if (!isAllowedMenuSlot(slotIndex) || slotIndex == hotbarMenuSlot) {
            LOGGER.warn("[SusInstantSwap] 生存交换失败: 不允许的槽位 (悬停={}, 快捷栏={})", slotIndex, hotbarMenuSlot);
            return;
        }

        int containerId = mc.player.inventoryMenu.containerId;
        int stateId = mc.player.inventoryMenu.getStateId();
        Int2ObjectOpenHashMap<ItemStack> changedSlots = new Int2ObjectOpenHashMap<>();
        ServerboundContainerClickPacket packet = new ServerboundContainerClickPacket(
                containerId, stateId, slotIndex, hotbar,
                ClickType.SWAP, ItemStack.EMPTY, changedSlots);

        if (mc.getConnection() != null) {
            mc.getConnection().send(packet);
            playSwapSound(mc);
            LOGGER.info("[SusInstantSwap] 生存模式交换完成: 槽位{} <-> 快捷栏{}", slotIndex, hotbar);
        }
    }

    // ── 创造模式交换 ──

    private static void performCreativeSwap(Minecraft mc) {
        Screen screen = mc.screen;
        if (!(screen instanceof CreativeModeInventoryScreen)) {
            LOGGER.warn("[SusInstantSwap] 创造交换失败: screen={}", screen);
            return;
        }
        if (mc.gameMode == null) {
            LOGGER.warn("[SusInstantSwap] 创造交换失败: gameMode=null");
            return;
        }

        Slot hovered = getHoveredSlot(screen);
        if (hovered == null || !hovered.hasItem()) {
            LOGGER.warn("[SusInstantSwap] 创造交换失败: 无悬停物品");
            return;
        }

        int selected = mc.player.getInventory().selected;
        int heldSlotIndex = selected + 36;
        boolean didSwap = false;

        // Check CONTAINER via reflection
        Object creativeContainer = getCreativeContainer();
        if (creativeContainer != null && hovered.container == creativeContainer) {
            ItemStack heldItem = mc.player.getInventory().getItem(selected).copy();
            ItemStack item = hovered.getItem().copyWithCount(1);

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

        } else {
            int containerSlot = hovered.getContainerSlot();
            if (containerSlot >= 0 && containerSlot <= 8) {
                int hotbarMenuSlot = containerSlot + 36;
                if (hotbarMenuSlot != heldSlotIndex) {
                    ItemStack hotbarItem = mc.player.getInventory().getItem(containerSlot).copy();
                    ItemStack heldItem = mc.player.getInventory().getItem(selected).copy();
                    mc.gameMode.handleCreativeModeItemAdd(hotbarItem, heldSlotIndex);
                    mc.gameMode.handleCreativeModeItemAdd(heldItem, hotbarMenuSlot);
                    didSwap = true;
                    LOGGER.info("[SusInstantSwap] 创造模式交换: 快捷栏槽位{} <-> 快捷栏{}", containerSlot, selected);
                }
            }
        }

        if (didSwap) {
            playSwapSound(mc);
        }
    }

    private static Object getCreativeContainer() {
        try {
            Field field = CreativeModeInventoryScreen.class.getDeclaredField("CONTAINER");
            field.setAccessible(true);
            return field.get(null);
        } catch (Exception e) { return null; }
    }

    private static int findFreeBackpackSlot(Minecraft mc) {
        for (int menuSlot = 9; menuSlot <= 35; menuSlot++) {
            if (mc.player.inventoryMenu.getSlot(menuSlot).getItem().isEmpty()) return menuSlot;
        }
        return -1;
    }

    // ── 声音 ──

    private static void playSwapSound(Minecraft mc) {
        if (!config.soundEnabled || mc.player == null) return;
        mc.player.playSound(SoundEvents.ITEM_PICKUP, 0.8f, 1.0f);
    }

    // ── 调试 ──

    private static void debugLog(String msg) {
        if (config.debug) LOGGER.info("[SusInstantSwap] {}", msg);
    }
}
