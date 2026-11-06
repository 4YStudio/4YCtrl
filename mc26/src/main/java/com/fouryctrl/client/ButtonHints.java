package com.fouryctrl.client;

import com.fouryctrl.FourYCtrl;
import com.fouryctrl.config.ModConfig;
import com.fouryctrl.input.Action;
import com.fouryctrl.input.Button;
import com.fouryctrl.input.GamepadManager;
import com.fouryctrl.util.Compat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Controlify 式的按键提示：在<b>左下角 / 右下角</b>各叠一列竖排的
 * 「手柄按钮 → 现在能做什么」速查。
 *
 * <p>为什么不横排一整条：手机横屏本来就窄，横排一行放不下几条，而且会盖住
 * 底部血条/经验条。竖排贴边只占两侧窄条，中间视野完全不受影响。</p>
 *
 * <p>内容随状态变化（这是和普通按键提示最大的区别）：</p>
 * <ul>
 *   <li>游戏内：右列是动作（跳/丢/背包/副手/攻击/使用/暂停），左列是状态
 *       （潜行开没开、疾跑开没开、主手拿的是什么、副手拿的是什么）。</li>
 *   <li>主手物品类型会改提示文字：空手=挥拳，方块=放置，食物=吃，武器/工具=攻击。</li>
 *   <li>容器界面（背包/箱子/工作台…）：右列变成物品操作（左键/右键/快速移动/丢弃），
 *       左列是格子导航。</li>
 *   <li>其它界面：右列是点击/返回，左列是光标移动。</li>
 * </ul>
 *
 * <p>按钮名一律从当前绑定表反查（玩家改键后提示自动跟随）；容器操作是写死的
 * 物理按钮，所以直接取按钮短名。</p>
 */
public final class ButtonHints {
    private ButtonHints() {
    }

    private record Row(String badge, String text) {
    }

    /**
     * 一列的排版结果：行内容 + 每行量好的像素宽度。
     *
     * <p>量宽度要调 {@code Font.getWidth()}，而它会走一遍字宽表；每帧对十几行
     * 各量两次（徽标 + 文字）在手机上不算便宜。缓存起来只在内容变化时重算。</p>
     */
    private static final class Column {
        final List<Row> rows;
        final int[] badgeW;
        final int[] textW;

        Column(List<Row> rows, int[] badgeW, int[] textW) {
            this.rows = rows;
            this.badgeW = badgeW;
            this.textW = textW;
        }

        boolean isEmpty() {
            return rows.isEmpty();
        }
    }

    /**
     * 缓存失效签名：把「影响提示内容的一切」压成一个字符串。
     *
     * <p>覆盖：分档设置、绑定表、潜行/疾跑状态、主副手物品（id + 数量 + 耐久）、
     * 配方书开关、界面类型。任何一项变了就重建这两列。</p>
     */
    private static String signature = "";
    private static Column cachedLeft;
    private static Column cachedRight;

    private static Column build(List<Row> rows) {
        var tr = Minecraft.getInstance().font;
        int n = rows.size();
        int[] bw = new int[n];
        int[] tw = new int[n];
        for (int i = 0; i < n; i++) {
            bw[i] = Math.max(14, tr.width(rows.get(i).badge()) + 6);
            tw[i] = tr.width(rows.get(i).text());
        }
        return new Column(rows, bw, tw);
    }

