package com.fouryctrl.mixin;

import com.fouryctrl.client.ButtonHints;
import com.fouryctrl.client.RadialMenu;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 游戏内 HUD 的按键提示注入点。
 *
 * <p>界面里的提示由 {@link com.fouryctrl.mixin.ScreenMixin} 负责（跟着界面走），
 * 但游戏内根本没有 Screen 对象，所以必须另找一个每帧都会跑的落点。</p>
 *
 * <h2>1.21.2 起 render 的第二参从 float 变为 RenderTickCounter</h2>
 * <p>1.20.6 及以前是 {@code render(DrawContext, float)}（tickDelta）；
 * 1.21.1 起改为 {@code render(DrawContext, RenderTickCounter)}，
 * mixin 的注入方法签名必须与宿主方法一致，否则 mixin 应用失败直接崩溃。</p>
 */
@Mixin(InGameHud.class)
public class InGameHudMixin {
    //? if >=1.21.1 {
    @Inject(method = "render", at = @At("RETURN"))
    private void fouryctrl$drawHints(DrawContext context, net.minecraft.client.render.RenderTickCounter tickCounter, CallbackInfo ci) {
        long p = com.fouryctrl.util.Perf.begin();
        ButtonHints.drawHud(context);
        // 轮盘在游戏内也要能看见（BACK 是游戏内最常用的触发键）。
        RadialMenu.draw(context);
        com.fouryctrl.util.Perf.end(com.fouryctrl.util.Perf.HUD, p);
    }
    //?} else {
    /*@Inject(method = "render", at = @At("RETURN"))
    private void fouryctrl$drawHints(DrawContext context, float tickDelta, CallbackInfo ci) {
        long p = com.fouryctrl.util.Perf.begin();
        ButtonHints.drawHud(context);
        // 轮盘在游戏内也要能看见（BACK 是游戏内最常用的触发键）。
        RadialMenu.draw(context);
        com.fouryctrl.util.Perf.end(com.fouryctrl.util.Perf.HUD, p);
    }
    *///?}
}
