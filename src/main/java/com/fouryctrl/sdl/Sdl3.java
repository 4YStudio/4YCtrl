package com.fouryctrl.sdl;

import com.fouryctrl.FourYCtrl;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.linux.DynamicLinkLoader;

import static org.lwjgl.system.linux.DynamicLinkLoader.RTLD_NOW;

/**
 * 进程内 SDL3 的极简裸绑定。
 *
 * <h2>为什么要自己 dlopen</h2>
 * FCL 的 libSDL3.so 只存在于 APK 的 nativeLibraryDir，Java 侧没有对应 jar，
 * 也没有任何 SDL3 的 Java 绑定可用（Controlify 用的 dev.isxander.sdl 需要 FFM 后端，
 * 而且要额外下发 .so）。所以我们用 LWJGL 自带的 {@link DynamicLinkLoader} 打开
 * 进程里已经加载的那一份，再用 {@link JNI} 直接按函数指针调用。
 *
 * <h2>为什么共享同一份 SDL 状态</h2>
 * FCL 的游戏 JVM（HotSpot）和启动器（ART）跑在同一个 Linux 进程里。
 * {@code dlopen("libSDL3.so")} 拿到的是同一个 so 句柄，因此 SDL 内部的
 * joystick 状态是两边共享的：ART 侧 FCL 的 SDL 直通把事件喂进 SDL，
 * 我们在 HotSpot 侧把它读出来。
 *
 * <h2>调用约定的实测结论</h2>
 * LWJGL 的 {@code JNI.invokeXxx}：<b>带 P 的变体最后一个 long 是函数地址；
 * 不带 P 的（invokeI / invokeZ / invokeV）第一个参数是函数地址。</b>
 * 这一点是在宿主机上用 gcc 编了一份返回可区分的测试 so 逐条跑出来的。
 */
public final class Sdl3 {
    private Sdl3() {
    }

    // SDL_InitFlags
    public static final int SDL_INIT_EVENTS = 0x00004000;
    public static final int SDL_INIT_JOYSTICK = 0x00000200;
    public static final int SDL_INIT_GAMEPAD = 0x00002000;
    /** SDL_HintPriority：OVERRIDE 优先级最高。 */
    private static final int SDL_HINT_OVERRIDE = 2;

    // 初始化（ensureReady）在客户端线程做，isReady()/status() 两边都会读、
    // 具体的读轴读键则在手柄轮询线程里跑，所以这三个字段必须 volatile。
    private static volatile boolean attempted;
    private static volatile boolean ok;
    private static long lib;

    /**
     * 一句话说明当前处在链路的哪一步，供设置界面直接显示。
     *
     * <p>安卓上翻 logcat 很痛苦，而「手柄未连接」这一句本身不区分原因：
     * 是 dlopen 失败？是没装在 FCL 里（没有代理）？还是 SDL 初始化失败？
     * 把原因挂在界面上，玩家一眼就能判断该改什么。</p>
     */
    private static volatile String status = "未初始化";

    private static long fnInit;
    private static long fnInitSubSystem;
    private static long fnWasInit;
    private static long fnQuitSubSystem;
    private static long fnGetError;
    private static long fnPumpEvents;
    private static long fnUpdateJoysticks;
    private static long fnGetJoysticks;
    private static long fnFree;
    private static long fnOpenJoystick;
    private static long fnCloseJoystick;
    private static long fnJoystickConnected;
    private static long fnNumAxes;
    private static long fnGetAxis;
    private static long fnNumButtons;
    private static long fnGetButton;
    private static long fnNumHats;
    private static long fnGetHat;
    private static long fnGetName;
    private static long fnGetType;
    private static long fnSetWindowSize;
    private static long fnSetHintWithPriority;
    private static long fnRumbleJoystick;
    private static long fnGetPowerInfo;

    public static boolean isReady() {
        return ok;
    }

