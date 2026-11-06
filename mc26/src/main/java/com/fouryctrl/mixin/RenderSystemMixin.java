package com.fouryctrl.mixin;

import com.fouryctrl.util.FrameGuard;
import com.fouryctrl.util.Perf;
import net.minecraft.client.FramerateLimiter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 帧上限守卫（26.3 版）。
 *
 * <h2>26.3 变了什么</h2>
 * <p>1.20.1 的限帧入口是 {@code RenderSystem.limitDisplayFPS(int)}，实现是
 * 「{@code glfwWaitEventsTimeout} 自旋」—— 而 FCL 的 LWJGL stub 把那个方法体
 * 整段注释掉了，所以 1.20.1 上我们必须自己 {@code Thread.sleep}。</p>
 *
 * <p>26.3 已经不用 GLFW：限帧搬到 {@link FramerateLimiter#limitDisplayFPS(int)}，
 * 实现是 {@code System.nanoTime} + {@code LockSupport.parkNanos(剩余 - 平均超时 - 0.5ms)}
 * + {@code Thread.onSpinWait()} 兜底，而且 {@code averageOvershootNs} 会自适应学习
 * 本机的 park 开销。也就是说 <b>26.3 原生已经睡得很准</b>，
 * 不再需要（也<b>不应该</b>）我们替换它的睡眠实现。</p>
 *
 * <h2>所以这里只保留「守卫」职责</h2>
 * <p>省电模组（FPS Reducer 之类）依然可能把帧上限压到 10：它们改的是
 * 限帧<em>数值</em>而不是睡眠方式。判定逻辑留在 {@link FrameGuard}，
 * 命中时 {@code ci.cancel()} 跳过这次限帧；其余情况原样放行，交给原生的
 * {@code parkNanos} 实现（比我们的 sleep+自旋更准）。</p>
 *
 * <h2>Perf 段</h2>
 * <p>每次进入记一段 {@code limit} 耗时（含睡着的时间）。关键是和 {@code frame} 对比：
 * {@code limit} ≈ {@code frame} 说明帧时间都花在限帧器里，{@code limit} 很小而
 * {@code frame} 很大说明卡在渲染/输入侧。</p>
 */
@Mixin(FramerateLimiter.class)
public class RenderSystemMixin {
    /** 本次限帧的起点（纳秒）；只被渲染线程读写，不需要同步。 */
    private static long fouryctrl$limitStart;

    @Inject(method = "limitDisplayFPS", at = @At("HEAD"), cancellable = true)
    private static void fouryctrl$guardLimit(int fps, CallbackInfo ci) {
        fouryctrl$limitStart = 0L;

        // 不限帧（或参数病态）：放过。26.3 的 Minecraft 也是
        // limit >= UNLIMITED_FRAMERATE_CUTOFF(260) 就根本不调本方法。
        if (fps <= 0 || fps >= FrameGuard.UNLIMITED) {
            ci.cancel();
            return;
        }

        // 外部模组塞进来的省电值：忽略，保持满帧。
        if (FrameGuard.isExternalThrottle(fps)) {
            FrameGuard.noteGuarded(fps);
            ci.cancel();
            return;
        }
        FrameGuard.clearCurrent();

        // 到这里才会真的睡：从这里开始计时，RETURN 里收尾。
        fouryctrl$limitStart = Perf.begin();
        // 其余交给 26.3 原生实现（parkNanos + 自适应超时补偿）。
    }

    /** 方法真正返回（睡完了）才记一段 limit 耗时。 */
    @Inject(method = "limitDisplayFPS", at = @At("RETURN"))
    private static void fouryctrl$endLimitPerf(int fps, CallbackInfo ci) {
        if (fouryctrl$limitStart != 0L) {
            Perf.end(Perf.LIMIT, fouryctrl$limitStart);
            fouryctrl$limitStart = 0L;
        }
    }
}
