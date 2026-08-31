package dev.processsearch.mixin;

import dev.processsearch.recipe.RecipeFilter;
import mezz.jei.common.util.ImmutableRect2i;
import mezz.jei.gui.recipes.RecipesGui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Says out loud when the recipe list has been narrowed.
 *
 * <p>Pages silently disappearing because of text left in the search box is exactly the kind of
 * thing that reads as a broken mod. One line of grey text removes the mystery.
 */
@Mixin(RecipesGui.class)
public class RecipesGuiMixin {
    @Inject(method = "render", at = @At("TAIL"))
    private void processsearch$drawFilterNotice(GuiGraphics graphics, int mouseX, int mouseY,
                                                float partialTicks, CallbackInfo ci) {
        if (!RecipeFilter.isActive()) {
            return;
        }
        RecipesGui self = (RecipesGui) (Object) this;
        ImmutableRect2i area;
        try {
            area = self.getArea();
        } catch (RuntimeException e) {
            return;
        }
        if (area == null) {
            return;
        }
        String text = "filtered: " + RecipeFilter.lastKept() + " of " + RecipeFilter.lastTotal();
        Minecraft mc = Minecraft.getInstance();
        graphics.drawString(mc.font, text, area.getX() + 5, area.getY() + area.getHeight() - 11,
                0xFF909090, false);
    }
}
