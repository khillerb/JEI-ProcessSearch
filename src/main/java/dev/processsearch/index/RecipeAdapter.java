package dev.processsearch.index;

import mezz.jei.api.recipe.category.IRecipeCategory;

/**
 * Pulls the inputs, outputs and facets out of one recipe.
 *
 * <p>Adapters are tried in order and the first to claim a recipe wins, so they are ordered
 * cheapest-and-most-specific first: reading fields off a Create {@code ProcessingRecipe} costs
 * nothing, while the generic fallback has JEI build the recipe's layout to find out what is in it.
 */
public interface RecipeAdapter {
    /**
     * @param category   the JEI category the recipe belongs to
     * @param jeiRecipe  the object JEI holds, usually a {@code RecipeHolder}
     * @param recipe     {@code jeiRecipe} unwrapped -- the {@code Recipe} itself where there is one
     * @param scan       buffer to fill
     * @return true if this adapter handled the recipe; no later adapter will be tried
     */
    boolean collect(IRecipeCategory<?> category, Object jeiRecipe, Object recipe, Scan scan);
}