    /** 生成当前状态的特征字符串（内容一变就重建）。 */
    private static String computeSignature(Minecraft client, boolean hud, boolean book) {
        ModConfig cfg = FourYCtrl.config();
        GamepadManager gp = FourYCtrl.gamepad();
        StringBuilder sb = new StringBuilder(64);
        // 界面种类必须进签名：容器界面（背包/箱子）的提示内容是「快捷移动/丢出一半」这类
        // 格子操作，普通界面（选项/单人游戏）是「十字键微调/LB RB 滚动」—— 两者原先都是
        // 'S-'，从背包直接进选项界面时签名不变，会复用上一套列内容（提示与当前界面错位）。
        char kind;
        if (hud) {
            kind = 'H';
        } else if (book) {
            kind = 'B';
        } else {
            kind = client != null && Compat.currentScreen(client) instanceof AbstractContainerScreen<?> ? 'C' : 'P';
        }
        sb.append(kind);
        sb.append(cfg == null ? "?" : cfg.hintMode);
        sb.append('/').append(cfg == null || cfg.showButtonHints ? 1 : 0);
        sb.append('/').append(gp != null && gp.wantSneak() ? 1 : 0);
        sb.append(gp != null && gp.sprintActive() ? 1 : 0);
        sb.append('/').append(cfg == null || cfg.bindings == null ? 0 : cfg.bindings.hashCode());
        // 键盘键绑定（按钮 → 别的模组的 KeyMapping）：也要进签名，否则刚绑上时装
        // 载的提示列不会刷新，玩家看不到自己刚加的那一条提示。
        sb.append('/').append(cfg == null || cfg.keyBindings == null ? 0 : cfg.keyBindings.hashCode());
        // 语言：文案全是 Component.translatable(...).getString() 烘出来的纯字符串，
        // 不把语言算进签名的话切语言后提示条会一直是旧语言。
        sb.append('/').append(client == null ? "?" : client.options.languageCode);
        if (client != null && client.player != null) {
            ItemStack main = client.player.getMainHandItem();
            ItemStack off = client.player.getItemInHand(InteractionHand.OFF_HAND);
            sb.append('/').append(stackKey(main)).append('/').append(stackKey(off));
        }
        return sb.toString();
    }

    private static String stackKey(ItemStack s) {
        if (s == null || s.isEmpty()) {
            return "0";
        }
        // 物品、数量、耐久都影响文案（空手/武器/工具/耐久告危）。
        return System.identityHashCode(s.getItem()) + ":" + s.getCount() + ":" + s.getDamageValue();
    }

    /** 由 ScreenMixin 在每个界面渲染末尾调用（画在一切内容之上）。 */
    public static void draw(Screen screen, GuiGraphicsExtractor context) {
        if (!enabled()) {
            return;
        }
        // 自己的设置/绑定界面不用提示条（底部有固定按钮行，会重叠）。
        if (screen instanceof ScrollScreen) {
            return;
        }
        if (screen instanceof AbstractContainerScreen<?>) {
            boolean book = bookOpen();
            ensureCache(null, false, book);
            drawColumns(context, cachedLeft, cachedRight);
        } else {
            ensureCache(null, false, false);
            drawColumns(context, cachedLeft, cachedRight);
        }
    }

    /**
     * 按需重建两列缓存。
     *
     * <p>签名不变就直接复用上次的排版结果 —— 这是「每帧重建字符串」的主要修复点：
     * 以前每帧都会做十几次翻译查找 + 字宽测量。</p>
     */
    private static void ensureCache(Minecraft client, boolean hud, boolean book) {
        Minecraft mc = client != null ? client : Minecraft.getInstance();
        String sig = computeSignature(mc, hud, book);
        if (sig.equals(signature) && cachedLeft != null && cachedRight != null) {
            return;
        }
        signature = sig;
        if (hud) {
            cachedLeft = build(gameLeft(mc));
            cachedRight = build(gameRight(mc));
        } else if (book) {
            cachedLeft = build(containerLeft());
            cachedRight = build(containerRight());
        } else if (mc != null && Compat.currentScreen(mc) instanceof AbstractContainerScreen<?>) {
            cachedLeft = build(containerLeft());
            cachedRight = build(containerRight());
        } else {
            cachedLeft = build(screenLeft());
            cachedRight = build(screenRight());
        }
    }

    /** 一次性诊断：首次进入 drawHud 时 dump 各开关状态，用于真机定位「HUD 文字不显示」。 */
    private static boolean hudDiagLogged = false;

    /**
     * 一次性诊断（第二段）：确认真的走到了画字那一步。
     *
     * <p>第一段 {@code [diag]} 只能说明「注入被调到了」，但模组自己的 {@code enabled()}/
     * {@code player}/{@code hudHidden} 任何一条不满足就会提前 return，那样日志里看不到
     * 文字相关的信息。这里在真正提交文字前再打一条，把行数、颜色、坐标、字宽都吐出来，
     * 一条日志就能区分「没走到画字」「画了但被丢弃」「画了且已提交」三种情况。</p>
     */
    private static boolean drawDiagLogged = false;

