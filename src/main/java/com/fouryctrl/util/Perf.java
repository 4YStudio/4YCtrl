package com.fouryctrl.util;

import com.fouryctrl.FourYCtrl;
import net.minecraft.client.MinecraftClient;

/**
 * 极轻量的耗时探针：只做 System.nanoTime 累加，每 {@link #INTERVAL_NS} 打一行日志。
 *
 * <p>为什么需要它：安卓上「插手柄后掉到 10 帧」这类问题，静态看代码永远猜不准是哪一次
 * JNI / 哪一段绘制在吃时间；而 adb 又不好拿。把每一段的平均/最大耗时按秒打在日志里，
 * 一次就能定位。</p>
 *
 * <p>开销：启用时每次采样两次 {@code System.nanoTime()}（纳秒级），每秒刷一行日志。
 * 关掉（{@link #enabled} = false）时所有方法立刻返回，等于零成本。</p>
 *
 * <p><b>默认是关的</b>（旧版默认开机就采 3 分钟，日志里每 2 秒刷一行 {@code [perf]}，
 * 玩家反馈太吵）。要数据时手动 {@code /4y perf} 打开，采集窗口结束会自动关回去，
 * 也可以随时 {@code /4y off} 立刻关。</p>
 */
public final class Perf {
    private Perf() {
    }

    // ---- 段位编号（顺序必须与 NAMES 一致）----
    public static final int TICK = 0;
    public static final int PUMP = 1;
    public static final int POLL = 2;
    public static final int READ = 3;
    public static final int TOGGLE = 4;
    public static final int RADIAL = 5;
    public static final int GUI = 6;
    public static final int APPLY = 7;
    public static final int TRIG = 8;
    public static final int LOOK = 9;
    public static final int CURSOR = 10;
    public static final int HUD = 11;
    public static final int AXIS = 12;
    public static final int FRAME = 13;
    public static final int LIMIT = 14;
    /** 界面渲染链（{@code ScreenMixin} 那一段整体）。 */
    public static final int SCREEN = 15;
    /** 提示条绘制。 */
    public static final int HINTS = 16;
    /** 轮盘绘制。 */
    public static final int RMMENU = 17;

    private static final String[] NAMES = {
            "tick", "pump", "poll", "read", "toggle", "radial",
            "gui", "apply", "trig", "look", "cursor", "hud", "axis", "frame", "limit",
            "screen", "hints", "rmmenu"
    };

    private static final long[] SUM = new long[NAMES.length];
    private static final long[] MAX = new long[NAMES.length];
    private static final int[] CNT = new int[NAMES.length];

    private static final long INTERVAL_NS = 2_000_000_000L;
    private static long windowStart = System.nanoTime();

    /** 上一帧 updateMouse 的时间戳，用来直接量出真实帧间隔。 */
    private static long lastFrameNanos;

    /** 探针总开关（{@code /4y perf} 开、{@code /4y off} 关）。默认<b>关</b>，只在需要时手动开。 */
    public static volatile boolean enabled = false;

    /**
     * {@code /4y perf} 打开后，默认采集多久就自动关掉（秒）：拿到一次现场数据就够了，
     * 常驻每 2 秒刷一行日志会淹没其它有用的日志。
     */
    private static final long AUTO_OFF_SECONDS = 180L;
    private static long startedNanos = System.nanoTime();
    private static boolean autoDisabled;
    /** 「有界面但 Screen 渲染链一次都没跑」这个结论是否已经提示过（只说一次）。 */
    private static boolean offlineReported;

    /** 手动打开一个采集窗口（{@code /4y perf} 用）。 */
    public static void enable() {
        autoDisabled = false;
        offlineReported = false;
        startedNanos = System.nanoTime();
        windowStart = System.nanoTime();
        enabled = true;
    }

    /** 手动关掉探针（{@code /4y off} 用；日志刷得太吵时用）。 */
    public static void disable() {
        enabled = false;
        autoDisabled = true;
    }

    public static long begin() {
        return enabled ? System.nanoTime() : 0L;
    }

    public static void end(int id, long start) {
        if (!enabled || start == 0L) {
            return;
        }
        add(id, System.nanoTime() - start);
    }

    public static void add(int id, long nanos) {
        if (!enabled) {
            return;
        }
        SUM[id] += nanos;
        CNT[id]++;
        if (nanos > MAX[id]) {
            MAX[id] = nanos;
        }
    }

