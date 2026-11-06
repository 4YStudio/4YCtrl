package com.fouryctrl.input;

import com.fouryctrl.FourYCtrl;
import com.fouryctrl.sdl.Sdl3;

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * 手柄轮询线程：把手柄相关的<b>全部</b> SDL 调用搬到独立守护线程，游戏主线程只读一份
 * 「最新的原始快照」。
 *
 * <p><b>为什么必须这么做</b>（这是「插手柄进世界后锁 10 帧」的根因修复）：</p>
 * <p>以前 {@code SDL_PumpEvents / SDL_UpdateJoysticks / SDL_GetJoystickAxis /
 * SDL_GetJoystickButton} 全都跑在客户端线程上（{@code ClientTickEvents} 与
 * {@code Mouse.updateMouse} 注入点）。FCL 的 SDL3 是 Android 后端：手柄事件由
 * ART 侧 {@code SDLControllerManager.onNativeJoy()} 写进 SDL 的 joystick 状态，
 * 而所有 joystick API（{@code SDL_LockJoysticks}）共享同一把锁。一旦这把锁被
 * ART 侧或驱动长时间持有，客户端线程每次读轴都会阻塞——最坏情况就是一帧卡几十毫秒，
 * 表现为「刚好 10 帧」这种整数量级的锁帧。拔掉手柄后 {@code state.connected}
 * 变 false，注入点立刻早退，帧率瞬间恢复，这与玩家观察到的现象完全一致。</p>
 *
 * <p>把调用搬到本线程后：即使 SDL 内部被锁住，被阻塞的也只是这个守护线程（读到的数据会变旧，
 * 但视角/光标继续用上一份快照，最多延迟几毫秒），客户端线程<b>永远不会</b>被它拖住。</p>
 *
 * <p>顺带的好处：轮询频率（默认约 6ms 一次）远高于 tick 的 50ms，
 * 所以视角/GUI 光标的输入延迟反而比原来「每 tick 采样 + 每帧现读」更小。</p>
 *
 * <p>线程安全约定：{@link #axes}/{@link #buttons} 等「已发布快照」只在 {@link #lock}
 * 保护下读写；轮询线程先在自己的私有缓冲区里做完所有 JNI，再用一次
 * {@code System.arraycopy} 发布——<b>绝不可以在持锁期间做 JNI</b>，否则游戏线程
 * 拷贝快照时又会被锁住，等于没修。</p>
 */
final class GamepadPoller implements Runnable {
    /** 轴数量上限（与 GamepadManager 保持一致，防异常驱动返回巨值）。 */
    static final int MAX_AXES = 64;
    /** 轮询间隔（毫秒）。6ms ≈ 166Hz，比 20Hz 的 tick 细得多，鼠标侧 60~120 帧都够用。 */
    private static final long LOOP_MS = 6L;
    /** 设备表扫描间隔（毫秒，可由配置覆盖）。SDL 自己的热插拔检测本来就有节流，扫更勤也没有意义。 */
    private volatile long scanIntervalMs = 500L;
    /**
     * 泵事件间隔（毫秒）。
     *
     * <p>Android 后端的轴/键状态是事件驱动写进 SDL 的（{@code onNativeJoy} 直接调
     * {@code SDL_PrivateJoystickAxis}），所以「读轴」不需要先泵事件；泵事件只负责
     * 消化事件队列与热插拔检测。20Hz 与以前每 tick 一次完全一致，不必更快 ——
     * 泵一次会进 ART 侧枚举 InputDevice，太勤反而给启动器添负担。</p>
     */
    private static final long PUMP_INTERVAL_MS = 50L;
    /** 掉线探测间隔（毫秒）：{@code SDL_JoystickConnected} 也不便宜，别每 6ms 问一次。 */
    private static final long PROBE_INTERVAL_MS = 50L;
    /**
     * 按键读取间隔（毫秒）。
     *
     * <p>按键最终只被 20Hz 的 tick 消费（{@code applyActions} 每 tick 读一次），
     * 读得比 tick 快毫无意义；而一次按键轮询是 15 次 JNI。轴则不同：视角和 GUI
     * 光标是每帧用的，必须比 tick 细。所以两条读路径用不同的频率 —— 每秒的
     * JNI 从约 3500 次降到约 1300 次，轮询线程自己也不至于占满一个核。</p>
     */
    private static final long BUTTON_INTERVAL_MS = 20L;
    /** 电量查询间隔（毫秒）：系统电量广播本来就很稀疏，不用每次轮询都问。 */
    private static final long POWER_INTERVAL_MS = 5_000L;

    // ---------------------------------------------------------------- 已发布快照
    private final Object lock = new Object();
    /** 当前手柄的轴读数（未套死区，-1~1）。 */
    private float[] axes = new float[0];
    /** 当前手柄的原始按钮态（未去抖，已含 hat 回落）。 */
    private final boolean[] buttons = new boolean[32];
    private String name = "";
    private boolean connected;
    private int power = -1;
    private int[] ids = new int[0];
    /** 每次发布都自增，调试用。 */
    private long seq;
    /** 连接状态或设备变化时自增：客户端线程靠它判断「要不要打日志/套用配置档」。 */
    private int deviceEpoch;

    // ---------------------------------------------------------------- 命令（客户端线程 → 本线程）
    private volatile boolean running = true;
    /** 指定要打开的设备 instance id；{@link Integer#MIN_VALUE} 表示没有请求。 */
    private volatile int requestOpenId = Integer.MIN_VALUE;
    /** 轮换方向：0 = 无请求，+1/-1 表示下一个/上一个。 */
    private volatile int requestCycle;
    private volatile boolean requestRedetect;
    /** 震动请求队列，元素为 {low, high, durationMs}。 */
    private final ArrayDeque<int[]> rumbleQueue = new ArrayDeque<>();
    private volatile boolean requestTestRumble;
    /** 诊断命令「震一下」用的强度（0..65535）。 */
    private volatile int testStrength = 0xFFFF / 2;

    private Thread thread;

    // ---------------------------------------------------------------- 仅本线程访问
    private long js;
    private int jsId = -1;
    private int manualJsId = -1;
    private int joystickSlot;
    private long lastScanNanos;
    private long lastProbeNanos;
    private long lastPumpNanos;
    private long lastButtonNanos;
    private long lastPowerNanos;
    private volatile int axisCount;
    private volatile int buttonCount;
    private volatile int hatCount;
    /** 私有读缓冲：先在这里把 JNI 全做完，再一次性发布。 */
    private final float[] readAxes = new float[MAX_AXES];
    private final boolean[] readButtons = new boolean[32];
    private long lastWarnNanos;
    /**
     * 单次 {@link #step()} 的平均耗时（微秒，近似 EWMA）。
     *
     * <p>这是判断「SDL 的 joystick 锁是不是真的在被长期占用」的唯一直接证据：
     * 它只统计本线程自己被 JNI 卡住的时间，与渲染线程完全无关。正常情况下
     * 一次 step 只有几十微秒（十几次 JNI）；如果它涨到几毫秒，说明锁竞争是真的，</p>
     */
    private volatile int stepMicros;
    /** 「轮询偏慢」的日志只打一次，避免刷屏。 */
    private boolean slowWarned;

    /** 单次轮询（读轴+读键+泵事件）的平均耗时，单位微秒。 */
    int stepMicros() {
        return stepMicros;
    }

    /** 设备表扫描间隔（毫秒，来自配置里的 joystickPollTicks × 50ms）。 */
    void setScanIntervalMs(long ms) {
        scanIntervalMs = Math.max(50L, ms);
    }

    void start() {
        if (thread != null) {
            return;
        }
        Thread t = new Thread(this, "4YCtrl-Gamepad");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    /** 停线程并关掉手柄句柄（关闭在 {@link #run()} 的 finally 里做，避免跨线程动句柄）。 */
    void shutdown() {
        running = false;
        Thread t = thread;
        thread = null;
        if (t == null) {
            return;
        }
        t.interrupt();
        try {
            t.join(800L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------- 给客户端线程的读接口

    /**
     * 把最新快照拷进调用方数组。
     *
     * @return 当前是否已连接（快照内容在未连接时是全 0）
     */
    boolean snapshot(float[] outAxes, boolean[] outButtons) {
        synchronized (lock) {
            int n = Math.min(outAxes.length, axes.length);
            System.arraycopy(axes, 0, outAxes, 0, n);
            for (int i = n; i < outAxes.length; i++) {
                outAxes[i] = 0f;
            }
            int bn = Math.min(outButtons.length, buttons.length);
            System.arraycopy(buttons, 0, outButtons, 0, bn);
            for (int i = bn; i < outButtons.length; i++) {
                outButtons[i] = false;
            }
            return connected;
        }
    }

    /**
     * 只拷轴读数（每帧用，比 {@link #snapshot} 便宜：不碰 buttons）。
     *
     * @return 拷贝了多少个轴
     */
    int snapshotAxes(float[] out) {
        synchronized (lock) {
            int n = Math.min(out.length, axes.length);
            System.arraycopy(axes, 0, out, 0, n);
            return n;
        }
    }

    int axisCount() {
        synchronized (lock) {
            return axes.length;
        }
    }

    int buttonCount() {
        synchronized (lock) {
            return buttonCount;
        }
    }

    int hatCount() {
        synchronized (lock) {
            return hatCount;
        }
    }

    String name() {
        synchronized (lock) {
            return name;
        }
    }

    int power() {
        synchronized (lock) {
            return power;
        }
    }

    boolean isConnected() {
        synchronized (lock) {
            return connected;
        }
    }

    int deviceId() {
        synchronized (lock) {
            return jsId;
        }
    }

    int deviceEpoch() {
        synchronized (lock) {
            return deviceEpoch;
        }
    }

    /** 当前 SDL 设备表里的 instance id 列表（副本，调用方随便改）。 */
    int[] ids() {
        synchronized (lock) {
            return ids.clone();
        }
    }

    long seq() {
        synchronized (lock) {
            return seq;
        }
    }

    // ---------------------------------------------------------------- 给客户端线程的写接口

    void requestOpen(int instanceId) {
        requestOpenId = instanceId;
    }

    void requestCycle(int dir) {
        requestCycle = dir == 0 ? 0 : (dir > 0 ? 1 : -1);
    }

    void requestRedetect() {
        requestRedetect = true;
    }

    void queueRumble(int low, int high, int durationMs) {
        synchronized (rumbleQueue) {
            if (rumbleQueue.size() >= 8) {
                rumbleQueue.pollFirst();
            }
            rumbleQueue.addLast(new int[]{low, high, durationMs});
        }
    }

    void queueTestRumble(int strength) {
        testStrength = strength;
        requestTestRumble = true;
    }

    // ---------------------------------------------------------------- 轮询循环

    @Override
    public void run() {
        try {
            while (running) {
                long t0 = System.nanoTime();
                try {
                    step();
                } catch (Throwable t) {
                    warnThrottled("4YCtrl: 手柄轮询异常（已跳过本次）", t);
                }
                long spentNanos = System.nanoTime() - t0;
                int micros = (int) Math.min(Integer.MAX_VALUE, spentNanos / 1_000L);
                // 指数滑动平均（1/8 权重）：单次抖动不至于让读数乱跳。
                stepMicros += (micros - stepMicros) / 8;
                if (!slowWarned && stepMicros > 5_000) {
                    // 一次轮询超过 5ms 说明 SDL 的 joystick 锁竞争是真的（正常只有几十微秒）。
                    // 这不影响帧率了（本线程是独立守护线程），但值得在日志里留一条，
                    // 方便以后判断「视角为什么有点滞后」。
                    slowWarned = true;
                    FourYCtrl.LOG.warn("4YCtrl: 手柄轮询单次耗时 {}μs（偏高，但读状态已不在主线程，"
                            + "不会掉帧；视角可能出现轻微滞后）", stepMicros);
                }
                long spentMs = spentNanos / 1_000_000L;
                long sleep = LOOP_MS - spentMs;
                if (sleep > 0L) {
                    try {
                        Thread.sleep(sleep);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
        } finally {
            if (js != 0L) {
                try {
                    Sdl3.closeJoystick(js);
                } catch (Throwable ignored) {
                    // 退出阶段，忽略
                }
                js = 0L;
            }
        }
    }

    private void step() {
        // 注意：初始化（dlopen + SDL_InitSubSystem）由客户端线程做、本线程只认结论。
        // 原因：SDL_InitSubSystem 在 FCL 下会经代理回调进 ART 侧（notifyLauncher →
        // SdlBridge.setupJNI / SDLSurface 重新协商），那条路径已经验证过只能在
        // 客户端线程走；而真正会卡帧的是「每 tick 的读轴读键」，那部分现在全在这里。
        if (!Sdl3.isReady()) {
            publishDisconnected();
            return;
        }

        // 1) 处理客户端线程的请求
        if (requestRedetect) {
            requestRedetect = false;
            closeJs();
            manualJsId = -1;
            joystickSlot = 0;
            lastScanNanos = 0L;
            publishDisconnected();
            Sdl3.retry();
            return;
        }
        int want = requestOpenId;
        if (want != Integer.MIN_VALUE) {
            requestOpenId = Integer.MIN_VALUE;
            manualJsId = want;
            openId(want);
            lastScanNanos = System.nanoTime();
        }
        int cycle = requestCycle;
        if (cycle != 0) {
            requestCycle = 0;
            cycleJoystick(cycle);
            lastScanNanos = System.nanoTime();
        }
        drainRumble();

        // 2) 泵事件 + 更新手柄（20Hz 够用，见 PUMP_INTERVAL_MS 的说明）
        long now = System.nanoTime();
        if (now - lastPumpNanos > PUMP_INTERVAL_MS * 1_000_000L) {
            lastPumpNanos = now;
            Sdl3.pumpEvents();
            Sdl3.updateJoysticks();
        }

        // 3) 掉线探测 + 设备扫描
        boolean needScan = js == 0L || now - lastScanNanos > scanIntervalMs * 1_000_000L;
        if (!needScan && now - lastProbeNanos > PROBE_INTERVAL_MS * 1_000_000L) {
            lastProbeNanos = now;
            if (!Sdl3.joystickConnected(js)) {
                // 拔掉了：立刻关句柄并重扫，玩家松手就要能感觉到「没输入了」。
                closeJs();
                publishDisconnected();
                needScan = true;
            }
        }
        if (needScan) {
            scan();
            lastScanNanos = now;
        }
        if (js == 0L) {
            publishDisconnected();
            return;
        }

        // 4) 读状态（所有 JNI 都在这里，跑在本线程）
        read();

        // 5) 电量偶尔刷一次
        if (now - lastPowerNanos > POWER_INTERVAL_MS * 1_000_000L) {
            lastPowerNanos = now;
            int p = Sdl3.powerPercent(js);
            synchronized (lock) {
                power = p;
            }
        }
    }

    private void warnThrottled(String msg, Throwable t) {
        long now = System.nanoTime();
        if (now - lastWarnNanos < 1_000_000_000L) {
            return;
        }
        lastWarnNanos = now;
        FourYCtrl.LOG.warn(msg, t);
    }

    // ---------------------------------------------------------------- 设备

    private void scan() {
        int[] list = Sdl3.getJoystickIds();
        synchronized (lock) {
            ids = list;
        }
        int best = choose(list);
        if (best != jsId) {
            openId(best);
        }
    }

    /**
     * 从设备表里挑一个来用：多手柄时按「轴数最多的优先」，同一档内保持
     * {@link #joystickSlot} 的顺序；玩家手动选过（{@link #manualJsId}）就尊重手动选择。
     */
    private int choose(int[] list) {
        if (list.length == 0) {
            return -1;
        }
        if (list.length == 1) {
            joystickSlot = 0;
            manualJsId = -1;
            return list[0];
        }
        if (manualJsId >= 0) {
            for (int id : list) {
                if (id == manualJsId) {
                    return id;
                }
            }
            // 手动选的那只拔了：回到自动挑选
            manualJsId = -1;
        }
        if (joystickSlot < 0 || joystickSlot >= list.length) {
            joystickSlot = 0;
        }
        int bestIdx = -1;
        int bestAxes = 0;
        for (int i = 0; i < list.length; i++) {
            int a = probeAxes(list[i]);
            if (a > bestAxes) {
                bestAxes = a;
                bestIdx = i;
            }
        }
        return bestIdx >= 0 ? list[bestIdx] : list[joystickSlot];
    }

    /**
     * 临时打开一次设备问轴数（挑设备用），问完立刻关掉。
     *
     * <p>两个护栏，都是「不能把正在用的句柄关掉」：</p>
     * <ul>
     *   <li>探的就是当前已连接的设备 → 直接用已缓存的能力值，不重复 open；</li>
     *   <li>SDL 对同一 instance id 可能返回<b>同一个</b>句柄，如果探针拿到的就是
     *       {@link #js}，关掉它等于把正在读的手柄关了（后面 read() 就在摸僵尸指针）。
     *       所以只在「拿到的句柄不是当前句柄」时才关。</li>
     * </ul>
     */
    private int probeAxes(int instanceId) {
        if (js != 0L && instanceId == jsId) {
            return axisCount;
        }
        long h = Sdl3.openJoystick(instanceId);
        if (h == 0L) {
            return 0;
        }
        try {
            return Sdl3.numAxes(h);
        } finally {
            if (js == 0L || h != js) {
                Sdl3.closeJoystick(h);
            }
        }
    }

    private void closeJs() {
        if (js != 0L) {
            Sdl3.closeJoystick(js);
            js = 0L;
        }
        jsId = -1;
        axisCount = 0;
        buttonCount = 0;
        hatCount = 0;
        lastButtonNanos = 0L;
        Arrays.fill(readButtons, false);
    }

    private void openId(int wantId) {
        closeJs();
        if (wantId < 0) {
            publishDisconnected();
            return;
        }
        long handle = Sdl3.openJoystick(wantId);
        if (handle == 0L) {
            FourYCtrl.LOG.warn("SDL_OpenJoystick({}) 失败：{}", wantId, Sdl3.safeError());
            publishDisconnected();
            return;
        }
        js = handle;
        jsId = wantId;
        int a = Sdl3.numAxes(handle);
        if (a < 0) {
            a = 0;
        }
        if (a > MAX_AXES) {
            a = MAX_AXES;
        }
        axisCount = a;
        buttonCount = Sdl3.numButtons(handle);
        hatCount = Sdl3.numHats(handle);
        int p = Sdl3.powerPercent(handle);
        String n = Sdl3.joystickName(handle);
        synchronized (lock) {
            axes = new float[a];
            name = n == null ? "" : n;
            power = p;
            connected = true;
            deviceEpoch++;
            seq++;
        }
        lastPowerNanos = System.nanoTime();
    }

    private void cycleJoystick(int dir) {
        int[] list = Sdl3.getJoystickIds();
        synchronized (lock) {
            ids = list;
        }
        if (list.length <= 1) {
            return;
        }
        joystickSlot = ((joystickSlot + dir) % list.length + list.length) % list.length;
        manualJsId = list[joystickSlot];
        openId(manualJsId);
    }

    private void drainRumble() {
        if (requestTestRumble) {
            requestTestRumble = false;
            if (js != 0L) {
                int s = testStrength;
                Sdl3.rumble(js, s, s, 400);
            }
        }
        while (true) {
            int[] r;
            synchronized (rumbleQueue) {
                r = rumbleQueue.pollFirst();
            }
            if (r == null) {
                return;
            }
            if (js != 0L) {
                Sdl3.rumble(js, r[0], r[1], r[2]);
            }
        }
    }

    // ---------------------------------------------------------------- 读状态

    private void read() {
        int n = axisCount;
        for (int i = 0; i < n; i++) {
            readAxes[i] = Sdl3.axis(js, i);
        }
        // 按键比轴慢一档（20Hz 就够，见 BUTTON_INTERVAL_MS）。
        long now = System.nanoTime();
        if (lastButtonNanos == 0L || now - lastButtonNanos > BUTTON_INTERVAL_MS * 1_000_000L) {
            lastButtonNanos = now;
            for (Button b : Button.ALL) {
                readButtons[b.index] = Sdl3.button(js, b.index);
            }
            applyHatFallback();
        }

        synchronized (lock) {
            if (axes.length != n) {
                axes = new float[n];
            }
            System.arraycopy(readAxes, 0, axes, 0, n);
            System.arraycopy(readButtons, 0, buttons, 0, buttons.length);
            seq++;
        }
    }

    /**
     * 十字键回落：部分手柄（尤其是虚拟手柄、以及 Android 上带 hat 的老设备）
     * 不把十字键上报成 button 11~14，而是上报成 hat（帽开关）。
     *
     * <p>SDL 的 hat 位定义：{@code 0x01=上, 0x02=右, 0x04=下, 0x08=左}。
     * 只有当四个 DPAD button 全为 false（设备确实没把它们当按钮报）时才回落，
     * 否则会把真按下的方向和 hat 的滞后读数混在一起。</p>
     */
    private void applyHatFallback() {
        boolean anyDpad = readButtons[Button.DPAD_UP.index] || readButtons[Button.DPAD_DOWN.index]
                || readButtons[Button.DPAD_LEFT.index] || readButtons[Button.DPAD_RIGHT.index];
        if (anyDpad || hatCount <= 0) {
            return;
        }
        int hat = Sdl3.hat(js, 0);
        if (hat == 0) {
            return;
        }
        if ((hat & 0x01) != 0) {
            readButtons[Button.DPAD_UP.index] = true;
        }
        if ((hat & 0x04) != 0) {
            readButtons[Button.DPAD_DOWN.index] = true;
        }
        if ((hat & 0x08) != 0) {
            readButtons[Button.DPAD_LEFT.index] = true;
        }
        if ((hat & 0x02) != 0) {
            readButtons[Button.DPAD_RIGHT.index] = true;
        }
    }

    private void publishDisconnected() {
        synchronized (lock) {
            boolean was = connected;
            connected = false;
            name = "";
            power = -1;
            if (axes.length != 0) {
                axes = new float[0];
            }
            Arrays.fill(buttons, false);
            if (was) {
                deviceEpoch++;
            }
            seq++;
        }
    }
}
