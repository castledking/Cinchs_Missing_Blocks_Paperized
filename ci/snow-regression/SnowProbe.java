import java.lang.reflect.Method;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.Snowable;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.block.data.CraftBlockData;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.bukkit.entity.Snowman;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Snow and ice against CMB pieces, through vanilla's own code: ServerLevel.tickPrecipitation
 * for weather (the MOTION_BLOCKING heightmap, Biome.shouldSnow/shouldFreeze, BlockFormEvent)
 * and CraftEventFactory.handleBlockFormEvent with a source entity for a snow golem's trail
 * and frost walker (EntityBlockFormEvent). Every case is its own column, open to the sky.
 */
public class SnowProbe extends JavaPlugin {
    static final int Y = 100, X0 = 1000, Z0 = 1000, REPAIR_X = 1200;
    static final String SLAB = "deepslate_slab", STAIRS = "deepslate_stairs", WALL = "deepslate_wall",
        DOUBLE = "deepslate_slab_double";
    World world;
    ServerLevel level;
    Method spawn, clearForPlacement;
    Snowman golem;
    int checks, failures;
    final Map<String, Block> columns = new LinkedHashMap<>();

    void check(boolean ok, String s) {
        checks++;
        if (!ok) {
            failures++;
            getLogger().warning("[SNOWTEST] FAIL " + s);
        } else
            getLogger().info("[SNOWTEST] PASS " + s);
    }

    void later(Runnable r, int ticks) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            try {
                r.run();
            } catch (Throwable e) {
                getLogger().log(java.util.logging.Level.SEVERE, "[SNOWTEST] error", e);
                getLogger().warning("[SNOWTEST] FINISHED failures=error");
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
            clearForPlacement = v.getDeclaredMethod("clearForPlacement", Block.class);
            clearForPlacement.setAccessible(true);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        later(this::setup, 100);
    }

    /** The bottom cell of a case's column: the floor is one below it. */
    Block column(String name) {
        Block cell = world.getBlockAt(X0 + 3 * columns.size(), Y + 1, Z0);
        columns.put(name, cell);
        return cell;
    }