    /** 当前链路状态的一句话描述，用于设置界面排错。 */
    public static String status() {
        return status;
    }

    /**
     * 丢掉上次的初始化结果，下次 {@link #ensureReady()} 重新走一遍完整流程。
     *
     * <p>为什么需要：{@link #ensureReady()} 是幂等的（只尝试一次），因为失败后每 tick
     * 重试只会刷日志。但真机上失败常是瞬时原因（手柄晚于游戏连上、FCL 侧 SDL2 装载
     * 慢了一步），玩家需要一个「重新检测」的动作，而不是重启游戏。</p>
     */
    public static synchronized void retry() {
        // 已拿到的 so 句柄留着：同一个 so 重复 dlopen 只是把引用计数加一，句柄不变，
        // 而符号地址本来就不用重新解析（FCL 的代理在进程内是固定的）。只重置「结论」。
        attempted = false;
        ok = false;
        status = "正在重新检测…";
    }

    /**
     * 打开 SDL3 并确保手柄子系统已初始化。
     *
     * @return true 表示后续可以直接读取手柄状态
     */
    public static synchronized boolean ensureReady() {
        if (attempted) {
            return ok;
        }
        attempted = true;
        try {
            if (lib == 0L) {
                lib = DynamicLinkLoader.dlopen("libSDL3.so", RTLD_NOW);
            }
            if (lib == 0L) {
                // 注意：此时 resolve() 还没跑，fnGetError 仍是 0。safeError() 内部会判 0 返回占位串，
                // 不会跳空地址（曾经的崩溃点）。
                status = "dlopen(\"libSDL3.so\") 失败：" + safeError();
                FourYCtrl.LOG.warn(status);
                return false;
            }
            if (!resolve()) {
                status = "libSDL3.so 缺少必要的 SDL 符号";
                return false;
            }
            // 注意：这里先不把 ok 置 true。ok 表示「SDL 调用可以放心发出去」，
            // 必须等手柄子系统真的初始化完（或确认已处于初始化状态）才成立，
            // 否则 autoInitSdl=false / InitSubSystem 失败时会出现「模组自认为就绪、
            // 手柄却完全没反应」的假就绪状态。
            FourYCtrl.LOG.info("SDL3 符号已解析（句柄 0x{}）", Long.toHexString(lib));

            // 必须在初始化手柄子系统之前设置：
            // SDL_InitJoysticks() 里会 SDL_AddHintCallback 读一次当前值。
            enableBackgroundJoyEvents();

            if (!proxyActive()) {
                status = "未检测到 FCL 的 SDL 初始化代理（不在 FCL 内，或 FCL 版本过旧）";
                FourYCtrl.LOG.error("未检测到 FCL 的 SDL 初始化代理，已跳过手柄子系统初始化。");
                FourYCtrl.LOG.error("这通常意味着：模组被装进了原版/其他启动器，或 FCL 版本过旧没有 sdl_hook。");
                FourYCtrl.LOG.error("为避免进程崩溃（SDL 的 Android 驱动在未装载时会解引用空 JavaVM），不继续初始化。");
                ok = false;
                return false;
            }
            if (FourYCtrl.config() != null && !FourYCtrl.config().autoInitSdl) {
                // 用户把 autoInitSdl 关了 —— 只读不初始化（适合 FCL 自己已经初始化好 SDL 的场景）。
                // 这种情况仍然要「能读」：FCL 若已初始化好子系统，读轴/读键是安全的。
                // 但只有 SDL_WasInit 说 JOYSTICK 已经起来了，才敢置 ok。
                status = "autoInitSdl=false，未初始化手柄子系统";
                FourYCtrl.LOG.info("autoInitSdl=false，跳过 SDL 手柄子系统初始化，只读取当前状态");
                ok = joystickSubsystemReady();
                if (!ok) {
                    status = "autoInitSdl=false，且 SDL 手柄子系统尚未初始化";
                }
                return ok;
            }
            ok = initJoystickSubsystem();
            return ok;
        } catch (Throwable t) {
            status = "SDL3 初始化异常：" + t;
            FourYCtrl.LOG.warn("SDL3 初始化异常：{}", t.toString());
            return false;
        }
    }

