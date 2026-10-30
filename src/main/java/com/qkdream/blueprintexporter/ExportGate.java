package com.qkdream.blueprintexporter;

import net.minecraft.network.chat.Component;

/**
 * The one switch every export entry point asks before it does anything.
 *
 * <p>Fire Control Eye now carries its own copy of this exporter, and two mods that both
 * register {@code /sableexport} and both draw an export mode into the blueprint tool do not
 * merely duplicate a command - they compete for the same output folder, so a player ends up
 * with whichever copy won the registration race and no way to tell which one ran. Rather
 * than delete anything from either side, this build steps aside when the other mod is
 * present: the command, the export screen and the blueprint tool's export page all consult
 * {@link #enabled()} first, and a refused entry point writes nothing at all.
 *
 * <p>The answer is cached on the first call because the mod list cannot change while the
 * game runs; caching also makes the load-time log line a one-off by construction. That first
 * call is {@link #logState()} from client setup, so the log always says which way the gate
 * went even if nothing else ever asks.
 *
 * <p>No member here throws: a mod list that cannot be read must not take the exporter down,
 * so a failed probe falls back to "enabled" and logs why.
 */
public final class ExportGate {

    /** The other mod's id, exactly as its {@code neoforge.mods.toml} declares it. */
    public static final String RIVAL_MOD_ID = "firecontrolcompat";

    /**
     * What a refused entry point answers.
     *
     * <p>One constant, so the command, the screen and the tool page cannot drift into three
     * slightly different sentences. The colour codes are legacy {@code §}-codes on purpose:
     * they are what this mod's sibling build uses, and both {@code CommandSourceStack} and
     * {@code LocalPlayer#displayClientMessage} render them.
     */
    public static final String DISABLED_MESSAGE =
        "§c检测到「火控之眼」已安装，独立导出器已自动禁用 —— 请用 §e/sableexport region§c 导出";

    /** The same sentence without colour codes, for a label that draws its own colour. */
    public static final String DISABLED_TEXT =
        "检测到「火控之眼」已安装，独立导出器已自动禁用 —— 请用 /sableexport region 导出";

    /**
     * {@code null} until the first caller asks, then either {@code TRUE} or {@code FALSE} for
     * the rest of the session. Boxed rather than a {@code boolean} plus a separate flag so
     * "not asked yet" cannot be confused with "asked, enabled".
     */
    private static Boolean cached;

    private ExportGate() {}

    /**
     * Whether this mod may export at all.
     *
     * <p>{@code synchronized} because the first call can come from the render thread (a screen
     * button) while a command thread is asking too; the probe happens once either way.
     */
    public static synchronized boolean enabled() {
        if (cached == null) {
            cached = probe();
        }
        return cached;
    }

    /** The inverse, spelled out so a guard reads like the sentence it stands for. */
    public static synchronized boolean disabled() {
        return !enabled();
    }

    /**
     * Throws when the exporter may not write.
     *
     * <p>For the public methods deep in the export path, where there is no command source to
     * answer and returning a count would look like success. Callers already wrap the bake in
     * {@code catch (Throwable)}, so this surfaces as an ordinary failed export with the
     * reason attached.
     */
    public static void requireOpen() {
        if (disabled()) {
            throw new IllegalStateException(
                "blueprintexporter: disabled because " + RIVAL_MOD_ID + " is loaded");
        }
    }

    /** The refusal as a component, so callers do not each build their own literal. */
    public static Component message() {
        return Component.literal(DISABLED_MESSAGE);
    }

    /**
     * The refusal for a GUI label, which draws its own colour.
     *
     * <p>Same sentence as {@link #message()} minus the colour codes: a legacy {@code §}-code
     * inside a component drawn by {@code GuiGraphics#drawString} would show up as its own
     * glyph, so the screen and the tool page use this and pass the colour as an argument.
     */
    public static Component label() {
        return Component.literal(DISABLED_TEXT);
    }

    /**
     * Forces the probe and its one log line, and is safe to call more than once.
     *
     * <p>Called from client setup: the requirement is a line in the log at load time, and a
     * player who never touches the exporter would otherwise never trigger the probe.
     */
    public static void logState() {
        try {
            enabled();
        } catch (Throwable t) {
            // enabled() already swallows a failed probe; this is only belt and braces so a
            // surprise here cannot abort client setup.
            BlueprintExporter.LOGGER.warn("could not resolve the export gate: {}", t.toString());
        }
    }

    /**
     * Reads the mod list once and writes the one line that says which way the gate went.
     *
     * <p>The two messages are quoted from the requirement verbatim, because the log line is
     * what a bug report will be searched for.
     */
    private static boolean probe() {
        try {
            net.neoforged.fml.ModList list = net.neoforged.fml.ModList.get();
            if (list != null && list.isLoaded(RIVAL_MOD_ID)) {
                BlueprintExporter.LOGGER.info(
                    "blueprintexporter: disabled because firecontrolcompat is loaded");
                return false;
            }
            BlueprintExporter.LOGGER.info(
                "blueprintexporter: enabled (firecontrolcompat absent)");
            return true;
        } catch (Throwable t) {
            BlueprintExporter.LOGGER.warn(
                "could not read the mod list ({}); keeping the exporter enabled", t.toString());
            return true;
        }
    }
}
