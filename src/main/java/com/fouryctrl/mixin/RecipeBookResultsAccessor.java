package com.fouryctrl.mixin;

import net.minecraft.client.gui.screen.recipebook.AnimatedResultButton;
import net.minecraft.client.gui.screen.recipebook.RecipeBookResults;
//? if <1.21.11 {
/*import net.minecraft.client.gui.widget.ToggleButtonWidget;
*///?} else {
import net.minecraft.client.gui.widget.TexturedButtonWidget;
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * 配方书结果区（5x4 的配方格子 + 上下翻页）字段全是 private，
 * 十字键逐格导航需要读它们的坐标与可见性。
 *
 * <p><b>返回类型必须与字段的真实类型一致</b>：{@code @Accessor} 生成的是
 * {@code getfield}，描述符写死在声明里。写成父类 {@code ClickableWidget} 虽然能
 * 编译通过，但 mixin 注解处理器会报「Cannot remap nextPageButton because it does
 * not exist in any of the targets」，运行时就是 {@code NoSuchFieldError}。
 * 字段类型在 1.21.11 从 {@code ToggleButtonWidget} 换成了
 * {@code TexturedButtonWidget}（翻页）与 {@code CyclingButtonWidget<Boolean>}
 * （过滤），所以这里按版本声明；调用方一律当 {@code ClickableWidget} 用（向上转型免费）。</p>
 */
@Mixin(RecipeBookResults.class)
public interface RecipeBookResultsAccessor {
    @Accessor("resultButtons")
    List<AnimatedResultButton> fouryctrl$getResultButtons();

    //? if <1.21.11 {
    /*@Accessor("nextPageButton")
    ToggleButtonWidget fouryctrl$getNextPageButton();

    @Accessor("prevPageButton")
    ToggleButtonWidget fouryctrl$getPrevPageButton();
    *///?} else {
    @Accessor("nextPageButton")
    TexturedButtonWidget fouryctrl$getNextPageButton();

    @Accessor("prevPageButton")
    TexturedButtonWidget fouryctrl$getPrevPageButton();
    //?}

    @Accessor("pageCount")
    int fouryctrl$getPageCount();

    @Accessor("currentPage")
    int fouryctrl$getCurrentPage();
}