    private static boolean resolve() {
        fnInit = sym("SDL_Init");
        fnInitSubSystem = sym("SDL_InitSubSystem");
        fnWasInit = sym("SDL_WasInit");
        fnQuitSubSystem = sym("SDL_QuitSubSystem");
        fnGetError = sym("SDL_GetError");
        fnPumpEvents = sym("SDL_PumpEvents");
        fnUpdateJoysticks = sym("SDL_UpdateJoysticks");
        fnGetJoysticks = sym("SDL_GetJoysticks");
        fnFree = sym("SDL_free");
        fnOpenJoystick = sym("SDL_OpenJoystick");
        fnCloseJoystick = sym("SDL_CloseJoystick");
        fnJoystickConnected = sym("SDL_JoystickConnected");
        fnNumAxes = sym("SDL_GetNumJoystickAxes");
        fnGetAxis = sym("SDL_GetJoystickAxis");
        fnNumButtons = sym("SDL_GetNumJoystickButtons");
        fnGetButton = sym("SDL_GetJoystickButton");
        fnNumHats = sym("SDL_GetNumJoystickHats");
        fnGetHat = sym("SDL_GetJoystickHat");
        fnGetName = sym("SDL_GetJoystickName");
        fnGetType = sym("SDL_GetJoystickType");
        // 仅用于探测 FCL 的 dlsym 代理是否生效，不参与实际功能
        fnSetWindowSize = sym("SDL_SetWindowSize");
        fnSetHintWithPriority = sym("SDL_SetHintWithPriority");
        fnRumbleJoystick = sym("SDL_RumbleJoystick");
        fnGetPowerInfo = sym("SDL_GetJoystickPowerInfo");

        // fnWasInit 必须算必需符号：initJoystickSubsystem() 用它做幂等判断，
        // 缺了它就会 invokeI(0L) 跳空地址。
        return fnInitSubSystem != 0 && fnGetJoysticks != 0 && fnOpenJoystick != 0
                && fnGetAxis != 0 && fnGetButton != 0 && fnGetError != 0
                && fnWasInit != 0 && fnCloseJoystick != 0 && fnFree != 0;
    }

    private static long sym(String name) {
        long p = DynamicLinkLoader.dlsym(lib, name);
        if (p == 0L && FourYCtrl.config() != null && FourYCtrl.config().debug) {
            FourYCtrl.LOG.debug("SDL3 缺少符号 {}", name);
        }
        return p;
    }

    // ---------------------------------------------------------------- init

    /**
     * 探测当前 {@code dlsym} 是否落在 FCL 的代理上。
     *
     * <p>原理：FCL 的 {@code customDlsym}（bytehook 全局 hook，见
     * {@code FoldCraftLauncher/FCL/src/main/jni/native_hooks/sdl_dlopen_hook.c}）
     * 对任意符号都会问一次 {@code sdlDlsymProxy(symbol, real)}，命中白名单就换成代理。
     * {@code SDL_SetWindowSize} 恰好在白名单里，而它的代理实现是
     * {@code (void)window; (void)w; (void)h; return true;} —— 恒返回 true。
     * 真实实现在参数非法时返回 false（Android 上根本不支持改窗口尺寸）。</p>
     *
     * <p>因此 {@code SDL_SetWindowSize(NULL, 0, 0)} 返回 true ⇒ 代理生效。
     * 这一步没有副作用：代理什么都不做，真实现也只是校验失败。</p>
     */
    private static boolean proxyActive() {
        if (fnSetWindowSize == 0L) {
            FourYCtrl.LOG.warn("找不到 SDL_SetWindowSize，无法探测代理链");
            return false;
        }
        boolean r;
        try {
            // SDL_SetWindowSize(SDL_Window*, int, int)
            r = JNI.invokePZ(0L, 0, 0, fnSetWindowSize);
        } catch (Throwable t) {
            FourYCtrl.LOG.warn("探测 SDL_SetWindowSize 抛异常：{}", t.toString());
            return false;
        }
        if (FourYCtrl.config() != null && FourYCtrl.config().debug) {
            FourYCtrl.LOG.debug("代理探测 SDL_SetWindowSize(NULL,0,0) = {}", r);
        }
        return r;
    }

