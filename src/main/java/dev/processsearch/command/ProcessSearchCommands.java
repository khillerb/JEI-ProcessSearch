package dev.processsearch.command;

import java.util.List;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import dev.processsearch.ProcessSearchConfig;
import dev.processsearch.index.ProcessIndex;
import dev.processsearch.input.TreeKeys;
import dev.processsearch.recipe.RecipeFilter;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * {@code /processsearch} -- client-side, for checking what actually got indexed.
 *
 * <p>{@code facets} earns its place: the token vocabulary is derived from whatever mods are
 * installed, so it is not something that can be documented up front. This is how you find out that
 * the token is {@code fan_washing} and not {@code washing}, or that a heated mixing recipe is
 * reachable as {@code mixing/heat.heated}.
 */
public final class ProcessSearchCommands {
    private static final int MAX_LISTED = 60;
    private static final int EXPECTED_PREFIXES = 4;

    private ProcessSearchCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("processsearch")
                .then(Commands.literal("stats").executes(ProcessSearchCommands::stats))
                .then(Commands.literal("rebuild").executes(ProcessSearchCommands::rebuild))
                .then(Commands.literal("facets")
                        .executes(ctx -> facets(ctx, ""))
                        .then(Commands.argument("contains", StringArgumentType.string())
                                .executes(ctx -> facets(ctx, StringArgumentType.getString(ctx, "contains")))))
                .executes(ProcessSearchCommands::stats));
    }

    private static int stats(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        send(source, Component.literal("Process Search").withStyle(ChatFormatting.GOLD));
        send(source, prefixLine(ProcessSearchConfig.madeByPrefix(), "process[/property]", "made by"));
        send(source, prefixLine(ProcessSearchConfig.usedInPrefix(), "process[/property]", "used in"));
        send(source, prefixLine(ProcessSearchConfig.machineForPrefix(), "process", "machine for"));
        send(source, prefixLine(ProcessSearchConfig.itemClassPrefix(), "class", "item class"));

        int registered = ProcessIndex.registeredPrefixes();
        if (registered < EXPECTED_PREFIXES) {
            // The mixin config fails soft so a JEI update cannot brick a live pack. The cost is that
            // a missed hook is invisible unless something says so out loud.
            send(source, Component.literal("  WARNING: only " + registered + " of " + EXPECTED_PREFIXES
                            + " prefixes registered -- check the log and processsearch-client.toml")
                    .withStyle(ChatFormatting.RED));
        }

        switch (ProcessIndex.state()) {
            case IDLE -> send(source, Component.literal("  index: not built (open JEI, or search with a prefix)")
                    .withStyle(ChatFormatting.YELLOW));
            case BUILDING -> send(source, Component.literal("  index: building, "
                    + ProcessIndex.recipesIndexed() + " recipes so far").withStyle(ChatFormatting.YELLOW));
            case READY -> {
                send(source, Component.literal("  index: ready -- " + ProcessIndex.workMillis()
                                + " ms of work, spread over " + ProcessIndex.elapsedSeconds() + " s")
                        .withStyle(ChatFormatting.GREEN));
                send(source, Component.literal("  " + ProcessIndex.recipesIndexed() + " recipes over "
                        + ProcessIndex.categoriesIndexed() + " categories"));
                send(source, Component.literal("  " + ProcessIndex.producedEntryCount() + " made-by, "
                        + ProcessIndex.consumedEntryCount() + " used-in, "
                        + ProcessIndex.machineEntryCount() + " machine entries"));
                send(source, Component.literal("  " + ProcessIndex.facetCount() + " distinct facets"));
            }
        }

        if (ProcessSearchConfig.processTree()) {
            String keys = TreeKeys.describeConsumers() + " / " + TreeKeys.describeProducers();
            send(source, Component.literal("  process tree: " + keys + " over a hovered item")
                    .withStyle(ChatFormatting.GRAY));
        } else {
            send(source, Component.literal("  process tree: off (enableProcessTree)")
                    .withStyle(ChatFormatting.GRAY));
        }

        if (RecipeFilter.isActive()) {
            send(source, Component.literal("  recipe pages filtered: " + RecipeFilter.lastKept()
                    + " of " + RecipeFilter.lastTotal()).withStyle(ChatFormatting.AQUA));
        }
        return 1;
    }

    private static Component prefixLine(char prefix, String shape, String meaning) {
        return Component.literal("  ")
                .append(Component.literal(prefix + shape).withStyle(ChatFormatting.AQUA))
                .append(Component.literal("  " + meaning).withStyle(ChatFormatting.GRAY));
    }

    private static int rebuild(CommandContext<CommandSourceStack> ctx) {
        ProcessIndex.reset();
        RecipeFilter.invalidate();
        ProcessIndex.requestBuild();
        send(ctx.getSource(), Component.literal("Process Search: rebuilding index")
                .withStyle(ChatFormatting.GREEN));
        return 1;
    }

    private static int facets(CommandContext<CommandSourceStack> ctx, String contains) {
        CommandSourceStack source = ctx.getSource();
        if (!ProcessIndex.isReady()) {
            send(source, Component.literal("Process Search: index not ready yet")
                    .withStyle(ChatFormatting.YELLOW));
            return 0;
        }
        List<String> matches = ProcessIndex.facetsMatching(contains, MAX_LISTED + 1);
        if (matches.isEmpty()) {
            send(source, Component.literal("No facets matching '" + contains + "'")
                    .withStyle(ChatFormatting.YELLOW));
            return 0;
        }
        boolean truncated = matches.size() > MAX_LISTED;
        List<String> shown = truncated ? matches.subList(0, MAX_LISTED) : matches;
        send(source, Component.literal(String.join(", ", shown)).withStyle(ChatFormatting.GRAY));
        if (truncated) {
            send(source, Component.literal("... narrow it with /processsearch facets \"text\"")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        return shown.size();
    }

    private static void send(CommandSourceStack source, Component message) {
        source.sendSuccess(() -> message, false);
    }
}
