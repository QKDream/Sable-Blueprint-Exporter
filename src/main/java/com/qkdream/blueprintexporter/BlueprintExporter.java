package com.qkdream.blueprintexporter;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sable blueprint -&gt; OBJ exporter.
 *
 * <p>The point of this mod is that it does <em>not</em> reimplement any of
 * Minecraft's model pipeline. It reads the blueprint exactly the way the game
 * does, renders it through {@code ModelBlockRenderer} exactly the way Create's
 * built-in isometric preview does, and writes the resulting triangles out. Every
 * mod's models therefore resolve the same way they do in game - including the
 * Copycats+ blocks whose blockstates are {@code minecraft:block/air} stubs and
 * whose shapes only exist at runtime.
 *
 * <p>Two ways in: the {@code /sableexport} command, and a screen opened with a
 * keybind (default {@code O}), which is where a player naturally is when they want
 * to export a blueprint.
 */
@Mod(BlueprintExporter.MOD_ID)
public final class BlueprintExporter {
    public static final String MOD_ID = "blueprintexporter";
    public static final Logger LOGGER = LoggerFactory.getLogger("blueprintexporter");

    /**
     * Things that went wrong without stopping the export.
     *
     * <p>Collected so the result reaches the player rather than only a log file. A
     * texture that could not be written used to be a warning nobody saw, and the
     * symptom - a mesh with no texture in Blender - gave no hint that the exporter had
     * failed to produce one.
     */
    public static final java.util.List<String> NOTES =
        java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    private static KeyMapping openExporter;

    public BlueprintExporter(IEventBus modEventBus) {
        // The keybind listener belongs on the MOD bus and has to be added from the
        // constructor, before the bus fires; the tick and command listeners belong
        // on the game bus.
        modEventBus.addListener(BlueprintExporter::onRegisterKeys);
        modEventBus.addListener(BlueprintExporter::onClientSetup);
        NeoForge.EVENT_BUS.addListener(BlueprintExporter::onClientTick);
        NeoForge.EVENT_BUS.addListener(BlueprintExporter::onRegisterClientCommands);
    }

    /**
     * Adds the export mode to the Sable blueprint tool.
     *
     * <p>Guarded by an explicit mod-loaded check and reached through a separate
     * class, so a pack without the blueprint tool - or without LowDragLib2, which it
     * needs - still loads this mod rather than failing on a missing class. The mod
     * therefore declares no hard dependency on either.
     */
    private static void onClientSetup(net.neoforged.fml.event.lifecycle.FMLClientSetupEvent event) {
        // Resolved once, here, so the log says which way the gate went before any other code
        // can ask it - and so a session that never opens the exporter still leaves its line.
        ExportGate.logState();
        if (!net.neoforged.fml.ModList.get().isLoaded("sable_schematic_api")) {
            LOGGER.info("blueprint tool not present; skipping the export mode");
            return;
        }
        event.enqueueWork(com.qkdream.blueprintexporter.photomancy.PhotomancyIntegration::register);
    }

    private static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        openExporter = new KeyMapping(
            "key.blueprintexporter.open",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_O,
            "key.categories.blueprintexporter");
        event.register(openExporter);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        if (openExporter == null) {
            return;
        }
        // consumeClick() is the only correct way to read a keybind: it fires once
        // per press and clears the state, so holding the key does not reopen the
        // screen every tick.
        while (openExporter.consumeClick()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && mc.screen == null) {
                mc.setScreen(new BlueprintExportScreen());
            }
        }
    }

    /**
     * Client-side command, because the bake needs baked models and they only exist
     * on the client. Registered on the game bus so a mod with no common-side code
     * still loads on a dedicated server without a class-loading failure.
     *
     * <p>Every branch is wrapped in {@link #gate} instead of calling its body directly:
     * with Fire Control Eye installed the whole command answers one sentence and writes
     * nothing, and putting the refusal at the registration site is the only shape a
     * branch added later cannot accidentally bypass.
     */
    private static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("sableexport")
                .then(Commands.literal("where")
                    .executes(ctx -> gate(ctx.getSource(), () -> {
                        BlueprintBake.reportDirs(ctx.getSource());
                        return 1;
                    })))
                .then(Commands.literal("gui")
                    .executes(ctx -> gate(ctx.getSource(), () -> {
                        Minecraft.getInstance().setScreen(new BlueprintExportScreen());
                        return 1;
                    })))
                .then(Commands.literal("all")
                    .executes(ctx -> gate(ctx.getSource(), () -> run(ctx.getSource(), null))))
                .then(Commands.argument("name", StringArgumentType.string())
                    .executes(ctx -> gate(ctx.getSource(),
                        () -> run(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))
                .executes(ctx -> gate(ctx.getSource(), () -> run(ctx.getSource(), null)))
        );
    }

    /**
     * Runs {@code body} only when {@link ExportGate} is open; otherwise answers and stops.
     *
     * <p>The body is a supplier rather than a plain value so a refused branch never even
     * evaluates its arguments: {@code /sableexport gui} must not build a screen and
     * {@code /sableexport where} must not walk the schematic folders. Nothing is written
     * on the refused path, which is the whole point - no second copy of the blueprint
     * appears in the output folder behind Fire Control Eye's back.
     */
    private static int gate(net.minecraft.commands.CommandSourceStack source,
                            java.util.function.Supplier<Integer> body) {
        if (!ExportGate.enabled()) {
            source.sendFailure(ExportGate.message());
            return 0;
        }
        return body.get();
    }

    private static int run(net.minecraft.commands.CommandSourceStack source, String name) {
        try {
            return BlueprintBake.run(source, name);
        } catch (Throwable t) {
            LOGGER.error("sableexport failed", t);
            source.sendFailure(Component.literal("sableexport failed: " + t));
            return 0;
        }
    }
}
