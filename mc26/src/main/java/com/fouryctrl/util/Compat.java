package com.fouryctrl.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * 26.x 兼容层（官方名源码树专用，极度瘦身）。
 *
 * <p>26.3 的 API 与 1.21.11 已高度接近（{@code GuiGraphicsExtractor} 的 2D 矩阵栈、
 * record 化输入事件），绝大多数调用点直接写 26.3 的写法即可，不需要转发。</p>
 *
 * <h2>26.1 → 26.2 的 GUI 大重构（本类存在的唯一原因）</h2>
 * <p>26.1 的当前界面是 {@code Minecraft.screen} <b>public 字段</b> + {@code setScreen(Screen)}，
 * HUD 绘制挂在 {@code Gui.extractRenderState}（<b>没有</b> {@code Hud} 类），
 * 而 {@code Options} 还留着 {@code public boolean hideGui}。</p>
 * <p>26.2 起这几样全部搬家：{@code Minecraft} 没有 screen 字段了，改用
 * {@code client.gui.screen()} / {@code client.gui.setScreen(...)}；HUD 拆出独立类
 * {@code net.minecraft.client.gui.Hud}（{@code gui.hud.toggle()} / {@code hud.isHidden()}）；
 * {@code Options.hideGui} 被删。</p>
 *
 * <p><b>验证记录</b>：26.1 == 26.1.1 == 26.1.2 字节码级相同（{@code Minecraft.class} 的
 * javap md5 一致），所以 Stonecutter 的 26.1 节点同时覆盖这三个补丁版，
 * 产物里 {@code minecraft} 依赖范围放宽成 {@code [26.1,26.2)}。</p>
 */
public final class Compat {
    private Compat() {
    }

    /**
     * 当前<b>实际生效</b>的帧上限。
     *
     * <p>26.x 里 {@code Window} 已经没有 {@code framerateLimit} 字段（1.21.4 起就删了），
     * 限帧改由 {@code Minecraft.getFramerateLimitTracker().getFramerateLimit()} 现算：
     * 它会按 {@code Options.inactivityFpsLimit()} 的档位在
     * 「玩家设定 / 窗口最小化 10 / 短 AFK 10 / 长 AFK 60 / 无世界 30」之间切换。</p>
     *
     * <p>注意语义变化：26.x 的「非玩家上限」（AFK 等）是<b>官方有意为之的低帧</b>，
     * 不是 FCL 那种误判，所以 FrameGuard 在 26.x 上不应该把它们当外部限帧拦掉 ——
     * 见 {@code FrameGuard.isExternalThrottle}。</p>
     *
     * @return 当前帧上限；取不到返回 -1
     */
    public static int windowFramerateLimit() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.getFramerateLimitTracker() == null) {
                return -1;
            }
            return mc.getFramerateLimitTracker().getFramerateLimit();
        } catch (Throwable t) {
            return -1;
        }
    }

    // ---------------------------------------------------------------- 当前界面

    /** 当前打开的界面（26.1 是 {@code Minecraft.screen} 字段，26.2+ 是 {@code Gui.screen()}）。 */
    public static Screen currentScreen(Minecraft mc) {
        if (mc == null) {
            return null;
        }
        //? if <26.2 {
        /*return mc.screen;
        *///?} else {
        return mc.gui.screen();
        //?}
    }

    /** 切换界面（26.1 是 {@code Minecraft.setScreen}，26.2+ 是 {@code Gui.setScreen}）。 */
    public static void openScreen(Minecraft mc, Screen screen) {
        if (mc == null) {
            return;
        }
        //? if <26.2 {
        /*mc.setScreen(screen);
        *///?} else {
        mc.gui.setScreen(screen);
        //?}
    }

    // ---------------------------------------------------------------- HUD 开关

    /**
     * 切换 HUD 显隐。
     *
     * <p>26.2+ 走 {@code gui.hud.toggle()}；26.1 没有 {@code Hud} 类，但有
     * {@code Options.hideGui} 字段（26.2 起删掉），语义一样是「隐藏整层 HUD」。</p>
     */
    public static void toggleHud(Minecraft mc) {
        if (mc == null) {
            return;
        }
        //? if <26.2 {
        /*mc.options.hideGui = !mc.options.hideGui;
        *///?} else {
        mc.gui.hud.toggle();
        //?}
    }

    /** HUD 是否被隐藏（与 {@link #toggleHud} 对应）。 */
    public static boolean isHudHidden(Minecraft mc) {
        if (mc == null) {
            return false;
        }
        //? if <26.2 {
        /*return mc.options.hideGui;
        *///?} else {
        return mc.gui.hud.isHidden();
        //?}
    }
}
