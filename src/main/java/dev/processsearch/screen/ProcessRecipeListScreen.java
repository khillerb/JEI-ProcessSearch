package dev.processsearch.screen;

import java.util.ArrayList;
import java.util.List;

import dev.processsearch.index.ProcessIndex;
import dev.processsearch.index.tree.Ingredients;
import dev.processsearch.index.tree.ProcessGraph;
import dev.processsearch.index.tree.ProcessNode;
import dev.processsearch.index.tree.ProcessTreeNavigation;
import dev.processsearch.index.tree.RecipeAdjacency;
import dev.processsearch.index.tree.RecipeAdjacency.RecipeRef;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * The drill-down: every recipe a machine node on the overview stands for.
 *
 * <p>Drawn as a centred panel <em>over</em> the graph rather than as a replacement for it. The graph
 * screen is kept as {@code parent} and rendered underneath, which makes this read as an overlay and
 * means Back is a single {@code setScreen} with the camera intact by construction -- there is no
 * view state to save or restore.
 *
 * <p>Rows are a compact {@code inputs -> outputs} summary, expandable to the full ingredient list.
 * Drawing a recipe properly is JEI's job, so right-clicking a row hands off to
 * {@code IRecipesGui.showRecipes}.
 *
 * <p>A row resolves its own ingredients through {@link RecipeAdjacency}, and caches them: JEI runs
 * the category's layout builder to answer, which is far too much to repeat every frame.
 */
public class ProcessRecipeListScreen extends Screen {
    private static final int PANEL_HEADER_H = 36;
    private static final int PANEL_MAX_W = 440;
    private static final int PANEL_MARGIN = 24;
    private static final int ROW_PAD = 3;
    private static final int SLOT = 18;
    private static final int ROW_H = SLOT + ROW_PAD * 2;
    private static final int MAX_COLLAPSED_INPUTS = 5;
    private static final int MAX_COLLAPSED_OUTPUTS = 3;

    private static final int PANEL_BG = 0xF01A1A1A;
    private static final int PANEL_BORDER = 0xFF6A6A6A;

    private final ProcessGraphScreen parent;
    private final ProcessGraph graph;
    private final ProcessNode process;
    private final List<Row> rows = new ArrayList<>();

    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelHeight;

    private double scroll;
    private Object hoveredIngredient;
    private Row hoveredRow;

    public ProcessRecipeListScreen(ProcessGraphScreen parent, ProcessGraph graph, ProcessNode process) {
        super(Component.literal("Process Recipes"));
        this.parent = parent;
        this.graph = graph;
        this.process = process;
        for (Object recipe : process.recipes) {
            if (recipe != null) {
                rows.add(new Row(process.category, recipe));
            }
        }
    }

    /** One recipe, with its resolved ingredients held so the layout builder runs once per row. */
    private static final class Row {
        final RecipeRef ref;
        final Object recipe;
        boolean expanded;
        int y;
        int height = ROW_H;

        private List<Object> inputs;
        private List<Object> outputs;

        Row(IRecipeCategory<?> category, Object recipe) {
            this.ref = new RecipeRef(category, recipe);
            this.recipe = recipe;
        }

        List<Object> inputs() {
            if (inputs == null) {
                inputs = resolve(false);
            }
            return inputs;
        }

        List<Object> outputs() {
            if (outputs == null) {
                outputs = resolve(true);
            }
            return outputs;
        }

        private List<Object> resolve(boolean wantOutputs) {
            RecipeAdjacency adjacency = ProcessIndex.adjacency();
            return adjacency == null ? List.of() : adjacency.displayIngredients(ref, wantOutputs);
        }
    }

