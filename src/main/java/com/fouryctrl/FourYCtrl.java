package com.fouryctrl;

import com.fouryctrl.config.ModConfig;
import com.fouryctrl.input.GamepadManager;
import com.fouryctrl.util.Platform;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 4YCtrl —— 让 FCL（Fold Craft Launcher）的「SDL 直通」模式下的真实手柄驱动 Minecraft。
 *
 * 设计要点（为什么这么做，见 README）：
 * 1. 完全不碰 FCL 本体，也不走 FCL 的 GLFW 假手柄通道；
 * 2. 模组在游戏 JVM 内用自己的 SDL3 绑定（LWJGL DynamicLinkLoader + JNI 裸调用）打开
 *    进程内已经存在的 libSDL3.so；
 * 3. 关键的一步是调用 SDL_InitSubSystem(JOYSTICK|GAMEPAD|EVENTS)。FCL 自带的
 *    SdlBridge.initializeControllerSubsystems() 从未被任何代码调用，所以 SDL 的手柄子系统
 *    一直是未初始化状态 —— 于是「SDL 直通」模式下 FCL 转发过来的 MotionEvent 到了
 *    SDLControllerManager.handleMotionEvent() 时，设备表是空的，全部被丢弃。
 *    我们把这一环补上，FCL 原有的直通链路就活了。
 *    用 SDL_InitSubSystem 而不是 SDL_Init 的原因：只有前者在 FCL 的 dlsym 代理白名单里
 *    （FoldCraftLauncher/FCL/src/main/jni/native_hooks/sdl_hook.c 的 sdlDlsymProxy），
 *    会先通知启动器 System.loadLibrary("SDL3")，让 SDL 的 Android 驱动拿到可用的 JavaVM；
 *    直接调真实现会因为没有 JavaVM 而在 JNI 回调处崩溃。
 * 4. 模组自己每 tick 从 SDL 读模拟量（不是 FCL 映射模式那种 8 方向量化），
 *    因此能直接复用 Minecraft 自己的灵敏度/平滑/反转 Y。
 */
public class FourYCtrl implements ClientModInitializer {
    public static final String MOD_ID = "fouryctrl";
    public static final Logger LOG = LoggerFactory.getLogger("4YCtrl");

    private static ModConfig config;
    private static GamepadManager gamepad;

    public static ModConfig config() {
        return config;
    }

    public static GamepadManager gamepad() {
        return gamepad;
    }

    /**
     * 诊断日志开关（F9 / {@code cfg.debug}）。
     *
     * <p>模组里那些一次性的排查日志（HUD 提示是否真的走到画字等）只在它打开时输出，
     * 免得正常玩家的 latest.log 里混进一堆 {@code [diag]}。</p>
     */
    public static boolean debugOn() {
        return config != null && config.debug;
    }

    @Override
    public void onInitializeClient() {
        config = ModConfig.load();
        gamepad = new GamepadManager(config);
        Keybinds.register();
        DiagCommand.register();

        if (!Platform.isAndroid()) {
            LOG.warn("4YCtrl: 当前不是 Android 环境（{}），手柄直通已停用，仅用于开发调试。",
                    Platform.describe());
        } else {
            LOG.info("4YCtrl: Android 环境已识别（{}），准备接管 SDL 直通手柄。", Platform.describe());
        }

        // gamepad.tick 只做「从 SDL 抓一帧状态」，真正的注入在 Mixin 里按 MC 的 tick 顺序落点。
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // 模组自己的 tick 一定要包起来：任何 Throwable 冒泡出去都会打断本 tick
            // 其余监听器（其它模组、原版逻辑），而且会每 tick 重复刷屏。
            // 这里吞掉并降级为一条日志，等于「手柄这一 tick 失效，游戏照常跑」。
            try {
                Keybinds.tick(client);
                gamepad.endTick(client);
            } catch (Throwable t) {
                LOG.warn("4YCtrl: tick 异常（已跳过本 tick）", t);
            }
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> gamepad.close());
    }

    /**
     * 在当前界面之上画软件光标（由 {@code ScreenMixin} 在 renderWithTooltip 尾部调）。
     *
     * <p>为什么不画在 Screen 里：我们不知道每个界面什么时候画完自己；从 Mixin 的
     * TAIL 注入点调过来，光标永远在最上层。安卓触屏本身没有系统光标可画，
     * 手柄玩家需要这个标记才知道光标在哪。</p>
     *
     * <p>形状是<b>实心十字</b>（一个加号）：横臂与竖臂在中心直接交叉，没有缺口。
     * 之前画的是中心留空的四条臂，看得见「框」，但落点那一个像素反而是空的——
     * 想点准一个 16×16 物品槽的边角时反而没有参考点。实心十字的中心像素就是命中点。</p>
     *
     * <p>粗细（{@code cursorThickness}，1~3px）与臂长（{@code cursorLength}，3~12px）
     * 都能在设置界面里调；深色那层是偏 1px 的影子，所以不论调多粗轮廓都很干净。</p>
     */
    public static void drawGuiCursor(DrawContext context) {
        GamepadManager gp = gamepad;
        if (gp == null || !gp.isCursorVisible()) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.currentScreen == null || client.getWindow() == null) {
            return;
        }
        int x = (int) Math.round(gp.cursorX());
        int y = (int) Math.round(gp.cursorY());

        // 深色描边用「向外扩 1px 的同形状十字」，而不是整体偏移 (1,1)：
        // 偏移画法会让深色只在右下露出来，视觉重心偏右下，看着就不是中心对称的。
        crosshair(context, x, y, 0xFF000000, 1);
        // 白色本体。
        crosshair(context, x, y, 0xFFFFFFFF, 0);
    }

    /**
     * 画一个实心十字；{@code pad} = 向外扩的像素数（画描边用）。
     *
     * <p>几何：横臂是厚 t、以 (x,y) 为几何中心的矩形，竖臂同理，两者在中心重叠成加号。
     * {@code o = (t-1)/2} 是把 t 像素的厚度居中到 (x,y) 的偏移量；臂的末端写
     * {@code x+arm+1}（不是 {@code x+arm}）是因为 fill 的边界是开区间——这样左右、
     * 上下各覆盖 arm+1 个像素，严格中心对称。</p>
     *
     * <p>旧写法用 {@code half = t/2}（t=1 时 half=0）且末端写 {@code x+arm}，导致
     * 右臂和下臂各多出 1px，十字整体偏向右下。</p>
     */
    private static void crosshair(DrawContext context, int x, int y, int color, int pad) {
        ModConfig cfg = config;
        int arm = cfg == null ? 6 : cfg.cursorLength;
        int t = cfg == null ? 1 : cfg.cursorThickness;
        int o = (t - 1) / 2;
        // 横臂：x 方向 -arm..+arm（含），y 方向厚 t 居中
        context.fill(x - arm - pad, y - o - pad, x + arm + 1 + pad, y - o + t + pad, color);
        // 竖臂：y 方向 -arm..+arm（含），x 方向厚 t 居中
        context.fill(x - o - pad, y - arm - pad, x - o + t + pad, y + arm + 1 + pad, color);
    }
}
