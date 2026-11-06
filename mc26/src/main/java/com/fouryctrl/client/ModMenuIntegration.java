package com.fouryctrl.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * ModMenu 集成：装了 ModMenu 时，「模组菜单 → 4YCtrl → 设置」直接打开我们的设置界面。
 *
 * <p>依赖是 {@code modCompileOnly}（可选依赖）：没装 ModMenu 时这个类不会被加载，
 * Fabric 只在 ModMenu 存在时才实例化 modmenu entrypoint，因此不会抛 ClassNotFound。</p>
 */
public class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        // ConfigScreenFactory.create(Screen) 的签名与 SettingsScreen(Screen parent) 正好对上
        return SettingsScreen::new;
    }
}
