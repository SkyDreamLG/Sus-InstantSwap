package com.susinstantswap.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Cloth Config 游戏内配置界面。
 * 仅在 Cloth Config API 已安装时由 ModMenuIntegration 构造，避免类加载异常。
 */
public class ClothConfigFactory implements ConfigScreenFactory<Screen> {

    @Override
    public Screen create(Screen parent) {
        SwapConfig config = SwapConfig.get();

        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.translatable("config.susinstantswap.title"))
                .setSavingRunnable(SwapConfig::save);

        ConfigEntryBuilder eb = builder.entryBuilder();
        ConfigCategory cat = builder.getOrCreateCategory(
                Component.translatable("config.susinstantswap.category.general"));

        cat.addEntry(eb.startBooleanToggle(
                        Component.translatable("config.susinstantswap.longPressMode"),
                        config.longPressMode)
                .setDefaultValue(true)
                .setTooltip(Component.translatable("config.susinstantswap.longPressMode.tooltip"))
                .setSaveConsumer(v -> config.longPressMode = v)
                .build());

        cat.addEntry(eb.startIntSlider(
                        Component.translatable("config.susinstantswap.holdThresholdMs"),
                        config.holdThresholdMs, 50, 1000)
                .setDefaultValue(200)
                .setTooltip(Component.translatable("config.susinstantswap.holdThresholdMs.tooltip"))
                .setSaveConsumer(v -> config.holdThresholdMs = v)
                .build());

        cat.addEntry(eb.startBooleanToggle(
                        Component.translatable("config.susinstantswap.soundEnabled"),
                        config.soundEnabled)
                .setDefaultValue(true)
                .setTooltip(Component.translatable("config.susinstantswap.soundEnabled.tooltip"))
                .setSaveConsumer(v -> config.soundEnabled = v)
                .build());

        cat.addEntry(eb.startBooleanToggle(
                        Component.translatable("config.susinstantswap.mouseReposition"),
                        config.mouseReposition)
                .setDefaultValue(true)
                .setTooltip(Component.translatable("config.susinstantswap.mouseReposition.tooltip"))
                .setSaveConsumer(v -> config.mouseReposition = v)
                .build());

        cat.addEntry(eb.startBooleanToggle(
                        Component.translatable("config.susinstantswap.debug"),
                        config.debug)
                .setDefaultValue(false)
                .setTooltip(Component.translatable("config.susinstantswap.debug.tooltip"))
                .setSaveConsumer(v -> config.debug = v)
                .build());

        return builder.build();
    }
}
