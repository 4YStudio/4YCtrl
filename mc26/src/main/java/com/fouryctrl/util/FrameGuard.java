package com.fouryctrl.util;

import com.mojang.blaze3d.platform.FramerateLimitTracker;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.Minecraft;

/**
 * 帧上限守卫（26.3 版）：判断「这次 {@code FramerateLimiter.limitDisplayFPS(fps)} 的请求，
 * 是玩家自己的设定 / 原版有意为之的降帧，还是别的模组塞进来的省电值」。
 *
 * <h2>26.3 的链路（与 1.20.1 完全不同）</h2>
 * <p>1.20.1：{@code Window.framerateLimit} + {@code RenderSystem.limitDisplayFPS}，
 * 判断「窗口上限 != 玩家上限」即可发现外部改动。</p>
 *
 * <p>26.3：{@code Window} 已经没有 framerateLimit，改由
 * {@link FramerateLimitTracker#getFramerateLimit()} 现算，{@code Minecraft.renderFrame}
 * 把结果写进 {@code GameRenderState.framerateLimit}，末尾
 * 「{@code limit < 260} 才调 {@link net.minecraft.client.FramerateLimiter#limitDisplayFPS(int)}」。</p>
 *
 * <p>tracker 的取值按 {@link FramerateLimitTracker#getThrottleReason()} 分派：</p>
 * <ul>
 *   <li>{@code NONE} → 自己的 {@code framerateLimit} 字段（正常等于玩家设定）；</li>
 *   <li>{@code WINDOW_ICONIFIED} / {@code SHORT_AFK} → 10；{@code LONG_AFK} → 60；
 *       {@code OUT_OF_LEVEL_MENU} → min(limit, 30)。这些是<b>原版有意为之</b>，不能拦。</li>
 * </ul>
 *
 * <h2>所以判定改成「原因 + 数值」两段</h2>
 * <ol>
 *   <li>{@code reason != NONE} → 原版省电（最小化 / AFK / 无世界），<b>一律不拦</b>；</li>
 *   <li>{@code reason == NONE}：此时唯一合法的值就是玩家设定 {@code options.framerateLimit()}；
 *       出现比它明显更低的值 ⇒ 有外部模组（典型 FPS Reducer）调了
 *       {@code tracker.setFramerateLimit(...)}。</li>
 * </ol>
 *
 * <p>注意 26.3 里 FPS Reducer 那类模组<b>不再</b>因为 FCL 的 GLFW stub 而误判后台
 * （26.3 走 SDL 后端，且 {@code isFocused()} 的坑在 1.20.1 才致命），但仍可能在
 * 真正的后台/闲置时降帧 —— 那种情况 reason 会变成 AFK/ICONIFIED，本守卫放行。</p>
 */
public final class FrameGuard {
    /** MC 里「无限制」档的哨兵值：{@code Minecraft.renderFrame} 只在 {@code limit < 260} 时调限帧。 */
    public static final int UNLIMITED = 260;

    /** 容忍比例：{@code fps < optionsMax * NUM / DEN} 才算「明显低于玩家设定」。 */
    private static final int GUARD_NUM = 3;
    private static final int GUARD_DEN = 4;

    /** 已告警次数（上限 3）与上次告警的 fps（同值不重复打）。 */
    private static int warned;
    private static int lastWarnedFps = -1;
    /** 最近一次被放行的外部限帧值（0 = 当前没在拦）。 */
    private static int lastGuardedValue;
    /** 累计放行次数，便于诊断「是不是一直在拦」。 */
    private static long guardedHits;

    private FrameGuard() {
    }

