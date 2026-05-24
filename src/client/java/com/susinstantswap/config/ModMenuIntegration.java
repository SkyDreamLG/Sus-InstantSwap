package com.susinstantswap.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * ModMenu 集成入口。
 * 安全处理 Cloth Config 不存在的情况（不直接导入 Cloth Config 类）。
 * 当用户安装了 Mod Menu 和 Cloth Config API 后，模组列表出现配置按钮。
 */
public class ModMenuIntegration implements ModMenuApi {

    private static final boolean CLOTH_AVAILABLE;

    static {
        boolean available = false;
        try {
            Class.forName("me.shedaniel.clothconfig2.api.ConfigBuilder");
            available = true;
        } catch (ClassNotFoundException ignored) {
        }
        CLOTH_AVAILABLE = available;
    }

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        if (!CLOTH_AVAILABLE) return null;
        return new ClothConfigFactory();
    }
}
