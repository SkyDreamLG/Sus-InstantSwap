package com.susinstantswap.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.susinstantswap.client.InstantSwapClient;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 在鼠标重定位时抑制首帧 tooltip 渲染，消除光标瞬移导致的闪烁。
 *
 * 替代 Forge/NeoForge 的 RenderTooltipEvent.Pre，Fabric 无此事件，
 * 通过 Mixin 拦截 AbstractContainerScreen.renderTooltip() 实现同等效果。
 */
@Mixin(AbstractContainerScreen.class)
public class AbstractContainerScreenMixin {

    @WrapOperation(
            method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screens/Screen;renderTooltip(Lnet/minecraft/client/gui/GuiGraphics;II)V"
            )
    )
    private void wrapRenderTooltip(AbstractContainerScreen<?> instance, GuiGraphics graphics,
                                    int mouseX, int mouseY, Operation<Void> original) {
        if (!InstantSwapClient.isSuppressNextTooltip()) {
            original.call(instance, graphics, mouseX, mouseY);
        }
        InstantSwapClient.consumeTooltipSuppress();
    }
}