    /** 由 InGameHud 的 TAIL 注入调用：游戏内 HUD 上的提示。 */
    public static void drawHud(GuiGraphicsExtractor context) {
        if (!hudDiagLogged && FourYCtrl.debugOn()) {
            hudDiagLogged = true;
            ModConfig dc = FourYCtrl.config();
            GamepadManager dg = FourYCtrl.gamepad();
            FourYCtrl.LOG.info("[diag] drawHud 首次调用：showButtonHints={}, hintMode={}, cfgEnabled={}, "
                            + "gpActive={}, player={}, screen={}, hudHidden={}",
                    dc == null ? "cfg-null" : dc.showButtonHints,
                    dc == null ? "cfg-null" : dc.hintMode,
                    dc != null && dc.enabled,
                    dg != null && dg.isActive(),
                    Minecraft.getInstance() != null && Minecraft.getInstance().player != null,
                    Minecraft.getInstance() != null && Compat.currentScreen(Minecraft.getInstance()) != null,
                    Compat.isHudHidden(Minecraft.getInstance()));
        }
        if (!enabled()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null || Compat.currentScreen(client) != null) {
            return;
        }
        if (Compat.isHudHidden(client)) {
            return; // F1 隐藏 HUD 时提示也一起收起来
        }
        ensureCache(client, true, false);
        if (!drawDiagLogged && FourYCtrl.debugOn()) {
            drawDiagLogged = true;
            Column l = cachedLeft;
            Column r = cachedRight;
            Row sample = null;
            if (l != null && !l.isEmpty()) {
                sample = l.rows.get(0);
            } else if (r != null && !r.isEmpty()) {
                sample = r.rows.get(0);
            }
            FourYCtrl.LOG.info("[diag2] 即将画 HUD 提示：leftRows={}, rightRows={}, gui={}x{}, badgeColor=0xFFFFFFFF, textColor=0xFFE0E0E0, sample='{}'",
                    l == null ? -1 : l.rows.size(),
                    r == null ? -1 : r.rows.size(),
                    context.guiWidth(), context.guiHeight(),
                    sample == null ? "<无行>" : sample.badge() + "/" + sample.text());
        }
        drawColumns(context, cachedLeft, cachedRight);
    }

    private static boolean enabled() {
        ModConfig cfg = FourYCtrl.config();
        GamepadManager gp = FourYCtrl.gamepad();
        // 用 isActive()（启用 + 已连接）而不是 isCursorVisible()：后者只在光标被摇杆
        // 推出来之后才为 true，而游戏内 HUD 上光标永远是隐藏的，用它会导致
        // 「游戏内永远不显示提示」。
        return cfg != null && cfg.showButtonHints && !"off".equals(cfg.hintMode)
                && gp != null && gp.isActive();
    }

    /**
     * 是否显示「玩家早已背下来的常用键」。
     *
     * <p>partial 模式下游戏内不显示跳/背包/热点栏这些天天按的键，
     * 只留状态与物品相关的行，把画面留出来。</p>
     */
    private static boolean showCommon() {
        ModConfig cfg = FourYCtrl.config();
        return cfg == null || !"partial".equals(cfg.hintMode);
    }

    // ------------------------------------------------------------------ 内容

    /** 游戏内左列：状态。 */
    private static List<Row> gameLeft(Minecraft client) {
        List<Row> rows = new ArrayList<>();
        GamepadManager gp = FourYCtrl.gamepad();
        ModConfig cfg = FourYCtrl.config();

        // 潜行 / 疾跑：把「切换状态」直接写在提示里，玩家一眼知道现在开没开。
        String sneakBadge = badge(cfg, Action.SNEAK, Button.RIGHT_STICK);
        if (sneakBadge != null) {
            rows.add(new Row(sneakBadge, translated("fouryctrl.hints.sneak_state",
                    Component.translatable(gp != null && gp.wantSneak()
                            ? "fouryctrl.state.on" : "fouryctrl.state.off"))));
        }
        String sprintBadge = badge(cfg, Action.SPRINT, Button.LEFT_STICK);
        if (sprintBadge != null) {
            rows.add(new Row(sprintBadge, translated("fouryctrl.hints.sprint_state",
                    Component.translatable(gp != null && gp.sprintActive()
                            ? "fouryctrl.state.on" : "fouryctrl.state.off"))));
        }

        // 主手 / 副手：手上是空的还是有东西、是什么类型，直接决定右列显示哪些动作。
        ItemStack main = client.player.getMainHandItem();
        ItemStack off = client.player.getItemInHand(InteractionHand.OFF_HAND);
        rows.add(new Row(translated("fouryctrl.hints.hand_main_short"),
                main.isEmpty() ? translated("fouryctrl.hints.hand_empty") : itemName(main)));
        if (!off.isEmpty()) {
            rows.add(new Row(translated("fouryctrl.hints.hand_off_short"), itemName(off)));
        }
        return rows;
    }

