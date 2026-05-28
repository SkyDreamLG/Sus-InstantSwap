package com.susinstantswap.config;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * 纯 Minecraft 组件的配置界面，不依赖 Cloth Config。
 * 通过 ModMenu 的 Mods 按钮进入。
 */
public class SimpleConfigScreen extends Screen {

    private final Screen parent;
    private final SwapConfig config;

    private static final int BUTTON_WIDTH = 200;
    private static final int SLIDER_WIDTH = 200;
    private static final int WIDGET_HEIGHT = 20;
    private static final int SPACING = 24;
    private static final int TITLE_Y = 20;
    private static final int DONE_Y_OFFSET = 12;

    public SimpleConfigScreen(Screen parent) {
        super(Component.translatable("config.susinstantswap.title"));
        this.parent = parent;
        this.config = SwapConfig.get();
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int y = 40;

        // ── 长按/短按模式开关 ──
        addRenderableWidget(createToggle(
                centerX, y,
                "config.susinstantswap.longPressMode",
                config.longPressMode,
                v -> config.longPressMode = v));
        y += SPACING;

        // ── 长按阈值滑块 ──
        addRenderableWidget(new HoldThresholdSlider(
                centerX - SLIDER_WIDTH / 2, y, SLIDER_WIDTH, WIDGET_HEIGHT));
        y += SPACING;

        // ── 交换音效开关 ──
        addRenderableWidget(createToggle(
                centerX, y,
                "config.susinstantswap.soundEnabled",
                config.soundEnabled,
                v -> config.soundEnabled = v));
        y += SPACING;

        // ── 鼠标重定位开关 ──
        addRenderableWidget(createToggle(
                centerX, y,
                "config.susinstantswap.mouseReposition",
                config.mouseReposition,
                v -> config.mouseReposition = v));
        y += SPACING;

        // ── 界面中更换开关 ──
        addRenderableWidget(createToggle(
                centerX, y,
                "config.susinstantswap.guiSwapEnabled",
                config.guiSwapEnabled,
                v -> config.guiSwapEnabled = v));
        y += SPACING;

        // ── 空位交换开关 ──
        addRenderableWidget(createToggle(
                centerX, y,
                "config.susinstantswap.emptySlotSwapEnabled",
                config.emptySlotSwapEnabled,
                v -> config.emptySlotSwapEnabled = v));
        y += SPACING;

        // ── 调试日志开关 ──
        addRenderableWidget(createToggle(
                centerX, y,
                "config.susinstantswap.debug",
                config.debug,
                v -> config.debug = v));
        y += SPACING;

        // ── 完成按钮 ──
        y += DONE_Y_OFFSET;
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, btn -> onClose())
                .bounds(centerX - 50, y, 100, WIDGET_HEIGHT)
                .build());
    }

    /**
     * 创建一个切换按钮，显示 ON/OFF 状态。
     */
    private Button createToggle(int centerX, int y, String key, boolean initial, java.util.function.Consumer<Boolean> onToggle) {
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
        // 先画半透明暗色背景（遮蔽下层 ModMenu 界面），再画标题和控件
        this.renderBackground(guiGraphics);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, TITLE_Y, 0xFFFFFF);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        SwapConfig.save();
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
                    (double)(SwapConfig.get().holdThresholdMs - MIN) / (MAX - MIN));
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            int current = getCurrentValue();
            setMessage(Component.translatable("config.susinstantswap.holdThresholdMs")
                    .append(": " + current + "ms"));
        }

        @Override
        protected void applyValue() {
            SwapConfig.get().holdThresholdMs = getCurrentValue();
        }

        private int getCurrentValue() {
            return (int)(this.value * (MAX - MIN) + MIN);
        }
    }
}
