package com.fouryctrl;

import com.fouryctrl.config.ModConfig;
import com.fouryctrl.input.Action;
import com.fouryctrl.input.Button;
import com.fouryctrl.input.GamepadManager;
import com.fouryctrl.sdl.Sdl3;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.Map;

/**
 * {@code /4y diag} —— 一条命令把整条链路的状态打出来。
 *
 * <p>为什么需要它：这个模组能出问题的地方太多（libSDL3.so 没加载上 / FCL 代理没拦住
 * dlsym / InitSubSystem 失败 / 设备表为空 / 手柄没注册成 joystick / 轴序号配错），
 * 而安卓上拿日志要连 adb。把状态摊在游戏聊天栏里，玩家截图就能定位断点。</p>
 *
 * <p>命令走 Fabric 的<b>客户端</b>命令 API（{@code ClientCommandRegistrationCallback}），
 * 因为是纯客户端模组，不能注册服务端命令；也正因为是客户端命令，单机/联机都能用。</p>
 */
public final class DiagCommand {
    private DiagCommand() {
    }

    private static final String PREFIX = "§b[4YCtrl] ";

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal("4y")
                    .then(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal("diag")
                            .executes(DiagCommand::run))
                    .then(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal("perf")
                            .executes(ctx -> {
                                FabricClientCommandSource src = ctx.getSource();
                                com.fouryctrl.util.Perf.enable();
                                line(src, "===== 4YCtrl 耗时探针 =====", Formatting.AQUA);
                                for (String s : com.fouryctrl.util.Perf.snapshot()) {
                                    line(src, s, Formatting.GRAY);
                                }
                                line(src, "（默认已关，无聊时可关掉；要真数据就等 2 秒再执行一次）",
                                        Formatting.DARK_GRAY);
                                return 1;
                            }))
                    .then(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal("off")
                            .executes(ctx -> {
                                com.fouryctrl.util.Perf.disable();
                                line(ctx.getSource(), "耗时探针已关闭", Formatting.GRAY);
                                return 1;
                            }))
                    .then(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal("rumble")
                            .executes(ctx -> {
                                GamepadManager gp = FourYCtrl.gamepad();
                                if (gp == null || !gp.isActive()) {
                                    reply(ctx, "手柄未激活，无法震动", Formatting.RED);
                                    return 0;
                                }
                                boolean ok = gp.testRumble();
                                reply(ctx, ok ? "已发送震动" : "震动失败（手柄可能不支持）",
                                        ok ? Formatting.GREEN : Formatting.RED);
                                return 1;
                            }))
                    .then(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal("nextpad")
                            .executes(ctx -> {
                                GamepadManager gp = FourYCtrl.gamepad();
                                if (gp == null) {
                                    return 0;
                                }
                                gp.cycleJoystick(net.minecraft.client.MinecraftClient.getInstance(), 1);
                                reply(ctx, "已切换到下一个手柄", Formatting.GREEN);
                                return 1;
                            }))
                    .then(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal("savepad")
                            .executes(ctx -> {
                                GamepadManager gp = FourYCtrl.gamepad();
                                ModConfig c = FourYCtrl.config();
                                if (gp == null || c == null) {
                                    return 0;
                                }
                                String name = gp.deviceName();
                                if (name.isBlank()) {
                                    reply(ctx, "还没有连接手柄", Formatting.RED);
                                    return 0;
                                }
                                boolean ok = c.saveDeviceProfile(name);
                                reply(ctx, ok ? "已把当前的轴序号与绑定存成设备档：" + name
                                                : "存档失败（手柄名称为空）",
                                        ok ? Formatting.GREEN : Formatting.RED);
                                return ok ? 1 : 0;
                            })));
        });
    }

    private static void reply(CommandContext<FabricClientCommandSource> ctx, String msg, Formatting color) {
        ctx.getSource().sendFeedback(Text.literal(PREFIX + msg).formatted(color));
    }

    private static int run(CommandContext<FabricClientCommandSource> ctx) {
        ModConfig cfg = FourYCtrl.config();
        GamepadManager gp = FourYCtrl.gamepad();
        FabricClientCommandSource src = ctx.getSource();

        line(src, "===== 4YCtrl 诊断 =====", Formatting.AQUA);
        line(src, "平台：" + com.fouryctrl.util.Platform.describe(), Formatting.GRAY);

        // 1. SDL 库本身
        line(src, "SDL3 就绪：" + (Sdl3.isReady() ? "是" : "否"), Sdl3.isReady() ? Formatting.GREEN : Formatting.RED);
        line(src, "SDL 状态：" + Sdl3.status(), Formatting.GRAY);

        if (gp == null) {
            line(src, "手柄管理器未初始化", Formatting.RED);
            return 0;
        }

        // 2. 设备与状态
        line(src, "总开关：" + (cfg.enabled ? "开" : "关")
                + "　手柄连接：" + (gp.isActive() ? "是" : "否"),
                gp.isActive() ? Formatting.GREEN : Formatting.YELLOW);

        float[] axes = gp.rawAxes();
        line(src, "轴数：" + axes.length + "　轴值：" + formatAxes(axes), Formatting.GRAY);
        line(src, "已配置轴位：LX=" + cfg.axisLX + " LY=" + cfg.axisLY
                + " RX=" + cfg.axisRX + " RY=" + cfg.axisRY
                + " LT=" + cfg.axisLT + " RT=" + cfg.axisRT, Formatting.GRAY);
        line(src, "按下：" + gp.pressedSummary() + "　支持震动：" + (gp.hasRumble() ? "是" : "否"), Formatting.GRAY);
        // 轮询线程的自证：seq 一直在涨就说明它活着（所以「卡 10 帧」时先看这个数变不变）。
        line(src, "轮询线程：seq=" + gp.pollerSeq() + "　轴数=" + gp.pollerAxisCount()
                + "　单次耗时≈" + gp.pollerStepMicros() + "μs"
                + "　每帧现读摇杆=" + (cfg.liveAxis ? "开" : "关"), Formatting.GRAY);
        // 帧上限守卫：若这里显示「正在放行外部限帧」，说明有省电模组在压帧
        // （典型是 FPS Reducer 在 FCL 下误判窗口失焦），本模组已忽略它的请求。
        line(src, "帧上限守卫：" + com.fouryctrl.util.FrameGuard.describe(), Formatting.GRAY);

        // 3. 绑定表
        StringBuilder sb = new StringBuilder();
        if (cfg.bindings != null) {
            for (Map.Entry<String, String> e : cfg.bindings.entrySet()) {
                Button b = Button.byId(e.getKey());
                Action a = Action.byId(e.getValue());
                if (b == null || a == Action.NONE) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append("  ");
                }
                sb.append(b.id).append("→").append(a.id);
            }
        }
        line(src, "绑定：" + (sb.length() == 0 ? "（空）" : sb.toString()), Formatting.GRAY);

        // 3b. 键盘键绑定（按钮 → 别的模组的 KeyBinding）
        StringBuilder kb = new StringBuilder();
        if (cfg.keyBindings != null) {
            for (Map.Entry<String, String> e : cfg.keyBindings.entrySet()) {
                Button b = Button.byId(e.getKey());
                if (b == null || e.getValue() == null || e.getValue().isBlank()) {
                    continue;
                }
                if (kb.length() > 0) {
                    kb.append("  ");
                }
                kb.append(b.id).append("→").append(e.getValue());
            }
        }
        line(src, "键盘键：" + (kb.length() == 0 ? "（空）" : kb.toString()), Formatting.GRAY);

        line(src, "设备档：" + profileSummary(cfg), Formatting.GRAY);
        line(src, "轮盘：" + com.fouryctrl.client.RadialMenu.describe(cfg), Formatting.GRAY);

        // 4. 关键配置
        line(src, String.format("死区 左%.2f 右%.2f 扳机%.2f　曲线%.2f　视角%.0f　光标%.0f",
                        cfg.leftDeadzone, cfg.rightDeadzone, cfg.triggerDeadzone,
                        cfg.stickCurve, cfg.lookSpeed, cfg.guiCursorSpeed), Formatting.DARK_GRAY);

        // 5. 常见故障的定向提示
        if (!Sdl3.isReady()) {
            line(src, "→ libSDL3.so 没加载成功。确认 FCL 用的是「SDL 直通」手柄模式，"
                    + "然后在设置界面按「重新检测」。", Formatting.YELLOW);
        } else if (!gp.isActive()) {
            line(src, "→ SDL 已就绪但没有活动手柄。检查手柄是否已配对并在 Android 里"
                    + "被识别为 joystick，然后按「重新检测」。", Formatting.YELLOW);
        } else if (axes.length == 0) {
            line(src, "→ 手柄已连接但读不到任何轴，可能是设备不支持或轴位配置错误。", Formatting.YELLOW);
        }
        return 1;
    }

    private static String profileSummary(ModConfig cfg) {
        if (cfg.deviceProfiles == null || cfg.deviceProfiles.isEmpty()) {
            return "（无，用 /4y savepad 存一份）";
        }
        StringBuilder sb = new StringBuilder();
        for (String k : cfg.deviceProfiles.keySet()) {
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append(k);
        }
        return sb.toString();
    }

    private static String formatAxes(float[] axes) {
        if (axes == null || axes.length == 0) {
            return "（无）";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < axes.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(String.format("%.2f", axes[i]));
        }
        return sb.append(']').toString();
    }

    private static void line(FabricClientCommandSource src, String msg, Formatting color) {
        src.sendFeedback(Text.literal(msg).formatted(color));
    }
}
