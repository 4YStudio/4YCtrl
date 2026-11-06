package com.fouryctrl.util;

import net.minecraft.client.MinecraftClient;

/**
 * 帧上限守卫：判断「这次 {@code RenderSystem.limitDisplayFPS(fps)} 的请求，
 * 是玩家自己的设定，还是别的模组塞进来的省电值」。
 *
 * <h2>为什么需要它</h2>
 * <p>本模组的 {@code RenderSystemMixin} 把 MC 的忙等自旋换成了真正的
 * {@code Thread.sleep}（因为 FCL 的 {@code glfwWaitEventsTimeout} 是空实现，
 * 见 {@code FoldCraftLauncher/LWJGL/3.4.1/src/main/java/org/lwjgl/glfw/GLFW.java:1365}）。</p>
 *
 * <p>副作用：省电类模组（典型是 FPS Reducer）会调用
 * {@code window.setFramerateLimit(10)} 来省电，它们本来是靠
 * {@code glfwWaitEventsTimeout} 睡过去的 —— 在 FCL 里那个调用无效，所以它们的限制
 * 本来就不生效。我们补上睡眠之后，别人的省电请求突然真的生效了，表现为
 * 「装上本模组就锁 10 帧，禁用就正常」。</p>
 *
 * <p>更具体的触发链（实测 FPS Reducer 1.20-2.5）：它每个 tick 用
 * {@code GLFW.glfwGetWindowAttrib(win, GLFW_FOCUSED)} 判断窗口是否在前台，
 * 而 FCL 的 LWJGL stub 从不写入 {@code GLFW_FOCUSED}
 * （{@code FoldCraftLauncher/LWJGL/3.4.1/src/main/java/org/lwjgl/glfw/GLFW.java:1015}
 * 只是 {@code windowAttribs.getOrDefault(attrib, 0)}，全文件只写过
 * GLFW_RESIZABLE / GLFW_CONTEXT_VERSION_* / GLFW_HOVERED / GLFW_VISIBLE），
 * 于是它永远认为「窗口在后台」，每个 tick 都请求 idleFps（配置里默认 10）。</p>
 *
 * <h2>判定方式（不写死任何 fps 数字，也不依赖「options 不是 260」）</h2>
 * <p>关键事实：MC 里 {@code Window.framerateLimit} 只在
 * {@code GameOptions.maxFps} 变动时被同步（{@code MinecraftClient.<init>} 与
 * {@code SimpleOption} 的变更回调），平时恒等于 {@code options.getMaxFps().getValue()}。
 * 所以「窗口上限 ≠ 玩家上限」本身就是「有别人改过」的证据。</p>
 *
 * <p>判定为外部限帧需同时满足：</p>
 * <ol>
 *   <li>{@code optionsMax > 0} 且 {@code windowMax >= 0}（两边都读得到，否则保守放行原行为）；</li>
 *   <li>{@code windowMax != optionsMax}（窗口上限被外部改过）；</li>
 *   <li>{@code fps <= windowMax}（本次请求不高于窗口上限）；</li>
 *   <li>{@code optionsMax >= 260}（玩家选「无限制」⇒ 任何限帧都不是他要的）
 *       <b>或</b> {@code fps < optionsMax * 3 / 4}（明显低于玩家设定，排除 60→50 这类正常降档）。</li>
 * </ol>
 *
 * <p>这样几类真实场景都正确：<br>
 * · {@code options=260, window=10, fps=10}（fpsreducer）→ 拦，保持满帧；<br>
 * · {@code options=60, window=60, fps=60}（正常）→ 不拦；<br>
 * · {@code options=260, window=260, fps=60}（世界外界面 MC 自己限 60，
 *   此时 window 仍是 260）→ 第 2 条不成立，不拦，界面照旧 60 帧；<br>
 * · {@code options=60, 外部设 window=10} → 拦；<br>
 * · 玩家自己真的把 maxFps 设成 30（window 也跟着 30）→ 第 2 条不成立，不拦。</p>
 *
 * <p>放行时最多 warn 3 次（避免刷屏），把 options / 参数 / window 三个数值都打出来，
 * 方便定位是哪个模组在省电。{@code /4y diag} 会显示 {@link #describe()}。</p>
 */
public final class FrameGuard {
    /** MC 里「无限制」档的哨兵值：{@code MinecraftClient.render} 只在 {@code limit < 260} 时调限帧。 */
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

    /** 玩家在视频设置里选的最大帧率；&gt;=260 表示「无限制」。取不到返回 0（= 无法判定，不拦）。 */
    public static int optionsMaxFps() {
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc == null || mc.options == null || mc.options.getMaxFps() == null) {
                return 0;
            }
            Integer v = mc.options.getMaxFps().getValue();
            return v == null ? 0 : v;
        } catch (Throwable t) {
            // 极早期（options 还没建好）或映射异常：退化成老行为（照常限帧）。
            return 0;
        }
    }

    /** {@code Window.framerateLimit} 的当前值；取不到返回 -1。 */
    public static int windowMaxFps() {
        try {
            return com.fouryctrl.util.Compat.windowFramerateLimit();
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 这次 {@code fps} 请求是不是「外部模组压进来的省电帧率」。
     * 判定为真时调用方应跳过睡眠（等价于 FCL 原本的空实现）。
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
        int optionsMax = optionsMaxFps();
        int windowMax = windowMaxFps();
        // 1. 两边都要读得到，否则无法分辨意图。
        if (optionsMax <= 0 || windowMax < 0) {
            return false;
        }
        // 2. 窗口上限与玩家上限不一致 = 有外部改过 Window.framerateLimit。
        if (windowMax == optionsMax) {
            return false;
        }
        // 3. 只在「不比窗口上限更松」时拦，绝不加严。
        if (fps > windowMax) {
            return false;
        }
        // 4a. 玩家选「无限制」：任何限帧都不是他要的。
        if (optionsMax >= UNLIMITED) {
            return true;
        }
        // 4b. 明显低于玩家设定（排除 60→50 这类正常降档）。
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
                        "4YCtrl: 检测到外部模组把帧上限压到 {} fps（玩家设定 {}，窗口上限 {}），"
                                + "已忽略这次限帧，避免游戏卡成幻灯片。"
                                + "常见原因：FPS Reducer 这类省电模组在 FCL 下误判「窗口在后台」"
                                + "（FCL 的 GLFW stub 从不写入 GLFW_FOCUSED）。"
                                + "建议禁用该模组，或把它的 idleFps 调高 / 关掉「后台降帧」。",
                        fps, optionsMaxFps(), windowMaxFps());
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
        int windowMax = windowMaxFps();
        StringBuilder sb = new StringBuilder();
        sb.append("玩家上限=").append(optionsMax <= 0 ? "?" : String.valueOf(optionsMax));
        sb.append("　窗口上限=").append(windowMax < 0 ? "?" : String.valueOf(windowMax));
        if (lastGuardedValue > 0) {
            sb.append("　正在放行外部限帧=").append(lastGuardedValue).append("fps");
        } else if (guardedHits > 0) {
            sb.append("　曾放行外部限帧（累计 ").append(guardedHits).append(" 次）");
        } else {
            sb.append("　未检测到外部限帧");
        }
        return sb.toString();
    }
}
