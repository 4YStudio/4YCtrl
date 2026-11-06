package com.fouryctrl.client;

import com.fouryctrl.FourYCtrl;
import com.fouryctrl.config.ModConfig;
import com.fouryctrl.input.Action;
import com.fouryctrl.input.Button;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 按键绑定界面：把每个手柄按钮映射到某个游戏动作，LT/RT 扳机单独成组。
 *
 * <p>交互方式特意做成「点一下循环切换到下一个动作」而不是 MC 原版的
 * 「点击进入待绑定状态、再按一个键」：手机上没有键盘，而我们的动作集合
 * 只有十来个，循环切换 + 按住手柄按钮试效果是触屏上最快的方式。
 * 右键条目可以往回退一个动作（减量），方向不会锁死。</p>
 *
 * <p>实时反馈：手柄上正按着的按钮，对应条目会高亮成绿色——玩家不用背
 * SOUTH/EAST 对应实体手柄上的哪个键，按一下看哪里亮就知道了。</p>
 *
 * <p>布局（v3）：继承 {@link ScrollScreen}，行高固定 20px 不再挤压；LT/RT 两个扳机
 * 也进这个界面（顶部独立一组）：它们是「持续动作」不是「按下触发」，语义和
 * 普通按钮不同，混在一张表里反而容易让人以为扳机也能绑跳跃。</p>
 */
public class BindingsScreen extends ScrollScreen {
    private static final int COLOR_HINT = 0xFFA8A8A8;
    private static final int COLOR_PRESSED = 0xFF55FF55;
    private static final int COLOR_TRIGGER = 0xFF5588FF;
    private static final int ROW_STEP = 22;
    private static final int WIDGET_H = 20;

    private final Screen parent;
    private final ModConfig cfg;

    /** 界面上每个按钮条目的运行时状态。 */
    private final List<Entry> entries = new ArrayList<>();
    private final List<Entry> triggerEntries = new ArrayList<>();
    /** 每行的「⌨ 键」按钮（把手柄按钮绑到任意 KeyMapping，用来触发别的模组）。 */
    private final List<Entry> keyEntries = new ArrayList<>();
    private static final int KEY_COL_W = 66;

    public BindingsScreen(Screen parent) {
        super(Component.translatable("fouryctrl.bindings.title"));
        this.parent = parent;
        this.cfg = FourYCtrl.config();
    }

    @Override
    protected void init() {
        entries.clear();
        triggerEntries.clear();
        keyEntries.clear();
        clearFixedChildren();
        if (cfg == null || cfg.bindings == null) {
            addFixedChild(net.minecraft.client.gui.components.Button.builder(Component.translatable("gui.done"), b -> onClose())
                    .bounds(this.width / 2 - 75, this.height - 26, 150, 20).build());
            return;
        }

        int widgetWidth = Math.min(300, this.width - 40);
        int x = (this.width - widgetWidth) / 2;
        int bottomY = this.height - 26;
        int viewTop = 48;   // 标题(12) + 提示(24) 之下
        int viewBottom = bottomY - 4;

        int y = viewTop;

        // —— LT/RT 扳机组：持续量（按住=攻击/使用），和普通按钮分开列 ——
        addTriggerEntry(triggerEntries, cfg.triggerLeftAction, "LT", x, y, widgetWidth, () -> cycleTrigger(0));
        y += ROW_STEP;
        addTriggerEntry(triggerEntries, cfg.triggerRightAction, "RT", x, y, widgetWidth, () -> cycleTrigger(1));
        y += ROW_STEP + 6;

        // —— 普通按钮组：15 个 SDL 按钮 ——
        // 每行右侧再放一颗「⌨ 键」：把手柄按钮绑到任意 KeyMapping（含其它模组的），
        // 游戏内按下即转发键盘事件。只在游戏内生效，界面里按钮仍然只做导航。
        int aw = widgetWidth - KEY_COL_W - 4;
        int kx = x + widgetWidth - KEY_COL_W;
        for (Button b : Button.values()) {
            AbstractWidget widget = net.minecraft.client.gui.components.Button.builder(currentLabel(b), btn -> cycle(b, 1))
                    .bounds(x, y, aw, WIDGET_H)
                    .build();
            addRenderableWidget(widget);
            entries.add(new Entry(b.id, widget, x, y, aw, WIDGET_H));
            AbstractWidget keyWidget = net.minecraft.client.gui.components.Button.builder(keyLabel(b), btn -> openKeyPicker(b))
                    .bounds(kx, y, KEY_COL_W, WIDGET_H)
                    .build();
            addRenderableWidget(keyWidget);
            keyEntries.add(new Entry(b.id, keyWidget, kx, y, KEY_COL_W, WIDGET_H));
            y += ROW_STEP;
        }

        setContentBounds(viewTop, viewBottom, x, x + widgetWidth, y - viewTop + 4);

        // 底部两颗固定按钮：不进 children（会被 super.render 的 translate 上移而与内容重叠），
        // 走 ScrollScreen 的固定通道。
        int half = (widgetWidth - 6) / 2;
        addFixedChild(net.minecraft.client.gui.components.Button.builder(Component.translatable("fouryctrl.bindings.reset"), btn -> {
                    cfg.bindings.putAll(ModConfig.defaultBindings());
                    cfg.triggerLeftAction = "attack";
                    cfg.triggerRightAction = "use";
                    if (cfg.keyBindings != null) {
                        cfg.keyBindings.clear();
                    }
                    refreshLabels();
                }).bounds(x, bottomY, half, 20).build());
        addFixedChild(net.minecraft.client.gui.components.Button.builder(Component.translatable("gui.done"), btn -> onClose())
                .bounds(x + half + 6, bottomY, widgetWidth - half - 6, 20).build());
    }

