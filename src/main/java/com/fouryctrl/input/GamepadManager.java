package com.fouryctrl.input;

import com.fouryctrl.FourYCtrl;
import com.fouryctrl.client.RadialMenu;
import com.fouryctrl.config.ModConfig;
import com.fouryctrl.mixin.HandledScreenAccessor;
import com.fouryctrl.mixin.MouseAccessor;
import com.fouryctrl.mixin.RecipeBookResultsAccessor;
import com.fouryctrl.mixin.RecipeBookWidgetAccessor;
import com.fouryctrl.sdl.Sdl3;
import com.fouryctrl.util.Compat;
import com.fouryctrl.util.Notify;
import com.fouryctrl.util.Perf;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.recipebook.AnimatedResultButton;
import net.minecraft.client.gui.screen.recipebook.RecipeBookResults;
import net.minecraft.client.gui.screen.recipebook.RecipeBookWidget;
import net.minecraft.client.gui.screen.recipebook.RecipeGroupButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.InputUtil;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.slot.Slot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * 手柄状态机：从 SDL3 抓帧 → 归一化成 {@link GamepadState} → 翻译成 Minecraft 的输入。
 *
 * <p>为什么不让 Minecraft 自己读手柄：MC 1.20.1 在 FCL 下跑的是 LWJGL 3.3.3，
 * 那一份 GLFW 的手柄 API 是空壳（{@code glfwGetJoystickAxes} 永远返回 capacity=0 的 buffer），
 * 所以任何走 GLFW 的模组都读不到数据。我们绕开它，直接读进程内的 SDL3。</p>
 *
 * <p>为什么视角不用 {@code Entity.changeLookDirection} 而写 {@code Mouse.cursorDeltaX/Y}：
 * 写 cursorDelta 可以让 Minecraft 自己套用它那套灵敏度曲线、平滑相机、望远镜、反转 Y，
 * 手感和 PC 上接手柄完全一致，也就彻底避开了 FCL 映射模式把摇杆量化成 8 个方向的毛病。</p>
 */
public final class GamepadManager {
    private final ModConfig cfg;

    /** 本帧手柄快照（渲染线程与客户端线程是同一个线程，无需同步）。 */
    private final GamepadState state = new GamepadState();
    /** 上一帧快照，用来做边沿检测。 */
    private final GamepadState prev = new GamepadState();

    /**
     * 所有 SDL 手柄调用（泵事件、设备扫描、读轴、读键、震动）都在这个线程里跑。
     *
     * <p>客户端线程只读 {@link GamepadPoller} 发布出来的快照 —— 这是「插手柄进世界后
     * 锁 10 帧」的根因修复：SDL 的 joystick 锁一旦被 ART 侧或驱动长时间持有，任何在
     * 客户端线程上发起的 SDL 调用都会把整帧拖住；搬到本线程后最多只是数据变旧。</p>
     */
    private final GamepadPoller poller = new GamepadPoller();
    /** 最近一次读到的设备变化代号，用来只在「刚连上/刚断开」时打日志、套配置档。 */
    private int lastDeviceEpoch = -1;
    /** 每帧刷新的轴读数（不含 JNI，纯粹从轮询线程的快照拷贝）。 */
    private final float[] frameAxes = new float[GamepadPoller.MAX_AXES];
    /** {@link #frameAxes} 里有效的前多少个。 */
    private int frameAxisCount;
    /** 每 tick 刷新的轴/键快照（同样零 JNI，只做一次数组拷贝）。 */
    private final float[] tickAxes = new float[GamepadPoller.MAX_AXES];
    private final boolean[] tickButtons = new boolean[32];
    /** 本 tick 快照里的连接状态与有效轴数。 */
    private boolean snapshotConnected;
    private int snapshotAxisCount;
    /** 上一次设备事件时是否连着，用于只在「真断开」时做一次性收尾。 */
    private boolean pollerWasConnected;
    private boolean shutdown;

    /** 按钮去抖窗长（tick）：按下立即生效，松开需要连续这么多 tick 确认。 */
    private static final int DEBOUNCE_TICKS = 2;
    /** 轴数量上限（防异常驱动返回巨值导致大数组分配 + 大量无意义 JNI）。 */
    private static final int MAX_AXES = 64;
    /** 去抖后的稳定按钮态（held/边沿检测全用它）。 */
    private final boolean[] buttonStable = new boolean[32];
    /** 松开方向待确认的计数（按下不走它，按下是即时的）。 */
    private final int[] buttonReleaseTicks = new int[32];

    /** 当前按住的动作集合（本帧），用于和上一帧做边沿比较。 */
    private final EnumSet<Action> prevHeld = EnumSet.noneOf(Action.class);
    private final EnumSet<Action> held = EnumSet.noneOf(Action.class);

    /** 上一 tick 实际下发给游戏的按键集合。applyHeld 的边沿判断必须基于它：
     *  prevHeld 记的是物理按钮，toggle 模式下「开关开着、物理键已松开」时
     *  want 恒为 true 而 prevHeld 不含动作，若用 prevHeld 当 was 会每 tick
     *  重复触发 onKeyPressed（潜行高频蹲起），且释放分支永远不跑（疾跑解除了）。 */
    private final EnumSet<Action> applied = EnumSet.noneOf(Action.class);

    /** KeyBinding → 它绑定的 InputUtil.Key 的缓存（MC 没有公开 getter，只能靠 translationKey 反查）。
     *  用 IdentityHashMap：KeyBinding 的 equals/hashCode 没有重写，但恒等比较更快也更安全。 */
    private final Map<KeyBinding, InputUtil.Key> keyCache = new IdentityHashMap<>();

    private double lookX;
    private double lookY;
    private long lastLookNanos;
    private int debugTicks;

    // ---- 每帧刷新轴快照 ----
    // 这里是「每帧」与「每 tick」两条读路径的说明：
    //
    //   beginFrame()  —— 由 Mouse.updateMouse 的 HEAD 每帧调一次，把轮询线程最新发布的
    //                    轴快照拷进 frameAxes。computeLook（HEAD）与 frameCursor（RETURN）
    //                    在同一次 updateMouse 里先后执行，共用这一份，零 JNI。
    //   readState()   —— 每 tick 把轴 + 按钮快照拷进 tickAxes/tickButtons，再组装 GamepadState。
    //
    // 两者都不发起任何 SDL 调用：所有 JNI 都在 GamepadPoller 线程里，客户端线程
    // 绝不会被 SDL 的 joystick 锁阻塞（那正是原来「锁 10 帧」的原因）。

    /** L3+R3 组合键已经按住多少 tick。 */
    private int comboHold;
    /** 本次组合键是否已经触发过（松开前不重复触发）。 */
    private boolean comboFired;
    /** 上一 tick 是否处于「无任何界面」的游戏状态，用于处理 setScreen 清空按键的问题。 */
    private boolean wasInGame;

    /** L3+R3 需要按住的 tick 数（20 tick = 1 秒）。 */
    private static final int COMBO_TICKS = 15;

    /** 切换式（toggle）动作的当前状态：true = 已激活（潜行中 / 疾跑中）。 */
    private boolean toggleSneakOn;
    private boolean toggleSprintOn;

    /** GUI 光标状态（缩放坐标，与 Mouse.x/y 同一坐标系）。 */
    private boolean cursorActive;
    private double cursorX;
    private double cursorY;
    /** 光标是否真的动过：只有动过才画出来，避免「不推摇杆屏幕中间悬一个箭头」。 */
    private boolean cursorVisible;
    /** 逐帧推进光标的上一帧时间戳（纳秒）；0 = 需要重新起算。 */
    private long lastCursorFrameNanos;
    /** 十字键微调光标的按住计数（0=上 1=下 2=左 3=右），用于长按连续挪动。 */
    private final int[] nudgeHold = new int[4];
    /** LB/RB 模拟滚轮的按住计数：首按滚一格，按住连滚。 */
    private int scrollHoldUp;
    private int scrollHoldDown;

    /** 上一 tick 的界面引用：界面切换时要把光标挪到容器的第一个格子上。 */
    private Screen lastTickScreen;
    /** 十字键四个方向的按住计数（0=上 1=下 2=左 3=右），用于长按加速重复。 */
    private final int[] dpadHold = new int[4];
    /** 我们是否主动把玩家置成了疾跑状态（关掉疾跑开关时要收回，否则会一直跑）。 */
    private boolean sprintApplied;

    /** 震动的剩余时长（tick）。到 0 后下一帧会把震动停掉。 */
    private int rumbleTicks;
    private int rumbleLow;
    private int rumbleHigh;

    /** 每个轴的原始读数（未套死区），供设置界面显示，用来判断死区是否合适。 */
    private float[] rawAxes = new float[0];

    // ---- 绑定表预编译 ----
    /** 编译后的绑定：按钮 → 动作。避免每 tick 对 {@code cfg.bindings} 做字符串解析。 */
    private final EnumMap<Button, Action> bind = new EnumMap<>(Button.class);
    /** 上一份绑定表的特征值，变了才重新编译。 */
    private int bindHash = 0x7FFFFFFF;

    // ---- 键盘键转发表（按钮 → 任意 KeyBinding 的按键） ----
    /**
     * 编译后的键盘绑定：按钮 → {@link InputUtil.Key}。
     *
     * <p>和上面的 {@link #bind}（本模组十来个动作）不同，这张表指向 MC 自己的
     * KeyBinding —— 包括原版和<b>其它模组</b>注册的，所以能用来触发别的模组的功能。</p>
     */
    private final EnumMap<Button, InputUtil.Key> keyBind = new EnumMap<>(Button.class);
    /** {@code cfg.keyBindings} 的上一份特征值（改它不会改 {@code cfg.bindings.hashCode()}，必须单独记）。 */
    private int keyBindHash = 0x7FFFFFFF;
    /** 当前由我们按下、还没松开的键盘键。存 Key 而不是按钮，避免改绑后抬起的是新键。 */
    private final EnumMap<Button, InputUtil.Key> keyBindApplied = new EnumMap<>(Button.class);



    // ---- 轴校准 ----
    /** 每个轴的读数区间采样（识别摇杆/扳机用）。 */
    private final float[] calibMin = new float[16];
    private final float[] calibMax = new float[16];
    private final float[] calibRest = new float[16];
    /** 采集到的轴数量（0 = 本帧没采）。 */
    private int calibAxes;
    /** 已采样 tick 数；到 {@link #CALIB_TICKS} 后做一次判定。 */
    private int calibTicks;
    private boolean calibDone;
    private static final int CALIB_TICKS = 30;
    /** 摇杆中点（减法校正，抵消手柄磨损造成的漂移）；下标即轴号。 */
    private final float[] axisCenter = new float[16];

    // ---- 震动 ----
    /** 上一 tick 攻击/使用是否已触发过震动（边沿用，不再靠 rumbleTicks 去重）。 */
    private int lastRumbleNanos;
    /** 主手已有过「耐久告危」提醒的 stack hash，换物品后清零重新武装。 */
    private int durabilityWarnStack;
    /** 上一 tick 的饥饿值 / 受伤计时 / 背包物品总数，用于边沿检测。 */
    private int lastFoodLevel = -1;
    private int lastHurtTime;
    private int lastItemCount = -1;

