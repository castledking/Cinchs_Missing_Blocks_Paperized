package net.cinchtail.cinchsmissingblocks.cmb;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import net.cinchtail.cinchsmissingblocks.cmb.config.CmbConfig;
import net.cinchtail.cinchsmissingblocks.cmb.scheduler.Schedulers;
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.api.event.FurnitureAttemptPlaceEvent;
import net.momirealms.craftengine.bukkit.api.event.FurnitureBreakEvent;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.bukkit.item.BukkitItemDefinition;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.GameMode;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.generator.structure.Structure;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * The warped nether wart crop, and the three ways to find it.
 *
 * <p>The crop is CraftEngine furniture, as Allium Harvest draws crops: an item display
 * per stage and a walk-through interaction hitbox, so it takes no block state (no
 * tripwire). Its variants are {@code age_0}..{@code age_3}. This class gives it the
 * block behaviour vanilla nether wart has:
 *
 * <ul>
 *   <li>planted on soul sand (and on warped nylium, with nylium planting on);</li>
 *   <li>grows one age at a time at vanilla's average rate: a random tick reaches a
 *   block every 4096 / randomTickSpeed ticks and nether wart grows on 1 in 10 of them.
 *   Each crop schedules its next growth on its own entity, so it stops when its chunk
 *   unloads, as random ticks do, and picks up again when it loads;</li>
 *   <li>drops 1 wart, or 2-4 plus up to the Fortune level when ripe;</li>
 *   <li>pops when its soil is broken, blown up or pushed, or water flows into it.</li>
 * </ul>
 *
 * <p>Finding it, each with its own switch (content.warped-nether-wart):
 * <ul>
 *   <li><b>fortress</b> - in a newly generated fortress chunk, each patch of nether
 *   wart has a chance to come up warped (DESIGN.md 5.1 a);</li>
 *   <li><b>biome edges</b> - in a newly generated chunk where a soul sand valley meets
 *   a forest, a few specks of soul sand with wart on top along the seam: warped beside
 *   warped forest, vanilla nether wart beside crimson (5.1 c);</li>
 *   <li><b>nylium planting</b> - nether wart planted on warped nylium grows warped.</li>
 * </ul>
 * Generation only ever touches a chunk on its first load, and only inside that chunk.
 */
public final class NetherWartCrops implements Listener {

    static final Key WART = Key.of(VerticalSlabListener.NAMESPACE, "warped_nether_wart");
    private static final int MAX_AGE = 3;
    /** How far (blocks) a valley column may be from a forest to count as its edge. */
    private static final int EDGE_REACH = 6;

    private final CmbPlugin plugin;

    public NetherWartCrops(CmbPlugin plugin) {
        this.plugin = plugin;
    }

    private CmbConfig.Wart settings() {
        return plugin.cmbConfig().content().wart();
    }

    static boolean isWart(BukkitFurniture furniture) {
        return furniture != null && WART.equals(furniture.id());
    }

    static int ageOf(BukkitFurniture furniture) {
        String variant = furniture.currentVariant().name();
        return variant.startsWith("age_") ? Integer.parseInt(variant.substring(4)) : 0;
    }

    private boolean soil(Block below) {
        return below.getType() == Material.SOUL_SAND
                || (below.getType() == Material.WARPED_NYLIUM && settings().nyliumPlanting());
    }

    // --- planting -----------------------------------------------------------

    /** The warped wart item: CraftEngine's placement, replaced by a planting. */
    @EventHandler(ignoreCancelled = true)
    public void onAttemptPlace(FurnitureAttemptPlaceEvent event) {
        if (!WART.equals(event.furniture().id())) {
            return;
        }
        event.setCancelled(true);
        if (!"ground".equals(event.variant().name())) {
            return;
        }
        Block cell = event.location().getBlock();
        if (!VerticalSlabListener.placeableIntoDry(cell) || !soil(cell.getRelative(BlockFace.DOWN))) {
            return;
        }
        Player player = event.player();
        if (!Protection.canBuild(player, cell, Material.NETHER_WART)) {
            return;
        }
        EquipmentSlot slot = event.hand() == InteractionHand.OFF_HAND
                ? EquipmentSlot.OFF_HAND : EquipmentSlot.HAND;
        if (plant(cell, 0) && player.getGameMode() != GameMode.CREATIVE) {
            ItemStack held = player.getInventory().getItem(slot);
            held.setAmount(held.getAmount() - 1);
        }
        player.swingHand(slot);
    }

