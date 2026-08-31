package dev.processsearch.index.tree;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.processsearch.ProcessSearchConfig;
import dev.processsearch.index.ProcessIndex;
import dev.processsearch.index.tree.RecipeAdjacency.RecipeRef;
import dev.processsearch.recipe.FacetQueryClauses;
import dev.processsearch.recipe.FacetQueryClauses.Clause;
import mezz.jei.api.recipe.category.IRecipeCategory;

/**
 * Walks the recipe graph outward from one item.
 *
 * <p>The adjacency comes from {@link RecipeAdjacency}, built during the index pass, so each step is
 * one map lookup. Resolving what a recipe actually contains is the expensive half and is left until
 * a node is really expanded.
 *
 * <p>Everything here is about staying finite. Breadth-first under a node budget, a visited set that
 * makes repeat items link rather than branch, and width caps that report what they dropped instead
 * of hiding it.
 */
public final class ProcessGraphBuilder {
    private final RecipeAdjacency adjacency;
    private final Direction direction;
    /** Facet clauses from the filter box; empty means "follow every recipe". */
    private final List<Clause> clauses;
    /** Negated item terms: anything matching these never becomes a node at all. */
    private final ItemQuery exclusionQuery;
    /** Positive item terms: matches are tinted and drawn deeper, never removed. */
    private final ItemQuery retentionQuery;

    /** Opt-in: a machine is followed only if it is in here. Empty means the graph is just the root. */
    private final Set<String> includedCategories = Set.copyOf(ProcessSearchConfig.treeIncludedCategories());
    private final boolean hideIdentity = ProcessSearchConfig.treeHideIdentityRecipes();
    /** Identity detection resolves a recipe's layout, so a node with hundreds of recipes memoises. */
    private final Map<Object, Boolean> identityCache = new IdentityHashMap<>();

    private static final int MAX_IDENTITY_OUTPUTS = 2;
    private static final int MAX_IDENTITY_INPUT_KEYS = 64;

    private final int maxProcesses = ProcessSearchConfig.treeMaxProcessesPerItem();
    private final int maxItems = ProcessSearchConfig.treeMaxItemsPerProcess();
    private final int maxNodes = ProcessSearchConfig.treeMaxNodes();

    private ProcessGraphBuilder(RecipeAdjacency adjacency, Direction direction, List<Clause> clauses,
                                ItemQuery exclusionQuery, ItemQuery retentionQuery) {
        this.adjacency = adjacency;
        this.direction = direction;
        this.clauses = clauses;
        this.exclusionQuery = exclusionQuery;
        this.retentionQuery = retentionQuery;
    }

    /** @return the graph, or null when the adjacency is not built or the key is not indexable */
    public static ProcessGraph build(Object rootKey, Direction direction) {
        RecipeAdjacency adjacency = ProcessIndex.adjacency();
        if (rootKey == null || adjacency == null || adjacency.isEmpty()) {
            return null;
        }
        String query = ProcessIndex.currentFilterText();
        List<Clause> clauses = FacetQueryClauses.parse(query);

        // A ~ token, or any recipe facet, can only be answered by the process index. Applying those
        // without it is worse than not applying them: a negated class filter with no index quietly
        // admits everything, because "did not match" and "could not tell" look identical. So refuse,
        // and let the screen say so.
        boolean needsIndex = !clauses.isEmpty() || FacetQueryClauses.mentionsItemClass(query);
        boolean filtersUsable = ProcessIndex.isReady() || !needsIndex;

        ProcessGraphBuilder builder = new ProcessGraphBuilder(adjacency, direction,
                filtersUsable ? clauses : List.of(),
                filtersUsable ? ItemQuery.of(FacetQueryClauses.itemExclusions(query)) : null,
                filtersUsable ? ItemQuery.of(FacetQueryClauses.itemRetention(query)) : null);
        ProcessGraph graph = builder.walk(rootKey, query, ProcessSearchConfig.treeWalkHops());
        if (graph != null && !filtersUsable) {
            graph.markIndexNotReady();
        }
        return graph;
    }

    private ProcessGraph walk(Object rootKey, String query, int depth) {
        Object display = Ingredients.display(rootKey);
        if (display == null) {
            return null;
        }
        ProcessGraph graph = new ProcessGraph(direction, query);
        graph.setBuilder(this);
        ItemNode root = graph.nodeFor(rootKey, display, 0);
        graph.setRoot(root);

        List<ItemNode> frontier = new ArrayList<>();
        frontier.add(root);
        for (int level = 0; level < depth && !frontier.isEmpty(); level++) {
            List<ItemNode> next = new ArrayList<>();
            for (ItemNode node : frontier) {
                if (graph.overBudget(maxNodes)) {
                    break;
                }
                expandOne(graph, node, next);
            }
            frontier = next;
        }

        applyItemHighlighting(graph);
        return graph;
    }

