package com.susinstantswap.config;

import com.susinstantswap.SusInstantSwapMod;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * Forge 原生配置界面（零外部依赖，仅 MC 组件）。
 *
 * 运行时配置修改通过 SwapConfig.Runtime 静态字段即时生效，
 * 同时写入 TOML 文件保证重启后持久化。
 */
public class ForgeConfigScreen extends Screen {

    private final Screen parent;

    private static final int BUTTON_WIDTH = 200;
    private static final int SLIDER_WIDTH = 200;
    private static final int WIDGET_HEIGHT = 20;
    private static final int SPACING = 24;

    public ForgeConfigScreen(Screen parent) {
        super(Component.translatable("config.susinstantswap.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int y = 40;

        // ── 长按/短按模式开关 ──
        addRenderableWidget(createToggle(centerX, y,
                "config.susinstantswap.longPressMode",
                SwapConfig.longPressModeRuntime,
                v -> SwapConfig.longPressModeRuntime = v));
        y += SPACING;

        // ── 长按阈值滑块 ──
        addRenderableWidget(new HoldThresholdSlider(
                centerX - SLIDER_WIDTH / 2, y, SLIDER_WIDTH, WIDGET_HEIGHT));
        y += SPACING;

        // ── 交换音效开关 ──
        addRenderableWidget(createToggle(centerX, y,
                "config.susinstantswap.soundEnabled",
                SwapConfig.soundEnabledRuntime,
                v -> SwapConfig.soundEnabledRuntime = v));
        y += SPACING;

        // ── 鼠标重定位开关 ──
        addRenderableWidget(createToggle(centerX, y,
                "config.susinstantswap.mouseReposition",
                SwapConfig.mouseRepositionRuntime,
                v -> SwapConfig.mouseRepositionRuntime = v));
        y += SPACING;

        // ── 界面中更换开关 ──
        addRenderableWidget(createToggle(centerX, y,
                "config.susinstantswap.guiSwapEnabled",
                SwapConfig.guiSwapEnabledRuntime,
                v -> SwapConfig.guiSwapEnabledRuntime = v));
        y += SPACING;

        // ── 调试日志开关 ──
        addRenderableWidget(createToggle(centerX, y,
                "config.susinstantswap.debug",
                SwapConfig.debugRuntime,
                v -> SwapConfig.debugRuntime = v));
        y += SPACING;

        // ── 完成按钮 ──
        y += 12;
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, btn -> saveAndClose())
                .bounds(centerX - 50, y, 100, WIDGET_HEIGHT)
                .build());
    }

    private Button createToggle(int centerX, int y, String key,
                                boolean initial, java.util.function.Consumer<Boolean> onToggle) {
        final boolean[] state = {initial};
        Button btn = Button.builder(makeToggleText(key, state[0]), b -> {
                    state[0] = !state[0];
                    onToggle.accept(state[0]);
                    b.setMessage(makeToggleText(key, state[0]));
                })
                .bounds(centerX - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, WIDGET_HEIGHT)
                .build();
        return btn;
    }

    private static Component makeToggleText(String key, boolean value) {
        return Component.translatable(key)
                .append(": ")
                .append(value ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 20, 0xFFFFFF);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    /**
     * 保存当前 Runtime 值到 TOML 文件（用于重启持久化）。
     * Runtime 值已被即时修改，无需额外同步。
     */
    private void saveAndClose() {
        Path configFile = FMLPaths.CONFIGDIR.get().resolve("susinstantswap-client.toml");
        try {
            Files.createDirectories(configFile.getParent());
            Files.write(configFile, toTomlContent(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException ignored) {
        }
        onClose();
    }

    private List<String> toTomlContent() {
        return List.of(
                "# Su's Instant Swap Configuration",
                "# Changes take effect immediately — no restart needed.",
                "",
                "# Long/Short Press Mode",
                "#   true  = Short press toggles inventory, long press swaps (default)",
                "#   false = Classic mode (hold to open, release to swap)",
                "longPressMode = " + SwapConfig.longPressModeRuntime,
                "",
                "# Long Press Threshold (ms). Range: 50 ~ 1000",
                "holdThresholdMs = " + SwapConfig.holdThresholdMsRuntime,
                "",
                "# Swap Sound — play item pickup sound on swap",
                "soundEnabled = " + SwapConfig.soundEnabledRuntime,
                "",
                "# Mouse Reposition — auto-move cursor to bottom-right when inventory opens",
                "mouseReposition = " + SwapConfig.mouseRepositionRuntime,
                "",
                "# GUI Swap — swap and close screen when pressing swap key in inventory",
                "guiSwapEnabled = " + SwapConfig.guiSwapEnabledRuntime,
                "",
                "# Debug Logging — print detailed swap info to game log",
                "debug = " + SwapConfig.debugRuntime,
                ""
        );
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 长按阈值滑块
    // ═══════════════════════════════════════════════════════════

    private static class HoldThresholdSlider extends AbstractSliderButton {
        private static final int MIN = 50;
        private static final int MAX = 1000;

        HoldThresholdSlider(int x, int y, int width, int height) {
            super(x, y, width, height, Component.empty(),
                    (double) (SwapConfig.holdThresholdMsRuntime - MIN) / (MAX - MIN));
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.translatable("config.susinstantswap.holdThresholdMs")
                    .append(": " + getCurrentValue() + "ms"));
        }

        @Override
        protected void applyValue() {
            SwapConfig.holdThresholdMsRuntime = getCurrentValue();
        }

        private int getCurrentValue() {
            return (int) (this.value * (MAX - MIN) + MIN);
        }
    }
}
