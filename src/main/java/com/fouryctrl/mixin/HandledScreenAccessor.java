package com.fouryctrl.mixin;

import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.slot.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 打开 {@code HandledScreen} 的私有成员，供容器导航（格子选格）使用。
 *
 * <p>{@code x}/{@code y} 是界面背景左上角的 GUI 坐标；{@code Slot.x}/{@code Slot.y}
 * 是相对这个左上角的格子坐标（格子 16x16，左上角再各留 1px 边框），
 * 两者相加才是「把光标放到这个格子正中」的绝对坐标。</p>
 *
 * <p>{@code getSlotAt} 是私有的，但它是唯一可靠的「坐标 → 格子」映射
 * （自己按 18px 网格算会漏掉铁砧/合成台那种不规则排布）。</p>
 */
@Mixin(HandledScreen.class)
public interface HandledScreenAccessor {
    @Accessor("x")
    int fouryctrl$getX();

    @Accessor("y")
    int fouryctrl$getY();

    @Invoker("getSlotAt")
    Slot fouryctrl$getSlotAt(double mouseX, double mouseY);
}
