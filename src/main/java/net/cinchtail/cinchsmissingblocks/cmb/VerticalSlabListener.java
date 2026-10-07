package net.cinchtail.cinchsmissingblocks.cmb;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.api.event.FurnitureAttemptPlaceEvent;
import net.momirealms.craftengine.bukkit.api.event.FurnitureBreakEvent;
import net.momirealms.craftengine.bukkit.api.event.FurnitureInteractEvent;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.bukkit.plugin.user.BukkitServerPlayer;
import net.momirealms.craftengine.bukkit.util.DirectionUtils;
import net.momirealms.craftengine.bukkit.util.LocationUtils;
import net.momirealms.craftengine.core.block.UpdateFlags;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundBlockUpdatePacketProxy;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ServerboundUseItemOnPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.server.level.ServerLevelProxy;
import net.momirealms.craftengine.proxy.minecraft.server.level.ServerPlayerProxy;
import net.momirealms.craftengine.proxy.minecraft.server.network.ServerGamePacketListenerImplProxy;
import net.momirealms.craftengine.proxy.minecraft.world.InteractionHandProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.block.BlockProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.block.BlocksProxy;
import net.momirealms.craftengine.proxy.minecraft.world.phys.BlockHitResultProxy;
import net.momirealms.craftengine.proxy.minecraft.world.phys.Vec3Proxy;
import net.momirealms.craftengine.core.entity.furniture.hitbox.FurnitureHitBox;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.item.ItemDefinition;
import net.momirealms.craftengine.core.item.behavior.FurnitureItem;
import net.momirealms.craftengine.core.item.behavior.ItemBehavior;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockHitResult;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.Vec3d;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.block.BlockSupport;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import net.cinchtail.cinchsmissingblocks.cmb.scheduler.Schedulers;

/**
 * Block behaviour for vertical slabs and horizontal stairs, which are CraftEngine
 * furniture rather than blocks, so the world treats their cell as air. This adds
 * vanilla slab and stair behaviours back:
 *
 * <ul>
 *   <li><b>Water.</b> A side the piece covers completely - a vertical slab's back, a
 *   horizontal stair's two backs - is solid. Water can't cross it in either
 *   direction, and a vertical slab's other sides let water in. Water from above lands
 *   on any piece's top and spreads over it; water inside a vertical slab's cell still
 *   falls out of the bottom. A horizontal stair takes no water by flow. That is vanilla's rule
 *   for a waterlogged stair or slab (a face blocks flow only when it fully covers the
 *   cell's side), so water flows into the open half of the cell and stops at the back
 *   instead of leaking past it.</li>
 *   <li><b>Waterlogging.</b> Right-clicking a vertical slab with a water bucket fills
 *   its cell with a water source, and an empty bucket takes the water back.</li>
 *   <li><b>Doubling.</b> Right-clicking a vertical slab's front with the same
 *   vertical slab, sneaking or not, turns the cell into {@code <id>_double}: the full
 *   block, which drops both slabs when mined. An edge doubles too, unless sneaking,
 *   which leaves it to CraftEngine's placement so slabs can be laid side by side.
 *   Clicking the <em>back</em> with any vertical slab places the new one against it,
 *   back to back in the next cell, the way clicking the underside of a bottom slab
 *   places a slab below it.</li>
 *   <li><b>Horizontal stair placement.</b> A horizontal stair is an L, three quarters
 *   of the cell, full height: the corner piece for vertical slab walls. CraftEngine's
 *   placement is cancelled and the stair is placed at the cell centre, turned to wrap
 *   (1) an inside corner of vertical slab walls next to it, if there is one, else
 *   (2) on a wall, the clicked wall plus the half of the face that was hit, else
 *   (3) on a floor or ceiling, the corner the player is facing.</li>
 * </ul>
 *
 * <p>Which half of its cell a plate fills comes from its collider boxes, not from
 * re-deriving CraftEngine's anchor and yaw maths. The union of the boxes is the plate,
 * and the cell face that union fully covers is the back.
 */
public final class VerticalSlabListener implements Listener {

    static final String NAMESPACE = "cinchsmissingblocks";
    static final String SUFFIX = "_vertical";
    static final String DOUBLE_SUFFIX = "_double";
    static final String STAIRS_SUFFIX = "_horizontal_stairs";

    /** One pixel. Collider boxes are float-rounded, so faces match to within this. */
    private static final double EPSILON = 1.0 / 16.0;
    /**
     * After this plugin handles a bucket on a vertical slab, the same player's
     * vanilla bucket use is ignored for this many ticks. The furniture interaction
     * and a vanilla use-item can both arrive from one right-click, and the second
     * would undo the first.
     */
    private static final int BUCKET_GUARD_TICKS = 4;

    private static final BlockFace[] HORIZONTAL = {
        BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };
    private static final BlockFace[] SIDES = {
        BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST,
        BlockFace.UP, BlockFace.DOWN
    };
    private static final double[] QUARTERS = {0.25, 0.75};

    /**
     * A furniture piece, the cell it stands in, and the cell faces it covers
     * completely ({@code solid}, top and bottom included). {@code back} is a vertical
     * slab's single solid side, and null for every other kind. {@code box} is the union
     * of its collider boxes (minX, minY, minZ, maxX, maxY, maxZ), kept for /cmbvslab.
     */
    record Plate(BukkitFurniture furniture, Kind kind, Block cell, BlockFace back,
                 Set<BlockFace> solid, double[] box) {}

    /**
     * What a piece is, from its id. A furniture slab or stair (the furniture test,
     * features.furniture-fallback) keeps the canonical block id, so any
     * cinchsmissingblocks furniture ending in _slab or _stairs is one: those ids are
     * never furniture otherwise.
     */
    enum Kind { VERTICAL_SLAB, HORIZONTAL_STAIRS, SLAB, STAIRS, WALL, FENCE, PANE, DOUBLE, CROP }

    static Kind kindOf(Key id) {
        if (id == null || !NAMESPACE.equals(id.namespace())) {
            return null;
        }
        String value = id.value();
        if (value.endsWith("_nether_wart")) {
            // A crop (NetherWartCrops): no solid face, never takes water.
            return Kind.CROP;
        }
        if (value.endsWith(DOUBLE_SUFFIX)) {
            // Two slabs as one furniture cube (vertical-slabs.doubles: furniture).
            return Kind.DOUBLE;
        }
        if (value.endsWith(SUFFIX)) {
            return Kind.VERTICAL_SLAB;
        }
        if (value.endsWith(STAIRS_SUFFIX)) {
            return Kind.HORIZONTAL_STAIRS;
        }
        if (value.endsWith("_slab")) {
            return Kind.SLAB;
        }
        if (value.endsWith("_stairs")) {
            return Kind.STAIRS;
        }
        if (value.endsWith("_wall")) {
            return Kind.WALL;
        }
        if (value.endsWith("_fence")) {
            return Kind.FENCE;
        }
        if (value.endsWith("_pane")) {
            return Kind.PANE;
        }
        return null;
    }

    // Concurrent and thread-local because on Folia each region runs on its own thread.
    private final CmbPlugin plugin;

    public VerticalSlabListener(CmbPlugin plugin) {
        this.plugin = plugin;
    }

    private final Map<UUID, Integer> bucketHandledAt = new ConcurrentHashMap<>();
    /** Set while this class fires its own protection-check events. */
    private final ThreadLocal<Boolean> firing = ThreadLocal.withInitial(() -> false);

    // --- geometry -----------------------------------------------------------

    static boolean isVerticalSlab(Key id) {
        return id != null && NAMESPACE.equals(id.namespace()) && id.value().endsWith(SUFFIX);
    }

    static boolean isHorizontalStairs(Key id) {
        return id != null && NAMESPACE.equals(id.namespace())
                && id.value().endsWith(STAIRS_SUFFIX);
    }

