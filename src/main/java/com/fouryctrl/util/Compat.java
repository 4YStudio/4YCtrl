package com.fouryctrl.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import org.lwjgl.glfw.GLFW;

/**
 * 多版本兼容层（1.20.1 → 1.21.11）。
 *
 * <p>本模组用 Stonecutter 一份源码出 9 个版本。绝大多数差异是「方法名/参数表不同」，
 * 这类只要在调用点集中到一个 shim 里就能一次改完；只有 <b>重写父类方法</b> 做不到
 * 运行时判断（{@code @Override} 的签名是编译期固定的），那种只能在各文件里用
 * {@code //? if ...} 条件注释写两份。</p>
 *
 * <h2>各版本分界（全部经 javap 实测，勿凭记忆改）</h2>
 * <ul>
 *   <li>{@code Screen.renderBackground(DrawContext)} 仅 1.20.1；1.20.2+ 变成
 *       {@code (DrawContext, int, int, float)}。→ {@link #renderBackground(Screen, DrawContext)}</li>
 *   <li>{@code Screen.init(MinecraftClient, int, int)} 到 1.21.8；1.21.11 是
 *       {@code init(int, int)}（client 从 {@code Screen.client} 字段拿）。
 *       → {@link #reinit(Screen)}</li>
 *   <li>{@code Element.mouseClicked/mouseReleased/mouseDragged} 到 1.21.8 收原始坐标；
 *       1.21.11 全部改成 record 入参（{@code Click}/{@code KeyInput}/{@code CharInput}）。
 *       → {@link #mouseClicked}/{@link #mouseReleased}/{@link #mouseDragged}/{@link #keyPressed}</li>
 *   <li>{@code Element.mouseScrolled} 1.20.1 是三参 {@code (DDD)}，1.20.2+ 是四参
 *       {@code (DDDD)}（多了 horizontalAmount）。→ {@link #mouseScrolled}</li>
 *   <li>{@code MinecraftClient.openPauseMenu(boolean)} 仅 1.20.1+1.20.2 之前；
 *       1.20.2+ 改名 {@code openGameMenu(boolean)}。→ {@link #openGameMenu}</li>
 *   <li>{@code ChatScreen(String)} 到 1.21.8；1.21.11 是 {@code ChatScreen(String, boolean)}
 *       （第二参 = draft）。→ {@link #openChat}</li>
 *   <li>{@code DrawContext.getMatrices()} 到 1.21.5 返回 {@code MatrixStack}
 *       （{@code push/pop/translate(f,f,f)}）；1.21.8+ 返回 {@code Matrix3x2fStack}
 *       （只有 {@code pushMatrix/popMatrix}，translate 是两参）。→ {@link #push}/{@link #pop}/
 *       {@link #translateZ} 等</li>
 *   <li>{@code ItemStack.isFood()} 被 1.20.6 的组件系统删掉 → 改查
 *       {@code DataComponentTypes.FOOD}。→ {@link #isFood}</li>
 *   <li>{@code Window.getFramerateLimit()} / {@code MinecraftClient.getFramerateLimit()}
 *       在 1.21.4 起没有（改由 {@code InactivityFpsLimiter.update()} 每次算）。
 *       → {@link #windowFramerateLimit}/{@link #effectiveFramerateLimit}</li>
 *   <li>{@code PlayerInventory.selectedSlot} 1.21.4+ 变 private。
 *       → {@link #selectedSlot}/{@link #setSelectedSlot}</li>
 *   <li>{@code KeyBinding} 构造器第 4 参（分类）：1.21.11 起要 {@code KeyBinding.Category}
 *       而非 String。→ {@link #keyCategory}</li>
 * </ul>
 *
 * <p><b>注意</b>：本类里凡是引用新版本才有的类（如 {@code Click}）都必须包在
 * {@code //? if} 里，否则老版本的编译器连符号都找不到。</p>
 */
public final class Compat {
    private Compat() {
    }

    // ---------------------------------------------------------------- 屏幕生命周期

    /**
     * 画界面背景（模糊 + 压暗 + 菜单全景）。
     *
     * <h2>1.21.8 起这里必须什么都不做</h2>
     * <p>1.21.8 的 {@code Screen.renderWithTooltip} 变成：</p>
     * <pre>
     *   context.createNewRootLayer();
     *   this.renderBackground(context, mouseX, mouseY, deltaTicks);  // ← 官方自己调了
     *   context.createNewRootLayer();
     *   this.render(context, mouseX, mouseY, deltaTicks);
     * </pre>
     * <p>各界面 {@code render()} 里再调一次就会在同一帧里 blur 两次，直接抛
     * {@code IllegalStateException: Can only blur once per frame}
     * （{@code GuiRenderState.applyBlur} 只允许每帧一次），表现为打开任意界面崩溃。
     * 就算没开模糊，也会把压暗层和全景各画两遍。所以 1.21.8+ 交给原版。</p>
     *
     * <p>26.x 的 {@code GuiGraphicsExtractor}/{@code extractRenderState} 管线同理，
     * 那边的界面代码里已经不带这个调用。</p>
     */
    public static void renderBackground(Screen screen, DrawContext context) {
        //? if <1.21.8 {
        int mx = (int) mouseX();
        int my = (int) mouseY();
        //? if <1.20.2 {
        /*screen.renderBackground(context);
        *///?} else {
        screen.renderBackground(context, mx, my, 0.0f);
        //?}
        //?}
    }

