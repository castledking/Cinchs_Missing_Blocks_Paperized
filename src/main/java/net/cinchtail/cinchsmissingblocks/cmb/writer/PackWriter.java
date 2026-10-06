package net.cinchtail.cinchsmissingblocks.cmb.writer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.bukkit.plugin.Plugin;

/**
 * Deliberately writes nothing.
 *
 * <p>This class used to copy a set of YAML files bundled in the plugin jar over the
 * deployed pack on every reload. That was wrong in a way that was invisible until it
 * bit: the jar's copies were a snapshot, so any pack regeneration that the release
 * pipeline had performed was silently reverted by {@code /cmb reload all}. The symptom
 * was a fix that appeared to do nothing - the corrected file was written, then
 * overwritten with the old one from the jar.
 *
 * <p>So the deploy step stops here. The pack generator owns the pack's contents; the
 * plugin owns runtime allocation and the behaviours CraftEngine cannot supply. If the
 * two ever need to be unified, the writer has to render from the bundled
 * {@code content.json} rather than copy a snapshot, and that is a real piece of work
 * rather than a copy loop.
 */
public final class PackWriter {

    private final Plugin plugin;

    public PackWriter(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Verifies the deployed pack is present and reports what is there.
     *
     * <p>Read-only on purpose. An earlier version of this method overwrote the
     * deployed pack from jar-bundled copies; see the class comment.
     *
     * @return whether the deployed pack directory exists
     * @throws IOException if the directory cannot be inspected
     */
    public boolean verifyPack() throws IOException {
        Path deployed = deployedPackRoot();
        if (!Files.isDirectory(deployed)) {
            plugin
                    .getLogger()
                    .warning(
                        "No CraftEngine pack at "
                            + deployed
                            + " - the bundled pack is installed there on startup unless "
                            + "resource-pack.install is false.");
            return false;
        }
        Path configuration = deployed.resolve("configuration");
        if (!Files.isDirectory(configuration)) {
            plugin
                    .getLogger()
                    .warning(
                        "Pack at "
                            + deployed
                            + " has no configuration/ directory; it will load no blocks.");
            return false;
        }
        plugin.getLogger().info("Found CraftEngine pack at " + deployed);
        return true;
    }

    private Path deployedPackRoot() {
        return Paths.get(
                plugin.getDataFolder().getParent(), "CraftEngine", "resources", "cinchsmissingblocks");
    }
}