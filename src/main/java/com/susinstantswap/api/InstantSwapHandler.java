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
 * <p>当玩家按下交换按键（默认 Left Alt）时：</p>
 * <ol>
 *   <li>遍历所有已注册的 handler，调用 {@link #onKeyDown(Minecraft)}。
 *       第一个返回非 null Screen 的 handler 将"接管"本次交换流程。</li>
 *   <li>按键释放时，调用该 handler 的 {@link #onKeyUp(Minecraft, Screen)} 执行实际的交换逻辑。</li>
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
     * 传入的 screen 参数是按键按下时由 {@link #onKeyDown(Minecraft)} 打开的屏幕，
     * 也就是当前正在显示的屏幕。handler 应基于此屏幕执行交换操作。
     * </p>
     *
     * @param mc     Minecraft 客户端实例
     * @param screen 当前打开的屏幕（由 onKeyDown 返回）
     */
    void onKeyUp(Minecraft mc, Screen screen);

    /**
     * 返回此 handler 的显示名称，用于日志输出。
     *
     * @return 人类可读的名称
     */
    default String getName() {
        return getClass().getSimpleName();
    }
}