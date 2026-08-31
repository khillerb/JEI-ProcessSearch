package dev.processsearch;

import dev.processsearch.command.ProcessSearchCommands;
import dev.processsearch.index.ProcessIndex;
import dev.processsearch.index.tree.ProcessTreeNavigation;
import dev.processsearch.recipe.RecipeFilter;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

/** Drives the index build: started on the first JEI-bearing screen, advanced a slice per tick. */
@EventBusSubscriber(modid = ProcessSearch.MOD_ID, value = Dist.CLIENT)
public final class ClientEvents {
    private ClientEvents() {}

    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (ProcessIndex.state() != ProcessIndex.State.IDLE) {
            return;
        }
        if (isJeiBearing(event.getNewScreen())) {
            ProcessIndex.requestBuild();
        }
    }

    /**
     * JEI's overlay rides on top of every container screen, and its own recipe GUI is a plain
     * {@link Screen} in the {@code mezz.jei} package. Either one means the player is somewhere they
     * could type a search.
     */
    private static boolean isJeiBearing(Screen screen) {
        if (screen == null) {
            return false;
        }
        return screen instanceof AbstractContainerScreen<?>
                || screen.getClass().getName().startsWith("mezz.jei.");
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (ProcessIndex.state() == ProcessIndex.State.BUILDING) {
            ProcessIndex.pump(ProcessSearchConfig.buildBudgetMillis() * 1_000_000L);
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        // Recipes belong to the connection, so nothing here may answer for the next world as it
        // stands. Retire rather than reset: the index is kept aside so the next join can reuse it
        // if the recipes turn out to be identical, which is the usual case going from a
        // singleplayer world to a server running the same pack.
        ProcessIndex.retire();
        // The graph holds JEI recipe objects, which a runtime reload replaces wholesale -- unlike
        // the index, it cannot be carried across.
        ProcessTreeNavigation.invalidate();
        RecipeFilter.invalidate();
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        ProcessSearchCommands.register(event.getDispatcher());
    }
}
