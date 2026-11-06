package com.fouryctrl.mixin;

import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.client.gui.screens.recipebook.RecipeBookPage;
import net.minecraft.client.gui.screens.recipebook.RecipeButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * 配方书结果区（5x4 的配方格子 + 上下翻页）字段全是 private，
 * 十字键逐格导航需要读它们的坐标与可见性。
 *
 * <p>26.x 改名对照：{@code resultButtons}→{@code buttons}、
 * {@code nextPageButton}→{@code forwardButton}、{@code prevPageButton}→{@code backButton}、
 * {@code pageCount}→{@code totalPages}（两者类型也从 ToggleButtonWidget 变成 ImageButton）。</p>
 */
@Mixin(RecipeBookPage.class)
public interface RecipeBookResultsAccessor {
    @Accessor("buttons")
    List<RecipeButton> fouryctrl$getResultButtons();

    @Accessor("forwardButton")
    ImageButton fouryctrl$getNextPageButton();

    @Accessor("backButton")
    ImageButton fouryctrl$getPrevPageButton();

    @Accessor("totalPages")
    int fouryctrl$getPageCount();

    @Accessor("currentPage")
    int fouryctrl$getCurrentPage();
}
