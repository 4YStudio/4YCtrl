package com.fouryctrl.client;

import com.fouryctrl.FourYCtrl;
import com.fouryctrl.config.ModConfig;
import com.fouryctrl.input.GamepadManager;
import com.fouryctrl.util.Compat;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * 游戏内设置界面：死区、视角速度、移动倍率、反转 Y、GUI 光标、震动、按键提示。
 *
 * <p>为什么需要它：安卓上没有键盘，也没法方便地编辑 {@code config/fouryctrl.json}。
 * 而死区的合适取值几乎完全取决于手柄个体差异（有些手柄摇杆回中偏差很大），
 * 只靠「改文件 → 重启游戏」调参数是不现实的。这个界面让玩家推着摇杆就能实时看效果。</p>
 *
 * <p>改动即时写进内存中的 {@link ModConfig}，拖动时立刻生效（{@code GamepadManager}
 * 每 tick 都读同一份配置对象）；关闭界面时才落盘，避免拖动过程中频繁写文件。</p>
 *
 * <p>布局（v3）：继承 {@link ScrollScreen}，控件用固定舒适尺寸（20px 高、24px 行距），
 * 放不下就滚动 —— 之前为了塞进一屏把行高压到 11px，手机上按钮小到点不中。
 * 轴序号（axisLX..axisRT）仍然不放 UI：它是「配错了就完全不能用」的选项，
 * 设置界面底部的轴读数行照旧实时显示，照着改 JSON 更稳妥。</p>
 */
public class SettingsScreen extends ScrollScreen {
    private static final int COLOR_HINT = 0xFFA8A8A8;
    private static final int ROW_STEP = 24;
    private static final int WIDGET_H = 20;

    private final Screen parent;
    private final ModConfig cfg;
    private final GamepadManager gamepad;
    private final List<ConfigSlider> sliders = new ArrayList<>();

    /** 轮盘说明文字的 y（-1 = 不画）；滚动区里没有控件，只能自己在 render 里补。 */
    private int radialHelpY = -1;

    public SettingsScreen(Screen parent) {
        super(Text.translatable("fouryctrl.settings.title"));
        this.parent = parent;
        this.cfg = FourYCtrl.config();
        this.gamepad = FourYCtrl.gamepad();
    }

