package dev.processsearch.mixin;

import java.util.List;

import dev.processsearch.recipe.RecipeFilter;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.gui.recipes.lookups.FocusedRecipes;
import mezz.jei.gui.recipes.lookups.IFocusedRecipes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Filters the recipe list behind a category page.
 *
 * <p>This is the list the recipe GUI pages through, so narrowing it here is what turns two thousand
 * mixing pages into the dozen that are actually heated. {@code RecipeFilter} returns the original
 * list by identity when nothing applies, so the common case costs one reference comparison.
 *
 * <p>Only {@code FocusedRecipes} is targeted, not {@code StaticFocusedRecipes}: the latter backs
 * {@code IRecipesGui.showRecipes}, where a mod has asked for a specific list of recipes by hand and
 * quietly dropping some of them would be wrong.
 */
@Mixin(FocusedRecipes.class)
public class FocusedRecipesMixin {
    @Inject(method = "getRecipes", at = @At("RETURN"), cancellable = true)
    private void processsearch$filterRecipes(CallbackInfoReturnable<List<?>> cir) {
        List<?> recipes = cir.getReturnValue();
        if (recipes == null || recipes.isEmpty()) {
            return;
        }
        IRecipeCategory<?> category = ((IFocusedRecipes<?>) (Object) this).getRecipeCategory();
        if (category == null) {
            return;
        }
        List<?> filtered = RecipeFilter.apply(category, recipes);
        if (filtered != recipes) {
            cir.setReturnValue(filtered);
        }
    }
}