    /** 游戏内右列：动作。 */
    private static List<Row> gameRight(Minecraft client) {
        List<Row> rows = new ArrayList<>();
        ModConfig cfg = FourYCtrl.config();
        ItemStack main = client.player.getMainHandItem();

        // partial 模式下跳/背包/暂停/物品栏这些天天按的键不占画面，只留状态与
        // 物品相关提示（玩家已经背下来的东西没必要一直刷屏）。
        if (showCommon()) {
            add(rows, cfg, Action.JUMP, Button.SOUTH, "fouryctrl.hints.jump");
        }
        // 空手时不让丢（丢不出去，提示只会误导）。
        if (!main.isEmpty()) {
            add(rows, cfg, Action.DROP, Button.EAST, "fouryctrl.hints.drop");
        }
        if (showCommon()) {
            add(rows, cfg, Action.INVENTORY, Button.WEST, "fouryctrl.hints.inventory");
        }
        if (!main.isEmpty() || !client.player.getItemInHand(InteractionHand.OFF_HAND).isEmpty()) {
            add(rows, cfg, Action.SWAP_OFFHAND, Button.NORTH, "fouryctrl.hints.swap_offhand");
        }

        // 攻击/使用的文字跟主手物品类型走：手感上这就是玩家最想确认的东西。
        String attackBadge = triggerBadge(cfg, true, Action.ATTACK);
        if (attackBadge != null) {
            rows.add(new Row(attackBadge,
                    main.isEmpty() ? translated("fouryctrl.hints.attack_fist")
                            : isWeaponLike(main) ? translated("fouryctrl.hints.attack")
                            : translated("fouryctrl.hints.attack_mine")));
        }
        String useBadge = triggerBadge(cfg, false, Action.USE);
        if (useBadge != null) {
            rows.add(new Row(useBadge, useText(main)));
        }

        String hotbar = hotbarBadge(cfg);
        if (hotbar != null && showCommon()) {
            rows.add(new Row(hotbar, translated("fouryctrl.hints.hotbar")));
        }
        // 玩家自己绑的「按钮 → 键盘按键」：这些键是用来触发别的模组功能的，
        // 光看手柄按钮完全猜不到，所以必须列出来（徽章=手柄按钮，文字=键盘键名）。
        appendKeyBindRows(rows, cfg);
        if (showCommon()) {
            add(rows, cfg, Action.PAUSE, Button.START, "fouryctrl.hints.pause");
        }
        return rows;
    }

    /** 把「按钮 → 键盘按键」的那几条绑定列进提示（最多 4 条，免得太挤）。 */
    private static void appendKeyBindRows(List<Row> rows, ModConfig cfg) {
        if (cfg == null || cfg.keyBindings == null || cfg.keyBindings.isEmpty()) {
            return;
        }
        int added = 0;
        for (Button b : Button.values()) {
            if (added >= 4) {
                break;
            }
            String name = KeyPickerScreen.displayNameOf(cfg, b);
            if (name == null) {
                continue;
            }
            rows.add(new Row(shortName(b), translated("fouryctrl.hints.key_bind", name)));
            added++;
        }
    }

    /** 容器界面左列：导航。 */
    private static List<Row> containerLeft() {
        List<Row> rows = new ArrayList<>();
        rows.add(new Row(shortName(Button.DPAD_UP) + "/" + shortName(Button.DPAD_DOWN)
                + "/" + shortName(Button.DPAD_LEFT) + "/" + shortName(Button.DPAD_RIGHT),
                translated("fouryctrl.hints.nav_slot")));
        rows.add(new Row(translated("fouryctrl.hints.stick_r_short"),
                translated("fouryctrl.hints.nav_free")));
        return rows;
    }

