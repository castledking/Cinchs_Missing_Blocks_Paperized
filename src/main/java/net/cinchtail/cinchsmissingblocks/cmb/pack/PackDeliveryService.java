package net.cinchtail.cinchsmissingblocks.cmb.pack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.cinchtail.cinchsmissingblocks.cmb.config.CmbConfig;
import net.cinchtail.cinchsmissingblocks.cmb.scheduler.Schedulers;
import net.momirealms.craftengine.bukkit.api.event.AsyncResourcePackGenerateEvent;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

/**
 * Decides who sends the resource pack, and makes that decision actually hold.
 *
 * <p>Exactly one plugin may send a pack to players. Two owners means two
 * {@code setResourcePack} calls and the client keeps the last one, so the other pack's
 * models silently vanish. CraftEngine owns sending by default; when RSPM is installed
 * and configured to deliver, CraftEngine must be told to keep building but stop sending.
 *
 * <p>That is what {@link #ensureCraftEngineDoesNotSend()} does, via CraftEngine's own
 * {@code resource-pack.delivery.send-on-join}. The flag gates only the send: the build
 * workflow is separate ({@code resource-pack.workflows} in CraftEngine's config), so the
 * zip is still produced. Blocks are unaffected either way - they are server-side registry
 * entries with server-side collision and loot, and a pack only decides how they look.
 *
 * <p>Timing. The copy must not race the build. {@code /ce reload all} returns long before
 * the zip exists: CraftEngine reloads packs, fires {@code CraftEngineReloadEvent}, and only
 * then runs the generate/validate/zip workflow. So {@code CraftEngineReloadEvent} is the
 * wrong signal, and copying on it would capture the previous build or a half-written file.
 * {@link AsyncResourcePackGenerateEvent} is the right one - CraftEngine fires it from inside
 * the zip step, immediately after {@code writePack} returns, and hands over the finished
 * path. It is async, hence the hop back to the main thread.
 */
public final class PackDeliveryService implements Listener {

    private static final String CE_PLUGIN = "CraftEngine";
    private static final String RSPM_PLUGIN = "ResourcePackManager";

    /** The filename CMB's pack takes inside RSPM's mixer, so a rebuild overwrites cleanly. */
    private static final String MIXER_FILENAME = "cmb.zip";

    /**
     * Matches the one setting, capturing its indentation and any trailing comment so a
     * rewrite preserves both. Anchored on the key rather than the value so a server that
     * has set it to either boolean is still found.
     */
    private static final Pattern SEND_ON_JOIN =
            Pattern.compile("^(\\s*)([#-]?\\s*send-on-join\\s*:\\s*)([^\\r\\n#]*)(\\s*(#.*)?)$");

    private final Plugin plugin;
    private final boolean rspmInstalled;

    /** Guards against re-entering the copy when a reload is triggered by our own command. */
    private final AtomicBoolean delivering = new AtomicBoolean();

    public PackDeliveryService(Plugin plugin) {
        this.plugin = plugin;
        this.rspmInstalled = plugin.getServer().getPluginManager().getPlugin(RSPM_PLUGIN) != null;
    }

    public boolean craftEngineInstalled() {
        return plugin.getServer().getPluginManager().getPlugin(CE_PLUGIN) != null;
    }

    public boolean rspmInstalled() {
        return rspmInstalled;
    }

    /**
     * Reconciles the configured delivery mode with what is installed.
     *
     * <p>Never silently overrides an admin who asked for RSPM: if it is configured but
     * absent, that is a warning and a fall back to CraftEngine, because the alternative is
     * players receiving no pack and every custom block rendering as its carrier.
     *
     * @param config the reloaded config
     * @return the mode that will actually be used
     */
    public CmbConfig.ResourcePack.Delivery resolve(CmbConfig config) {
        CmbConfig.ResourcePack.Delivery configured = config.resourcePack().delivery();

        if (configured == CmbConfig.ResourcePack.Delivery.RSPM) {
            if (rspmInstalled) {
                return CmbConfig.ResourcePack.Delivery.RSPM;
            }
            plugin
                    .getLogger()
                    .warning(
                            "resource-pack.delivery is 'rspm' but ResourcePackManager is not "
                                    + "installed; falling back to CraftEngine so players still "
                                    + "receive a pack.");
            return CmbConfig.ResourcePack.Delivery.CRAFTENGINE;
        }

        // Configured for CraftEngine, but RSPM is present. Do not switch behind the admin's
        // back - just point out that the option exists.
        if (rspmInstalled) {
            plugin
                    .getLogger()
                    .info(
                            "ResourcePackManager is installed. To have it merge the CMB pack "
                                    + "instead of CraftEngine sending it, set "
                                    + "resource-pack.delivery: rspm in CMB's config.yml and "
                                    + "restart; CMB will then stop CraftEngine sending and "
                                    + "hand it to RSPM.");
        }
        return CmbConfig.ResourcePack.Delivery.CRAFTENGINE;
    }

