package com.fouryctrl.mixin;

import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeBookPage;
import net.minecraft.client.gui.screens.recipebook.RecipeBookTabButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

/**
 * {@link RecipeBookComponent} 的字段全是 private 且没有 getter，而我们要：
 * 算书的矩形（判断光标是否落在配方书区域）、拿到分类页签/过滤按钮/搜索框/结果区
 * 做十字键逐格导航。
 *
 * <p>26.x 改名对照：{@code leftOffset}→{@code xOffset}、{@code parentWidth}→{@code width}、
 * {@code parentHeight}→{@code height}、{@code currentTab}→{@code selectedTab}、
 * {@code toggleCraftableButton}→{@code filterButton}(CycleButton&lt;Boolean&gt;)、
 * {@code searchField}→{@code searchBox}、{@code recipesArea}→{@code recipeBookPage}、
 * {@code isOpen()}→{@code isVisible()}、{@code toggleOpen()}→{@code toggleVisibility()}。</p>
 */
@Mixin(RecipeBookComponent.class)
public interface RecipeBookWidgetAccessor {
    @Accessor("xOffset")
    int fouryctrl$getLeftOffset();

    @Accessor("width")
    int fouryctrl$getParentWidth();

    @Accessor("height")
    int fouryctrl$getParentHeight();

    @Accessor("tabButtons")
    List<RecipeBookTabButton> fouryctrl$getTabButtons();

    @Accessor("selectedTab")
    RecipeBookTabButton fouryctrl$getCurrentTab();

    @Accessor("filterButton")
    CycleButton<Boolean> fouryctrl$getToggleCraftableButton();

    @Accessor("searchBox")
    EditBox fouryctrl$getSearchField();

    @Accessor("recipeBookPage")
    RecipeBookPage fouryctrl$getRecipesArea();

    /** 26.x 里 {@code getXOrigin()/getYOrigin()} 是 private，命中计算需要它们。 */
    @Invoker("getXOrigin")
    int fouryctrl$getXOrigin();

    @Invoker("getYOrigin")
    int fouryctrl$getYOrigin();
}
