package dev.processsearch.mixin;

import java.util.stream.Stream;

import dev.processsearch.ProcessSearchConfig;
import dev.processsearch.index.ProcessIndex;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.gui.ingredients.IngredientFilter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes a prefixed query correct even if it is typed before the index has finished building.
 *
 * <p>Normally the index fills in quietly across ticks after JEI is first opened and this never
 * fires. If someone searches sooner, finishing the build here -- synchronously, then rebuilding
 * JEI's search tree -- is the difference between a brief pause and silently returning nothing.
 */
@Mixin(IngredientFilter.class)
public class IngredientFilterMixin {
    @Inject(method = "getIngredientListUncached", at = @At("HEAD"))
    private void processsearch$ensureIndexReady(String filterText,
                                                CallbackInfoReturnable<Stream<ITypedIngredient<?>>> cir) {
        if (ProcessIndex.isReady() || ProcessIndex.isRebuilding()) {
            // isRebuilding matters: completing the build calls rebuildItemFilter, which invalidates
            // the cache, which can land straight back in this method. Without the guard that is
            // infinite recursion.
            return;
        }
        if (filterText == null || filterText.isEmpty()) {
            return;
        }
        if (!processsearch$mentionsPrefix(filterText)) {
            return;
        }
        ProcessIndex.completeNow();
    }

    @Unique
    private static boolean processsearch$mentionsPrefix(String filterText) {
        // A plain contains() is enough. The only cost of a false positive is starting a build that
        // was going to happen moments later anyway.
        for (char prefix : ProcessSearchConfig.allPrefixes()) {
            if (filterText.indexOf(prefix) >= 0) {
                return true;
            }
        }
        return false;
    }
}
