package com.fouryctrl.mixin;

import com.fouryctrl.client.ButtonHints;
import com.fouryctrl.client.RadialMenu;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 游戏内 HUD 的按键提示注入点。
 *
 * <p>界面里的提示由 {@code ScreenMixin} 负责（跟着界面走），但游戏内根本没有 Screen 对象，
 * 所以必须另找一个每帧都会跑的落点 —— HUD 全部画完之后返回的那一刻，
 * 从这里画提示就能压在最上层。</p>
 *
 * <h2>26.1 与 26.2+ 的宿主类不同</h2>
 * <p>26.1 还没有独立的 {@code net.minecraft.client.gui.Hud} 类，HUD 由
 * {@code Gui.extractRenderState(GuiGraphicsExtractor, DeltaTracker)} 一口气画完；
 * 26.2 起才拆出 {@code Hud}，由 {@code Hud.extractRenderState(...)} 负责。
 * 两者的<b>方法签名完全相同</b>（只差宿主类名），所以方法体共用一个。</p>
 */
//? if <26.2 {
/*@Mixin(net.minecraft.client.gui.Gui.class)
*///?} else {
@Mixin(net.minecraft.client.gui.Hud.class)
//?}
public class InGameHudMixin {
    @Inject(method = "extractRenderState", at = @At("RETURN"))
    private void fouryctrl$drawHints(GuiGraphicsExtractor context, DeltaTracker deltaTracker, CallbackInfo ci) {
        long p = com.fouryctrl.util.Perf.begin();
        ButtonHints.drawHud(context);
        // 轮盘在游戏内也要能看见（BACK 是游戏内最常用的触发键）。
        RadialMenu.draw(context);
        com.fouryctrl.util.Perf.end(com.fouryctrl.util.Perf.HUD, p);
    }
}
