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
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderTooltipEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

@OnlyIn(Dist.CLIENT)
public class InstantSwapClient {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static KeyMapping SWAP_KEY;
    private static SwapConfig config;

    enum SwapState { IDLE, OPEN }
    private static SwapState state = SwapState.IDLE;
    private static long pressStartTime;
    private static boolean longPressConfirmed;
    private static boolean suppressNextTooltip;
    private static int suppressTooltipFrames;
    private static boolean configLogged = false;

    public static void init(SwapConfig cfg) {
        LOGGER.info("[SusInstantSwap] v1.1.0 初始化客户端交换逻辑 (Forge 1.20.1)...");
        config = cfg;
        SWAP_KEY = new KeyMapping("key.susinstantswap.swap",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_ALT,
                "key.categories.susinstantswap");
        MinecraftForge.EVENT_BUS.register(InstantSwapClient.class);
    }

    public static void registerKey(RegisterKeyMappingsEvent event) {
        event.register(SWAP_KEY);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();

        if (suppressTooltipFrames > 0) {
            suppressTooltipFrames--;
            if (suppressTooltipFrames == 0) {
                suppressNextTooltip = false;
            }
        }

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

        if (state == SwapState.OPEN && mc.screen == null) {
            debugLog("界面被外部关闭，重置状态");
            state = SwapState.IDLE;
        }

        boolean creative = mc.gameMode.getPlayerMode() == GameType.CREATIVE;

        if (state == SwapState.OPEN) {
            long elapsed = System.currentTimeMillis() - pressStartTime;
            boolean keyDown = isSwapKeyDown();

            if (config.longPressMode.get()) {
                if (elapsed >= config.holdThresholdMs.get() && keyDown) {
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
                if (!keyDown) {
                    performSwapAndClose(mc, creative);
                    state = SwapState.IDLE;
                    debugLog("Tick: 经典模式交换完成");
                    return;
                }
            }
        }
    }

    @SubscribeEvent
    public static void onKeyInput(InputEvent.Key event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null) return;

        InputConstants.Key boundKey = SWAP_KEY.getKey();
        if (boundKey.getType() != InputConstants.Type.KEYSYM) return;
        if (event.getKey() != boundKey.getValue()) return;
        int action = event.getAction();
        if (action != GLFW.GLFW_PRESS && action != GLFW.GLFW_RELEASE) return;

        boolean keyDown = (action == GLFW.GLFW_PRESS);
        boolean creative = mc.gameMode.getPlayerMode() == GameType.CREATIVE;

        if (keyDown) {
            if (!canInteract(mc)) return;

            boolean alreadyOnInventory = isInventoryScreen(mc.screen);

            if (state == SwapState.IDLE) {
                if (alreadyOnInventory) {
                    if (!isVanillaInventoryKey()) {
                        mc.setScreen(null);
                    }
                    debugLog("KeyEvent: 物品栏已打开 → 关闭物品栏");
                    return;
                }

                if (mc.screen instanceof AbstractContainerScreen) {
                    mc.player.closeContainer();
                    debugLog("KeyEvent: 关闭容器界面（模拟原版E键）");
                    return;
                }
                if (mc.screen != null) {
                    return;
                }

                openInventoryAndPositionCursor(mc, creative);
                state = SwapState.OPEN;
                pressStartTime = System.currentTimeMillis();
                longPressConfirmed = false;
                debugLog("KeyEvent: 打开物品栏 → OPEN" +
                        (config.longPressMode.get() ? "" : "（经典模式）"));
            }
        } else {
            if (state == SwapState.OPEN) {
                long elapsed = System.currentTimeMillis() - pressStartTime;
                if (!config.longPressMode.get()
                        || elapsed >= config.holdThresholdMs.get()) {
                    performSwapAndClose(mc, creative);
                    debugLog("KeyEvent: 交换完成 (" + elapsed + "ms)");
                }
                state = SwapState.IDLE;
            }
        }
    }

    @SubscribeEvent
    public static void onRenderTooltip(RenderTooltipEvent.Pre event) {
        if (suppressNextTooltip) {
            event.setCanceled(true);
            suppressNextTooltip = false;
            suppressTooltipFrames = 0;
        }
    }

