package com.fouryctrl.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 让 {@link GameMenuScreenMixin} 能调用 {@code Screen} 的 protected 成员。
 *
 * <p>为什么不直接让 mixin 类 extends Screen：Mixin 不允许向目标类添加构造器，
 * 而 Screen 只有 {@code protected Screen(Component)} 一个构造器、没有无参版本，
 * {@code PauseScreen} 自身也走 {@code super(title)}，混进去会撞构造器。
 * 所以用接口 + invoker/accessor。</p>
 */
@Mixin(Screen.class)
public interface ScreenAccessor {
    @Invoker("addRenderableWidget")
    <T extends GuiEventListener & Renderable & NarratableEntry> T fouryctrl$addDrawableChild(T drawableElement);

    @Accessor("minecraft")
    Minecraft fouryctrl$getClient();
}
