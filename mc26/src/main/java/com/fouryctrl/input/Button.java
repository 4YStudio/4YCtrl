package com.fouryctrl.input;

/** SDL 的手柄按钮索引（= SDL_GamepadButton 枚举值，Android 驱动就是按这个顺序上报的）。 */
public enum Button {
    SOUTH(0, "SOUTH"),
    EAST(1, "EAST"),
    WEST(2, "WEST"),
    NORTH(3, "NORTH"),
    BACK(4, "BACK"),
    GUIDE(5, "GUIDE"),
    START(6, "START"),
    LEFT_STICK(7, "LEFT_STICK"),
    RIGHT_STICK(8, "RIGHT_STICK"),
    LEFT_SHOULDER(9, "LEFT_SHOULDER"),
    RIGHT_SHOULDER(10, "RIGHT_SHOULDER"),
    DPAD_UP(11, "DPAD_UP"),
    DPAD_DOWN(12, "DPAD_DOWN"),
    DPAD_LEFT(13, "DPAD_LEFT"),
    DPAD_RIGHT(14, "DPAD_RIGHT");

    public final int index;
    public final String id;

    /** 缓存 {@code values()}：它每次调用都会克隆数组，而抓帧热路径每 tick 要遍历好几遍。 */
    public static final Button[] ALL = values();

    private static final java.util.Map<String, Button> BY_ID = new java.util.HashMap<>();

    static {
        for (Button b : ALL) {
            BY_ID.put(b.id, b);
        }
    }

    Button(int index, String id) {
        this.index = index;
        this.id = id;
    }

    public static Button byId(String s) {
        if (s == null) {
            return null;
        }
        return BY_ID.get(s.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
