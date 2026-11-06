package com.fouryctrl.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * {@link MouseHandler} 的光标位置字段没有 setter（只有 {@code xpos()/ypos()} 只读访问器），
 * 模拟 GUI 光标需要直接挪动它 —— {@code Gui.extractRenderState} 里用
 * {@code MouseHandler.getScaledXPos(window)} 把 {@code xpos/ypos} 换算成界面坐标，
 * 所有 hover 检测与点击坐标都以它为准。
 *
 * <p>26.3 的字段名是 {@code xpos/ypos}（1.20.1 的 {@code Mouse.x/y} 已不存在）。</p>
 */
@Mixin(MouseHandler.class)
public interface MouseAccessor {
    @Accessor("xpos")
    void fouryctrl$setX(double x);

    @Accessor("ypos")
    void fouryctrl$setY(double y);
}
