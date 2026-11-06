package com.fouryctrl.mixin;

import com.fouryctrl.FourYCtrl;
import com.fouryctrl.client.SettingsScreen;
import com.fouryctrl.config.ModConfig;
import com.fouryctrl.input.GamepadManager;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在暂停菜单右上角加一个 4YCtrl 开关。
 *
 * <p>为什么需要它：安卓上没有键盘，模组原本的 F8 快捷键在手机上根本按不到，
 * 而「改 config/fouryctrl.json 再重启」对玩家来说太麻烦。暂停菜单是触屏一定能点到的地方，
 * 所以把主开关放在这里。</p>
 *
 * <p>为什么注入 {@code init()} 的 TAIL 而不是 {@code initWidgets()}：原版用 GridWidget
 * 自动排版，硬塞进去会把「返回游戏 / 进度 / 统计」整体挤歪；
 * 直接 {@code addDrawableChild} 一个绝对定位的按钮，既不干扰 Grid 布局。</p>
 *
 * <p>按钮分两行放在右上角：上面是总开关，下面是「设置」（死区 / 视角速度）。
 * 右上角是 Grid 布局之外唯一的空地——Grid 从顶部 25% 处开始垂直居中。</p>
 */
@Mixin(PauseScreen.class)
public abstract class GameMenuScreenMixin implements ScreenAccessor {
    @Inject(method = "init", at = @At("TAIL"))
    private void fouryctrl$addToggleButton(CallbackInfo ci) {
        final GamepadManager gamepad = FourYCtrl.gamepad();
        final ModConfig cfg = FourYCtrl.config();
        if (gamepad == null || cfg == null) {
            return;
        }

        Screen self = (Screen) (Object) this;
        int w = 110;
        int x = Math.max(4, self.width - w - 6);
        int y = 6;

        Button toggle = Button.builder(
                        label(cfg.enabled),
                        b -> {
                            gamepad.setEnabled(this.fouryctrl$getClient(), !cfg.enabled);
                            b.setMessage(label(cfg.enabled));
                        })
                .pos(x, y)
                .width(w)
                .build();
        this.fouryctrl$addDrawableChild(toggle);

        Button settings = Button.builder(
                        Component.translatable("fouryctrl.menu.settings"),
                        b -> this.fouryctrl$getClient().setScreenAndShow(new SettingsScreen(self)))
                .pos(x, y + 22)
                .width(w)
                .build();
        this.fouryctrl$addDrawableChild(settings);
    }

    private static Component label(boolean enabled) {
        return Component.translatable("fouryctrl.menu.toggle", Component.translatable(
                enabled ? "fouryctrl.state.on" : "fouryctrl.state.off"));
    }
}
