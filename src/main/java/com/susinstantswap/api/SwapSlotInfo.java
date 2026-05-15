package com.susinstantswap.api;

/**
 * 用于原版 fallback 交换的槽位信息。
 * <p>
 * 当某个 {@link InstantSwapHandler} 的 {@code onKeyUp} 返回 false 时，
 * 系统会尝试用原版逻辑重试。handler 可以通过
 * {@link InstantSwapHandler#getFallbackSwapSlots} 返回此记录，
 * 以提供修正后的槽位索引，适配某些模组对容器菜单槽位 ID 的特殊映射。
 * </p>
 *
 * @param menuSlotIndex   容器菜单中悬停物品所在的槽位索引（{@link net.minecraft.world.inventory.Slot#index}）
 * @param hotbarSlotIndex 快捷栏槽位索引 (0-8)，即 {@link net.minecraft.world.entity.player.Inventory#selected}
 */
public record SwapSlotInfo(int menuSlotIndex, int hotbarSlotIndex) {
}