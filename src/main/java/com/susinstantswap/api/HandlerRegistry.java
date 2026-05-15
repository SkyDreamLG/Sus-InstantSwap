package com.susinstantswap.api;

import com.mojang.logging.LogUtils;
import com.susinstantswap.integration.VanillaInventorySwapHandler;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ServiceLoader;

/**
 * 瞬间交换处理器注册中心。
 * <p>
 * 维护所有已注册的 {@link InstantSwapHandler} 实例。
 * 支持两种注册方式：
 * </p>
 * <ol>
 *   <li><b>Java ServiceLoader 自动发现</b>：
 *       附属模组在 jar 中提供
 *       {@code META-INF/services/com.susinstantswap.api.InstantSwapHandler} 文件，
 *       列出实现类的全限定名。模组加载时会自动扫描并注册。</li>
 *   <li><b>直接 API 注册</b>：
 *       附属模组在其构造器中直接调用 {@link #register(InstantSwapHandler)}。</li>
 * </ol>
 * <p>
 * <b>注意</b>：第三方 handler 可以注册任意数量，不再限制只允许一个。
 * 系统通过 {@link InstantSwapHandler#isEquipped(Minecraft)} 判断使用哪一个。
 * </p>
 *
 * @see InstantSwapHandler
 * @see VanillaInventorySwapHandler
 */
public final class HandlerRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final List<InstantSwapHandler> HANDLERS = new ArrayList<>();
    private static boolean discovered = false;

    private HandlerRegistry() {
        // 工具类，不可实例化
    }

    /**
     * 通过 Java ServiceLoader 自动扫描并注册所有 handler 实现。
     * <p>
     * 此方法应在模组主构造器中调用，确保所有模组（包括附属）都已加载。
     * 重复调用不会重复扫描。
     * </p>
     *
     * @throws IllegalStateException 如果发现超过一个第三方 handler
     */
    public static void discover() {
        if (discovered) {
            LOGGER.debug("[SusInstantSwap] ServiceLoader 已扫描，跳过重复扫描");
            return;
        }
        discovered = true;
        LOGGER.info("[SusInstantSwap] 开始通过 ServiceLoader 扫描交换处理器...");
        ServiceLoader<InstantSwapHandler> loader = ServiceLoader.load(InstantSwapHandler.class);
        int count = 0;
        for (InstantSwapHandler handler : loader) {
            register(handler);
            count++;
        }
        LOGGER.info("[SusInstantSwap] ServiceLoader 扫描完成，共发现 {} 个处理器", count);
    }

    /**
     * 注册一个 handler。
     * <p>
     * 重复注册同一个实例会被忽略。
     * 允许注册任意数量的第三方 handler（不再限制只允许一个），
     * 系统通过 {@link InstantSwapHandler#isEquipped(Minecraft)} 判断使用哪一个。
     * </p>
     *
     * @param handler 要注册的 handler
     */
    public static void register(InstantSwapHandler handler) {
        if (HANDLERS.contains(handler)) {
            LOGGER.warn("[SusInstantSwap] Handler '{}' 已注册，跳过重复注册", handler.getName());
            return;
        }

        HANDLERS.add(handler);
        String type = handler instanceof VanillaInventorySwapHandler ? "原版" : "第三方";
        LOGGER.info("[SusInstantSwap] 注册{}交换处理器: {}", type, handler.getName());
    }

    /**
     * 注销一个 handler。
     *
     * @param handler 要注销的 handler
     */
    public static void unregister(InstantSwapHandler handler) {
        if (HANDLERS.remove(handler)) {
            LOGGER.info("[SusInstantSwap] 注销交换处理器: {}", handler.getName());
        }
    }

    /**
     * 返回所有已注册 handler 的只读视图。
     *
     * @return handler 列表
     */
    public static List<InstantSwapHandler> getHandlers() {
        return Collections.unmodifiableList(HANDLERS);
    }

    /**
     * 获取所有第三方（非原版）handler 的列表。
     *
     * @return 第三方 handler 列表（可能为空）
     */
    public static List<InstantSwapHandler> getThirdPartyHandlers() {
        List<InstantSwapHandler> result = new ArrayList<>();
        for (InstantSwapHandler handler : HANDLERS) {
            if (!(handler instanceof VanillaInventorySwapHandler)) {
                result.add(handler);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * 在有配置要求时，根据背包穿戴状态查找已装备的第三方 handler。
     * <p>
     * 遍历所有第三方 handler 调用 {@link InstantSwapHandler#isEquipped(Minecraft)}，
     * 返回第一个返回 true 的 handler（同时只应有一个背包处于穿戴状态）。
     * </p>
     *
     * @param mc Minecraft 客户端实例
     * @return 装备中的第三方 handler，无装备时返回 null
     */
    public static InstantSwapHandler getEquippedThirdPartyHandler(Minecraft mc) {
        for (InstantSwapHandler handler : HANDLERS) {
            if (!(handler instanceof VanillaInventorySwapHandler) && handler.isEquipped(mc)) {
                return handler;
            }
        }
        return null;
    }

    /**
     * 获取原版交换 handler 实例。
     *
     * @return VanillaInventorySwapHandler 实例，如果没有注册则返回 null
     */
    public static VanillaInventorySwapHandler getVanillaHandler() {
        for (InstantSwapHandler handler : HANDLERS) {
            if (handler instanceof VanillaInventorySwapHandler vh) {
                return vh;
            }
        }
        return null;
    }

    /**
     * 根据名称查找已注册的 handler。
     *
     * @param name handler 的 {@link InstantSwapHandler#getName()} 返回值
     * @return 匹配的 handler，未找到则返回 null
     */
    public static InstantSwapHandler findByName(String name) {
        for (InstantSwapHandler handler : HANDLERS) {
            if (handler.getName().equals(name)) {
                return handler;
            }
        }
        return null;
    }
}