package net.cinchtail.cinchsmissingblocks.cmb;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

/**
 * Which cells hold a CMB furniture piece, so "is there a piece here?" is a hash lookup.
 *
 * <p>That question is asked on events that fire constantly: water flow, block
 * placement, every piston move, physics updates around bars. Answering each with an
 * entity query is what makes piston-heavy farms lag, so the index answers "no" for
 * almost every cell and an entity query only runs where it says a piece may be.
 *
 * <p>It follows the entities themselves: Paper's add/remove-from-world events fire
 * for every entity as chunks load and unload and as furniture is placed and broken,
 * so the index can't drift from what is actually in the world. A piece's furniture is
 * resolved through CraftEngine a tick after its entity appears, retrying while
 * CraftEngine finishes loading it; the plugin also indexes the pieces it places
 * itself straight away, so a piece is visible in the same tick it was placed.
 */
public final class PieceIndex implements Listener {

    /** world -> packed cell -> furniture entities whose piece is in that cell */
    private static final Map<UUID, Map<Long, Set<UUID>>> CELLS = new ConcurrentHashMap<>();
    /** furniture entity -> {world, cell} */
    private static final Map<UUID, Object[]> BY_ENTITY = new ConcurrentHashMap<>();
    private static final int RESOLVE_TRIES = 40;

    private final Plugin plugin;

    public PieceIndex(Plugin plugin) {
        this.plugin = plugin;
    }

    static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    /** Whether a piece may be in this cell. False is certain; true needs the entity query. */
    static boolean mayContain(Block cell) {
        Map<Long, Set<UUID>> world = CELLS.get(cell.getWorld().getUID());
        return world != null && world.containsKey(pack(cell.getX(), cell.getY(), cell.getZ()));
    }

    /** Index a piece now - for pieces the plugin itself just placed. */
    static void add(BukkitFurniture furniture) {
        if (furniture == null) {
            return;
        }
        VerticalSlabListener.Plate plate = VerticalSlabListener.plate(furniture);
        if (plate == null) {
            return;
        }
        Entity entity = furniture.bukkitEntity();
        UUID world = plate.cell().getWorld().getUID();
        long cell = pack(plate.cell().getX(), plate.cell().getY(), plate.cell().getZ());
        remove(entity.getUniqueId());
        CELLS.computeIfAbsent(world, w -> new ConcurrentHashMap<>())
                .computeIfAbsent(cell, c -> ConcurrentHashMap.newKeySet())
                .add(entity.getUniqueId());
        BY_ENTITY.put(entity.getUniqueId(), new Object[] {world, cell});
    }

    static void remove(UUID entity) {
        Object[] at = BY_ENTITY.remove(entity);
        if (at == null) {
            return;
        }
        Map<Long, Set<UUID>> world = CELLS.get((UUID) at[0]);
        if (world == null) {
            return;
        }
        world.computeIfPresent((Long) at[1], (cell, set) -> {
            set.remove(entity);
            return set.isEmpty() ? null : set;
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdd(EntityAddToWorldEvent event) {
        if (event.getEntity() instanceof ItemDisplay display && CraftEngineFurniture.isFurniture(display)) {
            resolve(display, RESOLVE_TRIES);
        }
    }

    private void resolve(Entity entity, int tries) {
        entity.getScheduler().run(plugin, task -> {
            BukkitFurniture furniture = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity);
            if (furniture != null) {
                add(furniture);
            } else if (tries > 0 && entity.isValid()) {
                resolve(entity, tries - 1);
            }
        }, null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRemove(EntityRemoveFromWorldEvent event) {
        if (event.getEntity() instanceof ItemDisplay) {
            remove(event.getEntity().getUniqueId());
        }
    }
}
