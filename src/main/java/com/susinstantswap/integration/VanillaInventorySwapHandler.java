package com.susinstantswap.integration;

import com.mojang.logging.LogUtils;
import com.susinstantswap.api.InstantSwapHandler;
import com.susinstantswap.api.SwapSlotInfo;
import com.susinstantswap.config.InstantSwapConfig;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import org.slf4j.Logger;

import java.util.Objects;

/**
 * 原版物品栏的瞬间交换处理器。
 * <p>
 * 当玩家打开原版 {@link InventoryScreen}（生存/冒险模式）
 * 或 {@link CreativeModeInventoryScreen}（创造模式）时执行交换。
 * 也可作为 fallback 处理器：当其他 handler 返回 false 时，
 * 系统会尝试用此 handler 对当前屏幕执行原版交换逻辑。
 * </p>
 * <p>
 * 此 handler 由 ServiceLoader 自动发现并注册。
 * </p>
 */
public class VanillaInventorySwapHandler implements InstantSwapHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public Screen onKeyDown(Minecraft mc) {
        if (mc.player == null || mc.gameMode == null) {
            return null;
        }

        boolean creative = mc.gameMode.hasInfiniteItems();
        boolean survival = mc.gameMode.getPlayerMode() == GameType.SURVIVAL
                || mc.gameMode.getPlayerMode() == GameType.ADVENTURE;

        if (!creative && !survival) {
            LOGGER.debug("[SusInstantSwap/Vanilla] 不支持的玩家模式，跳过");
            return null;
        }

