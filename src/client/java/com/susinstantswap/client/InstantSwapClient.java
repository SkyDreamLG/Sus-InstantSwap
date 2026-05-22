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
    private static KeyMapping SWAP_KEY;
    private static final KeyMapping.Category SWAP_CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("susinstantswap", "main"));
    private static SwapConfig config;

    enum SwapState { IDLE, OPEN }
    private static SwapState state = SwapState.IDLE;
    private static long pressStartTime;
    private static boolean longPressConfirmed;
    private static boolean configLogged = false;
    private static Field hoveredSlotField = null;
    private static boolean hoveredSlotFieldResolved = false;

    public static void init() {
        LOGGER.info("[SusInstantSwap] v1.1.0 初始化客户端交换逻辑 (Fabric 26.1)...");
        config = SwapConfig.get();

        SWAP_KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.susinstantswap.swap",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_ALT,
                SWAP_CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(InstantSwapClient::onClientTick);
        LOGGER.info("[SusInstantSwap] 按键绑定和 Tick 事件已注册 (Fabric 26.1)");
    }

    private static boolean isSwapKeyDown() {
        Minecraft mc = Minecraft.getInstance();
        long handle = mc.getWindow().handle();
        InputConstants.Key boundKey = SWAP_KEY.getDefaultKey();
        if (boundKey.getType() == InputConstants.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(handle, boundKey.getValue()) == GLFW.GLFW_PRESS;
        }
        return InputConstants.isKeyDown(mc.getWindow(), boundKey.getValue());
    }

    private static Slot getHoveredSlot(Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?> cs)) return null;
        if (!hoveredSlotFieldResolved) {
            hoveredSlotFieldResolved = true;
            for (String name : new String[]{"hoveredSlot", "focusedSlot"}) {
                try {
                    Field f = AbstractContainerScreen.class.getDeclaredField(name);
                    f.setAccessible(true); hoveredSlotField = f; break;
                } catch (NoSuchFieldException ignored) {}
            }
            if (hoveredSlotField == null) {
                for (Field field : AbstractContainerScreen.class.getDeclaredFields()) {
                    if (!Slot.class.isAssignableFrom(field.getType())) continue;
                    int mod = field.getModifiers();
                    if (java.lang.reflect.Modifier.isStatic(mod)) continue;
                    if (java.lang.reflect.Modifier.isPrivate(mod)) continue;
                    field.setAccessible(true); hoveredSlotField = field; break;
                }
            }
        }
        if (hoveredSlotField == null) return null;
        try { return (Slot) hoveredSlotField.get(cs); } catch (Exception e) { return null; }
    }

    private static void onClientTick(Minecraft mc) {
        if (mc.player == null || mc.gameMode == null) { state = SwapState.IDLE; return; }

        if (!configLogged) {
            configLogged = true;
            LOGGER.info("[SusInstantSwap] 配置: longPressMode={}, holdThresholdMs={}, soundEnabled={}, debug={}, mouseReposition={}",
                    config.longPressMode, config.holdThresholdMs, config.soundEnabled, config.debug, config.mouseReposition);
        }

        if (state == SwapState.OPEN && mc.screen == null) {
            debugLog("界面被外部关闭，重置状态"); state = SwapState.IDLE;
        }

        boolean creative = mc.gameMode.getPlayerMode().isCreative();

        if (state == SwapState.OPEN) {
            long elapsed = System.currentTimeMillis() - pressStartTime;
            boolean keyDown = isSwapKeyDown();
            if (config.longPressMode) {
                if (elapsed >= config.holdThresholdMs && keyDown) longPressConfirmed = true;
                if (!keyDown) {
                    if (longPressConfirmed) performSwapAndClose(mc, creative);
                    state = SwapState.IDLE; return;
                }
            } else {
                if (!keyDown) { performSwapAndClose(mc, creative); state = SwapState.IDLE; return; }
            }
            return;
        }

        if (isSwapKeyDown() && state == SwapState.IDLE) {
            if (!canInteract(mc)) return;
            if (isInventoryScreen(mc.screen)) { if (!isVanillaInventoryKey()) mc.setScreen(null); return; }
            if (mc.screen instanceof AbstractContainerScreen) { mc.player.closeContainer(); return; }
            if (mc.screen != null) return;
            openInventoryAndPositionCursor(mc, creative);
            state = SwapState.OPEN; pressStartTime = System.currentTimeMillis(); longPressConfirmed = false;
        }
    }

    private static boolean isVanillaInventoryKey() { return Minecraft.getInstance().options != null && SWAP_KEY.same(Minecraft.getInstance().options.keyInventory); }
    private static boolean canInteract(Minecraft mc) { GameType m = mc.gameMode.getPlayerMode(); return m == GameType.SURVIVAL || m == GameType.CREATIVE || m == GameType.ADVENTURE; }
    private static boolean isInventoryScreen(Screen s) { return s instanceof InventoryScreen || s instanceof CreativeModeInventoryScreen; }

    private static Screen createInventoryScreen(Minecraft mc, boolean creative) {
        if (creative) return new CreativeModeInventoryScreen(mc.player, mc.player.connection.enabledFeatures(), false);
        return new InventoryScreen(mc.player);
    }

    private static void openInventoryAndPositionCursor(Minecraft mc, boolean creative) {
        Screen screen = createInventoryScreen(mc, creative);
        preMoveCursorToWindowCorner(mc); mc.setScreen(screen);
        positionCursorIfEnabled(mc, screen);
    }

    private static void preMoveCursorToWindowCorner(Minecraft mc) {
        if (!config.mouseReposition) return;
        GLFW.glfwSetCursorPos(mc.getWindow().handle(), mc.getWindow().getWidth() - 10, mc.getWindow().getHeight() - 10);
    }

    private static void positionCursorIfEnabled(Minecraft mc, Screen screen) {
        if (!config.mouseReposition) return; positionCursorToUIBottomRight(mc, screen);
    }

    private static void positionCursorToUIBottomRight(Minecraft mc, Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?> cs)) {
            GLFW.glfwSetCursorPos(mc.getWindow().handle(), mc.getWindow().getWidth() - 10, mc.getWindow().getHeight() - 10); return;
        }
        try {
            Field l = AbstractContainerScreen.class.getDeclaredField("leftPos"); l.setAccessible(true);
            Field t = AbstractContainerScreen.class.getDeclaredField("topPos"); t.setAccessible(true);
            Field iw = AbstractContainerScreen.class.getDeclaredField("imageWidth"); iw.setAccessible(true);
            Field ih = AbstractContainerScreen.class.getDeclaredField("imageHeight"); ih.setAccessible(true);
            double s = mc.getWindow().getGuiScale();
            int px = (int) ((l.getInt(cs) + iw.getInt(cs)) * s) - 5;
            int py = (int) ((t.getInt(cs) + ih.getInt(cs)) * s) - 5;
            GLFW.glfwSetCursorPos(mc.getWindow().handle(), px, py);
        } catch (Exception ignored) {}
    }

    private static void performSwapAndClose(Minecraft mc, boolean creative) {
        if (creative) performCreativeSwap(mc); else performSurvivalSwap(mc);
        if (mc.player != null) mc.player.closeContainer();
    }

    private static boolean isAllowedMenuSlot(int s) { return s >= 9 && s <= 44; }

    private static void performSurvivalSwap(Minecraft mc) {
        if (!(mc.screen instanceof InventoryScreen)) { LOGGER.warn("[SusInstantSwap] 生存交换失败"); return; }
        Slot hovered = getHoveredSlot(mc.screen);
        if (hovered == null || !hovered.hasItem()) { LOGGER.warn("[SusInstantSwap] 生存交换失败: 无悬停物品"); return; }
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
        if (mc.getConnection() != null) { mc.getConnection().send(packet); playSwapSound(mc); }
    }

    private static void performCreativeSwap(Minecraft mc) {
        if (!(mc.screen instanceof CreativeModeInventoryScreen)) return;
        if (mc.gameMode == null) return;
        Slot hovered = getHoveredSlot(mc.screen);
        if (hovered == null || !hovered.hasItem()) return;
        int selected = mc.player.getInventory().getSelectedSlot();
        int heldSlotIndex = selected + 36;
        boolean didSwap = false;
        Object cc = getCreativeContainer();
        if (cc != null && hovered.container == cc) {
            ItemStack heldItem = mc.player.getInventory().getItem(selected).copy();
            ItemStack item = hovered.getItem().copyWithCount(1);
            if (!heldItem.isEmpty()) {
                int free = findFreeBackpackSlot(mc);
                if (free >= 0) mc.gameMode.handleCreativeModeItemAdd(heldItem, free);
            }
            mc.gameMode.handleCreativeModeItemAdd(item, heldSlotIndex); didSwap = true;
        } else {
            int cs = hovered.getContainerSlot();
            if (cs >= 0 && cs <= 8 && cs + 36 != heldSlotIndex) {
                ItemStack hotbarItem = mc.player.getInventory().getItem(cs).copy();
                ItemStack heldItem = mc.player.getInventory().getItem(selected).copy();
                mc.gameMode.handleCreativeModeItemAdd(hotbarItem, heldSlotIndex);
                mc.gameMode.handleCreativeModeItemAdd(heldItem, cs + 36); didSwap = true;
            }
        }
        if (didSwap) playSwapSound(mc);
    }

    private static Object getCreativeContainer() {
        try { Field f = CreativeModeInventoryScreen.class.getDeclaredField("CONTAINER"); f.setAccessible(true); return f.get(null); } catch (Exception e) { return null; }
    }

    private static int findFreeBackpackSlot(Minecraft mc) {
        for (int s = 9; s <= 35; s++) if (mc.player.inventoryMenu.getSlot(s).getItem().isEmpty()) return s;
        return -1;
    }

    private static void playSwapSound(Minecraft mc) {
        if (!config.soundEnabled || mc.player == null) return;
        mc.player.playSound(SoundEvents.ITEM_PICKUP, 0.8f, 1.0f);
    }

    private static void debugLog(String msg) { if (config.debug) LOGGER.info("[SusInstantSwap] {}", msg); }
}
