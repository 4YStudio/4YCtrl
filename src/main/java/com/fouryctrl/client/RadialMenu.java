package com.fouryctrl.client;

import com.fouryctrl.config.ModConfig;
import com.fouryctrl.input.Action;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/**
 * 按住某个按钮弹出的「径向动作轮盘」。
 *
 * <p>为什么需要它：手柄按钮就那么十几个，还都被基础操作（跳/背包/丢弃/快捷栏）占满了，
 * 剩下那些「偶尔用一次」的动作（聊天、切视角、玩家列表、隐藏 HUD）只能绑到十字键上，
 * 而十字键在界面里又要用来导航 —— 一个键两种身份，玩家记不住。</p>
 *
 * <p>轮盘把这些动作收进一个「按住才出现」的环：按住 BACK → 环弹出 → 右摇杆指方向 →
 * 松手执行。平时那枚按钮什么都不干，界面里十字键也能安心做导航。
 * 槽位内容写在 {@code fouryctrl.json} 的 {@code radialActions} 里，默认 8 个槽。</p>
 *
 * <p>绘制上刻意不用扇形：{@code DrawContext} 只能画矩形，画扇形得贴几百个小方块，
 * 手机上不划算。这里用「沿圆周摆一圈小徽章 + 一圈细环」实现，视觉上就是径向菜单，
 * 每帧只有几十个矩形。</p>
 */
public final class RadialMenu {
    private RadialMenu() {
    }

    /** 最多 8 个槽（超过 8 个在手机上指不准）。 */
    public static final int MAX_SLOTS = 8;

    private static final Action[] SLOTS = new Action[MAX_SLOTS];
    private static int slotCount;

    private static boolean open;
    private static int index;
    private static long openedAtMillis;

    static {
        for (int i = 0; i < MAX_SLOTS; i++) {
            SLOTS[i] = Action.NONE;
        }
    }

    public static boolean isOpen() {
        return open;
    }

    /** 关闭并清空（换界面、断手柄、关总开关时调用）。 */
    public static void reset() {
        open = false;
        index = 0;
    }

    /** 把配置里的 {@code radialActions} 解析成槽位（逗号分隔的动作 id）。 */
    private static void parse(ModConfig cfg) {
        String raw = cfg.radialActions == null ? "" : cfg.radialActions;
        String[] parts = raw.split(",");
        slotCount = 0;
        for (String p : parts) {
            if (slotCount >= MAX_SLOTS) {
                break;
            }
            Action a = Action.byId(p);
            if (a != Action.NONE) {
                SLOTS[slotCount++] = a;
            }
        }
    }

    /**
     * 每 tick 推进轮盘状态。
     *
     * @param held    轮盘键当前是否按住
     * @param rx      右摇杆 X（已减死区、-1~1）
     * @param ry      右摇杆 Y
     * @return 松手时选中的动作；没有选中或轮盘没开时返回 {@link Action#NONE}
     */
    public static Action tick(ModConfig cfg, boolean held, float rx, float ry) {
        if (cfg == null || !cfg.radialMenu || cfg.radialActions == null) {
            open = false;
            return Action.NONE;
        }
        if (!open) {
            if (held) {
                parse(cfg);
                if (slotCount == 0) {
                    return Action.NONE;
                }
                open = true;
                openedAtMillis = System.currentTimeMillis();
                index = 0;
                // 一开就按摇杆方向选，玩家的习惯是先推再松。
                updateIndex(rx, ry);
            }
            return Action.NONE;
        }
        if (held) {
            updateIndex(rx, ry);
            return Action.NONE;
        }
        // 松手：只有按住超过 120ms 才算「认真选过」，避免误触弹一下就触发。
        open = false;
        if (System.currentTimeMillis() - openedAtMillis < 120L) {
            return Action.NONE;
        }
        return index < slotCount ? SLOTS[index] : Action.NONE;
    }

