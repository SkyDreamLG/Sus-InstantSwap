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

public class InstantSwapClient {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final KeyMapping.Category SWAP_CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("susinstantswap", "main"));
    private static KeyMapping SWAP_KEY;
    private static KeyMapping SWAP_IN_GUI_KEY;
    private static SwapConfig config;

    enum SwapState { IDLE, OPEN }
    private static SwapState state = SwapState.IDLE;
    private static long pressStartTime;
    private static boolean longPressConfirmed;
    /** Edge detection: track previous frame key state */
    private static boolean swapKeyWasDown;
    private static boolean guiSwapKeyWasDown;
    private static boolean configLogged = false;

    // Reflective fields (Fabric has no AT)
    private static Field hoveredSlotField = null;
    private static boolean hoveredSlotFieldResolved = false;
    private static Field leftPosField = null;
    private static Field topPosField = null;
    private static Field imageWidthField = null;
    private static Field imageHeightField = null;
    private static boolean guiFieldsResolved = false;

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

    // ── 每帧 Tick ──

    private static void onClientTick(Minecraft mc) {
        if (mc.player == null || mc.gameMode == null) {
            state = SwapState.IDLE;
            return;
        }

        if (!configLogged) {
            configLogged = true;
            LOGGER.info("[SusInstantSwap] 配置: longPressMode={}, holdThresholdMs={}, soundEnabled={}, guiSwapEnabled={}, debug={}, mouseReposition={}",
                    config.longPressMode, config.holdThresholdMs, config.soundEnabled, config.guiSwapEnabled, config.debug, config.mouseReposition);
        }

        // External close detection
        if (state == SwapState.OPEN && mc.screen == null) {
            debugLog("界面被外部关闭，重置状态");
            state = SwapState.IDLE;
        }

        boolean creative = mc.gameMode.getPlayerMode().isCreative();
        boolean swapKeyDown = SWAP_KEY.isDown();
        boolean guiSwapKeyDown = SWAP_IN_GUI_KEY.isDown();

        // ── GUI Swap (edge detection on press) ──
        boolean guiSwapPressed = guiSwapKeyDown && !guiSwapKeyWasDown;
        boolean swapKeyPressed = swapKeyDown && !swapKeyWasDown;

        if (config.guiSwapEnabled) {
            boolean triggerGuiSwap = guiSwapPressed
                    || (swapKeyPressed && SWAP_IN_GUI_KEY.isUnbound());

            if (triggerGuiSwap && mc.screen instanceof AbstractContainerScreen) {
                if (performGuiSwap(mc, creative)) {
                    debugLog("Tick: 界面中更换完成");
                    state = SwapState.IDLE;
                    swapKeyWasDown = swapKeyDown;
                    guiSwapKeyWasDown = guiSwapKeyDown;
                    return;
                }
            }
        }

        // ── OPEN state: handle release ──
        if (state == SwapState.OPEN) {
            long elapsed = System.currentTimeMillis() - pressStartTime;

            if (config.longPressMode) {
                if (elapsed >= config.holdThresholdMs && swapKeyDown) {
                    longPressConfirmed = true;
                }
                if (!swapKeyDown) {
                    if (longPressConfirmed) {
                        performSwapAndClose(mc, creative);
                        debugLog("Tick: 长按完成（" + elapsed + "ms）");
                    }
                    state = SwapState.IDLE;
                }
            } else {
                if (!swapKeyDown) {
                    performSwapAndClose(mc, creative);
                    state = SwapState.IDLE;
                    debugLog("Tick: 经典模式交换完成");
                }
            }
        }

        // ── IDLE state: handle press ──
        if (swapKeyDown && state == SwapState.IDLE) {
            if (!canInteract(mc)) {
                swapKeyWasDown = swapKeyDown;
                guiSwapKeyWasDown = guiSwapKeyDown;
                return;
            }

            if (isInventoryScreen(mc.screen)) {
                if (!isVanillaInventoryKey()) {
                    mc.player.closeContainer();
                }
                debugLog("Tick: 物品栏已打开 → 关闭物品栏");
                swapKeyWasDown = swapKeyDown;
                guiSwapKeyWasDown = guiSwapKeyDown;
                return;
            }

            if (mc.screen instanceof AbstractContainerScreen) {
                mc.player.closeContainer();
                debugLog("Tick: 关闭容器界面（模拟原版E键）");
                swapKeyWasDown = swapKeyDown;
                guiSwapKeyWasDown = guiSwapKeyDown;
                return;
            }

            if (mc.screen != null) {
                swapKeyWasDown = swapKeyDown;
                guiSwapKeyWasDown = guiSwapKeyDown;
                return;
            }

            openInventoryAndPositionCursor(mc, creative);
            state = SwapState.OPEN;
            pressStartTime = System.currentTimeMillis();
            longPressConfirmed = false;
            debugLog("Tick: 打开物品栏 → OPEN" + (config.longPressMode ? "" : "（经典模式）"));
        }

        swapKeyWasDown = swapKeyDown;
        guiSwapKeyWasDown = guiSwapKeyDown;
    }

    // ── 反射工具 ──

    private static Slot getHoveredSlot(Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?> cs)) return null;
        if (!hoveredSlotFieldResolved) {
            hoveredSlotFieldResolved = true;
            for (String name : new String[]{"hoveredSlot", "focusedSlot"}) {
                try {
                    Field f = AbstractContainerScreen.class.getDeclaredField(name);
                    f.setAccessible(true);
                    hoveredSlotField = f;
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
                    break;
                }
            }
        }
        if (hoveredSlotField == null) return null;
        try { return (Slot) hoveredSlotField.get(cs); } catch (Exception e) { return null; }
    }

    private static void resolveGuiFields() {
        if (guiFieldsResolved) return;
        guiFieldsResolved = true;
        try {
            leftPosField = AbstractContainerScreen.class.getDeclaredField("leftPos");
            leftPosField.setAccessible(true);
            topPosField = AbstractContainerScreen.class.getDeclaredField("topPos");
            topPosField.setAccessible(true);
            imageWidthField = AbstractContainerScreen.class.getDeclaredField("imageWidth");
            imageWidthField.setAccessible(true);
            imageHeightField = AbstractContainerScreen.class.getDeclaredField("imageHeight");
            imageHeightField.setAccessible(true);
        } catch (NoSuchFieldException e) {
            LOGGER.warn("[SusInstantSwap] 反射 GUI 字段失败: {}", e.getMessage());
        }
    }

    private static Object getCreativeContainer() {
        try {
            Field f = CreativeModeInventoryScreen.class.getDeclaredField("CONTAINER");
            f.setAccessible(true);
            return f.get(null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Check if a Slot is a SlotWrapper and return its target.index via reflection.
     * Returns -1 if not a SlotWrapper or reflection fails.
     */
    private static int getSlotWrapperTargetIndex(Slot slot) {
        try {
            Class<?> slotClass = slot.getClass();
            if (!"SlotWrapper".equals(slotClass.getSimpleName())) return -1;
            Field targetField = slotClass.getDeclaredField("target");
            targetField.setAccessible(true);
            Slot target = (Slot) targetField.get(slot);
            return target.index;
        } catch (Exception e) {
            return -1;
        }
    }

    // ── GUI 交换 ──

    private static boolean performGuiSwap(Minecraft mc, boolean creative) {
        if (!(mc.screen instanceof AbstractContainerScreen<?> screen)) return false;

        Slot hoveredSlot = getHoveredSlot(mc.screen);
        if (hoveredSlot == null || !hoveredSlot.hasItem()) {
            debugLog("GUI交换: 悬停槽位无物品, 跳过");
            return false;
        }

        int hoveredIndex = hoveredSlot.index;
        int selectedHotbar = mc.player.getInventory().getSelectedSlot();

        if (screen instanceof CreativeModeInventoryScreen) {
            if (!creative) return false;
            if (performGuiCreativeSwap(mc, (CreativeModeInventoryScreen) screen, hoveredSlot)) {
                playSwapSound(mc);
                if (mc.player != null) mc.player.closeContainer();
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
                if (mc.player != null) mc.player.closeContainer();
                debugLog("GUI交换完成: 槽位" + hoveredIndex + " <-> 快捷栏" + selectedHotbar);
                return true;
            }
            return false;
        }

        return false;
    }

    private static boolean performGuiContainerSwap(AbstractContainerScreen<?> screen, int hoveredIndex, int selectedHotbar) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) return false;

        int containerId = screen.getMenu().containerId;
        int stateId = screen.getMenu().getStateId();
        Int2ObjectOpenHashMap<HashedStack> changedSlots = new Int2ObjectOpenHashMap<>();
        ServerboundContainerClickPacket packet = new ServerboundContainerClickPacket(
                containerId, stateId, (byte) hoveredIndex, (byte) selectedHotbar,
                ContainerInput.SWAP, changedSlots, HashedStack.EMPTY);
        mc.getConnection().send(packet);
        return true;
    }

    private static boolean performGuiCreativeSwap(Minecraft mc, CreativeModeInventoryScreen creativeScreen, Slot hoveredSlot) {
        if (mc.gameMode == null) return false;
        if (!hoveredSlot.hasItem()) return false;

        int selected = mc.player.getInventory().getSelectedSlot();
        int heldSlotIndex = selected + 36;
        Object creativeContainerObj = getCreativeContainer();

        if (creativeContainerObj != null && hoveredSlot.container == creativeContainerObj) {
            ItemStack heldItem = mc.player.getInventory().getItem(selected).copy();
            ItemStack item = hoveredSlot.getItem().copyWithCount(1);
            if (!heldItem.isEmpty()) {
                int freeBackpackSlot = findFreeBackpackSlot(mc);
                if (freeBackpackSlot >= 0) {
                    mc.gameMode.handleCreativeModeItemAdd(heldItem, freeBackpackSlot);
                }
            }
            mc.gameMode.handleCreativeModeItemAdd(item, heldSlotIndex);
            return true;
        }

        // SlotWrapper check via reflection (Fabric has no AT)
        int slotWrapperTargetIndex = getSlotWrapperTargetIndex(hoveredSlot);
        if (slotWrapperTargetIndex >= 0) {
            if (isAllowedMenuSlot(slotWrapperTargetIndex) && slotWrapperTargetIndex != heldSlotIndex) {
                ItemStack targetItem = creativeScreen.getMenu().getSlot(slotWrapperTargetIndex).getItem().copy();
                ItemStack heldItem = creativeScreen.getMenu().getSlot(heldSlotIndex).getItem().copy();
                mc.gameMode.handleCreativeModeItemAdd(targetItem, heldSlotIndex);
                mc.gameMode.handleCreativeModeItemAdd(heldItem, slotWrapperTargetIndex);
                return true;
            }
            return false;
        }

        int containerSlot = hoveredSlot.getContainerSlot();
        if (containerSlot >= 0 && containerSlot <= 8 && containerSlot != selected) {
            int hotbarMenuSlot = containerSlot + 36;
            ItemStack hotbarItem = mc.player.getInventory().getItem(containerSlot).copy();
            ItemStack heldItem = mc.player.getInventory().getItem(selected).copy();
            mc.gameMode.handleCreativeModeItemAdd(hotbarItem, heldSlotIndex);
            mc.gameMode.handleCreativeModeItemAdd(heldItem, hotbarMenuSlot);
            return true;
        }

        return false;
    }

    // ── 生存模式交换 ──

    private static boolean isAllowedMenuSlot(int menuSlot) {
        return menuSlot >= 9 && menuSlot <= 44;
    }

    private static void performSurvivalSwap(Minecraft mc) {
        if (!(mc.screen instanceof InventoryScreen)) {
            LOGGER.warn("[SusInstantSwap] 生存交换失败");
            return;
        }

        Slot hovered = getHoveredSlot(mc.screen);
        if (hovered == null || !hovered.hasItem()) {
            LOGGER.warn("[SusInstantSwap] 生存交换失败: 无悬停物品");
            return;
        }

        int slotIndex = hovered.index;
        int hotbar = mc.player.getInventory().getSelectedSlot();
        int hotbarMenuSlot = hotbar + 36;

        if (!isAllowedMenuSlot(slotIndex) || slotIndex == hotbarMenuSlot) return;

        int containerId = mc.player.inventoryMenu.containerId;
        int stateId = mc.player.inventoryMenu.getStateId();
        Int2ObjectOpenHashMap<HashedStack> changedSlots = new Int2ObjectOpenHashMap<>();
        ServerboundContainerClickPacket packet = new ServerboundContainerClickPacket(
                containerId, stateId, (byte) slotIndex, (byte) hotbar,
                ContainerInput.SWAP, changedSlots, HashedStack.EMPTY);
        if (mc.getConnection() != null) {
            mc.getConnection().send(packet);
            playSwapSound(mc);
        }
    }

    // ── 创造模式交换 ──

    private static void performCreativeSwap(Minecraft mc) {
        if (!(mc.screen instanceof CreativeModeInventoryScreen)) return;
        if (mc.gameMode == null) return;

        Slot hovered = getHoveredSlot(mc.screen);
        if (hovered == null || !hovered.hasItem()) return;

        int selected = mc.player.getInventory().getSelectedSlot();
        int heldSlotIndex = selected + 36;
        boolean didSwap = false;
        Object creativeContainerObj = getCreativeContainer();

        if (creativeContainerObj != null && hovered.container == creativeContainerObj) {
            ItemStack heldItem = mc.player.getInventory().getItem(selected).copy();
            ItemStack item = hovered.getItem().copyWithCount(1);
            if (!heldItem.isEmpty()) {
                int free = findFreeBackpackSlot(mc);
                if (free >= 0) mc.gameMode.handleCreativeModeItemAdd(heldItem, free);
            }
            mc.gameMode.handleCreativeModeItemAdd(item, heldSlotIndex);
            didSwap = true;
        } else if (getSlotWrapperTargetIndex(hovered) >= 0) {
            int targetMenuSlot = getSlotWrapperTargetIndex(hovered);
            if (isAllowedMenuSlot(targetMenuSlot) && targetMenuSlot != heldSlotIndex) {
                ItemStack targetItem = mc.player.inventoryMenu.getSlot(targetMenuSlot).getItem().copy();
                ItemStack heldItem = mc.player.inventoryMenu.getSlot(heldSlotIndex).getItem().copy();
                mc.gameMode.handleCreativeModeItemAdd(targetItem, heldSlotIndex);
                mc.gameMode.handleCreativeModeItemAdd(heldItem, targetMenuSlot);
                didSwap = true;
            }
        } else {
            int cs = hovered.getContainerSlot();
            if (cs >= 0 && cs <= 8 && cs + 36 != heldSlotIndex) {
                ItemStack hotbarItem = mc.player.getInventory().getItem(cs).copy();
                ItemStack heldItem = mc.player.getInventory().getItem(selected).copy();
                mc.gameMode.handleCreativeModeItemAdd(hotbarItem, heldSlotIndex);
                mc.gameMode.handleCreativeModeItemAdd(heldItem, cs + 36);
                didSwap = true;
            }
        }

        if (didSwap) playSwapSound(mc);
    }

    // ── 工具方法 ──

    private static void performSwapAndClose(Minecraft mc, boolean creative) {
        if (creative) performCreativeSwap(mc);
        else performSurvivalSwap(mc);
        if (mc.player != null) mc.player.closeContainer();
    }

    private static boolean isVanillaInventoryKey() {
        return Minecraft.getInstance().options != null
                && SWAP_KEY.same(Minecraft.getInstance().options.keyInventory);
    }

    private static boolean canInteract(Minecraft mc) {
        GameType m = mc.gameMode.getPlayerMode();
        return m == GameType.SURVIVAL || m == GameType.CREATIVE || m == GameType.ADVENTURE;
    }

    private static boolean isInventoryScreen(Screen s) {
        return s instanceof InventoryScreen || s instanceof CreativeModeInventoryScreen;
    }

    private static Screen createInventoryScreen(Minecraft mc, boolean creative) {
        if (creative) return new CreativeModeInventoryScreen(mc.player, mc.player.connection.enabledFeatures(), false);
        return new InventoryScreen(mc.player);
    }

    private static void openInventoryAndPositionCursor(Minecraft mc, boolean creative) {
        Screen screen = createInventoryScreen(mc, creative);
        preMoveCursorToWindowCorner(mc);
        mc.setScreen(screen);
        positionCursorIfEnabled(mc, screen);
    }

    private static void preMoveCursorToWindowCorner(Minecraft mc) {
        if (!config.mouseReposition) return;
        GLFW.glfwSetCursorPos(mc.getWindow().handle(), mc.getWindow().getWidth() - 10, mc.getWindow().getHeight() - 10);
    }

    private static void positionCursorIfEnabled(Minecraft mc, Screen screen) {
        if (!config.mouseReposition) return;
        positionCursorToUIBottomRight(mc, screen);
    }

    private static void positionCursorToUIBottomRight(Minecraft mc, Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?> cs)) {
            GLFW.glfwSetCursorPos(mc.getWindow().handle(), mc.getWindow().getWidth() - 10, mc.getWindow().getHeight() - 10);
            return;
        }
        resolveGuiFields();
        try {
            double s = mc.getWindow().getGuiScale();
            int px = (int) ((leftPosField.getInt(cs) + imageWidthField.getInt(cs)) * s) - 5;
            int py = (int) ((topPosField.getInt(cs) + imageHeightField.getInt(cs)) * s) - 5;
            GLFW.glfwSetCursorPos(mc.getWindow().handle(), px, py);
        } catch (Exception ignored) {}
    }

    private static int findFreeBackpackSlot(Minecraft mc) {
        for (int s = 9; s <= 35; s++)
            if (mc.player.inventoryMenu.getSlot(s).getItem().isEmpty()) return s;
        return -1;
    }

    private static void playSwapSound(Minecraft mc) {
        if (!config.soundEnabled || mc.player == null) return;
        mc.player.playSound(SoundEvents.ITEM_PICKUP, 0.8f, 1.0f);
    }

    private static void debugLog(String msg) {
        if (config.debug) LOGGER.info("[SusInstantSwap] {}", msg);
    }
}
