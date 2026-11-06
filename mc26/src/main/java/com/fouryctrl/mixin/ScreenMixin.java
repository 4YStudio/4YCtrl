package com.fouryctrl.mixin;

import com.fouryctrl.FourYCtrl;
import com.fouryctrl.client.ButtonHints;
import com.fouryctrl.client.RadialMenu;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在所有界面之上画一个软件光标。
 *
 * <p>为什么注入 {@code extractRenderStateWithTooltipAndSubtitles} 而不是
 * {@code extractRenderState}：前者是 final 的，由 {@code Gui} 对每个界面调用，
 * 内部先 {@code extractRenderState} 再画 tooltip。注入它的 TAIL 能保证光标永远画在
 * 子类内容与 tooltip 之上 —— 而注入后者的话，任何不调 {@code super} 的子类都会把光标盖掉。</p>
 *
 * <p>第二个坑是 z 序：配方书内部会把矩阵再推一层画整块 147x166 的书，
 * 而我们的光标是以 z=0 的顶点坐标画的，于是光标会被书盖住。26.3 的
 * {@code pose()} 是 {@code Matrix3x2fStack}（2D 矩阵栈，只有
 * {@code pushMatrix()/popMatrix()} 与两参 {@code translate}），已经没有 z 偏移，
 * 但保留 push/pop 仍然必要：它把我们的绘制和界面的变换隔离。</p>
 *
 * <p>另外每一块都记一段 {@code Perf}（{@code screen} 是整体、{@code cursor} /
 * {@code hints} / {@code vkbd} / {@code rmmenu} 是各自耗时）。这些数字只在
 * <b>有界面时</b>才有值，正好覆盖「暂停菜单掉到 10 帧」那类只在 GUI 里出现的卡顿：
 * 若 {@code screen} 很小而 {@code frame} 很大，问题就不在我们这里。</p>
 */
@Mixin(Screen.class)
public class ScreenMixin {
    @Inject(method = "extractRenderStateWithTooltipAndSubtitles", at = @At("TAIL"))
    private void fouryctrl$drawCursor(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        long perfAll = com.fouryctrl.util.Perf.begin();
        context.pose().pushMatrix();
        context.pose().translate(0.0f, 0.0f);
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
            context.pose().popMatrix();
            com.fouryctrl.util.Perf.end(com.fouryctrl.util.Perf.SCREEN, perfAll);
        }
    }
}