    @Override
    protected void init() {
        sliders.clear();
        clearFixedChildren();
        if (cfg == null) {
            addFixedChild(ButtonWidget.builder(Text.translatable("gui.done"), b -> close())
                    .dimensions(this.width / 2 - 75, this.height - 30, 150, 20).build());
            return;
        }

        int widgetWidth = Math.min(300, this.width - 40);
        int x = (this.width - widgetWidth) / 2;
        int bottomRowY = this.height - 30;
        int viewTop = 50;   // 标题(14) + 连接状态(26) + 链路状态(38) 之下
        int viewBottom = bottomRowY - 4;

        int y = viewTop;
        addSlider(x, y, widgetWidth, "fouryctrl.settings.left_deadzone",
                0.0, 0.6, cfg.leftDeadzone, 0.15f, 2, v -> cfg.leftDeadzone = v);
        y += ROW_STEP;
        addSlider(x, y, widgetWidth, "fouryctrl.settings.right_deadzone",
                0.0, 0.6, cfg.rightDeadzone, 0.12f, 2, v -> cfg.rightDeadzone = v);
        y += ROW_STEP;
        addSlider(x, y, widgetWidth, "fouryctrl.settings.trigger_deadzone",
                0.0, 0.6, cfg.triggerDeadzone, 0.10f, 2, v -> cfg.triggerDeadzone = v);
        y += ROW_STEP;
        addSlider(x, y, widgetWidth, "fouryctrl.settings.look_speed",
                20.0, 720.0, cfg.lookSpeed, 260.0f, 0, v -> cfg.lookSpeed = v);
        y += ROW_STEP;
        addSlider(x, y, widgetWidth, "fouryctrl.settings.move_scale",
                0.2, 1.0, cfg.moveSpeedScale, 1.0f, 2, v -> cfg.moveSpeedScale = v);
        y += ROW_STEP;

        // 摇杆响应曲线：1.0 = 线性；< 1 轻推更灵敏；> 1 轻推更细腻（适合精确走位）。
        addSlider(x, y, widgetWidth, "fouryctrl.settings.stick_curve",
                0.5, 3.0, cfg.stickCurve, 1.0f, 2, v -> cfg.stickCurve = v);
        y += ROW_STEP;

        // 视角加速度：1.0 = 等速；越大，摇杆推得越满转得越快（轻推仍然精细）。
        addSlider(x, y, widgetWidth, "fouryctrl.settings.look_accel",
                1.0, 3.0, cfg.lookAcceleration, 1.0f, 2, v -> cfg.lookAcceleration = v);
        y += ROW_STEP;

        ButtonWidget guiCursor = ButtonWidget.builder(toggleLabel("fouryctrl.settings.gui_cursor", cfg.guiCursor), b -> {
            cfg.guiCursor = !cfg.guiCursor;
            b.setMessage(toggleLabel("fouryctrl.settings.gui_cursor", cfg.guiCursor));
        }).dimensions(x, y, widgetWidth, WIDGET_H).build();
        addDrawableChild(guiCursor);
        y += ROW_STEP;

        // 光标灵敏度：像素/秒（满推）。手机屏幕小，默认 900 手感刚好；
        // 觉得飘就调低，觉得太慢就调高。
        addSlider(x, y, widgetWidth, "fouryctrl.settings.gui_cursor_speed",
                100.0, 2400.0, cfg.guiCursorSpeed, 900.0f, 0, v -> cfg.guiCursorSpeed = v);
        y += ROW_STEP;

        // 光标十字的粗细/臂长（配合实心十字造型，可调成更细更小的点）。
        addSlider(x, y, widgetWidth, "fouryctrl.settings.cursor_thickness",
                1.0, 3.0, cfg.cursorThickness, 1.0f, 0, v -> cfg.cursorThickness = Math.round(v));
        y += ROW_STEP;
        addSlider(x, y, widgetWidth, "fouryctrl.settings.cursor_length",
                3.0, 12.0, cfg.cursorLength, 6.0f, 0, v -> cfg.cursorLength = Math.round(v));
        y += ROW_STEP;

        // 十字键在界面里的作用：挪光标 / 在按钮之间吸附跳转。
        ButtonWidget dpadMode = ButtonWidget.builder(dpadModeLabel(cfg.dpadMode), b -> {
            cfg.dpadMode = ModConfig.nextDpadMode(cfg.dpadMode);
            b.setMessage(dpadModeLabel(cfg.dpadMode));
        }).dimensions(x, y, widgetWidth, WIDGET_H).build();
        addDrawableChild(dpadMode);
        y += ROW_STEP;

        // 轴位自动识别：连上手柄后静置 1.5 秒，自动判定哪两个轴是摇杆、哪两个是扳机。
        ButtonWidget axisAuto = ButtonWidget.builder(toggleLabel("fouryctrl.settings.axis_auto", cfg.axisAutoDetect), b -> {
            cfg.axisAutoDetect = !cfg.axisAutoDetect;
            b.setMessage(toggleLabel("fouryctrl.settings.axis_auto", cfg.axisAutoDetect));
        }).dimensions(x, y, widgetWidth, WIDGET_H).build();
        addDrawableChild(axisAuto);
        y += ROW_STEP;

        // 视角是否每帧现读：关掉后退回每 tick（20Hz）采样，视角最多滞后 50ms。
        // 现在读轴全在轮询线程上，客户端线程本来就只是拷快照，所以默认开着最好；
        // 留着这个开关是给「设备上拷快照也要紧」的人手动降档用。
        ButtonWidget liveAxis = ButtonWidget.builder(
                toggleLabel("fouryctrl.settings.live_axis", cfg.liveAxis), b -> {
                    cfg.liveAxis = !cfg.liveAxis;
                    b.setMessage(toggleLabel("fouryctrl.settings.live_axis", cfg.liveAxis));
                }).dimensions(x, y, widgetWidth, WIDGET_H).build();
        addDrawableChild(liveAxis);
        y += ROW_STEP;

        // 帧上限守卫：别的模组（FPS Reducer 之类）在 FCL 下会把帧上限压到 10 又不生效，
        // 我们补上真睡眠后它就真的生效了 → 这里默认忽略那种异常低的请求。
        ButtonWidget fpsGuard = ButtonWidget.builder(
                toggleLabel("fouryctrl.settings.fps_guard", cfg.ignoreExternalFpsLimit), b -> {
                    cfg.ignoreExternalFpsLimit = !cfg.ignoreExternalFpsLimit;
                    b.setMessage(toggleLabel("fouryctrl.settings.fps_guard", cfg.ignoreExternalFpsLimit));
                }).dimensions(x, y, widgetWidth, WIDGET_H).build();
        addDrawableChild(fpsGuard);
        y += ROW_STEP;

        // 底部按键提示条（Controlify 式）：背包等界面里显示「A=跳跃 LB=…」。
        ButtonWidget hints = ButtonWidget.builder(toggleLabel("fouryctrl.settings.show_hints", cfg.showButtonHints), b -> {
            cfg.showButtonHints = !cfg.showButtonHints;
            b.setMessage(toggleLabel("fouryctrl.settings.show_hints", cfg.showButtonHints));
        }).dimensions(x, y, widgetWidth, WIDGET_H).build();
        addDrawableChild(hints);
        y += ROW_STEP;

        // 提示内容分档：全部 / 部分（省略天天按的常用键）/ 不显示。
        ButtonWidget hintMode = ButtonWidget.builder(hintModeLabel(cfg.hintMode), b -> {
            cfg.hintMode = ModConfig.nextHintMode(cfg.hintMode);
            b.setMessage(hintModeLabel(cfg.hintMode));
        }).dimensions(x, y, widgetWidth, WIDGET_H).build();
        addDrawableChild(hintMode);
        y += ROW_STEP;

        // 提示条位置：点一下在这几种锚点之间循环（底部/顶部 × 两侧/仅左/仅右）。
        ButtonWidget hintAnchor = ButtonWidget.builder(anchorLabel(cfg.hintAnchor), b -> {
            cfg.hintAnchor = ModConfig.nextHintAnchor(cfg.hintAnchor);
            b.setMessage(anchorLabel(cfg.hintAnchor));
        }).dimensions(x, y, widgetWidth, WIDGET_H).build();
        addDrawableChild(hintAnchor);
        y += ROW_STEP;

        boolean showRumble = gamepad == null || gamepad.hasRumble();
        if (showRumble) {
            addSlider(x, y, widgetWidth, "fouryctrl.settings.rumble_strength",
                    0.05, 1.0, cfg.rumbleStrength, 0.6f, 2, v -> {
                        cfg.rumbleStrength = v;
                        cfg.rumbleEnabled = true;
                    });
            y += ROW_STEP;

            // 震动场景：除了「攻击/使用」这个基础反馈，其余四类场景各一个开关。
            y = addToggle(x, y, widgetWidth, "fouryctrl.settings.rumble_pickup", cfg.rumblePickup,
                    v -> cfg.rumblePickup = v);
            y = addToggle(x, y, widgetWidth, "fouryctrl.settings.rumble_durability", cfg.rumbleDurability,
                    v -> cfg.rumbleDurability = v);
            y = addToggle(x, y, widgetWidth, "fouryctrl.settings.rumble_hurt", cfg.rumbleHurt,
                    v -> cfg.rumbleHurt = v);
            y = addToggle(x, y, widgetWidth, "fouryctrl.settings.rumble_hunger", cfg.rumbleHunger,
                    v -> cfg.rumbleHunger = v);
        }

        ButtonWidget invert = ButtonWidget.builder(invertLabel(cfg.invertLookY), b -> {
            cfg.invertLookY = !cfg.invertLookY;
            b.setMessage(invertLabel(cfg.invertLookY));
        }).dimensions(x, y, widgetWidth, WIDGET_H).build();
        addDrawableChild(invert);
        y += ROW_STEP;

        // 潜行 / 疾跑：切换式（点一下生效，再点一下解除）还是按住式。
        // 手柄玩家几乎都偏爱切换式：按住 R3 跑图时拇指还要控制视角，很别扭。
        ButtonWidget toggleSneak = ButtonWidget.builder(modeLabel("fouryctrl.settings.toggle_sneak", cfg.toggleSneak), b -> {
            cfg.toggleSneak = !cfg.toggleSneak;
            b.setMessage(modeLabel("fouryctrl.settings.toggle_sneak", cfg.toggleSneak));
        }).dimensions(x, y, widgetWidth, WIDGET_H).build();
        addDrawableChild(toggleSneak);
        y += ROW_STEP;
        ButtonWidget toggleSprint = ButtonWidget.builder(modeLabel("fouryctrl.settings.toggle_sprint", cfg.toggleSprint), b -> {
            cfg.toggleSprint = !cfg.toggleSprint;
            b.setMessage(modeLabel("fouryctrl.settings.toggle_sprint", cfg.toggleSprint));
        }).dimensions(x, y, widgetWidth, WIDGET_H).build();
        addDrawableChild(toggleSprint);
        y += ROW_STEP;

        // 径向动作轮盘：按住 BACK 弹出环，右摇杆选方向、松手执行。
        // 槽位内容（radialActions）仍留在配置文件里 —— 那是 8 个动作 id 的逗号串，
        // 用循环按钮改一处要连点十几次才找得到想要的动作，手机上手感极差。
        ButtonWidget radial = ButtonWidget.builder(
                toggleLabel("fouryctrl.settings.radial_menu", cfg.radialMenu), b -> {
                    cfg.radialMenu = !cfg.radialMenu;
                    b.setMessage(toggleLabel("fouryctrl.settings.radial_menu", cfg.radialMenu));
                }).dimensions(x, y, widgetWidth, WIDGET_H).build();
        addDrawableChild(radial);
        // 一段说明文字：轮盘是唯一「需要在配置文件里配内容」的功能，界面里必须写清楚，
        // 否则玩家开了开关发现按住 BACK 没反应（radialActions 为空）就以为是 bug。
        radialHelpY = y + WIDGET_H + 2;
        y += ROW_STEP + 12;

        // 键位绑定入口：手柄按钮 → 游戏动作 的完整映射编辑界面（含 LT/RT 扳机）。
        addDrawableChild(ButtonWidget.builder(Text.translatable("fouryctrl.settings.bindings"),
                b -> this.client.setScreen(new BindingsScreen(this)))
                .dimensions(x, y, widgetWidth, WIDGET_H).build());
        y += ROW_STEP;

        // 内容高度登记（滚动用）；底部三颗按钮固定在视口外，不参与滚动。
        setContentBounds(viewTop, viewBottom, x, x + widgetWidth, y - viewTop + 4);

        // 底部三颗按钮：恢复默认 / 重新检测 / 完成。
        // 「重新检测」是真机上最常用的一颗：手柄比游戏晚连上、或 FCL 侧 SDL2 装载慢一步，
        // 初始化失败一次就再也不会自己重试（ensureReady 是幂等的），只能靠它重来。
        int third = (widgetWidth - 12) / 3;
        // 底部三颗固定按钮：不进 children（否则会被 super.render 的 translate 一起上移，
        // 与内容重叠），改走 ScrollScreen 的固定通道：渲染在滚动区之外、命中不做 contentY 变换。
        addFixedChild(ButtonWidget.builder(Text.translatable("fouryctrl.settings.reset"), b -> {
            for (ConfigSlider s : sliders) {
                s.resetToDefault();
            }
            cfg.invertLookY = false;
            invert.setMessage(invertLabel(false));
            cfg.hintAnchor = "bottom_both";
            hintAnchor.setMessage(anchorLabel(cfg.hintAnchor));
            cfg.hintMode = "partial";
            hintMode.setMessage(hintModeLabel(cfg.hintMode));
            cfg.dpadMode = "cursor";
            dpadMode.setMessage(dpadModeLabel(cfg.dpadMode));
            cfg.radialMenu = false;
            radial.setMessage(toggleLabel("fouryctrl.settings.radial_menu", false));
            cfg.ignoreExternalFpsLimit = true;
            fpsGuard.setMessage(toggleLabel("fouryctrl.settings.fps_guard", true));
        }).dimensions(x, bottomRowY, third, 20).build());
        addFixedChild(ButtonWidget.builder(Text.translatable("fouryctrl.settings.redetect"), b -> {
            if (gamepad != null) {
                gamepad.redetect();
            }
        }).dimensions(x + third + 6, bottomRowY, third, 20).build());
        addFixedChild(ButtonWidget.builder(Text.translatable("gui.done"), b -> close())
                .dimensions(x + (third + 6) * 2, bottomRowY, widgetWidth - (third + 6) * 2, 20).build());
    }

