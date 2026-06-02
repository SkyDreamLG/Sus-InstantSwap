package com.susinstantswap.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import com.susinstantswap.config.SwapConfig;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderTooltipEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import java.lang.reflect.Field;

import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

/**
 * Sus-InstantSwap v2.0 — coexists with the vanilla inventory key.
 * Forge 26.1 — uses reflection for CreativeModeInventoryScreen internals.
 * MC 26.1 API: ContainerInput, HashedStack, getSelectedSlot, handle(), playSound.
 */
public class InstantSwapClient {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static KeyMapping SWAP_IN_GUI_KEY;

    enum SwapState { IDLE, WATCHING, LONG_PRESS }
    private static SwapState state = SwapState.IDLE;

    private static boolean configLogged = false;
    private static boolean suppressNextTooltip;
    private static int suppressTooltipFrames;

    /** Called from TooltipMixin to check if tooltip should be suppressed. */
    public static boolean shouldSuppressTooltip() {
        if (!suppressNextTooltip) return false;
        suppressNextTooltip = false;
        suppressTooltipFrames = 0;
        return true;
    }

    // Belt-and-suspenders: sync config once on first tick
    private static boolean firstTickSyncDone = false;

    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(net.minecraft.resources.Identifier.fromNamespaceAndPath("susinstantswap", "main"));

    public static void init() {
        SWAP_IN_GUI_KEY = new KeyMapping("key.susinstantswap.swap_in_gui",
                InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(),
                CATEGORY);
        MinecraftForge.EVENT_BUS.register(InstantSwapClient.class);
    }

    public static void registerKey(RegisterKeyMappingsEvent event) {
        event.register(SWAP_IN_GUI_KEY);
    }

    // ── Container opened via right-click → reposition cursor ──
    // Tracked by PlayerInteractEvent to avoid repositioning for keybind-opened
    // screens (Curios, cosmetic armor, etc.)

