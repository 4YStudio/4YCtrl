package com.fouryctrl.config;

import com.fouryctrl.FourYCtrl;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模组配置。文件位于 .minecraft/config/fouryctrl.json，首次运行自动生成。
 *
 * <p>设计原则：所有可能因设备差异而出错的量（轴序号、死区、灵敏度）都放进配置，
 * 这样用户在手柄上发现「推左摇杆人物侧移」之类的错位时，改配置就能自救，不必等我发新版。</p>
 */
public final class ModConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * 配置版本。升级模组时用它把旧默认绑定迁移成新默认（用户没得选——旧默认是错的）。
     * v1：初版绑定（LB=疾跑、B=潜行……与 FCL 习惯相悖）。
     * v2：按 FCL 默认映射重排（LT=攻击、RT=使用、LB/RB=快捷栏、R3=潜行、B=丢弃、X=背包、Y=副手）。
     */
    public int configVersion = 2;

    // ---- 开关 ----
    /** 总开关。 */
    public boolean enabled = true;
    /**
     * 是否自动初始化 SDL 手柄子系统。
     *
     * <p>这一步是整条链路的钥匙：它是 SDL 的 Android 驱动唯一会被唤醒的时机，
     * 而它一旦被唤醒，FCL 的 SDL 直通（sdlEnabled）也会一并被打开。</p>
     */
    public boolean autoInitSdl = true;
    /** 调试日志。 */
    public boolean debug = false;

    // ---- 摇杆 ----
    /** 左摇杆死区（0~1，径向）。 */
    public float leftDeadzone = 0.15f;
    /** 右摇杆死区（0~1，径向）。 */
    public float rightDeadzone = 0.12f;
    /** 扳机死区（0~1，按行程比例）。 */
    public float triggerDeadzone = 0.10f;
    /** 视角满推速度（度/秒）。Minecraft 自己的鼠标灵敏度会再乘上去。 */
    public float lookSpeed = 260.0f;
    /** 是否反转右摇杆的上下（默认 false，交给 Minecraft 的「反转鼠标」处理）。 */
    public boolean invertLookY = false;
    /** 移动量缩放（0~1），1 表示满推即全速。 */
    public float moveSpeedScale = 1.0f;
    /**
     * 摇杆响应曲线指数（0.5~3，1 = 线性）。
     *
     * <p>大于 1 时小幅度推动更细腻（适合潜行搭桥、精细瞄准），代价是满推前的行程变「肉」；
     * 小于 1 时轻推就快（适合动作游戏）。只作用于摇杆的径向幅值，不影响方向。</p>
     */
    public float stickCurve = 1.0f;
    /**
     * 视角加速度指数（1~3，1 = 线性）。
     *
     * <p>大于 1 时轻推摇杆只有很小的视角位移、推满才快速转身 —— 这是大多数主机游戏的默认手感。</p>
     */
    public float lookAcceleration = 1.0f;

    // ---- 切换式（toggle）动作 ----
    /**
     * 潜行用「按一下切换」而不是「按住生效」。
     * 手柄玩家过一小时潜行搭桥，指头一直抠着 B 会废掉 —— PC 上的辅助功能也有同样选项。
     */
    public boolean toggleSneak = true;
    /** 疾跑用「按一下切换」而不是「按住生效」。 */
    public boolean toggleSprint = true;

    // ---- 界面（GUI）导航 ----
    /**
     * 用右摇杆模拟鼠标光标来操作各种界面（背包、箱子、设置……）。
     *
     * <p>安卓上没有鼠标，FCL 里只能用手指点；手柄玩家想在背包里整理物品就得放下手柄去戳屏幕。
     * 打开这个开关后，右摇杆推光标、<b>攻击键=左键、使用键=右键</b>，操作和 PC 上一致。</p>
     */
    public boolean guiCursor = true;
    /** 光标速度（GUI 像素/秒）。手机上 GUI 缩放很大，900 左右手感刚好。 */
    public float guiCursorSpeed = 900.0f;
    /** 光标十字的笔画粗细（像素，1~3）。手机上 1px 已经很细，2px 更醒目。 */
    public int cursorThickness = 1;
    /** 光标十字单侧臂长（像素，3~12）。 */
    public int cursorLength = 6;
    /**
     * 十字键在非容器界面里的语义：
     * <ul>
     *   <li>{@code cursor}（默认）—— 微调光标位置（每次 2px，按住加速）；</li>
     *   <li>{@code focus} —— 在界面控件之间跳焦点（按几何最近邻吸附，适合选项菜单）。</li>
     * </ul>
     */
    public String dpadMode = "cursor";

    // ---- 按键提示 ----
    /**
     * 打开界面时在<b>左下角 / 右下角</b>各叠一列竖排的「手柄按钮 → 现在能做什么」速查
     * （Controlify 式）。改过键位后提示自动跟随绑定表；游戏内 HUD 上也会显示。
     */
    public boolean showButtonHints = true;
    /**
     * 提示条的「详细程度」：
     * <ul>
     *   <li>{@code off} —— 完全不显示；</li>
     *   <li>{@code partial}（默认）—— 只提示「容易搞混的」按键（十字键、LB/RB、LT/RT、START 之类），
     *       常用的 A/B/摇杆不刷屏；</li>
     *   <li>{@code all} —— 把所有绑定都列出来。</li>
     * </ul>
     *
     * <p>为什么要分档：手机上屏幕就那么大，Minecraft 的 HUD 本来就挤，
     * 一次性列十几个按键会直接盖住血条和快捷栏；而 A=跳、摇杆=走路这种
     * 玩家玩两分钟就记住的东西，反复提示完全是噪音。</p>
     */
    public String hintMode = "partial";
    /**
     * 按键提示条的位置：
     * {@code bottom_both}（默认，左右两角贴底）、{@code bottom_left}、{@code bottom_right}、
     * {@code top_both}、{@code top_left}、{@code top_right}。
     *
     * <p>为什么做成可配：不同手机屏幕比例差别很大，底部可能压住物品栏/血条，
     * 顶部可能压住状态效果图标；玩家自己挪到不挡视线的一侧最省事。</p>
     */
    public String hintAnchor = "bottom_both";

    // ---- 震动 ----
    /** 按键/点击时给手柄一个短震动。 */
    public boolean rumbleEnabled = true;
    /** 震动强度（0~1）。 */
    public float rumbleStrength = 0.6f;
    /** 拾取物品时震一下。 */
    public boolean rumblePickup = true;
    /** 主手工具耐久低于 15% 时震一下（只提醒一次，换物品后重新武装）。 */
    public boolean rumbleDurability = true;
    /** 受伤时震一下。 */
    public boolean rumbleHurt = true;
    /** 饥饿值下降时震一下（默认关：饿得太频繁会烦）。 */
    public boolean rumbleHunger = false;

    // ---- 轴识别 ----
    /**
     * 自动识别轴位 + 摇杆中心校准。
     *
     * <p>连接时采样约 1.5 秒：静止在中点的轴判为摇杆，静止在最小值的轴判为扳机；
     * 同时记下每个摇杆的中点偏移，之后读轴时把它减掉（手柄用久了会漂移）。
     * 识别不自信时保持 {@code axisLX..axisRT} 的现有值不动。</p>
     */
    public boolean axisAutoDetect = true;
    /**
     * 视角/GUI 光标是否每帧用最新摇杆读数（而不是每 tick 的 20Hz 采样）。
     *
     * <p>所有 {@code SDL_GetJoystickAxis} 调用现在都跑在独立的轮询线程上（约 160Hz），
     * 客户端线程只是每帧拷一次数组快照，所以这项开着几乎没有成本，手感也最好；
     * 关掉后退回 tick 采样，视角最多滞后 50ms，表现为「起步一顿、停手还在滑」。</p>
     *
     * <p>曾经这里是「每帧直接 JNI 现读」，在安卓上会被 SDL 的 joystick 锁阻塞，
     * 把帧率压到个位数（就是那次锁 10 帧的根因），所以现在只影响「读哪份快照」，
     * 不再影响「是否调用 SDL」。</p>
     */
    public boolean liveAxis = true;
    /**
     * 忽略「外部模组把帧上限压到异常低」的限帧请求（默认开）。
     *
     * <p>本模组的 {@code RenderSystemMixin} 把 MC 的忙等自旋换成了真正睡眠
     * （FCL 的 {@code glfwWaitEventsTimeout} 是空实现）。副作用是：省电模组
     * （典型 FPS Reducer）的 {@code window.setFramerateLimit(10)} 从此真的生效，
     * 表现为「装上本模组就锁 10 帧、禁用就恢复」。开启本项后，若发现
     * 窗口上限与玩家设定不一致且请求值异常低，就跳过限帧、保持满帧，并打一次 warn 指路。</p>
     *
     * <p>只有想「真的让省电模组生效」时才需要关掉它。判定逻辑见
     * {@code com.fouryctrl.util.FrameGuard}。</p>
     */
    public boolean ignoreExternalFpsLimit = true;
    /**
     * 多久扫描一次设备表（单位：tick，1~100；实际换算成「tick × 50ms」交给轮询线程）。
     *
     * <p>只影响「有没有新手柄接进来」，不影响掉线检测（掉线是每 50ms 探一次
     * {@code SDL_JoystickConnected}，插拔手感即时）。扫描一次要枚举设备并
     * 逐个试开问轴数，所以别调得比 1 更小。</p>
     */
    public int joystickPollTicks = 10;

    // ---- 径向动作轮盘 ----
    /**
     * 按住 {@link #radialKey} 时屏幕中央弹出径向菜单，右摇杆指方向、松手执行。
     *
     * <p>为什么需要它：手柄按钮就十几个，还都被基础操作占满了；剩下那些「偶尔用一次」的
     * 动作只能挤到十字键上，而十字键在界面里又要做导航 —— 一个键两种身份，玩家记不住。
     * 轮盘把这些动作收进一个「按住才出现」的环，平时那枚按钮什么都不干。</p>
     */
    public boolean radialMenu = false;
    /** 触发轮盘的按钮 id（默认 BACK）。设成 none 等于关掉。 */
    public String radialKey = "BACK";
    /** 轮盘槽位（逗号分隔的动作 id，最多 8 个，从正上方开始顺时针排）。 */
    public String radialActions = "chat,perspective,player_list,hide_hud";

    // ---- 按设备保存的配置档 ----
    /**
     * 每只手柄一份「轴序号 + 绑定」的快照，键是 SDL 报上来的手柄名。
     *
     * <p>为什么按名字而不是 GUID：{@code SDL_GetJoystickGUID} 按值返回 16 字节结构体，
     * LWJGL 的 {@code JNI.invoke*} 没有返回结构体的变体（读出来必然是垃圾或崩溃），
     * 所以退一步用名字。同型号两个手柄会共用一份档，这在手机场景下够用。</p>
     *
     * <p>连接时若命中，会把 {@code axisLX..axisRT} 与 {@code bindings} 换成这份档的值，
     * 断线不还原（玩家下次插同一只还是这套）。用 {@code /4y savepad} 把当前设置存成档。</p>
     */
    public Map<String, DeviceProfile> deviceProfiles = new LinkedHashMap<>();

    /** 单只设备的配置档。字段与主配置同名，方便直接搬运。 */
    public static final class DeviceProfile {
        public int axisLX = 0;
        public int axisLY = 1;
        public int axisRX = 2;
        public int axisRY = 3;
        public int axisLT = 4;
        public int axisRT = 5;
        public Map<String, String> bindings = defaultBindings();
        public Map<String, String> keyBindings = new LinkedHashMap<>();
    }

    // ---- 轴序号（对着 SDL_GetJoystickAxis 的原始索引；-1 表示该轴不存在）----
    /**
     * 默认值 0..5 对应 FCL 的 SDLControllerManager.RangeComparator 排完序后的顺序：
     * X, Y, RX, RY, Z, RZ —— 也就是左摇杆 X/Y、右摇杆 X/Y、左扳机、右扳机。
     */
    public int axisLX = 0;
    public int axisLY = 1;
    public int axisRX = 2;
    public int axisRY = 3;
    public int axisLT = 4;
    public int axisRT = 5;

    // ---- 扳机绑定的动作 ----
    /** LT：默认攻击（鼠标左键），与 FCL 默认映射一致。 */
    public String triggerLeftAction = "attack";
    /** RT：默认使用（鼠标右键），与 FCL 默认映射一致。 */
    public String triggerRightAction = "use";

    // ---- 按键绑定：SDL 按钮名 -> 动作 id ----
    public Map<String, String> bindings = defaultBindings();

    /**
     * 额外绑定：SDL 按钮名 -> {@code KeyBinding} 的翻译键（如 {@code key.jump}）。
     *
     * <p>为什么要有这一层：{@code bindings} 只能映射到本模组自带的十来个动作，
     * 玩家想用手柄按钮去触发<b>别的模组</b>的功能（小地图、自动合成、投影……）就没路了。
     * 而 MC 里所有功能（含模组注册的）都挂在 {@code KeyBinding} 上，
     * {@code GameOptions.allKeys} 能一次拿到全部对象，所以这里存翻译键当 id：
     * 读档时再按翻译键把它解析回对象，配置文件里也不会出现对象引用。</p>
     *
     * <p>只在游戏内（无界面）转发：界面里十字键/按钮仍然只做导航，不会误触发。</p>
     */
    public Map<String, String> keyBindings = new LinkedHashMap<>();

    /**
     * 默认绑定对齐 FCL 自带的 GamepadMap（getDefaultGameMap），玩家从映射模式切过来不用重新学：
     * A=跳、B=丢出(Q)、X=背包(E)、Y=副手(F)、LB/RB=快捷栏左右（对应滚轮上下）、
     * LT=攻击(鼠标左键)、RT=使用(鼠标右键)、L3=疾跑（FCL 的 L3=左Ctrl）、R3=潜行（FCL 的 R3=左Shift）。
     * 十字键 FCL 原本映射的是一组开发者调试键（LSHIFT/O/J/K），对正常游玩没意义，留空。
     */
    public static Map<String, String> defaultBindings() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("SOUTH", "jump");            // A
        m.put("EAST", "drop");             // B
        m.put("WEST", "inventory");        // X
        m.put("NORTH", "swap_offhand");    // Y
        m.put("LEFT_SHOULDER", "hotbar_prev");   // LB
        m.put("RIGHT_SHOULDER", "hotbar_next");  // RB
        m.put("LEFT_STICK", "sprint");     // L3
        m.put("RIGHT_STICK", "sneak");     // R3
        m.put("DPAD_UP", "none");
        m.put("DPAD_DOWN", "none");
        m.put("DPAD_LEFT", "none");
        m.put("DPAD_RIGHT", "none");
        m.put("BACK", "none");
        m.put("START", "pause");
        m.put("GUIDE", "none");
        return m;
    }

    // ------------------------------------------------------------------

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("fouryctrl.json");
    }

    public static ModConfig load() {
        Path p = path();
        if (Files.exists(p)) {
            try {
                String json = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                ModConfig cfg = GSON.fromJson(json, ModConfig.class);
                if (cfg != null) {
                    migrate(cfg);
                    cfg.normalize();
                    FourYCtrl.LOG.info("已读取配置 {}", p);
                    return cfg;
                }
            } catch (IOException | JsonSyntaxException e) {
                FourYCtrl.LOG.warn("配置文件损坏，改用默认配置：{}", e.toString());
            }
        }
        ModConfig cfg = new ModConfig();
        cfg.normalize();
        cfg.save();
        return cfg;
    }

    /**
     * 跨版本迁移。v1 的默认绑定把潜行放在 B、疾跑放在 LB、快捷栏放在十字键上，
     * 和 FCL 玩家的肌肉记忆完全对不上；v2 起改成 FCL 风格。
     * 无法区分「旧默认」和「用户自定义」，所以直接整体覆盖 —— 旧配置本来就还没人调明白。
     */
    private static void migrate(ModConfig cfg) {
        if (cfg.configVersion < 2) {
            cfg.bindings = defaultBindings();
            cfg.triggerLeftAction = "attack";
            cfg.triggerRightAction = "use";
            cfg.configVersion = 2;
            FourYCtrl.LOG.info("配置从 v{} 迁移到 v2：按键绑定已重置为 FCL 风格默认值", cfg.configVersion - 1);
        }
    }

    public void save() {
        try {
            Path p = path();
            Files.createDirectories(p.getParent());
            Files.write(p, GSON.toJson(this).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            FourYCtrl.LOG.warn("写入配置失败：{}", e.toString());
        }
    }

    private void normalize() {
        if (bindings == null) {
            bindings = defaultBindings();
        }
        if (keyBindings == null) {
            keyBindings = new LinkedHashMap<>();
        }
        // 手改 JSON 可能留下空值/空串，编译前先剔除，避免每 tick 白查一遍。
        keyBindings.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null
                || e.getValue().isBlank());
        if (configVersion < 2) {
            migrate(this);
        }
        leftDeadzone = clamp01(leftDeadzone);
        rightDeadzone = clamp01(rightDeadzone);
        triggerDeadzone = clamp01(triggerDeadzone);
        moveSpeedScale = clamp01(moveSpeedScale);
        rumbleStrength = clamp01(rumbleStrength);
        if (lookSpeed <= 0f) {
            lookSpeed = 260.0f;
        }
        if (guiCursorSpeed < 100f) {
            guiCursorSpeed = 900.0f;
        }
        if (stickCurve < 0.5f) {
            stickCurve = 0.5f;
        }
        if (stickCurve > 3f) {
            stickCurve = 3f;
        }
        if (lookAcceleration < 1f) {
            lookAcceleration = 1f;
        }
        if (lookAcceleration > 3f) {
            lookAcceleration = 3f;
        }
        if (radialKey == null || radialKey.isBlank()) {
            radialKey = "BACK";
        }
        if (radialActions == null) {
            radialActions = "chat,perspective,player_list,hide_hud";
        }
        if (deviceProfiles == null) {
            deviceProfiles = new LinkedHashMap<>();
        }
        if (dpadMode == null || !DPAD_MODES.contains(dpadMode)) {
            dpadMode = "cursor";
        }
        if (joystickPollTicks < 1) {
            joystickPollTicks = 1;
        }
        if (joystickPollTicks > 100) {
            joystickPollTicks = 100;
        }
        cursorThickness = Math.max(1, Math.min(3, cursorThickness));
        cursorLength = Math.max(3, Math.min(12, cursorLength));
        if (hintAnchor == null || !HINT_ANCHORS.contains(hintAnchor)) {
            hintAnchor = "bottom_both";
        }
        if (hintMode == null || !HINT_MODES.contains(hintMode)) {
            hintMode = "partial";
        }
    }

    /**
     * 把当前轴序号与绑定存成「按设备名」的配置档。
     *
     * @return 是否写入成功（名字为空时不写）
     */
    public boolean saveDeviceProfile(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        DeviceProfile p = new DeviceProfile();
        p.axisLX = axisLX;
        p.axisLY = axisLY;
        p.axisRX = axisRX;
        p.axisRY = axisRY;
        p.axisLT = axisLT;
        p.axisRT = axisRT;
        p.bindings = new LinkedHashMap<>(bindings);
        p.keyBindings = new LinkedHashMap<>(keyBindings);
        deviceProfiles.put(name, p);
        save();
        return true;
    }

    /**
     * 连上某只设备时套用它之前存的档。
     *
     * @return 是否命中（没命中就继续用主配置里的值）
     */
    public boolean applyDeviceProfile(String name) {
        if (name == null || deviceProfiles == null) {
            return false;
        }
        DeviceProfile p = deviceProfiles.get(name);
        if (p == null) {
            return false;
        }
        axisLX = p.axisLX;
        axisLY = p.axisLY;
        axisRX = p.axisRX;
        axisRY = p.axisRY;
        axisLT = p.axisLT;
        axisRT = p.axisRT;
        if (p.bindings != null) {
            bindings = new LinkedHashMap<>(p.bindings);
        }
        if (p.keyBindings != null) {
            keyBindings = new LinkedHashMap<>(p.keyBindings);
        }
        return true;
    }

    /** 合法的提示条位置取值（顺序即设置界面里循环的顺序）。 */
    public static final java.util.List<String> HINT_ANCHORS = java.util.List.of(
            "bottom_both", "bottom_left", "bottom_right",
            "top_both", "top_left", "top_right");

    /** 循环切到下一个提示位置。 */
    public static String nextHintAnchor(String current) {
        int i = HINT_ANCHORS.indexOf(current);
        return HINT_ANCHORS.get((i < 0 ? 0 : i + 1) % HINT_ANCHORS.size());
    }

    /** 合法的提示档位（顺序即设置界面里循环的顺序：关 → 部分 → 全部）。 */
    public static final java.util.List<String> HINT_MODES = java.util.List.of("off", "partial", "all");

    /** 循环切到下一个提示档位。 */
    public static String nextHintMode(String current) {
        int i = HINT_MODES.indexOf(current);
        return HINT_MODES.get((i < 0 ? 0 : i + 1) % HINT_MODES.size());
    }

    /** 合法的十字键语义（顺序即设置界面里循环的顺序）。 */
    public static final java.util.List<String> DPAD_MODES = java.util.List.of("cursor", "focus");

    /** 循环切到下一个十字键语义。 */
    public static String nextDpadMode(String current) {
        int i = DPAD_MODES.indexOf(current);
        return DPAD_MODES.get((i < 0 ? 0 : i + 1) % DPAD_MODES.size());
    }

    private static float clamp01(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }
}
