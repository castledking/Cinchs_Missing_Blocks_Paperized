import java.lang.reflect.Method;
import java.util.*;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.util.Direction;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.type.Wall;
import org.bukkit.plugin.java.JavaPlugin;

public class GateProbe extends JavaPlugin {
    static final String[] GATES = {"nether_brick_fence_gate", "cracked_nether_brick_fence_gate",
        "red_nether_brick_fence_gate", "cracked_red_nether_brick_fence_gate", "blue_nether_brick_fence_gate",
        "cracked_blue_nether_brick_fence_gate"};
    static final Direction[] DIRS = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
    static class Case {
        Block gate, vanilla, piece, fence, wood, customFence, frontPiece, frontVanilla, frontFence, reverseGate,
            reverseVanilla, reversePiece;
        BukkitFurniture furniture, frontFurniture, reverseFurniture;
        Direction facing;
        BlockFace axis, inward, front;
        String id;
    }
    final List<Case> cases = new ArrayList<>();
    int failures, checks;
    Block reloadGate, reloadVanilla, reloadPiece, reloadVanillaOnly, reloadGateOnly, startupGate, startupWall;
    BukkitFurniture reloadFurniture;
    java.util.UUID reloadEntity;
    World world;
    Method spawn, reshape, connections;
    Object wallKind;
    void check(boolean ok, String s) {
        checks++;
        if (!ok) {
            failures++;
            getLogger().warning("[WALLTEST] FAIL " + s);
        } else
            getLogger().info("[WALLTEST] PASS " + s);
    }
    void later(Runnable r, int ticks) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            try {
                r.run();
            } catch (Throwable e) {
                getLogger().log(java.util.logging.Level.SEVERE, "[WALLTEST] error", e);
                Bukkit.shutdown();
            }
        }, ticks);
    }
    @Override
    public void onEnable() {
        try {
            ClassLoader cl = Bukkit.getPluginManager().getPlugin("CMB").getClass().getClassLoader();
            Class<?> v = cl.loadClass("net.cinchtail.cinchsmissingblocks.cmb.VerticalSlabListener"),
                     k = cl.loadClass("net.cinchtail.cinchsmissingblocks.cmb.VerticalSlabListener$Kind");
            spawn = v.getDeclaredMethod("spawn", Location.class, Key.class, String.class, boolean.class);
            spawn.setAccessible(true);
            reshape = v.getDeclaredMethod("reshapeAround", Block.class);
            reshape.setAccessible(true);
            connections = v.getDeclaredMethod("connections", Block.class, k);
            connections.setAccessible(true);
            wallKind = Arrays.stream(k.getEnumConstants())
                           .filter(o -> o.toString().equals("WALL"))
                           .findFirst()
                           .orElseThrow();
            if (java.nio.file.Files.exists(getDataFolder().toPath().resolve("restart.uuid")))
                later(this::beginReload, 90);
            else
                later(this::setup, 90);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    Block at(int x, int z) {
        return world.getBlockAt(x, 65, z);
    }
    void refresh(Block b) {
        try {
            reshape.invoke(null, b);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    BukkitFurniture wall(Block b) {
        try {
            String variant = (String) connections.invoke(null, b, wallKind);
            return (BukkitFurniture) spawn.invoke(null, b.getLocation().add(.5, 0, .5),
                Key.of("cinchsmissingblocks:soul_sandstone_wall"), variant, false);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    ImmutableBlockState state(Block b) {
        return CraftEngineBlocks.getCustomBlockState(b);
    }
    boolean prop(Block b, String name) {
        ImmutableBlockState s = state(b);
        Property<Boolean> p = s.getProperty(name);
        return s.get(p);
    }
    void gate(Block b, String id, Direction face) {
        var def = CraftEngineBlocks.byId(Key.of("cinchsmissingblocks:" + id));
        ImmutableBlockState s = def.defaultState();
        Property<Direction> facing = s.getProperty("facing");
        CraftEngineBlocks.place(b.getLocation(), s.with(facing, face), false);
        refresh(b);
    }
    void setup() {
        world = Bukkit.getWorlds().getFirst();
        int i = 0;
        for (String id : GATES)
            for (Direction d : DIRS) {
                int x = 10 + (i % 4) * 8, z = 10 + (i / 4) * 8;
                i++;
                Case c = new Case();
                c.id = id;
                c.facing = d;
                c.axis = (d == Direction.NORTH || d == Direction.SOUTH) ? BlockFace.EAST : BlockFace.SOUTH;
                c.inward = c.axis.getOppositeFace();
                c.front = switch (d) {
                    case NORTH -> BlockFace.NORTH;
                    case EAST -> BlockFace.EAST;
                    case SOUTH -> BlockFace.SOUTH;
                    default -> BlockFace.WEST;
                };
                c.gate = at(x, z);
                c.vanilla = c.gate.getRelative(c.inward);
                c.piece = c.gate.getRelative(c.axis);
                c.frontPiece = c.gate.getRelative(c.front);
                c.fence = at(x + 40, z).getRelative(c.axis);
                c.wood = at(x + 80, z).getRelative(c.axis);
                c.customFence = at(x + 120, z).getRelative(c.axis);
                for (Block b : List.of(c.gate, c.vanilla, c.piece, c.frontPiece, c.fence, c.wood, c.customFence)) {
                    b.getRelative(BlockFace.DOWN).setType(Material.STONE, false);
                    b.setType(Material.AIR, false);
                }
                gate(c.gate, id, d);
                c.vanilla.setType(Material.COBBLESTONE_WALL, true);
                refresh(c.vanilla);
                c.furniture = wall(c.piece);
                refresh(c.piece);
                c.frontFurniture = wall(c.frontPiece);
                refresh(c.frontPiece);
                Block gf = at(x + 40, z);
                gf.getRelative(BlockFace.DOWN).setType(Material.STONE, false);
                gate(gf, id, d);
                c.fence.setType(Material.NETHER_BRICK_FENCE, true);
                refresh(c.fence);
                Block gw = at(x + 80, z);
                gw.getRelative(BlockFace.DOWN).setType(Material.STONE, false);
                gate(gw, id, d);
                c.wood.setType(Material.OAK_FENCE, true);
                refresh(c.wood);
                Block gc = at(x + 120, z);
                gc.getRelative(BlockFace.DOWN).setType(Material.STONE, false);
                CraftEngineBlocks.place(
                    c.customFence.getLocation(), Key.of("cinchsmissingblocks:red_nether_brick_fence"), false);
                gate(gc, id, d);
                Block gfront = at(x + 160, z);
                gfront.getRelative(BlockFace.DOWN).setType(Material.STONE, false);
                gate(gfront, id, d);
                c.frontVanilla = gfront.getRelative(c.front);
                c.frontVanilla.getRelative(BlockFace.DOWN).setType(Material.STONE, false);
                c.frontVanilla.setType(Material.COBBLESTONE_WALL, true);
                refresh(c.frontVanilla);
                Block gfrontFence = at(x + 200, z);
                gfrontFence.getRelative(BlockFace.DOWN).setType(Material.STONE, false);
                gate(gfrontFence, id, d);
                c.frontFence = gfrontFence.getRelative(c.front);
                c.frontFence.getRelative(BlockFace.DOWN).setType(Material.STONE, false);
                c.frontFence.setType(Material.NETHER_BRICK_FENCE, true);
                refresh(c.frontFence);
                c.reverseGate = at(x + 240, z);
                c.reverseGate.getChunk().setForceLoaded(true);
                c.reverseGate.getRelative(BlockFace.DOWN).setType(Material.STONE, false);
                c.reverseVanilla = c.reverseGate.getRelative(c.inward);
                c.reversePiece = c.reverseGate.getRelative(c.axis);
                for (Block b : List.of(c.reverseVanilla, c.reversePiece)) {
                    b.getRelative(BlockFace.DOWN).setType(Material.STONE, false);
                    b.setType(Material.AIR, false);
                }
                c.reverseVanilla.setType(Material.COBBLESTONE_WALL, true);
                refresh(c.reverseVanilla);
                c.reverseFurniture = wall(c.reversePiece);
                refresh(c.reversePiece);
                gate(c.reverseGate, id, d);
                cases.add(c);
            }
        later(this::initial, 30);
    }
    void initial() {
        for (Case c : cases) {
            String n = c.id + " " + c.facing;
            Wall v = (Wall) c.vanilla.getBlockData();
            check(v.getHeight(c.axis) != Wall.Height.NONE, n + " vanilla wall joins gate: " + v.getHeight(c.axis));
            String variant = c.furniture.currentVariant().name();
            char side = switch (c.inward) {
                case NORTH -> 'n';
                case EAST -> 'e';
                case SOUTH -> 's';
                default -> 'w';
            };
            int idx = variant.indexOf(side);
            check(idx >= 0 && variant.charAt(idx + 1) != '0', n + " custom wall joins gate: " + variant);
            check(prop(c.gate, "in_wall"), n + " gate lowers in wall");
            check(
                ((MultipleFacing) c.fence.getBlockData()).hasFace(c.inward), n + " nether brick fence joins gate");
            check(
                !((MultipleFacing) c.wood.getBlockData()).hasFace(c.inward), n + " wooden fence remains separate");
            check(prop(c.customFence, c.inward.name().toLowerCase(Locale.ROOT)),
                n + " custom nether brick fence joins gate");
            String front = c.frontFurniture.currentVariant().name();
            char frontSide = switch (c.front.getOppositeFace()) {
                case NORTH -> 'n';
                case EAST -> 'e';
                case SOUTH -> 's';
                default -> 'w';
            };
            int fi = front.indexOf(frontSide);
            check(fi >= 0 && front.charAt(fi + 1) == '0', n + " custom wall in front stays separate");
            check(((Wall) c.frontVanilla.getBlockData()).getHeight(c.front.getOppositeFace()) == Wall.Height.NONE,
                n + " vanilla wall in front stays separate");
            check(!((MultipleFacing) c.frontFence.getBlockData()).hasFace(c.front.getOppositeFace()),
                n + " fence in front stays separate");
            check(prop(c.reverseGate, "in_wall"), n + " gate placed after walls lowers");
            check(((Wall) c.reverseVanilla.getBlockData()).getHeight(c.axis) != Wall.Height.NONE,
                n + " gate placed after vanilla wall connects");
            String rev = c.reverseFurniture.currentVariant().name();
            int ri = rev.indexOf(side);
            check(ri >= 0 && rev.charAt(ri + 1) != '0', n + " gate placed after custom wall connects");
            c.vanilla.setType(Material.AIR, true);
            refresh(c.vanilla);
        }
        later(this::afterVanilla, 20);
    }
    void afterVanilla() {
        for (Case c : cases) {
            String n = c.id + " " + c.facing;
            check(prop(c.gate, "in_wall"), n + " gate stays lowered beside custom wall");
            CraftEngineFurniture.remove(c.furniture.bukkitEntity(), false, false);
            refresh(c.piece);
            Block fg = c.fence.getRelative(c.inward);
            CraftEngineBlocks.remove(fg, false);
            refresh(fg);
        }
        later(this::afterPiece, 20);
    }
    void afterPiece() {
        for (Case c : cases) {
            check(!prop(c.gate, "in_wall"), c.id + " " + c.facing + " gate rises when both walls removed");
            check(!((MultipleFacing) c.fence.getBlockData()).hasFace(c.inward),
                c.id + " " + c.facing + " fence disconnects after gate removal");
        }
        later(this::prepareReload, 10);
    }
    void prepareReload() {
        reloadGate = at(1008, 1008);
        reloadVanilla = reloadGate.getRelative(BlockFace.WEST);
        reloadPiece = reloadGate.getRelative(BlockFace.EAST);
        reloadGateOnly = at(1012, 1008);
        reloadVanillaOnly = reloadGateOnly.getRelative(BlockFace.WEST);
        startupGate = at(2, 2);
        startupWall = startupGate.getRelative(BlockFace.WEST);
        for (Block b : List.of(reloadGate, reloadVanilla, reloadPiece, reloadGateOnly, reloadVanillaOnly,
                 startupGate, startupWall)) {
            b.getRelative(BlockFace.DOWN).setType(Material.STONE, false);
            b.setType(Material.AIR, false);
        }
        gate(reloadGate, "nether_brick_fence_gate", Direction.NORTH);
        reloadVanilla.setType(Material.COBBLESTONE_WALL, true);
        refresh(reloadVanilla);
        reloadFurniture = wall(reloadPiece);
        reloadEntity = reloadFurniture.bukkitEntity().getUniqueId();
        refresh(reloadPiece);
        gate(reloadGateOnly, "nether_brick_fence_gate", Direction.NORTH);
        reloadVanillaOnly.setType(Material.COBBLESTONE_WALL, true);
        refresh(reloadVanillaOnly);
        gate(startupGate, "nether_brick_fence_gate", Direction.NORTH);
        startupWall.setType(Material.COBBLESTONE_WALL, true);
        refresh(startupWall);
        later(this::saveForRestart, 30);
    }
    void stale(Block gate, Block vanilla) {
        Wall d = (Wall) vanilla.getBlockData();
        d.setHeight(BlockFace.EAST, Wall.Height.NONE);
        vanilla.setBlockData(d, false);
        ImmutableBlockState s = state(gate);
        Property<Boolean> in = s.getProperty("in_wall");
        CraftEngineBlocks.place(gate.getLocation(), s.with(in, false),
            net.momirealms.craftengine.core.block.UpdateFlags.UPDATE_CLIENTS, false);
    }
    void saveForRestart() {
        try {
            stale(reloadGate, reloadVanilla);
            stale(reloadGateOnly, reloadVanillaOnly);
            stale(startupGate, startupWall);
            reloadFurniture.setVariant("n0e0s0w0p1", true);
            check(((Wall) reloadVanillaOnly.getBlockData()).getHeight(BlockFace.EAST) == Wall.Height.NONE,
                "saved vanilla wall starts disconnected");
            getDataFolder().mkdirs();
            java.nio.file.Files.writeString(
                getDataFolder().toPath().resolve("restart.uuid"), reloadEntity.toString());
            getLogger().info("[WALLTEST] PHASE1 failures=" + failures + " checks=" + checks);
            Bukkit.shutdown();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    void beginReload() {
        try {
            world = Bukkit.getWorlds().getFirst();
            reloadEntity = java.util.UUID.fromString(
                java.nio.file.Files.readString(getDataFolder().toPath().resolve("restart.uuid")));
            startupGate = at(2, 2);
            startupWall = startupGate.getRelative(BlockFace.WEST);
            world.getChunkAt(1008 >> 4, 1008 >> 4).load(true);
            reloadGate = at(1008, 1008);
            reloadVanilla = reloadGate.getRelative(BlockFace.WEST);
            reloadPiece = reloadGate.getRelative(BlockFace.EAST);
            reloadGateOnly = at(1012, 1008);
            reloadVanillaOnly = reloadGateOnly.getRelative(BlockFace.WEST);
            later(this::checkReload, 80);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    void checkReload() {
        check(prop(reloadGate, "in_wall"), "reloaded gate detects furniture wall");
        check(((Wall) reloadVanilla.getBlockData()).getHeight(BlockFace.EAST) != Wall.Height.NONE,
            "reloaded vanilla wall reconnects with a furniture wall nearby");
        var entity = Bukkit.getEntity(reloadEntity);
        BukkitFurniture f = entity == null ? null : CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity);
        check(f != null && f.currentVariant().name().contains("w1"), "reloaded furniture wall reconnects");
        check(prop(reloadGateOnly, "in_wall"), "reloaded gate detects vanilla wall");
        check(((Wall) reloadVanillaOnly.getBlockData()).getHeight(BlockFace.EAST) != Wall.Height.NONE,
            "reloaded vanilla wall reconnects without furniture");
        check(prop(startupGate, "in_wall"), "gate in startup chunk detects vanilla wall");
        check(((Wall) startupWall.getBlockData()).getHeight(BlockFace.EAST) != Wall.Height.NONE,
            "wall in startup chunk reconnects");
        getLogger().info("[WALLTEST] FINISHED failures=" + failures + " checks=" + checks);
        Bukkit.shutdown();
    }
}