    @Override
    protected void init() {
        // The backdrop is a screen that is not the active one, so nothing else will tell it the
        // window changed size.
        if (parent != null) {
            parent.resize(minecraft, width, height);
        }

        panelWidth = Math.min(PANEL_MAX_W, Math.max(220, width - PANEL_MARGIN * 2));
        // Height comes from the collapsed rows and is then held fixed: recentring the panel every
        // time a row expands would make it jump under the cursor.
        int wanted = PANEL_HEADER_H + rows.size() * ROW_H + 8;
        panelHeight = Math.min(height - PANEL_MARGIN * 2, Math.max(120, wanted));
        panelLeft = (width - panelWidth) / 2;
        panelTop = (height - panelHeight) / 2;

        addRenderableWidget(Button.builder(Component.literal("< Back"), b -> back())
                .bounds(panelLeft + 4, panelTop + PANEL_HEADER_H - 24, 56, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(panelLeft + 64, panelTop + PANEL_HEADER_H - 24, 44, 20).build());

        relayout();
        clampScroll();
    }

    private void relayout() {
        int y = 0;
        for (Row row : rows) {
            row.y = y;
            row.height = row.expanded ? expandedHeight(row) : ROW_H;
            y += row.height;
        }
    }

    private int expandedHeight(Row row) {
        int inputs = size(row.inputs());
        int outputs = size(row.outputs());
        int lines = ceil(inputs, perLine()) + ceil(outputs, perLine());
        // Two section labels, the gaps around them, and the recipe id line at the bottom.
        return ROW_H + lines * SLOT + 42;
    }

    private int perLine() {
        return Math.max(1, (panelWidth - 24) / SLOT);
    }

    private static int ceil(int value, int per) {
        return value <= 0 ? 0 : (value + per - 1) / per;
    }

    private static int size(List<?> list) {
        return list == null ? 0 : list.size();
    }

    private int listTop() {
        return panelTop + PANEL_HEADER_H;
    }

    private int listBottom() {
        return panelTop + panelHeight - 4;
    }

    private int contentHeight() {
        return rows.isEmpty() ? 0 : rows.get(rows.size() - 1).y + rows.get(rows.size() - 1).height;
    }

    // ------------------------------------------------------------ render

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        if (parent != null) {
            parent.renderBackdrop(graphics, delta);
            graphics.fill(0, 0, width, height, 0xD0000000);
        } else {
            renderBackground(graphics, mouseX, mouseY, delta);
        }

        hoveredIngredient = null;
        hoveredRow = null;

        graphics.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + panelHeight, PANEL_BG);
        drawPanelBorder(graphics);

        graphics.enableScissor(panelLeft, listTop(), panelLeft + panelWidth, listBottom());
        graphics.pose().pushPose();
        graphics.pose().translate(panelLeft, listTop() - scroll, 0);

        int localMouseX = mouseX - panelLeft;
        int localMouseY = (int) (mouseY - listTop() + scroll);
        boolean inList = mouseY >= listTop() && mouseY < listBottom()
                && mouseX >= panelLeft && mouseX < panelLeft + panelWidth;
        for (Row row : rows) {
            if (row.y + row.height < scroll || row.y > scroll + (listBottom() - listTop())) {
                continue;
            }
            boolean hover = inList && localMouseY >= row.y && localMouseY < row.y + row.height;
            if (hover) {
                hoveredRow = row;
            }
            drawRow(graphics, row, localMouseX, localMouseY, hover, delta);
        }

        graphics.pose().popPose();
        graphics.disableScissor();

        drawHeader(graphics, delta);
        drawScrollbar(graphics);
        super.render(graphics, mouseX, mouseY, delta);

