package net.cinchtail.cinchsmissingblocks.cmb;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cinchtail.cinchsmissingblocks.cmb.config.CmbConfig;
import net.cinchtail.cinchsmissingblocks.cmb.pack.PackDeliveryService;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

/**
 * The setup message: shown once to each player with cmb.admin, a few seconds after
 * their first join with CMB installed, and on demand with /cmb setupmsg. It says what
 * CMB found - CraftEngine, how the pack reaches players - and flags setups known to go
 * wrong (ResourcePackManager installed but unused, Nexo muting stone sounds).
 * Who has seen it is kept in plugins/CMB/data.yml.
 */
public final class SetupNotice implements Listener {

    private static final long DELAY_TICKS = 60;

    private final CmbPlugin plugin;
    private final File dataFile;
    private final Set<String> seen = new HashSet<>();

    public SetupNotice(CmbPlugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "data.yml");
        seen.addAll(YamlConfiguration.loadConfiguration(dataFile).getStringList("setup-message-seen"));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!player.hasPermission("cmb.admin") || seen.contains(player.getUniqueId().toString())) {
            return;
        }
        player.getScheduler().runDelayed(plugin, task -> {
            send(player);
            seen.add(player.getUniqueId().toString());
            save();
        }, null, DELAY_TICKS);
    }

    public void send(CommandSender to) {
        Lang lang = plugin.lang();
        CmbConfig.ResourcePack.Delivery delivery = plugin.delivery();
        Plugin craftEngine = Bukkit.getPluginManager().getPlugin("CraftEngine");
        List<String> warnings = new ArrayList<>();
        if (delivery != CmbConfig.ResourcePack.Delivery.RSPM
                && Bukkit.getPluginManager().getPlugin("ResourcePackManager") != null) {
            warnings.add(lang.raw("setup-hint-rspm-installed"));
        }
        if (nexoMutesStone()) {
            warnings.add(lang.raw("setup-hint-nexo-sounds"));
        }
        if (Bukkit.getPluginManager().getPlugin("ViaBackwards") != null) {
            warnings.add(lang.raw("setup-hint-viabackwards"));
        }
        Map<String, String> placeholders = Map.of(
                "ce_version", craftEngine == null ? "?" : craftEngine.getPluginMeta().getVersion(),
                "delivery", delivery == null ? "?" : PackDeliveryService.describe(delivery),
                "delivery_note", lang.raw(delivery == CmbConfig.ResourcePack.Delivery.RSPM
                        ? "setup-delivery-rspm" : "setup-delivery-craftengine"),
                "warnings", String.join("\n", warnings));
        lang.lines("setup-message", placeholders).forEach(to::sendMessage);
    }

    /**
     * Nexo's furniture sounds mute vanilla stone (block.stone.* -> nothing) and rely
     * on its custom block sounds to play them back; with those off, stone is silent.
     */
    private boolean nexoMutesStone() {
        File mechanics = new File(plugin.getDataFolder().getParentFile(), "Nexo/mechanics.yml");
        if (!mechanics.isFile()) {
            return false;
        }
        YamlConfiguration nexo = YamlConfiguration.loadConfiguration(mechanics);
        return nexo.getBoolean("furniture.custom_block_sounds", false)
                && !nexo.getBoolean("custom_blocks.custom_block_sounds.enabled", false);
    }

    private void save() {
        YamlConfiguration data = YamlConfiguration.loadConfiguration(dataFile);
        data.set("setup-message-seen", new ArrayList<>(seen));
        try {
            data.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save data.yml: " + e.getMessage());
        }
    }
}
