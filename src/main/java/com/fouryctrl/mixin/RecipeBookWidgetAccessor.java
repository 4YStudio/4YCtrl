package com.fouryctrl.mixin;

import net.minecraft.client.gui.screen.recipebook.RecipeBookResults;
import net.minecraft.client.gui.screen.recipebook.RecipeBookWidget;
import net.minecraft.client.gui.screen.recipebook.RecipeGroupButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
//? if <1.21.11 {
/*import net.minecraft.client.gui.widget.ToggleButtonWidget;
*///?} else {
import net.minecraft.client.gui.widget.CyclingButtonWidget;
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * {@link RecipeBookWidget} 的字段全是 private 且没有 getter，而我们要：
 * 算书的矩形（判断光标是否落在配方书区域）、拿到分类页签/过滤按钮/搜索框/结果区
 * 做十字键逐格导航。
 */
@Mixin(RecipeBookWidget.class)
public interface RecipeBookWidgetAccessor {
    @Accessor("leftOffset")
    int fouryctrl$getLeftOffset();

    @Accessor("parentWidth")
    int fouryctrl$getParentWidth();

    @Accessor("parentHeight")
    int fouryctrl$getParentHeight();

    @Accessor("tabButtons")
    List<RecipeGroupButtonWidget> fouryctrl$getTabButtons();

    @Accessor("currentTab")
    RecipeGroupButtonWidget fouryctrl$getCurrentTab();

    // 同 {@link RecipeBookResultsAccessor}：@Accessor 的返回类型必须等于字段真实类型，
    // 否则 remap 阶段就会报「Cannot remap toggleCraftableButton」。
    // 1.21.11 把它从 ToggleButtonWidget 换成了 CyclingButtonWidget<Boolean>。
    //? if <1.21.11 {
    /*@Accessor("toggleCraftableButton")
    ToggleButtonWidget fouryctrl$getToggleCraftableButton();
    *///?} else {
    @Accessor("toggleCraftableButton")
    CyclingButtonWidget<Boolean> fouryctrl$getToggleCraftableButton();
    //?}

    @Accessor("searchField")
    TextFieldWidget fouryctrl$getSearchField();

    @Accessor("recipesArea")
    RecipeBookResults fouryctrl$getRecipesArea();
}
