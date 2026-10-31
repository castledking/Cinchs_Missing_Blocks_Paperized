package net.cinchtail.cinchsmissingblocks.cmb;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Reminds the console on startup, and players with {@code cmb.admin} on join, to run
 * {@code /cmb reload all} after CMB is updated.
 *
 * <p>CMB installs its bundled pack into CraftEngine during {@code onLoad()}, before
 * CraftEngine reads its configuration, so server-side definitions are current immediately.
 * CraftEngine's resource-pack workflow, however, only rebuilds {@code resource_pack.zip}
 * on reload - a plain restart keeps serving the previous zip, and dispatching
 * {@code ce reload all} during startup races CraftEngine's own enable phase. The reminder
 * therefore persists across restarts in {@code plugins/CMB/data.yml} until
 * {@code /cmb reload all} succeeds.
 */
public final class UpdateNotice implements Listener {

    private static final long DELAY_TICKS = 60;
    private static final String KEY_LAST_VERSION = "last-version";
    private static final String KEY_RELOAD_NEEDED = "reload-needed";
    private static final String KEY_UPDATED_FROM = "updated-from";

    record State(boolean reloadNeeded, String updatedFrom) {}

    private final CmbPlugin plugin;
    private final File dataFile;
    private final String currentVersion;
    private final Consumer<String> warn;
    private volatile State state;

    public UpdateNotice(CmbPlugin plugin, boolean hadExistingInstall) {
        this(plugin, new File(plugin.getDataFolder(), "data.yml"),
                plugin.getPluginMeta().getVersion(), hadExistingInstall,
                msg -> plugin.getLogger().warning(msg));
    }

    UpdateNotice(CmbPlugin plugin, File dataFile, String currentVersion,
                 boolean hadExistingInstall, Consumer<String> warn) {
        this.plugin = plugin;
        this.dataFile = dataFile;
        this.currentVersion = currentVersion;
        this.warn = warn;
        this.state = loadAndRecord(hadExistingInstall);
    }

    /**
     * Checked at the start of {@code onLoad()}, before {@code ConfigLoader} writes a
     * default {@code config.yml}: true when CMB was already installed on this server,
     * including releases (1.0.4 and earlier) that predated {@code last-version} in
     * {@code data.yml}.
     */
    static boolean hasExistingInstall(File dataFolder) {
        return new File(dataFolder, "data.yml").isFile()
                || new File(dataFolder, "config.yml").isFile()
                || new File(dataFolder, "lang.yml").isFile();
    }

    private State loadAndRecord(boolean hadExistingInstall) {
        synchronized (SetupNotice.DATA_LOCK) {
            YamlConfiguration data = YamlConfiguration.loadConfiguration(dataFile);
            String lastVersion = trimToNull(data.getString(KEY_LAST_VERSION));
            boolean wasReloadNeeded = data.getBoolean(KEY_RELOAD_NEEDED, false);
            String previousFrom = trimToNull(data.getString(KEY_UPDATED_FROM));

            State resolved;
            boolean dirty;
            if (lastVersion == null) {
                // First boot with version tracking: a fresh install needs no update
                // reminder (SetupNotice covers initial setup); an upgrade from 1.0.4 or
                // earlier does.
                resolved = new State(hadExistingInstall, null);
                dirty = true;
            } else if (!lastVersion.equals(currentVersion)) {
                String from = wasReloadNeeded && previousFrom != null ? previousFrom : lastVersion;
                resolved = new State(true, from);
                dirty = true;
            } else {
                resolved = new State(wasReloadNeeded, wasReloadNeeded ? previousFrom : null);
                dirty = false;
            }

            if (dirty) {
                write(data, resolved);
            }
            return resolved;
        }
    }

    public boolean isReloadNeeded() {
        return state.reloadNeeded();
    }

    String updatedFrom() {
        return state.updatedFrom();
    }

    /** Clears the pending reminder once {@code /cmb reload all} has triggered a rebuild. */
    public void markReloaded() {
        synchronized (SetupNotice.DATA_LOCK) {
            if (!state.reloadNeeded()) {
                return;
            }
            State cleared = new State(false, null);
            state = cleared;
            YamlConfiguration data = YamlConfiguration.loadConfiguration(dataFile);
            write(data, cleared);
        }
    }

    /** Prints the reminder to the console at the end of startup when a reload is pending. */
    public void remindConsole() {
        if (state.reloadNeeded()) {
            send(Bukkit.getConsoleSender());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!state.reloadNeeded()) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.hasPermission("cmb.admin")) {
            return;
        }
        player.getScheduler().runDelayed(plugin, task -> {
            if (state.reloadNeeded() && player.hasPermission("cmb.admin")) {
                send(player);
            }
        }, null, DELAY_TICKS);
    }

    public void send(CommandSender to) {
        send(to, plugin.lang(), plugin.cmbConfig().resourcePack().install());
    }

    void send(CommandSender to, Lang lang, boolean packInstallEnabled) {
        State current = state;
        String versionChange = current.updatedFrom() != null
                ? lang.raw("updated-from-to", Map.of(
                        "previous", current.updatedFrom(),
                        "current", currentVersion))
                : lang.raw("updated-to", Map.of("current", currentVersion));
        String packNote = lang.raw(packInstallEnabled
                ? "updated-pack-installed" : "updated-pack-manual");
        List<Component> lines = lang.lines("updated-message", Map.of(
                "version_change", versionChange,
                "current", currentVersion,
                "previous", current.updatedFrom() == null ? "" : current.updatedFrom(),
                "pack_note", packNote));
        boolean clickable = to instanceof Player;
        for (Component line : lines) {
            if (clickable && PlainTextComponentSerializer.plainText().serialize(line)
                    .contains("/cmb reload all")) {
                line = line.clickEvent(ClickEvent.suggestCommand("/cmb reload all"));
            }
            to.sendMessage(line);
        }
    }

    private void write(YamlConfiguration data, State next) {
        data.set(KEY_LAST_VERSION, currentVersion);
        data.set(KEY_RELOAD_NEEDED, next.reloadNeeded());
        data.set(KEY_UPDATED_FROM, next.updatedFrom());
        try {
            data.save(dataFile);
        } catch (IOException e) {
            warn.accept("Could not save data.yml: " + e.getMessage());
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