    /** 容器界面右列：物品操作。 */
    private static List<Row> containerRight() {
        List<Row> rows = new ArrayList<>();
        boolean book = bookOpen();
        // partial 下 A/B 这两个最高频的操作不写，只留进阶操作。
        // 光标停在配方书区域时 A/B 的含义变了（点选配方 / 收起书），换成书内提示。
        if (book) {
            rows.add(new Row(shortName(Button.SOUTH), translated("fouryctrl.hints.recipe_click")));
            rows.add(new Row(shortName(Button.EAST), translated("fouryctrl.hints.recipe_close")));
        } else if (showCommon()) {
            rows.add(new Row(shortName(Button.SOUTH), translated("fouryctrl.hints.slot_left")));
            rows.add(new Row(shortName(Button.EAST), translated("fouryctrl.hints.slot_right")));
        }
        if (!book) {
            rows.add(new Row(shortName(Button.WEST), translated("fouryctrl.hints.slot_quick_move")));
            rows.add(new Row(shortName(Button.NORTH), translated("fouryctrl.hints.slot_throw_one")));
            rows.add(new Row(shortName(Button.LEFT_SHOULDER), translated("fouryctrl.hints.slot_throw_stack")));
            rows.add(new Row(shortName(Button.RIGHT_SHOULDER), translated("fouryctrl.hints.slot_drop_cursor")));
        }
        // 配方书开着时额外提示书内操作（LT/RT 翻页、LB/RB 切分类）
        if (book) {
            rows.add(new Row("LT/RT", translated("fouryctrl.hints.recipe_page")));
            rows.add(new Row("LB/RB", translated("fouryctrl.hints.recipe_tab")));
        }
        if (showCommon()) {
            rows.add(new Row(shortName(Button.START), translated("fouryctrl.hints.close")));
        }
        return rows;
    }

    /** 当前界面的配方书是否打开（在书里时容器按键的含义变了）。 */
    private static boolean bookOpen() {
        Minecraft client = Minecraft.getInstance();
        return client != null && GamepadManager.openRecipeBook(Compat.currentScreen(client)) != null;
    }

    /** 泛用界面左列。 */
    private static List<Row> screenLeft() {
        List<Row> rows = new ArrayList<>();
        rows.add(new Row(translated("fouryctrl.hints.stick_r_short"), translated("fouryctrl.hints.nav_free")));
        if (showCommon()) {
            rows.add(new Row(translated("fouryctrl.hints.dpad_short"), translated("fouryctrl.hints.nudge")));
            rows.add(new Row(shortName(Button.LEFT_SHOULDER) + "/" + shortName(Button.RIGHT_SHOULDER),
                    translated("fouryctrl.hints.scroll")));
        }
        return rows;
    }

    /** 泛用界面右列。 */
    private static List<Row> screenRight() {
        List<Row> rows = new ArrayList<>();
        rows.add(new Row(shortName(Button.SOUTH), translated("fouryctrl.hints.click")));
        rows.add(new Row(shortName(Button.EAST), translated("fouryctrl.hints.back")));
        rows.add(new Row(shortName(Button.START), translated("fouryctrl.hints.close")));
        return rows;
    }

    // ------------------------------------------------------------------ 绘制

    /**
     * 按 {@code cfg.hintAnchor} 决定画哪几列、贴哪一边。
     *
     * <p>锚点格式是「垂直_水平」：{@code bottom_both / bottom_left / bottom_right /
     * top_both / top_left / top_right}。{@code top_*} 时行从顶部往下长（避免底部压住
     * 物品栏/血条）；{@code *_left} / {@code *_right} 时只画对应那一列，把另一侧完全
     * 让给画面（有些手机边框太窄，两列会挤到中间）。</p>
     */
    private static void drawColumns(GuiGraphicsExtractor context, Column left, Column right) {
        ModConfig cfg = FourYCtrl.config();
        String anchor = cfg == null || cfg.hintAnchor == null ? "bottom_both" : cfg.hintAnchor;
        boolean top = anchor.startsWith("top");
        boolean showLeft = !anchor.endsWith("_right");
        boolean showRight = !anchor.endsWith("_left");
        if (showLeft) {
            drawColumn(context, left, false, top);
        }
        if (showRight) {
            drawColumn(context, right, true, top);
        }
    }

