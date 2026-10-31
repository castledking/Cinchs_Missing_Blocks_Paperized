import com.mojang.authlib.GameProfile;
import java.lang.reflect.Method;
import java.util.*;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.api.event.FurnitureAttemptPlaceEvent;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.core.entity.furniture.FurnitureDefinition;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/**
 * Which half a furniture stair or slab takes when placed against a side, by vanilla's rule
 * (StairBlock and SlabBlock.getStateForPlacement): the top half only when the click landed
 * above the middle of the side. A click on a piece must count where it landed on the
 * piece, as a click on a block does. A horizontal stair placed against a side wraps the
 * half of the side that was hit.
 *
 * <p>No client is needed. A server-side player is aimed at a point and fires
 * FurnitureAttemptPlaceEvent as CraftEngine's FurnitureItemBehavior does for that click:
 * the anchor's variant, a location turned to the clicked face, and the block at the
 * clicked position. That block is the piece's own cell for a normal click on a piece,
 * which CMB hands to CraftEngine, and the cell the hit point falls in for a sneaking
 * click, which CraftEngine handles itself. Each case has its own spot in the air.
 */
public class PlacementProbe extends JavaPlugin {
    static final int Y = 100, X0 = 2000, Z0 = 2000, STEP = 6, SPOTS = 24;
    static final String STAIRS = "deepslate_stairs", SLAB = "deepslate_slab", DOUBLE = "deepslate_slab_double",
        HORIZONTAL = "deepslate_horizontal_stairs";
    World world;
    ServerPlayer handle;
    Player player;
    Method spawn;
    int checks, failures, spots, waits;
    final List<Runnable> cases = new ArrayList<>();

    void check(boolean ok, String s) {
        checks++;
        if (!ok) {
            failures++;
            getLogger().warning("[PLACETEST] FAIL " + s);
        } else
            getLogger().info("[PLACETEST] PASS " + s);
    }

