package com.susinstantswap.client.compat;

import com.susinstantswap.client.InstantSwapClient;
import com.susinstantswap.config.SwapConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
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
        this.addRenderableWidget(new HoldThresholdSlider(cx, y, ROW_W, ROW_H, cfg));
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

    /** Slider with value text rendered inside, vanilla-style. */
    private static class HoldThresholdSlider extends AbstractWidget {
        private static final int MIN = 50, MAX = 1000;
        private final SwapConfig cfg;
        private double sliderValue;

        HoldThresholdSlider(int x, int y, int w, int h, SwapConfig cfg) {
            super(x, y, w, h, Component.empty());
            this.cfg = cfg;
            this.sliderValue = clamp((double) (cfg.holdThresholdMs - MIN) / (MAX - MIN));
            updateMessage();
        }

        private void updateMessage() {
            int ms = (int) (MIN + sliderValue * (MAX - MIN));
            setMessage(Component.translatable("config.susinstantswap.holdThresholdMs")
                    .append(Component.literal(": " + ms + "ms")));
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mx, int my, float delta) {
            Minecraft mc = Minecraft.getInstance();
            int x = this.getX(), y = this.getY(), w = this.width, h = this.height;

            // Background: vanilla Button-style box (1px border + fill + bevels)
            g.fill(x, y, x + w, y + h, 0xFF000000);                       // outer border
            g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF3B3B3B);      // main fill
            g.fill(x + 1, y + 1, x + w - 1, y + 2, 0xFFFFFFFF);          // top highlight
            g.fill(x + 1, y + 1, x + 2, y + h - 1, 0xFFAAAAAA);          // left highlight
            g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, 0xFF262626);  // bottom shadow
            g.fill(x + w - 2, y + 1, x + w - 1, y + h - 1, 0xFF262626);  // right shadow

            // Track line
            int tx = x + 6, ty = y + h / 2, tw = w - 12;
            g.fill(tx, ty, tx + tw, ty + 1, 0xFF000000);
            g.fill(tx + 1, ty, tx + tw - 1, ty + 1, 0xFF555555);

            // Handle (8x14 pixels, 3D bevel)
            int hx = tx + (int) (sliderValue * (tw - 8));
            g.fill(hx, y + 2, hx + 8, y + h - 2, 0xFF888888);
            g.fill(hx + 1, y + 2, hx + 7, y + 3, 0xFFDDDDDD);
            g.fill(hx + 1, y + 2, hx + 2, y + h - 2, 0xFFDDDDDD);
            g.fill(hx + 1, y + h - 4, hx + 7, y + h - 3, 0xFF555555);
            g.fill(hx + 6, y + 2, hx + 7, y + h - 2, 0xFF555555);

            // Text centered over background
            int textColor = this.isHoveredOrFocused() ? 0xFFFFA0 : 0xE0E0E0;
            g.drawCenteredString(mc.font, this.getMessage(), x + w / 2, y + (h - 8) / 2, textColor);
        }

        @Override public void onClick(double mx, double my) { setValueFromMouse(mx); }
        @Override protected void onDrag(double mx, double my, double dx, double dy) { setValueFromMouse(mx); }

        private void setValueFromMouse(double mx) {
            this.sliderValue = clamp((mx - (this.getX() + 8)) / (this.width - 16));
            cfg.holdThresholdMs = (int) (MIN + sliderValue * (MAX - MIN));
            cfg.save();
            updateMessage();
        }

        private static double clamp(double v) { return v < 0 ? 0 : v > 1 ? 1 : v; }
        @Override protected boolean isValidClickButton(int b) { return b == 0; }
        @Override public void updateWidgetNarration(net.minecraft.client.gui.narration.NarrationElementOutput e) { this.defaultButtonNarrationText(e); }
    }
}
