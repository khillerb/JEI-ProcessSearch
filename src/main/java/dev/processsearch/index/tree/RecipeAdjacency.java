package dev.processsearch.index.tree;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import mezz.jei.api.ingredients.IIngredientSupplier;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;

import dev.processsearch.index.Scan;

/**
 * Which recipes consume a key, and which produce it.
 *
 * <p>EMI ships this: its recipe manager keeps {@code byInput} and {@code byOutput} maps with tags
 * already expanded, so the port there was one lookup per step. JEI has no equivalent -- its lookup
 * API is per recipe type, so "everything that consumes cobblestone" would mean walking every
 * category on every step. So the maps are built here instead, during the index pass that is already
 * visiting every recipe in the pack.
 *
 * <p>Only the {@code (category, recipe)} pair is stored, not the ingredients. Resolving what a
 * recipe contains means having JEI run the category's layout builder, and the walk only ever needs
 * that for the handful of recipes it actually expands -- so {@link #farSideKeys} does it on demand.
 * Storing the ingredient lists up front would multiply the memory cost by an order of magnitude for
 * data almost none of which is ever looked at.
 *
 * <p>Built only when {@code enableProcessTree} is on. It is the one part of this mod with a memory
 * cost worth mentioning, and turning the tree off should not pay it.
 */
public final class RecipeAdjacency {
    /** One recipe, with the category it was found in. */
    public record RecipeRef(IRecipeCategory<?> category, Object recipe) {}

    private final Map<Object, List<RecipeRef>> byInput = new HashMap<>();
    private final Map<Object, List<RecipeRef>> byOutput = new HashMap<>();
    private final Map<IRecipeCategory<?>, Object> icons = new HashMap<>();
    private IRecipeManager recipeManager;

    public void setRecipeManager(IRecipeManager manager) {
        this.recipeManager = manager;
    }

    public void clear() {
        byInput.clear();
        byOutput.clear();
        icons.clear();
    }

    public boolean isEmpty() {
        return byInput.isEmpty() && byOutput.isEmpty();
    }

    public int entryCount() {
        return byInput.size() + byOutput.size();
    }

    /** Records one scanned recipe. Called from the index build, once per recipe. */
    public void record(IRecipeCategory<?> category, Object jeiRecipe, Scan scan) {
        RecipeRef ref = new RecipeRef(category, jeiRecipe);
        for (Object key : scan.outputs) {
            byOutput.computeIfAbsent(key, k -> new ArrayList<>(2)).add(ref);
        }
        for (Object key : scan.inputs) {
            byInput.computeIfAbsent(key, k -> new ArrayList<>(2)).add(ref);
        }
    }

    /** The workstation to draw for a category, so a node reads "Crushing Wheels". */
    public void setIcon(IRecipeCategory<?> category, Object icon) {
        if (icon != null) {
            icons.put(category, icon);
        }
    }

    public Object iconFor(IRecipeCategory<?> category) {
        return icons.get(category);
    }

    /**
     * The recipes to follow from a node.
     *
     * @param direction {@code CONSUMERS} asks what takes this key as an input, {@code PRODUCERS}
     *                  what hands it back as an output
     */
    public List<RecipeRef> from(Object key, Direction direction) {
        Map<Object, List<RecipeRef>> map = direction == Direction.CONSUMERS ? byInput : byOutput;
        List<RecipeRef> found = map.get(key);
        return found == null ? List.of() : found;
    }

    /**
     * The keys on the far side of a step: a recipe's outputs when following consumers, its inputs
     * when following producers.
     *
     * <p>This is the call that costs something -- JEI builds the recipe's layout to answer it -- so
     * the walk stops asking as soon as it has filled its width cap.
     */
    public List<Object> farSideKeys(RecipeRef ref, Direction direction) {
        return direction == Direction.CONSUMERS ? outputKeys(ref) : inputKeys(ref);
    }

    /** What the recipe produces, regardless of which way the walk is running. */
    public List<Object> outputKeys(RecipeRef ref) {
        return keysOf(ref, RecipeIngredientRole.OUTPUT);
    }

    /** What the recipe consumes. Catalysts are a separate role and are not included. */
    public List<Object> inputKeys(RecipeRef ref) {
        return keysOf(ref, RecipeIngredientRole.INPUT);
    }

    /**
     * The ingredients themselves rather than their keys, for the drill-down list that has to draw
     * them. Same layout-builder call, so it is only asked for the one category being looked at.
     */
    public List<Object> displayIngredients(RecipeRef ref, boolean outputs) {
        IIngredientSupplier supplier = ingredientsOf(ref);
        if (supplier == null) {
            return List.of();
        }
        RecipeIngredientRole role = outputs ? RecipeIngredientRole.OUTPUT : RecipeIngredientRole.INPUT;
        List<Object> found = new ArrayList<>(4);
        try {
            for (ITypedIngredient<?> ingredient : supplier.getIngredients(role)) {
                found.add(ingredient.getIngredient());
            }
        } catch (RuntimeException | LinkageError e) {
            return List.of();
        }
        return found;
    }

    private List<Object> keysOf(RecipeRef ref, RecipeIngredientRole role) {
        IIngredientSupplier supplier = ingredientsOf(ref);
        if (supplier == null) {
            return List.of();
        }
        List<Object> keys = new ArrayList<>(4);
        for (ITypedIngredient<?> ingredient : supplier.getIngredients(role)) {
            Object key = Scan.key(ingredient.getIngredient());
            if (key != null && !keys.contains(key)) {
                keys.add(key);
            }
        }
        return keys;
    }

    @SuppressWarnings("unchecked")
    private IIngredientSupplier ingredientsOf(RecipeRef ref) {
        if (recipeManager == null) {
            return null;
        }
        try {
            return recipeManager.getRecipeIngredients(
                    (IRecipeCategory<Object>) ref.category(), ref.recipe());
        } catch (RuntimeException | LinkageError e) {
            // A category whose layout builder throws contributes nothing rather than killing the walk.
            return null;
        }
    }
}