    /** The piece for a piece of furniture, or null if it is not one of ours. */
    static Plate plate(BukkitFurniture furniture) {
        if (furniture == null || !furniture.isValid()) {
            return null;
        }
        Kind kind = kindOf(furniture.id());
        if (kind == null) {
            return null;
        }
        List<double[]> boxes = new ArrayList<>(6);
        for (FurnitureHitBox hitbox : furniture.hitboxes()) {
            hitbox.collectCullingBounds(aabb -> boxes.add(new double[] {
                aabb.minX, aabb.minY, aabb.minZ, aabb.maxX, aabb.maxY, aabb.maxZ
            }));
        }
        if (boxes.isEmpty()) {
            return null;
        }
        double[] box = boxes.getFirst().clone();
        for (double[] b : boxes) {
            for (int i = 0; i < 3; i++) {
                box[i] = Math.min(box[i], b[i]);
                box[i + 3] = Math.max(box[i + 3], b[i + 3]);
            }
        }
        Block cell = furniture.location().getWorld().getBlockAt(
                (int) Math.floor((box[0] + box[3]) / 2),
                (int) Math.floor((box[1] + box[4]) / 2),
                (int) Math.floor((box[2] + box[5]) / 2));
        Set<BlockFace> solid = EnumSet.noneOf(BlockFace.class);
        for (BlockFace face : SIDES) {
            if (covers(boxes, cell, face)) {
                solid.add(face);
            }
        }
        BlockFace back = kind == Kind.VERTICAL_SLAB && solid.size() == 1
                ? solid.iterator().next() : null;
        return new Plate(furniture, kind, cell, back, solid, box);
    }