    private static FramerateLimitTracker tracker() {
        try {
            Minecraft mc = Minecraft.getInstance();
            return mc == null ? null : mc.getFramerateLimitTracker();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 降帧原因（原版省电档位）；取不到返回 {@code null}。 */
    private static FramerateLimitTracker.FramerateThrottleReason reason() {
        try {
            FramerateLimitTracker t = tracker();
            return t == null ? null : t.getThrottleReason();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 玩家在视频设置里选的最大帧率；&gt;=260 表示「无限制」。取不到返回 0（= 无法判定，不拦）。 */
    public static int optionsMaxFps() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.options == null || mc.options.framerateLimit() == null) {
                return 0;
            }
            Integer v = mc.options.framerateLimit().get();
            return v == null ? 0 : v;
        } catch (Throwable t) {
            // 极早期（options 还没建好）或映射异常：退化成老行为（照常限帧）。
            return 0;
        }
    }

    /**
     * 当前<b>实际生效</b>的帧上限（26.3 取代了旧的 {@code Window.framerateLimit}）。
     * 取不到返回 -1。
     */
    public static int windowMaxFps() {
        try {
            FramerateLimitTracker t = tracker();
            return t == null ? -1 : t.getFramerateLimit();
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 这次 {@code fps} 请求是不是「外部模组压进来的省电帧率」。
     * 判定为真时调用方应跳过这次限帧（等价于 FCL 原本的 {@code glfwWaitEventsTimeout} 空实现）。
     */
    public static boolean isExternalThrottle(int fps) {
        // 玩家可以在设置里关掉本守卫（想真的让省电模组生效时）。
        try {
            com.fouryctrl.config.ModConfig cfg = com.fouryctrl.FourYCtrl.config();
            if (cfg != null && !cfg.ignoreExternalFpsLimit) {
                return false;
            }
        } catch (Throwable ignored) {
            // 读不到配置时按默认（开）走，不让守卫失效。
        }

        // 1. 原版自己的省电档位（最小化 / AFK / 无世界）：一律放行，绝不加严。
        FramerateLimitTracker.FramerateThrottleReason why = reason();
        if (why != null && why != FramerateLimitTracker.FramerateThrottleReason.NONE) {
            return false;
        }

        // 2. 读不到玩家设定就无法分辨意图，保守放行。
        int optionsMax = optionsMaxFps();
        if (optionsMax <= 0) {
            return false;
        }

        // 3a. 玩家选「无限制」：NONE 档下任何限帧都不是他要的。
        if (optionsMax >= UNLIMITED) {
            return fps < UNLIMITED;
        }
        // 3b. 明显低于玩家设定（排除 60→50 这类正常降档）。
        return fps < optionsMax * GUARD_NUM / GUARD_DEN;
    }

    /**
     * 记录一次「已放行外部限帧」，并在前几次打一条 warn 指路。
     *
     * @param fps 被忽略的限帧值
     */
    public static void noteGuarded(int fps) {
        lastGuardedValue = fps;
        guardedHits++;
        if (warned < 3 && fps != lastWarnedFps) {
            warned++;
            lastWarnedFps = fps;
            try {
                com.fouryctrl.FourYCtrl.LOG.warn(
                        "4YCtrl: 检测到外部模组把帧上限压到 {} fps（玩家设定 {}），"
                                + "已忽略这次限帧，避免游戏卡成幻灯片。"
                                + "常见原因：FPS Reducer 这类省电模组主动降帧。"
                                + "建议禁用该模组，或把它的 idleFps 调高 / 关掉「后台降帧」。",
                        fps, optionsMaxFps());
            } catch (Throwable ignored) {
                // 日志系统还没起来也不影响功能。
            }
        }
    }

    /** 正常限帧时清掉「当前被拦」标记（保留历史计数供诊断）。 */
    public static void clearCurrent() {
        lastGuardedValue = 0;
    }

    /** 给 {@code /4y diag} 用的一句话状态。 */
    public static String describe() {
        int optionsMax = optionsMaxFps();
        int effective = windowMaxFps();
        FramerateLimitTracker.FramerateThrottleReason why = reason();
        StringBuilder sb = new StringBuilder();
        sb.append("玩家上限=").append(optionsMax <= 0 ? "?" : String.valueOf(optionsMax));
        sb.append("　生效上限=").append(effective < 0 ? "?" : String.valueOf(effective));
        sb.append("　降帧原因=").append(why == null ? "?" : why.name());
        if (lastGuardedValue > 0) {
            sb.append("　正在放行外部限帧=").append(lastGuardedValue).append("fps");
        } else if (guardedHits > 0) {
            sb.append("　曾放行外部限帧（累计 ").append(guardedHits).append(" 次）");
        } else {
            sb.append("　未检测到外部限帧");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 兼容旧调用点（1.20.1 时代的语义名字，26.3 里不再使用）
    // ------------------------------------------------------------------

    /** 26.3 下没有「无世界 60 帧」以外的独立常量；{@link InactivityFpsLimit} 仅用于人类可读名。 */
    public static String inactivityMode() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.options == null) {
                return "?";
            }
            InactivityFpsLimit v = mc.options.inactivityFpsLimit().get();
            return v == null ? "?" : v.name();
        } catch (Throwable t) {
            return "?";
        }
    }
}
