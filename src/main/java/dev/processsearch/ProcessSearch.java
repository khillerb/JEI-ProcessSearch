package dev.processsearch;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * Process Search adds four prefixes to JEI's filter box:
 *
 * <pre>
 *   &gt;process[/property]   what MAKES this item
 *   &lt;process[/property]   what CONSUMES it
 *   *process              which MACHINE runs the process
 *   ~class                what KIND of item this is
 * </pre>
 *
 * <p>Facets are recipe categories ({@code >mixing}), the machines that run them
 * ({@code >mechanical_mixer}), and recipe-level properties that are not categories at all --
 * notably Create's heat requirement, so {@code >mixing/heat.heated} answers a question category
 * browsing cannot.
 *
 * <p>It also adds a process tree, opened with {@code <} or {@code >} over a hovered item: a pan and
 * zoom graph of alternating item and machine nodes for following a chain rather than a single step.
 *
 * <p>Client-only. It indexes recipes JEI already has and never talks to the server.
 */
@Mod(value = ProcessSearch.MOD_ID, dist = Dist.CLIENT)
public final class ProcessSearch {
    public static final String MOD_ID = "processsearch";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ProcessSearch(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, ProcessSearchConfig.SPEC);
        // NeoForge generates the whole screen from the spec, so every comment in
        // ProcessSearchConfig becomes a tooltip and every defineInRange becomes a slider.
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }
}
