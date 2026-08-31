package dev.processsearch.index;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Scratch buffer for one recipe: the facet tokens it earned, and the ingredient keys playing each
 * role.
 *
 * <p>Keys are {@code Item} or {@code Fluid} instances. Both are registry singletons, so they work
 * directly as map keys, and using them rather than stacks collapses every count/component variant
 * of an item onto one entry -- which is what you want when the question is "can this machine make
 * this thing", and is why the index stays small in a pack this size.
 */
public final class Scan {
    public final Set<String> tokens = new HashSet<>();
    public final List<Object> outputs = new ArrayList<>();
    public final List<Object> inputs = new ArrayList<>();

    public void reset() {
        tokens.clear();
        outputs.clear();
        inputs.clear();
    }

    public boolean hasRoles() {
        return !outputs.isEmpty() || !inputs.isEmpty();
    }

    public void addOutput(Object ingredient) {
        Object k = key(ingredient);
        if (k != null) {
            outputs.add(k);
        }
    }

    public void addInput(Object ingredient) {
        Object k = key(ingredient);
        if (k != null) {
            inputs.add(k);
        }
    }

    public void addOutputs(Object[] ingredients) {
        for (Object o : ingredients) {
            addOutput(o);
        }
    }

    public void addInputs(Object[] ingredients) {
        for (Object o : ingredients) {
            addInput(o);
        }
    }

    /** @return the registry singleton to key on, or null if this is not an item or fluid. */
    public static Object key(Object ingredient) {
        if (ingredient instanceof ItemStack stack) {
            return stack.isEmpty() ? null : stack.getItem();
        }
        if (ingredient instanceof FluidStack fluid) {
            return fluid.isEmpty() ? null : fluid.getFluid();
        }
        // Already a registry singleton. Modern Industrialization hands these back directly from
        // getInputItems() / getInputFluids() rather than wrapping them in stacks.
        if (ingredient instanceof Item || ingredient instanceof Fluid) {
            return ingredient;
        }
        return null;
    }

    public void addInputs(Iterable<?> ingredients) {
        for (Object o : ingredients) {
            addInput(o);
        }
    }
}
