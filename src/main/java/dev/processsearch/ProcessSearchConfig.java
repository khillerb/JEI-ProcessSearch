package dev.processsearch;

import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Client config.
 *
 * <p>Every getter here is defensive. The prefix getters in particular are read from a JEI mixin
 * that runs while JEI builds its ingredient filter, and there is no ordering guarantee that config
 * has finished loading at that point -- {@link ModConfigSpec.ConfigValue#get()} throws outright if
 * the spec is not loaded yet. A thrown exception inside that mixin would take the whole filter
 * down, so each getter falls back to the same default the spec declares.
 *
 * <p>A {@code ModConfigSpec} rather than a hand-rolled file, because NeoForge generates the whole
 * main-menu config screen from one: every comment here becomes a tooltip and every range becomes a
 * slider. {@code treeIncludedCategories} is the one entry not meant to be edited there -- it is
 * whatever machines the graph in front of you happens to touch, so the Filters button owns it.
 */
public final class ProcessSearchConfig {
    private static final ModConfigSpec.Builder B = new ModConfigSpec.Builder();

    private static final String DEFAULT_MADE_BY = ">";
    private static final String DEFAULT_USED_IN = "<";
    private static final String DEFAULT_MACHINE_FOR = "*";
    private static final String DEFAULT_ITEM_CLASS = "~";

    // ---------------------------------------------------------------- prefixes

    public static final ModConfigSpec.ConfigValue<String> MADE_BY_PREFIX = B
            .comment("Prefix for 'what MAKES this item'. Takes process[/property].",
                     "  >mixing                 anything a mixer outputs",
                     "  >mixing/heat.heated     ... but only with a blaze burner under the basin",
                     "Each prefix must be a single character no other search prefix uses.",
                     "Taken by JEI: @ # $ % ^ &   Taken by JEI Recipe Manager (if installed): - +",
                     "A leading - is JEI's NOT operator, so ->mixing excludes.")
            .define("madeByPrefix", DEFAULT_MADE_BY);

    public static final ModConfigSpec.ConfigValue<String> USED_IN_PREFIX = B
            .comment("Prefix for 'what CONSUMES this item'. Same process[/property] shape.",
                     "  <crushing               anything you can feed a crusher")
            .define("usedInPrefix", DEFAULT_USED_IN);

    public static final ModConfigSpec.ConfigValue<String> MACHINE_FOR_PREFIX = B
            .comment("Prefix for 'which MACHINE runs this process'. Takes a bare process.",
                     "  *mixing                 -> Mechanical Mixer, Basin")
            .define("machineForPrefix", DEFAULT_MACHINE_FOR);

    public static final ModConfigSpec.ConfigValue<String> ITEM_CLASS_PREFIX = B
            .comment("Prefix for 'what KIND of item is this'. Takes a bare class.",
                     "  ~compressed  ~decorative  ~dye",
                     "Mostly used negated, to clear the item grid:  >crafting -~compressed")
            .define("itemClassPrefix", DEFAULT_ITEM_CLASS);

    // ---------------------------------------------------------------- facet sources

    public static final ModConfigSpec.ConfigValue<Boolean> ENABLE_CREATE_FACETS = B
            .comment("Index Create's recipe-level facets: heat.*, fluid.*, chance.*, speed.*.",
                     "Covers every mod built on Create's shared ProcessingRecipe base, which in this",
                     "pack means roughly eighteen addons as well as Create itself.")
            .define("enableCreateFacets", true);

    public static final ModConfigSpec.ConfigValue<Boolean> ENABLE_MI_FACETS = B
            .comment("Index Modern Industrialization's facets: eu.* voltage tier, speed.*, chance.*.",
                     "One MachineRecipe class covers every MI machine.")
            .define("enableModernIndustrializationFacets", true);

    public static final ModConfigSpec.ConfigValue<Boolean> ENABLE_CATALYST_FACETS = B
            .comment("Index each category's catalyst items, which powers the machine-for prefix and",
                     "lets you search by the machine's own name (>mechanical_mixer) as well as by",
                     "the category (>mixing).")
            .define("enableCatalystFacets", true);

    public static final ModConfigSpec.ConfigValue<Boolean> ENABLE_RECIPE_PAGE_FILTER = B
            .comment("Apply the search box to the recipe GUI as well as the item grid. With this on,",
                     "typing >mixing/heat.heated and then pressing R shows only heated mixing pages",
                     "instead of every mixing recipe in the pack.")
            .define("enableRecipePageFilter", true);

    // ---------------------------------------------------------------- item classes

    public static final ModConfigSpec.ConfigValue<List<? extends String>> DECORATIVE_MOD_IDS = B
            .comment("Mods whose items are tagged ~decorative. These are furniture and block-variant",
                     "mods that flood the item grid without ever being the answer to an automation",
                     "question. JEI's @mod already excludes them one at a time; this does it in one.",
                     "Defaults are the decorative mods actually present in this pack.")
            .defineListAllowEmpty("decorativeModIds",
                    List.of("bibliocraft", "chipped", "framedblocks", "xtonesreworked",
                            "mcwbridges", "mcwdoors", "mcwfences", "mcwfurnitures", "mcwlights",
                            "mcwpaths", "mcwroofs", "mcwtrpdoors", "mcwwindows"),
                    () -> "",
                    o -> o instanceof String);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> TRIM_MOD_IDS = B
            .comment("Mods whose items are tagged ~trim. Armour-trim mods generate one item per",
                     "trim per material per armour piece, which is thousands of entries that are",
                     "never the answer to an automation question.",
                     "Exclude with:  >crafting -~trim")
            .defineListAllowEmpty("trimModIds",
                    List.of("allthetrims", "dynamictrim", "more_armor_trims", "bettertrims"),
                    () -> "",
                    o -> o instanceof String);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> COMPRESSED_MOD_IDS = B
            .comment("Mods whose items are tagged ~compressed. All The Compressed alone is ~3600",
                     "recipes of pure tier noise: every block compressed 1x through 9x.")
            .defineListAllowEmpty("compressedModIds",
                    List.of("allthecompressed"),
                    () -> "",
                    o -> o instanceof String);

    // ---------------------------------------------------------------- dye rules

    public static final ModConfigSpec.ConfigValue<List<? extends String>> DYE_CATEGORY_IDS = B
            .comment("Recipe categories whose every recipe counts as a dye recipe, tagged 'dye'.",
                     "Create: Dragons Plus generates one fan-coloring recipe per dyeable item per",
                     "colour, which is the single largest source of item-list noise in ATM10.",
                     "Exclude with:  >mixing -~dye")
            .defineListAllowEmpty("dyeCategoryIds",
                    List.of("create_dragons_plus:fan_coloring"),
                    () -> "",
                    o -> o instanceof String);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> DYE_RECIPE_PATTERNS = B
            .comment("Regexes matched against a recipe's full id (namespace:path). A match tags the",
                     "recipe's outputs with 'dye'. Defaults cover Create: Dragons Plus, whose 102 of",
                     "103 mixing recipes are dye conversions.")
            .defineListAllowEmpty("dyeRecipePatterns",
                    List.of("_dye_from_item$", "_dye_from_fluid$", "_as_coloring$", "_dye$"),
                    () -> "",
                    o -> o instanceof String);

    // ---------------------------------------------------------------- build

    public static final ModConfigSpec.ConfigValue<List<? extends String>> EXCLUDED_CATEGORIES = B
            .comment("Recipe category ids to skip entirely. Use this if some modded category is slow",
                     "to index or floods the index with tokens you do not want.")
            .defineListAllowEmpty("excludedCategories", List.of(), () -> "", o -> o instanceof String);

    public static final ModConfigSpec.IntValue BUILD_BUDGET_MS = B
            .comment("Milliseconds per client tick spent building the index. The build is sliced",
                     "across ticks on the client thread so it never blocks, and never runs off-thread",
                     "where a modded recipe category might touch client state.")
            .defineInRange("buildTimeBudgetMillisPerTick", 3, 1, 50);

    public static final ModConfigSpec.ConfigValue<Boolean> REUSE_INDEX = B
            .comment("Keep the index when you leave a world and reuse it on the next join if the",
                     "recipes are unchanged. Going singleplayer -> menu -> server on the same pack is",
                     "the common case, and rebuilding for it is pure waste. The recipes are compared",
                     "by an order-independent fingerprint of their ids, outputs and category tags, so",
                     "a pack that actually changed still rebuilds.")
            .define("reuseIndexAcrossWorlds", true);

    // ---------------------------------------------------------------- process tree

    public static final ModConfigSpec.ConfigValue<Boolean> ENABLE_PROCESS_TREE = B
            .comment("The < and > graph screens, opened over a hovered item.",
                     "  <   what can this be processed INTO?",
                     "  >   what are all the ways to MAKE this?",
                     "Turning this off also frees the recipe adjacency the walk needs, which is the",
                     "only part of this mod with a memory cost worth mentioning.")
            .define("enableProcessTree", true);

    public static final ModConfigSpec.ConfigValue<String> TREE_CONSUMERS_KEY = B
            .comment("Key that opens the 'processed into' tree over a hovered item.",
                     "Minecraft key names minus the key.keyboard. prefix, optionally with modifiers:",
                     "  shift+comma   ctrl+alt+g   f6   left.bracket",
                     "shift+comma and shift+period are < and > on a US layout only, which is why",
                     "these are configurable.")
            .define("treeConsumersKey", "shift+comma");

    public static final ModConfigSpec.ConfigValue<String> TREE_PRODUCERS_KEY = B
            .comment("Key that opens the 'ways to make this' tree over a hovered item.")
            .define("treeProducersKey", "shift+period");

    public static final ModConfigSpec.IntValue TREE_VIEW_LAYERS = B
            .comment("Rows drawn including the focus, and the depth the walk derives from it.",
                     "Rows alternate item, machine, item, so odd values only -- an even one would end",
                     "on a machine layer with nothing to show for it, and is rounded down.")
            .defineInRange("treeViewLayers", 7, 1, 9);

    public static final ModConfigSpec.IntValue TREE_VISIBLE_MACHINES = B
            .comment("Machines drawn around the focused item. The rest become a +N chip.")
            .defineInRange("treeVisibleMachines", 12, 1, 64);

    public static final ModConfigSpec.IntValue TREE_VISIBLE_ITEMS_PER_MACHINE = B
            .comment("Items drawn under each machine. Six fills a 3x2 block on the bottom row.")
            .defineInRange("treeVisibleItemsPerMachine", 6, 1, 64);

    public static final ModConfigSpec.IntValue TREE_VISIBLE_PER_LAYER = B
            .comment("Nodes drawn on each layer past the focus's own items. Detail has to decay with",
                     "distance or seven layers is thousands of boxes.")
            .defineInRange("treeVisiblePerLayer", 72, 2, 200);

    public static final ModConfigSpec.IntValue TREE_MAX_PROCESSES_PER_ITEM = B
            .comment("A ceiling on what the walk keeps per item, not on what is drawn. Raise this if",
                     "a +N chip is hiding something real.")
            .defineInRange("treeMaxProcessesPerItem", 32, 1, 128);

    public static final ModConfigSpec.IntValue TREE_MAX_ITEMS_PER_PROCESS = B
            .comment("The same ceiling for the items under one machine.")
            .defineInRange("treeMaxItemsPerProcess", 32, 1, 128);

    public static final ModConfigSpec.IntValue TREE_MAX_NODES = B
            .comment("Safety backstop on the accumulated graph across a whole session. The walk is",
                     "lazy, so this is only reached by exploring a very long way.")
            .defineInRange("treeMaxNodes", 6000, 50, 50000);

    public static final ModConfigSpec.IntValue TREE_MIN_ZOOM_PERCENT = B
            .comment("How far out you can scroll, and how far Fit will go. Below 55% the tree drops",
                     "its labels and the icons grow as you keep going, so there is a reason to go low.")
            .defineInRange("treeMinZoomPercent", 8, 3, 100);

    public static final ModConfigSpec.ConfigValue<Boolean> TREE_HIDE_IDENTITY_RECIPES = B
            .comment("Drop recipes whose outputs are all things they also consume. Anvil repair,",
                     "grindstone and enchanting are all shaped that way, and each turns any tool into",
                     "an endless loop on the graph. Matching the shape rather than naming the three",
                     "catches anything else like them for free.")
            .define("treeHideIdentityRecipes", true);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> TREE_INCLUDED_CATEGORIES = B
            .comment("Recipe categories the tree is allowed to follow. Opt-in: an empty list follows",
                     "nothing. Edited by the Filters button on the graph rather than by hand -- it",
                     "lists every category the walk actually met, busiest first.")
            .defineListAllowEmpty("treeIncludedCategories", List.of(), () -> "", o -> o instanceof String);

    public static final ModConfigSpec SPEC = B.build();

    private ProcessSearchConfig() {}

    // ---------------------------------------------------------------- accessors

    public static char madeByPrefix() {
        return firstChar(MADE_BY_PREFIX, DEFAULT_MADE_BY);
    }

    public static char usedInPrefix() {
        return firstChar(USED_IN_PREFIX, DEFAULT_USED_IN);
    }

    public static char machineForPrefix() {
        return firstChar(MACHINE_FOR_PREFIX, DEFAULT_MACHINE_FOR);
    }

    public static char itemClassPrefix() {
        return firstChar(ITEM_CLASS_PREFIX, DEFAULT_ITEM_CLASS);
    }

    /** All four prefix characters, in the order they are registered. */
    public static char[] allPrefixes() {
        return new char[] { madeByPrefix(), usedInPrefix(), machineForPrefix(), itemClassPrefix() };
    }

    private static char firstChar(ModConfigSpec.ConfigValue<String> value, String fallback) {
        String s;
        try {
            s = value.get();
        } catch (RuntimeException e) {
            s = fallback;
        }
        return (s == null || s.isEmpty()) ? fallback.charAt(0) : s.charAt(0);
    }

    public static boolean createFacets() {
        return bool(ENABLE_CREATE_FACETS, true);
    }

    public static boolean miFacets() {
        return bool(ENABLE_MI_FACETS, true);
    }

    public static boolean catalystFacets() {
        return bool(ENABLE_CATALYST_FACETS, true);
    }

    public static boolean recipePageFilter() {
        return bool(ENABLE_RECIPE_PAGE_FILTER, true);
    }

    public static boolean reuseIndexAcrossWorlds() {
        return bool(REUSE_INDEX, true);
    }

    public static boolean processTree() {
        return bool(ENABLE_PROCESS_TREE, true);
    }

    public static String treeConsumersKey() {
        return string(TREE_CONSUMERS_KEY, "shift+comma");
    }

    public static String treeProducersKey() {
        return string(TREE_PRODUCERS_KEY, "shift+period");
    }

    /** Odd values only: the rows alternate item, machine, item. */
    public static int treeViewLayers() {
        int layers = integer(TREE_VIEW_LAYERS, 7);
        return layers % 2 == 0 ? layers - 1 : layers;
    }

    /** How far the walk goes: one hop per machine-and-item pair of rows, plus one to spare. */
    public static int treeWalkHops() {
        return Math.max(1, treeViewLayers() / 2 + 1);
    }

    public static int treeVisibleMachines() {
        return integer(TREE_VISIBLE_MACHINES, 12);
    }

    public static int treeVisibleItemsPerMachine() {
        return integer(TREE_VISIBLE_ITEMS_PER_MACHINE, 6);
    }

    public static int treeVisiblePerLayer() {
        return integer(TREE_VISIBLE_PER_LAYER, 72);
    }

    public static int treeMaxProcessesPerItem() {
        return integer(TREE_MAX_PROCESSES_PER_ITEM, 32);
    }

    public static int treeMaxItemsPerProcess() {
        return integer(TREE_MAX_ITEMS_PER_PROCESS, 32);
    }

    public static int treeMaxNodes() {
        return integer(TREE_MAX_NODES, 6000);
    }

    public static float treeMinZoom() {
        return integer(TREE_MIN_ZOOM_PERCENT, 8) / 100.0F;
    }

    public static boolean treeHideIdentityRecipes() {
        return bool(TREE_HIDE_IDENTITY_RECIPES, true);
    }

    public static List<String> treeIncludedCategories() {
        return stringList(TREE_INCLUDED_CATEGORIES);
    }

    /**
     * Writes the machine allowlist and bumps {@link #generation()}.
     *
     * <p>The bump is what stops the Filters panel appearing to do nothing: the graph is cached on
     * root, direction and query, so without a generation in that key the next lookup would hand back
     * the graph built under the old rules.
     */
    public static void setTreeIncludedCategories(List<String> ids) {
        try {
            TREE_INCLUDED_CATEGORIES.set(List.copyOf(ids));
            TREE_INCLUDED_CATEGORIES.save();
        } catch (RuntimeException e) {
            ProcessSearch.LOGGER.warn("Could not save treeIncludedCategories: {}", e.toString());
        }
        generation++;
    }

    /**
     * Bumped whenever something the index or the graph is derived from changes.
     *
     * <p>Part of both the index fingerprint and the graph cache key, so a config change invalidates
     * a cached result rather than being served stale.
     */
    public static int generation() {
        return generation;
    }

    private static int generation;

    private static String string(ModConfigSpec.ConfigValue<String> value, String fallback) {
        try {
            String s = value.get();
            return (s == null || s.isBlank()) ? fallback : s;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static int integer(ModConfigSpec.IntValue value, int fallback) {
        try {
            return value.get();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static boolean bool(ModConfigSpec.ConfigValue<Boolean> value, boolean fallback) {
        try {
            return value.get();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    public static int buildBudgetMillis() {
        try {
            return BUILD_BUDGET_MS.get();
        } catch (RuntimeException e) {
            return 3;
        }
    }

    public static List<String> stringList(ModConfigSpec.ConfigValue<List<? extends String>> value) {
        try {
            return List.copyOf(value.get());
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** Compiles {@link #DYE_RECIPE_PATTERNS}, dropping any entry the user typo'd rather than throwing. */
    public static List<Pattern> dyePatterns() {
        return stringList(DYE_RECIPE_PATTERNS).stream()
                .map(ProcessSearchConfig::compileQuietly)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private static Pattern compileQuietly(String regex) {
        try {
            return Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            ProcessSearch.LOGGER.warn("Ignoring invalid dyeRecipePatterns entry '{}': {}", regex, e.getMessage());
            return null;
        }
    }
}