    /**
     * Whether the boxes cover one side of the cell completely. Checked at the centres
     * of the side's four quarters, just inside the cell: the pieces are built from
     * half-block boxes, so a quarter is either covered or not.
     */
    private static boolean covers(List<double[]> boxes, Block cell, BlockFace face) {
        double x = cell.getX();
        double y = cell.getY();
        double z = cell.getZ();
        double in = 0.02;
        for (double u : QUARTERS) {
            for (double v : QUARTERS) {
                double[] point = switch (face) {
                    case NORTH -> new double[] {x + u, y + v, z + in};
                    case SOUTH -> new double[] {x + u, y + v, z + 1 - in};
                    case WEST -> new double[] {x + in, y + v, z + u};
                    case EAST -> new double[] {x + 1 - in, y + v, z + u};
                    case UP -> new double[] {x + u, y + 1 - in, z + v};
                    case DOWN -> new double[] {x + u, y + in, z + v};
                    default -> throw new IllegalArgumentException(face.name());
                };
                if (!inAny(boxes, point)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean inAny(List<double[]> boxes, double[] p) {
        double t = 1e-3;
        for (double[] b : boxes) {
            if (p[0] >= b[0] - t && p[0] <= b[3] + t && p[1] >= b[1] - t && p[1] <= b[4] + t
                    && p[2] >= b[2] - t && p[2] <= b[5] + t) {
                return true;
            }
        }
        return false;
    }

    /**
     * Places a piece through CraftEngine and indexes it at once (PieceIndex), so it is
     * seen in the same tick - the index's own entity events resolve a tick later.
     */
    static BukkitFurniture spawn(Location at, Key id, String variant, boolean sound) {
        BukkitFurniture furniture = CraftEngineFurniture.place(at, id, variant, sound);
        PieceIndex.add(furniture);
        return furniture;
    }

    static BukkitFurniture spawn(Location at, Key id, String variant) {
        return spawn(at, id, variant, false);
    }

    /** Every piece standing in a cell. */
    static List<Plate> platesIn(Block cell) {
        if (!PieceIndex.mayContain(cell)) {
            return List.of();
        }
        List<Plate> plates = new ArrayList<>(1);
        // The furniture's own entity sits at its anchor: the floor centre for a ground
        // piece, the wall face for a wall slab. Both are within one block of the cell
        // centre, and the plate check below keeps only pieces that are in this cell.
        Location centre = cell.getLocation().add(0.5, 0.5, 0.5);
        for (ItemDisplay display : cell.getWorld().getNearbyEntitiesByType(
                ItemDisplay.class, centre, 1.0, 1.0, 1.0)) {
            if (!CraftEngineFurniture.isFurniture(display)) {
                continue;
            }
            Plate plate = plate(CraftEngineFurniture.getLoadedFurnitureByMetaEntity(display));
            if (plate != null && plate.cell().equals(cell)) {
                plates.add(plate);
            }
        }
        return plates;
    }

    private static boolean backCovers(Block cell, BlockFace side) {
        for (Plate plate : platesIn(cell)) {
            if (plate.solid().contains(side)) {
                return true;
            }
        }
        return false;
    }

    // --- water flow ---------------------------------------------------------

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onFlow(BlockFromToEvent event) {
        Block from = event.getBlock();
        Material fluid = from.getType();
        if (fluid != Material.WATER && fluid != Material.LAVA) {
            return;
        }
        BlockFace face = event.getFace();
        if (face == BlockFace.UP || face == BlockFace.SELF) {
            return;
        }
        Block to = event.getToBlock();
        List<Plate> target = platesIn(to);
        if (face == BlockFace.DOWN) {
            // Water in a piece's cell falls out of its bottom unless the bottom is
            // solid (a bottom slab or stair). Water from above lands on any piece's
            // top, as on a block.
            if (backCovers(from, BlockFace.DOWN)) {
                event.setCancelled(true);
                return;
            }
            if (!target.isEmpty()) {
                event.setCancelled(true);
                spreadOnTop(from);
            }
            return;
        }
        // Blocked if the source cell's piece covers the side water is leaving through,
        // or the target cell's covers the side it would enter by. Only a vertical slab
        // takes water in by flow; every other piece is waterlogged with a bucket or not
        // at all, as vanilla stairs and slabs are.
        if (backCovers(from, face)) {
            event.setCancelled(true);
            return;
        }
        // Water flowing into a crop washes it away, as it does vanilla nether wart.
        for (Plate plate : target) {
            if (plate.kind() == Kind.CROP) {
                NetherWartCrops.pop(plate.furniture());
                return;
            }
        }
        if (entryBlocked(target, face)) {
            event.setCancelled(true);
        }
    }

    /** Whether water moving {@code face}-wards may not enter a cell holding these pieces. */
    private static boolean entryBlocked(List<Plate> pieces, BlockFace face) {
        for (Plate plate : pieces) {
            if (plate.kind() != Kind.VERTICAL_SLAB
                    || plate.solid().contains(face.getOppositeFace())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The sideways spread vanilla skips when its downward flow is cancelled.
     *
     * <p>Paper fires the downward BlockFromToEvent inside FlowingFluid.spread and,
     * when it is cancelled, returns before spreadToSides - vanilla believes the water
     * went down. So water landing on a piece would just stop there. This does the one
     * step vanilla would have done (level 1 beside a source or a falling column, one
     * more than a flowing level), into air the flow rules above allow; from there
     * vanilla's own ticks keep the new water fed, and drain it when the column above
     * goes away. Water only - lava's spread distance depends on the dimension.
     */
    private void spreadOnTop(Block from) {
        if (from.getType() != Material.WATER || !(from.getBlockData() instanceof Levelled level)) {
            return;
        }
        int next = level.getLevel() == 0 || level.getLevel() >= 8 ? 1 : level.getLevel() + 1;
        if (next > 7) {
            return;
        }
        Schedulers.atLocation(plugin, from.getLocation(), () -> {
            if (from.getType() != Material.WATER) {
                return;
            }
            for (BlockFace face : HORIZONTAL) {
                Block side = from.getRelative(face);
                if (!side.getType().isAir() || backCovers(from, face)) {
                    continue;
                }
                if (entryBlocked(platesIn(side), face)) {
                    continue;
                }
                Levelled water = (Levelled) Material.WATER.createBlockData();
                water.setLevel(next);
                side.setBlockData(water);
            }
        });
    }

    // --- horizontal stairs: placement -----------------------------------------

    /** The four ways an L can turn: the two adjacent sides it covers. */
    private static final BlockFace[][] CORNERS = {
        {BlockFace.NORTH, BlockFace.EAST}, {BlockFace.EAST, BlockFace.SOUTH},
        {BlockFace.SOUTH, BlockFace.WEST}, {BlockFace.WEST, BlockFace.NORTH}
    };

    @EventHandler(ignoreCancelled = true)
    public void onAttemptPlace(FurnitureAttemptPlaceEvent event) {
        Key id = event.furniture().id();
        Kind kind = kindOf(id);
        if (kind == null || kind == Kind.CROP) {
            // A crop is planted by NetherWartCrops.
            return;
        }
        // Placed here instead, with a cell, variant and yaw this plugin chooses rather
        // than CraftEngine's anchor and anchor yaw.
        event.setCancelled(true);
        Player player = event.player();
        Location at = event.location();
        EquipmentSlot slot = event.hand() == InteractionHand.OFF_HAND
                ? EquipmentSlot.OFF_HAND : EquipmentSlot.HAND;
        String anchor = event.variant().name();
        BlockFace face = switch (anchor) {
            case "ground" -> BlockFace.UP;
            case "ceiling" -> BlockFace.DOWN;
            // A wall anchor is turned to face out of the clicked face.
            default -> faceForYaw(at.getYaw());
        };
        BlockFace wall = face == BlockFace.UP || face == BlockFace.DOWN ? null : face;
        Block cell = targetCell(event.clickedBlock(), face);
        // A claim or region may not want it: CraftEngine's own check ran on its anchor,
        // which is not always this cell, and this plugin places the piece itself.
        if (!Protection.canBuild(player, cell, player.getInventory().getItem(slot).getType())) {
            return;
        }
        Vector look = player.getLocation().getDirection();
        // A slab or stair's half, as vanilla: the top of a block gives the bottom half,
        // the underside the top half, and a side whichever half was hit.
        String half = switch (anchor) {
            case "ground" -> "bottom";
            case "ceiling" -> "top";
            default -> hitPoint(player, event, cell, look).getY() - cell.getY() >= 0.5
                    ? "top" : "bottom";
        };

        List<Plate> present = platesIn(cell);
        if (kind == Kind.SLAB && present.size() == 1 && present.getFirst().furniture().id().equals(id)
                && !present.getFirst().furniture().currentVariant().name().equals(half)) {
            // Into the other half of a slab of the same kind: the full block.
            ItemStack held = player.getInventory().getItem(slot);
            if (doubleUp(player, present.getFirst(), slot, held)) {
                player.swingHand(slot);
            }
            return;
        }
        if (!placeableInto(cell) || !present.isEmpty() || occupied(cell)) {
            return;
        }

        if (kind == Kind.VERTICAL_SLAB) {
            placeVerticalSlab(player, slot, id, cell, wall, at.getYaw());
            return;
        }
        String variant;
        float yaw;
        switch (kind) {
            case SLAB -> {
                variant = half;
                yaw = 0f;
            }
            case WALL, FENCE, PANE -> {
                variant = connections(cell, kind);
                yaw = 0f;
            }
            case STAIRS -> {
                BlockFace facing = player.getFacing();
                variant = half + "_" + stairShape(cell, facing, half);
                yaw = yawForFacing(facing);
            }
            default -> {
                BlockFace[] corner = snappedCorner(cell);
                if (corner == null) {
                    if (wall != null) {
                        BlockFace against = wall.getOppositeFace();
                        corner = new BlockFace[] {
                            against, sideOf(against, hitPoint(player, event, cell, look), cell)
                        };
                    } else {
                        corner = new BlockFace[] {
                            look.getZ() < 0 ? BlockFace.NORTH : BlockFace.SOUTH,
                            look.getX() > 0 ? BlockFace.EAST : BlockFace.WEST
                        };
                    }
                }
                variant = "ground";
                yaw = yawFor(corner);
            }
        }

        Location place = cell.getLocation().add(0.5, 0, 0.5);
        place.setYaw(yaw);
        if (spawn(place, id, variant, true) == null) {
            return;
        }
        // Only now discard what the piece replaces. Clearing first meant a failed spawn --
        // CraftEngine unloaded, the id disabled by config, the pack mid-reload -- destroyed
        // the player's grass and put nothing in its place, with nothing said.
        clearForPlacement(cell);
        if (player.getGameMode() != GameMode.CREATIVE) {
            ItemStack held = player.getInventory().getItem(slot);
            held.setAmount(held.getAmount() - 1);
        }
        player.swingHand(slot);
        // Stairs re-shape and walls/fences re-connect around any new piece: its solid
        // faces are something a wall or fence connects to.
        reshapeAround(cell);
    }

    /**
     * Whether a piece can go into a cell: air, water (it waterlogs), or a block vanilla
     * placement replaces - short grass, dead bushes, leaf litter, snow layers, seagrass.
     */
    static boolean placeableInto(Block cell) {
        Material type = cell.getType();
        return type.isAir() || type == Material.WATER || (cell.isReplaceable() && type != Material.LAVA);
    }

    /** Like placeableInto, for what can't stand in water (a crop). */
    static boolean placeableIntoDry(Block cell) {
        return cell.getType().isAir()
                || (cell.isReplaceable() && !cell.isLiquid() && !wet(cell));
    }

    /**
     * Removes what a placement replaces, as vanilla does - no drops - leaving water
     * where the replaced block was underwater (seagrass, a waterlogged plant).
     */
    static void clearForPlacement(Block cell) {
        Material type = cell.getType();
        if (type.isAir() || type == Material.WATER) {
            return;
        }
        // No physics: vanilla removes the plant as part of placing, not as a change that
        // notifies neighbours, and letting it do would let a grass placement tick whatever
        // happened to be beside it.
        cell.setType(wet(cell) ? Material.WATER : Material.AIR, false);
    }

    private static boolean wet(Block cell) {
        return switch (cell.getType()) {
            case SEAGRASS, TALL_SEAGRASS, KELP, KELP_PLANT -> true;
            default -> cell.getBlockData() instanceof org.bukkit.block.data.Waterlogged w && w.isWaterlogged();
        };
    }

    /**
     * The cell a click places into, as vanilla decides it: the clicked block itself
     * when it is replaceable (grass, snow, an empty cell), else its neighbour on the
     * clicked face.
     *
     * <p>Never from where the click landed. CraftEngine anchors furniture on the hit
     * point, which on a thin block - a pane post, iron bars, a trapdoor, a fence - is
     * inside that block's own cell: a vertical slab ended up sharing the cell, off the
     * grid by as many pixels as the thin block was thick, and clicking a pane post's
     * side placed nothing at all.
     */
    private static Block targetCell(Block clicked, BlockFace face) {
        if (clicked.isReplaceable() && clicked.getType() != Material.WATER && platesIn(clicked).isEmpty()) {
            return clicked;
        }
        return clicked.getRelative(face);
    }

    /**
     * A vertical slab, placed by this plugin rather than CraftEngine: anchored on the
     * cell's own grid - the floor centre for a floor click, mid-height on the cell face
     * it was placed against for a wall click - so it can never drift into a thin
     * block's cell.
     */
    private void placeVerticalSlab(Player player, EquipmentSlot slot, Key id, Block cell,
                                   BlockFace wall, float yaw) {
        if (!placeableInto(cell) || !platesIn(cell).isEmpty() || occupied(cell)) {
            return;
        }
        Location anchor = cell.getLocation().add(0.5, 0, 0.5);
        String variant = "ground";
        if (wall != null) {
            Vector back = wall.getOppositeFace().getDirection().multiply(0.5);
            anchor.add(back.getX(), 0.5, back.getZ());
            variant = "wall";
        }
        anchor.setYaw(yaw);
        if (spawn(anchor, id, variant, true) == null) {
            return;
        }
        clearForPlacement(cell);
        if (player.getGameMode() != GameMode.CREATIVE) {
            ItemStack held = player.getInventory().getItem(slot);
            held.setAmount(held.getAmount() - 1);
        }
        player.swingHand(slot);
        reshapeAround(cell);
    }

    // --- furniture stairs: vanilla's facing x half x shape --------------------

    /** A furniture stair's facing (its tall side) and half, read back off the piece. */
    record StairInfo(BlockFace facing, String half) {}

    private static StairInfo stairAt(Block cell) {
        for (Plate plate : platesIn(cell)) {
            if (plate.kind() == Kind.STAIRS) {
                String variant = plate.furniture().currentVariant().name();
                return new StairInfo(facingForYaw(plate.furniture().location().getYaw()),
                        variant.startsWith("top") ? "top" : "bottom");
            }
        }
        return null;
    }

    /**
     * StairBlock.getStairsShape, against furniture stairs. Outer when the stair behind
     * (on the tall side) is turned across this one, inner when the one in front is;
     * left when that turn is counter-clockwise of this stair's facing. Vanilla's
     * canTakeShape guard keeps a straight run straight.
     */
    static String stairShape(Block cell, BlockFace facing, String half) {
        StairInfo behind = stairAt(cell.getRelative(facing));
        if (behind != null && behind.half().equals(half) && crosses(behind.facing(), facing)
                && canTakeShape(cell, facing, half, behind.facing().getOppositeFace())) {
            return behind.facing() == counterClockwise(facing) ? "outer_left" : "outer_right";
        }
        StairInfo front = stairAt(cell.getRelative(facing.getOppositeFace()));
        if (front != null && front.half().equals(half) && crosses(front.facing(), facing)
                && canTakeShape(cell, facing, half, front.facing())) {
            return front.facing() == counterClockwise(facing) ? "inner_left" : "inner_right";
        }
        return "straight";
    }

    private static boolean canTakeShape(Block cell, BlockFace facing, String half, BlockFace side) {
        StairInfo neighbour = stairAt(cell.getRelative(side));
        return neighbour == null || neighbour.facing() != facing || !neighbour.half().equals(half);
    }

    private static boolean crosses(BlockFace a, BlockFace b) {
        boolean aX = a == BlockFace.EAST || a == BlockFace.WEST;
        boolean bX = b == BlockFace.EAST || b == BlockFace.WEST;
        return aX != bX;
    }

    private static BlockFace counterClockwise(BlockFace face) {
        return switch (face) {
            case NORTH -> BlockFace.WEST;
            case WEST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.EAST;
            default -> BlockFace.NORTH;
        };
    }

    /** Re-shapes the furniture stairs beside a cell, after one there came or went. */
    static void reshapeAround(Block cell) {
        // The cell itself, if it is bars, a wall or a fence that just went in; those
        // beside the cell join or let go of a piece that came or went, and a vanilla
        // wall below takes its tall sides and post from a piece wall above it.
        applyVanillaJoins(cell);
        for (BlockFace face : HORIZONTAL) {
            applyVanillaJoins(cell.getRelative(face));
        }
        Block below = cell.getRelative(BlockFace.DOWN);
        if (Tag.WALLS.isTagged(below.getType())) {
            // A piece above came or went: its tall sides and post follow.
            applyWall(below, true);
        } else {
            applyVanillaJoins(below);
        }
        // Below too: a wall's tall sides and post depend on what is above it.
        for (BlockFace face : new BlockFace[] {BlockFace.NORTH, BlockFace.SOUTH,
                BlockFace.EAST, BlockFace.WEST, BlockFace.DOWN}) {
            reshape(cell.getRelative(face));
        }
    }

    /**
     * Re-shapes the pieces in one cell. A wall whose shape changed re-shapes the wall
     * below it, down the column until a shape stays the same: the wall below takes its
     * tall sides and its post from this one, so without that, the result depended on the
     * order the wall was built in. A neighbour gaining an arm left the wall under it
     * with a low side (a 2-pixel hole), and a neighbour dropping its post left the wall
     * under it with a stray one.
     */
    private static void reshape(Block cell) {
        boolean wallChanged = false;
        for (Plate plate : platesIn(cell)) {
            String variant = switch (plate.kind()) {
                case STAIRS -> {
                    StairInfo info = stairAt(cell);
                    yield info.half() + "_" + stairShape(cell, info.facing(), info.half());
                }
                case WALL, FENCE, PANE -> connections(cell, plate.kind());
                default -> null;
            };
            if (variant != null && !variant.equals(plate.furniture().currentVariant().name())) {
                plate.furniture().setVariant(variant);
                wallChanged |= plate.kind() == Kind.WALL;
            }
        }
        if (wallChanged) {
            passDown(cell.getRelative(BlockFace.DOWN));
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBreak(FurnitureBreakEvent event) {
        Plate plate = plate(event.furniture());
        if (plate != null) {
            // Next tick, once the piece is gone, so its neighbours no longer see it.
            Block cell = plate.cell();
            Schedulers.atLocation(plugin, cell.getLocation(), () -> reshapeAround(cell));
        }
    }

    // --- keeping blocks out of piece cells ------------------------------------

    /**
     * No block goes into a piece's cell. A piece's cell is air to the server, so
     * vanilla let anything without a hitbox - buttons, rails, levers, string, plates,
     * torches - be placed inside furniture. features.protect-furniture-cells.
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onPlaceIntoPiece(BlockPlaceEvent event) {
        if (firing.get() || !plugin.cmbConfig().features().protectFurnitureCells()) {
            return;
        }
        // Doors and beds place two blocks; either half in a piece is refused.
        List<Block> cells = event instanceof org.bukkit.event.block.BlockMultiPlaceEvent multi
                ? multi.getReplacedBlockStates().stream().map(org.bukkit.block.BlockState::getBlock).toList()
                : List.of(event.getBlockPlaced());
        for (Block cell : cells) {
            if (!platesIn(cell).isEmpty()) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /**
     * A piston never moves a block into a piece's cell, or extends its head into one:
     * a piece stops a piston as obsidian does. The cost is a hash lookup per moved block
     * (PieceIndex), so piston farms pay nothing for it.
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onPistonExtend(org.bukkit.event.block.BlockPistonExtendEvent event) {
        if (pistonBlocked(event.getBlock(), event.getBlocks(), true)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onPistonRetract(org.bukkit.event.block.BlockPistonRetractEvent event) {
        if (pistonBlocked(event.getBlock(), event.getBlocks(), false)) {
            event.setCancelled(true);
        }
    }

    private static boolean pistonBlocked(Block piston, List<Block> moved, boolean extending) {
        if (!(piston.getBlockData() instanceof org.bukkit.block.data.Directional directional)) {
            return false;
        }
        BlockFace facing = directional.getFacing();
        BlockFace movement = extending ? facing : facing.getOppositeFace();
        if (extending && !platesIn(piston.getRelative(facing)).isEmpty()) {
            return true;
        }
        java.util.Set<Block> moving = new java.util.HashSet<>(moved);
        for (Block block : moved) {
            Block destination = block.getRelative(movement);
            if (!moving.contains(destination) && !platesIn(destination).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    // --- bars and panes joining pieces -----------------------------------------

    /**
     * Iron bars, copper bars (1.21.9+) and glass panes - vanilla's IronBarsBlock. Matched
     * by name, so a server without copper bars just has none here.
     */
    private static final java.util.Set<Material> BARS = java.util.Arrays.stream(Material.values())
            .filter(m -> !m.isLegacy() && m.isBlock()
                    && (m.name().endsWith("_BARS") || m.name().endsWith("GLASS_PANE")))
            .collect(java.util.stream.Collectors.toCollection(() -> java.util.EnumSet.noneOf(Material.class)));

    /** Whether a bar reaches toward a piece's face: a vertical slab's back and sides, not
     * its front; walls, panes and doubles; any face another piece covers completely. */
    private static boolean barJoins(Plate plate, BlockFace face) {
        return switch (plate.kind()) {
            case VERTICAL_SLAB -> plate.back() != null && face != plate.back().getOppositeFace();
            case WALL, PANE, DOUBLE -> true;
            default -> plate.solid().contains(face);
        };
    }

    /**
     * Sets a bar's sides toward pieces. Vanilla can't see furniture - the cell is air
     * to it - so it never joins one, and resets any side we set on its next shape
     * update (onBarPhysics re-applies). Sides toward real blocks are left to vanilla;
     * toward an empty cell, false, as vanilla would have it.
     */
    static void applyBars(Block bar) {
        if (!BARS.contains(bar.getType())
                || !(bar.getBlockData() instanceof org.bukkit.block.data.MultipleFacing data)) {
            return;
        }
        boolean changed = false;
        for (BlockFace side : HORIZONTAL) {
            Block next = bar.getRelative(side);
            List<Plate> pieces = platesIn(next);
            Boolean join = null;
            if (!pieces.isEmpty()) {
                join = false;
                for (Plate plate : pieces) {
                    join |= barJoins(plate, side.getOppositeFace());
                }
            } else if (next.getType().isAir() || next.getType() == Material.WATER) {
                join = false;
            }
            if (join != null && data.hasFace(side) != join) {
                data.setFace(side, join);
                changed = true;
            }
        }
        if (changed) {
            bar.setBlockData(data, false);
        }
    }

    /**
     * A vanilla block that joins pieces, as its own shape update just reset it: bars,
     * panes, walls and nether brick fences beside a piece, and a wall under a piece.
     * Re-applied on the next tick; a piece wall under a vanilla wall that changed is
     * re-shaped too, since it takes its tall sides and post from it.
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onVanillaJoinPhysics(org.bukkit.event.block.BlockPhysicsEvent event) {
        Block block = event.getBlock();
        Material type = block.getType();
        boolean wall = Tag.WALLS.isTagged(type);
        if (!BARS.contains(type) && !wall && type != Material.NETHER_BRICK_FENCE) {
            return;
        }
        boolean beside = wall && PieceIndex.mayContain(block.getRelative(BlockFace.UP));
        for (BlockFace side : HORIZONTAL) {
            beside |= PieceIndex.mayContain(block.getRelative(side));
        }
        // Once per block per tick: a block gets a physics event from each neighbour that
        // changes, and a task for each piled up.
        if (beside && pendingJoins.add(block)) {
            Schedulers.atLocation(plugin, block.getLocation(), () -> {
                pendingJoins.remove(block);
                applyVanillaJoins(block);
            });
        }
        Block below = block.getRelative(BlockFace.DOWN);
        if (wall && PieceIndex.mayContain(below) && pendingReshapes.add(below)) {
            Schedulers.atLocation(plugin, below.getLocation(), () -> {
                pendingReshapes.remove(below);
                reshape(below);
            });
        }
    }

    private static final Set<Block> pendingJoins = ConcurrentHashMap.newKeySet();
    private static final Set<Block> pendingReshapes = ConcurrentHashMap.newKeySet();

    /** Joins a vanilla block to the pieces beside (and, for a wall, above) it. */
    static void applyVanillaJoins(Block block) {
        Material type = block.getType();
        if (BARS.contains(type)) {
            applyBars(block);
        } else if (Tag.WALLS.isTagged(type)) {
            applyWall(block);
        } else if (type == Material.NETHER_BRICK_FENCE) {
            applyFence(block);
        }
    }

    /**
     * Sets a vanilla wall's sides toward pieces, and its tall sides and post from a piece
     * above, by the same rules the piece walls follow (connections). Vanilla can't see
     * furniture, so on its own it never joins a piece wall, and it took a piece wall above
     * it for nothing. Sides toward real blocks keep vanilla's answer; a side toward a cell
     * with pieces that don't join is cut.
     */
    static void applyWall(Block block) {
        applyWall(block, false);
    }

    /**
     * {@code aboveChanged}: what is above just changed - a piece above came or went, or a
     * wall above was re-shaped - so its tall sides and post are worked out again here, as
     * vanilla wasn't told. Only over air or a wall: under any other block vanilla's own
     * answer, from its shape update, stands.
     */
    private static void applyWall(Block block, boolean aboveChanged) {
        if (!(block.getBlockData() instanceof org.bukkit.block.data.type.Wall data)) {
            return;
        }
        Block above = block.getRelative(BlockFace.UP);
        boolean pieceAbove = !platesIn(above).isEmpty();
        boolean fromAbove = pieceAbove || aboveChanged && (above.getType().isAir()
                || above.getBlockData() instanceof org.bukkit.block.data.type.Wall);
        BlockFace[] order = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};
        int[] height = new int[4];
        boolean changed = false;
        for (int i = 0; i < 4; i++) {
            BlockFace side = order[i];
            List<Plate> pieces = platesIn(block.getRelative(side));
            boolean joined;
            if (!pieces.isEmpty()) {
                joined = false;
                for (Plate plate : pieces) {
                    joined |= wallJoins(plate, side.getOppositeFace());
                }
            } else {
                joined = data.getHeight(side) != org.bukkit.block.data.type.Wall.Height.NONE;
            }
            org.bukkit.block.data.type.Wall.Height h = !joined
                    ? org.bukkit.block.data.type.Wall.Height.NONE
                    // Toward a piece, or under one, tall and low are this plugin's call;
                    // otherwise vanilla's own answer stands.
                    : (!pieces.isEmpty() || fromAbove)
                            ? (coversSide(above, side) ? org.bukkit.block.data.type.Wall.Height.TALL
                                                       : org.bukkit.block.data.type.Wall.Height.LOW)
                            : data.getHeight(side);
            height[i] = switch (h) {
                case NONE -> 0;
                case LOW -> 1;
                case TALL -> 2;
            };
            if (data.getHeight(side) != h) {
                data.setHeight(side, h);
                changed = true;
            }
        }
        if (changed || fromAbove) {
            boolean up = raisesPost(height, above);
            if (data.isUp() != up) {
                data.setUp(up);
                changed = true;
            }
        }
        if (changed) {
            // Without physics. With it, vanilla's shape update set the walls beside back to
            // its own answer, they were set again, which set this one back - every tick,
            // and multiplying, until the server stopped responding (walls in a row with
            // pieces on top). Nothing beside a wall depends on its shape; only the wall
            // below does, for its tall sides and post, so the change goes down by hand.
            block.setBlockData(data, false);
            passDown(block.getRelative(BlockFace.DOWN));
        }
    }

    /** The wall below a wall that changed - vanilla or a piece - takes its new shape. */
    private static void passDown(Block below) {
        if (below.getBlockData() instanceof org.bukkit.block.data.type.Wall) {
            applyWall(below, true);
        } else if (PieceIndex.mayContain(below)) {
            reshape(below);
        }
    }

    private static boolean wallJoins(Plate plate, BlockFace face) {
        return switch (plate.kind()) {
            // Vanilla walls join walls and bars (panes) - and so the piece ones.
            case WALL, PANE, DOUBLE -> true;
            default -> plate.solid().contains(face);
        };
    }

    /**
     * Sets a vanilla nether brick fence's sides toward pieces: the piece fences are all
     * nether brick, which joins only nether brick, and any full face. As for bars, a side
     * toward real blocks is left to vanilla.
     */
    static void applyFence(Block block) {
        if (!(block.getBlockData() instanceof org.bukkit.block.data.MultipleFacing data)) {
            return;
        }
        boolean changed = false;
        for (BlockFace side : HORIZONTAL) {
            List<Plate> pieces = platesIn(block.getRelative(side));
            if (pieces.isEmpty()) {
                continue;
            }
            boolean join = false;
            for (Plate plate : pieces) {
                join |= plate.kind() == Kind.FENCE || plate.kind() == Kind.DOUBLE
                        || plate.solid().contains(side.getOppositeFace());
            }
            if (data.hasFace(side) != join) {
                data.setFace(side, join);
                changed = true;
            }
        }
        if (changed) {
            block.setBlockData(data, false);
        }
    }

    /** A real block placed or broken beside a furniture wall or fence changes what it joins. */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!firing.get()) {
            Block block = event.getBlockPlaced();
            Schedulers.atLocation(plugin, block.getLocation(), () -> reshapeAround(block));
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Schedulers.atLocation(plugin, block.getLocation(), () -> reshapeAround(block));
    }

    // --- furniture walls and fences: vanilla's connections --------------------

    /**
     * The variant for a furniture wall or fence in a cell, by vanilla's WallBlock: each
     * side 0 (not joined), 1 (low) or 2 (tall - a wall's side, when the block above
     * covers it), and for a wall p0/p1 for its post.
     *
     * <p>The post follows shouldRaisePost: kept under a wall that has one; kept unless
     * the run is straight; dropped on a straight run with both sides tall; otherwise
     * kept only when the block above covers the post.
     */
    static String connections(Block cell, Kind kind) {
        BlockFace[] order = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};
        Block above = cell.getRelative(BlockFace.UP);
        int[] height = new int[4];
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            if (joins(cell, order[i], kind)) {
                height[i] = kind == Kind.WALL && coversSide(above, order[i]) ? 2 : 1;
            }
            name.append("nesw".charAt(i)).append(height[i]);
        }
        if (kind == Kind.WALL) {
            name.append("p").append(raisesPost(height, above) ? 1 : 0);
        }
        return name.toString();
    }

    private static boolean raisesPost(int[] h, Block above) {
        if (wallPostAbove(above)) {
            return true;
        }
        boolean n = h[0] == 0, e = h[1] == 0, s = h[2] == 0, w = h[3] == 0;
        if ((n && e && s && w) || n != s || e != w) {
            return true;
        }
        if ((h[0] == 2 && h[2] == 2) || (h[1] == 2 && h[3] == 2)) {
            return false;
        }
        return Tag.WALL_POST_OVERRIDE.isTagged(above.getType()) || coversPost(above);
    }

    /** A wall above with its post up - furniture or vanilla. */
    private static boolean wallPostAbove(Block above) {
        for (Plate plate : platesIn(above)) {
            if (plate.kind() == Kind.WALL) {
                return plate.furniture().currentVariant().name().endsWith("p1");
            }
        }
        return above.getBlockData() instanceof org.bukkit.block.data.type.Wall wall && wall.isUp();
    }

    /** Whether the block above covers a wall side's top (vanilla's side test shape). */
    private static boolean coversSide(Block above, BlockFace side) {
        for (Plate plate : platesIn(above)) {
            if (plate.kind() == Kind.WALL) {
                // Its own arm on that side sits right on top of this one.
                String variant = plate.furniture().currentVariant().name();
                int at = variant.indexOf("nesw".charAt(sideIndex(side)));
                return at >= 0 && variant.charAt(at + 1) != '0';
            }
            if (plate.solid().contains(BlockFace.DOWN)) {
                return true;
            }
        }
        if (above.getBlockData() instanceof org.bukkit.block.data.type.Wall wall) {
            return wall.getHeight(side) != org.bukkit.block.data.type.Wall.Height.NONE;
        }
        return above.getBlockData().isFaceSturdy(BlockFace.DOWN, BlockSupport.FULL);
    }

    /** Whether the block above covers a wall's post (vanilla's post test shape). */
    private static boolean coversPost(Block above) {
        for (Plate plate : platesIn(above)) {
            // A wall above always has its post or two arms through the centre.
            if (plate.kind() == Kind.WALL || plate.solid().contains(BlockFace.DOWN)) {
                return true;
            }
        }
        return above.getBlockData() instanceof org.bukkit.block.data.type.Wall
                || above.getBlockData().isFaceSturdy(BlockFace.DOWN, BlockSupport.FULL);
    }

    private static int sideIndex(BlockFace side) {
        return switch (side) {
            case NORTH -> 0;
            case EAST -> 1;
            case SOUTH -> 2;
            default -> 3;
        };
    }

    /**
     * Whether a wall, fence or pane reaches toward a side, by vanilla's rules: another
     * of the same kind (furniture or vanilla), walls and panes and bars to each other, a
     * fence gate (not for panes), a nether brick fence to nether brick fences, or any
     * full face - a block's, or one a furniture piece covers completely. Leaves never.
     */
    private static boolean joins(Block cell, BlockFace side, Kind kind) {
        Block next = cell.getRelative(side);
        boolean wallOrPane = kind == Kind.WALL || kind == Kind.PANE;
        for (Plate plate : platesIn(next)) {
            if (plate.kind() == kind || plate.solid().contains(side.getOppositeFace())
                    // Walls and panes join each other, as vanilla's do.
                    || (wallOrPane && (plate.kind() == Kind.WALL || plate.kind() == Kind.PANE))) {
                return true;
            }
        }
        Material type = next.getType();
        // Panes never join fence gates (vanilla's IronBarsBlock); walls and fences do.
        if (kind != Kind.PANE && Tag.FENCE_GATES.isTagged(type)) {
            return true;
        }
        if (wallOrPane && (Tag.WALLS.isTagged(type) || type == Material.IRON_BARS
                || type.name().endsWith("GLASS_PANE"))) {
            return true;
        }
        // The furniture fences are all nether brick, which joins only nether brick.
        if (kind == Kind.FENCE && Tag.FENCES.isTagged(type) && !Tag.WOODEN_FENCES.isTagged(type)) {
            return true;
        }
        if (Tag.LEAVES.isTagged(type)) {
            return false;
        }
        return next.getBlockData().isFaceSturdy(side.getOppositeFace(), BlockSupport.FULL);
    }

    /**
     * The stair yaw for a facing. The models put the tall side at model north, which
     * CraftEngine's rotateHitboxOffset (and the display, in step) turns to north at
     * yaw 0, east at 90, south at 180 and west at 270.
     */
    private static float yawForFacing(BlockFace facing) {
        return switch (facing) {
            case EAST -> 90f;
            case SOUTH -> 180f;
            case WEST -> 270f;
            default -> 0f;
        };
    }

    private static BlockFace facingForYaw(float yaw) {
        return switch (Math.floorMod(Math.round(yaw / 90f), 4)) {
            case 1 -> BlockFace.EAST;
            case 2 -> BlockFace.SOUTH;
            case 3 -> BlockFace.WEST;
            default -> BlockFace.NORTH;
        };
    }

    /**
     * The corner to wrap if the cell sits in an inside corner of two vertical slab
     * walls, else null. An L covering north and east continues a wall whose plates
     * cover north in the cell to its west, and one whose plates cover east in the
     * cell to its south; it snaps only when both are there.
     */
    private static BlockFace[] snappedCorner(Block cell) {
        for (BlockFace[] corner : CORNERS) {
            if (backCovers(cell.getRelative(corner[1].getOppositeFace()), corner[0])
                    && backCovers(cell.getRelative(corner[0].getOppositeFace()), corner[1])) {
                return corner;
            }
        }
        return null;
    }

    /** The side of the cell, across from {@code against}, that a point is on. */
    private static BlockFace sideOf(BlockFace against, Vector point, Block cell) {
        if (against == BlockFace.NORTH || against == BlockFace.SOUTH) {
            return point.getX() >= cell.getX() + 0.5 ? BlockFace.EAST : BlockFace.WEST;
        }
        return point.getZ() >= cell.getZ() + 0.5 ? BlockFace.SOUTH : BlockFace.NORTH;
    }

    /**
     * Where on the clicked face the player hit. CraftEngine's location is centred on
     * the face, so the hit comes from a fresh ray; if that ray does not reach the same
     * block (a click on other furniture), the player's look direction decides.
     */
    private static Vector hitPoint(Player player, FurnitureAttemptPlaceEvent event,
                                   Block cell, Vector look) {
        RayTraceResult ray = player.rayTraceBlocks(6.0);
        if (ray != null && event.clickedBlock().equals(ray.getHitBlock())) {
            return ray.getHitPosition();
        }
        return cell.getLocation().add(0.5, 0.5, 0.5).toVector().add(look);
    }

    /** CraftEngine's wall yaw (Direction.getYaw) back to the face it came from. */
    private static BlockFace faceForYaw(float yaw) {
        int quarter = Math.floorMod(Math.round(yaw / 90f), 4);
        return switch (quarter) {
            case 0 -> BlockFace.SOUTH;
            case 1 -> BlockFace.WEST;
            case 2 -> BlockFace.NORTH;
            default -> BlockFace.EAST;
        };
    }

    /**
     * The yaw that turns the authored L onto a corner. Authored in hitbox config space
     * as the plate at z -0.25 and the quadrant at (+0.25, +0.25); CraftEngine's
     * rotateHitboxOffset puts that at south + east for yaw 180, north + west for 0,
     * east + north for 90 and west + south for 270.
     */
    private static float yawFor(BlockFace[] corner) {
        Set<BlockFace> sides = EnumSet.of(corner[0], corner[1]);
        if (sides.containsAll(EnumSet.of(BlockFace.SOUTH, BlockFace.EAST))) {
            return 180f;
        }
        if (sides.containsAll(EnumSet.of(BlockFace.NORTH, BlockFace.WEST))) {
            return 0f;
        }
        if (sides.containsAll(EnumSet.of(BlockFace.EAST, BlockFace.NORTH))) {
            return 90f;
        }
        return 270f;
    }

    // --- right-click: waterlog, drain, double -------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onInteract(FurnitureInteractEvent event) {
        Plate plate = plate(event.furniture());
        if (plate == null) {
            return;
        }
        Player player = event.player();
        EquipmentSlot slot = event.hand() == InteractionHand.OFF_HAND
                ? EquipmentSlot.OFF_HAND : EquipmentSlot.HAND;
        ItemStack held = player.getInventory().getItem(slot);
        boolean handled = switch (held.getType()) {
            case WATER_BUCKET -> waterlog(player, plate, slot, held);
            case BUCKET -> drain(player, plate, slot, held);
            default -> {
                Location point = event.interactionPoint();
                BlockFace face = clickedFace(event.hitBox(), point, player.getEyeLocation().getDirection());
                // A slab doubles from its open side, sneaking or not, as vanilla's does:
                // a bottom slab's top, a top slab's underside. Its sides place beside it.
                if (plate.kind() == Kind.SLAB
                        && face == ("top".equals(plate.furniture().currentVariant().name())
                                ? BlockFace.DOWN : BlockFace.UP)
                        && doubleUp(player, plate, slot, held)) {
                    yield true;
                }
                // A vertical slab's top and bottom stack: the next one goes above or below.
                if (plate.kind() == Kind.VERTICAL_SLAB && face != BlockFace.UP && face != BlockFace.DOWN) {
                    if (clickedBack(plate, point) && placeBehind(player, plate, event.hand(), point)) {
                        yield true;
                    }
                    // The front always doubles, sneaking or not: otherwise CraftEngine's
                    // sneak placement puts a second plate in the open half, which looks
                    // like the full block but is two pieces of furniture. Sneaking on an
                    // edge is left to CraftEngine, so slabs can still be laid sideways.
                    if ((!player.isSneaking() || clickedFront(plate, point))
                            && doubleUp(player, plate, slot, held)) {
                        yield true;
                    }
                }
                // Anything else in hand goes against the clicked face, as vanilla places
                // against a stair or slab. Sneaking is CraftEngine's own placement,
                // which does the same.
                yield !player.isSneaking()
                        && placeAgainst(player, plate, event.hitBox(), event.hand(), point, held);
            }
        };
        if (handled) {
            event.setCancelled(true);
            player.swingHand(slot);
        }
    }

    private boolean waterlog(Player player, Plate plate, EquipmentSlot slot, ItemStack held) {
        Block cell = plate.cell();
        if (plate.kind() == Kind.DOUBLE || plate.kind() == Kind.CROP
                || recentlyHandled(player) || isWaterSource(cell)
                || !cell.getType().isAir() || waterEvaporates(cell)) {
            return false;
        }
        if (!Protection.canBuild(player, cell, held.getType())) {
            return false;
        }
        if (!allowed(new PlayerBucketEmptyEvent(player, cell, behind(plate), plate.back(),
                Material.WATER_BUCKET, held, slot))) {
            return false;
        }
        cell.setType(Material.WATER);
        if (player.getGameMode() != GameMode.CREATIVE) {
            player.getInventory().setItem(slot, new ItemStack(Material.BUCKET));
        }
        cell.getWorld().playSound(cell.getLocation().add(0.5, 0.5, 0.5),
                Sound.ITEM_BUCKET_EMPTY, SoundCategory.BLOCKS, 1f, 1f);
        markHandled(player);
        return true;
    }

    private boolean drain(Player player, Plate plate, EquipmentSlot slot, ItemStack held) {
        Block cell = plate.cell();
        if (recentlyHandled(player) || !isWaterSource(cell)) {
            return false;
        }
        if (!Protection.canBuild(player, cell, held.getType())) {
            return false;
        }
        if (!allowed(new PlayerBucketFillEvent(player, cell, behind(plate), plate.back(),
                Material.BUCKET, held, slot))) {
            return false;
        }
        cell.setType(Material.AIR);
        if (player.getGameMode() != GameMode.CREATIVE) {
            ItemStack water = new ItemStack(Material.WATER_BUCKET);
            if (held.getAmount() == 1) {
                player.getInventory().setItem(slot, water);
            } else {
                held.setAmount(held.getAmount() - 1);
                player.getInventory().addItem(water).values()
                        .forEach(rest -> player.getWorld().dropItem(player.getLocation(), rest));
            }
        }
        cell.getWorld().playSound(cell.getLocation().add(0.5, 0.5, 0.5),
                Sound.ITEM_BUCKET_FILL, SoundCategory.BLOCKS, 1f, 1f);
        markHandled(player);
        return true;
    }

    private boolean doubleUp(Player player, Plate plate, EquipmentSlot slot, ItemStack held) {
        if (held.getType().isAir() || !CraftEngineItems.isCustomItem(held)) {
            return false;
        }
        Key furnitureId = plate.furniture().id();
        if (!furnitureId.equals(CraftEngineItems.getCustomItemId(held))) {
            return false;
        }
        Key doubleId = Key.of(furnitureId.namespace(), furnitureId.value() + DOUBLE_SUFFIX);
        // A block or furniture, whichever the pack defines (vertical-slabs.doubles).
        boolean asBlock = CraftEngineBlocks.byId(doubleId) != null;
        if (!asBlock && CraftEngineFurniture.byId(doubleId) == null) {
            return false;
        }
        Block cell = plate.cell();
        // Only a lone slab in an otherwise empty (or water-filled) cell becomes a
        // full block, and never on top of a mob or player standing in its open half.
        if (!(cell.getType().isAir() || cell.getType() == Material.WATER)
                || platesIn(cell).size() != 1 || occupied(cell)) {
            return false;
        }
        if (!Protection.canBuild(player, cell, held.getType())) {
            return false;
        }
        if (!allowed(new BlockPlaceEvent(cell, cell.getState(), behind(plate), held,
                player, true, slot))) {
            return false;
        }
        Location anchor = plate.furniture().location();
        String variant = plate.furniture().currentVariant().name();
        CraftEngineFurniture.remove(plate.furniture(), false, false);
        boolean placed;
        if (asBlock) {
            placed = CraftEngineBlocks.place(cell.getLocation(), doubleId, true);
        } else {
            // A full cube holds no water, block or not.
            if (cell.getType() == Material.WATER) {
                cell.setType(Material.AIR);
            }
            placed = spawn(cell.getLocation().add(0.5, 0, 0.5),
                    doubleId, "ground", true) != null;
        }
        if (!placed) {
            // Put the slab back where it was rather than lose it.
            spawn(anchor, furnitureId, variant);
            return false;
        }
        if (player.getGameMode() != GameMode.CREATIVE) {
            held.setAmount(held.getAmount() - 1);
        }
        return true;
    }

    /** Whether a click landed on the plate's back face, the one against the next cell. */
    private static boolean clickedBack(Plate plate, Location point) {
        if (plate.back() == null) {
            return false;
        }
        Block cell = plate.cell();
        return switch (plate.back()) {
            case NORTH -> Math.abs(point.getZ() - cell.getZ()) < EPSILON;
            case SOUTH -> Math.abs(point.getZ() - (cell.getZ() + 1)) < EPSILON;
            case WEST -> Math.abs(point.getX() - cell.getX()) < EPSILON;
            case EAST -> Math.abs(point.getX() - (cell.getX() + 1)) < EPSILON;
            default -> false;
        };
    }

    /** Whether a click landed on the plate's front face, mid-cell and parallel to its back. */
    private static boolean clickedFront(Plate plate, Location point) {
        if (plate.back() == null) {
            return false;
        }
        Block cell = plate.cell();
        return switch (plate.back()) {
            case NORTH, SOUTH -> Math.abs(point.getZ() - (cell.getZ() + 0.5)) < EPSILON;
            case WEST, EAST -> Math.abs(point.getX() - (cell.getX() + 0.5)) < EPSILON;
            default -> false;
        };
    }

    /**
     * Places the held vertical slab against the clicked one's back, through
     * CraftEngine's own furniture placement: the same call it makes for a sneaking
     * click, so collision, protection and the place event are all CraftEngine's.
     *
     * <p>A wall slab anchors at the clicked point and faces the clicked face, so a hit
     * on the back face (snapped exactly onto the cell boundary) puts the new plate in
     * the next cell with its back against this one's.
     */
    private boolean placeBehind(Player player, Plate plate, InteractionHand hand, Location point) {
        BukkitServerPlayer user = BukkitAdaptor.adapt(player);
        if (user == null) {
            return false;
        }
        Optional<ItemDefinition> definition = user.getItemInHand(hand).getDefinition();
        if (definition.isEmpty()) {
            return false;
        }
        FurnitureItem furnitureItem = definition.get().behavior().getFirst(FurnitureItem.class);
        if (furnitureItem == null
                || !isVerticalSlab(definition.get().id())) {
            return false;
        }
        Block cell = plate.cell();
        Block behind = cell.getRelative(plate.back());
        if (!(behind.getType().isAir() || behind.getType() == Material.WATER)) {
            return false;
        }
        double x = point.getX();
        double z = point.getZ();
        switch (plate.back()) {
            case NORTH -> z = cell.getZ();
            case SOUTH -> z = cell.getZ() + 1;
            case WEST -> x = cell.getX();
            case EAST -> x = cell.getX() + 1;
            default -> { }
        }
        BlockHitResult hit = new BlockHitResult(new Vec3d(x, point.getY(), z),
                DirectionUtils.toDirection(plate.back()),
                new BlockPos(cell.getX(), cell.getY(), cell.getZ()), false);
        return ((ItemBehavior) furnitureItem).useOnBlock(new UseOnContext(user, hand, hit)).success();
    }

    /**
     * Places what the player holds against the clicked face of a piece: the next cell
     * over, as vanilla places against any block. CraftEngine only does this for a
     * sneaking click, so a normal click on a piece placed nothing.
     *
     * <p>A furniture item (another vertical slab, a horizontal stair) goes through
     * CraftEngine's own furniture placement, as its sneak path does. A block goes
     * through vanilla's use-item-on: like CraftEngine, the piece's cell (air or water to
     * the server) is a cobweb for the duration of the call, so vanilla places the block
     * beside it instead of into it, then the cell is put back.
     */
    private boolean placeAgainst(Player player, Plate plate, FurnitureHitBox hitBox,
                                 InteractionHand hand, Location point, ItemStack held) {
        if (held.getType().isAir()) {
            return false;
        }
        BlockFace face = clickedFace(hitBox, point, player.getEyeLocation().getDirection());
        BukkitServerPlayer user = BukkitAdaptor.adapt(player);
        if (face == null || user == null) {
            return false;
        }
        Block cell = plate.cell();
        // A wall or fence collides 1.5 high, as vanilla's does, so its top hitbox reaches
        // half a block into the cell above, past the model. That part is what a player's
        // crosshair meets when aiming at the top of the wall, and every face of it means
        // "on top". Taken face by face, its sides placed a piece beside the wall below,
        // or nothing when that cell was already the next wall along.
        if ((plate.kind() == Kind.WALL || plate.kind() == Kind.FENCE)
                && point.getY() >= cell.getY() + 1 - 1e-3) {
            face = BlockFace.UP;
        }
        Vec3d at = new Vec3d(point.getX(), point.getY(), point.getZ());
        Optional<ItemDefinition> definition = user.getItemInHand(hand).getDefinition();
        FurnitureItem furnitureItem = definition
                .map(d -> d.behavior().getFirst(FurnitureItem.class)).orElse(null);
        if (furnitureItem != null) {
            BlockHitResult hit = new BlockHitResult(at, DirectionUtils.toDirection(face),
                    new BlockPos(cell.getX(), cell.getY(), cell.getZ()), false);
            return ((ItemBehavior) furnitureItem).useOnBlock(new UseOnContext(user, hand, hit)).success();
        }
        // Blocks only - CraftEngine's own block items included. Other items keep what
        // they did before: nothing.
        if (!held.getType().isBlock() && !CraftEngineItems.isCustomItem(held)) {
            return false;
        }
        Object nmsPlayer = user.minecraftPlayer();
        Object level = ServerPlayerProxy.INSTANCE.getLevel(nmsPlayer);
        Object pos = LocationUtils.toBlockPos(cell.getX(), cell.getY(), cell.getZ());
        Object previous = ServerLevelProxy.INSTANCE.getBlockState(level, pos);
        Object packet = ServerboundUseItemOnPacketProxy.INSTANCE.newInstance(
                hand == InteractionHand.OFF_HAND ? InteractionHandProxy.OFF_HAND : InteractionHandProxy.MAIN_HAND,
                BlockHitResultProxy.INSTANCE.newInstance(
                        Vec3Proxy.INSTANCE.newInstance(at.x(), at.y(), at.z()),
                        DirectionUtils.toNMSDirection(DirectionUtils.toDirection(face)), pos, false),
                0);
        try {
            ServerLevelProxy.INSTANCE.setBlock(level, pos,
                    BlockProxy.INSTANCE.getDefaultBlockState(BlocksProxy.COBWEB), UpdateFlags.UPDATE_INVISIBLE);
            ServerboundUseItemOnPacketProxy.INSTANCE.setTimestamp(packet, System.currentTimeMillis());
            ServerGamePacketListenerImplProxy.INSTANCE.handleUseItemOn(
                    ServerPlayerProxy.INSTANCE.getConnection(nmsPlayer), packet);
        } finally {
            ServerLevelProxy.INSTANCE.setBlock(level, pos, previous, UpdateFlags.UPDATE_INVISIBLE);
            user.sendPacket(ClientboundBlockUpdatePacketProxy.INSTANCE.newInstance$1(level, pos), false);
        }
        return true;
    }

    /**
     * The face of the clicked collider box the click landed on: of the box faces the
     * point lies on, the one turned most towards the player.
     */
    private static BlockFace clickedFace(FurnitureHitBox hitBox, Location point, Vector look) {
        double x = point.getX();
        double y = point.getY();
        double z = point.getZ();
        double t = 1e-3;
        BlockFace best = null;
        double bestDot = Double.MAX_VALUE;
        for (int i = 0; i < hitBox.partCount(); i++) {
            var b = hitBox.part(i).aabb();
            boolean inX = x >= b.minX - t && x <= b.maxX + t;
            boolean inY = y >= b.minY - t && y <= b.maxY + t;
            boolean inZ = z >= b.minZ - t && z <= b.maxZ + t;
            if (!(inX && inY && inZ)) {
                continue;
            }
            BlockFace[] faces = {BlockFace.WEST, BlockFace.EAST, BlockFace.DOWN, BlockFace.UP,
                                 BlockFace.NORTH, BlockFace.SOUTH};
            double[] gaps = {x - b.minX, b.maxX - x, y - b.minY, b.maxY - y, z - b.minZ, b.maxZ - z};
            for (int f = 0; f < faces.length; f++) {
                if (Math.abs(gaps[f]) > t) {
                    continue;
                }
                double dot = faces[f].getDirection().dot(look);
                if (dot < bestDot) {
                    bestDot = dot;
                    best = faces[f];
                }
            }
        }
        return best;
    }

    private static boolean occupied(Block cell) {
        for (Entity entity : cell.getWorld().getNearbyEntities(cell.getBoundingBox())) {
            if (entity instanceof LivingEntity
                    && !CraftEngineFurniture.isCollisionEntity(entity)
                    && !CraftEngineFurniture.isFurniture(entity)) {
                return true;
            }
        }
        return false;
    }

    private static Block behind(Plate plate) {
        return plate.back() == null ? plate.cell() : plate.cell().getRelative(plate.back());
    }

    /**
     * Whether a water bucket evaporates here (the Nether). Vanilla now reads this from
     * the gameplay/water_evaporates environment attribute, which the Paper API does not
     * expose yet; isUltraWarm is the deprecated view of the same thing.
     */
    @SuppressWarnings("deprecation")
    private static boolean waterEvaporates(Block cell) {
        return cell.getWorld().isUltraWarm();
    }

    private static boolean isWaterSource(Block cell) {
        return cell.getType() == Material.WATER
                && cell.getBlockData() instanceof Levelled level && level.getLevel() == 0;
    }

    /** Fires a protection check (region plugins listen for these) and reports the verdict. */
    private boolean allowed(org.bukkit.event.Cancellable event) {
        firing.set(true);
        try {
            Bukkit.getPluginManager().callEvent((org.bukkit.event.Event) event);
        } finally {
            firing.set(false);
        }
        return !event.isCancelled()
                && !(event instanceof BlockPlaceEvent place && !place.canBuild());
    }

    // --- the guard against a second, vanilla bucket use ---------------------

    private void markHandled(Player player) {
        bucketHandledAt.put(player.getUniqueId(), Bukkit.getCurrentTick());
    }

    private boolean recentlyHandled(Player player) {
        Integer at = bucketHandledAt.get(player.getUniqueId());
        return at != null && Bukkit.getCurrentTick() - at <= BUCKET_GUARD_TICKS;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (!firing.get() && recentlyHandled(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (!firing.get() && recentlyHandled(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        bucketHandledAt.remove(event.getPlayer().getUniqueId());
    }
}