    /**
     * 关掉 SDL 的「无键盘焦点就不发手柄事件」过滤。
     *
     * <p>这是真机能否读到数据的隐藏门槛，来自 {@code sdl_joystick.c} 的
     * {@code SDL_PrivateJoystickShouldIgnoreEvent()}：</p>
     * <pre>
     * if (SDL_HasWindows() &amp;&amp; SDL_GetKeyboardFocus() == NULL) return true;
     * </pre>
     * <p>一旦返回 true，{@code SDL_SendJoystickButton} 会直接丢掉「按下」
     * （只放行松开），{@code SDL_SendJoystickAxis} 只放行回中方向的变动、
     * 丢掉推离中心的变动，{@code SDL_SendJoystickHat} 丢掉所有非回中值。
     * 结果就是：手柄动了，但 {@code SDL_GetJoystickAxis()} 永远返回 0。</p>
     *
     * <p>FCL 是「SDL 直通」模式，MotionEvent 走的是 SDL 自己的事件处理，
     * 而它的 SDL 窗口不一定拿得到键盘焦点，所以必须显式打开这个 hint。
     * 用 OVERRIDE 优先级，避免被环境变量压掉。</p>
     */
    private static void enableBackgroundJoyEvents() {
        if (fnSetHintWithPriority == 0L) {
            FourYCtrl.LOG.warn("找不到 SDL_SetHintWithPriority，跳过后台手柄事件开关");
            return;
        }
        java.nio.ByteBuffer name = null;
        java.nio.ByteBuffer value = null;
        try {
            name = MemoryUtil.memUTF8("SDL_JOYSTICK_ALLOW_BACKGROUND_EVENTS", true);
            value = MemoryUtil.memUTF8("1", true);
            boolean r = JNI.invokePPPZ(MemoryUtil.memAddress(name), MemoryUtil.memAddress(value),
                    SDL_HINT_OVERRIDE, fnSetHintWithPriority);
            if (r) {
                FourYCtrl.LOG.info("已开启 SDL 后台手柄事件（不受键盘焦点影响）");
            } else {
                FourYCtrl.LOG.warn("设置 SDL_JOYSTICK_ALLOW_BACKGROUND_EVENTS 失败：{}", safeError());
            }
        } catch (Throwable t) {
            FourYCtrl.LOG.warn("设置后台手柄事件 hint 抛异常：{}", t.toString());
        } finally {
            if (name != null) {
                MemoryUtil.memFree(name);
            }
            if (value != null) {
                MemoryUtil.memFree(value);
            }
        }
    }