        LOGGER.info("[SusInstantSwap/Vanilla] 按键按下，打开原版物品栏 ({})",
                creative ? "创造模式" : "生存模式");
        return new InventoryScreen(mc.player);
    }

    @Override
    public boolean onKeyUp(Minecraft mc, Screen screen) {
        if (mc.player == null || mc.gameMode == null) {
            return false;
        }

        boolean creative = mc.gameMode.hasInfiniteItems();
        if (creative) {
            return performCreativeSwap(mc, screen);
        } else {
            return performSurvivalSwap(mc, screen);
        }
    }

    @Override
    public String getName() {
        return "VanillaInventory";
    }

    /**
     * 在容器菜单的槽位列表中查找属于玩家物品栏且 containerSlot 匹配的 Slot。
     * <p>
     * 不依赖固定的容器菜单索引布局，可适配 Traveler's Backpack 等
     * 第三方背包 GUI 的不同槽位排列。
     * </p>
     *
     * @param screen        当前容器屏幕
     * @param containerSlot 玩家物品栏中的槽位索引 (0-35: 快捷栏 0-8, 背包 9-35)
     * @return 匹配的 Slot，未找到则返回 null
     */
    private static Slot findPlayerInventorySlot(AbstractContainerScreen<?> screen, int containerSlot) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return null;
        for (Slot slot : screen.getMenu().slots) {
            if (slot.container == mc.player.getInventory()
                    && slot.getContainerSlot() == containerSlot) {
                return slot;
            }
        }
        return null;
    }

    /**
     * 使用 handler 提供的修正槽位索引执行生存模式 fallback 交换。
     * <p>
     * 直接使用给定的槽位索引构建并发送 SWAP 数据包，跳过 getSlotUnderMouse()
     * 和 findPlayerInventorySlot 的查找步骤。
     * </p>
     *
     * @param mc     Minecraft 客户端实例
     * @param screen 当前屏幕
     * @param slots  handler 提供的修正槽位信息
     */
    public static void performFallbackSwap(Minecraft mc, Screen screen, SwapSlotInfo slots) {
        if (mc.player == null || mc.getConnection() == null) return;

        // 校验悬停槽位必须存在且有物品（与原版 survival 逻辑保持一致）
        Slot hoveredSlot = mc.player.inventoryMenu.getSlot(slots.menuSlotIndex());
        if (!hoveredSlot.hasItem()) {
            LOGGER.warn("[SusInstantSwap/Vanilla] Fallback 交换失败: 槽位{}无物品 (屏幕: {})",
                    slots.menuSlotIndex(),
                    screen != null ? screen.getClass().getSimpleName() : "null");
            return;
        }

        int containerId = mc.player.inventoryMenu.containerId;
        int stateId = mc.player.inventoryMenu.getStateId();
        Int2ObjectOpenHashMap<ItemStack> changedSlots = new Int2ObjectOpenHashMap<>();
        ServerboundContainerClickPacket packet = new ServerboundContainerClickPacket(
                containerId, stateId, slots.menuSlotIndex(), slots.hotbarSlotIndex(),
                ClickType.SWAP, ItemStack.EMPTY, changedSlots);
        mc.getConnection().send(packet);
        mc.player.playNotifySound(SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.8f, 1.0f);
        LOGGER.info("[SusInstantSwap/Vanilla] Fallback 交换完成: 槽位{} <-> 快捷栏{} (屏幕: {})",
                slots.menuSlotIndex(), slots.hotbarSlotIndex(),
                screen != null ? screen.getClass().getSimpleName() : "null");
    }

    // ==================== 生存模式交换 ====================

    /**
     * 执行生存/冒险模式下的物品交换。
     * 支持任何 {@link AbstractContainerScreen} 或其子类（包括第三方背包 GUI），
     * 以便在 fallback 场景中也能正常工作。
     * <p>
     * 通过 {@link Slot#getContainerSlot()} 动态定位玩家物品栏槽位在容器菜单中的位置，
     * 而非依赖硬编码的索引偏移，从而兼容不同容器布局。
     * </p>
     *
     * @return true 表示交换成功，false 表示失败
     */
    private boolean performSurvivalSwap(Minecraft mc, Screen screen) {
        // 支持 AbstractContainerScreen 及其所有子类，用于 fallback
        if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
            LOGGER.warn("[SusInstantSwap/Vanilla] 生存模式交换失败: 当前屏幕不是容器屏幕 ({})",
                    screen != null ? screen.getClass().getSimpleName() : "null");
            return false;
        }

        Slot hoveredSlot = containerScreen.getSlotUnderMouse();
        if (hoveredSlot == null) {
            LOGGER.warn("[SusInstantSwap/Vanilla] 生存模式交换失败: 未悬停在任何槽位 (屏幕: {})",
                    screen.getClass().getSimpleName());
            return false;
        }

        int selectedHotbar = 0;
        if (mc.player != null) {
            selectedHotbar = mc.player.getInventory().selected;
        }

        if (!hoveredSlot.hasItem()) {
            // 悬停槽位为空 — 检查是否允许空交换
            if (InstantSwapConfig.isAllowSwapWithEmptySlot()) {
                // 允许：把主手物品放入目标空槽位
                ItemStack heldItem = mc.player.getInventory().getItem(selectedHotbar);
                if (heldItem.isEmpty()) {
                    LOGGER.warn("[SusInstantSwap/Vanilla] 生存模式空交换失败: 主手也无物品");
                    return false;
                }
                // 验证悬停槽位属于玩家物品栏且位于背包区域
                if (hoveredSlot.container != mc.player.getInventory()) {
                    LOGGER.warn("[SusInstantSwap/Vanilla] 生存模式空交换失败: 悬停槽位不属于玩家物品栏");
                    return false;
                }
                int playerInvSlot = hoveredSlot.getContainerSlot();
                if (playerInvSlot < 9 || playerInvSlot > 35) {
                    LOGGER.warn("[SusInstantSwap/Vanilla] 生存模式空交换失败: 悬停槽位不在玩家背包区域 (playerInvSlot={})",
                            playerInvSlot);
                    return false;
                }
                Slot hotbarSlot = findPlayerInventorySlot(containerScreen, selectedHotbar);
                if (hotbarSlot == null || hoveredSlot.index == hotbarSlot.index) {
                    LOGGER.warn("[SusInstantSwap/Vanilla] 生存模式空交换失败: 快捷栏槽位位置异常");
                    return false;
                }
                int containerId = mc.player.inventoryMenu.containerId;
                int stateId = mc.player.inventoryMenu.getStateId();
                Int2ObjectOpenHashMap<ItemStack> changedSlots = new Int2ObjectOpenHashMap<>();
                ServerboundContainerClickPacket packet = new ServerboundContainerClickPacket(
                        containerId, stateId, hoveredSlot.index, selectedHotbar,
                        ClickType.SWAP, ItemStack.EMPTY, changedSlots);
                Objects.requireNonNull(mc.getConnection()).send(packet);
                mc.player.playNotifySound(SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.8f, 1.0f);
                LOGGER.info("[SusInstantSwap/Vanilla] 生存模式空交换完成: 主手物品放入槽位{}", hoveredSlot.index);
                return true;
            } else {
                // 不允许：发送消息提示玩家
                LOGGER.warn("[SusInstantSwap/Vanilla] 生存模式交换失败: 悬停槽位无物品");
                mc.player.displayClientMessage(
                        Component.translatable("susinstantswap.message.empty_slot_blocked"), true);
                return false;
            }
        }

        // 验证悬停槽位属于玩家物品栏
        if (hoveredSlot.container != mc.player.getInventory()) {
            LOGGER.warn("[SusInstantSwap/Vanilla] 生存模式交换失败: 悬停槽位不属于玩家物品栏");
            return false;
        }

        int playerInvSlot = hoveredSlot.getContainerSlot();
        // 只允许交换背包存储区 (9-35)，不允许交换快捷栏 (0-8)、盔甲 (36-39) 或副手 (40)
        if (playerInvSlot < 9 || playerInvSlot > 35) {
            LOGGER.warn("[SusInstantSwap/Vanilla] 生存模式交换失败: 悬停槽位不在玩家背包区域 (playerInvSlot={})",
                    playerInvSlot);
            return false;
        }

        // 在容器菜单中动态查找选中快捷栏槽位，不依赖硬编码索引
        Slot hotbarSlot = findPlayerInventorySlot(containerScreen, selectedHotbar);
        if (hotbarSlot == null) {
            LOGGER.warn("[SusInstantSwap/Vanilla] 生存模式交换失败: 无法找到快捷栏槽位{}在容器菜单中的位置",
                    selectedHotbar);
            return false;
        }

        // 避免同槽位交换
        if (hoveredSlot.index == hotbarSlot.index) {
            LOGGER.warn("[SusInstantSwap/Vanilla] 生存模式交换失败: 悬停槽位与快捷栏槽位相同");
            return false;
        }

        int containerId = mc.player.inventoryMenu.containerId;
        int stateId = mc.player.inventoryMenu.getStateId();
        Int2ObjectOpenHashMap<ItemStack> changedSlots = new Int2ObjectOpenHashMap<>();
        ServerboundContainerClickPacket packet = new ServerboundContainerClickPacket(
                containerId, stateId, hoveredSlot.index, selectedHotbar,
                ClickType.SWAP, ItemStack.EMPTY, changedSlots);
        Objects.requireNonNull(mc.getConnection()).send(packet);
        mc.player.playNotifySound(SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.8f, 1.0f);
        LOGGER.info("[SusInstantSwap/Vanilla] 生存模式交换完成: 槽位{} (playerInv={}) <-> 快捷栏{} (屏幕: {})",
                hoveredSlot.index, playerInvSlot, selectedHotbar, screen.getClass().getSimpleName());
        return true;
    }

    // ==================== 创造模式交换 ====================

    /**
     * 执行创造模式下的物品交换。
     * 支持 {@link CreativeModeInventoryScreen} 及任何 {@link AbstractContainerScreen}，
     * 以便在 fallback 场景中也能正常工作。
     *
     * @return true 表示交换成功，false 表示失败
     */
    private boolean performCreativeSwap(Minecraft mc, Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
            LOGGER.warn("[SusInstantSwap/Vanilla] 创造模式交换失败: 当前屏幕不是容器屏幕 ({})",
                    screen != null ? screen.getClass().getSimpleName() : "null");
            return false;
        }

        Slot hoveredSlot = containerScreen.getSlotUnderMouse();
        if (hoveredSlot == null || !hoveredSlot.hasItem()) {
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        Component.translatable("susinstantswap.message.empty_slot_blocked"), true);
            }
            LOGGER.warn("[SusInstantSwap/Vanilla] 创造模式交换失败: 悬停槽位无物品 (屏幕: {})",
                    screen.getClass().getSimpleName());
            return false;
        }

        int selected = 0;
        if (mc.player != null) {
            selected = mc.player.getInventory().selected;
        }
        int heldSlotIndex = selected + 36;
        boolean didSwap = false;

        // 创造模式物品栏的专属逻辑
        if (screen instanceof CreativeModeInventoryScreen creativeScreen) {
            if (hoveredSlot.container == CreativeModeInventoryScreen.CONTAINER) {
                ItemStack item = hoveredSlot.getItem().copyWithCount(1);
                if (mc.gameMode != null) {
                    mc.gameMode.handleCreativeModeItemAdd(item, heldSlotIndex);
                }
                didSwap = true;
                LOGGER.info("[SusInstantSwap/Vanilla] 创造模式交换: 从创造物品栏拿取物品到快捷栏{}", selected);

            } else if (hoveredSlot instanceof CreativeModeInventoryScreen.SlotWrapper wrapper) {
                int targetContainerSlot = wrapper.target.getContainerSlot();
                // 只交换玩家背包存储区 (9-35)
                if (wrapper.target.container == mc.player.getInventory()
                        && targetContainerSlot >= 9 && targetContainerSlot <= 35) {
                    int targetMenuSlot = wrapper.target.index;
                    if (targetMenuSlot != heldSlotIndex) {
                        ItemStack targetItem = wrapper.target.getItem().copy();
                        ItemStack heldItem = mc.player.inventoryMenu.getSlot(heldSlotIndex).getItem().copy();
                        if (mc.gameMode != null) {
                            mc.gameMode.handleCreativeModeItemAdd(targetItem, heldSlotIndex);
                        }
                        if (mc.gameMode != null) {
                            mc.gameMode.handleCreativeModeItemAdd(heldItem, targetMenuSlot);
                        }
                        didSwap = true;
                        LOGGER.info("[SusInstantSwap/Vanilla] 创造模式交换: 背包槽位{} (playerInv={}) <-> 快捷栏{}",
                                targetMenuSlot, targetContainerSlot, selected);
                    }
                }
            } else {
                int containerSlot = hoveredSlot.getContainerSlot();
                if (containerSlot >= 0 && containerSlot <= 8) {
                    int hotbarMenuSlot = containerSlot + 36;
                    if (hotbarMenuSlot != heldSlotIndex) {
                        ItemStack hotbarItem = mc.player.getInventory().getItem(containerSlot).copy();
                        ItemStack heldItem = mc.player.getInventory().getItem(selected).copy();
                        if (mc.gameMode != null) {
                            mc.gameMode.handleCreativeModeItemAdd(hotbarItem, heldSlotIndex);
                        }
                        if (mc.gameMode != null) {
                            mc.gameMode.handleCreativeModeItemAdd(heldItem, hotbarMenuSlot);
                        }
                        didSwap = true;
                        LOGGER.info("[SusInstantSwap/Vanilla] 创造模式交换: 快捷栏槽位{} <-> 快捷栏{}",
                                containerSlot, selected);
                    }
                }
            }
        } else {
            // Fallback: 非创造模式物品栏屏幕，使用动态槽位定位执行 SWAP
            // 验证悬停槽位属于玩家物品栏
            if (mc.player != null && hoveredSlot.container != mc.player.getInventory()) {
                LOGGER.warn("[SusInstantSwap/Vanilla] 创造模式 fallback 交换失败: 悬停槽位不属于玩家物品栏");
            }
        }

        if (didSwap) {
            mc.player.playNotifySound(SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.8f, 1.0f);
        }
        return didSwap;
    }
}