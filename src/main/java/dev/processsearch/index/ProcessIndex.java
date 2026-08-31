package dev.processsearch.index;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import dev.processsearch.ProcessSearch;
import dev.processsearch.ProcessSearchConfig;
import dev.processsearch.index.adapters.CreateProcessingAdapter;
import dev.processsearch.index.adapters.GenericJeiAdapter;
import dev.processsearch.index.adapters.MachineRecipeAdapter;
import dev.processsearch.index.adapters.VanillaRecipeAdapter;
import dev.processsearch.index.tree.RecipeAdjacency;
import dev.processsearch.mixin.IngredientFilterApiAccessor;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IIngredientFilter;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IJeiRuntime;
import mezz.jei.gui.ingredients.IListElementInfo;
import mezz.jei.gui.ingredients.IngredientFilter;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.neoforged.fml.ModList;

/**
 * Reverse index from registry singletons (Item or Fluid) to searchable facet tokens.
 *
 * <p>Four maps, one per search prefix: what makes a thing, what consumes it, what machine runs a
 * process, and what kind of thing an item is.
 *
 * <p>Built on the client thread, sliced across ticks under a time budget. Deliberately not on a
 * worker thread: the generic adapter has JEI run a recipe category's layout builder, and modded
 * categories are under no obligation to be safe to touch off-thread.
 */
public final class ProcessIndex {
    public enum State { IDLE, BUILDING, READY }

    /** {@code >} -- item is an output of a recipe carrying these tokens. */
    private static final Map<Object, Set<String>> MADE_BY = new HashMap<>();
    /** {@code <} -- item is an input. */
    private static final Map<Object, Set<String>> USED_IN = new HashMap<>();
    /** {@code *} -- item is the catalyst that runs these processes. */
    private static final Map<Object, Set<String>> MACHINE_FOR = new HashMap<>();
    /** {@code ~} -- recipe-derived item classes. Rule-derived ones are computed in the getter. */
    private static final Map<Object, Set<String>> ITEM_CLASS = new HashMap<>();

    /** categoryId -> its process tokens, kept after the build so the recipe filter can reuse them. */
    private static final Map<String, Set<String>> CATEGORY_TOKENS = new HashMap<>();

    /**
     * What the process tree walks: which recipes consume a key and which produce it.
     *
     * <p>Filled in the same pass as the facet maps, because that pass is already visiting every
     * recipe in the pack and a second one would double the build. Skipped entirely when the tree is
     * turned off -- it is the only structure here with a memory cost worth mentioning.
     */
    private static final RecipeAdjacency ADJACENCY = new RecipeAdjacency();
    private static boolean buildingAdjacency;

    private static IJeiRuntime runtime;
    private static State state = State.IDLE;

    /**
     * The fingerprint the finished index was built from, kept across a disconnect so the next join
     * can decide whether the maps still describe the world.
     */
    private static Fingerprint retainedFingerprint;
    /** Set while an index is being held aside rather than cleared -- see {@link #retire()}. */
    private static boolean retained;

    // Build cursor.
    private static List<IRecipeCategory<?>> categories = List.of();
    private static int categoryCursor;
    private static Iterator<?> currentRecipes;
    private static IRecipeCategory<?> currentCategory;
    private static String currentCategoryId;
    private static Set<String> currentProcessTokens = Set.of();
    private static List<RecipeAdapter> adapters = List.of();
    private static Facets.DyeRules dyeRules = new Facets.DyeRules(Set.of(), List.of());
    private static Facets.ItemClassRules itemClassRules;
    private static final Scan SCAN = new Scan();
    /** Scratch for the per-recipe token cross product. Build path only -- see indexRecipe. */
    private static final Set<String> COMBINED = new HashSet<>();
    private static final Set<String> WARNED = new HashSet<>();

    // Stats.
    private static int recipesIndexed;
    private static int categoriesIndexed;
    private static long buildStartNanos;
    /** Wall clock from build start to finish -- mostly time spent waiting for the next tick. */
    private static long elapsedNanos;
    /** Time actually spent indexing. This is the number that says whether the build is expensive. */
    private static long workNanos;

    /** Guards re-entrancy: finish() -> rebuildItemFilter() -> getElements() -> our own filter hook. */
    private static boolean rebuilding;

