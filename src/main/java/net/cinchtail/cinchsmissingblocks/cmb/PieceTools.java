package net.cinchtail.cinchsmissingblocks.cmb;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.cinchtail.cinchsmissingblocks.cmb.config.CmbConfig;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * /cmb kill and /cmb glow: CMB pieces around a player, by {@link PieceCategory}.
 *
 * <p>Pieces are found two ways, because they are two things: furniture (stairs, slabs,
 * walls, fences, panes, vertical slabs, horizontal stairs, doubles as furniture) by the
 * entities near the player, and CraftEngine blocks (cubes, pillars, doubles as blocks)
 * by the blocks in the radius. The radius is a sphere around the player's block, capped
 * by tools.max-radius.
 *
 * <p>The outlines follow GriefPrevention3D's glowing visualization: a block display per
 * piece, glowing in its category's colour (any RGB, through the glow colour override),
 * seen only by the player who asked (hidden by default, shown to them). They are not
 * persistent, so a restart or an unloaded chunk can never leave one behind, and they go
 * when the player runs /cmb glow again, quits, or the plugin disables.
 */
final class PieceTools implements Listener {

    /** What a piece found by {@link #find} is, and the box it fills, in world coordinates. */
    record Found(PieceCategory category, BukkitFurniture furniture, Block block, double[] box) {}

    private static final String GLOW_TAG = "cmb-glow";
    /** Outlines sit this far outside the piece, so they never z-fight its faces. */
    private static final double PAD = 0.01;

    private final CmbPlugin plugin;
    private final Map<UUID, List<BlockDisplay>> glowing = new ConcurrentHashMap<>();

    PieceTools(CmbPlugin plugin) {
        this.plugin = plugin;
    }

    private CmbConfig.Tools settings() {
        return plugin.cmbConfig().tools();
    }

    // --- finding -------------------------------------------------------------------

    /** Every CMB piece of these categories within {@code radius} of {@code center}. */
    static List<Found> find(Location center, int radius, Set<PieceCategory> categories) {
        World world = center.getWorld();
        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();
        long r2 = (long) radius * radius;
        List<Found> found = new ArrayList<>();

        for (Entity entity : world.getNearbyEntities(center, radius + 1, radius + 2, radius + 1,
                CraftEngineFurniture::isFurniture)) {
            BukkitFurniture furniture = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity);
            if (furniture == null) {
                continue;
            }
            PieceCategory category = categoryOf(furniture.id(), false);
            if (category == null || !categories.contains(category)) {
                continue;
            }
            if (category == PieceCategory.CROPS) {
                Block cell = furniture.location().getBlock();
                if (distance2(cell, cx, cy, cz) <= r2) {
                    found.add(new Found(category, furniture, null, cropBox(cell, furniture)));
                }
                continue;
            }
            VerticalSlabListener.Plate plate = VerticalSlabListener.plate(furniture);
            if (plate == null) {
                continue;
            }
            Block cell = plate.cell();
            if (distance2(cell, cx, cy, cz) <= r2) {
                found.add(new Found(category, furniture, null, plate.box()));
            }
        }