    private void addTriggerEntry(List<Entry> list, String current, String badge,
                                 int x, int y, int width, Runnable onClick) {
        AbstractWidget widget = net.minecraft.client.gui.components.Button.builder(triggerLabel(badge, current), btn -> onClick.run())
                .bounds(x, y, width, WIDGET_H)
                .build();
        addRenderableWidget(widget);
        list.add(new Entry(badge, widget, x, y, width, WIDGET_H));
    }

    private void cycleTrigger(int which) {
        cycleTrigger(which, 1);
    }

    /** 循环切换扳机动作；delta=+1 下一个 / -1 上一个。 */
    private void cycleTrigger(int which, int delta) {
        // 扳机只允许绑定「按住有效」的动作：attack/use/none + 其余持续型动作。
        // edgeTriggered 的动作（开背包之类）按住没有意义，排除掉。
        Action[] pool = new Action[Action.values().length];
        int n = 0;
        for (Action a : Action.values()) {
            if (!a.edgeTriggered) {
                pool[n++] = a;
            }
        }
        String cur = which == 0 ? cfg.triggerLeftAction : cfg.triggerRightAction;
        Action current = Action.byId(cur);
        int idx = -1;
        for (int i = 0; i < n; i++) {
            if (pool[i] == current) {
                idx = i;
                break;
            }
        }
        // 当前值不在池里（手改 JSON 填了边缘动作）时从 pool[0] 重新起步。
        if (idx < 0) {
            idx = delta > 0 ? -1 : 0;
        }
        Action next = pool[((idx + delta) % n + n) % n];
        if (which == 0) {
            cfg.triggerLeftAction = next.id;
        } else {
            cfg.triggerRightAction = next.id;
        }
        refreshLabels();
    }

    /** 循环切换某个按钮绑定的动作；delta=+1 下一个 / -1 上一个。 */
    private void cycle(Button button, int delta) {
        Action current = Action.byId(cfg.bindings.getOrDefault(button.id, Action.NONE.id));
        Action[] all = Action.values();
        // NONE 是第一个，也参与循环（选回 NONE = 解绑）。
        int idx = (current.ordinal() + delta + all.length) % all.length;
        cfg.bindings.put(button.id, all[idx].id);
        refreshLabels();
    }

