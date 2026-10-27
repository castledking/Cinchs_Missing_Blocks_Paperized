import java.lang.reflect.Method;
import java.util.*;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.core.entity.furniture.hitbox.FurnitureHitBox;
import net.momirealms.craftengine.core.entity.furniture.hitbox.FurnitureHitboxPart;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.Vec3d;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public final class WallProbe extends JavaPlugin implements Listener {
    Player player;
    World world;
    Method spawn, reshape, connections, plates;
    Object wallKind;
    BukkitFurniture left, middle, right, single, upperLeft, upperMiddle, upperRight, cubeLeft, cubeRight,
        vanillaMiddle;
    final Map<BukkitFurniture, UUID> furnitureEntities = new IdentityHashMap<>();
    int failures;
    boolean protectionEvent;
    int protectedX = -1;
    int protectedSnowX = -1;
    int snowCaseIndex;
    record SnowCase(String item, Material ground, boolean allowed) {}
    final List<SnowCase> snowCases = List.of(
        new SnowCase("minecraft:cobblestone_wall", Material.GRASS_BLOCK, true),
        new SnowCase("cinchsmissingblocks:soul_sandstone_wall", Material.GRASS_BLOCK, true),
        new SnowCase("cinchsmissingblocks:andesite_brick_wall", Material.PODZOL, true),
        new SnowCase("cinchsmissingblocks:soul_sandstone_wall", Material.MYCELIUM, true),
        new SnowCase("cinchsmissingblocks:blue_nether_brick_fence", Material.GRASS_BLOCK, true),
        new SnowCase("cinchsmissingblocks:nether_brick_fence_gate", Material.GRASS_BLOCK, true),
        new SnowCase("cinchsmissingblocks:soul_sandstone_wall", Material.GRASS_BLOCK, false));
    void log(String s) {
        getLogger().info("[WALLTEST] " + s);
    }
    void check(boolean ok, String label) {
        log((ok ? "PASS " : "FAIL ") + label);
        if (!ok)
            failures++;
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
            Class<?> v = Bukkit.getPluginManager().getPlugin("CMB").getClass().getClassLoader().loadClass(
                "net.cinchtail.cinchsmissingblocks.cmb.VerticalSlabListener");
            spawn = v.getDeclaredMethod("spawn", Location.class, Key.class, String.class, boolean.class);
            spawn.setAccessible(true);
            reshape = v.getDeclaredMethod("reshapeAround", Block.class);
            reshape.setAccessible(true);
            plates = v.getDeclaredMethod("platesIn", Block.class);
            plates.setAccessible(true);
            Class<?> k = Class.forName(
                "net.cinchtail.cinchsmissingblocks.cmb.VerticalSlabListener$Kind", true, v.getClassLoader());
            connections = v.getDeclaredMethod("connections", Block.class, k);
            connections.setAccessible(true);
            wallKind = java.util.Arrays.stream(k.getEnumConstants())
                           .filter(o -> o.toString().equals("WALL"))
                           .findFirst()
                           .orElseThrow();
            Bukkit.getPluginManager().registerEvents(this, this);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    @EventHandler
    public void join(PlayerJoinEvent e) {
        if (e.getPlayer().getName().equals("ParityProbe")) {
            player = e.getPlayer();
            world = player.getWorld();
            later(this::setup, 30);
        }
    }
    Block at(int x, int y, int z) {
        return world.getBlockAt(x, y, z);
    }
    BukkitFurniture wall(int x) throws Exception {
        return wall(x, 65);
    }
    BukkitFurniture wall(int x, int y) throws Exception {
        Location l = new Location(world, x + .5, y, .5);
        String variant = (String) connections.invoke(null, at(x, y, 0), wallKind);
        BukkitFurniture f = (BukkitFurniture) spawn.invoke(
            null, l, Key.of("cinchsmissingblocks:soul_sandstone_wall"), variant, false);
        if (f == null)
            throw new IllegalStateException("spawn failed " + x);
        furnitureEntities.put(f, f.bukkitEntity().getUniqueId());
        reshape.invoke(null, at(x, y, 0));
        return f;
    }
    boolean removed(BukkitFurniture furniture) {
        return Bukkit.getEntity(furnitureEntities.get(furniture)) == null;
    }
    void aim(double x, double y, double z, double tx, double ty, double tz) {
        Location l = new Location(world, x, y, z);
        l.setDirection(new org.bukkit.util.Vector(tx - x, ty - (y + player.getEyeHeight()), tz - z));
        player.teleport(l);
    }
    int target(BukkitFurniture f) {
        Location eye = player.getEyeLocation();
        org.bukkit.util.Vector d = eye.getDirection().multiply(5);
        Vec3d start = new Vec3d(eye.getX(), eye.getY(), eye.getZ());
        Vec3d end = new Vec3d(eye.getX() + d.getX(), eye.getY() + d.getY(), eye.getZ() + d.getZ());
        double best = Double.MAX_VALUE;
        int id = -1;
        for (FurnitureHitBox h : f.hitboxes())
            for (int i = 0; i < h.partCount(); i++) {
                FurnitureHitboxPart p = h.part(i);
                var hit = p.aabb().clip(start, end);
                if (hit.isPresent()) {
                    Vec3d q = hit.get().hitLocation();
                    double dist = Math.pow(q.x - eye.getX(), 2) + Math.pow(q.y - eye.getY(), 2)
                        + Math.pow(q.z - eye.getZ(), 2);
                    if (dist < best) {
                        best = dist;
                        id = p.entityId();
                        log("ray hit=" + q + " interactive=" + p.interactive() + " id=" + id);
                    }
                }
            }
        if (id == -1)
            throw new IllegalStateException("no ray hit");
        return id;
    }
    void setup() {
        try {
            player.setGameMode(GameMode.CREATIVE);
            player.setAllowFlight(true);
            player.setFlying(true);
            player.getInventory().clear();
            for (int x = -2; x < 59; x++)
                for (int z = -1; z < 5; z++) {
                    at(x, 64, z).setType(Material.STONE);
                    for (int y = 65; y < 70; y++) at(x, y, z).setType(Material.AIR);
                }
            left = wall(0);
            middle = wall(1);
            right = wall(2);
            later(this::before, 20);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    void before() {
        check(left.currentVariant().name().contains("e1"),
            "left wall initially connects east: " + left.currentVariant().name());
        check(right.currentVariant().name().contains("w1"),
            "right wall initially connects west: " + right.currentVariant().name());
        aim(1.5, 65, 3.0, 1.5, 65.65, .5);
        later(() -> {
            int id = target(middle);
            player.sendMessage("[WALLTEST-ACTION] attack " + id);
            later(this::after, 25);
        }, 10);
    }
    void after() {
        check(removed(middle), "middle wall broken by native player");
        check(
            left.currentVariant().name().contains("e0"), "left wall disconnects: " + left.currentVariant().name());
        check(right.currentVariant().name().contains("w0"),
            "right wall disconnects: " + right.currentVariant().name());
        try {
            for (int x = 10; x <= 12; x++) wall(x, 65);
            upperLeft = wall(10, 66);
            upperMiddle = wall(11, 66);
            upperRight = wall(12, 66);
            later(this::beforeStack, 15);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    void beforeStack() {
        check(upperLeft.currentVariant().name().contains("e1") || upperLeft.currentVariant().name().contains("e2"),
            "upper left initially connects: " + upperLeft.currentVariant().name());
        check(
            upperRight.currentVariant().name().contains("w1") || upperRight.currentVariant().name().contains("w2"),
            "upper right initially connects: " + upperRight.currentVariant().name());
        aim(11.5, 66, 3, 11.5, 66.65, .5);
        later(() -> {
            int id = target(upperMiddle);
            player.sendMessage("[WALLTEST-ACTION] attack " + id);
            later(this::afterStack, 25);
        }, 10);
    }
    void afterStack() {
        check(removed(upperMiddle), "stacked middle broken by native player");
        check(upperLeft.currentVariant().name().contains("e0"),
            "upper left disconnects: " + upperLeft.currentVariant().name());
        check(upperRight.currentVariant().name().contains("w0"),
            "upper right disconnects: " + upperRight.currentVariant().name());
        try {
            net.momirealms.craftengine.bukkit.api.CraftEngineBlocks.place(
                at(17, 65, 0).getLocation(), Key.of("cinchsmissingblocks:soul_sandstone"), false);
            cubeLeft = wall(16);
            cubeRight = wall(18);
            later(this::beforeCube, 15);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    void beforeCube() {
        check(cubeLeft.currentVariant().name().contains("e1"),
            "left wall initially connects to custom block: " + cubeLeft.currentVariant().name());
        check(cubeRight.currentVariant().name().contains("w1"),
            "right wall initially connects to custom block: " + cubeRight.currentVariant().name());
        aim(17.5, 65, 3, 17.5, 65.6, .5);
        later(() -> {
            player.sendMessage("[WALLTEST-ACTION] dig 17");
            later(this::afterCube, 25);
        }, 10);
    }
    void afterCube() {
        check(at(17, 65, 0).getType().isAir(), "custom block broken by native player: " + at(17, 65, 0).getType());
        check(cubeLeft.currentVariant().name().contains("e0"),
            "left wall disconnects from custom block: " + cubeLeft.currentVariant().name());
        check(cubeRight.currentVariant().name().contains("w0"),
            "right wall disconnects from custom block: " + cubeRight.currentVariant().name());
        try {
            at(22, 65, 0).setType(Material.STONE);
            at(26, 65, 0).setType(Material.STONE);
            at(23, 65, 0).setType(Material.MUD_BRICK_WALL);
            at(25, 65, 0).setType(Material.MUD_BRICK_WALL);
            org.bukkit.block.data.type.Wall a = (org.bukkit.block.data.type.Wall) at(23, 65, 0).getBlockData();
            a.setHeight(org.bukkit.block.BlockFace.WEST, org.bukkit.block.data.type.Wall.Height.LOW);
            at(23, 65, 0).setBlockData(a, false);
            org.bukkit.block.data.type.Wall b = (org.bukkit.block.data.type.Wall) at(25, 65, 0).getBlockData();
            b.setHeight(org.bukkit.block.BlockFace.EAST, org.bukkit.block.data.type.Wall.Height.LOW);
            at(25, 65, 0).setBlockData(b, false);
            vanillaMiddle = wall(24);
            later(this::beforeVanilla, 15);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    org.bukkit.block.data.type.Wall.Height height(int x, org.bukkit.block.BlockFace face) {
        return ((org.bukkit.block.data.type.Wall) at(x, 65, 0).getBlockData()).getHeight(face);
    }
    void beforeVanilla() {
        check(height(23, org.bukkit.block.BlockFace.WEST) != org.bukkit.block.data.type.Wall.Height.NONE,
            "vanilla left initially joins real block");
        check(height(25, org.bukkit.block.BlockFace.EAST) != org.bukkit.block.data.type.Wall.Height.NONE,
            "vanilla right initially joins real block");
        check(height(23, org.bukkit.block.BlockFace.EAST) != org.bukkit.block.data.type.Wall.Height.NONE,
            "vanilla left initially joins custom wall: " + height(23, org.bukkit.block.BlockFace.EAST));
        check(height(25, org.bukkit.block.BlockFace.WEST) != org.bukkit.block.data.type.Wall.Height.NONE,
            "vanilla right initially joins custom wall: " + height(25, org.bukkit.block.BlockFace.WEST));
        aim(24.5, 65, 3, 24.5, 65.65, .5);
        later(() -> {
            int id = target(vanillaMiddle);
            player.sendMessage("[WALLTEST-ACTION] attack " + id);
            later(this::afterVanilla, 25);
        }, 10);
    }
    void afterVanilla() {
        check(removed(vanillaMiddle), "custom wall between vanilla walls broken");
        check(height(23, org.bukkit.block.BlockFace.EAST) == org.bukkit.block.data.type.Wall.Height.NONE,
            "vanilla left disconnects: " + height(23, org.bukkit.block.BlockFace.EAST));
        check(height(25, org.bukkit.block.BlockFace.WEST) == org.bukkit.block.data.type.Wall.Height.NONE,
            "vanilla right disconnects: " + height(25, org.bukkit.block.BlockFace.WEST));
        check(height(23, org.bukkit.block.BlockFace.WEST) != org.bukkit.block.data.type.Wall.Height.NONE,
            "vanilla wall retains real block connection");
        check(height(25, org.bukkit.block.BlockFace.EAST) != org.bukkit.block.data.type.Wall.Height.NONE,
            "opposite wall retains real block connection");
        try {
            single = wall(6);
            player.getInventory().setItemInMainHand(new ItemStack(Material.COBBLESTONE_WALL));
            player.setSneaking(true);
            aim(6.5, 66, 3.0, 6.5, 66.2, .5);
            later(() -> {
                int id = target(single);
                player.sendMessage("[WALLTEST-ACTION] click " + id);
                later(this::afterClick, 25);
            }, 10);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(org.bukkit.event.block.BlockPlaceEvent event) {
        if (event.getBlockPlaced().getX() == protectedX && event.getBlockPlaced().getY() == 66) {
            protectionEvent = true;
            event.setCancelled(true);
        }
    }
    void afterClick() {
        check(at(6, 66, 0).getType() == Material.COBBLESTONE_WALL,
            "sneak places vanilla wall above: above=" + at(6, 66, 0).getType()
                + " diagonally=" + at(6, 66, 1).getType());
        check(at(6, 66, 1).getType().isAir() && at(6, 67, 0).getType().isAir(),
            "one click places only one wall: side=" + at(6, 66, 1).getType()
                + " above=" + at(6, 67, 0).getType());
        try {
            single = wall(44);
            player.setSneaking(true);
            aim(44.5, 68, .5, 44.5, 66.2, .5);
            later(() -> {
                int id = target(single);
                player.sendMessage("[WALLTEST-ACTION] clickup " + id);
                later(this::afterClickUp, 25);
            }, 10);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    void afterClickUp() {
        check(at(44, 66, 0).getType() == Material.COBBLESTONE_WALL,
            "sneak click from above places a wall on top");
        check(at(44, 67, 0).getType().isAir(), "sneak click from above does not place a second wall");
        try {
            single = wall(33);
            player.setSneaking(true);
            aim(33.5, 65, 3, 33.5, 65.65, .5);
            later(() -> {
                int id = target(single);
                player.sendMessage("[WALLTEST-ACTION] side " + id);
                later(this::afterSide, 25);
            }, 10);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    void afterSide() {
        check(at(33, 65, 1).getType() == Material.COBBLESTONE_WALL,
            "sneak side click places beside wall: " + at(33, 65, 1).getType());
        check(at(33, 66, 0).getType().isAir(), "side click does not place above");
        try {
            single = wall(36);
            player.setSneaking(false);
            aim(36.5, 66, 3, 36.5, 66.2, .5);
            later(() -> {
                int id = target(single);
                player.sendMessage("[WALLTEST-ACTION] normal " + id);
                later(this::afterNormal, 25);
            }, 10);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    void afterNormal() {
        check(at(36, 66, 0).getType() == Material.COBBLESTONE_WALL,
            "normal top click places above: " + at(36, 66, 0).getType());
        try {
            single = wall(39);
            protectedX = 39;
            player.setGameMode(GameMode.SURVIVAL);
            player.getInventory().setItemInMainHand(new ItemStack(Material.COBBLESTONE_WALL, 4));
            player.setAllowFlight(true);
            player.setFlying(true);
            player.setSneaking(true);
            aim(39.5, 66, 3, 39.5, 66.2, .5);
            later(() -> {
                int id = target(single);
                player.sendMessage("[WALLTEST-ACTION] deny " + id);
                later(this::afterDeny, 25);
            }, 10);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    void afterDeny() {
        check(protectionEvent, "protected placement fired BlockPlaceEvent");
        check(at(39, 66, 0).getType().isAir(), "protected placement is cancelled");
        check(player.getInventory().getItemInMainHand().getAmount() == 4,
            "cancelled placement does not consume item");
        try {
            protectedX = -1;
            single = wall(46);
            player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
            player.getInventory().setItemInOffHand(new ItemStack(Material.COBBLESTONE_WALL, 4));
            aim(46.5, 66, 3, 46.5, 66.2, .5);
            later(() -> {
                int id = target(single);
                player.sendMessage("[WALLTEST-ACTION] offhand " + id);
                later(this::afterOffhand, 25);
            }, 10);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    void afterOffhand() {
        check(at(46, 66, 0).getType() == Material.COBBLESTONE_WALL,
            "off hand places a wall when the main hand is empty");
        check(player.getInventory().getItemInOffHand().getAmount() == 3,
            "successful off-hand placement consumes one wall");
        check(at(46, 66, 1).getType().isAir() && at(46, 67, 0).getType().isAir(),
            "off-hand placement does not produce an extra wall");
        try {
            player.setGameMode(GameMode.CREATIVE);
            player.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
            player.getInventory().setItemInMainHand(
                net.momirealms.craftengine.bukkit.api.CraftEngineItems.byId(
                    "cinchsmissingblocks:nether_brick_fence_gate").buildBukkitItem());
            player.setSneaking(false);
            at(53, 65, 0).setType(Material.COBBLESTONE_WALL);
            single = wall(55);
            player.teleport(new Location(world, 54.5, 65, 3.5, 180, 28));
            later(() -> {
                player.sendMessage("[WALLTEST-ACTION] gateplace 54");
                later(this::afterGatePlace, 25);
            }, 10);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    boolean gateProperty(String name) {
        var state = net.momirealms.craftengine.bukkit.api.CraftEngineBlocks.getCustomBlockState(at(54, 65, 0));
        if (state == null) return false;
        net.momirealms.craftengine.core.block.property.Property<Boolean> property = state.getProperty(name);
        return property != null && state.get(property);
    }
    void afterGatePlace() {
        check(gateProperty("in_wall"), "native gate placement lowers between vanilla and custom walls");
        check(height(53, org.bukkit.block.BlockFace.EAST) != org.bukkit.block.data.type.Wall.Height.NONE,
            "native gate placement connects the existing vanilla wall");
        check(single.currentVariant().name().contains("w1"),
            "native gate placement connects the existing custom wall: " + single.currentVariant().name());
        player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        player.sendMessage("[WALLTEST-ACTION] gateuse 54");
        later(this::afterGateOpen, 15);
    }
    void afterGateOpen() {
        check(gateProperty("open"), "native gate still opens by hand beside walls");
        check(at(54, 65, 0).getCollisionShape().getBoundingBoxes().isEmpty(),
            "opened gate remains passable");
        at(54, 64, 0).setType(Material.REDSTONE_BLOCK, true);
        later(this::afterGatePower, 10);
    }
    void afterGatePower() {
        check(gateProperty("powered") && gateProperty("open"), "redstone powers the connected gate");
        at(54, 64, 0).setType(Material.STONE, true);
        later(this::afterGateUnpower, 10);
    }
    void afterGateUnpower() {
        check(!gateProperty("powered") && !gateProperty("open"), "removing power closes the connected gate");
        player.sendMessage("[WALLTEST-ACTION] dig 54");
        later(this::afterGateBreak, 20);
    }
    void afterGateBreak() {
        check(net.momirealms.craftengine.bukkit.api.CraftEngineBlocks.getCustomBlockState(at(54, 65, 0)) == null,
            "native player breaks the gate");
        check(height(53, org.bukkit.block.BlockFace.EAST) == org.bukkit.block.data.type.Wall.Height.NONE,
            "existing vanilla wall disconnects after gate removal");
        check(single.currentVariant().name().contains("w0"),
            "existing custom wall disconnects after gate removal: " + single.currentVariant().name());
        startSnowCase();
    }
    @EventHandler(priority = EventPriority.LOWEST)
    public void protectSnow(net.momirealms.craftengine.bukkit.api.event.FurnitureAttemptPlaceEvent event) {
        if (event.clickedBlock().getX() == protectedSnowX) event.setCancelled(true);
    }
    boolean snowy(Block block) {
        return block.getBlockData() instanceof org.bukkit.block.data.Snowable data && data.isSnowy();
    }
    void startSnowCase() {
        if (snowCaseIndex == snowCases.size()) {
            finish();
            return;
        }
        SnowCase test = snowCases.get(snowCaseIndex);
        int x = 62 + snowCaseIndex * 4;
        for (int dx = -1; dx <= 2; dx++)
            for (int z = -1; z <= 4; z++) {
                at(x + dx, 64, z).setType(Material.STONE);
                for (int y = 65; y < 69; y++) at(x + dx, y, z).setType(Material.AIR);
            }
        at(x, 64, 0).setType(test.ground());
        at(x, 65, 0).setType(Material.SNOW);
        at(x + 1, 64, 0).setType(Material.GRASS_BLOCK);
        at(x + 1, 65, 0).setType(Material.SNOW);
        check(snowy(at(x, 64, 0)), "snow fixture starts snowy: " + test);
        protectedSnowX = test.allowed() ? -1 : x;
        player.setGameMode(GameMode.SURVIVAL);
        player.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
        ItemStack held = test.item().startsWith("minecraft:")
            ? new ItemStack(Material.matchMaterial(test.item()))
            : net.momirealms.craftengine.bukkit.api.CraftEngineItems.byId(test.item()).buildBukkitItem();
        held.setAmount(2);
        player.getInventory().setItemInMainHand(held);
        aim(x + .5, 65, 3.5, x + .5, 65.125, .5);
        later(() -> {
            player.sendMessage("[WALLTEST-ACTION] snowplace " + x);
            later(this::afterSnowCase, 15);
        }, 10);
    }
    void afterSnowCase() {
        SnowCase test = snowCases.get(snowCaseIndex);
        int x = 62 + snowCaseIndex * 4;
        String label = test.toString();
        try {
            if (test.allowed()) {
                boolean placed;
                if (test.item().startsWith("minecraft:")) {
                    placed = at(x, 65, 0).getType() == Material.COBBLESTONE_WALL;
                } else if (test.item().endsWith("_wall")) {
                    placed = !((List<?>) plates.invoke(null, at(x, 65, 0))).isEmpty();
                } else {
                    var state = net.momirealms.craftengine.bukkit.api.CraftEngineBlocks.getCustomBlockState(at(x, 65, 0));
                    placed = state != null && state.owner().value().id().toString().equals(test.item());
                }
                check(placed && at(x, 65, 0).getType() != Material.SNOW, "native placement replaces snow: " + label);
                check(at(x, 64, 0).getType() == test.ground() && !snowy(at(x, 64, 0)),
                    "ground loses snowy texture after placement: " + label + " actual=" + at(x, 64, 0).getBlockData());
            } else {
                check(at(x, 65, 0).getType() == Material.SNOW && snowy(at(x, 64, 0)),
                    "cancelled wall placement preserves snow and snowy ground");
                check(((List<?>) plates.invoke(null, at(x, 65, 0))).isEmpty(),
                    "cancelled snow placement creates no furniture");
            }
            check(player.getInventory().getItemInMainHand().getAmount() == (test.allowed() ? 1 : 2),
                "snow placement consumes an item only on success: " + label);
            check(at(x + 1, 65, 0).getType() == Material.SNOW && snowy(at(x + 1, 64, 0)),
                "adjacent snow and snowy ground remain intact: " + label);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        snowCaseIndex++;
        startSnowCase();
    }
    void finish() {
        log("FINISHED failures=" + failures);
        Bukkit.shutdown();
    }
}