    /**
     * 初始化 SDL 的手柄子系统。
     *
     * <p>这一步是整个方案的关键。FCL 的 {@code SdlBridge.initializeControllerSubsystems()}
     * 从来没有被任何代码调用过，所以 SDL 的手柄子系统一直是未初始化状态，
     * 结果就是 {@code SDLControllerManager.mJoysticks} 永远为空，
     * 直通过来的 MotionEvent 在 {@code handleMotionEvent()} 里被静默丢弃。</p>
     *
     * <p>另外：{@code SDL_InitSubSystem} 在 FCL 的 dlsym 代理名单里，
     * 我们拿到的是 {@code custom_SDL_InitSubSystem_Func}，它会先通知启动器
     * 完成 SDL3/SDL2 的装载并置 {@code SdlBridge.sdlEnabled = true}，
     * 这让 FCL 的直通链路真正开始转发手柄事件。
     * 我们故意用它而不是 {@code SDL_Init}：只有走代理，才能保证
     * 启动器侧先完成 {@code System.loadLibrary("SDL3")}，从而让 SDL 的
     * Android 驱动在回调 Java 时有一个可用的 JavaVM。</p>
     *
     * <p>另一个坑：{@code SDL_InitSubSystem(SDL_INIT_JOYSTICK)} 内部只会
     * {@code SDL_InitSubSystem(SDL_INIT_EVENTS)}（见 {@code sdl_joystick.c} 的
     * {@code SDL_InitJoysticks()}），并不会顺带把 VIDEO 初始化起来。
     * FCL 在直通模式下已经建了 SDL 窗口，所以这里不需要也不应该碰 VIDEO。</p>
     */
    /** SDL_WasInit 问一句：手柄子系统当前是否已初始化。符号缺失时按「否」处理。 */
    private static boolean joystickSubsystemReady() {
        if (fnWasInit == 0L) {
            return false;
        }
        try {
            return (JNI.invokeI(SDL_INIT_JOYSTICK, fnWasInit) & SDL_INIT_JOYSTICK) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * @return 手柄子系统最终是否可用（决定 {@link #ok}）
     */
    private static boolean initJoystickSubsystem() {
        int flags = SDL_INIT_JOYSTICK | SDL_INIT_GAMEPAD | SDL_INIT_EVENTS;
        int already;
        try {
            already = fnWasInit == 0L ? 0 : JNI.invokeI(flags, fnWasInit);
        } catch (Throwable t) {
            already = 0;
        }
        if ((already & SDL_INIT_JOYSTICK) != 0) {
            status = "手柄子系统已处于初始化状态";
            FourYCtrl.LOG.info("SDL 手柄子系统已处于初始化状态");
            return true;
        }
        boolean r;
        try {
            r = JNI.invokeZ(flags, fnInitSubSystem);
        } catch (Throwable t) {
            status = "SDL_InitSubSystem 抛异常：" + t;
            FourYCtrl.LOG.warn("SDL_InitSubSystem 抛异常：{}", t.toString());
            return false;
        }
        if (!r) {
            status = "SDL_InitSubSystem 失败：" + safeError();
            FourYCtrl.LOG.warn("SDL_InitSubSystem({}) 失败：{}", flags, safeError());
            return false;
        }
        status = "手柄子系统已就绪";
        FourYCtrl.LOG.info("SDL 手柄子系统初始化成功（flags=0x{}）", Integer.toHexString(flags));
        return true;
    }

    /**
     * 震动：{@code SDL_RumbleJoystick(SDL_Joystick*, Uint16 low, Uint16 high, Uint32 ms)}。
     *
     * <p>Android 后端把震动映射到系统的 Vibrator（见 so 里的
     * {@code SDL.joystick.cap.rumble} / {@code Rumble failed, device disconnected} 等字符串），
     * 所以只要手柄本身支持震动、且 FCL 的 SDL 已初始化，这个调用就能让手机侧的手柄震起来。</p>
     *
     * <p>返回 false 一般意味着「设备不支持震动」或「句柄已失效」，两种情况都不该抛给调用方。</p>
     */
    public static boolean rumble(long js, int low, int high, int durationMs) {
        if (!ok || js == 0L || fnRumbleJoystick == 0) {
            return false;
        }
        try {
            // Uint16 参数：Java int 高位是垃圾，ABI 上被调方虽只读低 16 位，
            // 但显式截断更稳妥（armv7 等 32 位 ABI 上参数是原样入栈的）。
            return JNI.invokePZ(js, low & 0xFFFF, high & 0xFFFF, durationMs, fnRumbleJoystick);
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean hasRumble() {
        return fnRumbleJoystick != 0L;
    }

    /**
     * 手柄电量百分比；未知/不支持时返回 -1。
     *
     * <p>{@code SDL_PowerState SDL_GetJoystickPowerInfo(SDL_Joystick*, int *percent)}：
     * 返回值是枚举（-1 = 未知、0 = 未知电源、1 = 用电池、2 = 充电中、3 = 已充满…），
     * 电量通过第二个出参写出。我们把「用电池」类的电量换算成百分比，其它情况返回 -1。</p>
     */
    public static int powerPercent(long js) {
        if (!ok || js == 0L || fnGetPowerInfo == 0L) {
            return -1;
        }
        long ptr = MemoryUtil.nmemAlloc(4);
        try {
            MemoryUtil.memPutInt(ptr, -1);
            int state = JNI.invokePPI(js, ptr, fnGetPowerInfo);
            int percent = MemoryUtil.memGetInt(ptr);
            // SDL_POWERSTATE_ON_BATTERY = 1, CHARGING = 2, CHARGED = 3
            if (state >= 1 && state <= 3 && percent >= 0 && percent <= 100) {
                return percent;
            }
            return -1;
        } catch (Throwable t) {
            return -1;
        } finally {
            MemoryUtil.nmemFree(ptr);
        }
    }

    /** 最长读 256 字节：错误的来源可能是栈上的定长缓冲区，不保证有终止符。 */
    private static final int MAX_CSTR = 256;

    /**
     * 读一个 C 字符串，但<b>只读到 NUL 为止</b>。
     *
     * <p>以前这里是 {@code MemoryUtil.memUTF8(p, MAX_CSTR)}，那只看了前 256 字节、
     * 完全无视终止符 —— 名字只有 16 字节时，后面 240 字节的堆内容也一起被解码出来，
     * 于是界面显示成 {@code Gamesir-X5 Lite9rM1crLcdJ(dJ(dJ(dJ(odel} 这种
     * 「真名字 + 一段别人的内存」。SDL 的名字都是 NUL 结尾的，读到终止符就停。</p>
     *
     * <p>停止条件三选一：遇到 NUL、读满 {@code MAX_CSTR}、遇到非 UTF-8 的坏字节序列
     * （乱码驱动会塞进 GBK 之类的东西，早停能避免把后续字节拼成更多垃圾）。</p>
     */
    private static String readCString(long p) {
        if (p == 0L) {
            return "";
        }
        byte[] buf = new byte[MAX_CSTR];
        int n = 0;
        while (n < MAX_CSTR) {
            byte b = MemoryUtil.memGetByte(p + n);
            if (b == 0) {
                break;
            }
            buf[n++] = b;
        }
        if (n == 0) {
            return "";
        }
        // 用 STRICT 解码：非法字节序列抛异常 → 直接返回空串，交给调用方回退「手柄 #n」。
        // 这样宁可显示实例号，也不会显示半截乱码。
        try {
            return java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(buf, 0, n))
                    .toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            return "";
        }
    }

    /**
     * 把 SDL 给回来的字符串洗成「一定能画出来」的形态。
     *
     * <p>安卓上不少驱动上报的手柄名是 GBK/Big5 字节流或者干脆乱码，
     * {@link MemoryUtil#memUTF8} 解码后会留下 {@code U+FFFD}、NUL、各种控制字符，
     * 原版字体里没有字形，就渲染成一排虚线方框（玩家报障的「NVL/NUL 方框」）。
     * 这里做确定性过滤：</p>
     * <ul>
     *   <li>剔除 C0/C1 控制字符、软连字符 {@code U+00AD}、零宽字符；</li>
     *   <li>剔除私有使用区（{@code U+E000..U+F8FF}、补充私有区 {@code U+F0000..U+10FFFF}）
     *       与替换符 {@code U+FFFD}、{@code U+FFFE/U+FFFF} 非字符；</li>
     *   <li>把连续空白压成一个空格，去掉首尾空白。</li>
     * </ul>
     *
     * <p><b>必须是确定性的</b>：{@code GamepadManager.onDeviceChanged} 拿这个字符串
     * 当 {@code ModConfig.applyDeviceProfile(name)} 的 key，同型号手柄每次连着都必须
     * 洗出同一个名字，否则「每手柄独立配置档」会时中时不中。</p>
     */
    static String sanitize(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        boolean lastSpace = false;
        for (int i = 0; i < raw.length(); ) {
            int cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == 0xAD || cp == 0x200B || cp == 0x200C || cp == 0x200D || cp == 0xFEFF) {
                continue;
            }
            if (cp == 0xFFFD || cp == 0xFFFE || cp == 0xFFFF) {
                continue;
            }
            if (cp < 0x20 || (cp >= 0x7F && cp <= 0x9F)) {
                continue;
            }
            if ((cp >= 0xE000 && cp <= 0xF8FF) || cp >= 0xF0000) {
                continue;
            }
            if (Character.isWhitespace(cp) || cp == 0x00A0) {
                if (!lastSpace && sb.length() > 0) {
                    sb.append(' ');
                    lastSpace = true;
                }
                continue;
            }
            lastSpace = false;
            sb.appendCodePoint(cp);
            if (sb.length() >= 64) {
                // 名字只是一行抬头，超长的直接截断（也避免异常驱动灌进来一大串垃圾）。
                break;
            }
        }
        int end = sb.length();
        while (end > 0 && sb.charAt(end - 1) == ' ') {
            end--;
        }
        sb.setLength(end);
        return sb.toString();
    }

    public static String safeError() {
        if (fnGetError == 0L) {
            // dlopen 失败时 resolve() 都还没跑，这里必须提前返回，否则 invokeP(0L) 跳空地址。
            return "(SDL_GetError 未解析)";
        }
        try {
            long p = JNI.invokeP(fnGetError);
            return p == 0L ? "(null)" : sanitize(readCString(p));
        } catch (Throwable t) {
            return "(unavailable: " + t + ")";
        }
    }

    // ---------------------------------------------------------------- events

    public static void pumpEvents() {
        if (ok && fnPumpEvents != 0) {
            JNI.invokeV(fnPumpEvents);
        }
    }

    public static void updateJoysticks() {
        if (ok && fnUpdateJoysticks != 0) {
            JNI.invokeV(fnUpdateJoysticks);
        }
    }

    /** 返回当前所有手柄的实例 id；没有则返回空数组。 */
    public static int[] getJoystickIds() {
        if (!ok || fnGetJoysticks == 0 || fnFree == 0) {
            return EMPTY;
        }
        long countPtr = MemoryUtil.nmemAlloc(4);
        long arr = 0L;
        try {
            arr = JNI.invokePP(countPtr, fnGetJoysticks);
            int count = MemoryUtil.memGetInt(countPtr);
            // 上限保护：count 来自被调方写回的 int，异常驱动写脏时不能直接拿去分配/循环读取。
            if (arr == 0L || count <= 0 || count > MAX_JOYSTICKS) {
                return EMPTY;
            }
            int[] ids = new int[count];
            for (int i = 0; i < count; i++) {
                ids[i] = MemoryUtil.memGetInt(arr + (long) i * 4L);
            }
            return ids;
        } catch (Throwable t) {
            return EMPTY;
        } finally {
            if (arr != 0L) {
                try {
                    JNI.invokePV(arr, fnFree);
                } catch (Throwable ignored) {
                }
            }
            MemoryUtil.nmemFree(countPtr);
        }
    }

    private static final int[] EMPTY = new int[0];
    /** 合理的手柄数量上限（SDL 自己也不会报到这个量级）。 */
    private static final int MAX_JOYSTICKS = 32;

    // ---------------------------------------------------------------- joystick

    public static long openJoystick(int instanceId) {
        if (!ok || fnOpenJoystick == 0L) {
            return 0L;
        }
        try {
            // SDL_JoystickID 是 32 位；以 long 传入时低 32 位正是它，
            // aarch64 上被调方只读 w0，因此这样传是安全的。
            return JNI.invokePP(instanceId & 0xFFFFFFFFL, fnOpenJoystick);
        } catch (Throwable t) {
            return 0L;
        }
    }

    public static void closeJoystick(long js) {
        if (ok && js != 0L && fnCloseJoystick != 0) {
            try {
                JNI.invokePV(js, fnCloseJoystick);
            } catch (Throwable ignored) {
            }
        }
    }

    public static boolean joystickConnected(long js) {
        if (!ok || js == 0L || fnJoystickConnected == 0) {
            return false;
        }
        try {
            // bool SDL_JoystickConnected(SDL_Joystick*)
            return JNI.invokePZ(js, fnJoystickConnected);
        } catch (Throwable t) {
            return false;
        }
    }

    public static String joystickName(long js) {
        if (!ok || js == 0L || fnGetName == 0) {
            return "";
        }
        try {
            long p = JNI.invokePP(js, fnGetName);
            // 名字可能含驱动上报的非 UTF-8 字节 / 控制字符：只读到 NUL 再洗，
            // 否则会把名字后面的堆内存也当名字读出来（见 readCString 的注释）。空串由调用方回退。
            return p == 0L ? "" : sanitize(readCString(p));
        } catch (Throwable t) {
            return "";
        }
    }

    public static int joystickType(long js) {
        if (!ok || js == 0L || fnGetType == 0) {
            return -1;
        }
        try {
            return JNI.invokePI(js, fnGetType);
        } catch (Throwable t) {
            return -1;
        }
    }

    public static int numAxes(long js) {
        if (!ok || js == 0L || fnNumAxes == 0) {
            return 0;
        }
        try {
            return JNI.invokePI(js, fnNumAxes);
        } catch (Throwable t) {
            return 0;
        }
    }

    public static int numButtons(long js) {
        if (!ok || js == 0L || fnNumButtons == 0) {
            return 0;
        }
        try {
            return JNI.invokePI(js, fnNumButtons);
        } catch (Throwable t) {
            return 0;
        }
    }

    public static int numHats(long js) {
        if (!ok || js == 0L || fnNumHats == 0) {
            return 0;
        }
        try {
            return JNI.invokePI(js, fnNumHats);
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 读一个轴，归一化到 [-1, 1]。
     *
     * <p>SDL3 的返回类型是 Sint16(-32768..32767)。LWJGL 没有
     * {@code short f(void*, int)} 这个组合，所以我们按 {@code int f(void*, int)} 取，
     * 再自己截断低 16 位并做符号扩展 —— ABI 只保证 16 位有效，高位是垃圾。</p>
     */
    public static float axis(long js, int index) {
        if (!ok || js == 0L || fnGetAxis == 0) {
            return 0f;
        }
        try {
            int raw = JNI.invokePI(js, index, fnGetAxis);
            short v = (short) (raw & 0xFFFF);
            return v / 32767f;
        } catch (Throwable t) {
            return 0f;
        }
    }

    public static boolean button(long js, int index) {
        if (!ok || js == 0L || fnGetButton == 0) {
            return false;
        }
        try {
            return JNI.invokePZ(js, index, fnGetButton);
        } catch (Throwable t) {
            return false;
        }
    }

    public static int hat(long js, int index) {
        if (!ok || js == 0L || fnGetHat == 0) {
            return 0;
        }
        try {
            return JNI.invokePI(js, index, fnGetHat) & 0xFF;
        } catch (Throwable t) {
            return 0;
        }
    }

    public static void quitSubSystem(int flags) {
        if (ok && fnQuitSubSystem != 0) {
            try {
                JNI.invokeV(flags, fnQuitSubSystem);
            } catch (Throwable ignored) {
            }
        }
    }
}