    /** 右键 = 往回退一个动作（不用一路循环到底才能回头）。只认视口内、滚动条外的条目，
     *  否则会在标题区/底部按钮上误中一个被滚上去的隐藏行。 */
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.button();
        if (button == 1 && mouseY >= viewTop && mouseY < viewBottom) {
            // 视口坐标 → 内容坐标再比对条目位置。
            double cy = contentY(mouseY);
            for (Entry e : entries) {
                if (mouseX >= e.x && mouseX < e.x + e.width && cy >= e.y && cy < e.y + e.height) {
                    cycle(Button.byId(e.key), -1);
                    return true;
                }
            }
            // 扳机条目也支持右键回退。
            for (Entry e : triggerEntries) {
                if (mouseX >= e.x && mouseX < e.x + e.width && cy >= e.y && cy < e.y + e.height) {
                    cycleTrigger(e.key.equals("LT") ? 0 : 1, -1);
                    return true;
                }
            }
            // 「⌨ 键」列右键 = 清掉这条键盘绑定（等同采集界面里的「清除」）。
            for (Entry e : keyEntries) {
                if (mouseX >= e.x && mouseX < e.x + e.width && cy >= e.y && cy < e.y + e.height) {
                    if (cfg.keyBindings != null) {
                        cfg.keyBindings.remove(e.key);
                        refreshLabels();
                    }
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubled);
    }

    /** 打开键盘键采集界面。 */
    private void openKeyPicker(Button button) {
        if (this.minecraft != null) {
            // 先存盘再离开：BindingsScreen.removed() 会 save，但采集界面回来时
            // 本界面是重建的（init 重新跑一遍），标签自然刷新。
            cfg.save();
            this.minecraft.setScreenAndShow(new KeyPickerScreen(this, cfg, button.id));
        }
    }

    /** 从采集界面回来时调用：重建控件让「⌨ 键」标签显示新键。 */
    public void refreshAfterKeyChange() {
        if (this.minecraft != null) {
            // 不先清 children 的话 init 会在旧控件之上再叠一层（点击命中判定会错乱）。
            this.rebuildWidgets();
        }
    }

    private Component keyLabel(Button button) {
        String name = KeyPickerScreen.displayNameOf(cfg, button);
        if (name == null) {
            return Component.translatable("fouryctrl.bindings.key_none");
        }
        return Component.translatable("fouryctrl.bindings.key_entry", Component.literal(name));
    }

    private void refreshLabels() {
        for (Entry e : entries) {
            Button b = Button.byId(e.key);
            if (b != null) {
                e.widget.setMessage(currentLabel(b));
            }
        }
        for (Entry e : keyEntries) {
            Button b = Button.byId(e.key);
            if (b != null) {
                e.widget.setMessage(keyLabel(b));
            }
        }
        // 扳机标签：Entry.key 存的是 "LT"/"RT" 徽章。
        for (Entry e : triggerEntries) {
            String cur = e.key.equals("LT") ? cfg.triggerLeftAction : cfg.triggerRightAction;
            e.widget.setMessage(triggerLabel(e.key, cur));
        }
    }

    private Component currentLabel(Button button) {
        Action a = Action.byId(cfg.bindings.getOrDefault(button.id, Action.NONE.id));
        return Component.translatable("fouryctrl.bindings.entry",
                Component.translatable("fouryctrl.button." + button.id),
                Component.translatable("fouryctrl.action." + a.id));
    }

    private static Component triggerLabel(String badge, String actionId) {
        return Component.translatable("fouryctrl.bindings.trigger",
                Component.literal(badge),
                Component.translatable("fouryctrl.action." + Action.byId(actionId).id));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        // 背景/模糊由基类管线（extractRenderStateWithTooltipAndSubtitles）负责：
        // 它已经 nextStratum + extractBackground 过了，这里再调一次会触发
        // "Can only blur once per frame" 崩溃，所以绝对不要自己再调 extractBackground。
        context.centeredText(this.font, this.title, this.width / 2, 12, 0xFFFFFFFF);
        String hint = Component.translatable("fouryctrl.bindings.hint").getString();
        context.centeredText(this.font, hint, this.width / 2, 24, COLOR_HINT);
        String hint2 = Component.translatable("fouryctrl.bindings.key_hint").getString();
        context.centeredText(this.font, hint2, this.width / 2, 34, COLOR_HINT);

        var gamepad = FourYCtrl.gamepad();

        // 内容随滚动整体上移；scissor 裁掉滚出视口的控件；命中判定已在 mouseClicked/mouseDragged 里换算。
        scissorContent(context);
        context.pose().pushMatrix();
        context.pose().translate(0, -scroll);
        super.extractRenderState(context, mouseX, (int) contentY(mouseY), delta);
        // 手柄按钮实时高亮：按住哪个按钮，哪一行变绿。玩家由此确认
        // 「界面里的 SOUTH 就是我手柄上的 A 键」。扳机行用蓝色。
        if (gamepad != null && gamepad.state().connected) {
            for (Entry e : entries) {
                Button b = Button.byId(e.key);
                if (b != null && gamepad.state().isDown(b)) {
                    context.fill(e.x, e.y + e.height, e.x + e.width, e.y + e.height + 1, COLOR_PRESSED);
                }
            }
        }
        context.pose().popMatrix();
        unscissor(context);

        drawScrollbar(context);

        // 固定按钮最后画：在滚动裁剪之外，永远落在底部固定区。
        renderFixed(context, mouseX, mouseY, delta);
    }

    @Override
    public void removed() {
        if (cfg != null) {
            cfg.save();
        }
        super.removed();
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreenAndShow(this.parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }

    /** key：普通条目存按钮 id（供反查 Button），扳机条目存 "LT"/"RT" 徽章。 */
    private static final class Entry {
        final String key;
        final AbstractWidget widget;
        final int x;
        final int y;
        final int width;
        final int height;

        Entry(String key, AbstractWidget widget, int x, int y, int width, int height) {
            this.key = key;
            this.widget = widget;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }
    }
}
