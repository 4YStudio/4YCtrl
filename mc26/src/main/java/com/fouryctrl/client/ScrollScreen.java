package com.fouryctrl.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 带纵向滚动条的界面基类。
 *
 * <p>为什么不用原版 {@code ElementListWidget}：它自带表头/边框/焦点渲染，
 * 在手机高 GUI 缩放下可用高度只有 200 出头，嵌套滚动区域的可视反馈反而更差；
 * 而且我们的行是「按钮 + 附加渲染（高亮线、说明文字）」，自己排更直。</p>
 *
 * <p>滚动实现是「内容坐标系 + scroll 偏移」：所有控件按未滚动的绝对 y 摆放，
 * 渲染时整体上移 {@code scroll}px；滚动的只是绘制与命中判定，控件坐标不变，
 * 所以不需要在滚动时移动任何 widget。</p>
 *
 * <p>命中判定：滚动后 widget 的实际渲染位置 = 原 y - scroll。MC 的输入派发用
 * widget 自己的 x/y 判定，直接让 Screen.mouseClicked 的 mouse 坐标加回 scroll
 * 即可让原版命中逻辑照常工作（视口坐标 → 内容坐标的逆变换）。滚轮与滚动条
 * 拖动都改 {@code scroll}，并夹紧到 [0, maxScroll]。</p>
 */
public abstract class ScrollScreen extends Screen {
    /** 滚动条视觉宽度。 */
    private static final int SCROLLBAR_W = 3;
    /** 滚动条左右各留的空白，内容别贴到条上。 */
    private static final int SCROLLBAR_MARGIN = 6;

    /** 当前滚动偏移（px，向下滚增大 = 内容上移）。 */
    protected int scroll;
    /** 内容总高度（init 里由子类算好）。 */
    protected int contentHeight;
    /** 视口顶部（标题/提示下面），内容从这里开始绘制。 */
    protected int viewTop;
    /** 视口底部（底部按钮上面）。 */
    protected int viewBottom;

    /** 滚动条拖动状态：-1 = 没在拖，否则是拖动起始时滑块顶部在条上的相对位置。
     *  必须显式初始化成 -1：int 默认值是 0，而 mouseDragged 用 {@code >= 0} 判拖拽，
     *  默认 0 会让「根本没点滚动条」的第一次拖拽（例如拖一个滑杆）被当成在拖滚动条。 */
    private int draggingThumb = -1;
    private double dragStartMouseY;
    private double dragStartScroll;

    /** 内容 x 范围（给滚动条画在右边；也给子类布局用）。 */
    protected int contentLeft;
    protected int contentRight;

    /** 固定子控件（如底部按钮行）：不进 children、不随内容滚动，也不被 translate 波及。
     *  子类必须在 init 开头调 {@link #clearFixedChildren()}（resize 会重新 init）。 */
    private final List<AbstractWidget> fixedChildren = new ArrayList<>();

    protected ScrollScreen(Component title) {
        super(title);
    }

    /** 子类在 init 里按好布局后调用：登记内容高度。 */
    protected void setContentBounds(int viewTop, int viewBottom, int contentLeft, int contentRight, int contentHeight) {
        this.viewTop = viewTop;
        this.viewBottom = viewBottom;
        this.contentLeft = contentLeft;
        this.contentRight = contentRight;
        this.contentHeight = contentHeight;
        this.scroll = 0;
        // resize/重开会重新 init：上一轮没走完的拖拽状态必须清掉，
        // 否则新界面的第一次拖拽会带着旧坐标跳一下。
        this.draggingThumb = -1;
        this.dragStartMouseY = 0;
        this.dragStartScroll = 0;
    }

    /** 内容超过视口的最大滚动量。 */
    protected int maxScroll() {
        return Math.max(0, contentHeight - (viewBottom - viewTop));
    }

    private void clampScroll() {
        scroll = Math.max(0, Math.min(maxScroll(), scroll));
    }

    /** 命中判定用：把视口 y 换算成内容 y（widget 原始坐标）。 */
    protected double contentY(double viewportY) {
        return viewportY + scroll;
    }

