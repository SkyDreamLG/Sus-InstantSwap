package com.susinstantswap.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.Expose;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Path;

/**
 * Sus-InstantSwap configuration for Fabric — Gson JSON based.
 * Config file: config/susinstantswap.json
 * Changes take effect immediately — no restart needed.
 */
public class SwapConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(SwapConfig.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().excludeFieldsWithoutExposeAnnotation().create();

    @Expose public boolean longPressMode = true;
    @Expose public int holdThresholdMs = 200;
    @Expose public boolean soundEnabled = true;
    @Expose public boolean debug = false;
    @Expose public boolean mouseReposition = true;
    @Expose public boolean guiSwapEnabled = false;

    private static SwapConfig INSTANCE;
    private static Path configPath;

    public static SwapConfig get() {
        if (INSTANCE == null) {
            INSTANCE = new SwapConfig();
            configPath = FabricLoader.getInstance().getConfigDir().resolve("susinstantswap.json");
            load();
        }
        return INSTANCE;
    }

    public static void load() {
        File file = configPath.toFile();
        if (file.exists()) {
            try (Reader reader = new FileReader(file)) {
                SwapConfig loaded = GSON.fromJson(reader, SwapConfig.class);
                // Merge loaded values
                INSTANCE.longPressMode = loaded.longPressMode;
                INSTANCE.holdThresholdMs = loaded.holdThresholdMs;
                INSTANCE.soundEnabled = loaded.soundEnabled;
                INSTANCE.debug = loaded.debug;
                INSTANCE.mouseReposition = loaded.mouseReposition;
                INSTANCE.guiSwapEnabled = loaded.guiSwapEnabled;
                LOGGER.info("[SusInstantSwap] 配置已加载: longPressMode={}, holdThresholdMs={}, soundEnabled={}, debug={}, mouseReposition={}, guiSwapEnabled={}",
                        INSTANCE.longPressMode, INSTANCE.holdThresholdMs, INSTANCE.soundEnabled, INSTANCE.debug, INSTANCE.mouseReposition, INSTANCE.guiSwapEnabled);
            } catch (Exception e) {
                LOGGER.warn("[SusInstantSwap] 配置加载失败，使用默认值: {}", e.getMessage());
            }
        } else {
            save();
            LOGGER.info("[SusInstantSwap] 已创建默认配置文件");
        }
    }

    public static void save() {
        try {
            configPath.getParent().toFile().mkdirs();
            try (Writer writer = new FileWriter(configPath.toFile())) {
                GSON.toJson(INSTANCE, writer);
            }
        } catch (Exception e) {
            LOGGER.warn("[SusInstantSwap] 配置保存失败: {}", e.getMessage());
        }
    }
}
