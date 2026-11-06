package com.fouryctrl.mixin;

import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * {@link Mouse} 的 x/y 字段没有 setter（只有 {@code getX()/getY()}），
 * GUI 光标模拟需要直接挪动它 —— 所有依赖 {@code Mouse.x/y} 的代码
 * （{@code Screen.mouseClicked} 的坐标换算、hover 检测）都以这两个字段为准。
 */
@Mixin(Mouse.class)
public interface MouseAccessor {
    @Accessor("x")
    void fouryctrl$setX(double x);

    @Accessor("y")
    void fouryctrl$setY(double y);
}
