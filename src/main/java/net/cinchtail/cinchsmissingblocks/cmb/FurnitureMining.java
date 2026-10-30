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
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.api.event.FurnitureBreakEvent;
import net.momirealms.craftengine.bukkit.api.event.FurnitureHitEvent;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.bukkit.item.BukkitItemDefinition;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Tool-sensitive mining progress for furniture-backed CMB pieces.
 *
 * <p>Furniture can't be mined like a block. A client sends mining progress only for
 * blocks, and for an entity it sends one attack per click, with nothing for a held
 * button. Each attack contributes elapsed mining time, with a bounded allowance for
 * the interval between clicks. Crack displays follow the furniture's own geometry.
 *
 * <p>Tool speed and the correct-tool rule come from a vanilla reference block: the
 * vanilla slab itself for a vanilla material, and a block mined with the same tool
 * for a CMB one (generate_pack writes this to the pack's intermediate/mining.json).
 * The native player calculation includes Efficiency, effects, attributes, being
 * airborne and underwater. As in vanilla, a piece that needs a tool breaks without one but
 * drops nothing. Every hit shows crumbs of the piece and plays its hit sound.
 *
 * <p>CraftEngine fires FurnitureHitEvent on each hit and, with {@code hit_times: 1},
 * breaks on any hit it lets through. So every hit short of the count is cancelled,
 * and the last one goes on to CraftEngine's own break, after its anti-grief check.
 */
public final class FurnitureMining implements Listener {

    /** Progress resets after this long without a hit, as vanilla mining does. */
    private static final int IDLE_RESET_TICKS = 40;

    record Spec(BlockData reference, float hardness) {}

    record Progress(UUID furniture, double amount, int lastTick, ItemStack tool) {}

    private final CmbPlugin plugin;
    private final FurnitureCracks cracks;
    private final Map<UUID, Progress> progress = new ConcurrentHashMap<>();
    private volatile Map<String, Spec> specs = Map.of();
    private volatile long loadedModified = Long.MIN_VALUE;

    public FurnitureMining(CmbPlugin plugin) {
        this.plugin = plugin;
        this.cracks = new FurnitureCracks(plugin);
    }

    /**
     * A punch on a piece is a mining hit, not an attack. CraftEngine handles the hit at
     * packet level, then passes the attack on to vanilla aimed at the piece's base display
     * entity, and vanilla runs a full player attack on it: the swing's attack sound
     * (entity.player.attack.knockback and kin) plays over the block's own hit sound. A
     * block, which is what the piece stands in for, makes no such sound. The hit has
     * already been counted by then, so the vanilla attack is only dropped.
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onAttack(io.papermc.paper.event.player.PrePlayerAttackEntityEvent event) {
        Entity target = event.getAttacked();
        if (!CraftEngineFurniture.isFurniture(target)) {
            return;
        }
        BukkitFurniture furniture = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(target);
        if (furniture != null && VerticalSlabListener.plate(furniture) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHit(FurnitureHitEvent event) {
        Player player = event.player();
        if (player.getGameMode() == GameMode.CREATIVE) {
            clear(player.getUniqueId());
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
                && now - previous.lastTick() <= IDLE_RESET_TICKS
                && previous.tool().isSimilar(player.getInventory().getItemInMainHand());
        if (continuing && now == previous.lastTick()) {
            event.setCancelled(true);
            return;
        }
        if (!continuing) clear(player.getUniqueId());
        Location at = event.furniture().location();
        double speed = spec.hardness() < 0 ? 0 : spec.hardness() == 0 ? 1
                : BukkitAdaptor.adapt(player).getDestroyProgress(
                        BlockStateUtils.blockDataToBlockState(spec.reference()),
                        new BlockPos(at.getBlockX(), at.getBlockY(), at.getBlockZ()))
                        * spec.reference().getMaterial().getHardness() / spec.hardness();
        double amount = MiningProgress.advance(continuing ? previous.amount() : 0, speed,
                continuing ? now - previous.lastTick() : 0);
        if (amount < 1) {
            progress.put(player.getUniqueId(), new Progress(target, amount, now,
                    player.getInventory().getItemInMainHand().clone()));
            event.setCancelled(true);
            feedback(event, spec);
            cracks.update(player, event.furniture(), amount);
            if (!continuing) watch(player, event.furniture());
            return;
        }
        // The last hit: CraftEngine breaks the piece.
        clear(player.getUniqueId());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBreak(FurnitureBreakEvent event) {
        progress.entrySet().stream().filter(entry -> entry.getValue().furniture().equals(event.furniture().uuid()))
                .map(Map.Entry::getKey).toList().forEach(this::clear);
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
        clear(event.getPlayer().getUniqueId());
    }

    private void watch(Player player, BukkitFurniture furniture) {
        player.getScheduler().runAtFixedRate(plugin, task -> {
            Progress active = progress.get(player.getUniqueId());
            if (active == null || !active.furniture().equals(furniture.uuid())) { task.cancel(); return; }
            boolean expired = Bukkit.getCurrentTick() - active.lastTick() > IDLE_RESET_TICKS;
            if (expired || !furniture.isValid() || !player.getWorld().equals(furniture.location().getWorld())
                    || player.getEyeLocation().distanceSquared(furniture.location()) > 64
                    || !active.tool().isSimilar(player.getInventory().getItemInMainHand()) || !aimingAt(player, furniture)) {
                clear(player.getUniqueId()); task.cancel();
            }
        }, () -> clear(player.getUniqueId()), 2, 2);
    }

    private static boolean aimingAt(Player player, BukkitFurniture furniture) {
        var plate = VerticalSlabListener.plate(furniture);
        if (plate == null) return true;
        double[] b = plate.box();
        return new org.bukkit.util.BoundingBox(b[0], b[1], b[2], b[3], b[4], b[5]).rayTrace(
                player.getEyeLocation().toVector(), player.getEyeLocation().getDirection(), 8) != null;
    }

    private void clear(UUID player) { progress.remove(player); cracks.clear(player); }

    public void clearAll() { progress.clear(); cracks.clearAll(); }

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
