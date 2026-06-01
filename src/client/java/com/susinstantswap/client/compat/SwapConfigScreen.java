package com.susinstantswap.client.compat;

import com.susinstantswap.client.InstantSwapClient;
import com.susinstantswap.config.SwapConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.ChatFormatting;

/**
 * All-button config screen — each option is a full-width button.
 *
 * <p>Adapted for MC 1.20.1: Button subclass for vanilla rendering,
 * single-arg {@code renderBackground(GuiGraphics)}.</p>
 */
public class SwapConfigScreen extends Screen {
    private static final int ROW_W = 310;
    private static final int ROW_H = 20;
    private static final int GAP = 2;
    private static final int TITLE_Y = 16;
    private static final int TOP = 34;
    private static final int DONE_W = 200;
    private static final int DONE_H = 20;

    private final Screen parent;
    private final SwapConfig cfg;

    public SwapConfigScreen(Screen parent) {
        super(Component.translatable("config.susinstantswap.title"));
        this.parent = parent;
        this.cfg = InstantSwapClient.getConfig();
    }

    @Override
    protected void init() {
        int cx = (this.width - ROW_W) / 2;
        int y = TOP;

        addBoolean(cx, y, "config.susinstantswap.modEnabled",
                cfg.modEnabled, v -> { cfg.modEnabled = v; save(); });
        y += ROW_H + GAP;
        addThreshold(cx, y);
        y += ROW_H + GAP;
        addBoolean(cx, y, "config.susinstantswap.soundEnabled",
                cfg.soundEnabled, v -> { cfg.soundEnabled = v; save(); });
        y += ROW_H + GAP;
        addBoolean(cx, y, "config.susinstantswap.mouseReposition",
                cfg.mouseReposition, v -> { cfg.mouseReposition = v; save(); });
        y += ROW_H + GAP;
        addBoolean(cx, y, "config.susinstantswap.guiSwapEnabled",
                cfg.guiSwapEnabled, v -> { cfg.guiSwapEnabled = v; save(); });
        y += ROW_H + GAP;
        addBoolean(cx, y, "config.susinstantswap.emptySlotSwapEnabled",
                cfg.emptySlotSwapEnabled, v -> { cfg.emptySlotSwapEnabled = v; save(); });
        y += ROW_H + GAP;
        addBoolean(cx, y, "config.susinstantswap.debug",
                cfg.debug, v -> { cfg.debug = v; save(); });

        // Done
        int doneY = this.height - DONE_H - 8;
        this.addRenderableWidget(new VanillaButton(
                cx + (ROW_W - DONE_W) / 2, doneY, DONE_W, DONE_H,
                CommonComponents.GUI_DONE,
                btn -> Minecraft.getInstance().setScreen(parent)));
    }

    private void addBoolean(int cx, int y, String key, boolean initial,
                            java.util.function.Consumer<Boolean> setter) {
        final boolean[] st = { initial };
        VanillaButton btn = new VanillaButton(cx, y, ROW_W, ROW_H,
                buttonText(key, st[0]),
                b -> {
                    st[0] = !st[0];
                    setter.accept(st[0]);
                    b.setMessage(buttonText(key, st[0]));
                });
        this.addRenderableWidget(btn);
    }

    private void addThreshold(int cx, int y) {
        this.addRenderableWidget(new ThresholdButton(cx, y, ROW_W, ROW_H, cfg));
    }

    private static MutableComponent buttonText(String key, boolean on) {
        return Component.translatable(key)
                .append(Component.literal(": "))
                .append(on ? ON : OFF);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        this.renderBackground(g);
        super.render(g, mouseX, mouseY, delta);
        g.drawCenteredString(this.font, this.title, this.width / 2, TITLE_Y, 0xFFFFFF);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    private void save() { cfg.save(); }

    private static final Component ON  = Component.translatable("options.on")
            .withStyle(ChatFormatting.GREEN);
    private static final Component OFF = Component.translatable("options.off")
            .withStyle(ChatFormatting.RED);

    // ── Widgets ──

    private static class VanillaButton extends Button {
        VanillaButton(int x, int y, int w, int h, Component msg, OnPress onPress) {
            super(x, y, w, h, msg, onPress, Button.DEFAULT_NARRATION);
        }
    }

    private static class ThresholdButton extends VanillaButton {
        private static final int MIN = 50, MAX = 1000, STEP = 50;
        private final SwapConfig cfg;

        ThresholdButton(int x, int y, int w, int h, SwapConfig cfg) {
            super(x, y, w, h, Component.empty(), b -> {});
            this.cfg = cfg;
            updateMessage();
        }

        private void updateMessage() {
            setMessage(Component.translatable("config.susinstantswap.holdThresholdMs")
                    .append(Component.literal(": " + cfg.holdThresholdMs + "ms")));
        }

        @Override
        public boolean mouseClicked(double mx, double my, int button) {
            if (this.active && this.visible && this.clicked(mx, my)) {
                if (button == 0) {
                    cfg.holdThresholdMs = Math.min(MAX, cfg.holdThresholdMs + STEP);
                } else if (button == 1) {
                    cfg.holdThresholdMs = Math.max(MIN, cfg.holdThresholdMs - STEP);
                }
                cfg.save();
                updateMessage();
                this.playDownSound(Minecraft.getInstance().getSoundManager());
                return true;
            }
            return false;
        }
    }
}