    /**
     * Set by the prefix mixin. The mixin config fails soft rather than crashing a live pack, which
     * means a JEI update that moved the target would otherwise just make the prefixes quietly stop
     * existing -- so /processsearch stats reports this.
     */
    private static int registeredPrefixes;

    private ProcessIndex() {}

    // ------------------------------------------------------------ lookup (called from the mixin)

    public static Collection<String> madeByStrings(IListElementInfo<?> info) {
        return lookup(MADE_BY, info);
    }

    public static Collection<String> usedInStrings(IListElementInfo<?> info) {
        return lookup(USED_IN, info);
    }

    public static Collection<String> machineForStrings(IListElementInfo<?> info) {
        return lookup(MACHINE_FOR, info);
    }

    /**
     * Item classes are half stored and half computed. {@code ~dye} comes from recipes and lives in
     * the map; {@code ~compressed} and {@code ~decorative} are a namespace test, so computing them
     * here costs nothing and, more importantly, catches items that have no recipes at all -- which
     * is most of what a furniture mod ships.
     */
    public static Collection<String> itemClassStrings(IListElementInfo<?> info) {
        Object key = keyOf(info);
        if (key == null) {
            return List.of();
        }
        Set<String> fromRules = itemClassRules().classify(key);
        Set<String> fromRecipes = ITEM_CLASS.getOrDefault(key, Set.of());
        if (fromRules.isEmpty()) {
            return fromRecipes;
        }
        if (fromRecipes.isEmpty()) {
            return fromRules;
        }
        Set<String> all = new HashSet<>(fromRules);
        all.addAll(fromRecipes);
        return all;
    }

    private static Collection<String> lookup(Map<Object, Set<String>> map, IListElementInfo<?> info) {
        if (map.isEmpty()) {
            return List.of();
        }
        Object key = keyOf(info);
        return key == null ? List.of() : map.getOrDefault(key, Set.of());
    }

    /** Item classes for a raw index key, which is what the graph has rather than a JEI element. */
    public static Collection<String> itemClassesFor(Object key) {
        if (key == null) {
            return List.of();
        }
        Set<String> stored = ITEM_CLASS.getOrDefault(key, Set.of());
        Set<String> rules = itemClassRules().classify(key);
        if (rules.isEmpty()) {
            return stored;
        }
        if (stored.isEmpty()) {
            return rules;
        }
        Set<String> both = new HashSet<>(stored);
        both.addAll(rules);
        return both;
    }

    /** The tree's recipe adjacency, or null when it was never built. */
    public static RecipeAdjacency adjacency() {
        return ADJACENCY.isEmpty() ? null : ADJACENCY;
    }

    /** JEI's runtime, or null before it lands. The hotkey needs it to find what is hovered. */
    public static IJeiRuntime runtime() {
        return runtime;
    }

    /** JEI's ingredient manager, for rendering a graph node. Null before the runtime lands. */
    public static IIngredientManager ingredientManager() {
        return runtime == null ? null : runtime.getIngredientManager();
    }