    /** 重新走一遍 {@code init}（布局变化后刷新控件）。 */
    public static void reinit(Screen screen) {
        MinecraftClient client = MinecraftClient.getInstance();
        int w = screen.width;
        int h = screen.height;
        //? if <1.21.11 {
        /*screen.init(client, w, h);
        *///?} else {
        screen.init(w, h);
        //?}
    }

    /** 打开暂停菜单。 */
    public static void openGameMenu(MinecraftClient client, boolean pauseOnly) {
        //? if <1.20.2 {
        /*client.openPauseMenu(pauseOnly);
        *///?} else {
        client.openGameMenu(pauseOnly);
        //?}
    }

    /** 打开聊天框（空输入）。 */
    public static Screen chatScreen(String text) {
        //? if <1.21.11 {
        /*return new net.minecraft.client.gui.screen.ChatScreen(text);
        *///?} else {
        return new net.minecraft.client.gui.screen.ChatScreen(text, false);
        //?}
    }

    // ---------------------------------------------------------------- 鼠标 / 键盘事件转发

    /** 把 {@code (x, y, button)} 折成各版本的 {@code mouseClicked} 调用。 */
    public static boolean mouseClicked(Screen screen, double x, double y, int button) {
        //? if <1.21.11 {
        /*return screen.mouseClicked(x, y, button);
        *///?} else {
        return screen.mouseClicked(
                new net.minecraft.client.gui.Click(x, y, new net.minecraft.client.input.MouseInput(button, 0)), false);
        //?}
    }

    /** 同 {@link #mouseClicked}，但目标是任意 {@code Element}（widget）。 */
    public static boolean mouseClicked(net.minecraft.client.gui.Element element, double x, double y, int button) {
        //? if <1.21.11 {
        /*return element.mouseClicked(x, y, button);
        *///?} else {
        return element.mouseClicked(
                new net.minecraft.client.gui.Click(x, y, new net.minecraft.client.input.MouseInput(button, 0)), false);
        //?}
    }

    public static boolean mouseReleased(Screen screen, double x, double y, int button) {
        //? if <1.21.11 {
        /*return screen.mouseReleased(x, y, button);
        *///?} else {
        return screen.mouseReleased(
                new net.minecraft.client.gui.Click(x, y, new net.minecraft.client.input.MouseInput(button, 0)));
        //?}
    }

    public static boolean mouseReleased(net.minecraft.client.gui.Element element, double x, double y, int button) {
        //? if <1.21.11 {
        /*return element.mouseReleased(x, y, button);
        *///?} else {
        return element.mouseReleased(
                new net.minecraft.client.gui.Click(x, y, new net.minecraft.client.input.MouseInput(button, 0)));
        //?}
    }

    public static boolean mouseDragged(Screen screen, double x, double y, int button,
                                       double deltaX, double deltaY) {
        //? if <1.21.11 {
        /*return screen.mouseDragged(x, y, button, deltaX, deltaY);
        *///?} else {
        return screen.mouseDragged(
                new net.minecraft.client.gui.Click(x, y, new net.minecraft.client.input.MouseInput(button, 0)),
                deltaX, deltaY);
        //?}
    }

    /** 滚轮：1.20.1 三参，1.20.2+ 四参（多一个 horizontalAmount=0）。 */
    public static boolean mouseScrolled(Screen screen, double x, double y, double vertical) {
        //? if <1.20.2 {
        /*return screen.mouseScrolled(x, y, vertical);
        *///?} else {
        return screen.mouseScrolled(x, y, 0.0, vertical);
        //?}
    }

    /** 键盘事件（虚拟键盘给聊天框发 Enter）。 */
    public static boolean keyPressed(Screen screen, int keyCode, int scanCode, int modifiers) {
        //? if <1.21.11 {
        /*return screen.keyPressed(keyCode, scanCode, modifiers);
        *///?} else {
        return screen.keyPressed(new net.minecraft.client.input.KeyInput(keyCode, scanCode, modifiers));
        //?}
    }

    // ---------------------------------------------------------------- 矩阵