    /**
     * 加一行二态开关按钮，返回下一行的 y。
     *
     * <p>状态用可变容器持有：直接捕获 {@code initial} 会让每次点击都算出同一个值
     * （{@code !initial} 恒定），点了没反应。也不用 {@code toggleLabel} 的「切换/按住」
     * 文案 —— 这里是开/关语义。</p>
     */
    private int addToggle(int x, int y, int width, String labelKey, boolean initial,
                          java.util.function.Consumer<Boolean> setter) {
        boolean[] cur = {initial};
        ButtonWidget w = ButtonWidget.builder(toggleLabel(labelKey, cur[0]), b -> {
            cur[0] = !cur[0];
            setter.accept(cur[0]);
            b.setMessage(toggleLabel(labelKey, cur[0]));
        }).dimensions(x, y, width, WIDGET_H).build();
        addDrawableChild(w);
        return y + ROW_STEP;
    }

    private void addSlider(int x, int y, int width, String labelKey,
                           double min, double max, float value, float defaultValue,
                           int decimals, Consumer<Float> setter) {
        ConfigSlider slider = new ConfigSlider(x, y, width, WIDGET_H,
                Text.translatable(labelKey), min, max, value, defaultValue, decimals, setter);
        sliders.add(slider);
        addDrawableChild(slider);
    }