    private static Object keyOf(IListElementInfo<?> info) {
        try {
            return Scan.key(info.getTypedIngredient().getIngredient());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Facets.ItemClassRules itemClassRules() {
        if (itemClassRules == null) {
            itemClassRules = Facets.ItemClassRules.fromConfig();
        }
        return itemClassRules;
    }

    // ------------------------------------------------------------ lifecycle

    public static void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        runtime = jeiRuntime;
        reset();
    }

    /**
     * Drops the runtime as well as the data. Without this a resource reload would leave a dead
     * {@code IJeiRuntime} behind that {@link #requestBuild()} would happily go on to use.
     */
    public static void onRuntimeUnavailable() {
        runtime = null;
        reset();
    }

    /**
     * Keeps the finished index aside instead of clearing it, for a disconnect that is very likely
     * to be followed by a join to the same pack.
     *
     * <p>Nothing is copied: the maps stay exactly where they are, and the only state that changes is
     * that the index stops reporting itself ready. The next build either fingerprints its way back
     * to READY or clears them for real.
     */
    public static void retire() {
        if (state != State.READY || !ProcessSearchConfig.reuseIndexAcrossWorlds()) {
            reset();
            return;
        }
        retained = true;
        state = State.IDLE;
        categories = List.of();
        categoryCursor = 0;
        currentRecipes = null;
        currentCategory = null;
        currentProcessTokens = Set.of();
        adapters = List.of();
    }

    public static void reset() {
        retained = false;
        retainedFingerprint = null;
        MADE_BY.clear();
        USED_IN.clear();
        MACHINE_FOR.clear();
        ITEM_CLASS.clear();
        CATEGORY_TOKENS.clear();
        ADJACENCY.clear();
        WARNED.clear();
        categories = List.of();
        categoryCursor = 0;
        currentRecipes = null;
        currentCategory = null;
        currentProcessTokens = Set.of();
        adapters = List.of();
        itemClassRules = null;
        recipesIndexed = 0;
        categoriesIndexed = 0;
        elapsedNanos = 0;
        workNanos = 0;
        state = State.IDLE;
    }

    public static State state() {
        return state;
    }

    public static boolean isReady() {
        return state == State.READY;
    }

    public static boolean isRebuilding() {
        return rebuilding;
    }

    /** Called once per {@code ElementPrefixParser} construction, which recurs on every JEI reload. */
    public static void setRegisteredPrefixes(int count) {
        registeredPrefixes = count;
    }

    public static int registeredPrefixes() {
        return registeredPrefixes;
    }

    /** Starts the build if it has not started. Safe to call every tick. */
    public static void requestBuild() {
        if (state != State.IDLE || runtime == null || rebuilding) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            // Registries come from the connected level; without one, getResultItem cannot resolve.
            return;
        }

        List<RecipeAdapter> chain = new ArrayList<>(4);
        // Each mod-specific adapter is behind a ModList check so its classes never link without
        // the mod present.
        if (ProcessSearchConfig.createFacets() && ModList.get().isLoaded("create")) {
            chain.add(new CreateProcessingAdapter());
        }
        if (ProcessSearchConfig.miFacets() && ModList.get().isLoaded("modern_industrialization")) {
            chain.add(new MachineRecipeAdapter());
        }
        chain.add(new VanillaRecipeAdapter(mc.level.registryAccess()));
        chain.add(new GenericJeiAdapter(runtime.getRecipeManager()));
        adapters = List.copyOf(chain);

        buildingAdjacency = ProcessSearchConfig.processTree();
        if (buildingAdjacency) {
            ADJACENCY.setRecipeManager(runtime.getRecipeManager());
        }

        dyeRules = Facets.DyeRules.fromConfig();
        itemClassRules = Facets.ItemClassRules.fromConfig();
        Set<String> excluded = Set.copyOf(
                ProcessSearchConfig.stringList(ProcessSearchConfig.EXCLUDED_CATEGORIES));

        List<IRecipeCategory<?>> found = new ArrayList<>();
        runtime.getRecipeManager().createRecipeCategoryLookup().includeHidden().get()
                .filter(c -> !excluded.contains(c.getRecipeType().getUid().toString()))
                .forEach(found::add);

        // Fingerprinted over the filtered list, so what is compared is exactly what would be built.
        Fingerprint fingerprint = Fingerprint.of(runtime.getRecipeManager(), found);
        if (retained) {
            if (fingerprint != null && fingerprint.equals(retainedFingerprint)) {
                retained = false;
                state = State.READY;
                ProcessSearch.LOGGER.info(
                        "Reusing process index: {} recipes over {} categories are unchanged",
                        fingerprint.recipes(), fingerprint.categories());
                rebuildJeiFilter();
                return;
            }
            // Different pack, different recipes, or a config change. Start clean.
            ProcessSearch.LOGGER.info("Recipes changed since the last index; rebuilding");
            reset();
        }
        retainedFingerprint = fingerprint;

        categories = found;
        categoryCursor = 0;
        currentRecipes = null;
        buildStartNanos = System.nanoTime();
        state = State.BUILDING;
        ProcessSearch.LOGGER.info("Building process index over {} recipe categories", categories.size());
    }

    /** Advances the build by at most {@code budgetNanos}. */
    public static void pump(long budgetNanos) {
        pumpUntil(System.nanoTime() + budgetNanos);
    }

    /** Finishes the build now, blocking. Used when a prefixed query is typed mid-build. */
    public static void completeNow() {
        requestBuild();
        pumpUntil(Long.MAX_VALUE);
    }

