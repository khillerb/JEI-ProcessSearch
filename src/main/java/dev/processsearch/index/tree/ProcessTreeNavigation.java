package dev.processsearch.index.tree;

import java.util.ArrayDeque;
import java.util.Deque;

import dev.processsearch.ProcessSearch;
import dev.processsearch.ProcessSearchConfig;
import dev.processsearch.index.ProcessIndex;
import dev.processsearch.index.Scan;
import dev.processsearch.screen.ProcessGraphScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Holds the built graph so moving between the overview and the recipe list costs nothing.
 *
 * <p>A walk over a 445-mod pack is not something to repeat because someone clicked Back, so the
 * graph is kept until the world changes or the search text does. Either of those makes it wrong
 * rather than merely stale.
 */
public final class ProcessTreeNavigation {
    private static ProcessGraph current;
    /** The inventory screen the player was on, to return to when the tree closes. */
    private static Screen origin;
    /** Previous roots, so re-rooting is undoable. */
    private static final Deque<ProcessGraph> HISTORY = new ArrayDeque<>();
    private static final int MAX_HISTORY = 16;

    private ProcessTreeNavigation() {}

    /**
     * Entry point from the hotkey.
     *
     * @param hovered the raw ingredient under the cursor, an {@code ItemStack} or {@code FluidStack}
     * @return true if a tree screen was opened
     */
    public static boolean open(Object hovered, Direction direction) {
        if (!ProcessSearchConfig.processTree() || hovered == null) {
            return false;
        }
        Object key = Scan.key(hovered);
        if (key == null) {
            return false;
        }
        rememberOrigin();
        HISTORY.clear();
        return show(key, direction);
    }

    /** Re-roots the graph at another node, keeping the old one on the back stack. */
    public static boolean reroot(Object key, Direction direction) {
        if (current != null) {
            push(current);
        }
        return show(key, direction);
    }

    /**
     * Walks the current root again from scratch.
     *
     * <p>For when the rules changed under it -- the category dropdown edits config, and the cache is
     * keyed on root, direction and query, so it would otherwise hand back the graph built under the
     * old exclusions.
     */
    public static boolean rebuildCurrent() {
        if (current == null || current.root() == null) {
            return false;
        }
        Object key = current.root().key;
        Direction direction = current.direction;
        current = null;
        return show(key, direction);
    }

    /** @return true when there was somewhere to go back to */
    public static boolean back() {
        ProcessGraph previous = HISTORY.pollLast();
        if (previous == null) {
            return false;
        }
        current = previous;
        openGraphScreen();
        return true;
    }

    public static boolean canGoBack() {
        return !HISTORY.isEmpty();
    }

    private static boolean show(Object key, Direction direction) {
        ProcessGraph graph = reusable(key, direction);
        if (graph == null) {
            long start = System.nanoTime();
            graph = ProcessGraphBuilder.build(key, direction);
            if (graph == null) {
                return false;
            }
            ProcessSearch.LOGGER.debug("Built process graph: {} nodes in {} ms",
                    graph.nodeCount(), (System.nanoTime() - start) / 1_000_000L);
        }
        current = graph;
        openGraphScreen();
        return true;
    }

    /** The cached graph, when it still describes the same question under the same search. */
    private static ProcessGraph reusable(Object key, Direction direction) {
        if (current == null || current.direction != direction) {
            return null;
        }
        ItemNode root = current.root();
        if (root == null || key == null || !key.equals(root.key)) {
            return null;
        }
        return current.query.equals(ProcessIndex.currentFilterText()) ? current : null;
    }

    public static ProcessGraph graph() {
        return current;
    }

    /** Reopens the overview from cache, which is what Back from the recipe list does. */
    public static void openGraphScreen() {
        if (current == null) {
            close();
            return;
        }
        Minecraft.getInstance().setScreen(new ProcessGraphScreen(current));
    }

    /** Returns to whatever the player was looking at before the tree opened. */
    public static void close() {
        Minecraft.getInstance().setScreen(origin);
    }

    /** Dropped when the world changes: the graph points at recipe objects that no longer exist. */
    public static void invalidate() {
        current = null;
        origin = null;
        HISTORY.clear();
    }

    private static void push(ProcessGraph graph) {
        HISTORY.addLast(graph);
        while (HISTORY.size() > MAX_HISTORY) {
            HISTORY.pollFirst();
        }
    }

    /** Whatever was open when the hotkey fired, usually the inventory JEI was drawn over. */
    private static void rememberOrigin() {
        origin = Minecraft.getInstance().screen;
    }
}
