package com.susinstantswap.integration;

import com.mojang.logging.LogUtils;
import com.susinstantswap.api.InstantSwapHandler;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
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
 * 仅在玩家打开原版 {@link InventoryScreen}（生存/冒险模式）
 * 或 {@link CreativeModeInventoryScreen}（创造模式）时生效。
 * 如果当前屏幕不是这两种，则不接管交换流程。
 * </p>
 * <p>
 * 此 handler 由 ServiceLoader 自动发现并注册。其内部实现
 * 与原版本保持了相同的交换逻辑。
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
    public void onKeyUp(Minecraft mc, Screen screen) {
        if (mc.player == null || mc.gameMode == null) {
            return;
        }

        boolean creative = mc.gameMode.hasInfiniteItems();
        if (creative) {
            performCreativeSwap(mc, screen);
        } else {
            performSurvivalSwap(mc, screen);
        }
    }

    @Override
    public String getName() {
        return "VanillaInventory";
    }

    private static boolean isAllowedMenuSlot(int menuSlot) {
        return menuSlot >= 9 && menuSlot <= 44;
    }

    // ==================== 生存模式交换 ====================

    private void performSurvivalSwap(Minecraft mc, Screen screen) {
        if (!(screen instanceof InventoryScreen inventoryScreen)) {
            LOGGER.warn("[SusInstantSwap/Vanilla] 生存模式交换失败: 当前屏幕不是物品栏");
            return;
        }

        Slot hoveredSlot = inventoryScreen.getSlotUnderMouse();
        if (hoveredSlot == null) {
            LOGGER.warn("[SusInstantSwap/Vanilla] 生存模式交换失败: 未悬停在任何槽位");
            return;
        }

        if (!hoveredSlot.hasItem()) {
            LOGGER.warn("[SusInstantSwap/Vanilla] 生存模式交换失败: 悬停槽位无物品");
            return;
        }

        int hoveredIndex = hoveredSlot.index;
        int selectedHotbar = mc.player.getInventory().selected;
        int hotbarMenuSlot = selectedHotbar + 36;

        if (!isAllowedMenuSlot(hoveredIndex) || hoveredIndex == hotbarMenuSlot) {
            LOGGER.warn("[SusInstantSwap/Vanilla] 生存模式交换失败: 不允许的槽位 (悬停={}, 快捷栏={})",
                    hoveredIndex, hotbarMenuSlot);
            return;
        }

        int containerId = mc.player.inventoryMenu.containerId;
        int stateId = mc.player.inventoryMenu.getStateId();
        Int2ObjectOpenHashMap<ItemStack> changedSlots = new Int2ObjectOpenHashMap<>();
        ServerboundContainerClickPacket packet = new ServerboundContainerClickPacket(
                containerId, stateId, hoveredIndex, selectedHotbar,
                ClickType.SWAP, ItemStack.EMPTY, changedSlots);
        Objects.requireNonNull(mc.getConnection()).send(packet);
        mc.player.playNotifySound(SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.8f, 1.0f);
        LOGGER.info("[SusInstantSwap/Vanilla] 生存模式交换完成: 槽位{} <-> 快捷栏{}",
                hoveredIndex, selectedHotbar);
    }

    // ==================== 创造模式交换 ====================

    private void performCreativeSwap(Minecraft mc, Screen screen) {
        if (!(screen instanceof CreativeModeInventoryScreen creativeScreen)) {
            LOGGER.warn("[SusInstantSwap/Vanilla] 创造模式交换失败: 当前屏幕不是创造模式物品栏");
            return;
        }

        Slot hoveredSlot = creativeScreen.getSlotUnderMouse();
        if (hoveredSlot == null || !hoveredSlot.hasItem()) {
            LOGGER.warn("[SusInstantSwap/Vanilla] 创造模式交换失败: 悬停槽位无物品");
            return;
        }

        int selected = mc.player.getInventory().selected;
        int heldSlotIndex = selected + 36;
        boolean didSwap = false;

        if (hoveredSlot.container == CreativeModeInventoryScreen.CONTAINER) {
            ItemStack item = hoveredSlot.getItem().copyWithCount(1);
            mc.gameMode.handleCreativeModeItemAdd(item, heldSlotIndex);
            didSwap = true;
            LOGGER.info("[SusInstantSwap/Vanilla] 创造模式交换: 从创造物品栏拿取物品到快捷栏{}", selected);

        } else if (hoveredSlot instanceof CreativeModeInventoryScreen.SlotWrapper wrapper) {
            int targetMenuSlot = wrapper.target.index;
            if (isAllowedMenuSlot(targetMenuSlot) && targetMenuSlot != heldSlotIndex) {
                ItemStack targetItem = mc.player.inventoryMenu.getSlot(targetMenuSlot).getItem().copy();
                ItemStack heldItem = mc.player.inventoryMenu.getSlot(heldSlotIndex).getItem().copy();
                mc.gameMode.handleCreativeModeItemAdd(targetItem, heldSlotIndex);
                mc.gameMode.handleCreativeModeItemAdd(heldItem, targetMenuSlot);
                didSwap = true;
                LOGGER.info("[SusInstantSwap/Vanilla] 创造模式交换: 背包槽位{} <-> 快捷栏{}",
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
                    LOGGER.info("[SusInstantSwap/Vanilla] 创造模式交换: 快捷栏槽位{} <-> 快捷栏{}",
                            containerSlot, selected);
                }
            }
        }

        if (didSwap) {
            mc.player.playNotifySound(SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.8f, 1.0f);
        }
    }
}