    /** 每帧（Mouse.updateMouse 头部）调一次：把帧间隔记进 frame 段。 */
    public static void frame() {
        if (!enabled) {
            return;
        }
        long now = System.nanoTime();
        long prev = lastFrameNanos;
        lastFrameNanos = now;
        if (prev != 0L) {
            long d = now - prev;
            if (d > 0L && d < 2_000_000_000L) {
                add(FRAME, d);
            }
        }
    }

    /** 由 endTick 每 tick 调一次；到点才真正打日志。 */
    public static void tick() {
        if (!enabled) {
            return;
        }
        long now = System.nanoTime();
        if (!autoDisabled && (now - startedNanos) / 1_000_000_000L > AUTO_OFF_SECONDS) {
            autoDisabled = true;
            enabled = false;
            FourYCtrl.LOG.info("[perf] 采集窗口结束（{} 秒），已自动关闭。要看再执行 /4y perf。",
                    AUTO_OFF_SECONDS);
            return;
        }
        if (now - windowStart < INTERVAL_NS) {
            return;
        }
        flush(now);
    }

    private static void flush(long now) {
        windowStart = now;
        MinecraftClient client = MinecraftClient.getInstance();
        int fps = client == null ? -1 : client.getCurrentFps();
        detectOfflineUi(client);
        StringBuilder sb = new StringBuilder(200);
        sb.append("[perf] fps=").append(fps);
        for (int i = 0; i < NAMES.length; i++) {
            if (CNT[i] == 0) {
                continue;
            }
            double avg = SUM[i] / (double) CNT[i] / 1.0e6;   // ms
            double max = MAX[i] / 1.0e6;
            sb.append(' ').append(NAMES[i]).append('=')
                    .append(String.format("%.2f", avg)).append('(')
                    .append(String.format("%.1f", max)).append(")x").append(CNT[i]);
            SUM[i] = 0L;
            MAX[i] = 0L;
            CNT[i] = 0;
        }
        FourYCtrl.LOG.info(sb.toString());
    }

    /**
     * 识别「界面渲染链没被调用」的平台：有些启动器（FCL 的触屏光标模式等）在有界面时
     * 不走 {@code Screen.renderWithTooltip}，于是 screen/hints/rmmenu 全是 0 次。
     *
     * <p>这个结论非常重要：它意味着「界面里掉帧」不可能来自模组的界面绘制，
     * 排查方向要转向帧循环本身（其它 mod、启动器、GPU 驱动）。</p>
     */
    private static void detectOfflineUi(MinecraftClient client) {
        if (offlineReported || client == null || client.currentScreen == null) {
            return;
        }
        int frames = Math.max(CNT[FRAME], CNT[HUD]);
        if (frames < 3 || CNT[SCREEN] > 0 || CNT[HINTS] > 0 || CNT[RMMENU] > 0) {
            return;
        }
        offlineReported = true;
        FourYCtrl.LOG.warn("[perf] 有界面，但这个窗口内 Screen 渲染链跑了 0 次"
                + "（screen/hints/rmmenu 都没有样本）→ 平台的帧循环在界面里绕过了"
                + " Screen.renderWithTooltip，界面掉帧与本模组的界面绘制无关。"
                + "对照本行前后的 frame/hud/tick 数值即可确认模组本身是否吃时间。");
    }

    /**
     * 把最近一个窗口的统计写成多行文本（给 {@code /4y perf} 用，玩家截图即可）。
     *
     * <p>不重置计数器：聊天栏只是「顺便看一眼」，不该打乱日志里的节奏。</p>
     */
    public static java.util.List<String> snapshot() {
        java.util.List<String> out = new java.util.ArrayList<>();
        MinecraftClient client = MinecraftClient.getInstance();
        long now = System.nanoTime();
        double win = (now - windowStart) / 1.0e9;
        out.add("fps=" + (client == null ? "?" : client.getCurrentFps())
                + "  窗口=" + String.format("%.1f", win) + "s");
        boolean uiChain = CNT[SCREEN] > 0 || CNT[HINTS] > 0 || CNT[RMMENU] > 0;
        if (client != null && client.currentScreen != null && !uiChain) {
            out.add("注意：当前有界面，但 Screen 渲染链 0 次样本 —— 本平台的帧循环"
                    + "没走 Screen.renderWithTooltip，界面掉帧与本模组绘制无关。");
        }
        for (int i = 0; i < NAMES.length; i++) {
            if (CNT[i] == 0) {
                continue;
            }
            out.add(NAMES[i] + ": 平均 " + String.format("%.2f", SUM[i] / (double) CNT[i] / 1.0e6)
                    + "ms  最大 " + String.format("%.1f", MAX[i] / 1.0e6)
                    + "ms  次数 " + CNT[i]);
        }
        return out;
    }
}
