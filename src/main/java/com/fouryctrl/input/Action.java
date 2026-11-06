package com.fouryctrl.input;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** 手柄按钮能触发的动作。配置里用 {@link #id} 表示。 */
public enum Action {
    NONE("none", false),
    JUMP("jump", false),
    SNEAK("sneak", false),
    SPRINT("sprint", false),
    ATTACK("attack", false),
    USE("use", false),
    INVENTORY("inventory", true),
    DROP("drop", true),
    SWAP_OFFHAND("swap_offhand", true),
    HOTBAR_PREV("hotbar_prev", true),
    HOTBAR_NEXT("hotbar_next", true),
    PAUSE("pause", true),
    /**
     * 以下是「辅助动作」，主要给十字键用：游戏内十字键默认不绑任何东西，
     * 玩家在这里选一个自己想要的（比如十字键上=打开聊天）。
     */
    CHAT("chat", true),
    PERSPECTIVE("perspective", true),
    /** 按住显示玩家列表（和键盘 Tab 一样是「按住才显示」）。 */
    PLAYER_LIST("player_list", false),
    HIDE_HUD("hide_hud", true);

    /** 配置里写的名字。 */
    public final String id;
    /** 是否「按一下触发一次」而不是「按住持续」。 */
    public final boolean edgeTriggered;

    Action(String id, boolean edgeTriggered) {
        this.id = id;
        this.edgeTriggered = edgeTriggered;
    }

    private static final Map<String, Action> BY_ID = new HashMap<>();

    static {
        for (Action a : values()) {
            BY_ID.put(a.id, a);
        }
        // 容忍大小写与空格
        BY_ID.put("swapoffhand", SWAP_OFFHAND);
        BY_ID.put("hotbarprev", HOTBAR_PREV);
        BY_ID.put("hotbarnext", HOTBAR_NEXT);
        BY_ID.put("playerlist", PLAYER_LIST);
        BY_ID.put("hidehud", HIDE_HUD);
    }

    public static Action byId(String s) {
        if (s == null) {
            return NONE;
        }
        String k = s.trim().toLowerCase(Locale.ROOT);
        Action a = BY_ID.get(k);
        return a == null ? NONE : a;
    }
}
