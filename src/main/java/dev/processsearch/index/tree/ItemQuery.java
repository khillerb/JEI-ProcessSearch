package dev.processsearch.index.tree;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

import dev.processsearch.ProcessSearchConfig;
import dev.processsearch.index.ProcessIndex;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.material.Fluid;

/**
 * The item half of the filter box, as something that can be asked of a graph node.
 *
 * <p>On EMI this was {@code EmiSearch.CompiledQuery}, which tests a stack against a parsed query
 * directly. JEI keeps the equivalent inside its ingredient filter, behind a suffix tree built for
 * the item list rather than for arbitrary questions, so this reimplements the part the graph needs:
 * a name match, JEI's {@code @mod} prefix, and our own {@code ~class} prefix.
 *
 * <p>{@code #tooltip} and {@code $tag} are deliberately not answered. Both would mean resolving
 * data per node that JEI has already indexed for a different purpose, and getting them wrong in the
 * exclusion direction would silently delete branches. A term this cannot answer simply does not
 * match, which for an exclusion means "keep it" -- the safe direction.
 */
public final class ItemQuery {
    private final List<String> terms;

    private ItemQuery(List<String> terms) {
        this.terms = terms;
    }

    /** @return a query, or null when there is nothing to test */
    public static ItemQuery of(List<String> terms) {
        return terms == null || terms.isEmpty() ? null : new ItemQuery(terms);
    }

    /** True when the node matches every term, which is how whitespace-as-AND reads. */
    public boolean test(ItemNode node) {
        return node != null && test(node.key, node.display);
    }

    public boolean test(Object key, Object display) {
        for (String term : terms) {
            if (!matches(key, display, term)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matches(Object key, Object display, String term) {
        if (term.isEmpty()) {
            return false;
        }
        char first = term.charAt(0);
        String rest = term.substring(1);
        if (first == '@') {
            return !rest.isEmpty() && namespaceOf(key).contains(rest);
        }
        if (first == ProcessSearchConfig.itemClassPrefix()) {
            return !rest.isEmpty() && anyContains(ProcessIndex.itemClassesFor(key), rest);
        }
        if (first == '#' || first == '$' || first == '%' || first == '^' || first == '&') {
            // JEI's own prefixes for tooltip, tag, creative tab, colour and id.
            return false;
        }
        return Ingredients.name(display).toLowerCase(Locale.ROOT).contains(term);
    }

    private static boolean anyContains(Collection<String> values, String term) {
        for (String value : values) {
            if (value.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private static String namespaceOf(Object key) {
        ResourceLocation id = null;
        if (key instanceof Item item) {
            id = BuiltInRegistries.ITEM.getKey(item);
        } else if (key instanceof Fluid fluid) {
            id = BuiltInRegistries.FLUID.getKey(fluid);
        }
        return id == null ? "" : id.getNamespace();
    }
}
