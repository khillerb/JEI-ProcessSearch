package dev.processsearch.index.adapters;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import aztech.modern_industrialization.api.energy.CableTier;
import aztech.modern_industrialization.machines.recipe.MachineRecipe;

import dev.processsearch.index.Facets;
import dev.processsearch.index.RecipeAdapter;
import dev.processsearch.index.Scan;
import mezz.jei.api.recipe.category.IRecipeCategory;

/**
 * Modern Industrialization, the pack's largest namespace at roughly 3745 recipes.
 *
 * <p>MI has the same structural gift Create does: one {@link MachineRecipe} class backs every
 * machine it ships, carrying {@code eu}, {@code duration} and probability-weighted item and fluid
 * lists. So this single adapter covers the whole mod, and Extended Industrialization with it.
 *
 * <p>The facet that matters is voltage. EU/t is a hard automation constraint -- an LV recipe and an
 * HV recipe are different problems -- and JEI shows it on the recipe but cannot filter by it.
 *
 * <p>Only loaded when MI is present; registration is behind a
 * {@code ModList.isLoaded("modern_industrialization")} check.
 */
public final class MachineRecipeAdapter implements RecipeAdapter {
    /**
     * Sorted ascending, resolved once per index build. {@code allTiers()} rather than the five
     * built-in constants, so tiers registered by addons are included instead of silently landing
     * in whichever bucket happened to be last.
     */
    private final List<CableTier> tiers;

    public MachineRecipeAdapter() {
        this.tiers = CableTier.allTiers().stream()
                .sorted(Comparator.comparingLong(CableTier::getEu))
                .toList();
    }

    @Override
    public boolean collect(IRecipeCategory<?> category, Object jeiRecipe, Object recipe, Scan scan) {
        if (!(recipe instanceof MachineRecipe machine)) {
            return false;
        }

        String tier = euTier(machine.eu);
        if (tier != null) {
            scan.tokens.add(tier);
        }
        String speed = Facets.speedBucket(machine.duration);
        if (speed != null) {
            scan.tokens.add(speed);
        }

        boolean random = false;

        for (MachineRecipe.ItemOutput out : machine.itemOutputs) {
            scan.addOutput(out.getStack());
            if (out.probability() < 1.0F) {
                random = true;
            }
        }
        for (MachineRecipe.FluidOutput out : machine.fluidOutputs) {
            scan.tokens.add(Facets.FLUID_OUT);
            scan.addOutput(out.fluid());
            if (out.probability() < 1.0F) {
                random = true;
            }
        }
        for (MachineRecipe.ItemInput in : machine.itemInputs) {
            scan.addInputs(in.getInputItems());
        }
        for (MachineRecipe.FluidInput in : machine.fluidInputs) {
            scan.tokens.add(Facets.FLUID_IN);
            scan.addInputs(in.getInputFluids());
        }

        if (!machine.itemOutputs.isEmpty() || !machine.fluidOutputs.isEmpty()) {
            scan.tokens.add(random ? Facets.CHANCE_RANDOM : Facets.CHANCE_CERTAIN);
        }
        return scan.hasRoles();
    }

    /** @return {@code eu.lv} .. {@code eu.superconductor}, or null if MI registered no tiers. */
    private String euTier(int eu) {
        if (tiers.isEmpty()) {
            return null;
        }
        for (CableTier tier : tiers) {
            if (tier.getEu() >= eu) {
                return "eu." + Facets.sanitize(tier.name.toLowerCase(Locale.ROOT));
            }
        }
        // Above every registered tier. Naming it after the top tier would be a lie, so say so.
        return "eu.above_max";
    }
}