    private static void updateIndex(float rx, float ry) {
        float mag = (float) Math.sqrt(rx * rx + ry * ry);
        if (mag < 0.45f) {
            // 摇杆回中：保持当前选择（玩家可能只是松了下手指）。
            return;
        }
        double ang = Math.atan2(ry, rx);            // 屏幕坐标，y 向下
        double frac = (ang + Math.PI / 2) / (Math.PI * 2);  // 0 = 正上方
        frac -= Math.floor(frac);
        int i = (int) Math.round(frac * slotCount);
        index = ((i % slotCount) + slotCount) % slotCount;
    }

    public static void draw(DrawContext context) {
        if (!open) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getWindow() == null) {
            return;
        }
        var tr = client.textRenderer;
        // DrawContext 没有 getScaledWidth/Height（那是 Screen 的方法），
        // 缩放后的 GUI 尺寸只能从窗口拿：GUI 坐标 = 物理像素 / scaleFactor。
        int sw = client.getWindow().getScaledWidth();
        int sh = client.getWindow().getScaledHeight();
        int cx = sw / 2;
        int cy = sh / 2;
        float radius = Math.min(sw, sh) * 0.27f;

        // 背板：中间一块半透明圆盘，压暗游戏画面，让环看得清。
        // 用横向条带近似圆（每条 4px 高），比逐像素便宜得多。
        for (int y = -(int) radius; y < (int) radius; y += 4) {
            int half = (int) Math.sqrt(Math.max(0, radius * radius - (float) y * y));
            context.fill(cx - half, cy + y, cx + half, cy + y + 4, 0x66000000);
        }
        // 细环（48 个点）。
        for (int i = 0; i < 48; i++) {
            double a = Math.PI * 2 * i / 48.0;
            int x = cx + (int) (Math.cos(a) * radius);
            int y = cy + (int) (Math.sin(a) * radius);
            context.fill(x - 1, y - 1, x + 1, y + 1, 0x60FFFFFF);
        }

        for (int i = 0; i < slotCount; i++) {
            double a = -Math.PI / 2 + Math.PI * 2 * i / slotCount;
            int x = cx + (int) (Math.cos(a) * radius);
            int y = cy + (int) (Math.sin(a) * radius);
            String label = label(SLOTS[i]);
            int w = tr.getWidth(label) + 10;
            int h = 13;
            int left = x - w / 2;
            int top = y - h / 2;
            boolean sel = i == index;
            context.fill(left, top, left + w, top + h, sel ? 0xE02E6B2E : 0xC0202020);
            context.fill(left, top, left + w, top + 1, sel ? 0xFF8FE08F : 0x50FFFFFF);
            context.drawTextWithShadow(tr, Text.literal(label), left + 5, top + 3,
                    sel ? 0xFFFFFFFF : 0xFFB0B0B0);
        }

        // 中央提示：松手执行 / 现在选中的是什么。
        String center = Text.translatable("fouryctrl.radial.hint").getString();
        context.drawCenteredTextWithShadow(tr, Text.literal(center), cx, cy - 10, 0xFFD0D0D0);
        if (index < slotCount) {
            context.drawCenteredTextWithShadow(tr, Text.literal(label(SLOTS[index])), cx, cy + 2,
                    0xFFFFFFFF);
        }
    }

    private static String label(Action a) {
        if (a == null || a == Action.NONE) {
            return "-";
        }
        return Text.translatable("fouryctrl.action." + a.id).getString();
    }

    /** 调试/诊断用：当前槽位列表。 */
    public static String describe(ModConfig cfg) {
        if (cfg == null || !cfg.radialMenu) {
            return "关闭";
        }
        StringBuilder sb = new StringBuilder("BACK 按住");
        sb.append(" [");
        String[] parts = (cfg.radialActions == null ? "" : cfg.radialActions).split(",");
        int n = 0;
        for (String p : parts) {
            Action a = Action.byId(p);
            if (a == Action.NONE) {
                continue;
            }
            if (n++ > 0) {
                sb.append(", ");
            }
            sb.append(a.id);
            if (n >= MAX_SLOTS) {
                break;
            }
        }
        return sb.append(']').toString();
    }
}