    /**
     * Stops CraftEngine sending the pack, leaving the build alone.
     *
     * <p>Edited as text rather than through a YAML round trip on purpose. Loading and
     * re-saving CraftEngine's config through a YAML library would drop every comment in it,
     * and that file is the reference for its own options.
     *
     * @return true if the setting is now false
     */
    public boolean ensureCraftEngineDoesNotSend() {
        Path config = craftEngineConfigPath();
        if (config == null || !Files.isRegularFile(config)) {
            plugin.getLogger().warning("CraftEngine config.yml not found; cannot stop it sending.");
            return false;
        }
        try {
            String original = Files.readString(config, StandardCharsets.UTF_8);
            StringBuilder out = new StringBuilder(original.length());
            boolean changed = false;
            Matcher matcher = SEND_ON_JOIN.matcher(original);
            int cursor = 0;
            while (matcher.find()) {
                out.append(original, cursor, matcher.start());
                String value = matcher.group(3).trim();
                if (!"false".equalsIgnoreCase(value)) {
                    out.append(matcher.group(1))
                            .append(matcher.group(2))
                            .append("false");
                    if (matcher.group(4) != null && !matcher.group(4).isBlank()) {
                        out.append(matcher.group(4));
                    }
                    changed = true;
                } else {
                    out.append(matcher.group());
                }
                cursor = matcher.end();
            }
            out.append(original.substring(cursor));

            if (changed) {
                Files.writeString(config, out.toString(), StandardCharsets.UTF_8);
                plugin
                        .getLogger()
                        .info(
                                "Set CraftEngine resource-pack.delivery.send-on-join: false - "
                                        + "RSPM now owns delivery. Restart CraftEngine for it to "
                                        + "take effect.");
            }
            return true;
        } catch (IOException e) {
            plugin
                    .getLogger()
                    .warning(
                            "Could not update CraftEngine's send-on-join: "
                                    + e.getMessage()
                                    + " - set it to false by hand, or players will be sent two packs.");
            return false;
        }
    }

    /**
     * Copies the freshly built pack into RSPM's mixer and asks RSPM to remix.
     *
     * <p>Fire-and-forget plus a guarded re-entry: {@code /rspm reload} re-reads the mixer
     * directory, and if that reload were ever to re-enter this handler a synchronous
     * dispatch would recurse.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPackGenerated(AsyncResourcePackGenerateEvent event) {
        if (!delivering.compareAndSet(false, true)) {
            return;
        }
        try {
            // The event is async; the mixer copy and the command both belong on the global
            // region thread, which is what console commands run on under Folia.
            Schedulers.global(
                    plugin,
                    () -> {
                        try {
                            copyToMixer(event.zipFilePath());
                        } catch (IOException e) {
                            plugin
                                    .getLogger()
                                    .warning("Could not hand the pack to RSPM: " + e.getMessage());
                        } finally {
                            delivering.set(false);
                        }
                    });
        } catch (Throwable t) {
            delivering.set(false);
            plugin.getLogger().warning("Could not schedule RSPM delivery: " + t.getMessage());
        }
    }

    private void copyToMixer(Path zip) throws IOException {
        if (zip == null || !Files.isRegularFile(zip)) {
            plugin.getLogger().warning("CraftEngine reported no generated pack at " + zip + ".");
            return;
        }
        Path mixer = mixerDirectory();
        Files.createDirectories(mixer);
        Path target = mixer.resolve(MIXER_FILENAME);
        long size = Files.size(zip);
        // REPLACE_EXISTING and a stable name: RSPM merges by filename, so a changing name
        // would accumulate every previous build in its mixer.
        Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
        plugin
                .getLogger()
                .info("Copied CraftEngine pack (" + size + " bytes) to " + target + ".");

        boolean dispatched =
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "rspm reload");
        if (!dispatched) {
            plugin
                    .getLogger()
                    .warning(
                            "Ran /rspm reload but the command was not recognised; run it by hand "
                                    + "or the merged pack is now one build behind.");
        }
    }

    private Path craftEngineConfigPath() {
        Path plugins = plugin.getDataFolder().toPath().getParent();
        return plugins == null ? null : plugins.resolve(CE_PLUGIN).resolve("config.yml");
    }

    private Path mixerDirectory() {
        Path plugins = plugin.getDataFolder().toPath().getParent();
        return plugins.resolve(RSPM_PLUGIN).resolve("mixer");
    }

    /** Human-readable mode name for logs. */
    public static String describe(CmbConfig.ResourcePack.Delivery delivery) {
        return delivery.name().toLowerCase(Locale.ROOT);
    }

    static Path resolveMixer(Path pluginsDir) {
        return pluginsDir.resolve(RSPM_PLUGIN).resolve("mixer");
    }

    static String mixerFilename() {
        return MIXER_FILENAME;
    }

    static Path pluginsDirectoryOf(Plugin plugin) {
        return Paths.get(plugin.getDataFolder().toPath().getParent().toString());
    }
}