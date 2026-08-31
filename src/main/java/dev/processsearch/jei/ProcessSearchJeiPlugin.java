package dev.processsearch.jei;

import dev.processsearch.ProcessSearch;
import dev.processsearch.index.ProcessIndex;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.ResourceLocation;

/**
 * The handoff point. {@code onRuntimeAvailable} is the first moment the recipe manager exists, so
 * it is where the index gets its source -- but the build itself waits until JEI is actually opened,
 * so a player who never searches by machine never pays for it.
 */
@JeiPlugin
public class ProcessSearchJeiPlugin implements IModPlugin {
    private static final ResourceLocation UID =
            ResourceLocation.fromNamespaceAndPath(ProcessSearch.MOD_ID, "jei_plugin");

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        ProcessIndex.onRuntimeAvailable(jeiRuntime);
    }

    @Override
    public void onRuntimeUnavailable() {
        // A resource reload rebuilds every recipe, so the old index is not just stale, it points at
        // recipe objects that no longer exist.
        ProcessIndex.onRuntimeUnavailable();
    }
}
