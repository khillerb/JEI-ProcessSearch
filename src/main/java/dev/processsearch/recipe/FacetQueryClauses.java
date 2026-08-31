package dev.processsearch.recipe;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.processsearch.ProcessSearchConfig;

/**
 * Reads the facet half of JEI's filter box.
 *
 * <p>Shared by the recipe-page filter and the process tree, so the two cannot drift apart -- a query
 * that hides a recipe page must hide the same recipe in the tree.
 *
 * <p>The grammar is JEI's own, deliberately: quoted runs stay together, {@code |} separates
 * alternatives, whitespace is AND within an alternative, and a leading {@code -} negates.
 */
public final class FacetQueryClauses {
    /** JEI's own tokenizer: quoted runs stay together, a leading - negates. */
    private static final Pattern TOKEN = Pattern.compile("(-?\"[^\"]*\"?|\\S+)");
    private static final Pattern QUOTE = Pattern.compile("\"");
    private static final Pattern OR = Pattern.compile("\\|");

    private FacetQueryClauses() {}

    /** One alternative from an OR-separated query. */
    public record Clause(List<String> required, List<String> excluded) {
        public boolean isEmpty() {
            return required.isEmpty() && excluded.isEmpty();
        }

        public boolean matches(Set<String> facets) {
            for (String token : required) {
                if (!contains(facets, token)) {
                    return false;
                }
            }
            for (String token : excluded) {
                if (contains(facets, token)) {
                    return false;
                }
            }
            return true;
        }

        /** Substring, to match how JEI's suffix-tree storage behaves in the item list. */
        private static boolean contains(Set<String> facets, String token) {
            for (String facet : facets) {
                if (facet.contains(token)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * @return one clause per OR alternative, or empty when the query cannot narrow recipes at all
     */
    public static List<Clause> parse(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String[] alternatives = OR.split(query);
        List<Clause> clauses = new ArrayList<>(alternatives.length);
        for (String alternative : alternatives) {
            Clause clause = parseClause(alternative);
            if (!clause.isEmpty()) {
                clauses.add(clause);
            }
        }
        // An alternative with no facet tokens matches everything, so the whole OR does. Plain text
        // search, or a query using only the item-class prefix, lands here and filters nothing.
        return clauses.size() == alternatives.length ? clauses : List.of();
    }

    public static boolean matchesAny(List<Clause> clauses, Set<String> facets) {
        for (Clause clause : clauses) {
            if (clause.matches(facets)) {
                return true;
            }
        }
        return false;
    }

    private static Clause parseClause(String text) {
        List<String> required = new ArrayList<>(2);
        List<String> excluded = new ArrayList<>(2);
        char madeBy = ProcessSearchConfig.madeByPrefix();
        char usedIn = ProcessSearchConfig.usedInPrefix();
        char machineFor = ProcessSearchConfig.machineForPrefix();

        Matcher matcher = TOKEN.matcher(text);
        while (matcher.find()) {
            String token = matcher.group(1);
            boolean negated = token.startsWith("-");
            if (negated) {
                token = token.substring(1);
            }
            token = QUOTE.matcher(token).replaceAll("");
            if (token.length() < 2) {
                continue;
            }
            char prefix = token.charAt(0);
            if (prefix != madeBy && prefix != usedIn && prefix != machineFor) {
                continue;
            }
            String facet = token.substring(1).toLowerCase(Locale.ROOT);
            if (!facet.isEmpty()) {
                (negated ? excluded : required).add(facet);
            }
        }
        return new Clause(List.copyOf(required), List.copyOf(excluded));
    }

    // ------------------------------------------------------------ the item half

    /** True when the query mentions the {@code ~} prefix, which only the index can answer. */
    public static boolean mentionsItemClass(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        char itemClass = ProcessSearchConfig.itemClassPrefix();
        Matcher matcher = TOKEN.matcher(query);
        while (matcher.find()) {
            String token = strip(matcher.group(1));
            if (!token.isEmpty() && token.charAt(0) == itemClass) {
                return true;
            }
        }
        return false;
    }

    /**
     * The negated item terms, as a list of tokens with their {@code -} removed.
     *
     * <p>These are hard exclusions on the graph: an item matching one never becomes a node. The
     * recipe prefixes are dropped here, because asking "is this item made by mixing?" of every item
     * in the graph would be a different question from the one that was typed.
     */
    public static List<String> itemExclusions(String query) {
        return itemTerms(query, true);
    }

    /** The positive item terms. These tint matches and pull them forward; they never remove. */
    public static List<String> itemRetention(String query) {
        return itemTerms(query, false);
    }

    private static List<String> itemTerms(String query, boolean negated) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        char madeBy = ProcessSearchConfig.madeByPrefix();
        char usedIn = ProcessSearchConfig.usedInPrefix();
        char machineFor = ProcessSearchConfig.machineForPrefix();

        List<String> terms = new ArrayList<>(2);
        Matcher matcher = TOKEN.matcher(query);
        while (matcher.find()) {
            String raw = matcher.group(1);
            boolean isNegated = raw.startsWith("-");
            if (isNegated != negated) {
                continue;
            }
            String token = strip(raw);
            if (token.isEmpty()) {
                continue;
            }
            char prefix = token.charAt(0);
            if (prefix == madeBy || prefix == usedIn || prefix == machineFor) {
                continue;
            }
            terms.add(token.toLowerCase(Locale.ROOT));
        }
        return List.copyOf(terms);
    }

    private static String strip(String token) {
        String stripped = token.startsWith("-") ? token.substring(1) : token;
        return QUOTE.matcher(stripped).replaceAll("");
    }
}