    private static Text invertLabel(boolean invert) {
        return Text.translatable("fouryctrl.settings.invert_y", Text.translatable(
                invert ? "fouryctrl.state.on" : "fouryctrl.state.off"));
    }

    private static Text modeLabel(String key, boolean toggle) {
        return Text.translatable(key, Text.translatable(
                toggle ? "fouryctrl.settings.mode_toggle" : "fouryctrl.settings.mode_hold"));
    }

    private static Text toggleLabel(String key, boolean on) {
        return Text.translatable(key, Text.translatable(
                on ? "fouryctrl.state.on" : "fouryctrl.state.off"));
    }

    /** 提示内容分档：全部 / 部分 / 不显示。 */
    private static Text hintModeLabel(String mode) {
        String key = mode == null ? "partial" : mode;
        return Text.translatable("fouryctrl.settings.hint_mode",
                Text.translatable("fouryctrl.hint_mode." + key));
    }

    /** 十字键在界面里的作用：挪光标 / 在控件间吸附跳转。 */
    private static Text dpadModeLabel(String mode) {
        String key = mode == null ? "cursor" : mode;
        return Text.translatable("fouryctrl.settings.dpad_mode",
                Text.translatable("fouryctrl.dpad_mode." + key));
    }

    /** 提示条位置：6 个锚点各一条翻译键（如 fouryctrl.hint_anchor.bottom_both）。 */
    private static Text anchorLabel(String anchor) {
        String key = anchor == null ? "bottom_both" : anchor;
        return Text.translatable("fouryctrl.settings.hint_anchor",
                Text.translatable("fouryctrl.hint_anchor." + key));
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        Compat.renderBackground(this, context);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title,
                this.width / 2, 14, 0xFFFFFFFF);