    /** 渲染偏移：内容 y → 视口 y（为负 = 已滚出视口上方，子类可用来裁剪）。 */
    protected int viewY(int contentY) {
        return contentY - scroll;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (maxScroll() > 0) {
            scroll -= (int) Math.signum(vertical) * 24;
            clampScroll();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.button();
        // 固定子控件（底部按钮等）优先：坐标就是视口坐标，不做 contentY 变换。
        if (clickFixed(event)) {
            return true;
        }
        // 再给滚动条：点在条上（或滑块上）进入拖动，不再下发给内容。
        if (button == 0 && maxScroll() > 0 && isOverScrollbar(mouseX)) {
            int thumbTop = thumbTop();
            int thumbH = thumbHeight();
            if (mouseY >= thumbTop && mouseY < thumbTop + thumbH) {
                draggingThumb = 0;
            } else {
                // 点空轨道：把滑块中心跳到点击处并开始拖动。
                scroll = (int) ((mouseY - viewTop - thumbH / 2.0) / Math.max(1, trackHeight()) * maxScroll());
                clampScroll();
                draggingThumb = 0;
            }
            dragStartMouseY = mouseY;
            dragStartScroll = scroll;
            return true;
        }
        // 内容命中：把视口坐标还原成内容坐标，交给原版派发。
        // 只在视口内转发：视口外（底部固定区/标题区）没有内容控件，
        // 否则滚出视口的控件仍会被点到（看不见却响应）。
        if (mouseY >= viewTop && mouseY < viewBottom) {
            return super.mouseClicked(withY(event, contentY(mouseY)), doubled);
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
        double mouseX = event.x();
        double mouseY = event.y();
        if (draggingThumb >= 0 && maxScroll() > 0) {
            // 按像素比例拖动：手指移动 1px = 内容滚 trackHeight/maxScroll px。
            double scale = (double) maxScroll() / Math.max(1, trackHeight());
            scroll = (int) Math.round(dragStartScroll + (mouseY - dragStartMouseY) * scale);
            clampScroll();
            return true;
        }
        return super.mouseDragged(withY(event, contentY(mouseY)), deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        draggingThumb = -1;
        releaseFixed(event);
        return super.mouseReleased(withY(event, contentY(event.y())));
    }

    /** 视口坐标 → 内容坐标：改 y 后重新构造事件（record 不可变）。 */
    protected static MouseButtonEvent withY(MouseButtonEvent event, double y) {
        return new MouseButtonEvent(event.x(), y, event.buttonInfo());
    }

    private int trackLeft() {
        return contentRight + 2;
    }

    private int trackHeight() {
        return Math.max(1, viewBottom - viewTop);
    }

    private int thumbHeight() {
        int track = trackHeight();
        if (contentHeight <= 0) {
            return track;
        }
        return Math.max(24, track * Math.min(track, contentHeight) / contentHeight);
    }

    private int thumbTop() {
        int track = trackHeight();
        int max = maxScroll();
        if (max == 0) {
            return viewTop;
        }
        return viewTop + (track - thumbHeight()) * scroll / max;
    }

    private boolean isOverScrollbar(double mouseX) {
        // 左边界不给余量：条紧贴内容右缘，若往左多算几 px 会吃掉控件右缘的点击。
        return mouseX >= trackLeft() && mouseX <= trackLeft() + SCROLLBAR_W + 4;
    }

    // ------------------------------------------------------------------ 固定子控件

    /** 登记一个不随内容滚动的子控件（子类 init 里调用）。 */
    protected AbstractWidget addFixedChild(AbstractWidget widget) {
        fixedChildren.add(widget);
        return widget;
    }

    /** 子类 init 开头调用：resize 会重新 init，先清掉上一轮的固定控件。 */
    protected void clearFixedChildren() {
        fixedChildren.clear();
    }

    /** 渲染固定子控件：在子类 render 的滚动区之外调用（不做 translate、不做 scissor）。 */
    protected void renderFixed(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        for (AbstractWidget w : fixedChildren) {
            if (w.visible) {
                w.extractRenderState(context, mouseX, mouseY, delta);
            }
        }
    }

    private boolean clickFixed(MouseButtonEvent event) {
        for (AbstractWidget w : fixedChildren) {
            if (w.mouseClicked(event, false)) {
                this.setFocused(w);
                if (event.button() == 0) {
                    this.setDragging(true);
                }
                return true;
            }
        }
        return false;
    }

    private void releaseFixed(MouseButtonEvent event) {
        for (AbstractWidget w : fixedChildren) {
            w.mouseReleased(event);
        }
    }

    /** 在内容之上、视口之内画滚动条。子类在 super.render 前调用（画在控件下面），
     *  或渲染末尾调用（画在控件上面）。这里选后者：条压在控件上更醒目。 */
    protected void drawScrollbar(GuiGraphicsExtractor context) {
        if (maxScroll() <= 0) {
            return;
        }
        int x = trackLeft();
        context.fill(x, viewTop, x + SCROLLBAR_W, viewTop + trackHeight(), 0x33FFFFFF);
        int top = thumbTop();
        context.fill(x, top, x + SCROLLBAR_W, top + thumbHeight(), 0x88FFFFFF);
    }

    /** 内容裁剪：让滚出视口的控件不画出来（scissor）。 */
    protected void scissorContent(GuiGraphicsExtractor context) {
        context.enableScissor(contentLeft - 2, viewTop, this.width - 2, viewBottom);
    }

    protected void unscissor(GuiGraphicsExtractor context) {
        context.disableScissor();
    }
}