    BukkitFurniture piece(Block cell, String id, String variant) {
        try {
            BukkitFurniture f = (BukkitFurniture) spawn.invoke(null, cell.getLocation().add(.5, 0, .5),
                Key.of("cinchsmissingblocks:" + id), variant, false);
            if (f == null)
                throw new IllegalStateException("no piece " + id + "/" + variant);
            return f;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    void weather(Block cell) {
        level.tickPrecipitation(new BlockPos(cell.getX(), cell.getY(), cell.getZ()));
    }

    /** What a snow golem's trail or frost walker does: form a block with an entity as the cause. */
    void entityForm(Block cell, Material type) {
        CraftEventFactory.handleBlockFormEvent(level, new BlockPos(cell.getX(), cell.getY(), cell.getZ()),
            ((CraftBlockData) type.createBlockData()).getState(), 3, ((CraftEntity) golem).getHandle());
    }

    /** Water source in a cell walled in on four sides, so it stays a lone source. */
    void pool(Block cell) {
        for (BlockFace f : new BlockFace[] {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST})
            cell.getRelative(f).setType(Material.STONE, false);
        cell.setType(Material.WATER, false);
    }

    /** Snow anywhere in a column from its bottom cell up, as "y=..." offsets. */
    List<Integer> snowIn(Block cell) {
        List<Integer> found = new ArrayList<>();
        for (int dy = 0; dy < 12; dy++)
            if (cell.getRelative(0, dy, 0).getType() == Material.SNOW)
                found.add(dy);
        return found;
    }

    void prepare(int fromX, int toX) {
        for (int cx = (fromX - 8) >> 4; cx <= (toX + 8) >> 4; cx++)
            for (int cz = (Z0 - 8) >> 4; cz <= (Z0 + 8) >> 4; cz++)
                world.getChunkAt(cx, cz).addPluginChunkTicket(this);
        for (int x = fromX - 8; x <= toX + 8; x++)
            for (int z = Z0 - 8; z <= Z0 + 8; z++) {
                for (int y = Y - 8; y <= Y + 16; y++)
                    world.setBiome(x, y, z, Biome.SNOWY_PLAINS);
                for (int y = Y; y <= Y + 12; y++)
                    world.getBlockAt(x, y, z).setType(Material.AIR, false);
            }
    }

    void setup() {
        world = Bukkit.getWorlds().getFirst();
        level = ((CraftWorld) world).getHandle();
        for (String id : List.of(SLAB, STAIRS, WALL, DOUBLE))
            check(CraftEngineFurniture.byId(Key.of("cinchsmissingblocks:" + id)) != null, "piece " + id + " is loaded");
        prepare(X0, X0 + 3 * 20);
        prepare(REPAIR_X, REPAIR_X + 3);
        world.getBlockAt(X0 - 5, Y, Z0 + 5).setType(Material.BARRIER, false);
        golem = world.spawn(world.getBlockAt(X0 - 5, Y + 1, Z0 + 5).getLocation().add(.5, 0, .5), Snowman.class,
            s -> s.setAI(false));

        // Weather columns: a stone floor, open sky.
        String[][] weatherCases = {
            {"open column (control)", null, null, "0"},
            {"piece beside the column (control)", SLAB, "bottom", "beside"},
            {"bottom slab piece in the cell", SLAB, "bottom", "0"},
            {"top slab piece in the cell", SLAB, "top", "0"},
            {"stairs piece in the cell", STAIRS, "bottom_straight", "0"},
            {"wall piece in the cell", WALL, "n0e0s0w0p1", "0"},
            {"double piece in the cell", DOUBLE, "ground", "0"},
            {"slab piece as a roof three cells up", SLAB, "bottom", "3"},
        };
        for (String[] c : weatherCases) {
            Block cell = column(c[0]);
            cell.getRelative(BlockFace.DOWN).setType(Material.STONE, false);
            if (c[1] != null)
                piece(c[3].equals("beside") ? cell.getRelative(BlockFace.EAST)
                                            : cell.getRelative(0, Integer.parseInt(c[3]), 0), c[1], c[2]);
        }
        Block realRoof = column("stone roof three cells up (vanilla control)");
        realRoof.getRelative(BlockFace.DOWN).setType(Material.STONE, false);
        realRoof.getRelative(0, 3, 0).setType(Material.STONE, false);

        // Freezing: a lone water source on stone.
        Block freezeOpen = column("water, open (control)");
        Block freezePiece = column("water with a slab piece in it");
        Block freezeRoof = column("water under a slab piece roof");
        for (Block cell : List.of(freezeOpen, freezePiece, freezeRoof)) {
            cell.getRelative(BlockFace.DOWN).setType(Material.STONE, false);
            pool(cell);
        }
        piece(freezePiece, SLAB, "bottom");
        piece(freezeRoof.getRelative(0, 3, 0), SLAB, "bottom");
        for (Block cell : List.of(freezeOpen, freezePiece, freezeRoof))
            weather(cell);
        check(freezeOpen.getType() == Material.ICE, "water, open (control): froze - " + freezeOpen.getType());
        check(freezePiece.getType() == Material.WATER, "water with a slab piece in it: stays water - " + freezePiece.getType());
        check(freezeRoof.getType() == Material.WATER, "water under a slab piece roof: stays water - " + freezeRoof.getType());

        // A snow golem's trail and frost walker: entity forms, held back only by a piece in the cell.
        Block golemOpen = column("golem trail, open (control)");
        Block golemPiece = column("golem trail into a slab piece");
        Block golemRoof = column("golem trail under a slab piece roof");
        Block frostOpen = column("frost walker, open water (control)");
        Block frostPiece = column("frost walker, water with a slab piece in it");
        for (Block cell : List.of(golemOpen, golemPiece, golemRoof, frostOpen, frostPiece))
            cell.getRelative(BlockFace.DOWN).setType(Material.STONE, false);
        piece(golemPiece, SLAB, "bottom");
        piece(golemRoof.getRelative(0, 3, 0), SLAB, "bottom");
        pool(frostOpen);
        pool(frostPiece);
        piece(frostPiece, SLAB, "bottom");
        for (Block cell : List.of(golemOpen, golemPiece, golemRoof))
            entityForm(cell, Material.SNOW);
        for (Block cell : List.of(frostOpen, frostPiece))
            entityForm(cell, Material.FROSTED_ICE);
        check(golemOpen.getType() == Material.SNOW, "golem trail, open (control): snow - " + golemOpen.getType());
        check(golemPiece.getType() == Material.AIR, "golem trail into a slab piece: no snow - " + golemPiece.getType());
        check(golemRoof.getType() == Material.SNOW,
            "golem trail under a slab piece roof: snow, a roof doesn't stop it - " + golemRoof.getType());
        check(frostOpen.getType() == Material.FROSTED_ICE, "frost walker, open water (control): frosted ice - " + frostOpen.getType());
        check(frostPiece.getType() == Material.WATER, "frost walker, water with a slab piece in it: stays water - " + frostPiece.getType());

        // Placing a piece into snow, as onAttemptPlace does it: spawn, then clearForPlacement.
        Block placing = column("placing a piece into snow");
        placing.getRelative(BlockFace.DOWN).setType(Material.GRASS_BLOCK, false);
        placing.setType(Material.SNOW, true);
        check(snowy(placing), "placing a piece into snow: the ground starts snowy");
        piece(placing, SLAB, "bottom");
        try {
            clearForPlacement.invoke(null, placing);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        check(placing.getType() == Material.AIR && !snowy(placing),
            "placing a piece into snow: snow and the ground's snow cleared - " + placing.getType() + " snowy=" + snowy(placing));

        world.setStorm(true);
        world.setWeatherDuration(20 * 600);
        later(this::snowfall, 80);
    }

    void snowfall() {
        check(level.isRaining(), "it is snowing");
        for (Map.Entry<String, Block> e : columns.entrySet()) {
            if (e.getKey().startsWith("water") || e.getKey().startsWith("golem") || e.getKey().startsWith("frost")
                    || e.getKey().startsWith("placing"))
                continue;
            weather(e.getValue());
        }
        Block open = columns.get("open column (control)");
        check(snowIn(open).equals(List.of(0)), "open column (control): snow on the floor - " + snowIn(open));
        Block beside = columns.get("piece beside the column (control)");
        check(snowIn(beside).equals(List.of(0)), "piece beside the column (control): snow on the floor - " + snowIn(beside));
        for (String name : List.of("bottom slab piece in the cell", "top slab piece in the cell", "stairs piece in the cell",
                 "wall piece in the cell", "double piece in the cell", "slab piece as a roof three cells up")) {
            Block cell = columns.get(name);
            check(snowIn(cell).isEmpty(), name + ": no snow in the column - " + snowIn(cell));
        }
        Block roof = columns.get("stone roof three cells up (vanilla control)");
        check(snowIn(roof).equals(List.of(4)), "stone roof (vanilla control): snow on the roof only - " + snowIn(roof));
        repairSetup();
    }

    Block repairPiece, repairOpen;

    /** Snow left in a piece's cell by an older build, and the same snow with no piece. */
    void repairSetup() {
        world.setStorm(false);
        repairPiece = world.getBlockAt(REPAIR_X, Y + 1, Z0);
        repairOpen = world.getBlockAt(REPAIR_X + 3, Y + 1, Z0);
        for (Block cell : List.of(repairPiece, repairOpen))
            cell.getRelative(BlockFace.DOWN).setType(Material.GRASS_BLOCK, false);
        piece(repairPiece, SLAB, "bottom");
        for (Block cell : List.of(repairPiece, repairOpen))
            cell.setType(Material.SNOW, true);
        check(repairPiece.getType() == Material.SNOW && snowy(repairPiece), "repair: old snow in a piece's cell, ground snowy");
        int cx = REPAIR_X >> 4, cz = Z0 >> 4;
        for (int x = cx - 1; x <= cx + 1; x++)
            for (int z = cz - 1; z <= cz + 1; z++)
                world.removePluginChunkTicket(x, z, this);
        later(() -> {
            check(world.unloadChunk(cx, cz, true), "repair: the chunk unloads");
            later(() -> {
                world.getChunkAt(cx, cz).addPluginChunkTicket(this);
                later(this::repairCheck, 80);
            }, 20);
        }, 20);
    }

    boolean snowy(Block cell) {
        return cell.getRelative(BlockFace.DOWN).getBlockData() instanceof Snowable s && s.isSnowy();
    }

    void repairCheck() {
        boolean loaded = false;
        for (var e : world.getNearbyEntities(repairPiece.getLocation().add(.5, .5, .5), 1, 1, 1))
            loaded |= CraftEngineFurniture.isFurniture(e);
        check(loaded, "repair: the piece is back after the reload");
        check(repairPiece.getType() == Material.AIR, "repair: the piece's cell is cleared of snow - " + repairPiece.getType());
        check(!snowy(repairPiece), "repair: the ground under the piece is no longer snowy");
        check(repairOpen.getType() == Material.SNOW && snowy(repairOpen),
            "repair (control): snow with no piece is kept - " + repairOpen.getType());
        getLogger().info("[SNOWTEST] FINISHED failures=" + failures + " checks=" + checks);
        Bukkit.shutdown();
    }
}
