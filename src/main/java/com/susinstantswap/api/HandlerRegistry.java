package com.susinstantswap.api;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ServiceLoader;

/**
 * 瞬间交换处理器注册中心。
 * <p>
 * 维护所有已注册的 {@link InstantSwapHandler} 实例并提供遍历查询。
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
 * handler 的查询顺序即注册顺序。ServiceLoader 发现的 handler 会先被注册。
 * </p>
 *
 * @see InstantSwapHandler
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
        LOGGER.info("[SusInstantSwap] 注册交换处理器: {}", handler.getName());
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
}