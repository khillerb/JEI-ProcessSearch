package dev.processsearch.mixin;

import java.util.LinkedHashMap;
import java.util.Map;

import dev.processsearch.ProcessSearch;
import dev.processsearch.ProcessSearchConfig;
import dev.processsearch.index.ProcessIndex;
import it.unimi.dsi.fastutil.chars.Char2ObjectMap;
import mezz.jei.api.helpers.IColorHelper;
import mezz.jei.api.helpers.IModIdHelper;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.common.config.IIngredientFilterConfig;
import mezz.jei.core.search.LimitedStringStorage;
import mezz.jei.core.search.PrefixInfo;
import mezz.jei.core.search.SearchMode;
import mezz.jei.gui.ingredients.IListElement;
import mezz.jei.gui.ingredients.IListElementInfo;
import mezz.jei.gui.search.ElementPrefixParser;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds the four process-search prefixes to JEI's filter grammar.
 *
 * <pre>
 *   &gt;process[/property]   what MAKES this item
 *   &lt;process[/property]   what CONSUMES this item
 *   *process               which MACHINE runs this process
 *   ~class                 what KIND of item this is
 * </pre>
 *
 * <p>JEI keeps its prefixes in a {@code char -> PrefixInfo} map built in this constructor, so
 * appending to it at TAIL is all it takes for the new prefixes to behave like native ones: they
 * combine with spaces (AND), {@code |} (OR) and a leading {@code -} (NOT) for free, and they
 * compose with {@code @mod}, {@code #tag} and the rest without any further work.
 *
 * <p>{@code addPrefix} is private, so the map is written directly.
 *
 * <p>{@link SearchMode#REQUIRE_PREFIX} matters: without it these tokens would join plain unprefixed
 * text search and every item would start matching its own machine names.
 */
@Mixin(ElementPrefixParser.class)
public class ElementPrefixParserMixin {
    @Shadow
    @Final
    private Char2ObjectMap<PrefixInfo<IListElementInfo<?>, IListElement<?>>> map;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void processsearch$addPrefixes(IIngredientManager ingredientManager,
                                           IIngredientFilterConfig filterConfig,
                                           IColorHelper colorHelper,
                                           IModIdHelper modIdHelper,
                                           CallbackInfo ci) {
        // LinkedHashMap so a duplicate config char is reported against the prefix that lost, in
        // registration order, rather than silently overwriting.
        Map<Character, PrefixInfo.IStringsGetter<IListElementInfo<?>>> wanted = new LinkedHashMap<>();
        Map<Character, String> labels = new LinkedHashMap<>();

        processsearch$want(wanted, labels, ProcessSearchConfig.madeByPrefix(),
                ProcessIndex::madeByStrings, "made by");
        processsearch$want(wanted, labels, ProcessSearchConfig.usedInPrefix(),
                ProcessIndex::usedInStrings, "used in");
        processsearch$want(wanted, labels, ProcessSearchConfig.machineForPrefix(),
                ProcessIndex::machineForStrings, "machine for");
        processsearch$want(wanted, labels, ProcessSearchConfig.itemClassPrefix(),
                ProcessIndex::itemClassStrings, "item class");

        int registered = 0;
        for (Map.Entry<Character, PrefixInfo.IStringsGetter<IListElementInfo<?>>> entry : wanted.entrySet()) {
            if (processsearch$register(entry.getKey(), entry.getValue(), labels.get(entry.getKey()))) {
                registered++;
            }
        }
        ProcessIndex.setRegisteredPrefixes(registered);
    }

    @Unique
    private void processsearch$want(Map<Character, PrefixInfo.IStringsGetter<IListElementInfo<?>>> wanted,
                                    Map<Character, String> labels,
                                    char prefix,
                                    PrefixInfo.IStringsGetter<IListElementInfo<?>> strings,
                                    String label) {
        if (wanted.containsKey(prefix)) {
            ProcessSearch.LOGGER.error(
                    "Prefix '{}' is configured for both '{}' and '{}'; '{}' will not be registered",
                    prefix, labels.get(prefix), label, label);
            return;
        }
        wanted.put(prefix, strings);
        labels.put(prefix, label);
    }

    @Unique
    private boolean processsearch$register(char prefix,
                                           PrefixInfo.IStringsGetter<IListElementInfo<?>> strings,
                                           String what) {
        if (map.containsKey(prefix)) {
            // JEI itself claims @ # $ % ^ &, and other addons claim their own. Losing our prefix is
            // a config fix; silently stealing someone else's would be a bug report with no clue in it.
            ProcessSearch.LOGGER.error(
                    "Search prefix '{}' ({}) is already taken -- change it in processsearch-client.toml",
                    prefix, what);
            return false;
        }
        map.put(prefix, new PrefixInfo<>(
                prefix,
                () -> SearchMode.REQUIRE_PREFIX,
                strings,
                LimitedStringStorage::new));
        ProcessSearch.LOGGER.info("Registered JEI search prefix '{}' ({})", prefix, what);
        return true;
    }
}