    private static void pumpUntil(long deadlineNanos) {
        if (state != State.BUILDING) {
            return;
        }
        long enter = System.nanoTime();
        boolean done = drain(deadlineNanos);
        workNanos += System.nanoTime() - enter;
        if (done) {
            finish();
        }
    }

    /** @return true when every category has been consumed. */
    private static boolean drain(long deadlineNanos) {
        int sinceClockCheck = 0;
        while (true) {
            if (currentRecipes == null) {
                if (categoryCursor >= categories.size()) {
                    return true;
                }
                beginCategory(categories.get(categoryCursor++));
                if (currentRecipes == null) {
                    continue;
                }
            }
            while (hasNextRecipe()) {
                Object recipe = nextRecipe();
                if (recipe != null) {
                    indexRecipe(recipe);
                    recipesIndexed++;
                }
                // Reading the clock every recipe would cost more than the work it guards.
                if ((++sinceClockCheck & 0x3F) == 0 && System.nanoTime() >= deadlineNanos) {
                    return false;
                }
            }
            currentRecipes = null;
            categoriesIndexed++;
            if (System.nanoTime() >= deadlineNanos) {
                return false;
            }
        }
    }

    private static boolean hasNextRecipe() {
        if (currentRecipes == null) {
            return false;
        }
        try {
            return currentRecipes.hasNext();
        } catch (RuntimeException | LinkageError e) {
            warnOnce(currentCategoryId, e);
            currentRecipes = null;
            return false;
        }
    }

