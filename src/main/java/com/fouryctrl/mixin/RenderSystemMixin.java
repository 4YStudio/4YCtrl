package com.fouryctrl.mixin;

import com.fouryctrl.util.FrameGuard;
import com.fouryctrl.util.Perf;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 修「进游戏后帧率被锁死 / CPU 被烧满」：把 MC 的忙等限帧换成真正的睡眠，
 * 同时挡住「别的模组把帧上限压到异常低」导致的锁帧。
 *
 * <h2>为什么要改这个方法</h2>
 * <p>MC 的 {@code RenderSystem.limitDisplayFPS(fps)} 实现是：</p>
 * <pre>
 *   double deadline = lastDrawTime + 1.0 / fps;
 *   double now = GLFW.glfwGetTime();
 *   while (now &lt; deadline) {
 *       GLFW.glfwWaitEventsTimeout(deadline - now);   // 本该睡一会儿
 *       now = GLFW.glfwGetTime();
 *   }
 *   lastDrawTime = now;
 * </pre>
 * <p>但 FCL 自带的 LWJGL stub 把 {@code glfwWaitEventsTimeout} 的方法体整段注释掉了
 * （3.3.3 在 {@code GLFW.java:1317}，3.4.1 在 {@code GLFW.java:1365}，
 * 注释原文 "Boardwalk: this isn't how you do a frame limiter, but oh well"），它什么都不做。
 * 于是上面变成纯忙等自旋：烧满一个核，主线程被占住，实测帧率掉到 10 左右。</p>
 *
 * <p>修法：取消原方法，用 {@code Thread.sleep} 睡到 deadline（最后 ~0.8ms 用
 * {@code Thread.onSpinWait()} 补齐，避免 sleep 睡过头压低帧率）。
 * 时间基准用本类自己的 {@code fouryctrl$lastDrawTime}，不用 {@code @Shadow} ——
 * {@code RenderSystem} 的私有字段在 yarn 映射里没有条目，shadow 不可靠。</p>
 *
 * <h2>外部限帧守卫</h2>
 * <p>本 mixin 让「睡眠」真的生效后，省电模组（典型 FPS Reducer）的
 * {@code window.setFramerateLimit(10)} 才第一次真的把游戏压到 10 帧。
 * 这类请求不是玩家意图，跳过睡眠即可（等于 FCL 原本的空实现、也就是不装本模组的行为）。
 * 判定逻辑放在 {@link FrameGuard}（三方比对 options / fps / window，见该类的 javadoc）。</p>
 *
 * <h2>Perf 段</h2>
 * <p>每次进入记一段 {@code limit} 耗时。它包含睡着的时间，所以「大」本身正常；
 * 关键是和 {@code frame} 对比：{@code limit} ≈ {@code frame} 说明帧时间都花在限帧器里，
 * {@code limit} 很小而 {@code frame} 很大说明卡在渲染/输入侧。</p>
 */
@Mixin(RenderSystem.class)
public class RenderSystemMixin {
    /** 上一帧的时间点（秒），等价于 RenderSystem.lastDrawTime。 */
    private static double fouryctrl$lastDrawTime;

    @Inject(method = "limitDisplayFPS", at = @At("HEAD"), cancellable = true)
    private static void fouryctrl$sleepInsteadOfSpin(int fps, CallbackInfo ci) {
        long perfStart = Perf.begin();

        // 不限帧（或参数病态）：放过，别反弹回原实现去忙等。
        // 260 必须与 MinecraftClient.render 的阈值一致（limit >= 260 它根本不调本方法），
        // 否则 250..259 会掉进下面的 sleep 分支、把帧率压回 250。
        if (fps <= 0 || fps >= FrameGuard.UNLIMITED) {
            ci.cancel();
            Perf.end(Perf.LIMIT, perfStart);
            return;
        }

        // 外部模组塞进来的省电值：忽略，保持满帧。
        if (FrameGuard.isExternalThrottle(fps)) {
            FrameGuard.noteGuarded(fps);
            ci.cancel();
            Perf.end(Perf.LIMIT, perfStart);
            return;
        }
        FrameGuard.clearCurrent();

        double deadline = fouryctrl$lastDrawTime + 1.0 / fps;
        double now = GLFW.glfwGetTime();
        // 卡顿或切后台回来时 deadline 可能远在过去，此时不要补帧式地连睡。
        if (deadline < now - 0.5) {
            fouryctrl$lastDrawTime = now;
            ci.cancel();
            Perf.end(Perf.LIMIT, perfStart);
            return;
        }
        if (now < deadline) {
            long sleepNanos = (long) ((deadline - now) * 1.0e9);
            // Thread.sleep 精度只有 ~1ms 甚至更差，睡满会睡过头。
            // 先睡到「deadline 前 0.8ms」，剩下的零头用 onSpinWait 微转补齐。
            final long slack = 800_000L;
            if (sleepNanos > slack) {
                long s = sleepNanos - slack;
                try {
                    Thread.sleep(s / 1_000_000L, (int) (s % 1_000_000L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            while (GLFW.glfwGetTime() < deadline) {
                Thread.onSpinWait();
            }
            now = GLFW.glfwGetTime();
            if (now < deadline) {
                now = deadline;
            }
        }
        fouryctrl$lastDrawTime = now;
        ci.cancel();
        Perf.end(Perf.LIMIT, perfStart);
    }
}
