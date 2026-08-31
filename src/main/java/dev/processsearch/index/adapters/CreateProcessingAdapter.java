package dev.processsearch.index.adapters;

import java.util.List;

import com.simibubi.create.content.processing.recipe.HeatCondition;
import com.simibubi.create.content.processing.recipe.ProcessingOutput;
import com.simibubi.create.content.processing.recipe.ProcessingRecipe;

import dev.processsearch.index.Facets;
import dev.processsearch.index.RecipeAdapter;
import dev.processsearch.index.Scan;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;

/**
 * The reason Create compat is cheap.
 *
 * <p>Nearly every Create machine -- mixing, crushing, milling, pressing, deploying, item
 * application, spout filling, item draining, sawing, all four fan processes, packing, compacting --
 * and every Create addon recipe built on the same base share one superclass,
 * {@link ProcessingRecipe}. So a single {@code instanceof} covers the whole ecosystem, including
 * Create: Dragons Plus' ColoringRecipe, Create Encased, Createaddition and the rest of the roughly
 * eighteen addons in this pack.
 *
 * <p>It also reads what JEI cannot show you as a category: heat requirement is a <em>field</em> on
 * the recipe, not a separate category, so "made by a heated mixer" is invisible to category
 * browsing and needs exactly this.
 *
 * <p>This class must only be loaded when Create is present -- it is registered behind a
 * {@code ModList.isLoaded("create")} check so the JVM never tries to link these types otherwise.
 */
public final class CreateProcessingAdapter implements RecipeAdapter {
    @Override
    public boolean collect(IRecipeCategory<?> category, Object jeiRecipe, Object recipe, Scan scan) {
        if (!(recipe instanceof ProcessingRecipe<?, ?> processing)) {
            return false;
        }

        scan.tokens.add(heatToken(processing.getRequiredHeat()));

        String speed = Facets.speedBucket(processing.getProcessingDuration());
        if (speed != null) {
            scan.tokens.add(speed);
        }

        collectResults(processing, scan);

        for (Ingredient in : processing.getIngredients()) {
            scan.addInputs(in.getItems());
        }
        for (SizedFluidIngredient in : processing.getFluidIngredients()) {
            scan.tokens.add(Facets.FLUID_IN);
            scan.addInputs(in.getFluids());
        }
        for (FluidStack out : processing.getFluidResults()) {
            scan.tokens.add(Facets.FLUID_OUT);
            scan.addOutput(out);
        }

        return true;
    }

    /**
     * Create's outputs carry a roll chance, which is the difference between a crushing recipe you
     * can build a ratio around and one you cannot. Anything below 1.0 anywhere makes the whole
     * recipe random.
     */
    private static void collectResults(ProcessingRecipe<?, ?> processing, Scan scan) {
        List<ProcessingOutput> results = processing.getRollableResults();
        boolean random = false;
        for (ProcessingOutput result : results) {
            scan.addOutput(result.getStack());
            if (result.getChance() < 1.0F) {
                random = true;
            }
        }
        if (!results.isEmpty()) {
            scan.tokens.add(random ? Facets.CHANCE_RANDOM : Facets.CHANCE_CERTAIN);
        }
    }

    private static String heatToken(HeatCondition heat) {
        if (heat == null) {
            return Facets.HEAT_NONE;
        }
        return switch (heat) {
            case HEATED -> Facets.HEAT_HEATED;
            case SUPERHEATED -> Facets.HEAT_SUPERHEATED;
            default -> Facets.HEAT_NONE;
        };
    }
}
