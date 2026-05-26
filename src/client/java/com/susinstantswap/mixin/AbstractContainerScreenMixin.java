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
 * MC 1.20.1 中 AbstractContainerScreen.render() 在方法末尾直接调用
 * this.renderTooltip(GuiGraphics, int, int) 渲染悬停槽位的物品 tooltip。
 * 在入口处拦截即可取消渲染，效果等价于 Forge 的 RenderTooltipEvent.Pre。
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