    private static boolean screenOpenedByInteract = false;

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        screenOpenedByInteract = true;
    }

    @SubscribeEvent
    public static void onRightClickEntity(PlayerInteractEvent.EntityInteractSpecific event) {
        screenOpenedByInteract = true;
    }

    @SubscribeEvent
    public static void onScreenInitPost(ScreenEvent.Init.Post event) {
        if (!SwapConfig.mouseRepositionRuntime) return;
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> s)) return;
        if (s instanceof InventoryScreen || s instanceof CreativeModeInventoryScreen) return;
        if (!screenOpenedByInteract) return;
        screenOpenedByInteract = false;
        positionCursorToUIBottomRight(s);
    }

    // ── Per-tick ──

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();

        if (suppressTooltipFrames > 0 && --suppressTooltipFrames == 0)
            suppressNextTooltip = false;

        // Belt-and-suspenders: sync config on first tick
        if (!firstTickSyncDone) {
            firstTickSyncDone = true;
            com.susinstantswap.SusInstantSwapMod.CONFIG.syncToRuntime();
        }

        if (!configLogged) {
            configLogged = true;
            LOGGER.info("[SusInstantSwap] Config: mod={} threshold={}ms sound={} guiSwap={} emptySwap={} debug={} mouse={}",
                    SwapConfig.modEnabledRuntime, SwapConfig.holdThresholdMsRuntime, SwapConfig.soundEnabledRuntime,
                    SwapConfig.guiSwapEnabledRuntime, SwapConfig.emptySlotSwapEnabledRuntime,
                    SwapConfig.debugRuntime, SwapConfig.mouseRepositionRuntime);
        }

        // Sync master switch to shared state (read by mixins)
        SwapKeyState.modEnabled = SwapConfig.modEnabledRuntime;
        if (!SwapKeyState.modEnabled) return;

        if (mc.player == null || mc.gameMode == null) {
            state = SwapState.IDLE;
            SwapKeyState.closePendingTicks = 0;
            return;
        }

        // Deferred close — gives server a tick to sync after swap
        if (SwapKeyState.closePendingTicks > 0) {
            SwapKeyState.closePendingTicks--;
            if (SwapKeyState.closePendingTicks == 0) {
                if (mc.screen instanceof AbstractContainerScreen) {
                    debugLog("deferred close");
                    mc.player.closeContainer();
                }
            }
            state = SwapState.IDLE;
        }

        // ── IDLE: wait for screen to open after E press ──
        if (state == SwapState.IDLE) {
            if (SwapKeyState.inventoryKeyHeld && !SwapKeyState.longPressConfirmed) {
                if (mc.screen instanceof AbstractContainerScreen) {
                    SwapKeyState.pressStartNanos = System.nanoTime();
                    positionCursorIfEnabled(mc, mc.screen);
                    state = SwapState.WATCHING;
                    debugLog("WATCHING");
                }
            }
            return;
        }

        // ── WATCHING: check threshold ──
        if (state == SwapState.WATCHING) {
            if (mc.screen == null) { state = SwapState.IDLE; return; }
            if (!isInventoryKeyPhysicallyDown(mc)) { state = SwapState.IDLE; return; }
            if ((System.nanoTime() - SwapKeyState.pressStartNanos)
                    >= SwapConfig.holdThresholdMsRuntime * 1_000_000L) {
                SwapKeyState.longPressConfirmed = true;
                state = SwapState.LONG_PRESS;
                debugLog("LONG_PRESS");
            }
            return;
        }

        // ── LONG_PRESS → release triggers swap ──
        if (state == SwapState.LONG_PRESS) {
            if (mc.screen == null) { state = SwapState.IDLE; return; }
            if (!isInventoryKeyPhysicallyDown(mc) || !SwapKeyState.inventoryKeyHeld) {
                boolean swapped = performSwap(mc);
                if (!swapped) {
                    int closeDelay = (mc.screen instanceof AbstractContainerScreen<?> s && isVanillaInventory(s)) ? 1 : 2;
                    SwapKeyState.closePendingTicks = closeDelay; // auto-close
                }
                state = SwapState.IDLE;
            }
        }
    }

    // ── InputEvent: EditBox protection only ──
    // GUI swap is handled via ScreenKeyMixin (reliable, no Forge event bus dependency).

    @SubscribeEvent
    public static void onKeyInput(InputEvent.Key event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null) return;

        int action = event.getAction();
        if (action != GLFW.GLFW_PRESS && action != GLFW.GLFW_RELEASE) return;

        boolean keyDown = (action == GLFW.GLFW_PRESS);
        boolean isInventoryKey = isInventoryKeyEvent(mc, event);

        // EditBox protection: consume click so E key doesn't close screen
        if (keyDown && isInventoryKey && mc.screen != null && hasEditBoxFocus(mc.screen)) {
            while (mc.options.keyInventory.consumeClick()) {}
            if (mc.screen instanceof AbstractContainerScreen) {
                if (mc.player.containerMenu.getSlot(0).hasItem()) return;
            } else return;
        }
    }

    // ── GUI swap entry (from ScreenKeyMixin) ──
    // Handles BOTH bound SWAP_IN_GUI_KEY presses AND E key fallback (when unbound).

    public static boolean tryPerformGuiSwap(AbstractContainerScreen<?> screen, InputConstants.Key pressedKey) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null) return false;
        if (!SwapConfig.guiSwapEnabledRuntime) return false;

        boolean isEKey = pressedKey.equals(mc.options.keyInventory.getKey());
        boolean isBoundGuiSwapKey = !SWAP_IN_GUI_KEY.isUnbound()
                && pressedKey.equals(SWAP_IN_GUI_KEY.getKey());

        // Bound GUI swap key: always triggers swap
        if (isBoundGuiSwapKey) {
            debugLog("GUI swap via bound key");
            return performSwap(mc);
        }

        // E key when SWAP_IN_GUI_KEY is unbound: triggers swap
        if (isEKey && SWAP_IN_GUI_KEY.isUnbound()) {
            debugLog("GUI swap via E key (unbound fallback)");
            return performSwap(mc);
        }

        return false;
    }

    // ── Unified swap (GUI + long press) ──

    private static boolean performSwap(Minecraft mc) {
        if (!(mc.screen instanceof AbstractContainerScreen<?> screen)) return false;
        Slot hs = screen.getSlotUnderMouse();
        if (hs == null || (!hs.hasItem() && !SwapConfig.emptySlotSwapEnabledRuntime)) return false;

        int sel = mc.player.getInventory().getSelectedSlot();

        // Both slots empty → nothing to swap
        if (!hs.hasItem() && mc.player.getInventory().getItem(sel).isEmpty()) return false;

        // ── Creative inventory → special handling ──
        if (screen instanceof CreativeModeInventoryScreen cs) {
            if (creativeSwap(mc, cs, sel)) { playSwapSound(mc); return true; }
            return false;
        }

        // Player inventory → restrict to backpack + hotbar
        if (screen instanceof InventoryScreen && (!isPlayerInventorySlot(hs) || hs.index == hotbarMenuSlot(sel)))
            return false;

        // Slot validation: hand item must fit the target slot
        ItemStack hand = mc.player.getInventory().getItem(sel);
        if (!hand.isEmpty() && !hs.mayPlace(hand)) return false;

        int closeDelay = isVanillaInventory(screen) ? 1 : 2;

        // All containers → ContainerInput.SWAP (MC 26.1 API)
        if (containerSwap(screen, hs.index, sel)) {
            playSwapSound(mc);
            SwapKeyState.closePendingTicks = closeDelay;
            return true;
        }
        return false;
    }

    private static boolean containerSwap(AbstractContainerScreen<?> s, int slotIdx, int hotbar) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) return false;
        Int2ObjectOpenHashMap<HashedStack> cs = new Int2ObjectOpenHashMap<>();
        mc.getConnection().send(new ServerboundContainerClickPacket(
                s.getMenu().containerId, s.getMenu().getStateId(),
                (byte) slotIdx, (byte) hotbar, ContainerInput.SWAP, cs, HashedStack.EMPTY));
        return true;
    }

    // ── Creative swap (FG7 AT unreliable, use reflection for SlotWrapper/CONTAINER) ──
    // Branch order (matches NF 1.21.1):
    //   1. CONTAINER (creative tab item grid)
    //   2. CREATIVE_EQUIP (armor/offhand, isInventoryOpen=true)
    //   3. SlotWrapper (non-equipment player inventory: hotbar 36-44, backpack 9-35)
    //   4. REGULAR (fallback hotbar slots)
    //
    // On Forge, SlotWrapper.getContainerSlot() returns screen position (0-8), NOT
    // the target index. We use swTarget.index as the definitive slot position.

    private static boolean creativeSwap(Minecraft mc, CreativeModeInventoryScreen cs, int sel) {
        if (mc.gameMode == null) return false;
        Slot hs = cs.getSlotUnderMouse();
        if (hs == null || (!hs.hasItem() && !SwapConfig.emptySlotSwapEnabledRuntime)) return false;

        int hotbarSize = hotbarSize(mc);
        int menuHotbarStart = 36;
        int heldIdx = menuHotbarStart + sel;
        ItemStack handStack = mc.player.getInventory().getItem(sel);

        Slot swTarget = getSlotWrapperTarget(hs);
        int realCsi = (swTarget != null) ? swTarget.index : hs.getContainerSlot();
        debugLog("creativeSwap ENTER: sel=" + sel + " hand=" + (handStack.isEmpty()?"EMPTY":handStack.getDisplayName().getString())
                + " hs.container=" + (hs.container==getCreativeContainer()?"CONTAINER":hs.container==mc.player.getInventory()?"PLAYER_INV":
                  swTarget!=null?"SlotWrapper("+swTarget.index+")":"OTHER")
                + " hs.index=" + hs.index + " realCsi=" + realCsi + " rawCsi=" + hs.getContainerSlot());

        // ── CONTAINER (creative tab item grid) ──
        if (hs.container == getCreativeContainer()) {
            debugLog("  branch=CONTAINER");
            ItemStack held = handStack.copy();
            ItemStack item = hs.getItem().copyWithCount(1);
            debugLog("  held=" + (held.isEmpty()?"EMPTY":held.getDisplayName().getString()) + " item=" + item.getDisplayName().getString());
            if (!held.isEmpty()) {
                int f = freeSlot(mc);
                debugLog("  freeSlot=" + f);
                if (f >= 0 && f < mc.player.getInventory().getContainerSize()) {
                    mc.player.getInventory().setItem(f, held.copy());
                    mc.gameMode.handleCreativeModeItemAdd(held.copy(), menuHotbarStart + f);
                    debugLog("  setItem(" + f + ",held) + addItem(" + (menuHotbarStart+f) + ")");
                }
            }
            if (sel < mc.player.getInventory().getContainerSize()) {
                mc.player.getInventory().setItem(sel, item);
                mc.gameMode.handleCreativeModeItemAdd(item, heldIdx);
                debugLog("  setItem(" + sel + ",item) + addItem(" + heldIdx + ")");
            }
            SwapKeyState.closePendingTicks = 1;
            return true;
        }

        // ── CREATIVE_EQUIP: realCsi=5-8 (armor) or 45 (offhand) ──
        if (cs.isInventoryOpen() && (realCsi == 45 || (realCsi >= 5 && realCsi <= 8))) {
            debugLog("  branch=CREATIVE_EQUIP realCsi=" + realCsi + " sel=" + sel);
            if (!handStack.isEmpty() && !hs.mayPlace(handStack)) {
                debugLog("  mayPlace rejected -> false");
                return false;
            }
            if (realCsi <= 8 && !handStack.isEmpty()) {
                EquipmentSlot expected = realCsi == 5 ? EquipmentSlot.HEAD :
                                        realCsi == 6 ? EquipmentSlot.CHEST :
                                        realCsi == 7 ? EquipmentSlot.LEGS : EquipmentSlot.FEET;
                EquipmentSlot actual = mc.player.getEquipmentSlotForItem(handStack);
                if (!actual.isArmor() || actual != expected) {
                    debugLog("  armor mismatch: expected=" + expected + " actual=" + actual + " -> false");
                    return false;
                }
            }
            mc.getConnection().send(new ServerboundContainerClickPacket(
                cs.getMenu().containerId, cs.getMenu().getStateId(),
                (byte) realCsi, (byte) sel, ContainerInput.SWAP,
                new Int2ObjectOpenHashMap<>(), HashedStack.EMPTY));
            debugLog("  containerClickPacket(slot=" + realCsi + " hotbar=" + sel + " SWAP)");
            SwapKeyState.closePendingTicks = 1;
            return true;
        }

        // ── SlotWrapper (non-equipment player inventory: hotbar 36-44, backpack 9-35) ──
        if (swTarget != null && realCsi > 8 && realCsi != 45) {
            int t = swTarget.index;
            debugLog("  branch=SlotWrapper t=" + t + " heldMenuIdx=" + heldIdx);
            if (isPlayerInventorySlot(hs) && t != heldIdx) {
                ItemStack ti = cs.getMenu().getSlot(t).getItem().copy();
                ItemStack hi = cs.getMenu().getSlot(heldIdx).getItem().copy();
                int invIdx = t >= menuHotbarStart ? t - menuHotbarStart : t;
                debugLog("  ti=" + ti.getDisplayName().getString() + " hi=" + hi.getDisplayName().getString() + " invIdx=" + invIdx);
                safeSet(mc, sel, ti);
                mc.gameMode.handleCreativeModeItemAdd(ti, heldIdx);
                debugLog("  safeSet(" + sel + ",ti) + addItem(" + heldIdx + ")");
                safeSet(mc, invIdx, hi);
                mc.gameMode.handleCreativeModeItemAdd(hi, t);
                debugLog("  safeSet(" + invIdx + ",hi) + addItem(" + t + ")");
                SwapKeyState.closePendingTicks = 1;
                return true;
            }
            debugLog("  SKIP: sameSlot=" + (t==heldIdx) + " isPlayerInv=" + isPlayerInventorySlot(hs));
            return false;
        }

        // ── REGULAR (fallback hotbar slots, non-SlotWrapper) ──
        int c2 = hs.getContainerSlot();
        debugLog("  branch=REGULAR c2=" + c2);
        if (c2 >= 0 && c2 < hotbarSize && c2 != sel) {
            ItemStack hi = handStack.copy();
            ItemStack oi = mc.player.getInventory().getItem(c2).copy();
            debugLog("  hi(hand->target)=" + (hi.isEmpty()?"EMPTY":hi.getDisplayName().getString()) + " oi(target->hotbar)=" + oi.getDisplayName().getString());
            safeSet(mc, sel, oi);
            mc.gameMode.handleCreativeModeItemAdd(oi, heldIdx);
            debugLog("  safeSet(" + sel + ",oi) + addItem(" + heldIdx + ")");
            safeSet(mc, c2, hi);
            mc.gameMode.handleCreativeModeItemAdd(hi, menuHotbarStart + c2);
            debugLog("  safeSet(" + c2 + ",hi) + addItem(" + (menuHotbarStart+c2) + ")");
            SwapKeyState.closePendingTicks = 1;
            return true;
        }
        debugLog("  NO MATCH -> false");
        return false;
    }

    private static void safeSet(Minecraft mc, int idx, ItemStack stack) {
        if (idx >= 0 && idx < mc.player.getInventory().getContainerSize())
            mc.player.getInventory().setItem(idx, stack);
    }

    private static boolean isPlayerInventorySlot(Slot slot) {
        return slot.container == Minecraft.getInstance().player.getInventory();
    }

    private static boolean isVanillaInventory(AbstractContainerScreen<?> screen) {
        return screen instanceof InventoryScreen || screen instanceof CreativeModeInventoryScreen;
    }

    private static int hotbarMenuSlot(int sel) {
        return 36 + sel;
    }

    private static int hotbarSize(Minecraft mc) {
        return mc.player.getInventory().getContainerSize() - 27;
    }

    private static int freeSlot(Minecraft mc) {
        var inv = mc.player.getInventory();
        int size = 36; // hotbar(9) + main(27), excludes armor/offhand
        int hbSize = hotbarSize(mc);
        int sel = inv.getSelectedSlot();
        for (int i = 0; i < hbSize; i++)
            if (i != sel && inv.getItem(i).isEmpty()) return i;
        for (int i = hbSize; i < size; i++)
            if (inv.getItem(i).isEmpty()) return i;
        return -1;
    }

    // ── Key detection ──

    /** Public entry for mixins: checks whether an InputConstants.Key matches the vanilla inventory key. */
    public static boolean isSwapKey(InputConstants.Key key) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.options == null) return false;
        InputConstants.Key invKey = mc.options.keyInventory.getKey();
        return invKey.getType() == key.getType() && invKey.getValue() == key.getValue();
    }

    private static boolean isInventoryKeyPhysicallyDown(Minecraft mc) {
        InputConstants.Key key = mc.options.keyInventory.getKey();
        if (key.getType() != InputConstants.Type.KEYSYM) return false;
        return GLFW.glfwGetKey(mc.getWindow().handle(), key.getValue()) == GLFW.GLFW_PRESS;
    }

    private static boolean isInventoryKeyEvent(Minecraft mc, InputEvent.Key event) {
        InputConstants.Key ik = mc.options.keyInventory.getKey();
        return ik.getType() == InputConstants.Type.KEYSYM && event.getKey() == ik.getValue();
    }

    private static boolean hasEditBoxFocus(Screen s) {
        if (s == null) return false;
        if (s.getFocused() instanceof EditBox) return true;
        for (var c : s.children()) if (c instanceof EditBox) return true;
        String n = s.getClass().getName();
        return n.contains("BookEdit") || n.contains("SignEdit");
    }

    // ── Mouse reposition ──

    private static void positionCursorIfEnabled(Minecraft mc, Screen screen) {
        if (!SwapConfig.mouseRepositionRuntime || !(screen instanceof AbstractContainerScreen<?> s)) return;
        positionCursorToUIBottomRight(s);
    }

    private static void positionCursorToUIBottomRight(AbstractContainerScreen<?> s) {
        Minecraft mc = Minecraft.getInstance();
        long h = mc.getWindow().handle();
        double gs = mc.getWindow().getGuiScale();
        GLFW.glfwSetCursorPos(h,
                (int) ((s.getGuiLeft() + s.getXSize()) * gs) - 5,
                (int) ((s.getGuiTop() + s.getYSize()) * gs) - 5);
        suppressNextTooltip = true;
        suppressTooltipFrames = 2;
    }

    private static void playSwapSound(Minecraft mc) {
        if (!SwapConfig.soundEnabledRuntime || mc.player == null) return;
        mc.player.playSound(SoundEvents.ITEM_PICKUP, 0.8f, 1.0f);
    }

    private static void debugLog(String msg) {
        if (SwapConfig.debugRuntime) LOGGER.info("[SusInstantSwap] {}", msg);
    }

    // ── Reflection cache (FG7 AT unreliable, fall back to reflection) ──

    private static Object cachedContainer;
    private static boolean containerCached;

    private static Object getCreativeContainer() {
        if (!containerCached) {
            containerCached = true;
            try {
                Field f = CreativeModeInventoryScreen.class.getDeclaredField("CONTAINER");
                f.setAccessible(true);
                cachedContainer = f.get(null);
            } catch (Exception e) {
                LOGGER.warn("[SusInstantSwap] CONTAINER field access failed: {}", e.toString());
            }
        }
        return cachedContainer;
    }

    private static Slot getSlotWrapperTarget(Slot slot) {
        try {
            Field f = slot.getClass().getDeclaredField("target");
            f.setAccessible(true);
            return (Slot) f.get(slot);
        } catch (Exception ignored) {
            return null;
        }
    }
}