    /** 压栈（1.21.8+ 的 Matrix3x2fStack 没有 push()，改叫 pushMatrix()）。 */
    public static void push(DrawContext context) {
        //? if <1.21.8 {
        /*context.getMatrices().push();
        *///?} else {
        context.getMatrices().pushMatrix();
        //?}
    }

    public static void pop(DrawContext context) {
        //? if <1.21.8 {
        /*context.getMatrices().pop();
        *///?} else {
        context.getMatrices().popMatrix();
        //?}
    }

    /** 平移 (x, y)（不含 z —— 1.21.8+ 的 2D 矩阵栈没有 z）。 */
    public static void translate(DrawContext context, float x, float y) {
        //? if <1.21.8 {
        /*context.getMatrices().translate(x, y, 0.0f);
        *///?} else {
        context.getMatrices().translate(x, y);
        //?}
    }

    /**
     * 把绘制抬到指定 z 层。
     *
     * <p>1.21.8+ 的 {@code Matrix3x2fStack} 是纯 2D，没有 z 轴，也就没有「压在上面」这回事：
     * 那里靠的是调用顺序（{@code ScreenMixin} 注入在 {@code renderWithTooltip} 的 TAIL，
     * 本来就最后画）。所以新版直接不做平移。</p>
     */
    public static void translateZ(DrawContext context, float z) {
        //? if <1.21.8 {
        /*context.getMatrices().translate(0.0f, 0.0f, z);
        *///?}
    }

    // ---------------------------------------------------------------- 杂项

    /** {@code ItemStack.isFood()} 的跨版本等价物。 */
    public static boolean isFood(net.minecraft.item.ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        //? if <1.20.6 {
        /*return stack.isFood();
        *///?} else {
        return stack.contains(net.minecraft.component.DataComponentTypes.FOOD);
        //?}
    }

    /** 玩家自己设定的最大帧率（&gt;=260 视为无限制）。取不到返回 0。 */
    public static int optionsMaxFps() {
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc == null || mc.options == null) {
                return 0;
            }
            Integer v = mc.options.getMaxFps().getValue();
            return v == null ? 0 : v;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * {@code Window.framerateLimit} 的当前值。
     *
     * <p>1.20.1–1.21.1：{@code Window.framerateLimit} 是真值（外部模组改它就能压帧）。
     * 1.21.4+：{@code Window} 不再存这个字段，限帧值每次由
     * {@code InactivityFpsLimiter.update()} 现算，所以只能退化成「玩家设定值」。</p>
     */
    public static int windowFramerateLimit() {
        return Compat.windowMaxFpsCompat();
    }

    private static int windowMaxFpsCompat() {
        //? if <1.21.4 {
        /*try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc == null || mc.getWindow() == null) {
                return -1;
            }
            return mc.getWindow().getFramerateLimit();
        } catch (Throwable t) {
            return -1;
        }
        *///?} else {
        return optionsMaxFps();
        //?}
    }

    /** 当前玩家选中的快捷栏槽位（1.21.5 起才有 getSelectedSlot）。 */
    public static int selectedSlot(net.minecraft.entity.player.PlayerInventory inv) {
        //? if <1.21.5 {
        /*return inv.selectedSlot;
        *///?} else {
        return inv.getSelectedSlot();
        //?}
    }

    public static void setSelectedSlot(net.minecraft.entity.player.PlayerInventory inv, int slot) {
        //? if <1.21.4 {
        /*inv.selectedSlot = slot;
        *///?} else {
        inv.setSelectedSlot(slot);
        //?}
    }

    /** {@code KeyBinding} 的分类参数（1.21.11 起必须传 {@code KeyBinding.Category}）。 */
    public static Object keyCategory(String name) {
        //? if <1.21.11 {
        /*return name;
        *///?} else {
        return net.minecraft.client.option.KeyBinding.Category.MISC;
        //?}
    }

    /** {@code TextFieldWidget.setCursor} 1.20.1 是单参，1.20.2+ 多一个「是否选中」布尔。 */
    public static void setCursor(net.minecraft.client.gui.widget.TextFieldWidget field, int pos) {
        //? if <1.20.2 {
        /*field.setCursor(pos);
        *///?} else {
        field.setCursor(pos, false);
        //?}
    }

    /** 当前鼠标位置（给 {@code renderBackground} 的 hover 参数用；取不到就当 0）。 */
    private static double mouseX() {
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            return mc == null || mc.mouse == null ? 0 : mc.mouse.getX();
        } catch (Throwable t) {
            return 0;
        }
    }

    private static double mouseY() {
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            return mc == null || mc.mouse == null ? 0 : mc.mouse.getY();
        } catch (Throwable t) {
            return 0;
        }
    }

    /** F8/F9 之类的调试键（只在 PC 上有意义，但类必须能被加载）。 */
    public static final int KEY_F8 = GLFW.GLFW_KEY_F8;
    public static final int KEY_F9 = GLFW.GLFW_KEY_F9;
}
