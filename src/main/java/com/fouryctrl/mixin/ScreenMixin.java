package com.fouryctrl.mixin;

import com.fouryctrl.FourYCtrl;
import com.fouryctrl.client.ButtonHints;
import com.fouryctrl.client.RadialMenu;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在所有界面之上画一个软件光标。
 *
 * <p>为什么注入 {@code renderWithTooltip} 而不是 {@code render}：
 * {@code renderWithTooltip} 是 final 的，由 {@code GameRenderer.render} 对每个界面调用，
 * 内部先 {@code render} 再画 tooltip。注入它的 TAIL 能保证光标永远画在
 * 子类内容与 tooltip 之上 —— 而注入 {@code render} 的话，任何不调
 * {@code super.render} 的子类都会把光标盖掉。</p>
 *
 * <p>第二个坑是 z 序：{@code RecipeBookWidget.render} 内部会把矩阵
 * {@code translate(0, 0, 100)} 再画整块 147x166 的配方书，而我们的光标是以
 * z=0 的顶点坐标画的，于是光标会被书盖住。这里把整段绘制抬到 z=300。</p>
 *
 * <p>另外每一块都记一段 {@code Perf}（{@code screen} 是整体、{@code cursor} /
 * {@code hints} / {@code rmmenu} 是各自耗时）。这些数字只在
 * <b>有界面时</b>才有值，正好覆盖「暂停菜单掉到 10 帧」那类只在 GUI 里出现的卡顿：
 * 若 {@code screen} 很小而 {@code frame} 很大，问题就不在我们这里。</p>
 */
@Mixin(Screen.class)
public class ScreenMixin {
    @Inject(method = "renderWithTooltip", at = @At("TAIL"))
    private void fouryctrl$drawCursor(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        long perfAll = com.fouryctrl.util.Perf.begin();
        com.fouryctrl.util.Compat.push(context);
        com.fouryctrl.util.Compat.translateZ(context, 300.0f);
        try {
            long t = com.fouryctrl.util.Perf.begin();
            FourYCtrl.drawGuiCursor(context);
            com.fouryctrl.util.Perf.end(com.fouryctrl.util.Perf.CURSOR, t);

            t = com.fouryctrl.util.Perf.begin();
            ButtonHints.draw((Screen) (Object) this, context);
            com.fouryctrl.util.Perf.end(com.fouryctrl.util.Perf.HINTS, t);

            t = com.fouryctrl.util.Perf.begin();
            RadialMenu.draw(context);
            com.fouryctrl.util.Perf.end(com.fouryctrl.util.Perf.RMMENU, t);
        } finally {
            com.fouryctrl.util.Compat.pop(context);
            com.fouryctrl.util.Perf.end(com.fouryctrl.util.Perf.SCREEN, perfAll);
        }
    }
}
