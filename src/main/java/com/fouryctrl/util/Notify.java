package com.fouryctrl.util;

import com.fouryctrl.FourYCtrl;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

/**
 * 给玩家看的提示。
 *
 * <p>安卓上翻 logcat 非常麻烦，所以状态变化尽量用游戏里的动作栏（物品栏上方那行字）说一声，
 * 而不是只写日志。</p>
 *
 * <p>为什么不用聊天框：动作栏不刷屏、不需要玩家手动打开聊天记录，看一秒就消失，
 * 对「手柄连上了」「调试日志开了」这种瞬时状态更合适。</p>
 */
public final class Notify {
    private Notify() {
    }

    /** 在动作栏显示一条翻译键（带参数）的消息；不在游戏内时退回日志。 */
    public static void actionBar(MinecraftClient client, String translationKey, Object... args) {
        Text text = Text.translatable(translationKey, args);
        try {
            if (client != null && client.player != null) {
                client.player.sendMessage(text, true);
                return;
            }
        } catch (Throwable t) {
            FourYCtrl.LOG.debug("动作栏消息发送失败：{}", t.toString());
        }
        FourYCtrl.LOG.info(text.getString());
    }
}
