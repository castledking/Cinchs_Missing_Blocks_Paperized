package net.cinchtail.cinchsmissingblocks.cmb;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.api.event.FurnitureBreakEvent;
import net.momirealms.craftengine.bukkit.api.event.FurnitureHitEvent;
import net.momirealms.craftengine.bukkit.item.BukkitItemDefinition;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Hit-to-break for the CMB furniture pieces: several punches instead of one.
 *
 * <p>Furniture can't be mined like a block. A client sends mining progress only for
 * blocks, and for an entity it sends one attack per click, with nothing for a held
 * button. Its cell is air to the client, so there are no crack overlays either. What
 * it does send is every click, so a piece takes a number of hits worked out the way
 * vanilla works out mining time:
 *
 * <pre>
 *   per tick = tool speed / hardness / (correct tool ? 30 : 100)
 *   hits     = ceil(ticks to break / TICKS_PER_HIT), between 1 and MAX_HITS
 * </pre>
 *
 * <p>Tool speed and the correct-tool rule come from a vanilla reference block: the
 * vanilla slab itself for a vanilla material, and a block mined with the same tool
 * for a CMB one (generate_pack writes this to the pack's intermediate/mining.json).
 * Efficiency counts. As in vanilla, a piece that needs a tool breaks without one but
 * drops nothing. Every hit shows crumbs of the piece and plays its hit sound.
 *
 * <p>CraftEngine fires FurnitureHitEvent on each hit and, with {@code hit_times: 1},
 * breaks on any hit it lets through. So every hit short of the count is cancelled,
 * and the last one goes on to CraftEngine's own break, after its anti-grief check.
 */
public final class FurnitureMining implements Listener {

    /** Roughly how long one hit stands in for, in mining ticks. */
    private static final int TICKS_PER_HIT = 6;
    private static final int MAX_HITS = 20;
    /** Hits closer together than this don't count, so an autoclicker isn't instant. */
    private static final int MIN_TICKS_BETWEEN_HITS = 3;
    /** Progress resets after this long without a hit, as vanilla mining does. */
    private static final int IDLE_RESET_TICKS = 40;

    record Spec(BlockData reference, float hardness) {}

    record Progress(UUID furniture, int hits, int lastTick) {}

    private final CmbPlugin plugin;
    private final Map<UUID, Progress> progress = new ConcurrentHashMap<>();
    private volatile Map<String, Spec> specs = Map.of();
    private volatile long loadedModified = Long.MIN_VALUE;

    public FurnitureMining(CmbPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onHit(FurnitureHitEvent event) {
        Player player = event.player();
        if (player.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        Spec spec = spec(event.furniture().id());
        if (spec == null) {
            return;
        }
        int now = Bukkit.getCurrentTick();
        UUID target = event.furniture().uuid();
        Progress previous = progress.get(player.getUniqueId());
        boolean continuing = previous != null && previous.furniture().equals(target)
                && now - previous.lastTick() <= IDLE_RESET_TICKS;
        if (continuing && now - previous.lastTick() < MIN_TICKS_BETWEEN_HITS) {
            event.setCancelled(true);
            return;
        }
        int hits = continuing ? previous.hits() + 1 : 1;
        if (hits < hitsNeeded(spec, player.getInventory().getItemInMainHand())) {
            progress.put(player.getUniqueId(), new Progress(target, hits, now));
            event.setCancelled(true);
            feedback(event, spec);
            return;
        }
        // The last hit: CraftEngine breaks the piece.
        progress.remove(player.getUniqueId());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBreak(FurnitureBreakEvent event) {
        Player player = event.player();
        if (player.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        Spec spec = spec(event.furniture().id());
        if (spec != null && !correctTool(spec, player.getInventory().getItemInMainHand())) {
            event.setDropItems(false);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        progress.remove(event.getPlayer().getUniqueId());
    }

    static int hitsNeeded(Spec spec, ItemStack tool) {
        if (spec.hardness() <= 0) {
            return 1;
        }
        float speed = spec.reference().getDestroySpeed(tool, true);
        double perTick = speed / spec.hardness() / (correctTool(spec, tool) ? 30.0 : 100.0);
        int ticks = (int) Math.ceil(1.0 / perTick);
        return Math.clamp((long) Math.ceil(ticks / (double) TICKS_PER_HIT), 1, MAX_HITS);
    }

    static boolean correctTool(Spec spec, ItemStack tool) {
        return !spec.reference().requiresCorrectToolForDrops()
                || spec.reference().isPreferredTool(tool);
    }

    private void feedback(FurnitureHitEvent event, Spec spec) {
        Location at = event.hitPoint();
        ItemStack crumbs = crumbItem(event.furniture().id());
        if (crumbs != null) {
            at.getWorld().spawnParticle(Particle.ITEM, at, 6, 0.1, 0.1, 0.1, 0.05, crumbs);
        }
        at.getWorld().playSound(at, spec.reference().getSoundGroup().getHitSound(),
                SoundCategory.BLOCKS, 0.5f, 0.75f);
    }

    /** The piece's own item, so the crumbs are its texture; a double shows its slab's. */
    private static ItemStack crumbItem(Key furniture) {
        String value = furniture.value();
        if (value.endsWith(VerticalSlabListener.DOUBLE_SUFFIX)) {
            value = value.substring(0, value.length() - VerticalSlabListener.DOUBLE_SUFFIX.length());
        }
        BukkitItemDefinition item = CraftEngineItems.byId(Key.of(furniture.namespace(), value));
        return item == null ? null : item.buildBukkitItem();
    }

    // --- mining.json --------------------------------------------------------

    private Spec spec(Key furniture) {
        if (furniture == null || !VerticalSlabListener.NAMESPACE.equals(furniture.namespace())) {
            return null;
        }
        reloadIfChanged();
        return specs.get(furniture.asString());
    }

    private Path file() {
        return Paths.get(plugin.getDataFolder().getParent(), "CraftEngine", "resources",
                "cinchsmissingblocks", "intermediate", "mining.json");
    }

    /**
     * Re-read when the file changes, so a freshly rsynced pack takes effect with
     * CraftEngine's own reload and no plugin reload. A missing file means no piece has
     * mining data, and every piece breaks in one hit as before.
     */
    private synchronized void reloadIfChanged() {
        Path file = file();
        long modified;
        try {
            modified = Files.isRegularFile(file) ? Files.getLastModifiedTime(file).toMillis() : -1;
        } catch (IOException e) {
            modified = -1;
        }
        if (modified == loadedModified) {
            return;
        }
        loadedModified = modified;
        Map<String, Spec> loaded = new HashMap<>();
        if (modified >= 0) {
            try {
                JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                    JsonObject value = entry.getValue().getAsJsonObject();
                    Material reference = Material.matchMaterial(value.get("reference").getAsString());
                    if (reference == null || !reference.isBlock()) {
                        continue;
                    }
                    loaded.put(entry.getKey(), new Spec(reference.createBlockData(),
                            value.get("hardness").getAsFloat()));
                }
            } catch (IOException | RuntimeException e) {
                plugin.getLogger().warning("Could not read " + file + ": " + e);
            }
        }
        specs = Map.copyOf(loaded);
    }
}