        int minY = Math.max(world.getMinHeight(), cy - radius);
        int maxY = Math.min(world.getMaxHeight() - 1, cy + radius);
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    continue;
                }
                for (int y = minY; y <= maxY; y++) {
                    long d2 = (long) (x - cx) * (x - cx) + (long) (y - cy) * (y - cy) + (long) (z - cz) * (z - cz);
                    if (d2 > r2) {
                        continue;
                    }
                    Block block = world.getBlockAt(x, y, z);
                    ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(block);
                    if (state == null || state.isEmpty()) {
                        continue;
                    }
                    PieceCategory category = categoryOf(state.owner().value().id(), true);
                    if (category != null && categories.contains(category)) {
                        found.add(new Found(category, null, block,
                                new double[] {x, y, z, x + 1, y + 1, z + 1}));
                    }
                }
            }
        }
        return found;
    }

    /** A wart crop's height at each age: its hitbox's, 14 pixels wide. */
    private static final double[] CROP_HEIGHT = {0.25, 0.5, 0.75, 0.875};

    private static double[] cropBox(Block cell, BukkitFurniture crop) {
        double height = CROP_HEIGHT[Math.min(Math.max(NetherWartCrops.ageOf(crop), 0), CROP_HEIGHT.length - 1)];
        int x = cell.getX(), y = cell.getY(), z = cell.getZ();
        return new double[] {x + 0.0625, y, z + 0.0625, x + 0.9375, y + height, z + 0.9375};
    }

    private static PieceCategory categoryOf(Key id, boolean block) {
        if (id == null || !VerticalSlabListener.NAMESPACE.equals(id.namespace())) {
            return null;
        }
        return PieceCategory.of(id.value(), block);
    }

    private static long distance2(Block cell, int cx, int cy, int cz) {
        long dx = cell.getX() - cx;
        long dy = cell.getY() - cy;
        long dz = cell.getZ() - cz;
        return dx * dx + dy * dy + dz * dz;
    }

    static Map<PieceCategory, Integer> counts(List<Found> found) {
        Map<PieceCategory, Integer> counts = new EnumMap<>(PieceCategory.class);
        for (Found f : found) {
            counts.merge(f.category(), 1, Integer::sum);
        }
        return counts;
    }

    // --- kill ----------------------------------------------------------------------

    /** Removes what {@link #find} found, without drops, and re-shapes what joined it. */
    static void kill(List<Found> found) {
        List<Block> cells = new ArrayList<>();
        for (Found f : found) {
            if (f.furniture() != null) {
                if (f.furniture().isValid()) {
                    VerticalSlabListener.Plate plate = VerticalSlabListener.plate(f.furniture());
                    if (plate != null) {
                        cells.add(plate.cell());
                    }
                    CraftEngineFurniture.remove(f.furniture(), false, false);
                }
            } else if (CraftEngineBlocks.remove(f.block())) {
                cells.add(f.block());
            }
        }
        // Removing through the API fires no break event, so the walls, fences, panes and
        // stairs that joined these pieces wouldn't let go of them on their own.
        for (Block cell : cells) {
            VerticalSlabListener.reshapeAround(cell);
        }
    }

    // --- glow ----------------------------------------------------------------------

    boolean isGlowing(Player player) {
        return glowing.containsKey(player.getUniqueId());
    }

    /** Outlines the pieces for this player only, replacing any outlines they had. */
    int glow(Player player, List<Found> found) {
        clearGlow(player);
        List<BlockDisplay> displays = new ArrayList<>();
        Map<String, Integer> colors = settings().colors();
        for (Found f : found) {
            double[] b = f.box();
            Location at = new Location(player.getWorld(), b[0] - PAD, b[1] - PAD, b[2] - PAD);
            Color color = Color.fromRGB(colors.getOrDefault(f.category().key(), f.category().defaultColor));
            BlockDisplay display = player.getWorld().spawn(at, BlockDisplay.class, d -> {
                // Before it is sent to anyone: hidden by default, never saved.
                d.setVisibleByDefault(false);
                d.setPersistent(false);
                d.addScoreboardTag(GLOW_TAG);
                d.setBlock(Material.WHITE_STAINED_GLASS.createBlockData());
                d.setGlowing(true);
                d.setGlowColorOverride(color);
                d.setBrightness(new Display.Brightness(15, 15));
                d.setShadowRadius(0f);
                d.setShadowStrength(0f);
                d.setTransformation(new Transformation(
                        new Vector3f(),
                        new AxisAngle4f(),
                        new Vector3f((float) (b[3] - b[0] + 2 * PAD), (float) (b[4] - b[1] + 2 * PAD),
                                (float) (b[5] - b[2] + 2 * PAD)),
                        new AxisAngle4f()));
            });
            player.showEntity(plugin, display);
            displays.add(display);
        }
        glowing.put(player.getUniqueId(), displays);
        return displays.size();
    }

    void clearGlow(Player player) {
        List<BlockDisplay> displays = glowing.remove(player.getUniqueId());
        if (displays != null) {
            for (BlockDisplay display : displays) {
                display.getScheduler().run(plugin, task -> display.remove(), null);
            }
        }
    }

    void clearAll() {
        for (List<BlockDisplay> displays : glowing.values()) {
            for (BlockDisplay display : displays) {
                if (display.isValid()) {
                    display.remove();
                }
            }
        }
        glowing.clear();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clearGlow(event.getPlayer());
    }
}