        if (hoveredIngredient != null) {
            drawIngredientTooltip(graphics, hoveredIngredient, mouseX, mouseY);
        } else if (hoveredRow != null) {
            drawRowTooltip(graphics, hoveredRow, mouseX, mouseY);
        }
    }

    private void drawPanelBorder(GuiGraphics graphics) {
        int right = panelLeft + panelWidth - 1;
        int bottom = panelTop + panelHeight - 1;
        graphics.hLine(panelLeft, right, panelTop, PANEL_BORDER);
        graphics.hLine(panelLeft, right, bottom, PANEL_BORDER);
        graphics.vLine(panelLeft, panelTop, bottom, PANEL_BORDER);
        graphics.vLine(right, panelTop, bottom, PANEL_BORDER);
    }

    /** Coordinates here are panel-relative: the pose is already translated to the panel. */
    private void drawRow(GuiGraphics graphics, Row row, int mouseX, int localMouseY, boolean hover,
                         float delta) {
        int top = row.y;
        graphics.fill(4, top, panelWidth - 10, top + row.height - 1, hover ? 0x40FFFFFF : 0x30000000);

        int x = drawStacks(graphics, row.inputs(), 8, top + ROW_PAD,
                row.expanded ? Integer.MAX_VALUE : MAX_COLLAPSED_INPUTS, mouseX, localMouseY, delta);
        graphics.drawString(font, "→", x + 2, top + ROW_PAD + 5, 0xFFAAAAAA, false);
        x += 12;
        drawStacks(graphics, row.outputs(), x, top + ROW_PAD,
                row.expanded ? Integer.MAX_VALUE : MAX_COLLAPSED_OUTPUTS, mouseX, localMouseY, delta);

        if (row.expanded) {
            int y = top + ROW_H + 4;
            y = drawWrapped(graphics, "Inputs", row.inputs(), y, mouseX, localMouseY, delta);
            y = drawWrapped(graphics, "Outputs", row.outputs(), y, mouseX, localMouseY, delta);
            ResourceLocation id = safeId(row);
            if (id != null) {
                graphics.drawString(font, font.plainSubstrByWidth(id.toString(), panelWidth - 20),
                        8, y, 0xFF707070, false);
            }
        }
    }

    /** @return the x just past the last slot drawn */
    private int drawStacks(GuiGraphics graphics, List<Object> stacks, int x, int y,
                           int limit, int mouseX, int localMouseY, float delta) {
        if (stacks == null) {
            return x;
        }
        int drawn = 0;
        for (Object stack : stacks) {
            if (drawn >= limit) {
                graphics.drawString(font, "+" + (stacks.size() - drawn), x + 2, y + 5, 0xFFFFAA00, false);
                return x + 18;
            }
            if (stack == null) {
                continue;
            }
            Ingredients.render(graphics, stack, x, y);
            if (mouseX >= x && mouseX < x + 16 && localMouseY >= y && localMouseY < y + 16) {
                hoveredIngredient = stack;
            }
            x += SLOT;
            drawn++;
        }
        return x;
    }

    private int drawWrapped(GuiGraphics graphics, String label, List<Object> stacks,
                            int y, int mouseX, int localMouseY, float delta) {
        if (stacks == null || stacks.isEmpty()) {
            return y;
        }
        graphics.drawString(font, label, 8, y, 0xFF909090, false);
        y += 10;
        int perLine = perLine();
        int column = 0;
        int x = 8;
        for (Object stack : stacks) {
            if (stack == null) {
                continue;
            }
            Ingredients.render(graphics, stack, x, y);
            if (mouseX >= x && mouseX < x + 16 && localMouseY >= y && localMouseY < y + 16) {
                hoveredIngredient = stack;
            }
            x += SLOT;
            if (++column >= perLine) {
                column = 0;
                x = 8;
                y += SLOT;
            }
        }
        return y + SLOT + 2;
    }

    private void drawHeader(GuiGraphics graphics, float delta) {
        graphics.fill(panelLeft + 1, panelTop + 1, panelLeft + panelWidth - 1,
                panelTop + PANEL_HEADER_H, 0xFF141414);
        graphics.hLine(panelLeft, panelLeft + panelWidth - 1, panelTop + PANEL_HEADER_H, 0xFF404040);

        int iconX = panelLeft + panelWidth - 22;
        if (process.icon != null) {
            Ingredients.render(graphics, process.icon, iconX, panelTop + 4);
        } else {
            try {
                IDrawable icon = process.category.getIcon();
                if (icon != null) {
                    icon.draw(graphics, iconX, panelTop + 4);
                }
            } catch (RuntimeException | LinkageError e) {
                // A category whose icon needs a context we do not have here draws nothing.
            }
        }

        String title = font.plainSubstrByWidth(process.title(), panelWidth - 40);
        graphics.drawString(font, title, panelLeft + 4, panelTop + 4, 0xFFFFFFFF, false);

        String subtitle = process.recipeCount() + " recipes " + graph.direction.verb() + " "
                + parentName();
        graphics.drawString(font, font.plainSubstrByWidth(subtitle, panelWidth - 130),
                panelLeft + 112, panelTop + PANEL_HEADER_H - 18, 0xFF909090, false);
    }

    private String parentName() {
        return process.parent == null ? "this item" : process.parent.name();
    }

    private void drawScrollbar(GuiGraphics graphics) {
        int viewHeight = listBottom() - listTop();
        int content = contentHeight();
        if (content <= viewHeight) {
            return;
        }
        int barHeight = Math.max(16, viewHeight * viewHeight / content);
        int barTop = listTop() + (int) (scroll * (viewHeight - barHeight) / (content - viewHeight));
        int right = panelLeft + panelWidth - 3;
        graphics.fill(right - 4, listTop(), right, listBottom(), 0x40FFFFFF);
        graphics.fill(right - 4, barTop, right, barTop + barHeight, 0xC0FFFFFF);
    }

    private void drawIngredientTooltip(GuiGraphics graphics, Object ingredient, int mouseX,
                                       int mouseY) {
        graphics.renderComponentTooltip(font, Ingredients.tooltip(ingredient), mouseX, mouseY);
    }

    private void drawRowTooltip(GuiGraphics graphics, Row row, int mouseX, int mouseY) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(row.expanded ? "Click to collapse" : "Click to expand"));
        lines.add(Component.literal("Right-click to open in JEI")
                .withStyle(ChatFormatting.DARK_GRAY));
        graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
    }

    /**
     * A datapack recipe knows its own id; anything else is a category-generated entry, and JEI's
     * {@code getRegistryName} is the only thing that might name it.
     */
    @SuppressWarnings("unchecked")
    private static ResourceLocation safeId(Row row) {
        if (row.recipe instanceof RecipeHolder<?> holder) {
            return holder.id();
        }
        try {
            return ((IRecipeCategory<Object>) row.ref.category()).getRegistryName(row.recipe);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    // ------------------------------------------------------------ input

    private void back() {
        if (parent != null) {
            // The same screen object, so its pan and zoom are exactly where they were left.
            minecraft.setScreen(parent);
        } else {
            ProcessTreeNavigation.openGraphScreen();
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        boolean insidePanel = mouseX >= panelLeft && mouseX < panelLeft + panelWidth
                && mouseY >= panelTop && mouseY < panelTop + panelHeight;
        if (!insidePanel) {
            // Clicking the graph behind the panel dismisses it, which is the quickest way back.
            back();
            return true;
        }
        if (mouseY < listTop() || mouseY >= listBottom()) {
            return true;
        }
        int localY = (int) (mouseY - listTop() + scroll);
        for (Row row : rows) {
            if (localY >= row.y && localY < row.y + row.height) {
                if (button == 1) {
                    // Hand off to JEI rather than reimplementing a recipe layout.
                    showInJei(row);
                    return true;
                }
                row.expanded = !row.expanded;
                relayout();
                clampScroll();
                return true;
            }
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX,
                                 double scrollY) {
        double amount = scrollY;
        scroll -= amount * 24;
        clampScroll();
        return true;
    }

    private void clampScroll() {
        int max = Math.max(0, contentHeight() - (listBottom() - listTop()));
        scroll = Math.max(0, Math.min(max, scroll));
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) {
            back();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        ProcessTreeNavigation.close();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @SuppressWarnings("unchecked")
    private void showInJei(Row row) {
        IJeiRuntime runtime = ProcessIndex.runtime();
        if (runtime == null) {
            return;
        }
        try {
            runtime.getRecipesGui().showRecipes(
                    (IRecipeCategory<Object>) row.ref.category(), List.of(row.recipe), List.of());
        } catch (RuntimeException | LinkageError e) {
            // Nothing to fall back to; staying on this panel is better than a crash.
        }
    }
}
