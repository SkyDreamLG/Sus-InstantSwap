package com.susinstantswap.mixin;

import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 通过 @Invoker 暴露 Screen.clearTooltipForNextRenderPass() 给其他 Mixin 调用。
 * 使用 Invoker 而非 @Shadow，因为 Invoker 直接生成桥接方法调用，
 * Mixin AP 会正确处理 Intermediary 映射。
 */
@Mixin(Screen.class)
public interface ScreenAccessor {

    @Invoker("clearTooltipForNextRenderPass")
    void invokeClearTooltipForNextRenderPass();
}