    /**
     * Walks outward from one node, far enough to fill the view.
     *
     * <p>Breadth-first for the same reason the initial walk is: a depth-first dive would spend the
     * node budget on the first branch and leave the rest of the layer empty.
     */
    boolean expandFrom(ProcessGraph graph, ItemNode node, int hops) {
        if (node == null) {
            return false;
        }
        int before = graph.nodeCount();
        List<ItemNode> frontier = new ArrayList<>();
        frontier.add(node);
        for (int hop = 0; hop < hops && !frontier.isEmpty(); hop++) {
            List<ItemNode> next = new ArrayList<>();
            for (ItemNode current : frontier) {
                if (graph.overBudget(maxNodes)) {
                    break;
                }
                expandOne(graph, current, next);
            }
            frontier = next;
        }
        return graph.nodeCount() != before;
    }

    // ------------------------------------------------------------ the walk

    private void expandOne(ProcessGraph graph, ItemNode node, List<ItemNode> discovered) {
        if (node.expanded) {
            return;
        }
        node.expanded = true;

        List<RecipeRef> refs = adjacency.from(node.key, direction);
        if (refs.isEmpty()) {
            return;
        }

        Map<IRecipeCategory<?>, List<Object>> byCategory = new LinkedHashMap<>();
        boolean facetsUsed = false;
        for (RecipeRef ref : refs) {
            IRecipeCategory<?> category = ref.category();
            if (category == null) {
                continue;
            }
            // Counted before the skips, and this is what makes an empty allowlist survivable: the
            // Filters panel lists every machine that touches what has been walked, so there is
            // always something to tick in even when nothing is enabled yet.
            graph.countEncountered(category, 1);
            if (!includedCategories.contains(category.getRecipeType().getUid().toString())) {
                continue;
            }
            if (!clauses.isEmpty()) {
                facetsUsed = true;
                if (!FacetQueryClauses.matchesAny(clauses, facetsOf(category, ref.recipe()))) {
                    continue;
                }
            }
            if (hideIdentity && isIdentity(ref)) {
                continue;
            }
            byCategory.computeIfAbsent(category, k -> new ArrayList<>()).add(ref.recipe());
        }
        if (facetsUsed) {
            graph.markFacetsApplied();
        }
        if (byCategory.isEmpty()) {
            return;
        }

        // Biggest first, so that when the width cap bites it keeps the processes that matter and
        // the "+N more" is a tail of one-offs rather than the interesting half.
        List<Map.Entry<IRecipeCategory<?>, List<Object>>> ordered = new ArrayList<>(byCategory.entrySet());
        ordered.sort(Comparator
                .comparingInt((Map.Entry<IRecipeCategory<?>, List<Object>> e) -> -e.getValue().size())
                .thenComparing(e -> e.getKey().getRecipeType().getUid().toString()));

        int shown = Math.min(ordered.size(), maxProcesses);
        node.hiddenProcesses = ordered.size() - shown;
        for (int i = 0; i < shown; i++) {
            if (graph.overBudget(maxNodes)) {
                node.hiddenProcesses = ordered.size() - i;
                break;
            }
            Map.Entry<IRecipeCategory<?>, List<Object>> entry = ordered.get(i);
            ProcessNode process = new ProcessNode(entry.getKey(), adjacency.iconFor(entry.getKey()),
                    List.copyOf(entry.getValue()), node);
            graph.countProcess();
            addFarSide(graph, process, node, discovered);
            node.add(process);
        }
    }

    /** The other end of the step: outputs when following consumers, inputs when following producers. */
    private void addFarSide(ProcessGraph graph, ProcessNode process, ItemNode parent,
                            List<ItemNode> discovered) {
        Map<Object, Integer> frequency = new LinkedHashMap<>();
        for (Object recipe : process.recipes) {
            // Resolving a recipe's layout is the expensive call, so stop once there is comfortably
            // more than the width cap can draw. Ordering by frequency needs a margin over the cap,
            // not the whole list.
            if (frequency.size() >= maxItems * 2) {
                break;
            }
            for (Object key : adjacency.farSideKeys(new RecipeRef(process.category, recipe), direction)) {
                if (key.equals(parent.key)) {
                    // Dropping the parent kills the trivial self-loop every reversible recipe has.
                    continue;
                }
                if (isExcluded(key)) {
                    // A negated search term means "do not show me this", so it never becomes a node
                    // at all -- not drawn, not expanded, and not kept as a stepping stone.
                    graph.markExcludedApplied();
                    continue;
                }
                frequency.merge(key, 1, Integer::sum);
            }
        }
        if (frequency.isEmpty()) {
            return;
        }

        List<Object> keys = new ArrayList<>(frequency.keySet());
        keys.sort(Comparator.comparingInt((Object key) -> -frequency.getOrDefault(key, 0)));

        int shown = Math.min(keys.size(), maxItems);
        process.hiddenItems = keys.size() - shown;
        for (int i = 0; i < shown; i++) {
            if (graph.overBudget(maxNodes)) {
                process.hiddenItems = keys.size() - i;
                break;
            }
            Object key = keys.get(i);
            Object display = Ingredients.display(key);
            if (display == null) {
                continue;
            }
            boolean fresh = !graph.isKnown(key);
            ItemNode child = graph.nodeFor(key, display, parent.depth + 1);
            process.add(child);
            if (fresh) {
                discovered.add(child);
            }
        }
    }

