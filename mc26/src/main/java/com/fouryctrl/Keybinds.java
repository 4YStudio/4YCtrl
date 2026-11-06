package com.fouryctrl;

import com.fouryctrl.config.ModConfig;
import com.fouryctrl.input.GamepadManager;
import com.fouryctrl.util.Notify;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import com.mojang.blaze3d.platform.InputConstants;

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

    private static KeyMapping toggle;
    private static KeyMapping debug;

    /**
     * 26.3 的按键分类不再是字符串键，而是 {@code KeyMapping.Category}（{@code record(Identifier id)}）。
     * 它的名字由 {@code id.toLanguageKey("key.category")} 得出，即
     * {@code fouryctrl:general} → {@code key.category.fouryctrl.general}；
     * 所以语言文件里的 {@code "category.fouryctrl"} 要改成这个新键（已同步改）。
     */
    private static KeyMapping.Category category;

    public static void register() {
        // register(Identifier) 会检测重复注册并抛异常，所以只建一次。
        if (category == null) {
            category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("fouryctrl", "general"));
        }
        // 用 (String, int, Category) 这个重载：26.1/26.2 内部把它翻成 Type.KEYSYM，
        // 26.3 翻成 Type.KEYBOARD（26.3 删掉了 KEYSYM），写法以 (Type, int) 传会分叉。
        toggle = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.fouryctrl.toggle", InputConstants.KEY_F8, category));
        debug = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.fouryctrl.debug", InputConstants.KEY_F9, category));
    }

    /** 每个客户端 tick 调用一次。 */
    public static void tick(Minecraft client) {
        ModConfig cfg = FourYCtrl.config();
        if (cfg == null) {
            return;
        }
        GamepadManager gamepad = FourYCtrl.gamepad();
        while (toggle != null && toggle.consumeClick()) {
            if (gamepad != null) {
                gamepad.setEnabled(client, !cfg.enabled);
            }
        }
        while (debug != null && debug.consumeClick()) {
            cfg.debug = !cfg.debug;
            cfg.save();
            FourYCtrl.LOG.info("4YCtrl 调试日志已{}", cfg.debug ? "开启" : "关闭");
            Notify.actionBar(client, cfg.debug ? "fouryctrl.msg.debug_on" : "fouryctrl.msg.debug_off");
        }
    }
}
