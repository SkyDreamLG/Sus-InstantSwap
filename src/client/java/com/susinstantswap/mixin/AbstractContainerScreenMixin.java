package com.susinstantswap.mixin;

import com.susinstantswap.client.InstantSwapClient;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在鼠标重定位时抑制首帧 tooltip 渲染，消除光标瞬移导致的闪烁。
 *
 * 替代 Forge/NeoForge 的 RenderTooltipEvent.Pre，Fabric 无此事件，
 * 通过 Mixin 拦截 AbstractContainerScreen.renderTooltip() 实现同等效果。
 *
 * MC 1.21.1: renderTooltip(GuiGraphics, int, int) 是 AbstractContainerScreen 的
 * protected 方法，由 InventoryScreen/CreativeModeInventoryScreen.render() 末尾调用。
 * 注意：1.21.1 中 Screen 没有 renderTooltip(GuiGraphics, int, int)，工具提示通过
 * renderWithTooltip() + setTooltipForNextRenderPass() 延迟渲染管道处理。
 */
@Mixin(AbstractContainerScreen.class)
public class AbstractContainerScreenMixin {

    @Inject(
            method = "renderTooltip(Lnet/minecraft/client/gui/GuiGraphics;II)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void onRenderTooltip(GuiGraphics graphics, int mouseX, int mouseY, CallbackInfo ci) {
        if (InstantSwapClient.isSuppressNextTooltip()) {
            ci.cancel();
        }
        InstantSwapClient.consumeTooltipSuppress();
    }
}
