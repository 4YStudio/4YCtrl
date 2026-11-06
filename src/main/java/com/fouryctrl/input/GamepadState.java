package com.fouryctrl.input;

/**
 * 一帧手柄快照。所有轴都已归一化到 [-1, 1]（扳机是 [0, 1]）。
 *
 * <p>刻意做成不可变的普通对象：Mixin 每帧都读它，
 * 避免出现「读到一半被 tick 线程改掉」的中间态。</p>
 */
public final class GamepadState {
    public static final GamepadState EMPTY = new GamepadState();

    public boolean connected;
    public String name = "";

    public float leftX;
    public float leftY;
    /** 右摇杆 X（视角）。 */
    public float rightX;
    /** 右摇杆 Y（视角，已按 SDL 原样：上为负）。 */
    public float rightY;
    public float leftTrigger;
    public float rightTrigger;

    /** 按 {@link Button#index} 索引。 */
    public final boolean[] buttons = new boolean[32];

    /** 清空成「未连接」状态。 */
    public void reset() {
        connected = false;
        name = "";
        leftX = 0f;
        leftY = 0f;
        rightX = 0f;
        rightY = 0f;
        leftTrigger = 0f;
        rightTrigger = 0f;
        java.util.Arrays.fill(buttons, false);
    }

    /** 把另一个快照拷进来（用于上一帧对比）。 */
    public void copyFrom(GamepadState other) {
        connected = other.connected;
        name = other.name;
        leftX = other.leftX;
        leftY = other.leftY;
        rightX = other.rightX;
        rightY = other.rightY;
        leftTrigger = other.leftTrigger;
        rightTrigger = other.rightTrigger;
        System.arraycopy(other.buttons, 0, buttons, 0, buttons.length);
    }

    public boolean isDown(Button b) {
        return b != null && b.index < buttons.length && buttons[b.index];
    }

    public boolean isDown(int index) {
        return index >= 0 && index < buttons.length && buttons[index];
    }
}
