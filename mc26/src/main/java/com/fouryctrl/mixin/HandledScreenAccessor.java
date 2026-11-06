package com.fouryctrl.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 打开 {@code AbstractContainerScreen} 的私有成员，供容器导航（格子选格）使用。
 *
 * <p>26.x 里 {@code x}/{@code y} 已改名为 {@code leftPos}/{@code topPos}，
 * {@code getSlotAt} 改名为 {@code getHoveredSlot}（签名不变）。</p>
 *
 * <p>{@code leftPos}/{@code topPos} 是界面背景左上角的 GUI 坐标；{@code Slot.x}/{@code Slot.y}
 * 是相对这个左上角的格子坐标（格子 16x16，左上角再各留 1px 边框），
 * 两者相加才是「把光标放到这个格子正中」的绝对坐标。</p>
 *
 * <p>{@code getSlotAt} 是私有的，但它是唯一可靠的「坐标 → 格子」映射
 * （自己按 18px 网格算会漏掉铁砧/合成台那种不规则排布）。</p>
 */
@Mixin(AbstractContainerScreen.class)
public interface HandledScreenAccessor {
    @Accessor("leftPos")
    int fouryctrl$getX();

    @Accessor("topPos")
    int fouryctrl$getY();

    @Invoker("getHoveredSlot")
    Slot fouryctrl$getSlotAt(double mouseX, double mouseY);
}
