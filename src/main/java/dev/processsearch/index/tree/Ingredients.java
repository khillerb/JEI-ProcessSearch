package dev.processsearch.index.tree;

import java.util.List;

import dev.processsearch.index.ProcessIndex;
import mezz.jei.api.ingredients.IIngredientRenderer;
import mezz.jei.api.runtime.IIngredientManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Turning an index key back into something drawable.
 *
 * <p>The index and the graph both key on registry singletons, which is what keeps them small but
 * leaves nothing to render. This rebuilds a display stack from a key and hands the drawing to JEI,
 * whose renderers already know how to draw a fluid, an energy unit or whatever else a mod has
 * registered as an ingredient type.
 *
 * <p>Everything here fails soft. A modded ingredient renderer that throws should cost one blank
 * square, not the whole screen.
 */
public final class Ingredients {
    private static final int BUCKET = 1000;

    private Ingredients() {}

    /** @return an {@code ItemStack} or {@code FluidStack} for a key, or null if it is neither. */
    public static Object display(Object key) {
        if (key instanceof Item item) {
            return new ItemStack(item);
        }
        if (key instanceof Fluid fluid) {
            return new FluidStack(fluid, BUCKET);
        }
        return null;
    }

    public static void render(GuiGraphics graphics, Object display, int x, int y) {
        if (display == null) {
            return;
        }
        // The common case by a wide margin, and vanilla draws it without going through JEI at all.
        if (display instanceof ItemStack stack) {
            graphics.renderItem(stack, x, y);
            graphics.renderItemDecorations(Minecraft.getInstance().font, stack, x, y);
            return;
        }
        IIngredientRenderer<Object> renderer = rendererFor(display);
        if (renderer == null) {
            return;
        }
        try {
            renderer.render(graphics, display, x, y);
        } catch (RuntimeException | LinkageError e) {
            // A modded renderer that throws costs one blank square.
        }
    }

    public static String name(Object display) {
        if (display == null) {
            return "?";
        }
        if (display instanceof ItemStack stack) {
            return stack.getHoverName().getString();
        }
        if (display instanceof FluidStack fluid) {
            return fluid.getHoverName().getString();
        }
        List<Component> tooltip = tooltip(display);
        return tooltip.isEmpty() ? String.valueOf(display) : tooltip.get(0).getString();
    }

    public static List<Component> tooltip(Object display) {
        if (display == null) {
            return List.of();
        }
        if (display instanceof ItemStack stack) {
            try {
                return stack.getTooltipLines(Item.TooltipContext.EMPTY,
                        Minecraft.getInstance().player, tooltipFlag());
            } catch (RuntimeException | LinkageError e) {
                return List.of(stack.getHoverName());
            }
        }
        IIngredientRenderer<Object> renderer = rendererFor(display);
        if (renderer == null) {
            return List.of();
        }
        try {
            return renderer.getTooltip(display, tooltipFlag());
        } catch (RuntimeException | LinkageError e) {
            return List.of();
        }
    }

    private static TooltipFlag tooltipFlag() {
        return Minecraft.getInstance().options.advancedItemTooltips
                ? TooltipFlag.ADVANCED
                : TooltipFlag.NORMAL;
    }

    @SuppressWarnings("unchecked")
    private static IIngredientRenderer<Object> rendererFor(Object display) {
        IIngredientManager manager = ProcessIndex.ingredientManager();
        if (manager == null) {
            return null;
        }
        try {
            return (IIngredientRenderer<Object>) manager.getIngredientRenderer(display);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }
}
