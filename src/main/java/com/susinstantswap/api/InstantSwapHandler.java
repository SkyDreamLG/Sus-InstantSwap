package com.susinstantswap.api;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * 瞬间交换处理器接口。
 * <p>
 * 附属模组实现此接口来为其他背包模组（如 Traveler's Backpack 等）提供适配。
 * 实现类需要通过以下方式之一注册到 {@link HandlerRegistry}：
 * <ul>
 *   <li>在附属模组的 jar 中通过 Java ServiceLoader 声明
 *       （文件：META-INF/services/com.susinstantswap.api.InstantSwapHandler）</li>
 *   <li>在附属模组的构造器中直接调用 {@link HandlerRegistry#register(InstantSwapHandler)}</li>
 * </ul>
 * </p>
 *
 * <h3>生命周期</h3>
 * <p>系统允许注册任意数量的第三方 handler。
 * 根据用户配置 {@code preferThirdParty} 和背包穿戴状态决定使用哪个 handler：</p>
 * <ol>
 *   <li><b>按键按下时</b>：若当前无任何屏幕打开：
 *       <ul>
 *         <li>{@code preferThirdParty=true}：遍历所有第三方 handler 的
 *             {@link #isEquipped(Minecraft)}，若某个返回 true 则使用该 handler
 *             （同时只会有一个背包处于穿戴状态）；若无穿着则使用原版 handler</li>
 *         <li>{@code preferThirdParty=false}：始终使用原版 handler</li>
 *       </ul>
 *       系统记录实际打开屏幕的 handler。</li>
 *   <li><b>按键释放时</b>：由记录的 handler 执行
 *       {@link #onKeyUp(Minecraft, Screen)}。若未记录任何 handler
 *       （如屏幕被外部关闭后重新打开），回退到原版交换逻辑。</li>
 *   <li>若 handler 的 onKeyUp 返回 false：
 *       <ul>
 *         <li>第三方 handler 失败 → 原版 fallback（优先用 {@link #getFallbackSwapSlots} 修正槽位）</li>
 *         <li>原版 handler 失败 → 尝试第三方 handler（若有穿着）</li>
 *       </ul></li>
 * </ol>
 */
public interface InstantSwapHandler {

    /**
     * 当交换按键被按下且当前没有打开任何屏幕时调用。
     * <p>
     * handler 可以在此方法中打开自己管理的容器屏幕（例如旅行者背包的 GUI）。
     * 如果此 handler 不想或不能在当前状态下处理，应返回 null，
     * 此时会继续询问下一个 handler。
     * </p>
     *
     * @param mc Minecraft 客户端实例
     * @return 打开的屏幕实例，或 null 表示此 handler 不处理本次按键
     */
    Screen onKeyDown(Minecraft mc);

    /**
     * 当交换按键被释放时调用，执行物品交换逻辑。
     * <p>
     * 传入的 screen 参数是当前正在显示的屏幕，
     * 即按键按下时由本 handler 的 {@link #onKeyDown} 返回的屏幕实例。
     * handler 应基于此屏幕执行交换操作。
     * </p>
     *
     * @param mc     Minecraft 客户端实例
     * @param screen 当前打开的屏幕（由本 handler 打开）
     * @return true 表示交换成功完成，false 表示交换失败（此时系统可能 fallback 到原版逻辑）
     */
    boolean onKeyUp(Minecraft mc, Screen screen);

    /**
     * 当此 handler 的 {@link #onKeyUp} 返回 false，系统即将 fallback 到
     * 原版交换逻辑时调用。handler 可返回修正后的槽位索引，
     * 以适配某些模组对容器菜单槽位 ID 的特殊映射。
     * <p>
     * 返回 null 表示 handler 不提供修正，由原版逻辑自行判断槽位。
     * </p>
     *
     * @param mc     Minecraft 客户端实例
     * @param screen 当前打开的屏幕
     * @return 修正后的槽位信息，或 null 表示不需要修正
     */
    default SwapSlotInfo getFallbackSwapSlots(Minecraft mc, Screen screen) {
        return null;
    }

    /**
     * 查询此 handler 对应的背包是否处于穿戴状态。
     * <p>
     * 当 {@code preferThirdParty=true} 时，系统会遍历所有第三方 handler
     * 调用此方法。返回 true 的 handler 将被用于执行打开 GUI 和交换操作。
     * 同时只应有一个 handler 返回 true（玩家同时只能穿戴一个背包）。
     * </p>
     * <p>
     * 默认返回 false。原版 handler 不需要覆盖此方法。
     * </p>
     *
     * @param mc Minecraft 客户端实例
     * @return true 表示对应背包已穿戴
     */
    default boolean isEquipped(Minecraft mc) {
        return false;
    }

    /**
     * 返回此 handler 的显示名称，用于日志输出。
     *
     * @return 人类可读的名称
     */
    default String getName() {
        return getClass().getSimpleName();
    }
}