    void later(Runnable r, int ticks) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            try {
                r.run();
            } catch (Throwable e) {
                getLogger().log(java.util.logging.Level.SEVERE, "[PLACETEST] error", e);
                getLogger().warning("[PLACETEST] FINISHED failures=error");
                Bukkit.shutdown();
            }
        }, ticks);
    }

    @Override
    public void onEnable() {
        try {
            ClassLoader cl = Bukkit.getPluginManager().getPlugin("CMB").getClass().getClassLoader();
            Class<?> v = cl.loadClass("net.cinchtail.cinchsmissingblocks.cmb.VerticalSlabListener");
            spawn = v.getDeclaredMethod("spawn", Location.class, Key.class, String.class, boolean.class);
            spawn.setAccessible(true);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        later(this::setup, 100);
    }

    static Key key(String id) {
        return Key.of("cinchsmissingblocks:" + id);
    }

    /** A fresh spot in the air: the cell a case's first piece or block goes in. */
    Block spot() {
        return world.getBlockAt(X0 + STEP * spots++, Y, Z0);
    }

    void piece(Block cell, String id, String variant, float yaw) {
        Location at = cell.getLocation().add(.5, 0, .5);
        at.setYaw(yaw);
        try {
            if (spawn.invoke(null, at, key(id), variant, false) == null)
                throw new IllegalStateException("no piece " + id + "/" + variant);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    /** Puts the player's eye at {@code eye}, looking at {@code at}. */
    void aim(Vector eye, Vector at) {
        Location look = new Location(world, eye.getX(), eye.getY(), eye.getZ());
        look.setDirection(at.clone().subtract(eye));
        handle.snapTo(eye.getX(), eye.getY() - handle.getEyeHeight(), eye.getZ(), look.getYaw(), look.getPitch());
        handle.setYHeadRot(look.getYaw());
    }

    /** CraftEngine's Direction.getYaw, the yaw of a wall anchor placed against a face. */
    static float yawOf(BlockFace face) {
        return switch (face) {
            case WEST -> 90f;
            case NORTH -> 180f;
            case EAST -> 270f;
            default -> 0f;
        };
    }

    /**
     * The event CraftEngine fires for a click at {@code at} on the {@code face} side of
     * what is there, holding {@code id}, with {@code clicked} as the clicked block.
     */
    void click(String id, BlockFace face, Block clicked, Vector at) {
        player.getInventory().setItemInMainHand(CraftEngineItems.byId(key(id)).buildBukkitItem());
        FurnitureDefinition definition = CraftEngineFurniture.byId(key(id));
        String anchor = face == BlockFace.UP ? "ground" : face == BlockFace.DOWN ? "ceiling" : "wall";
        Location location = new Location(world, at.getX(), at.getY(), at.getZ(), yawOf(face), 0);
        Bukkit.getPluginManager().callEvent(new FurnitureAttemptPlaceEvent(player, definition,
            definition.getVariant(anchor), location, InteractionHand.MAIN_HAND, clicked));
    }

    /** The piece in a cell, by its display entity at the cell's floor centre, or null. */
    BukkitFurniture placed(Block cell) {
        for (Entity e : world.getNearbyEntities(BoundingBox.of(cell).expand(0.01)))
            if (CraftEngineFurniture.isFurniture(e)) {
                BukkitFurniture f = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(e);
                if (f != null)
                    return f;
            }
        return null;
    }

    /** "top" or "bottom", the half of the slab or stair in a cell, or "nothing". */
    String half(Block cell) {
        BukkitFurniture f = placed(cell);
        return f == null ? "nothing" : f.currentVariant().name().split("_")[0];
    }

    void setup() {
        world = Bukkit.getWorlds().getFirst();
        if (CraftEngineFurniture.byId(key(STAIRS)) == null && waits++ < 15) {
            later(this::setup, 40);
            return;
        }
        for (String id : List.of(STAIRS, SLAB, DOUBLE, HORIZONTAL))
            check(CraftEngineFurniture.byId(key(id)) != null, "piece " + id + " is loaded");
        for (int cx = (X0 - 8) >> 4; cx <= (X0 + STEP * SPOTS + 8) >> 4; cx++)
            for (int cz = (Z0 - 8) >> 4; cz <= (Z0 + 8) >> 4; cz++)
                world.getChunkAt(cx, cz).addPluginChunkTicket(this);
        for (int x = X0 - 4; x <= X0 + STEP * SPOTS + 4; x++)
            for (int z = Z0 - 4; z <= Z0 + 4; z++)
                for (int y = Y - 3; y <= Y + 3; y++)
                    world.getBlockAt(x, y, z).setType(Material.AIR, false);
        handle = new ServerPlayer(((CraftServer) Bukkit.getServer()).getServer(), ((CraftWorld) world).getHandle(),
            new GameProfile(UUID.randomUUID(), "PlacementProbe"), ClientInformation.createDefault());
        player = handle.getBukkitEntity();

        cases.add(() -> {
            // The harness itself: the player's eye and look are where the probe put them.
            Vector eye = new Vector(X0 + .5, Y + 1.62, Z0 - 3.5), at = new Vector(X0 + .5, Y + .5, Z0 + .5);
            aim(eye, at);
            Location seen = player.getEyeLocation();
            check(seen.toVector().distance(eye) < 1e-6
                    && seen.getDirection().dot(at.clone().subtract(eye).normalize()) > 0.99999,
                "the server-side player looks from where it is aimed");
        });

        // A stair against a stair's side, as reported: above the side's middle it is the
        // top half, below it the bottom half, wherever the eye is.
        cases.add(() -> stairBeside("upper half, from above", .75, 1.62, false, "top"));
        cases.add(() -> stairBeside("upper half, from above, sneaking", .75, 1.62, true, "top"));
        cases.add(() -> stairBeside("lower half, from below", .25, -.6, false, "bottom"));
        cases.add(() -> stairBeside("lower half, from below, sneaking", .25, -.6, true, "bottom"));
        cases.add(() -> stairBeside("upper half, from below (control)", .75, -.6, false, "top"));
        cases.add(() -> stairBeside("lower half, from above (control)", .25, 1.62, false, "bottom"));

        cases.add(() -> {
            // The riser of a bottom stair facing north: the stair goes in front of it,
            // and the riser is all above the middle.
            Block p = spot();
            piece(p, STAIRS, "bottom_straight", 0f);
            Vector at = new Vector(p.getX() + .5, Y + .75, p.getZ() + .5);
            aim(new Vector(p.getX() + .5, Y + 1.62, p.getZ() + 3), at);
            click(STAIRS, BlockFace.SOUTH, p, at);
            String got = half(p.getRelative(BlockFace.SOUTH));
            check("top".equals(got), "stair against a stair's riser: top, got " + got);
        });

        cases.add(() -> slabBeside("against a top slab's side", "top", .75, "top"));
        cases.add(() -> slabBeside("against a bottom slab's side (control)", "bottom", .25, "bottom"));

        // Against a real block the hit comes from the block ray, as before.
        cases.add(() -> stairBesideBlock("upper half, from above (control)", .75, 1.62, "top"));
        cases.add(() -> stairBesideBlock("lower half, from below (control)", .25, -.6, "bottom"));
        cases.add(() -> {
            // Vanilla's top half is strictly above the middle: a level look at the
            // middle line itself gives the bottom half.
            Block p = spot();
            p.setType(Material.STONE, false);
            double middle = Y + .5, height = handle.getEyeHeight(), feet = middle - height;
            for (int i = 0; i < 64 && feet + height != middle; i++)
                feet = feet + height < middle ? Math.nextUp(feet) : Math.nextDown(feet);
            handle.snapTo(p.getX() + 3.5, feet, p.getZ() + .5, 90f, 0f);
            handle.setYHeadRot(90f);
            Vector at = new Vector(p.getX() + 1, middle, p.getZ() + .5);
            click(STAIRS, BlockFace.EAST, p, at);
            String got = half(p.getRelative(BlockFace.EAST));
            check(player.getEyeLocation().getY() == middle && "bottom".equals(got),
                "stair against a block's side, exactly at its middle: bottom, got " + got
                    + " (eye y " + player.getEyeLocation().getY() + ")");
        });

        // The other anchors never look at the hit point.
        cases.add(() -> {
            Block p = spot();
            piece(p, STAIRS, "bottom_straight", 0f);
            Vector at = new Vector(p.getX() + .5, Y + 1, p.getZ() + .25);
            aim(new Vector(p.getX() + .5, Y + 2.5, p.getZ() + 2.5), at);
            click(STAIRS, BlockFace.UP, p, at);
            String got = half(p.getRelative(BlockFace.UP));
            check("bottom".equals(got), "stair on top of a stair (control): bottom, got " + got);
        });
        cases.add(() -> {
            Block p = spot();
            piece(p, SLAB, "top", 0f);
            Vector at = new Vector(p.getX() + .5, Y + .5, p.getZ() + .5);
            aim(new Vector(p.getX() + .5, Y - 1.5, p.getZ() + 2.5), at);
            click(STAIRS, BlockFace.DOWN, p, at);
            String got = half(p.getRelative(BlockFace.DOWN));
            check("top".equals(got), "stair under a top slab (control): top, got " + got);
        });

        // A horizontal stair against a piece's side wraps the half of the side that was hit.
        cases.add(() -> horizontalBeside("north half: wraps west and north", .25, 0));
        cases.add(() -> horizontalBeside("south half (control): wraps west and south", .75, 270));

        next();
    }

    void next() {
        if (cases.isEmpty()) {
            getLogger().info("[PLACETEST] FINISHED failures=" + failures + " checks=" + checks);
            Bukkit.shutdown();
            return;
        }
        cases.removeFirst().run();
        // CMB takes one placement per player every 100 ms.
        later(this::next, 5);
    }

    /**
     * A stair against the east side of a bottom stair facing north, at {@code hitY} up
     * the side, the eye {@code eyeY} above the stair's floor. Above the middle the side
     * is the upper step, the north half; below it, the lower step, the whole width.
     */
    void stairBeside(String name, double hitY, double eyeY, boolean sneaking, String want) {
        Block p = spot();
        piece(p, STAIRS, "bottom_straight", 0f);
        double z = hitY > .5 ? .25 : .75;
        Vector at = new Vector(p.getX() + 1, Y + hitY, p.getZ() + z);
        aim(new Vector(p.getX() + 3.5, Y + eyeY, p.getZ() + z), at);
        Block clicked = sneaking ? world.getBlockAt(at.getBlockX(), at.getBlockY(), at.getBlockZ()) : p;
        click(STAIRS, BlockFace.EAST, clicked, at);
        String got = half(p.getRelative(BlockFace.EAST));
        check(want.equals(got), "stair against a stair's side, " + name + ": " + want + ", got " + got);
    }

    void slabBeside(String name, String variant, double hitY, String want) {
        Block p = spot();
        piece(p, SLAB, variant, 0f);
        Vector at = new Vector(p.getX() + 1, Y + hitY, p.getZ() + .5);
        aim(new Vector(p.getX() + 3.5, Y + 1.62, p.getZ() + .5), at);
        click(SLAB, BlockFace.EAST, p, at);
        String got = half(p.getRelative(BlockFace.EAST));
        check(want.equals(got), "slab " + name + ": " + want + ", got " + got);
    }

    void stairBesideBlock(String name, double hitY, double eyeY, String want) {
        Block p = spot();
        p.setType(Material.STONE, false);
        Vector at = new Vector(p.getX() + 1, Y + hitY, p.getZ() + .5);
        aim(new Vector(p.getX() + 3.5, Y + eyeY, p.getZ() + .5), at);
        click(STAIRS, BlockFace.EAST, p, at);
        String got = half(p.getRelative(BlockFace.EAST));
        check(want.equals(got), "stair against a block's side, " + name + ": " + want + ", got " + got);
    }

    /** Looking straight at the side, so only the hit point, not the look, tells the halves apart. */
    void horizontalBeside(String name, double z, int wantYaw) {
        if (CraftEngineFurniture.byId(key(HORIZONTAL)) == null) {
            check(false, "horizontal stair against a piece's side, " + name + ": no " + HORIZONTAL);
            return;
        }
        Block p = spot();
        piece(p, DOUBLE, "ground", 0f);
        Vector at = new Vector(p.getX() + 1, Y + .5, p.getZ() + z);
        aim(new Vector(p.getX() + 3.5, Y + 1.62, p.getZ() + z), at);
        click(HORIZONTAL, BlockFace.EAST, p, at);
        BukkitFurniture f = placed(p.getRelative(BlockFace.EAST));
        Integer yaw = f == null ? null : Math.floorMod(Math.round(f.location().getYaw()), 360);
        check(yaw != null && yaw == wantYaw,
            "horizontal stair against a piece's side, " + name + ": yaw " + wantYaw + ", got " + yaw);
    }
}