    private static boolean isSwapKeyDown() {
        Minecraft mc = Minecraft.getInstance();
        InputConstants.Key boundKey = SWAP_KEY.getKey();
        if (boundKey.getType() != InputConstants.Type.KEYSYM) return false;
        return GLFW.glfwGetKey(mc.getWindow().getWindow(), boundKey.getValue()) == GLFW.GLFW_PRESS;
    }

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
        return screen instanceof InventoryScreen
                || screen instanceof CreativeModeInventoryScreen;
    }

    private static Screen createInventoryScreen(Minecraft mc, boolean creative) {
        if (creative) {
            // Forge 1.20.1: CreativeModeInventoryScreen has 3-param constructor (Player, FeatureFlagSet, boolean)
            return new CreativeModeInventoryScreen(mc.player,
                    mc.player.connection.enabledFeatures(), true);
        }
        return new InventoryScreen(mc.player);
    }

    private static void openInventoryAndPositionCursor(Minecraft mc, boolean creative) {
        Screen screen = createInventoryScreen(mc, creative);
        preMoveCursorToWindowCorner(mc);
        mc.setScreen(screen);
        positionCursorIfEnabled(mc, screen);
    }

    private static void preMoveCursorToWindowCorner(Minecraft mc) {
        if (!config.mouseReposition.get()) return;
        long handle = mc.getWindow().getWindow();
        int width = mc.getWindow().getWidth();
        int height = mc.getWindow().getHeight();
        GLFW.glfwSetCursorPos(handle, width - 10, height - 10);
    }

    private static void positionCursorIfEnabled(Minecraft mc, Screen screen) {
        if (!config.mouseReposition.get()) return;
        positionCursorToUIBottomRight(mc, screen);
    }

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
        suppressNextTooltip = true;
        suppressTooltipFrames = 2;
    }

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
        // MC 1.20.1: selected 字段可直接访问
        int selectedHotbar = mc.player.getInventory().selected;
        int hotbarMenuSlot = selectedHotbar + 36;

        if (!isAllowedMenuSlot(hoveredIndex) || hoveredIndex == hotbarMenuSlot) {
            LOGGER.warn("[SusInstantSwap] 生存模式交换失败: 不允许的槽位 (悬停={}, 快捷栏={})",
                    hoveredIndex, hotbarMenuSlot);
            return;
        }

        int containerId = mc.player.inventoryMenu.containerId;
        // MC 1.20.1: stateId is private, use getStateId or reflection
        int stateId = getContainerStateId(mc);
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

    private static void performCreativeSwap(Minecraft mc) {
        if (!(mc.screen instanceof CreativeModeInventoryScreen creativeScreen)) {
            LOGGER.warn("[SusInstantSwap] 创造模式交换失败: 当前屏幕不是创造模式物品栏");
            return;
        }

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

        // MC 1.20.1: CONTAINER is not public, use reflection to check
        Object creativeContainer = getCreativeContainer();
        if (creativeContainer != null && hoveredSlot.container == creativeContainer) {
            ItemStack heldItem = mc.player.getInventory().getItem(selected).copy();
            ItemStack item = hoveredSlot.getItem().copyWithCount(1);

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

    private static int getContainerStateId(Minecraft mc) {
        // In MC 1.20.1, stateId is private. Use the getter if available.
        try {
            return (int) mc.player.inventoryMenu.getClass()
                    .getMethod("getStateId").invoke(mc.player.inventoryMenu);
        } catch (Exception e) {
            // Fallback: use 0 (common for single-player)
            return 0;
        }
    }

    private static Object getCreativeContainer() {
        try {
            java.lang.reflect.Field field = CreativeModeInventoryScreen.class
                    .getDeclaredField("CONTAINER");
            field.setAccessible(true);
            return field.get(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static int findFreeBackpackSlot(Minecraft mc) {
        for (int menuSlot = 9; menuSlot <= 35; menuSlot++) {
            if (mc.player.inventoryMenu.getSlot(menuSlot).getItem().isEmpty()) {
                return menuSlot;
            }
        }
        return -1;
    }

    private static void playSwapSound(Minecraft mc) {
        if (!config.soundEnabled.get() || mc.player == null) return;
        mc.player.playSound(SoundEvents.ITEM_PICKUP, 0.8f, 1.0f);
    }

    private static void debugLog(String msg) {
        if (config.debug.get()) {
            LOGGER.info("[SusInstantSwap] {}", msg);
        }
    }
}