        String info;
        if (gamepad != null && gamepad.state().connected) {
            int power = gamepad.powerPercent();
            // deviceLabel 而不是 state().name：驱动漏报名字时回退成「手柄 #n」，
            // 否则这一行是空的（或一排虚线方框）。
            String name = gamepad.deviceLabel();
            info = power >= 0
                    ? Text.translatable("fouryctrl.settings.connected_battery", name, power).getString()
                    : Text.translatable("fouryctrl.settings.connected", name).getString();
        } else {
            info = Text.translatable("fouryctrl.settings.not_connected").getString();
        }
        context.drawCenteredTextWithShadow(this.textRenderer, info,
                this.width / 2, 26, COLOR_HINT);

        // 链路状态：dlopen / 代理探测 / InitSubSystem 各步成败，排错第一入口。
        if (gamepad != null) {
            String status = truncated(gamepad.sdlStatus(), this.width - 12);
            context.drawCenteredTextWithShadow(this.textRenderer, status,
                    this.width / 2, 38, COLOR_HINT);
        }

        // 内容随滚动整体上移；scissor 裁掉滚出视口的控件（否则会画到标题/状态上），
        // 子控件的悬停判定也要用换算过的鼠标 y（super.render 把这对坐标原样传给每个 widget 做高亮）。
        scissorContent(context);
        Compat.push(context);
        Compat.translate(context, 0, -scroll);
        super.render(context, mouseX, (int) contentY(mouseY), delta);
        Compat.pop(context);
        unscissor(context);

