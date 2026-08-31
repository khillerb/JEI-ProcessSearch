package dev.processsearch.mixin;

import mezz.jei.gui.ingredients.IngredientFilter;
import mezz.jei.gui.ingredients.IngredientFilterApi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reaches the real {@link IngredientFilter} behind the runtime's {@code IIngredientFilter}.
 *
 * <p>The public API exposes only the filter text, but what this mod needs is
 * {@code rebuildItemFilter()} -- the one call that makes JEI re-read every search prefix's strings
 * after the index has been populated.
 */
@Mixin(IngredientFilterApi.class)
public interface IngredientFilterApiAccessor {
    @Accessor("ingredientFilter")
    IngredientFilter processsearch$getIngredientFilter();
}