    /**
     * 画一列竖排提示。{@code top=false} 时行从底部往上长、{@code top=true} 时从顶部往下长；
     * 超出一屏可用高度的条目直接丢弃（手机横屏高度有限，宁可少显示也不要顶到准星/物品栏上）。
     */
    private static void drawColumn(GuiGraphicsExtractor context, Column col, boolean alignRight, boolean top) {
        if (col == null || col.isEmpty()) {
            return;
        }
        List<Row> rows = col.rows;
        var tr = Minecraft.getInstance().font;
        int sw = context.guiWidth();
        int sh = context.guiHeight();
        final int rowH = 11;
        // 顶部留出 16px（避开顶部的 Boss 条/状态效果），底部留 6px（物品栏/经验条）。
        int startY = 16;
        int bottom = sh - 6;
        int maxRows = Math.max(1, (bottom - startY) / rowH);
        int n = Math.min(rows.size(), maxRows);

        for (int i = 0; i < n; i++) {
            Row r = rows.get(i);
            int y = top ? startY + i * rowH : bottom - (n - i) * rowH;
            int badgeW = col.badgeW[i];
            int textW = col.textW[i];
            int w = badgeW + textW + 9;
            int x = alignRight ? Math.max(2, sw - 4 - w) : 4;
            context.fill(x, y - 1, x + w, y + 9, 0x66000000);
            context.fill(x, y - 1, x + badgeW, y + 9, 0x40FFFFFF);
            // 颜色必须带 alpha（0xFF...）。26.x 的 GuiGraphicsExtractor.text 在
            // ARGB.alpha(color)==0 时直接丢弃，不会画任何东西。
            context.centeredText(tr, Component.literal(r.badge()),
                    x + badgeW / 2, y, 0xFFFFFFFF);
            context.text(tr, r.text(), x + badgeW + 4, y, 0xFFE0E0E0);
        }
    }

    // ------------------------------------------------------------------ 绑定反查

    private static void add(List<Row> rows, ModConfig cfg, Action action, Button fallback, String textKey) {
        String badge = badge(cfg, action, fallback);
        if (badge != null) {
            rows.add(new Row(badge, translated(textKey)));
        }
    }

    /** 动作对应的按钮短名：先反查绑定表，没有则用兜底物理按钮（玩家没绑也照样提示）。 */
    private static String badge(ModConfig cfg, Action action, Button fallback) {
        String found = findByAction(cfg, action.id);
        return found != null ? found : shortName(fallback);
    }

    private static String triggerBadge(ModConfig cfg, boolean left, Action fallbackAction) {
        String configured = left ? cfg.triggerLeftAction : cfg.triggerRightAction;
        if (configured != null && configured.equals(fallbackAction.id)) {
            return left ? "LT" : "RT";
        }
        return findByAction(cfg, fallbackAction.id);
    }

    private static String hotbarBadge(ModConfig cfg) {
        String prev = findByAction(cfg, Action.HOTBAR_PREV.id);
        String next = findByAction(cfg, Action.HOTBAR_NEXT.id);
        if (prev != null && next != null) {
            return prev + "/" + next;
        }
        return prev != null ? prev : next;
    }

    /** 绑定表里第一个映射到 actionId 的按钮的短名。 */
    private static String findByAction(ModConfig cfg, String actionId) {
        if (cfg.bindings == null) {
            return null;
        }
        for (Button b : Button.values()) {
            if (actionId.equals(cfg.bindings.get(b.id))) {
                return shortName(b);
            }
        }
        return null;
    }

    /** 按钮短名：语言键 fouryctrl.button_short.*，缺省退回 SDL id。 */
    private static String shortName(Button b) {
        String key = "fouryctrl.button_short." + b.id;
        Component t = Component.translatable(key);
        // translatable 对缺失键返回键名本身 —— 用这个特性做缺省检测
        return key.equals(t.getString()) ? b.id : t.getString();
    }

    // ------------------------------------------------------------------ 杂项

    private static String itemName(ItemStack stack) {
        String name = stack.getHoverName().getString();
        if (name.length() > 14) {
            name = name.substring(0, 13) + "…";
        }
        return name;
    }

    private static boolean isWeaponLike(ItemStack stack) {
        // 有耐久 + 不是方块 → 当武器/工具看待（剑、斧、镐、弓、三叉戟都符合）
        return stack.isDamageableItem() && !(stack.getItem() instanceof BlockItem);
    }

    /** 使用键的文字：食物=吃，方块=放置，其它=使用。 */
    private static String useText(ItemStack main) {
        if (main.isEmpty()) {
            return translated("fouryctrl.hints.use_empty");
        }
        if (main.has(DataComponents.FOOD)) {
            return translated("fouryctrl.hints.use_eat");
        }
        if (main.getItem() instanceof BlockItem) {
            // 不调 BlockState.isSolidBlock —— 那需要真实世界/坐标，这里只是给个提示文案。
            return translated("fouryctrl.hints.use_place");
        }
        return translated("fouryctrl.hints.use");
    }

    /** 带参数的翻译（%s）。 */
    private static String translated(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }
}