        // 轴读数行：夹在视口内绘制（不超出底欄），跟随内容滚动。
        if (radialHelpY > 0) {
            // viewY：内容坐标 → 视口坐标（减去 scroll），和子控件的上移方向一致。
            int hy = viewY(radialHelpY);
            if (hy > viewTop - 10 && hy < viewBottom) {
                String help = truncated(Text.translatable("fouryctrl.settings.radial_help").getString(),
                        this.width - 24);
                context.drawCenteredTextWithShadow(this.textRenderer, help,
                        this.width / 2, hy, COLOR_HINT);
            }
        }

        String axes = axesLine();
        if (axes != null) {
            int y = Math.max(viewTop + 8, Math.min(viewBottom - 6, viewBottom - 14)) ;
            if (y + 10 > viewBottom) {
                y = viewBottom - 12;
            }
            context.drawCenteredTextWithShadow(this.textRenderer, axes,
                    this.width / 2, y, COLOR_HINT);
        }

        drawScrollbar(context);

        // 固定按钮最后画：在滚动裁剪之外，永远落在底部固定区。
        renderFixed(context, mouseX, mouseY, delta);
    }

    /** 按像素宽度截断，超了补省略号。状态文案里带系统错误，长度不可控。 */
    private String truncated(String text, int maxWidth) {
        if (text == null) {
            return "";
        }
        if (this.textRenderer.getWidth(text) <= maxWidth) {
            return text;
        }
        return this.textRenderer.trimToWidth(text, Math.max(0, maxWidth - this.textRenderer.getWidth("…"))) + "…";
    }

    /**
     * 一行「轴号=当前值」，并在被配置引用的轴号前标上用途（LX/LY/RX/RY/LT/RT）。
     *
     * <p>这是排查「推左摇杆人却侧着走」最直接的手段：推一下摇杆，看哪个轴在动，
     * 再对照标签就知道 {@code axisLX..axisRT} 该填几。手机上没法看日志时尤其有用。</p>
     */
    private String axesLine() {
        if (gamepad == null || cfg == null || !gamepad.state().connected) {
            return null;
        }
        float[] raw = gamepad.rawAxes();
        if (raw == null || raw.length == 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder(Text.translatable("fouryctrl.settings.axes").getString()).append(' ');
        int budget = Math.max(60, this.width - 12);
        for (int i = 0; i < raw.length; i++) {
            String tag = tagOf(i);
            String piece = tag + i + '=' + String.format(Locale.ROOT, "%.2f", raw[i]) + ' ';
            if (this.textRenderer.getWidth(sb.toString() + piece) > budget) {
                sb.append("…");
                break;
            }
            sb.append(piece);
        }
        return sb.toString().trim();
    }

    private String tagOf(int index) {
        if (index == cfg.axisLX) return "LX";
        if (index == cfg.axisLY) return "LY";
        if (index == cfg.axisRX) return "RX";
        if (index == cfg.axisRY) return "RY";
        if (index == cfg.axisLT) return "LT";
        if (index == cfg.axisRT) return "RT";
        return "";
    }

    @Override
    public void removed() {
        // 无论怎么离开（ESC、返回按钮、被别的界面顶掉）都落盘。
        if (cfg != null) {
            cfg.save();
        }
        super.removed();
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(this.parent);
        }
    }

    /**
     * 保持暂停。
     *
     * <p>这个界面是从暂停菜单进来的，{@code Screen.shouldPause()} 默认返回 false，
     * 不重写的话「单人游戏里从暂停菜单点进设置」会直接把游戏解除暂停——人在看菜单，
     * 世界却在跑，旁边一只苦力怕就过来了。</p>
     */
    @Override
    public boolean shouldPause() {
        return true;
    }

    /**
     * 一行「标签 + 当前值」的滑块。
     *
     * <p>{@link SliderWidget} 的 {@code value} 字段是 0~1 的归一化位置，
     * 真正的配置值要自己映射回去；{@code updateMessage()} / {@code applyValue()}
     * 是两个抽象方法，必须实现（父类构造后不会自动调用 updateMessage，所以这里手动调一次）。</p>
     */
    private static final class ConfigSlider extends SliderWidget {
        private final Text label;
        private final double min;
        private final double max;
        private final float defaultValue;
        private final int decimals;
        private final Consumer<Float> setter;

        ConfigSlider(int x, int y, int width, int height, Text label,
                     double min, double max, float value, float defaultValue,
                     int decimals, Consumer<Float> setter) {
            super(x, y, width, height, Text.empty(), toRatio(value, min, max));
            this.label = label;
            this.min = min;
            this.max = max;
            this.defaultValue = defaultValue;
            this.decimals = decimals;
            this.setter = setter;
            updateMessage();
        }

        private static double toRatio(double value, double min, double max) {
            if (max <= min) {
                return 0.0;
            }
            double ratio = (value - min) / (max - min);
            return Math.max(0.0, Math.min(1.0, ratio));
        }

        private float current() {
            return (float) (min + this.value * (max - min));
        }

        void resetToDefault() {
            this.value = toRatio(defaultValue, min, max);
            updateMessage();
            applyValue();
        }

        @Override
        protected void updateMessage() {
            String text = decimals <= 0
                    ? String.format(Locale.ROOT, "%.0f", current())
                    : String.format(Locale.ROOT, "%." + decimals + "f", current());
            setMessage(Text.translatable("fouryctrl.settings.value", label, Text.literal(text)));
        }

        @Override
        protected void applyValue() {
            setter.accept(current());
        }
    }
}