    /** Vanilla nether wart planted on warped nylium grows warped. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlantOnNylium(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getBlockFace() != BlockFace.UP
                || event.getClickedBlock() == null || event.getItem() == null
                || event.getItem().getType() != Material.NETHER_WART
                || CraftEngineItems.isCustomItem(event.getItem())
                || event.getClickedBlock().getType() != Material.WARPED_NYLIUM
                || !settings().nyliumPlanting()
                || event.useInteractedBlock() == org.bukkit.event.Event.Result.DENY) {
            return;
        }
        Block cell = event.getClickedBlock().getRelative(BlockFace.UP);
        if (!VerticalSlabListener.placeableIntoDry(cell)) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!Protection.canBuild(player, cell, Material.NETHER_WART)) {
            return;
        }
        if (plant(cell, 0) && player.getGameMode() != GameMode.CREATIVE) {
            event.getItem().setAmount(event.getItem().getAmount() - 1);
        }
        player.swingHand(event.getHand() == null ? EquipmentSlot.HAND : event.getHand());
    }

    /** Places a warped wart of an age in a cell and starts it growing. */
    boolean plant(Block cell, int age) {
        BukkitFurniture furniture = VerticalSlabListener.spawn(
                cell.getLocation().add(0.5, 0, 0.5), WART, "age_" + age, true);
        if (furniture == null) {
            return false;
        }
        VerticalSlabListener.clearForPlacement(cell);
        scheduleGrowth(furniture);
        return true;
    }

    // --- growth -------------------------------------------------------------

    private void scheduleGrowth(BukkitFurniture furniture) {
        if (ageOf(furniture) >= MAX_AGE) {
            return;
        }
        Entity entity = furniture.bukkitEntity();
        long delay = growthDelay(entity.getWorld());
        if (delay > 0) {
            entity.getScheduler().runDelayed(plugin, task -> grow(entity), null, delay);
        }
    }

    /**
     * Ticks to the next growth, drawn so the average matches vanilla: a block is random
     * ticked every 4096 / speed ticks on average, and nether wart grows on 1 in 10.
     * Never, when randomTickSpeed is 0 - vanilla wart doesn't grow then either.
     */
    private static long growthDelay(World world) {
        Integer speed = world.getGameRuleValue(GameRules.RANDOM_TICK_SPEED);
        if (speed == null || speed <= 0) {
            return -1;
        }
        double mean = 4096.0 / (speed * 0.1);
        double draw = ThreadLocalRandom.current().nextDouble();
        return Math.max(1, Math.round(-mean * Math.log(1 - draw)));
    }