    /**
     * Every item on the far side of a machine, uncapped and unfiltered.
     *
     * <p>Deliberately ignores the width caps <em>and</em> the search exclusions, because this backs
     * the "+N more" chip: the whole point of clicking it is to see what was held back, and a list
     * that re-applied the very rules that hid them would be a dead end.
     */
    public static List<Object> allFarSide(ProcessNode process, Direction direction) {
        RecipeAdjacency adjacency = ProcessIndex.adjacency();
        if (adjacency == null) {
            return List.of();
        }
        Object parentKey = process.parent == null ? null : process.parent.key;
        Map<Object, Object> displays = new LinkedHashMap<>();
        for (Object recipe : process.recipes) {
            for (Object key : adjacency.farSideKeys(new RecipeRef(process.category, recipe), direction)) {
                if (key.equals(parentKey) || displays.containsKey(key)) {
                    continue;
                }
                Object display = Ingredients.display(key);
                if (display != null) {
                    displays.put(key, display);
                }
            }
        }
        return List.copyOf(displays.values());
    }

    private Set<String> facetsOf(IRecipeCategory<?> category, Object recipe) {
        try {
            return ProcessIndex.facetsForRecipe(category, recipe);
        } catch (RuntimeException | LinkageError e) {
            return Set.of();
        }
    }

    private boolean isExcluded(Object key) {
        if (exclusionQuery == null) {
            return false;
        }
        try {
            return exclusionQuery.test(key, Ingredients.display(key));
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /**
     * True when every output is something the recipe also consumes.
     *
     * <p>Which is precisely anvil repairing, grindstone and enchanting: steps that hand back an item
     * of the same kind and so loop tools endlessly back on themselves. Because keys collapse
     * component variants, an enchanted sword and a plain one are the same item here, which is what
     * makes enchanting fall out of this rule rather than needing to be named.
     */
    private boolean isIdentity(RecipeRef ref) {
        Boolean cached = identityCache.get(ref.recipe());
        if (cached != null) {
            return cached;
        }
        boolean identity = computeIdentity(ref);
        identityCache.put(ref.recipe(), identity);
        return identity;
    }

    private boolean computeIdentity(RecipeRef ref) {
        // Both sides by name, not by walk direction: identity is a property of the recipe, not of
        // which way it is being read.
        List<Object> outputs = adjacency.outputKeys(ref);
        List<Object> inputs = adjacency.inputKeys(ref);
        if (outputs.isEmpty() || inputs.isEmpty()) {
            return false;
        }
        // Two cheap gates before the comparison. A repair-shaped recipe hands back a single item and
        // takes a handful of specific ones; anything with several outputs, or an input list as wide
        // as a large tag, is a real transformation.
        if (outputs.size() > MAX_IDENTITY_OUTPUTS || inputs.size() > MAX_IDENTITY_INPUT_KEYS) {
            return false;
        }
        Set<Object> inputKeys = new HashSet<>(inputs);
        for (Object output : outputs) {
            if (!inputKeys.contains(output)) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------ item highlighting

    /**
     * Marks which items match the positive half of the search.
     *
     * <p>Marking rather than pruning, deliberately. Retention that keeps only branches leading to a
     * match reads fine against a deep pre-walk, but once the walk is lazy every child is a leaf and
     * "leads to a match" degenerates into "is itself a match" -- a search for {@code cobblestone}
     * would delete crushing, milling and blasting for producing gravel, sand and stone. The screen
     * tints matches and prefers them when choosing what to draw deeper. Removal is what a leading
     * {@code -} is for.
     */
    private void applyItemHighlighting(ProcessGraph graph) {
        ItemNode root = graph.root();
        if (retentionQuery == null || root == null) {
            return;
        }
        graph.markItemFilterApplied();
        mark(root, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private void mark(ItemNode node, Set<ItemNode> seen) {
        if (!seen.add(node)) {
            return;
        }
        try {
            node.matchesFilter = retentionQuery.test(node);
        } catch (RuntimeException | LinkageError e) {
            node.matchesFilter = false;
        }
        for (ProcessNode process : node.processes()) {
            for (ItemNode child : process.items()) {
                mark(child, seen);
            }
        }
    }

}