    private static String fmt(float v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    /** 当前按下的按钮名，仅用于 debug 日志。 */
    public String pressedSummary() {
        StringBuilder sb = new StringBuilder();
        for (Button b : Button.ALL) {
            if (state.isDown(b)) {
                if (sb.length() > 0) {
                    sb.append('+');
                }
                sb.append(b.name());
            }
        }
        return sb.length() == 0 ? "-" : sb.toString();
    }

    public GamepadManager(ModConfig cfg) {
        this.cfg = cfg;
        // 手柄轮询线程：所有 SDL 手柄调用（泵事件、扫描设备、读轴读键、震动）都在它上面跑，
        // 客户端线程只读快照。守护线程，游戏退出时随进程结束；CLIENT_STOPPING 里也会显式关。
        poller.setScanIntervalMs(Math.max(1, cfg.joystickPollTicks) * 50L);
        poller.start();
    }

    public GamepadState state() {
        return state;
    }

    /**
     * 全部轴的原始读数（未套死区，-1~1），索引即 {@code SDL_GetJoystickAxis} 的轴号。
     * 返回的是内部数组，调用方只读。
     */
    public float[] rawAxes() {
        return rawAxes;
    }

    /** 轮询线程当前发布的数据代号（单调递增），调试/诊断用。 */
    public long pollerSeq() {
        return poller.seq();
    }

    /** 轮询线程当前能读到多少个轴（0 = 没连手柄，诊断用）。 */
    public int pollerAxisCount() {
        return poller.axisCount();
    }

    /**
     * 轮询线程单次轮询的平均耗时（微秒）。
     *
     * <p>这是「卡 10 帧」排障的关键读数：它只反映轮询线程被 SDL 的 joystick 锁
     * 卡住多久。若它只有几十微秒而帧率依然很低，说明瓶颈不在手柄读状态下。</p>
     */
    public int pollerStepMicros() {
        return poller.stepMicros();
    }

    /** 链路状态一句话描述（来自 {@link Sdl3#status()}），供设置界面显示。 */
    public String sdlStatus() {
        return Sdl3.status();
    }

    /** 当前手柄是否支持震动（SDL 函数指针是否解析成功）。 */
    public boolean hasRumble() {
        return Sdl3.hasRumble();
    }

    /**
     * 手柄电量百分比（0~100），未知或系统未上报时返回 -1。
     * 数据来自 {@code SDL_GetJoystickPowerInfo}，安卓后端会读系统的电量广播。
     */
    public int powerPercent() {
        return poller.power();
    }

    /** 当前连接的手柄名（SDL 报上来的名字，已清洗），未连接或驱动没报名字时返回空串。 */
    public String deviceName() {
        String n = poller.name();
        return n == null ? "" : n;
    }

    /**
     * 界面上显示用的手柄名：驱动程序普遍会漏报名字（SDL 给空串或全乱码），
     * 这时不能显示一个空白，回退成「手柄 #实例号」。
     *
     * <p>注意别拿这个当配置档的 key —— 它是展示用的，实例号会变；
     * 存档一律用 {@link #deviceName()}（见 {@code /4y savepad}）。</p>
     */
    public String deviceLabel() {
        String n = deviceName();
        if (!n.isBlank()) {
            return n;
        }
        return "手柄 #" + poller.deviceId();
    }

    /** GUI 光标当前位置（缩放坐标）。 */
    public double cursorX() {
        return cursorX;
    }

    public double cursorY() {
        return cursorY;
    }

    /** GUI 光标是否应该画出来（开关打开、手柄连接、且玩家推过摇杆）。 */
    public boolean isCursorVisible() {
        return cfg.enabled && state.connected && cursorVisible;
    }

    /** 模组是否真的在接管输入（开关打开且手柄连着）。 */
    public boolean isActive() {
        return cfg.enabled && state.connected;
    }

    /**
     * 本 tick 玩家是否应该是潜行状态。
     *
     * <p>为什么不走 {@code KeyBinding}：{@code GameOptions.sneakKey} 是
     * {@code StickyKeyBinding}，当玩家在无障碍选项里打开「切换潜行」时，它的
     * {@code setPressed(false)} 会被<b>完全忽略</b>（只有 true 会翻转），于是
     * 「松开 R3 却站不起来」「再点 R3 又立刻蹲下」—— 这就是真机上反复出现的 bug。
     * 改 {@code Input.sneaking} 则直接进 {@code ClientPlayerEntity.tickMovement} 与
     * {@code sendMovementPackets}（对比 lastSneaking 发 PRESS/RELEASE_SHIFT_KEY），
     * 全链路正确，且不受无障碍选项影响。</p>
     */
    public boolean wantSneak() {
        if (!cfg.enabled || !state.connected) {
            return false;
        }
        return cfg.toggleSneak ? toggleSneakOn : isBoundHeld(Action.SNEAK);
    }

    /** 本 tick 疾跑开关是否打开（真正疾跑还要满足「在前进」）。 */
    public boolean sprintActive() {
        if (!cfg.enabled || !state.connected) {
            return false;
        }
        return cfg.toggleSprint ? toggleSprintOn : isBoundHeld(Action.SPRINT);
    }

    /** 上一 tick 是否由我们把 {@code Input.sneaking} 强制成 true。 */
    private boolean sneakForced;

    /** 供 mixin 判断是否需要「收回」我们强制的潜行（false 时由原版值说话）。 */
    public boolean sneakForced() {
        return sneakForced;
    }

    public void setSneakForced(boolean value) {
        sneakForced = value;
    }

    /**
     * 疾跑接管：vanilla 的疾跑判定要求 {@code options.sprintKey.isPressed()},
     * 而 {@code sprintKey} 同样是 StickyKeyBinding（同一个坑）。所以这里直接写
     * {@code setSprinting}。vanilla 在 {@code tickMovement} 后段仍会检查
     * 「没有前进输入 / 不能疾跑」并自动停跑，因此只需负责「开」和「关」两个方向。
     */
    public void applySprintState(ClientPlayerEntity player, boolean movingForward) {
        if (player == null) {
            return;
        }
        boolean want = sprintActive();
        if (want && movingForward) {
            if (!player.isSprinting()) {
                player.setSprinting(true);
            }
            sprintApplied = true;
        } else if (!want && sprintApplied) {
            // 只在「是我们开的疾跑」时才收：不干扰键盘/双击 W 等原生疾跑。
            player.setSprinting(false);
            sprintApplied = false;
        }
    }

    /**
     * 用户点了「重新检测」：丢掉 SDL 的初始化结论，关掉当前手柄，下一 tick 重走一遍。
     *
     * <p>真机上常见的失败是时序问题（比如手柄比游戏启动得晚、FCL 侧加载 SDL2 慢了一步），
     * 这种情况下重试一次就好了 —— 但 {@code ensureReady()} 是幂等的，不主动清状态就要重启游戏。</p>
     */
    public void redetect() {
        releaseKeys(MinecraftClient.getInstance());
        sneakForced = false;
        sprintApplied = false;
        poller.requestRedetect();
        state.reset();
        held.clear();
        prevHeld.clear();
        applied.clear();
        clearDebounce();
        toggleSneakOn = false;
        toggleSprintOn = false;
        lastDeviceEpoch = -1;
    }

    public boolean isDriving(MinecraftClient client) {
        // cfg.enabled 必须一起判：停用后我们仍然在每 tick 读手柄（不然无法用 L3+R3 重新打开），
        // 但绝不能再用这些读数去驱动移动/视角。
        return cfg.enabled && state.connected && client != null && client.player != null
                && client.currentScreen == null;
    }

    // ------------------------------------------------------------------ 每 tick

    /**
     * 每个客户端 tick 末尾调用：抓一帧 SDL 状态，然后把它翻译成按键/热键。
     */
    public void endTick(MinecraftClient client) {
        if (shutdown) {
            return;
        }
        long perfTick = Perf.begin();
        try {
            endTick0(client);
        } finally {
            Perf.end(Perf.TICK, perfTick);
            Perf.tick();
        }
    }

    private void endTick0(MinecraftClient client) {
        if (shutdown) {
            return;
        }
        if (!Sdl3.isReady() && !Sdl3.ensureReady()) {
            // 初始化（dlopen + SDL_InitSubSystem）留在这里做，是一次性的、且 FCL 的
            // SDL 代理会在 InitSubSystem 里回调 ART 侧（notifyLauncher → setupJNI /
            // SDLSurface 重新协商），那条路径只验证过在客户端线程上走。
            // 真正每 tick 会卡的是「读轴读键」，那部分已经搬到轮询线程了。
            // 进世界后的稳态里 isReady() 恒为 true，本分支不会触发。
            // 必须主动释放按键，否则「按住跳跃时关掉开关」会让 Minecraft 以为键一直按着。
            releaseKeys(client);
            state.reset();
            held.clear();
            wasInGame = false;
            lookX = 0.0;
            lookY = 0.0;
            return;
        }
        long p = Perf.begin();
        syncFromPoller(client);
        Perf.end(Perf.PUMP, p);

        p = Perf.begin();
        readState();
        Perf.end(Perf.READ, p);
        // 组合键在任何状态下都要检测：停用时也得能用它重新打开（安卓上没有键盘可按 F8）。
        p = Perf.begin();
        updateToggleCombo(client);
        Perf.end(Perf.TOGGLE, p);

        // 径向动作轮盘：按住 radialKey（默认 BACK）弹出，右摇杆选方向，松手执行。
        p = Perf.begin();
        radialSelected = RadialMenu.tick(cfg, state.connected && radialHeld(),
                liveStick(cfg.axisRX), liveStick(cfg.axisRY));
        Perf.end(Perf.RADIAL, p);

        // GUI 光标要在界面打开时持续驱动 —— 哪怕 mc.enabled 被关了也一样吗？不：
        // 总开关关了就彻底停手，避免「关了开关还有半个模组活着」的困惑。
        if (cfg.enabled && state.connected) {
            p = Perf.begin();
            tickGuiCursor(client);
            Perf.end(Perf.GUI, p);
            tickRumble(client);
        } else {
            cursorVisible = false;
            lastCursorFrameNanos = 0L;
            rumbleTicks = 0;
        }

        if (cfg.enabled) {
            p = Perf.begin();
            applyActions(client);
            Perf.end(Perf.APPLY, p);
            p = Perf.begin();
            updateRumbleTriggers(client);
            Perf.end(Perf.TRIG, p);
        } else {
            // 关闭开关时也要放开按键：否则「按住攻击时关掉开关」会让攻击一直卡在按下态。
            releaseKeys(client);
            held.clear();
            lookX = 0.0;
            lookY = 0.0;
            wasInGame = false;
        }

        if (cfg.debug && (++debugTicks % 20) == 0) {
            FourYCtrl.LOG.info("[debug] {} axes=({}, {}, {}, {}) trig=({}, {}) btn={}",
                    state.connected ? state.name : "<未连接>",
                    fmt(state.leftX), fmt(state.leftY), fmt(state.rightX), fmt(state.rightY),
                    fmt(state.leftTrigger), fmt(state.rightTrigger),
                    pressedSummary());
        }

        if (radialPlayerListTicks > 0 && --radialPlayerListTicks == 0 && client.options != null) {
            KeyBinding.setKeyPressed(keyOf(client.options.playerListKey), false);
        }

        prev.copyFrom(state);
        prevHeld.clear();
        prevHeld.addAll(held);
        held.clear();
    }

    /** 轮盘触发键是否按着（配置里 {{@code radialKey}} 对应的 SDL 按钮名）。 */
    private boolean radialHeld() {
        if (!cfg.radialMenu || cfg.radialKey == null) {
            return false;
        }
        Button b = Button.byId(cfg.radialKey);
        return b != null && state.isDown(b);
    }

    /** 把我们按下过的键全部放开（不碰摇杆状态，停用时仍要继续读手柄）。 */
    private void releaseKeys(MinecraftClient client) {
        if (client == null) {
            return;
        }
        // toggle 状态一并清掉：停用/断开时清，重新启用后从「站直、不疾跑」开始，
        // 否则「潜行开着时停用模组，重新启用后人还隐着」而且玩家不知道开关在哪。
        toggleSneakOn = false;
        toggleSprintOn = false;
        // 早退条件不能只看 applied：keyOf() 可能在按下那一刻因界面类型而返回 null
        // （那时没写进 applied），但之后环境变了对同一个 key 反而能解析出来 ——
        // 早退会让这个键永远卡在按下态。所以只要持有过任何键就全量扫一遍。
        boolean listPressed = client.options != null && client.options.playerListKey != null
                && client.options.playerListKey.isPressed();
        if (prevHeld.isEmpty() && applied.isEmpty() && held.isEmpty()
                && keyBindApplied.isEmpty()
                && radialPlayerListTicks <= 0 && !listPressed) {
            return;
        }
        for (Action a : Action.values()) {
            KeyBinding kb = keyOf(client, a);
            if (kb != null) {
                KeyBinding.setKeyPressed(keyOf(kb), false);
            }
        }
        // 玩家列表（Tab）是 applyPlayerList 自己置的 pressed，不进 applied 集合；
        // 不在这里单独抬一下的话，「按着查看玩家列表时停用模组/拔手柄」会让列表一直挂着。
        if (client.options != null && client.options.playerListKey != null) {
            KeyBinding.setKeyPressed(keyOf(client.options.playerListKey), false);
        }
        radialPlayerListTicks = 0;
        // 转发出去的键盘键（别的模组的 KeyBinding）也要抬，否则停用模组/拔手柄/打字时
        // 那个键会一直是按下态，被绑定的功能会卡住。
        releaseKeyBindings();
        prevHeld.clear();
        applied.clear();
        held.clear();
    }

    /**
     * L3+R3 长按切换总开关。
     *
     * <p>安卓上没有键盘，F8/F9 实际按不到，所以手柄自己必须有一条重新开关的路。</p>
     */
    private void updateToggleCombo(MinecraftClient client) {
        boolean combo = state.isDown(Button.LEFT_STICK) && state.isDown(Button.RIGHT_STICK);
        if (!combo) {
            comboHold = 0;
            comboFired = false;
            return;
        }
        if (comboFired) {
            return;
        }
        if (++comboHold >= COMBO_TICKS) {
            comboFired = true;
            setEnabled(client, !cfg.enabled);
        }
    }

    /** 切换并持久化总开关；同时给玩家一条动作栏提示。 */
    public void setEnabled(MinecraftClient client, boolean value) {
        if (cfg.enabled == value) {
            return;
        }
        cfg.enabled = value;
        cfg.save();
        FourYCtrl.LOG.info("4YCtrl 已{}", value ? "启用" : "停用");
        Notify.actionBar(client, value ? "fouryctrl.msg.enabled" : "fouryctrl.msg.disabled");
    }

    /**
     * 把轮询线程发布的最新快照同步到客户端线程。
     *
     * <p>这里只做一次数组拷贝，<b>不发任何 SDL 调用</b> —— 手柄相关的 JNI 全在
     * {@link GamepadPoller} 里跑。这是「插手柄进世界后锁 10 帧」的根因修复：
     * SDL 的所有 joystick API 共用一把锁，FCL 的 Android 后端从 ART 侧写状态，
     * 那把锁被长时间持有时，客户端线程的每一次读轴都会卡住整帧。</p>
     *
     * <p>顺便处理「设备变了」的一次性副作用（打日志、套设备配置档、复位校准、断开抬键），
     * 用 {@code deviceEpoch} 判断，不会每 tick 重复。</p>
     */
    private void syncFromPoller(MinecraftClient client) {
        snapshotConnected = poller.snapshot(tickAxes, tickButtons);
        snapshotAxisCount = snapshotConnected ? poller.axisCount() : 0;
        int epoch = poller.deviceEpoch();
        if (epoch != lastDeviceEpoch) {
            lastDeviceEpoch = epoch;
            onDeviceChanged(client, snapshotConnected);
        }
    }

    /** 设备连接状态或具体设备变了：做一次性的日志/配置档/复位工作。 */
    private void onDeviceChanged(MinecraftClient client, boolean connected) {
        if (connected) {
            pollerWasConnected = true;
            String name = deviceName();
            String label = deviceLabel();
            FourYCtrl.LOG.info("已连接手柄 #{}: {}", poller.deviceId(), name);
            // 每只设备各自一套轴序号与绑定：手机场景下常插着两只不同型号的手柄，
            // 它们的轴顺序完全可能不一样（比如扳机排在 4/5 还是 6/7）。
            if (cfg.applyDeviceProfile(name)) {
                FourYCtrl.LOG.info("已套用设备配置档：{}", name);
                bindHash = 0x7FFFFFFF; // 强制下一 tick 重编译绑定表
            }
            logCapabilities();
            int power = poller.power();
            Notify.actionBar(client, "fouryctrl.msg.connected",
                    label + (power >= 0 ? " (" + power + "%)" : ""));
            // 换了设备：轴序号可能完全不同，旧的校准终点/死区中点全部作废。
            calibDone = false;
            calibTicks = 0;
            Arrays.fill(axisCenter, 0f);
            return;
        }
        if (!pollerWasConnected) {
            return;
        }
        pollerWasConnected = false;
        FourYCtrl.LOG.info("手柄已断开");
        Notify.actionBar(client, "fouryctrl.msg.disconnected");
        // 断开时先把我们按下的键全部抬起来，再清状态。
        // 旧代码只做 applied.clear()：如果拔手柄时正按着跳跃/攻击/使用，
        // 那些 KeyBinding 会永远停在 pressed=true（applied 被清掉后没人再释放它们），
        // 玩家会「一直跳 / 一直挥砍」直到打开某个界面触发 unpressAll。
        releaseKeys(client);
        toggleSneakOn = false;
        toggleSprintOn = false;
        clearDebounce();
        cursorActive = false;
        cursorVisible = false;
        calibDone = false;
        calibTicks = 0;
        // 轮盘也要复位：否则按住 BACK 时拔掉手柄，环会一直挂在屏幕中间
        // （tick 里 held 恒为 false，但 open 状态没人去关）。
        RadialMenu.reset();
    }

    /**
     * 在多手柄之间轮换（GUIDE + 十字键左右，或设置界面里的按钮）。
     *
     * <p>实际打开动作交给轮询线程，这里只投一个请求 —— 否则切换手柄的那一下
     * 又要让客户端线程去碰 SDL 的 joystick 锁。</p>
     *
     * @param dir 轮换方向：正数 = 下一个，负数 = 上一个
     */
    public boolean cycleJoystick(MinecraftClient client, int dir) {
        int[] ids = poller.ids();
        if (ids.length <= 1) {
            Notify.actionBar(client, "fouryctrl.msg.only_one_pad");
            return false;
        }
        poller.requestCycle(dir);
        return true;
    }

    /**
     * 连上手柄时把能力（轴数/键数/帽数）打一次日志。
     *
     * <p>SDL 的 Android 驱动会把 MotionRange 按 {@code RangeComparator} 重排
     * （X, Y, RX, RY, Z, RZ …）之后才编号，所以「第 4 个轴到底是不是左扳机」不能靠猜，
     * 设备间顺序也不一样（有些手柄的扳机是 LTRIGGER/RTRIGGER，会排到 6、7）。
     * 这份日志就是给玩家对照 config/fouryctrl.json 里的 axisLT / axisRT 用的。</p>
     *
     * <p>注意：这里读的是轮询线程已发布的能力值，不再现问 SDL。</p>
     */
    private void logCapabilities() {
        int axes = poller.axisCount();
        int buttons = poller.buttonCount();
        int hats = poller.hatCount();
        FourYCtrl.LOG.info("手柄能力：{} 个轴、{} 个按键、{} 个帽开关", axes, buttons, hats);
        if (cfg.axisLT >= axes || cfg.axisRT >= axes) {
            FourYCtrl.LOG.warn("配置里的扳机轴索引(axisLT={}, axisRT={})超出了本手柄的轴数({})，请到 config/fouryctrl.json 里改正",
                    cfg.axisLT, cfg.axisRT, axes);
        }
        if (cfg.debug) {
            float[] tmp = new float[axes];
            poller.snapshotAxes(tmp);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < axes; i++) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(i).append('=').append(fmt(tmp[i]));
            }
            FourYCtrl.LOG.info("[debug] 轴读数：{}", sb);
        }
    }

    private void readState() {
        if (!snapshotConnected) {
            // 断开时清掉轴读数，否则设置界面会一直显示上一台手柄的旧数值。
            if (rawAxes.length != 0) {
                rawAxes = new float[0];
            }
            // state 也要复位：否则 connected 会一直停在 true，
            // isDriving() 继续拿着上一帧的轴去驱动视角/移动。
            state.reset();
            return;
        }
        state.reset();
        state.connected = true;
        state.name = poller.name();

        // 先记下全部原始轴读数：设置界面靠它让玩家看着实时数值调死区，
        // 而不是「改个数字→关界面→推摇杆猜效果」。
        // 这些数据全部来自轮询线程的快照，本方法内没有任何 SDL 调用。
        int axes = snapshotAxisCount;
        if (axes < 0) {
            axes = 0;
        }
        if (axes > MAX_AXES) {
            axes = MAX_AXES;
        }
        if (rawAxes.length != axes) {
            rawAxes = new float[axes];
        }
        System.arraycopy(tickAxes, 0, rawAxes, 0, axes);

        // 轴自动识别 + 中心校准：连上手柄后先采样 1.5 秒，再据静止位置判断
        // 哪些是摇杆（约在 0）、哪些是扳机（静止在 -1）。
        if (cfg.axisAutoDetect && !calibDone) {
            sampleAxes(axes);
        }

        float lx = axisCalibrated(cfg.axisLX);
        float ly = axisCalibrated(cfg.axisLY);
        float lm = (float) Math.sqrt(lx * lx + ly * ly);
        float[] l = radial(lx, ly, lm, cfg.leftDeadzone);
        state.leftX = l[0];
        state.leftY = l[1];

        float rx = axisCalibrated(cfg.axisRX);
        float ry = axisCalibrated(cfg.axisRY);
        float rm = (float) Math.sqrt(rx * rx + ry * ry);
        float[] r = radial(rx, ry, rm, cfg.rightDeadzone);
        state.rightX = r[0];
        state.rightY = r[1];

        state.leftTrigger = trigger(axisCalibrated(cfg.axisLT), cfg.triggerDeadzone);
        state.rightTrigger = trigger(axisCalibrated(cfg.axisRT), cfg.triggerDeadzone);

        // 按键直接取快照（hat 回落已在轮询线程里做完）。
        for (Button b : Button.ALL) {
            state.buttons[b.index] = tickButtons[b.index];
        }

        // 按钮去抖（非对称）：SDL 在 Android 上偶发同一次按压的 down→up→down 抖动，
        // 没有这层过滤时 toggle 动作（潜行/疾跑）一次按压会被当成两次按下沿
        // （开→关→开，表现是「站起来又立即潜行」）。
        //
        // 以前两个方向都要求连续 2 tick 才更新，代价是「按下」也要等 100ms ——
        // 跳跃/攻击这种手感要求高的键能明显感觉到延迟。现在改成：
        //   按下：立即生效（玩家不会误报按下，抖动的只有松开）；
        //   松开：连续 DEBOUNCE_TICKS 个 tick 都读到松开才认。
        for (Button b : Button.ALL) {
            int i = b.index;
            boolean raw = state.buttons[i];
            if (raw) {
                buttonStable[i] = true;
                buttonReleaseTicks[i] = 0;
            } else if (buttonStable[i]) {
                if (++buttonReleaseTicks[i] >= DEBOUNCE_TICKS) {
                    buttonStable[i] = false;
                    buttonReleaseTicks[i] = 0;
                }
            } else {
                buttonReleaseTicks[i] = 0;
            }
        }
        for (Button b : Button.ALL) {
            state.buttons[b.index] = buttonStable[b.index];
        }
    }

    /**
     * 轴采样：记录每个轴的区间与静止值。
     *
     * <p>判定规则（{@link #finishCalibration()}）：</p>
     * <ul>
     *   <li>静止值接近 -1（&lt; -0.5）且正向行程大 ⇒ 扳机；</li>
     *   <li>静止值接近 0 且两个方向都有行程 ⇒ 摇杆。</li>
     * </ul>
     * <p>只有在「找到 2 个摇杆 + 2 个扳机且互不重叠」时才覆盖配置，
     * 否则保留用户/默认值 —— 识别错误比不识别更糟。</p>
     */
    private void sampleAxes(int axes) {
        int n = Math.min(axes, calibMin.length);
        if (calibTicks == 0) {
            for (int i = 0; i < n; i++) {
                calibMin[i] = rawAxes[i];
                calibMax[i] = rawAxes[i];
                calibRest[i] = rawAxes[i];
            }
            calibAxes = n;
        } else if (n != calibAxes) {
            // 轴数变了（换了手柄）：重新开始采样。
            calibTicks = 0;
            return;
        } else {
            for (int i = 0; i < n; i++) {
                calibMin[i] = Math.min(calibMin[i], rawAxes[i]);
                calibMax[i] = Math.max(calibMax[i], rawAxes[i]);
                // 静止值用滑动平均，避免采样期间玩家动了一下就定死。
                calibRest[i] += (rawAxes[i] - calibRest[i]) * 0.2f;
            }
        }
        if (++calibTicks >= CALIB_TICKS) {
            calibDone = true;
            finishCalibration();
        }
    }

    /** 采样结束：判定哪些轴是摇杆/扳机，并记录摇杆中点。 */
    private void finishCalibration() {
        int n = calibAxes;
        int[] sticks = new int[4];
        int stickCount = 0;
        int[] trigs = new int[2];
        int trigCount = 0;
        for (int i = 0; i < n; i++) {
            float rest = calibRest[i];
            float range = calibMax[i] - calibMin[i];
            if (rest < -0.5f && range > 0.3f) {
                if (trigCount < trigs.length) {
                    trigs[trigCount++] = i;
                }
            } else if (Math.abs(rest) <= 0.35f) {
                if (stickCount < sticks.length) {
                    sticks[stickCount++] = i;
                }
            }
        }
        if (stickCount >= 4 && trigCount >= 2) {
            // SDL 的 Android 驱动按 X, Y, RX, RY 排序上报，采样顺序也一致。
            cfg.axisLX = sticks[0];
            cfg.axisLY = sticks[1];
            cfg.axisRX = sticks[2];
            cfg.axisRY = sticks[3];
            cfg.axisLT = trigs[0];
            cfg.axisRT = trigs[1];
            Arrays.fill(axisCenter, 0f);
            for (int i = 0; i < n; i++) {
                if (Math.abs(calibRest[i]) <= 0.35f) {
                    axisCenter[i] = calibRest[i];
                }
            }
            cfg.save();
            FourYCtrl.LOG.info("已自动识别轴位：LX={} LY={} RX={} RY={} LT={} RT={}",
                    cfg.axisLX, cfg.axisLY, cfg.axisRX, cfg.axisRY, cfg.axisLT, cfg.axisRT);
        } else {
            FourYCtrl.LOG.info("轴位识别不自信（找到 {} 个摇杆候选、{} 个扳机候选），保留现有配置",
                    stickCount, trigCount);
        }
    }

    /** 读一个轴并减掉校准出来的中点（抵消摇杆漂移）。 */
    private float axisCalibrated(int index) {
        if (index < 0) {
            return 0f;
        }
        float v;
        if (index < rawAxes.length) {
            v = rawAxes[index];
        } else {
            // 越界（配置里的轴号对本手柄不存在）：当 0 处理，绝不现问 SDL。
            v = 0f;
        }
        if (index < axisCenter.length) {
            v -= axisCenter[index];
        }
        if (v > 1f) {
            v = 1f;
        } else if (v < -1f) {
            v = -1f;
        }
        return v;
    }

    /**
     * 径向死区 + 重新归一化，避免死区边缘出现「一推就跳到 0.15」的台阶。
     *
     * <p>{@code mag} 由调用方传进来（它已经算过一次），并且这里不再分配数组 ——
     * 这个方法每 tick 被调两次，热路径上不产生垃圾。</p>
     */
    private float[] radial(float x, float y, float mag, float deadzone) {
        if (mag <= deadzone || mag <= 1.0e-4f) {
            radialOut[0] = 0f;
            radialOut[1] = 0f;
            return radialOut;
        }
        // deadzone==1 时下面会 0/0，得到一个 NaN 再去驱动光标，表现为光标瞬移/乱飞。
        // 死区顶到 1 = 完全不用这根摇杆，直接当无输入。
        if (deadzone >= 1f) {
            radialOut[0] = 0f;
            radialOut[1] = 0f;
            return radialOut;
        }
        // 先归一化到「死区之后、满推之前」的 0..1，再套响应曲线，最后还原成方向向量。
        float norm = Math.min(1f, (mag - deadzone) / (1f - deadzone));
        if (cfg.stickCurve != 1.0f) {
            norm = (float) Math.pow(norm, cfg.stickCurve);
        }
        float scaled = norm / mag;
        radialOut[0] = x * scaled;
        radialOut[1] = y * scaled;
        return radialOut;
    }

    /** radial() 复用的输出数组，避免每 tick 两次分配。 */
    private final float[] radialOut = new float[2];

    /**
     * Android 后端把扳机上报成 -1..1（松开是 -1），这里换算成 0..1 并套死区。
     */
    private static float trigger(float raw, float deadzone) {
        float v = (raw + 1f) * 0.5f;
        if (v <= deadzone) {
            return 0f;
        }
        return Math.min(1f, (v - deadzone) / (1f - deadzone));
    }

    // ------------------------------------------------------------------ 动作分派

    /**
     * 把 {@code cfg.bindings}（String→String）编译成 {@link Button}→{@link Action}。
     *
     * <p>只在绑定表内容变化时重编译（用 hashCode + size 做特征值探测：
     * 设置界面改绑定时 {@code cfg.bindings} 会被就地替换，hash 随之改变）。
     * 每 tick 编译一整个 EnumMap 是没必要的开销。</p>
     */
    private void refreshBindings() {
        if (cfg.bindings == null) {
            if (!bind.isEmpty()) {
                bind.clear();
            }
            return;
        }
        int hash = cfg.bindings.hashCode();
        if (hash == bindHash) {
            return;
        }
        bindHash = hash;
        bind.clear();
        for (Map.Entry<String, String> e : cfg.bindings.entrySet()) {
            Button b = Button.byId(e.getKey());
            if (b == null) {
                continue;
            }
            Action a = Action.byId(e.getValue());
            if (a != Action.NONE) {
                bind.put(b, a);
            }
        }
    }

    /**
     * 编译 {@code cfg.keyBindings}（按钮 id → InputUtil 翻译键，如 {@code key.keyboard.f}）。
     *
     * <p>只在内容变化时重编译。解析失败（手改 JSON 写错、或者键名不属于当前映射）的条目
     * 直接丢弃 —— 宁可这一条不生效，也不要每 tick 抛异常。</p>
     */
    private void refreshKeyBindings() {
        if (cfg.keyBindings == null) {
            releaseKeyBindings();
            if (!keyBind.isEmpty()) {
                keyBind.clear();
            }
            return;
        }
        int hash = cfg.keyBindings.hashCode();
        if (hash == keyBindHash) {
            return;
        }
        keyBindHash = hash;
        // 换表前把旧键全抬起：否则改了键，旧键会永远停在按下态。
        releaseKeyBindings();
        keyBind.clear();
        for (Map.Entry<String, String> e : cfg.keyBindings.entrySet()) {
            Button b = Button.byId(e.getKey());
            if (b == null) {
                continue;
            }
            String id = e.getValue();
            if (id == null || id.isBlank()) {
                continue;
            }
            InputUtil.Key key;
            try {
                key = InputUtil.fromTranslationKey(id);
            } catch (Throwable t) {
                continue;
            }
            if (key == null || key == InputUtil.UNKNOWN_KEY) {
                continue;
            }
            keyBind.put(b, key);
        }
    }

    /**
     * 把绑定的键盘键按下去 / 松开。
     *
     * <p>只在游戏内（无界面）转发：界面里按钮已经另有语义（选格子 / 微调光标），
     * 再同时往外发键盘事件会让玩家开着背包就触发别的模组功能。</p>
     */
    private void dispatchKeyBindings(boolean inGame) {
        if (keyBind.isEmpty()) {
            releaseKeyBindings();
            return;
        }
        for (Map.Entry<Button, InputUtil.Key> e : keyBind.entrySet()) {
            Button b = e.getKey();
            InputUtil.Key key = e.getValue();
            if (key == null) {
                continue;
            }
            boolean want = inGame && state.isDown(b);
            boolean was = keyBindApplied.containsKey(b);
            if (want && !was) {
                // 和 applyHeld 一样：一次调用里既置按下态（持续型需求）又投递一次
                // 按下沿（timesPressed++，wasPressed() 型需求）。
                KeyBinding.setKeyPressed(key, true);
                KeyBinding.onKeyPressed(key);
                keyBindApplied.put(b, key);
            } else if (!want && was) {
                KeyBinding.setKeyPressed(key, false);
                keyBindApplied.remove(b);
            }
        }
    }

    /** 抬起所有由我们转发出去的键盘键。 */
    private void releaseKeyBindings() {
        if (keyBindApplied.isEmpty()) {
            return;
        }
        for (InputUtil.Key key : keyBindApplied.values()) {
            if (key != null) {
                KeyBinding.setKeyPressed(key, false);
            }
        }
        keyBindApplied.clear();
    }

    private void applyActions(MinecraftClient client) {
        held.clear();

        // 先把物理按键翻译成动作表 —— 即使 player 暂时为 null 也要做。
        // 换维度/重生/death 那一两 tick 里 client.player 会是 null，若在这里直接 return，
        // endTick 会用空的 held 覆盖 prevHeld，下一 tick 所有按着的键都变成「新按下」，
        // toggle 潜行/疾跑会被凭空翻转一次（人突然起立 / 突然疾跑）。
        refreshBindings();
        refreshKeyBindings();
        for (Map.Entry<Button, Action> e : bind.entrySet()) {
            if (state.isDown(e.getKey())) {
                held.add(e.getValue());
            }
        }
        // 扳机当作模拟按钮：过死区就算按下
        if (state.leftTrigger > 0f) {
            Action a = Action.byId(cfg.triggerLeftAction);
            if (a != Action.NONE) {
                held.add(a);
            }
        }
        if (state.rightTrigger > 0f) {
            Action a = Action.byId(cfg.triggerRightAction);
            if (a != Action.NONE) {
                held.add(a);
            }
        }

        if (client.player == null) {
            wasInGame = false;
            // 世界还在加载时不会有界面事件，但我们上一 tick 可能正把某个键按着；
            // 不在这里抬，那个键会卡在按下态直到下一轮 rearm。
            releaseKeyBindings();
            return;
        }

        boolean inGame = client.currentScreen == null;

        // toggle 边沿检测：按钮按下那一 tick 翻转开关状态。
        // 必须在 rearm 计算之前做：rearm 会清 prevHeld，如果先 rearm 后检测，
        // 「按着潜行键开背包再关掉」会丢掉释放沿，开关就永远弹不回来了。
        updateToggles();

        // MinecraftClient.setScreen() 每次都会调 KeyBinding.unpressAll()，把我们用
        // setPressed 置上的键全部清掉；但 prevHeld 仍记着「按着」，边沿就不会再重放，
        // 表现为「按住前进时打开暂停菜单，关掉后不走了」。
        // 所以只要进出「无界面」状态，就把上一帧的按键快照清空，让持续动作重新触发。
        // 但边沿型动作不能跟着重放：否则「按着 Y 打开背包、松手关掉背包」会在关闭的
        // 那一 tick 又立刻打开背包，玩家会觉得按键粘住了。所以这一 tick 只放行持续动作。
        // 轮盘：开着的时候把所有常规动作让开（否则推右摇杆选菜单会顺手转视角/点界面），
        // 松手时执行选中的那一个。轮盘动作都是「一次性」语义，直接按边沿分发。
        if (radialSelected != Action.NONE) {
            Action a = radialSelected;
            radialSelected = Action.NONE;
            performRadial(client, a, inGame);
            prevHeld.clear();
            return;
        }
        if (RadialMenu.isOpen()) {
            prevHeld.clear();
            return;
        }

        boolean rearm = inGame != wasInGame;
        if (rearm) {
            // applied 也一并清：unpressAll 已把游戏侧 pressed 清掉，applied 若仍记着
            // 「按着」，want && !was 不成立、else if (was) 也不成立，自愈分支之后
            // 恢复不了按下的键（applyHeld 的 was 现在基于 applied）。清掉后下一
            // tick want && !was 重新触发，等效 rearm；同 tick 内 applyHeld 走
            // 自愈分支（kb.isPressed() 为 false 时重新置位），不丢「按住前进开菜单」。
            applied.clear();
            prevHeld.clear();
            // 玩家在「按键设置」界面里可能刚改过绑定；缓存是 KeyBinding→Key 的映射，
            // 改绑后不失效会把旧键当成新键发出去（改了跳跃键但模型还在按旧键）。
            // 进出界面正好是最廉价的失效时机。
            keyCache.clear();
            // 键盘键同样不能被「上一帧快照」卡住：setScreen 的 unpressAll 已经清掉了
            // pressed，这里把记录一并清空，回到游戏后 want && !was 会重新按下。
            // 先 release 再清：万一 rearm 不是经 setScreen 触发（例如换世界），
            // 也保证那个键真的被抬起来。
            releaseKeyBindings();
            wasInGame = inGame;
        }

        // 持续型动作：jump / attack / use；sneak / sprint 走下面的 toggle 或 hold 分支。
        // 注意：SNEAK / SPRINT 不在这里下发 KeyBinding —— 见 wantSneak() 的注释，
        // StickyKeyBinding 会吞掉 setPressed(false)。它们由 KeyboardInputMixin 直接写
        // Input.sneaking / player.setSprinting()。
        applyHeld(client, Action.JUMP, inGame);
        applyHeld(client, Action.ATTACK, inGame, held.contains(Action.ATTACK));
        applyHeld(client, Action.USE, inGame, held.contains(Action.USE));
        if (rearm) {
            return;
        }
        // 玩家自己绑定的「按钮 → 键盘按键」：只在游戏内转发，用来驱动别的模组。
        dispatchKeyBindings(inGame);
        // 一次性动作：打开背包 / 丢弃 / 交换副手
        applyTap(client, Action.INVENTORY, inGame);
        applyTap(client, Action.DROP, inGame);
        applyTap(client, Action.SWAP_OFFHAND, inGame);

        if (inGame && rising(Action.HOTBAR_PREV)) {
            shiftHotbar(client, -1);
        }
        if (inGame && rising(Action.HOTBAR_NEXT)) {
            shiftHotbar(client, +1);
        }
        if (rising(Action.PAUSE)) {
            togglePause(client);
        }
        // 十字键可绑定的一次性动作：聊天 / 视角切换 / 隐藏 HUD。
        // 这些在 MC 里都是 KeyBinding，但手柄侧没有真实的键盘事件可复用，
        // 直接调对应 API 更稳（尤其 HIDE_HUD 在 1.20.1 根本没有 hideHudKey）。
        if (inGame && rising(Action.CHAT)) {
            try {
                client.setScreen(Compat.chatScreen(""));
            } catch (Throwable t) {
                if (cfg.debug) {
                    FourYCtrl.LOG.debug("打开聊天失败：{}", t.toString());
                }
            }
        }
        if (inGame && rising(Action.PERSPECTIVE) && client.options != null) {
            client.options.setPerspective(client.options.getPerspective().next());
        }
        if (inGame && rising(Action.HIDE_HUD) && client.options != null) {
            client.options.hudHidden = !client.options.hudHidden;
        }
        // 玩家列表是「按住才显示」的语义，所以走 hold 而不是边沿。
        applyPlayerList(client, inGame);
    }

    /** 按住时把 playerListKey 置下，松开或离开游戏时抬起（列表随之消失）。 */
    private void applyPlayerList(MinecraftClient client, boolean inGame) {
        if (client.options == null) {
            return;
        }
        KeyBinding kb = client.options.playerListKey;
        if (kb == null) {
            return;
        }
        // 轮盘触发的一次性点亮：这期间禁止释放，否则下一 tick 就被下面这段收走，
        // 玩家列表只闪一帧（等于没触发）。
        if (radialPlayerListTicks > 0) {
            return;
        }
        boolean want = inGame && held.contains(Action.PLAYER_LIST);
        if (want) {
            if (!kb.isPressed()) {
                KeyBinding.setKeyPressed(keyOf(kb), true);
            }
        } else if (kb.isPressed()) {
            KeyBinding.setKeyPressed(keyOf(kb), false);
        }
    }

    /**
     * 切换式潜行/疾跑：按钮按下沿翻转开关状态。
     *
     * <p>为什么不用 KeyBinding 自带的 toggle 语义：那是「按住才切换」的组合规则，
     * 手柄玩家要的是「点一下 B 潜行、再点一下站起来」。开关状态存在这里而不是
     * 读 kb.isPressed()，是因为 unpressAll/rearm 都可能把 pressed 清掉，
     * 而开关语义必须不受那些清理影响。</p>
     */
    private void updateToggles() {
        if (cfg.toggleSneak && rising(Action.SNEAK)) {
            toggleSneakOn = !toggleSneakOn;
        }
        if (cfg.toggleSprint && rising(Action.SPRINT)) {
            toggleSprintOn = !toggleSprintOn;
        }
        // 按住型（toggle 关闭时）状态不在这里处理，applyActions 直接传 held.contains。
    }

    private boolean rising(Action a) {
        return held.contains(a) && !prevHeld.contains(a);
    }

    /** 清空按钮去抖状态（断开/重置时用，避免重连后残留旧按键）。 */
    private void clearDebounce() {
        java.util.Arrays.fill(buttonStable, false);
        java.util.Arrays.fill(buttonReleaseTicks, 0);
    }

    private void applyHeld(MinecraftClient client, Action action, boolean inGame) {
        applyHeld(client, action, inGame, held.contains(action));
    }

    private void applyHeld(MinecraftClient client, Action action, boolean inGame, boolean want) {
        KeyBinding kb = keyOf(client, action);
        if (kb == null) {
            return;
        }
        want = inGame && want;
        boolean was = applied.contains(action);
        InputUtil.Key key = keyOf(kb);
        if (want && !was) {
            // isPressed() 驱动「持续」，timesPressed 驱动「点按」的一次性分支。
            // 只在真正从未按下 → 按下的沿上触发一次；toggle 模式下开关状态由
            // toggleSneakOn/toggleSprintOn 维护，这里不会重复触发。
            KeyBinding.setKeyPressed(key, true);
            KeyBinding.onKeyPressed(key);
            applied.add(action);
        } else if (want) {
            // 自愈：MinecraftClient.setScreen() → unpressAll()、以及 Mouse.lockCursor() →
            // updatePressedStates() 都会把我们置上的 pressed 清成 false。重新置位即可，
            // 这样「按住跳跃切出去再切回来」不会变成按键失灵。
            if (!kb.isPressed()) {
                KeyBinding.setKeyPressed(key, true);
            }
        } else if (was) {
            KeyBinding.setKeyPressed(key, false);
            applied.remove(action);
        }
    }

    private void applyTap(MinecraftClient client, Action action, boolean inGame) {
        if (!inGame || !rising(action)) {
            return;
        }
        KeyBinding kb = keyOf(client, action);
        if (kb == null) {
            return;
        }
        // handleInputEvents 里这些动作只看 wasPressed()，也就是 timesPressed；
        // 所以只加计数、不置 pressed，避免「按住就反复打开背包」。
        KeyBinding.onKeyPressed(keyOf(kb));
    }

    private void shiftHotbar(MinecraftClient client, int delta) {
        if (client.player == null) {
            return;
        }
        int size = 9;
        int slot = Compat.selectedSlot(client.player.getInventory());
        Compat.setSelectedSlot(client.player.getInventory(), ((slot + delta) % size + size) % size);
    }

    private void togglePause(MinecraftClient client) {
        try {
            if (client.currentScreen == null) {
                Compat.openGameMenu(client, true);
            } else {
                client.currentScreen.close();
            }
        } catch (Throwable t) {
            if (cfg.debug) {
                FourYCtrl.LOG.debug("切换暂停菜单失败：{}", t.toString());
            }
        }
    }

    /**
     * 执行轮盘里选中的动作。
     *
     * <p>轮盘的槽位是「玩家随手塞的偶尔用一次的动作」，所以这里按动作语义分三类处理：
     * 开关类（视角/HUD）直接改状态；界面类（聊天）直接开屏；剩下的复用普通输入的
     * 分发路径（快捷栏直接改槽位、暂停直接开关菜单、背包/丢弃给按键计数）。</p>
     */
    private void performRadial(MinecraftClient client, Action action, boolean inGame) {
        switch (action) {
            case CHAT -> {
                if (inGame) {
                    try {
                        client.setScreen(Compat.chatScreen(""));
                    } catch (Throwable t) {
                        if (cfg.debug) {
                            FourYCtrl.LOG.debug("打开聊天失败：{}", t.toString());
                        }
                    }
                }
            }
            case PERSPECTIVE -> {
                if (inGame && client.options != null) {
                    client.options.setPerspective(client.options.getPerspective().next());
                }
            }
            case HIDE_HUD -> {
                if (inGame && client.options != null) {
                    client.options.hudHidden = !client.options.hudHidden;
                }
            }
            case PLAYER_LIST -> {
                // 玩家列表是「按住才显示」的，轮盘是一次性触发 —— 短暂点亮一下：
                // 把按键按下并在 20 tick 后由 updateToggles 之外的自愈逻辑放开。
                if (inGame) {
                    KeyBinding kb = keyOf(client, action);
                    if (kb != null) {
                        KeyBinding.setKeyPressed(keyOf(kb), true);
                        radialPlayerListTicks = 20;
                    }
                }
            }
            case HOTBAR_PREV -> shiftHotbar(client, -1);
            case HOTBAR_NEXT -> shiftHotbar(client, 1);
            case PAUSE -> togglePause(client);
            case INVENTORY, DROP, SWAP_OFFHAND -> {
                if (inGame) {
                    KeyBinding kb = keyOf(client, action);
                    if (kb != null) {
                        KeyBinding.onKeyPressed(keyOf(kb));
                    }
                }
            }
            default -> {
                // JUMP/SNEAK/SPRINT/ATTACK/USE 是持续型，放手就没了，放进轮盘没有意义；
                // 真被塞进来就忽略，避免「松手后凭空跳一下」。
            }
        }
    }

    private KeyBinding keyOf(MinecraftClient client, Action action) {
        if (client.options == null) {
            return null;
        }
        switch (action) {
            case JUMP:
                return client.options.jumpKey;
            case SNEAK:
                return client.options.sneakKey;
            case SPRINT:
                return client.options.sprintKey;
            case ATTACK:
                return client.options.attackKey;
            case USE:
                return client.options.useKey;
            case INVENTORY:
                return client.options.inventoryKey;
            case DROP:
                return client.options.dropKey;
            case SWAP_OFFHAND:
                return client.options.swapHandsKey;
            default:
                return null;
        }
    }

    /**
     * 拿到 KeyBinding 对应的 {@link InputUtil.Key}。
     *
     * <p>MC 1.20.1 的 KeyBinding 没有公开 {@code getBoundKey()}，只有
     * {@code getBoundKeyTranslationKey()}，所以用 {@code InputUtil.fromTranslationKey} 反查。
     * 反查结果按 KeyBinding 缓存，避免每 tick 都解析字符串。</p>
     */
    private InputUtil.Key keyOf(KeyBinding kb) {
        if (kb == null) {
            return InputUtil.UNKNOWN_KEY;
        }
        InputUtil.Key cached = keyCache.get(kb);
        if (cached != null) {
            return cached;
        }
        InputUtil.Key resolved;
        try {
            resolved = InputUtil.fromTranslationKey(kb.getBoundKeyTranslationKey());
        } catch (Throwable t) {
            resolved = InputUtil.UNKNOWN_KEY;
        }
        keyCache.put(kb, resolved);
        return resolved;
    }

    // ------------------------------------------------------------------ 供 Mixin 使用

    /**
     * 计算这一帧应该注入的鼠标位移（单位：鼠标像素，交给 MC 自己乘灵敏度曲线）。
     *
     * <p>按真实经过时间算，而不是按 tick：{@code Mouse.updateMouse()} 是每帧调一次，
     * 帧率越高调用越多；若按 tick 的固定 50ms 算，高帧率下视角会被放大好几倍。</p>
     */
    public void computeLook(MinecraftClient client) {
        lookX = 0.0;
        lookY = 0.0;

        long now = System.nanoTime();
        double seconds = lastLookNanos == 0L ? 0.0 : (now - lastLookNanos) / 1.0e9;
        lastLookNanos = now;
        if (seconds > 0.1) {
            seconds = 0.1;
        }
        if (seconds <= 0.0) {
            return;
        }
        if (!isDriving(client)) {
            return;
        }

        // 每帧现读右摇杆，而不是用 20Hz 的 tick 采样值 state.rightX/Y。
        // 采样值最多滞后 50ms，快速甩视角时表现为「起步一顿、停手还在滑」。
        // 每帧只多两次 JNI（SDL_GetJoystickAxis），代价可以忽略。
        float rx = liveStick(cfg.axisRX);
        float ry = liveStick(cfg.axisRY);
        float mag = (float) Math.sqrt(rx * rx + ry * ry);
        if (mag <= cfg.rightDeadzone) {
            return;
        }
        float[] r = radial(rx, ry, mag, cfg.rightDeadzone);
        double nx = r[0];
        double ny = r[1];
        // 视角加速度：轻推细腻、推满更快。指数 1 = 线性（等速）。
        if (cfg.lookAcceleration != 1.0f) {
            double nm = Math.sqrt(nx * nx + ny * ny);
            if (nm > 1.0e-4) {
                double factor = Math.pow(nm, cfg.lookAcceleration - 1.0);
                nx *= factor;
                ny *= factor;
            }
        }

        // Mouse.updateMouse 内部：普通分支 h = f^3 * 8（f = sens*0.6+0.2），
        // 最后 changeLookDirection 再乘 0.15。所以要得到 deg 度，
        // cursorDelta = deg / (f^3 * 8 * 0.15)。
        // 望远镜（第一人称持望远镜）分支只用 g = f^3，少了那个 8，
        // 不区分的话望远镜下视角会被放大 8 倍。
        double sens = client.options.getMouseSensitivity().getValue();
        double f = sens * 0.6 + 0.2;
        double f3 = f * f * f;
        boolean spyglass = client.player != null
                && client.player.isUsingSpyglass()
                && client.options.getPerspective().isFirstPerson();
        double degPerPixel = (spyglass ? f3 : f3 * 8.0) * 0.15;

        double degX = nx * cfg.lookSpeed * seconds;
        double degY = ny * cfg.lookSpeed * seconds;
        if (cfg.invertLookY) {
            degY = -degY;
        }
        lookX = degX / degPerPixel;
        lookY = degY / degPerPixel;
    }

    /**
     * 取本帧的某个轴读数（减掉校准中点、夹紧到 ±1）。
     *
     * <p>数据来自 {@link #beginFrame()} 每帧拷进 {@link #frameAxes} 的快照 ——
     * 快照由 {@link GamepadPoller} 线程以约 6ms 的间隔刷新，比一帧还细，
     * 所以手感与「每帧现读」几乎无差别，但客户端线程一次 JNI 都不发。</p>
     */
    private float liveStick(int index) {
        if (!cfg.liveAxis || index < 0 || index >= frameAxisCount) {
            return tickStick(index);
        }
        float v = frameAxes[index];
        if (index < axisCenter.length) {
            v -= axisCenter[index];
        }
        if (v > 1f) {
            return 1f;
        }
        if (v < -1f) {
            return -1f;
        }
        return v;
    }

    /** 从本 tick 已同步好的 {@link #rawAxes} 里取一个轴（同样不产生 JNI）。 */
    private float tickStick(int index) {
        if (index < 0 || index >= rawAxes.length) {
            return 0f;
        }
        float v = rawAxes[index];
        if (index < axisCenter.length) {
            v -= axisCenter[index];
        }
        if (v > 1f) {
            return 1f;
        } else if (v < -1f) {
            return -1f;
        }
        return v;
    }

    /**
     * 由 {@code Mouse.updateMouse} 的 HEAD 每帧调一次：把轮询线程的最新轴快照
     * 拷进 {@link #frameAxes}，供同帧内的 {@code computeLook} 与 {@code frameCursor} 共用。
     *
     * <p>只做一次数组拷贝（最多几十个 float），没有任何 SDL 调用。</p>
     */
    public void beginFrame() {
        if (!cfg.liveAxis) {
            // 关掉「高频轴」时退回 tick 采样：视角最多滞后 50ms，但连这次数组拷贝也省了。
            frameAxisCount = 0;
            return;
        }
        frameAxisCount = poller.snapshotAxes(frameAxes);
    }

    public double lookX() {
        return lookX;
    }

    public double lookY() {
        return lookY;
    }

    /** 模拟移动量的前后分量（+1 = 向前）。 */
    public float moveForward() {
        return -state.leftY * cfg.moveSpeedScale;
    }

    /** 模拟移动量的左右分量（+1 = 向右）。 */
    public float moveSideways() {
        return state.leftX * cfg.moveSpeedScale;
    }

    // ------------------------------------------------------------------ GUI 光标

    /**
     * 右摇杆模拟鼠标光标 + 攻击键/使用键模拟左右键。
     *
     * <p>实现方式是「直接挪 {@code Mouse.x/y} + 调 {@code Screen.mouseClicked/mouseReleased}」
     * 而不是去 hack GLFW 回调：FCL 的 GLFW 是 stub，回调链不完整；而 Screen 的这些方法
     * 正是真实鼠标事件最终汇入的地方，坐标体系（缩放后 GUI 坐标）也一致。</p>
     *
     * <p>只模拟「点按」不模拟拖拽：拖拽需要 dragging 状态与每帧 mouseDragged 转发，
     * 而摇杆手感下拖拽（如拖动滑块）很少用，先用点按覆盖 95% 的操作。
     * 滑块类控件用点按也能跳转到点击位置（SliderWidget.onClick 会 setValueFromMouse）。</p>
     */
    private void tickGuiCursor(MinecraftClient client) {
        if (!cfg.guiCursor) {
            cursorVisible = false;
            return;
        }
        boolean inScreen = client.currentScreen != null;
        if (!inScreen) {
            // 回到游戏后光标隐藏，下次进界面重新从中心开始。
            cursorVisible = false;
            cursorActive = false;
            lastCursorFrameNanos = 0L;
            return;
        }

        if (!cursorActive) {
            // 第一次进入界面：把光标放在屏幕中心。
            cursorActive = true;
            cursorX = client.getWindow().getScaledWidth() / 2.0;
            cursorY = client.getWindow().getScaledHeight() / 2.0;
        }
        // 只要在界面里就让十字光标可见：以前要摇杆推动才显示，结果「没推摇杆就按 A」
        // 会在看不见的位置点一下，非常困惑。现在光标一进界面就画出来（十字架形状），
        // 「看得见才能瞄」依然成立，但不推摇杆也能直接点。
        cursorVisible = true;

        // 界面切换：进容器就把光标吸到格子上，省得玩家先摸索着推摇杆。
        //
        // 但同类容器之间切换（背包→箱子→背包这类连续开合，以及配方书开合导致的
        // 屏幕对象重建）不应该把光标重置回第一个格子：玩家刚在 27 号格调好位置，
        // 换个箱子又跳回 1 号格，等于每开一次箱子都要重新找路。
        // 判定依据是「容器类型（ScreenHandler 的类）相同 + 屏幕尺寸相同」。
        Screen screen = client.currentScreen;
        if (screen != lastTickScreen) {
            boolean sameKind = isSameContainerKind(screen, lastTickScreen);
            lastTickScreen = screen;
            java.util.Arrays.fill(dpadHold, 0);
            if (screen instanceof HandledScreen<?> hs && !sameKind) {
                snapToFirstSlot(client, hs);
            }
        }

        // 自绘光标同步：把虚拟光标写回 Mouse.x/y。这两个字段的语义是「窗口物理像素」
        // （字节码验证：getX()/getY() 直接返回字段；GameRenderer/onMouseButton 都用
        // x * scaledWidth / width 换算 GUI 坐标），所以写之前要乘 windowScaleFactor。
        // 写对后 GameRenderer 每帧传给 Screen.render 的 hover 坐标、tooltip 位置、
        // 以及我们调 screen.mouseClicked 的坐标三者必然一致。
        // 之前写 GUI 坐标导致偏移 = 1/scaleFactor；而反射 FCL CallbackBridge 的
        // 通道（GameMouse）在真机上走不通（SDL 直通劫持 / stackQueue 未达 GLFW 回调），已删。
        // 只在界面内写：游戏内光标锁定时 Mouse.x/y 语义不同，不能碰。
        syncMouse(client);

        // 摇杆驱动的位移不在这里做：tick 只有 20Hz，逐 tick 挪光标在手机上肉眼可见地一顿一顿。
        // 真正的位移在 frameCursor()，由 ScreenMixin 每帧调一次（渲染帧率，通常 60~120Hz）。

        // 攻击键=左键、使用键=右键。用边沿（不是按住）：screen 的 mouseClicked 对
        // 重复按下不幂合，而且背包里「按住 A 不停抓取」不是期望行为。
        // 另外必须 cursorVisible 才允许点击：光标没被摇杆推出来之前它停在屏幕中心，
        // 若此时按 RT/LT 会在不可见的位置点一下 —— 典型事故是开背包后按攻击键，
        // 结果点了背包正中间那个格子。光标可见才点，和「看得见才能瞄」一致。
        if (client.currentScreen instanceof HandledScreen<?> hs) {
            // 光标推到左侧配方书区域时接管操作：十字键选配方格子、A 点选、
            // LB/RB 切分类、LT/RT 翻页（容器里这四个键平时是丢物品/取一半，
            // 但光标在书里时那些操作本来也没意义，所以按区域分工不冲突）。
            if (tickRecipeBook(client, hs)) {
                return;
            }
            // 容器界面：ABXY/扳机走物品操作、十字键走格子导航。
            // 这里绝不能调 Screen.mouseClicked —— 安卓触摸屏模式（getTouchscreen 默认 true）下
            // HandledScreen.mouseClicked 会走 touchDragSlotStart 拖拽分支，永远不调
            // onMouseClick，物品根本不动。必须直调 interactionManager.clickSlot。
            tickContainer(client, hs);
            return;
        }

        // 自由光标（非容器界面）：十字键微调位置（比摇杆精准），LB/RB 模拟滚轮。
        // dpadMode="focus" 时十字键改为「在控件间吸附跳转」，适合纯按钮式界面
        // （选项、语言列表）：不用一寸一寸挪，按一下直接落到最近的按钮上。
        if ("focus".equals(cfg.dpadMode)) {
            focusNavigate(client);
        } else {
            nudgeFreeCursor(client);
        }
        handleScreenScroll(client);

        // 普通界面（选项/单人游戏列表/暂停菜单…）：A 直接点一下光标位置（按下+抬起，
        // 因为列表项和按钮只要一次完整点击），B 返回/关闭。扳机仍然当左右键用，
        // 这样需要「右键」语义的自定义界面也不会丢功能。
        if (buttonEdge(Button.SOUTH)) {
            click(cursorX, cursorY, 0);
            release(cursorX, cursorY, 0);
        }
        if (buttonEdge(Button.EAST)) {
            closeScreen(client);
        }
        boolean leftNow = isBoundHeld(Action.ATTACK);
        boolean rightNow = isBoundHeld(Action.USE);
        if (leftNow && !cursorLeftWas) {
            click(cursorX, cursorY, 0);
        } else if (!leftNow && cursorLeftWas) {
            release(cursorX, cursorY, 0);
        }
        if (rightNow && !cursorRightWas) {
            click(cursorX, cursorY, 1);
        } else if (!rightNow && cursorRightWas) {
            release(cursorX, cursorY, 1);
        }
        cursorLeftWas = leftNow;
        cursorRightWas = rightNow;
    }

    private boolean cursorLeftWas;
    private boolean cursorRightWas;
    /** 轮盘松手时选中的动作（本 tick 内消费一次）。 */
    private Action radialSelected = Action.NONE;
    /** 轮盘触发「玩家列表」时剩余的点亮 tick（该动作是按住型，轮盘只能给一次性）。 */
    private int radialPlayerListTicks;

    /**
     * 把虚拟光标写回 {@code Mouse.x/y}。
     *
     * <p>这两个字段的语义是「窗口物理像素」（字节码验证：GameRenderer/onMouseButton 都用
     * {@code x * scaledWidth / width} 换算 GUI 坐标），所以写之前要乘 windowScaleFactor。</p>
     */
    private void syncMouse(MinecraftClient client) {
        Mouse mouse = client.mouse;
        if (mouse == null) return;
        MouseAccessor acc = (MouseAccessor) mouse;
        double phys = client.getWindow().getScaleFactor();
        acc.fouryctrl$setX(cursorX * phys);
        acc.fouryctrl$setY(cursorY * phys);
    }

    /**
     * 两个界面是否属于「同一种容器」（背包/箱子/熔炉…）。
     *
     * <p>用来避免同类容器互换时把光标位置重置。判据是两边的
     * {@code ScreenHandler} 类完全相同、且界面尺寸相同 —— 尺寸不同说明布局变了，
     * 沿用旧坐标可能落在别的格子上。</p>
     */
    private static boolean isSameContainerKind(Screen now, Screen before) {
        if (!(now instanceof HandledScreen<?> cur) || !(before instanceof HandledScreen<?> old)) {
            return false;
        }
        ScreenHandler a = cur.getScreenHandler();
        ScreenHandler b = old.getScreenHandler();
        if (a == null || b == null || a.getClass() != b.getClass()) {
            return false;
        }
        return now.width == before.width && now.height == before.height;
    }

    /**
     * 逐帧推进光标（由 {@code ScreenMixin} 在每次界面渲染时调用）。
     *
     * <p>为什么不在 tick 里做：tick 固定 20Hz，光标位置每 50ms 才变一次，画面上就是
     * 「一卡一卡」。而渲染帧率通常 60~120Hz，按帧乘 dt 推进才能和真实鼠标一样顺。</p>
     *
     * <p>速度曲线：满推 = {@code guiCursorSpeed} 像素/秒，套 {@code stickCurve}
     * 响应曲线。曲线乘在统一速度上而不是分量上，所以斜推方向不会偏。</p>
     */
    public void frameCursor(MinecraftClient client) {
        if (!cfg.guiCursor || !cursorActive || client == null || client.currentScreen == null) {
            return;
        }
        long now = System.nanoTime();
        if (lastCursorFrameNanos == 0L) {
            lastCursorFrameNanos = now;
            return;
        }
        double dt = (now - lastCursorFrameNanos) / 1.0e9;
        lastCursorFrameNanos = now;
        if (dt <= 0.0) return;
        if (dt > 0.1) dt = 0.1; // 卡顿/切后台回来时不要瞬移一大段

        // 每帧现读摇杆（同 computeLook）：tick 采样值最多滞后 50ms，光标会一顿一顿的。
        float rx = liveStick(cfg.axisRX);
        float ry = liveStick(cfg.axisRY);
        double vx = rx;
        double vy = ry;
        if (Math.abs(vx) <= 0.01f && Math.abs(vy) <= 0.01f) return;

        // 容器界面同样允许摇杆自由移动光标：以前这里直接 return，导致打开背包/工作台/
        // 熔炉之后右摇杆完全失效（格子吸附只能靠十字键一步一步走，路径长了很痛苦）。
        // cursorX/Y 与 Mouse.x/y 始终由 syncMouse 同步，hovered 格子由 getSlotAt 按
        // 光标坐标实时算，所以自由移动不会和高亮脱节。
        double rawMag = Math.sqrt(vx * vx + vy * vy);
        double mag = Math.min(1.0, rawMag);
        if (mag <= cfg.rightDeadzone) return;
        // 死区后重新归一化，避免「一推就跳一段」；再套响应曲线。
        // deadzone 被配置成 1 时下面的除法会得 NaN → 光标瞬移；夹一下。
        double dz = Math.min(0.999, cfg.rightDeadzone);
        double norm = Math.min(1.0, Math.max(0.0, (mag - dz) / (1.0 - dz)));
        if (cfg.stickCurve != 1.0f) {
            norm = Math.pow(norm, cfg.stickCurve);
        }
        double speed = cfg.guiCursorSpeed * norm; // 像素/秒
        // 除的是真实模长而不是夹到 1 的那个：摇杆推对角时 rawMag 可达 1.41，
        // 用夹后值做除数会让斜向实际快 1.41 倍（斜推比直推飘）。
        double scale = speed * dt / Math.max(1.0e-4, rawMag);
        double maxX = client.getWindow().getScaledWidth();
        double maxY = client.getWindow().getScaledHeight();
        cursorX = Math.max(0.0, Math.min(maxX, cursorX + vx * scale));
        cursorY = Math.max(0.0, Math.min(maxY, cursorY + vy * scale));

        syncMouse(client);
        try {
            client.currentScreen.mouseMoved(cursorX, cursorY);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 自由光标界面里用十字键微调光标（比摇杆更精准）。
     *
     * <p>容器界面不走这里（十字键在容器里是逐格导航）；只在非容器界面生效。
     * 首按挪 2 像素，按住不放会以越来越快的节奏连挪（8 帧后每 2 tick 一次）。</p>
     */
    private void nudgeFreeCursor(MinecraftClient client) {
        // 界面里十字键永远做导航：动作分发只发生在游戏内（applyActions 里看 currentScreen == null），
        // 所以「绑了动作」不该影响界面内的光标微调。
        Button[] dirs = {Button.DPAD_UP, Button.DPAD_DOWN, Button.DPAD_LEFT, Button.DPAD_RIGHT};
        int[][] vec = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};
        boolean moved = false;
        for (int i = 0; i < dirs.length; i++) {
            if (!state.isDown(dirs[i])) {
                nudgeHold[i] = 0;
                continue;
            }
            int t = ++nudgeHold[i];
            if (t == 1 || (t > 8 && (t - 9) % 2 == 0)) {
                double maxX = client.getWindow().getScaledWidth();
                double maxY = client.getWindow().getScaledHeight();
                cursorX = Math.max(0.0, Math.min(maxX, cursorX + vec[i][0] * 2.0));
                cursorY = Math.max(0.0, Math.min(maxY, cursorY + vec[i][1] * 2.0));
                moved = true;
            }
        }
        if (!moved) return;
        syncMouse(client);
        try {
            client.currentScreen.mouseMoved(cursorX, cursorY);
        } catch (Throwable ignored) {
        }
    }

    /** 十字键方向向量：上、下、左、右。 */
    private static final int[][] FOCUS_DIR = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};

    /**
     * 十字键在界面控件之间吸附跳转（{@code dpadMode="focus"}）。
     *
     * <p>MC 1.20.1 的 {@code Screen.children()} 是 public 的（来自 {@code ParentElement}），
     * 直接遍历即可，不需要 mixin。每个可点的 {@code ClickableWidget} 同时提供了
     * {@code getX/getY/getWidth/getHeight} 与 public 的 {@code visible/active} 字段。</p>
     *
     * <p>选取规则用「沿方向投影 + 垂直距离加权」，避免在网格布局里跳到斜对面的按钮：
     * 只有投影为正（在按键方向上）的才算候选，得分 {@code along + perp * 2}。</p>
     */
    private void focusNavigate(MinecraftClient client) {
        Screen screen = client.currentScreen;
        if (screen == null) {
            return;
        }
        Button[] dirs = {Button.DPAD_UP, Button.DPAD_DOWN, Button.DPAD_LEFT, Button.DPAD_RIGHT};
        for (int i = 0; i < dirs.length; i++) {
            if (!state.isDown(dirs[i])) {
                nudgeHold[i] = 0;
                continue;
            }
            int t = ++nudgeHold[i];
            // 首按立即跳，之后每 3 tick 一格（长按连续翻）。
            if (t != 1 && !(t > 6 && (t - 7) % 3 == 0)) {
                continue;
            }
            ClickableWidget target = nearestWidget(screen, cursorX, cursorY,
                    FOCUS_DIR[i][0], FOCUS_DIR[i][1]);
            if (target == null) {
                continue;
            }
            cursorX = target.getX() + target.getWidth() / 2.0;
            cursorY = target.getY() + target.getHeight() / 2.0;
            cursorActive = true;
            cursorVisible = true;
            syncMouse(client);
            try {
                screen.setFocused(target);
                screen.mouseMoved(cursorX, cursorY);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 在界面里找「从这个点沿 (dx,dy) 方向最近的可点控件」。
     *
     * @return 命中的控件；该方向没有候选时返回 {@code null}
     */
    private static ClickableWidget nearestWidget(Screen screen, double fromX, double fromY, int dx, int dy) {
        ClickableWidget best = null;
        double bestScore = Double.MAX_VALUE;
        for (var element : screen.children()) {
            if (!(element instanceof ClickableWidget w) || !w.visible || !w.active) {
                continue;
            }
            double cx = w.getX() + w.getWidth() / 2.0;
            double cy = w.getY() + w.getHeight() / 2.0;
            double ox = cx - fromX;
            double oy = cy - fromY;
            // 沿方向上的投影（必须为正：只往按的方向跳）
            double along = ox * dx + oy * dy;
            if (along <= 1.0) {
                continue;
            }
            // 垂直方向的偏移量（取绝对值）
            double perp = Math.abs(ox * dy - oy * dx);
            double score = along + perp * 2.0;
            if (score < bestScore) {
                bestScore = score;
                best = w;
            }
        }
        return best;
    }

    private boolean isDpadButton(Button b) {
        return b == Button.DPAD_UP || b == Button.DPAD_DOWN
                || b == Button.DPAD_LEFT || b == Button.DPAD_RIGHT;
    }

    /**
     * LB/RB 模拟鼠标滚轮（只在非容器界面生效，容器里 LB/RB 已用于丢物品）。
     *
     * <p>坐标直接用我们这套已缩放的 GUI 坐标：{@code Mouse.onMouseScroll} 内部也是把
     * 物理像素换算成 GUI 坐标后再调 {@code Screen.mouseScrolled}，所以这里不用再乘
     * scaleFactor（否则滚动命中的位置会错开）。</p>
     */
    private void handleScreenScroll(MinecraftClient client) {
        boolean up = state.isDown(Button.LEFT_SHOULDER);
        boolean down = state.isDown(Button.RIGHT_SHOULDER);

        boolean fireUp = false;
        if (up) {
            int t = ++scrollHoldUp;
            fireUp = t == 1 || t % 4 == 0;
        } else {
            scrollHoldUp = 0;
        }
        boolean fireDown = false;
        if (down) {
            int t = ++scrollHoldDown;
            fireDown = t == 1 || t % 4 == 0;
        } else {
            scrollHoldDown = 0;
        }
        scrollHoldUp = up ? scrollHoldUp : 0;
        scrollHoldDown = down ? scrollHoldDown : 0;
        if (!fireUp && !fireDown) return;

        try {
            Compat.mouseScrolled(client.currentScreen, cursorX, cursorY, fireUp ? 1.0 : -1.0);
            client.currentScreen.applyMousePressScrollNarratorDelay();
        } catch (Throwable t) {
            FourYCtrl.LOG.debug("4YCtrl: 滚轮模拟失败：{}", t.toString());
        }
    }

    /**
     * 某个动作当前是否被任何按钮/扳机按住（含扳机模拟按钮）。
     *
     * <p>不能直接看 {@code held}：tickGuiCursor 在 applyActions 之前跑，held 还是上一帧
     * 的残留（已 clear）；所以这里独立重算一遍「绑定到此动作的按钮/扳机是否按下」。
     * 直接查绑定表而不是硬编码按钮号：否则用户改了键位，界面里的点击就跟着错。</p>
     */
    private boolean isBoundHeld(Action action) {
        if (cfg.bindings != null) {
            for (Map.Entry<String, String> e : cfg.bindings.entrySet()) {
                if (!action.id.equals(e.getValue())) {
                    continue;
                }
                Button b = Button.byId(e.getKey());
                if (b != null && state.isDown(b)) {
                    return true;
                }
            }
        }
        // 扳机：不硬编码「右扳机=攻击」，动作与扳机的对应关系在配置里
        // （默认 LT=攻击、RT=使用，与 FCL 一致），两边都要查。
        if (action == Action.ATTACK || action == Action.USE) {
            if (state.rightTrigger > 0f && action.id.equals(cfg.triggerRightAction)) {
                return true;
            }
            if (state.leftTrigger > 0f && action.id.equals(cfg.triggerLeftAction)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ 容器界面

    /**
     * 容器界面（背包/箱子/工作台/熔炉…）的手柄操作。
     *
     * <p>按键分配：</p>
     * <ul>
     *   <li>十字键：在格子之间精确移动光标（按住会加速重复）</li>
     *   <li>A / LT：左键 —— 拿起整叠 / 放下整叠 / 交换</li>
     *   <li>B / RT：右键 —— 取一半 / 放一个</li>
     *   <li>X：Shift+左键 —— 快速移动整叠（箱子↔背包）</li>
     *   <li>Y：丢出一个</li>
     *   <li>LB：丢出整叠</li>
     *   <li>RB：把光标上托着的物品丢出去</li>
     * </ul>
     * <p>全部通过 {@code clickSlot} 直发，绕开触摸屏拖拽分支，行为与 PC 鼠标完全一致。</p>
     */
    private void tickContainer(MinecraftClient client, HandledScreen<?> screen) {
        Button[] dirs = {Button.DPAD_UP, Button.DPAD_DOWN, Button.DPAD_LEFT, Button.DPAD_RIGHT};
        int[][] vec = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};
        for (int i = 0; i < dirs.length; i++) {
            if (!state.isDown(dirs[i])) {
                dpadHold[i] = 0;
                continue;
            }
            int t = ++dpadHold[i];
            // 首按立即走一步，按住 0.5 秒后每 3 tick 走一步（跨半屏不用按十几次）。
            if (t == 1 || (t > 10 && (t - 11) % 3 == 0)) {
                stepSlotCursor(client, screen, vec[i][0], vec[i][1]);
            }
        }

        if (!cursorVisible) {
            return;
        }
        Slot hovered = slotAtCursor(screen);
        if (buttonEdge(Button.SOUTH) || triggerEdge(true)) {
            containerClick(client, hovered, 0, SlotActionType.PICKUP);
        }
        if (buttonEdge(Button.EAST) || triggerEdge(false)) {
            containerClick(client, hovered, 1, SlotActionType.PICKUP);
        }
        if (buttonEdge(Button.WEST)) {
            containerClick(client, hovered, 0, SlotActionType.QUICK_MOVE);
        }
        if (buttonEdge(Button.NORTH)) {
            containerClick(client, hovered, 0, SlotActionType.THROW);
        }
        if (buttonEdge(Button.LEFT_SHOULDER)) {
            containerClick(client, hovered, 1, SlotActionType.THROW);
        }
        if (buttonEdge(Button.RIGHT_SHOULDER)) {
            // slotId = -999 是 vanilla 的「点到界面外」：放下 = 把光标上的物品丢出去。
            containerClick(client, null, 0, SlotActionType.PICKUP);
        }
    }

    /**
     * 光标落在配方书区域时，把输入交给配方书自己处理。
     *
     * <p>为什么把坐标原样喂给 {@code RecipeBookWidget.mouseClicked}：它的内部实现就是
     * 用绝对 GUI 坐标减去 {@code ((parentWidth-147)/2-leftOffset, (parentHeight-166)/2)}
     * 再依次处理结果格子（→ {@code clickRecipe}）/搜索框/过滤按钮/分类页签。
     * 复刻这套命中逻辑没有意义，直接喂坐标即可，且 CraftingScreen/AbstractFurnaceScreen
     * 自己也是这么转发的。</p>
     *
     * <p>区域分工：光标在书里时 LT/RT 翻页、LB/RB 切分类 —— 容器里这四个键平时是
     * 丢物品/取一半/丢弃，但光标在书里时那些操作本来也没有意义。</p>
     *
     * @return true 表示本 tick 的输入已被配方书消费，调用方不要再走容器逻辑
     */
    private boolean tickRecipeBook(MinecraftClient client, HandledScreen<?> screen) {
        RecipeBookWidget book = openRecipeBook(screen);
        if (book == null) {
            return false;
        }
        RecipeBookWidgetAccessor acc = (RecipeBookWidgetAccessor) book;
        int left = (acc.fouryctrl$getParentWidth() - 147) / 2 - acc.fouryctrl$getLeftOffset();
        int top = (acc.fouryctrl$getParentHeight() - 166) / 2;
        // 分类页签在书左边缘再往左 30px（35x27），所以命中区左边多留一点。
        int hitLeft = left - 32;
        if (cursorX < hitLeft || cursorX > left + 147 || cursorY < top - 2 || cursorY > top + 168) {
            return false;
        }

        List<ClickableWidget> targets = recipeTargets(acc);

        // 十字键在书内逐格导航（首按一步，按住 0.5 秒后每 3 tick 一步）
        Button[] dirs = {Button.DPAD_UP, Button.DPAD_DOWN, Button.DPAD_LEFT, Button.DPAD_RIGHT};
        int[][] vec = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};
        for (int i = 0; i < dirs.length; i++) {
            if (!state.isDown(dirs[i])) {
                dpadHold[i] = 0;
                continue;
            }
            int t = ++dpadHold[i];
            if (t == 1 || (t > 10 && (t - 11) % 3 == 0)) {
                moveToNearestTarget(client, targets, vec[i][0], vec[i][1]);
            }
        }

        // A：在光标位置点一下（方案格子 / 搜索框 / 过滤按钮 / 分类页签全靠它）
        if (buttonEdge(Button.SOUTH)) {
            try {
                Compat.mouseClicked((Element) book, cursorX, cursorY, 0);
                if (cfg.rumbleEnabled) {
                    rumbleTicks = 3;
                    rumbleLow = rumbleStrength();
                    rumbleHigh = 0;
                }
            } catch (Throwable t) {
                if (cfg.debug) {
                    FourYCtrl.LOG.debug("配方书点击失败：{}", t.toString());
                }
            }
        }
        // B：收起配方书（再按一次才退出界面，避免一下连退两层）
        if (buttonEdge(Button.EAST)) {
            try {
                book.toggleOpen();
            } catch (Throwable ignored) {
            }
        }

        RecipeBookResults area = acc.fouryctrl$getRecipesArea();
        if (area instanceof RecipeBookResultsAccessor ra) {
            if (triggerEdge(true)) {
                clickWidget(book, ra.fouryctrl$getPrevPageButton());
            }
            if (triggerEdge(false)) {
                clickWidget(book, ra.fouryctrl$getNextPageButton());
            }
        }
        if (buttonEdge(Button.LEFT_SHOULDER)) {
            cycleTab(book, acc, -1);
        }
        if (buttonEdge(Button.RIGHT_SHOULDER)) {
            cycleTab(book, acc, +1);
        }
        return true;
    }

    /**
     * 当前界面里打开的配方书；界面没有配方书（例如铁砧/切石机）或书没打开时返回 null。
     */
    public static RecipeBookWidget openRecipeBook(Screen screen) {
        if (screen == null) {
            return null;
        }
        try {
            // 1.21.11 删掉了 RecipeBookProvider.getRecipeBookWidget()，但全版本
            // 都把配方书作为 selectable child 挂进 children()，扫一遍反而更通用。
            for (Element element : screen.children()) {
                if (element instanceof RecipeBookWidget book && book.isOpen()) {
                    return book;
                }
            }
        } catch (Throwable t) {
            return null;
        }
        return null;
    }

    /** 书里可被十字键选中的控件：结果格 / 翻页 / 分类页签 / 搜索框 / 过滤按钮。 */
    private List<ClickableWidget> recipeTargets(RecipeBookWidgetAccessor acc) {
        List<ClickableWidget> list = new ArrayList<>();
        RecipeBookResults area = acc.fouryctrl$getRecipesArea();
        if (area instanceof RecipeBookResultsAccessor ra) {
            for (AnimatedResultButton b : ra.fouryctrl$getResultButtons()) {
                if (b.visible) {
                    list.add(b);
                }
            }
            addIfVisible(list, ra.fouryctrl$getNextPageButton());
            addIfVisible(list, ra.fouryctrl$getPrevPageButton());
        }
        for (RecipeGroupButtonWidget tab : acc.fouryctrl$getTabButtons()) {
            if (tab.visible) {
                list.add(tab);
            }
        }
        addIfVisible(list, acc.fouryctrl$getSearchField());
        addIfVisible(list, acc.fouryctrl$getToggleCraftableButton());
        return list;
    }

    private static void addIfVisible(List<ClickableWidget> list, ClickableWidget w) {
        if (w != null && w.visible) {
            list.add(w);
        }
    }

    /** 在给定方向上找最近的控件并把光标移过去（控件坐标已是绝对 GUI 坐标）。 */
    private void moveToNearestTarget(MinecraftClient client, List<ClickableWidget> targets, int dx, int dy) {
        if (targets.isEmpty()) {
            return;
        }
        ClickableWidget best = null;
        double bestScore = Double.MAX_VALUE;
        for (ClickableWidget w : targets) {
            double sx = w.getX() + w.getWidth() / 2.0;
            double sy = w.getY() + w.getHeight() / 2.0;
            double along = dx != 0 ? (sx - cursorX) * dx : (sy - cursorY) * dy;
            double perp = dx != 0 ? Math.abs(sy - cursorY) : Math.abs(sx - cursorX);
            if (along <= 0.5) {
                continue; // 不在这个方向上
            }
            double score = along + perp * 2.0;
            if (score < bestScore) {
                bestScore = score;
                best = w;
            }
        }
        if (best == null) {
            return;
        }
        cursorX = best.getX() + best.getWidth() / 2.0;
        cursorY = best.getY() + best.getHeight() / 2.0;
        cursorActive = true;
        cursorVisible = true;
        syncMouse(client);
        try {
            client.currentScreen.mouseMoved(cursorX, cursorY);
        } catch (Throwable ignored) {
        }
    }

    /** 点一个书内控件：把它的中心坐标喂给书自己的 mouseClicked。 */
    private void clickWidget(RecipeBookWidget book, ClickableWidget w) {
        if (w == null || !w.visible || !w.active) {
            return;
        }
        try {
            Compat.mouseClicked((Element) book, w.getX() + w.getWidth() / 2.0, w.getY() + w.getHeight() / 2.0, 0);
        } catch (Throwable ignored) {
        }
    }

    /** LB/RB 切分类：在可见页签里循环，再把光标吸到新页签上。 */
    private void cycleTab(RecipeBookWidget book, RecipeBookWidgetAccessor acc, int delta) {
        List<RecipeGroupButtonWidget> tabs = acc.fouryctrl$getTabButtons();
        if (tabs == null) {
            return;
        }
        List<RecipeGroupButtonWidget> visible = new ArrayList<>();
        for (RecipeGroupButtonWidget t : tabs) {
            if (t.visible) {
                visible.add(t);
            }
        }
        if (visible.isEmpty()) {
            return;
        }
        RecipeGroupButtonWidget current = acc.fouryctrl$getCurrentTab();
        int index = visible.indexOf(current);
        int next = index < 0 ? 0 : ((index + delta) % visible.size() + visible.size()) % visible.size();
        RecipeGroupButtonWidget target = visible.get(next);
        clickWidget(book, target);
        // 光标跟着吸到新页签上，方便接着用 A 确认或十字键往结果区走。
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.currentScreen != null) {
            cursorX = target.getX() + target.getWidth() / 2.0;
            cursorY = target.getY() + target.getHeight() / 2.0;
            cursorActive = true;
            cursorVisible = true;
            syncMouse(client);
            try {
                client.currentScreen.mouseMoved(cursorX, cursorY);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 按钮的按下沿（用上一 tick 的快照比较，去抖后的稳定态）。 */
    private boolean buttonEdge(Button b) {
        return state.isDown(b) && !prev.isDown(b);
    }

    /** 扳机的按下沿；left=true 取左扳机。 */
    private boolean triggerEdge(boolean left) {
        float now = left ? state.leftTrigger : state.rightTrigger;
        float was = left ? prev.leftTrigger : prev.rightTrigger;
        return now > 0f && was <= 0f;
    }

    private Slot slotAtCursor(HandledScreen<?> screen) {
        if (!(screen instanceof HandledScreenAccessor acc)) {
            return null;
        }
        try {
            return acc.fouryctrl$getSlotAt(cursorX, cursorY);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 进容器时把光标吸到第一个可用格子（箱子=左上角，背包=合成输出）。 */
    private void snapToFirstSlot(MinecraftClient client, HandledScreen<?> screen) {
        if (!(screen instanceof HandledScreenAccessor acc)) {
            return;
        }
        ScreenHandler handler = screen.getScreenHandler();
        if (handler == null) {
            return;
        }
        for (Slot s : handler.slots) {
            if (s.isEnabled()) {
                moveCursorTo(client, acc, s);
                return;
            }
        }
    }

    /**
     * 十字键逐格导航：在所有可用格子里挑「目标方向上最近的一个」。
     * 用几何距离而不是行列索引，因为同一界面里往往有多个网格区
     * （箱子 9×6 + 背包 9×3 + 快捷栏 9×1 + 护甲列），索引换算是错的。
     */
    private void stepSlotCursor(MinecraftClient client, HandledScreen<?> screen, int dx, int dy) {
        if (!(screen instanceof HandledScreenAccessor acc)) {
            return;
        }
        ScreenHandler handler = screen.getScreenHandler();
        if (handler == null) {
            return;
        }
        int bx = acc.fouryctrl$getX();
        int by = acc.fouryctrl$getY();
        Slot current = slotAtCursor(screen);
        double cx = current != null ? bx + current.x + 8 : cursorX;
        double cy = current != null ? by + current.y + 8 : cursorY;

        Slot best = null;
        double bestScore = Double.MAX_VALUE;
        for (Slot s : handler.slots) {
            if (s == current || !s.isEnabled()) {
                continue;
            }
            double sx = bx + s.x + 8;
            double sy = by + s.y + 8;
            double along;
            double perp;
            if (dx != 0) {
                along = (sx - cx) * dx;
                perp = Math.abs(sy - cy);
            } else {
                along = (sy - cy) * dy;
                perp = Math.abs(sx - cx);
            }
            if (along <= 0.5) {
                continue; // 不在这个方向上
            }
            // 主方向距离为主，横向偏移加权惩罚：优先走直线，斜向只在没有直线候选时选。
            double score = along + perp * 2.0;
            if (score < bestScore) {
                bestScore = score;
                best = s;
            }
        }
        if (best != null) {
            moveCursorTo(client, acc, best);
        }
    }

    private void moveCursorTo(MinecraftClient client, HandledScreenAccessor acc, Slot slot) {
        cursorX = acc.fouryctrl$getX() + slot.x + 8;
        cursorY = acc.fouryctrl$getY() + slot.y + 8;
        cursorActive = true;
        cursorVisible = true;
        syncMouse(client);
        try {
            client.currentScreen.mouseMoved(cursorX, cursorY);
        } catch (Throwable ignored) {
        }
    }

    /** 物品操作：直发 clickSlot。slot == null 时用 -999（界面外）。 */
    private void containerClick(MinecraftClient client, Slot slot, int button, SlotActionType type) {
        if (client.interactionManager == null || client.player == null) {
            return;
        }
        ScreenHandler handler = client.player.currentScreenHandler;
        if (handler == null) {
            return;
        }
        int slotId = slot != null ? slot.id : -999;
        try {
            client.interactionManager.clickSlot(handler.syncId, slotId, button, type, client.player);
            if (cfg.rumbleEnabled) {
                rumbleTicks = 3;
                rumbleLow = rumbleStrength();
                rumbleHigh = 0;
            }
        } catch (Throwable t) {
            FourYCtrl.LOG.warn("4YCtrl: 容器点击失败（slot={}, {}）", slotId, type, t);
        }
    }

    private void click(double x, double y, int button) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.currentScreen == null) {
            return;
        }
        try {
            Compat.mouseClicked(client.currentScreen, x, y, button);
            if (cfg.rumbleEnabled) {
                rumbleTicks = 4;
                rumbleLow = rumbleStrength();
                rumbleHigh = 0;
            }
        } catch (Throwable ignored) {
        }
    }

    private void release(double x, double y, int button) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.currentScreen == null) {
            return;
        }
        try {
            Compat.mouseReleased(client.currentScreen, x, y, button);
        } catch (Throwable ignored) {
        }
    }

    /** 关掉当前界面（等同 ESC，但绕开 shouldCloseOnEsc 之外的键盘事件）。 */
    private void closeScreen(MinecraftClient client) {
        if (client.currentScreen == null) {
            return;
        }
        try {
            client.currentScreen.close();
        } catch (Throwable t) {
            FourYCtrl.LOG.warn("4YCtrl: 关闭界面失败", t);
        }
    }

    // ------------------------------------------------------------------ 震动

    /**
     * 触发一次震动。
     *
     * <p>以前是「每 tick 调一次 {@code SDL_RumbleJoystick(,,,60)}，共 6 tick」——
     * 60ms 的请求每 50ms 重发一次，请求会在 10ms 的空档里断掉，手感是一串碎震。
     * 现在按「一次给足」：直接下发 {@code ticks * 50ms} 的时长，SDL 自己在硬件上跑完。</p>
     */
    private void requestRumble(int ticks, int low, int high) {
        if (!cfg.rumbleEnabled || !poller.isConnected()) {
            return;
        }
        // 震动也在轮询线程上执行：SDL_RumbleJoystick 同样要走 joystick 锁，
        // 在客户端线程上调它一样可能卡帧。
        poller.queueRumble(low, high, Math.max(40, ticks * 50));
    }

    /** 把本帧新触发的事件换算成震动请求。只做边沿触发，不持续施震。 */
    private void updateRumbleTriggers(MinecraftClient client) {
        if (!cfg.rumbleEnabled) {
            return;
        }
        int strength = rumbleStrength();

        // ① 游戏内攻击/使用（扳机过死区）给一个短震。不硬编码左右扳机对应哪个动作：
        // 看「某扳机绑定的动作」是否是攻击/使用 —— 配置改了震动也跟着对。
        boolean attack = (state.rightTrigger > 0f && prev.rightTrigger <= 0f && "attack".equals(cfg.triggerRightAction))
                || (state.leftTrigger > 0f && prev.leftTrigger <= 0f && "attack".equals(cfg.triggerLeftAction));
        boolean use = (state.rightTrigger > 0f && prev.rightTrigger <= 0f && "use".equals(cfg.triggerRightAction))
                || (state.leftTrigger > 0f && prev.leftTrigger <= 0f && "use".equals(cfg.triggerLeftAction));
        if (attack) {
            requestRumble(4, strength, strength / 2);
        } else if (use) {
            requestRumble(3, strength / 2, 0);
        }

        ClientPlayerEntity player = client.player;
        if (player == null) {
            lastFoodLevel = -1;
            lastHurtTime = 0;
            lastItemCount = -1;
            durabilityWarnStack = 0;
            return;
        }

        // ② 捡到东西：背包总物品数增加就轻震一下。
        if (cfg.rumblePickup) {
            int count = 0;
            for (int i = 0; i < player.getInventory().size(); i++) {
                count += player.getInventory().getStack(i).getCount();
            }
            if (lastItemCount >= 0 && count > lastItemCount) {
                requestRumble(2, strength / 3, 0);
            }
            lastItemCount = count;
        }

        // ③ 受伤：hurtTime 从 0 跳到正值。
        if (cfg.rumbleHurt) {
            if (player.hurtTime > lastHurtTime) {
                requestRumble(4, strength, strength);
            }
            lastHurtTime = player.hurtTime;
        }

        // ④ 饥饿：foodLevel 下降（每掉一格只提醒一次）。
        if (cfg.rumbleHunger) {
            int food = player.getHungerManager().getFoodLevel();
            if (lastFoodLevel >= 0 && food < lastFoodLevel) {
                requestRumble(2, 0, strength / 2);
            }
            lastFoodLevel = food;
        }

        // ⑤ 主手工具耐久告危：跨过 10% 那一刻提醒一次，换物品后重新武装。
        if (cfg.rumbleDurability) {
            ItemStack main = player.getMainHandStack();
            int hash = System.identityHashCode(main.getItem()) * 31 + main.getCount();
            if (main.isDamageable() && main.getMaxDamage() > 0) {
                float left = 1.0f - (float) main.getDamage() / main.getMaxDamage();
                if (left <= 0.1f && hash != durabilityWarnStack) {
                    durabilityWarnStack = hash;
                    requestRumble(3, strength, 0);
                }
            } else {
                durabilityWarnStack = 0;
            }
        }
    }

    /** 诊断命令用：立刻震一下（0.4 秒），返回是否被硬件接受。 */
    public boolean testRumble() {
        if (!poller.isConnected() || !hasRumble()) {
            return false;
        }
        poller.queueTestRumble(rumbleStrength());
        return true;
    }

    private int rumbleStrength() {
        return (int) (Math.max(0.05f, cfg.rumbleStrength) * 65535.0f);
    }

    /**
     * 兼容旧调用点（容器点击/光标点击等处直接用 {@code rumbleTicks = N}）。
     *
     * <p>震动现在是「下发一次、硬件自己跑」，不再需要每 tick 续期，
     * 所以这个 tick 只需要把「刚被置上的 rumbleTicks」消费掉。</p>
     */
    private void tickRumble(MinecraftClient client) {
        if (rumbleTicks <= 0) {
            return;
        }
        int ticks = rumbleTicks;
        rumbleTicks = 0;
        if (!poller.isConnected()) {
            return;
        }
        poller.queueRumble(rumbleLow, rumbleHigh, Math.max(40, ticks * 50));
    }

    // ------------------------------------------------------------------ 收尾

    public void close() {
        shutdown = true;
        // 关句柄在轮询线程的 finally 里做：句柄只属于那个线程，跨线程碰它反而危险。
        poller.shutdown();
    }
}
