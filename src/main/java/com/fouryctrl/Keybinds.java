package com.fouryctrl;

import com.fouryctrl.config.ModConfig;
import com.fouryctrl.input.GamepadManager;
import com.fouryctrl.util.Notify;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/**
 * 键盘快捷键（给 PC 上调试用）。
 *
 * <p>真机上改 JSON 很痛苦，所以给了两个键：</p>
 * <ul>
 *   <li>F8：总开关，临时停用/启用 4YCtrl；</li>
 *   <li>F9：切换调试日志，开启后每 20 tick 打一行摇杆/按键快照，方便用户报 bug。</li>
 * </ul>
 *
 * <p>注意：安卓上没有物理键盘，这两个键基本按不到。所以真机上请用「暂停菜单右上角的
 * 4YCtrl 按钮」或「长按 L3+R3 一秒」来开关——见 {@link GamepadManager#updateToggleCombo}。</p>
 */
public final class Keybinds {
    private Keybinds() {
    }

    private static KeyBinding toggle;
    private static KeyBinding debug;

    public static void register() {
        // 分类参数第 4 位：1.20.1–1.21.8 是 String（值就是翻译键去掉前缀，
        // 所以旧代码写 "category.fouryctrl"），1.21.11 改成 KeyBinding.Category
        // 记录，它自己拼 "key.category.<id>"。两种写法最终解析到同一个翻译键
        // key.category.fouryctrl，所以这里按版本分支但不影响语言文件。
        //? if <1.21.11 {
        /*String category = "category.fouryctrl";
        toggle = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.fouryctrl.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F8, category));
        debug = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.fouryctrl.debug", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F9, category));
        *///?} else {
        KeyBinding.Category category =
                KeyBinding.Category.create(net.minecraft.util.Identifier.of("fouryctrl", "main"));
        toggle = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.fouryctrl.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F8, category));
        debug = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.fouryctrl.debug", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F9, category));
        //?}
    }

    /** 每个客户端 tick 调用一次。 */
    public static void tick(MinecraftClient client) {
        ModConfig cfg = FourYCtrl.config();
        if (cfg == null) {
            return;
        }
        GamepadManager gamepad = FourYCtrl.gamepad();
        while (toggle != null && toggle.wasPressed()) {
            if (gamepad != null) {
                gamepad.setEnabled(client, !cfg.enabled);
            }
        }
        while (debug != null && debug.wasPressed()) {
            cfg.debug = !cfg.debug;
            cfg.save();
            FourYCtrl.LOG.info("4YCtrl 调试日志已{}", cfg.debug ? "开启" : "关闭");
            Notify.actionBar(client, cfg.debug ? "fouryctrl.msg.debug_on" : "fouryctrl.msg.debug_off");
        }
    }
}