    private void grow(Entity entity) {
        BukkitFurniture furniture = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity);
        if (!isWart(furniture) || !furniture.isValid()) {
            return;
        }
        int age = ageOf(furniture);
        if (age < MAX_AGE) {
            furniture.setVariant("age_" + (age + 1));
            scheduleGrowth(furniture);
        }
    }

    /** Crops coming back into a loaded chunk resume growing. */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        List<Entity> displays = new ArrayList<>();
        for (Entity entity : event.getEntities()) {
            if (entity instanceof ItemDisplay) {
                displays.add(entity);
            }
        }
        if (displays.isEmpty()) {
            return;
        }
        // Next tick: CraftEngine loads its furniture for these entities in its own
        // listener, which may run after this one.
        for (Entity entity : displays) {
            entity.getScheduler().run(plugin, task -> {
                BukkitFurniture furniture = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity);
                if (isWart(furniture)) {
                    scheduleGrowth(furniture);
                }
            }, null);
        }
    }

    // --- drops and popping --------------------------------------------------

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBreak(FurnitureBreakEvent event) {
        if (isWart(event.furniture()) && event.player().getGameMode() != GameMode.CREATIVE) {
            dropFor(event.furniture(), event.player().getInventory().getItemInMainHand());
        }
    }

    private static void dropFor(BukkitFurniture furniture, ItemStack tool) {
        BukkitItemDefinition item = CraftEngineItems.byId(WART);
        if (item == null) {
            return;
        }
        int count = 1;
        if (ageOf(furniture) >= MAX_AGE) {
            int fortune = tool == null ? 0 : tool.getEnchantmentLevel(Enchantment.FORTUNE);
            ThreadLocalRandom random = ThreadLocalRandom.current();
            count = 2 + random.nextInt(3) + (fortune > 0 ? random.nextInt(fortune + 1) : 0);
        }
        ItemStack drop = item.buildBukkitItem();
        drop.setAmount(count);
        Location at = furniture.location().clone().add(0, 0.25, 0);
        at.getWorld().dropItemNaturally(at, drop);
    }

    /** Removes a crop as if it popped: its drops, no player. */
    static void pop(BukkitFurniture furniture) {
        if (!isWart(furniture) || !furniture.isValid()) {
            return;
        }
        dropFor(furniture, null);
        CraftEngineFurniture.remove(furniture, false, true);
    }

    /** The crop on top of a block, if any. */
    static BukkitFurniture cropAbove(Block soil) {
        for (VerticalSlabListener.Plate plate : VerticalSlabListener.platesIn(soil.getRelative(BlockFace.UP))) {
            if (isWart(plate.furniture())) {
                return plate.furniture();
            }
        }
        return null;
    }

    private void popAbove(List<Block> blocks) {
        for (Block block : blocks) {
            if (block.getType() == Material.SOUL_SAND || block.getType() == Material.WARPED_NYLIUM) {
                Schedulers.atLocation(plugin, block.getLocation(), () -> {
                    BukkitFurniture crop = cropAbove(block);
                    if (crop != null && !soil(block)) {
                        pop(crop);
                    }
                });
            }
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onSoilBreak(BlockBreakEvent event) {
        popAbove(List.of(event.getBlock()));
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBlockExplode(BlockExplodeEvent event) {
        popAbove(event.blockList());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onEntityExplode(EntityExplodeEvent event) {
        popAbove(event.blockList());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        popAbove(event.getBlocks());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        popAbove(event.getBlocks());
    }

    // --- finding it: worldgen on a chunk's first load -----------------------

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!event.isNewChunk() || event.getWorld().getEnvironment() != World.Environment.NETHER) {
            return;
        }
        CmbConfig.Wart wart = settings();
        Chunk chunk = event.getChunk();
        if (wart.fortress() && !chunk.getStructures(Structure.FORTRESS).isEmpty()) {
            convertFortressWart(chunk, wart.fortressChance());
        }
        if (wart.biomeEdges()) {
            seedBiomeEdges(chunk, wart.biomeEdgeChance());
        }
    }

    /**
     * Turns some of a fortress chunk's nether wart patches warped, keeping each wart's
     * age. A patch is a connected group of wart (the fortress room's 2x5 beds); each
     * rolls once.
     */
    private void convertFortressWart(Chunk chunk, double chance) {
        World world = chunk.getWorld();
        ChunkSnapshot snapshot = chunk.getChunkSnapshot(false, false, false);
        Set<Long> wart = new HashSet<>();
        for (int y = world.getMinHeight(); y < world.getMaxHeight(); y++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    if (snapshot.getBlockType(x, y, z) == Material.NETHER_WART) {
                        wart.add(pack(x, y, z));
                    }
                }
            }
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        while (!wart.isEmpty()) {
            List<Long> patch = new ArrayList<>();
            ArrayDeque<Long> queue = new ArrayDeque<>(List.of(wart.iterator().next()));
            wart.remove(queue.peek());
            while (!queue.isEmpty()) {
                long at = queue.poll();
                patch.add(at);
                int x = (int) (at >> 40), y = (int) ((at >> 20) & 0xFFFFF) - 2048, z = (int) (at & 0xFFFFF);
                for (int[] d : new int[][] {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}, {0, -1, 0}}) {
                    long next = pack(x + d[0], y + d[1], z + d[2]);
                    if (wart.remove(next)) {
                        queue.add(next);
                    }
                }
            }
            if (random.nextDouble() >= chance) {
                continue;
            }
            for (long at : patch) {
                int x = (int) (at >> 40), y = (int) ((at >> 20) & 0xFFFFF) - 2048, z = (int) (at & 0xFFFFF);
                Block block = chunk.getBlock(x, y, z);
                Schedulers.atLocation(plugin, block.getLocation(), () -> {
                    if (block.getType() == Material.NETHER_WART
                            && block.getBlockData() instanceof Ageable ageable) {
                        int age = ageable.getAge();
                        block.setType(Material.AIR, false);
                        plant(block, age);
                    }
                });
            }
        }
    }

    private static long pack(int x, int y, int z) {
        return ((long) x << 40) | ((long) (y + 2048) << 20) | (z & 0xFFFFF);
    }

    /**
     * Specks of soul sand and wart where a soul sand valley meets a forest, inside this
     * chunk only. Biomes are sampled on a 4-block grid at y 64; a valley sample within
     * EDGE_REACH of a forest sample is edge, and a few such spots get soul sand floor and
     * a wart of a random age - warped beside warped forest, vanilla beside crimson.
     */
    private void seedBiomeEdges(Chunk chunk, double chance) {
        ChunkSnapshot snapshot = chunk.getChunkSnapshot(false, true, false);
        List<int[]> valley = new ArrayList<>();
        List<int[]> forest = new ArrayList<>();
        for (int x = 0; x < 16; x += 4) {
            for (int z = 0; z < 16; z += 4) {
                Biome biome = snapshot.getBiome(x, 64, z);
                if (biome == Biome.SOUL_SAND_VALLEY) {
                    valley.add(new int[] {x, z});
                } else if (biome == Biome.WARPED_FOREST) {
                    forest.add(new int[] {x, z, 1});
                } else if (biome == Biome.CRIMSON_FOREST) {
                    forest.add(new int[] {x, z, 0});
                }
            }
        }
        if (valley.isEmpty() || forest.isEmpty()) {
            return;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        World world = chunk.getWorld();
        for (int[] v : valley) {
            int[] nearest = null;
            int best = Integer.MAX_VALUE;
            for (int[] f : forest) {
                int d = Math.abs(f[0] - v[0]) + Math.abs(f[1] - v[1]);
                if (d < best) {
                    best = d;
                    nearest = f;
                }
            }
            if (best > EDGE_REACH || random.nextDouble() >= chance) {
                continue;
            }
            int x = Math.clamp(v[0] + random.nextInt(-1, 2), 0, 15);
            int z = Math.clamp(v[1] + random.nextInt(-1, 2), 0, 15);
            boolean warped = nearest[2] == 1;
            Integer floor = floorAt(world, snapshot, x, z);
            if (floor == null) {
                continue;
            }
            Block ground = chunk.getBlock(x, floor, z);
            int age = random.nextInt(MAX_AGE + 1);
            Schedulers.atLocation(plugin, ground.getLocation(), () -> {
                ground.setType(Material.SOUL_SAND, false);
                Block cell = ground.getRelative(BlockFace.UP);
                if (warped) {
                    plant(cell, age);
                } else {
                    cell.setType(Material.NETHER_WART, false);
                    if (cell.getBlockData() instanceof Ageable ageable) {
                        ageable.setAge(age);
                        cell.setBlockData(ageable, false);
                    }
                }
            });
        }
    }

    /** The highest valley floor in a column: soul sand or soul soil with air above. */
    private static Integer floorAt(World world, ChunkSnapshot snapshot, int x, int z) {
        for (int y = Math.min(110, world.getMaxHeight() - 3); y > Math.max(31, world.getMinHeight()); y--) {
            Material ground = snapshot.getBlockType(x, y, z);
            if ((ground == Material.SOUL_SAND || ground == Material.SOUL_SOIL)
                    && snapshot.getBlockType(x, y + 1, z).isAir()
                    && snapshot.getBlockType(x, y + 2, z).isAir()) {
                return y;
            }
        }
        return null;
    }
}
