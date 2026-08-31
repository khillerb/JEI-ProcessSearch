package dev.processsearch.index.adapters;

import dev.processsearch.index.Facets;
import dev.processsearch.index.RecipeAdapter;
import dev.processsearch.index.Scan;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

/**
 * Fast path for the vanilla-shaped categories, which are also the biggest ones: in a pack this size
 * {@code minecraft:crafting} alone holds more recipes than everything else put together, and every
 * modded crafting and smelting recipe lands there too.
 *
 * <p>Scoped deliberately to the {@code minecraft} namespace. Every vanilla category backed by a
 * {@link Recipe} is single-output by contract, so reading {@code getResultItem} tells the whole
 * story and there is no reason to pay for a layout build. That is emphatically not true of modded
 * categories -- Mekanism and Thermal machines routinely emit two or three stacks and a fluid -- so
 * those fall through to {@link GenericJeiAdapter}, which asks JEI what it actually draws.
 *
 * <p>Vanilla's non-{@code Recipe} categories (anvil, brewing, fuel, compostable) fail the
 * {@code instanceof} and fall through as well, which is correct: JEI models those differently.
 */
public final class VanillaRecipeAdapter implements RecipeAdapter {
    private final HolderLookup.Provider registries;

    public VanillaRecipeAdapter(HolderLookup.Provider registries) {
        this.registries = registries;
    }

    @Override
    public boolean collect(IRecipeCategory<?> category, Object jeiRecipe, Object recipe, Scan scan) {
        if (!(recipe instanceof Recipe<?> vanilla) || vanilla.isSpecial()) {
            return false;
        }
        if (!"minecraft".equals(category.getRecipeType().getUid().getNamespace())) {
            return false;
        }

        // These run against modded Recipe implementations far more often than vanilla ones, and one
        // that throws would otherwise take down the whole index build.
        ItemStack result;
        try {
            result = vanilla.getResultItem(registries);
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
        if (result == null || result.isEmpty()) {
            return false;
        }

        try {
            for (Ingredient in : vanilla.getIngredients()) {
                scan.addInputs(in.getItems());
            }
        } catch (RuntimeException | LinkageError e) {
            // Keep the output already in hand; this recipe's inputs simply go unindexed.
        }

        scan.addOutput(result);
        if (Facets.isPacking(vanilla, result)) {
            scan.tokens.add(Facets.PACKING);
        }
        return true;
    }
}