    private static Object nextRecipe() {
        try {
            return currentRecipes.next();
        } catch (RuntimeException | LinkageError e) {
            warnOnce(currentCategoryId, e);
            currentRecipes = null;
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static void beginCategory(IRecipeCategory<?> category) {
        currentCategory = category;
        RecipeType<?> type = category.getRecipeType();
        currentCategoryId = type.getUid().toString();

        List<ItemStack> catalysts = catalystsOf(type);
        currentProcessTokens = processTokens(category, type, catalysts);
        CATEGORY_TOKENS.put(currentCategoryId, currentProcessTokens);

        // The * prefix is just this relation read backwards: category -> catalysts becomes
        // catalyst -> processes, built in the same pass at no extra cost.
        for (ItemStack catalyst : catalysts) {
            Object key = Scan.key(catalyst);
            if (key != null) {
                MACHINE_FOR.computeIfAbsent(key, k -> new HashSet<>()).addAll(currentProcessTokens);
            }
        }
        if (buildingAdjacency && !catalysts.isEmpty()) {
            // The workstation, so a graph node reads "Crushing Wheels" rather than "create:crushing".
            ADJACENCY.setIcon(category, catalysts.get(0));
        }

        try {
            RecipeType<Object> erased = (RecipeType<Object>) type;
            currentRecipes = runtime.getRecipeManager()
                    .createRecipeLookup(erased).includeHidden().get().iterator();
        } catch (RuntimeException | LinkageError e) {
            warnOnce(currentCategoryId, e);
            currentRecipes = null;
        }
    }

    private static List<ItemStack> catalystsOf(RecipeType<?> type) {
        if (!ProcessSearchConfig.catalystFacets()) {
            return List.of();
        }
        try {
            return runtime.getRecipeManager().createRecipeCatalystLookup(type).includeHidden()
                    .getItemStack().toList();
        } catch (RuntimeException | LinkageError e) {
            warnOnce(type.getUid().toString(), e);
            return List.of();
        }
    }

    /**
     * Tokens every recipe in a category inherits: the category id, its bare path, its displayed
     * title, and the names of the machines that run it.
     */
    private static Set<String> processTokens(IRecipeCategory<?> category, RecipeType<?> type,
                                             List<ItemStack> catalysts) {
        Set<String> tokens = new HashSet<>();
        ResourceLocation uid = type.getUid();
        add(tokens, uid.toString());
        add(tokens, uid.getPath());
        try {
            add(tokens, category.getTitle().getString());
        } catch (RuntimeException e) {
            // A category whose title needs a context we do not have here; the id still works.
        }
        for (ItemStack catalyst : catalysts) {
            add(tokens, catalystName(catalyst));
        }
        return Set.copyOf(tokens);
    }

    private static String catalystName(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id == null ? null : id.getPath();
    }

    private static void add(Set<String> tokens, String raw) {
        if (raw == null || raw.isEmpty()) {
            return;
        }
        String token = Facets.sanitize(raw);
        if (!token.isEmpty()) {
            tokens.add(token);
        }
    }

    private static void indexRecipe(Object jeiRecipe) {
        Object recipe = jeiRecipe instanceof RecipeHolder<?> holder ? holder.value() : jeiRecipe;

        SCAN.reset();
        boolean handled = collectInto(currentCategory, jeiRecipe, recipe, SCAN, currentCategoryId);
        if (!handled || !SCAN.hasRoles()) {
            return;
        }

        boolean dye = dyeRules.matches(currentCategoryId, recipeId(currentCategory, jeiRecipe), recipe);
        if (dye) {
            SCAN.tokens.add(Facets.DYE);
        }

        // Reused rather than allocated per recipe: this runs 341k times in this pack.
        COMBINED.clear();
        Facets.combineInto(COMBINED, currentProcessTokens, SCAN.tokens);

        for (Object key : SCAN.outputs) {
            MADE_BY.computeIfAbsent(key, k -> new HashSet<>()).addAll(COMBINED);
            if (dye) {
                ITEM_CLASS.computeIfAbsent(key, k -> new HashSet<>()).add(Facets.CLASS_DYE);
            }
        }
        for (Object key : SCAN.inputs) {
            USED_IN.computeIfAbsent(key, k -> new HashSet<>()).addAll(COMBINED);
        }

        if (buildingAdjacency) {
            ADJACENCY.record(currentCategory, jeiRecipe, SCAN);
        }
    }

    /**
     * Runs the adapter chain, filling {@code scan} with role ingredients and property tokens.
     * Shared by the index build and the recipe-page filter.
     */
    private static boolean collectInto(IRecipeCategory<?> category, Object jeiRecipe, Object recipe,
                                       Scan scan, String categoryId) {
        // Facets that do not depend on which adapter claims the roles. Create's
        // automatic_shapeless category already carries "shapeless" inside its own name, and JEI
        // matches substrings, so >shapeless catches both without special-casing it.
        if (recipe instanceof ShapelessRecipe) {
            scan.tokens.add(Facets.SHAPELESS);
        }
        for (RecipeAdapter adapter : adapters) {
            try {
                if (adapter.collect(category, jeiRecipe, recipe, scan)) {
                    return true;
                }
            } catch (RuntimeException | LinkageError e) {
                warnOnce(categoryId + "/" + adapter.getClass().getSimpleName(), e);
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static ResourceLocation recipeId(IRecipeCategory<?> category, Object jeiRecipe) {
        if (jeiRecipe instanceof RecipeHolder<?> holder) {
            return holder.id();
        }
        try {
            return ((IRecipeCategory<Object>) category).getRegistryName(jeiRecipe);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    // ------------------------------------------------------------ recipe-page filtering

    /** Whatever is currently typed in JEI's search box, or empty if JEI is not up yet. */
    public static String currentFilterText() {
        if (runtime == null) {
            return "";
        }
        try {
            String text = runtime.getIngredientFilter().getFilterText();
            return text == null ? "" : text;
        } catch (RuntimeException | LinkageError e) {
            return "";
        }
    }

    /**
     * Facets for one recipe, computed on demand for the recipe GUI.
     *
     * <p>Deliberately not stored during the build. A recipe-to-facets map would be tens of
     * thousands of entries kept alive permanently to serve a filter that is only active while
     * someone is looking at a category page; recomputing one category's worth on the spot is far
     * cheaper, and {@code RecipeFilter} caches the result per (category, query) anyway.
     */
    public static Set<String> facetsForRecipe(IRecipeCategory<?> category, Object jeiRecipe) {
        if (state != State.READY) {
            return Set.of();
        }
        String categoryId = category.getRecipeType().getUid().toString();
        Set<String> processes = CATEGORY_TOKENS.getOrDefault(categoryId, Set.of());
        Object recipe = jeiRecipe instanceof RecipeHolder<?> holder ? holder.value() : jeiRecipe;

        Scan scan = new Scan();
        if (!collectInto(category, jeiRecipe, recipe, scan, categoryId)) {
            return processes;
        }
        if (dyeRules.matches(categoryId, recipeId(category, jeiRecipe), recipe)) {
            scan.tokens.add(Facets.DYE);
        }
        return Facets.combine(processes, scan.tokens);
    }

    // ------------------------------------------------------------ finish

    private static void finish() {
        // Most items share an identical token set -- every plain crafting output has the same one.
        // Collapsing them onto shared immutable instances turns tens of thousands of small sets
        // into a few hundred.
        canonicalize(MADE_BY);
        canonicalize(USED_IN);
        canonicalize(MACHINE_FOR);
        canonicalize(ITEM_CLASS);

        elapsedNanos = System.nanoTime() - buildStartNanos;
        state = State.READY;
        categories = List.of();
        currentCategory = null;
        currentRecipes = null;
        currentProcessTokens = Set.of();
        COMBINED.clear();

        // Both numbers, because only one of them is a cost. The build is capped at a few ms per
        // tick, so elapsed time is mostly waiting between ticks -- what matters is the work total.
        ProcessSearch.LOGGER.info(
                "Process index ready: {} recipes / {} categories, {} made-by + {} used-in + {} machine entries, "
                        + "{} facets, {} ms of work over {} s",
                recipesIndexed, categoriesIndexed, MADE_BY.size(), USED_IN.size(), MACHINE_FOR.size(),
                facetCount(), workNanos / 1_000_000L, elapsedNanos / 1_000_000_000L);
        if (buildingAdjacency) {
            ProcessSearch.LOGGER.info("Process tree adjacency: {} keys", ADJACENCY.entryCount());
        }

        rebuildJeiFilter();
    }

    private static void canonicalize(Map<Object, Set<String>> map) {
        Map<Set<String>, Set<String>> pool = new HashMap<>();
        map.replaceAll((key, tokens) -> pool.computeIfAbsent(tokens, Set::copyOf));
    }

    /**
     * Hands the freshly filled index to JEI.
     *
     * <p>JEI reads every prefix's strings exactly once, while it builds its search tree -- which
     * happens long before this index exists. {@code rebuildItemFilter} throws that tree away and
     * builds a new one, re-reading every string getter including ours. That is the whole reason
     * building lazily is possible at all.
     */
    private static void rebuildJeiFilter() {
        if (runtime == null || rebuilding) {
            return;
        }
        IIngredientFilter api = runtime.getIngredientFilter();
        if (!(api instanceof IngredientFilterApiAccessor accessor)) {
            ProcessSearch.LOGGER.warn(
                    "IngredientFilterApi mixin did not apply; prefixes stay empty until JEI reloads");
            return;
        }
        rebuilding = true;
        try {
            IngredientFilter filter = accessor.processsearch$getIngredientFilter();
            filter.rebuildItemFilter();
        } catch (RuntimeException | LinkageError e) {
            ProcessSearch.LOGGER.error("Failed to rebuild JEI's ingredient filter", e);
        } finally {
            rebuilding = false;
        }
    }

    private static void warnOnce(String key, Throwable e) {
        if (key != null && WARNED.add(key)) {
            ProcessSearch.LOGGER.warn("Skipping part of '{}' while indexing: {}", key, e.toString());
        }
    }

    // ------------------------------------------------------------ stats

    public static int producedEntryCount() {
        return MADE_BY.size();
    }

    public static int consumedEntryCount() {
        return USED_IN.size();
    }

    public static int machineEntryCount() {
        return MACHINE_FOR.size();
    }

    public static int recipesIndexed() {
        return recipesIndexed;
    }

    public static int categoriesIndexed() {
        return categoriesIndexed;
    }

    /** Time actually spent indexing, as opposed to waiting between ticks. */
    public static long workMillis() {
        return workNanos / 1_000_000L;
    }

    public static long elapsedSeconds() {
        return elapsedNanos / 1_000_000_000L;
    }

    public static int facetCount() {
        return distinctFacets().size();
    }

    public static List<String> facetsMatching(String contains, int limit) {
        String needle = contains == null ? "" : contains.toLowerCase(Locale.ROOT);
        return distinctFacets().stream()
                .filter(f -> needle.isEmpty() || f.contains(needle))
                .sorted()
                .limit(limit)
                .toList();
    }

    private static Set<String> distinctFacets() {
        Set<String> distinct = new HashSet<>();
        MADE_BY.values().forEach(distinct::addAll);
        USED_IN.values().forEach(distinct::addAll);
        MACHINE_FOR.values().forEach(distinct::addAll);
        return distinct;
    }
}
