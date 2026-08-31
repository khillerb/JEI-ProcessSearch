package dev.processsearch.index.adapters;

import java.util.List;

import dev.processsearch.index.RecipeAdapter;
import dev.processsearch.index.Scan;
import mezz.jei.api.ingredients.IIngredientSupplier;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;

/**
 * Last resort, and the reason this works for mods nobody wrote an adapter for.
 *
 * <p>Asks JEI itself what a recipe contains, which means it sees exactly what the category draws --
 * multi-output machines, custom non-{@code Recipe} categories, generated recipes, all of it. The
 * cost is that JEI has to run the category's layout builder to answer, so this runs only for
 * recipes the two cheap adapters declined.
 */
public final class GenericJeiAdapter implements RecipeAdapter {
    private final IRecipeManager recipeManager;

    public GenericJeiAdapter(IRecipeManager recipeManager) {
        this.recipeManager = recipeManager;
    }

    @Override
    public boolean collect(IRecipeCategory<?> category, Object jeiRecipe, Object recipe, Scan scan) {
        IIngredientSupplier supplier = ingredientsOf(category, jeiRecipe);
        if (supplier == null) {
            return false;
        }
        for (ITypedIngredient<?> out : supplier.getIngredients(RecipeIngredientRole.OUTPUT)) {
            scan.addOutput(out.getIngredient());
        }
        List<ITypedIngredient<?>> inputs = supplier.getIngredients(RecipeIngredientRole.INPUT);
        for (ITypedIngredient<?> in : inputs) {
            scan.addInput(in.getIngredient());
        }
        // CATALYST is deliberately not indexed as an input: a mixer is not something you feed a
        // mixer. Catalysts become category-level tokens instead, so >mechanical_mixer finds what
        // the machine makes rather than the machine itself.
        return scan.hasRoles();
    }

    @SuppressWarnings("unchecked")
    private IIngredientSupplier ingredientsOf(IRecipeCategory<?> category, Object jeiRecipe) {
        try {
            return recipeManager.getRecipeIngredients((IRecipeCategory<Object>) category, jeiRecipe);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }
}
