package net.cinchtail.cinchsmissingblocks.cmb;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.cinchtail.cinchsmissingblocks.cmb.scheduler.Schedulers;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;

/**
 * Layer 1b: does the live server behave like what the generator produced?
 *
 * <p>Deliberately not a second source of truth. Expectations come from the pack's own
 * {@code intermediate/content.json} and {@code intermediate/allocation.json} - which
 * states a block has, which carrier each was assigned, which properties it declares.
 * This asks one question: does the running server match the generated data?
 *
 * <p>Scope is deliberately narrow. Cubes first: 61 of the served blocks are plain full
 * cubes, so a failure there is systemic rather than per-block. Pillars add only axis
 * rotation, and their collision is a full cube because they ride full-cube carriers -
 * which the generated data agrees with, and that is the point of checking against it.
 *
 * <p>Not covered here, and not pretendable from a command:
 * <ul>
 *   <li>persistence across a restart - needs a real restart;</li>
 *   <li>whether unrelated vanilla blocks still render correctly - needs a client.</li>
 * </ul>
 */
public final class Integration1bCommand implements Listener {

    private static final String PACK = "cinchsmissingblocks";

    private final CmbPlugin plugin;
    private JsonObject content;
    private JsonObject allocation;

    public Integration1bCommand(CmbPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Brigadier form, because JavaPlugin#getCommand is unavailable during startup on a
     * Paper plugin; the command is declared in paper-plugin.yml and bound here
     * through the COMMANDS lifecycle event.
     */
    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("cmb1b")
                .requires(source -> source.getSender().hasPermission("cmb.debug"))
                .then(Commands.argument("family", StringArgumentType.word())
                        // The family argument alone used to run "cubes" regardless, so
                        // /cmb1b pillars silently probed cubes again.
                        .executes(c -> run(c.getSource().getSender(),
                                StringArgumentType.getString(c, "family"), 8) ? 1 : 0)
                        .then(Commands.argument("limit", IntegerArgumentType.integer(1))
                                .executes(c -> run(c.getSource().getSender(),
                                        StringArgumentType.getString(c, "family"),
                                        IntegerArgumentType.getInteger(c, "limit"))
                                        ? 1 : 0)))
                .executes(c -> run(c.getSource().getSender(), "cubes", 8) ? 1 : 0)
                .build();
    }

    /** One generated state to probe: appearance {@code index} of block {@code id}. */
    private record Job(String id, int index, String carrier, String key,
                       Map<String, String> properties) {}

    /**
     * Where the probe cell goes relative to the player: above their head, so the cell
     * is inside their simulation distance and the drop is out of pickup reach.
     *
     * <p>A fixed cell at (0, 100, 0) looked fine but was 14 chunks from the player on
     * a server with simulation-distance 4. getBlockAt loads such a chunk only far
     * enough to place and break blocks: the drop spawned correctly and then could not
     * be found, which read as "dropped NOTHING".
     */
    private static final int CELL_ABOVE_PLAYER = 6;

    /** Ticks between the break and collecting its drop: spawned items exist next tick. */
    private static final long SETTLE_TICKS = 2L;

    /**
     * What the server did with the current probe's break, recorded from the events
     * themselves. When no drop arrives, this says where it disappeared: never
     * generated, generated then cleared, or spawned somewhere else.
     */
    private final List<String> trace = new ArrayList<>();
    private final List<java.util.UUID> spawned = new ArrayList<>();
    private Block watched;

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBreak(BlockBreakEvent event) {
        if (event.getBlock().equals(watched)) {
            trace.add("BlockBreakEvent cancelled=" + event.isCancelled()
                    + " dropItems=" + event.isDropItems());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDrop(BlockDropItemEvent event) {
        if (event.getBlock().equals(watched)) {
            List<String> items = new ArrayList<>();
            event.getItems().forEach(i -> items.add(describe(i.getItemStack())));
            trace.add("BlockDropItemEvent cancelled=" + event.isCancelled() + " items=" + items);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSpawn(ItemSpawnEvent event) {
        if (watched != null && event.getLocation().getWorld().equals(watched.getWorld())
                && event.getLocation().distanceSquared(watched.getLocation().add(0.5, 0.5, 0.5)) < 64) {
            var at = event.getLocation();
            trace.add(String.format("ItemSpawnEvent cancelled=%s %s at %.2f,%.2f,%.2f",
                    event.isCancelled(), describe(event.getEntity().getItemStack()),
                    at.getX(), at.getY(), at.getZ()));
            spawned.add(event.getEntity().getUniqueId());
        }
    }

    /** Follow the spawned drop: who removed it, and why. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRemove(EntityRemoveEvent event) {
        if (spawned.contains(event.getEntity().getUniqueId())) {
            var at = event.getEntity().getLocation();
            trace.add(String.format("EntityRemoveEvent cause=%s at %.2f,%.2f,%.2f",
                    event.getCause(), at.getX(), at.getY(), at.getZ()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPickup(EntityPickupItemEvent event) {
        if (spawned.contains(event.getItem().getUniqueId())) {
            trace.add("EntityPickupItemEvent by " + event.getEntity().getName()
                    + " cancelled=" + event.isCancelled());
        }
    }

    private static String describe(ItemStack stack) {
        return CraftEngineItems.isCustomItem(stack)
                ? CraftEngineItems.getCustomItemId(stack).asString()
                : stack.getType().getKey().toString();
    }

    /**
     * Command entry point.
     *
     * <p>Plugin commands run on the global region thread under Folia, but everything below
     * this point reads and writes blocks in the player's region and drives that player's
     * inventory. Both are region-owned, so the whole probe is handed to the cell's region
     * rather than run where the command landed.
     */
    private boolean run(CommandSender sender, String group, int limit) {
        if (!load()) {
            sender.sendMessage("could not read " + PACK + "/intermediate/*.json");
            return true;
        }
        if (sender instanceof Player player) {
            Schedulers.atLocation(plugin, player.getLocation(),
                    () -> runInRegion(sender, group, limit));
            return true;
        }
        return runInRegion(sender, group, limit);
    }

    private boolean runInRegion(CommandSender sender, String group, int limit) {
        List<String> ids = served(group, limit);
        sender.sendMessage("1b " + group.toUpperCase() + " probe  served="
                + served(group, Integer.MAX_VALUE).size() + "  probing=" + ids.size());
        if (ids.isEmpty()) {
            sender.sendMessage("  nothing served for that family");
            return true;
        }

        // The drop check needs a real player break: Player#breakBlock runs the server
        // side of a survival left-click (BlockBreakEvent, the held tool, the normal
        // drop path). No silent fallback to breakNaturally() - that is what produced
        // the earlier false results.
        Player breaker = sender instanceof Player p ? p : null;
        String noDrop = breaker == null ? "NOT RUN: run /cmb1b as an in-game player"
                : breaker.getGameMode() == GameMode.CREATIVE
                ? "NOT RUN: creative mode drops nothing; switch to survival" : null;

        List<Job> jobs = new ArrayList<>();
        for (String id : ids) {
            JsonArray states = statesFor(id);
            JsonArray appearances = content.getAsJsonObject("blocks").getAsJsonObject(id)
                    .getAsJsonArray("appearances");
            for (int i = 0; i < states.size() && i < appearances.size(); i++) {
                JsonObject appearance = appearances.get(i).getAsJsonObject();
                Map<String, String> props = new TreeMap<>();
                JsonObject declared = appearance.getAsJsonObject("properties");
                if (declared != null) {
                    declared.entrySet().forEach(e -> props.put(e.getKey(), e.getValue().getAsString()));
                }
                jobs.add(new Job(id, i, states.get(i).getAsString(),
                        appearance.get("key").getAsString(), props));
            }
        }

        World world = breaker != null ? breaker.getWorld() : Bukkit.getWorlds().getFirst();
        Block cell = breaker != null
                ? world.getBlockAt(breaker.getLocation()).getRelative(0, CELL_ABOVE_PLAYER, 0)
                : world.getBlockAt(0, 100, 0);
        if (cell.getY() >= world.getMaxHeight() - 1) {
            cell = world.getBlockAt(cell.getX(), world.getMaxHeight() - 2, cell.getZ());
        }
        // The cell and its floor are restored afterwards: a probe must not leave holes
        // in whatever was built there.
        org.bukkit.block.data.BlockData cellWas = cell.getBlockData();
        org.bukkit.block.data.BlockData floorWas = cell.getRelative(0, -1, 0).getBlockData();
        sender.sendMessage(String.format("  cell %d,%d,%d  chunk load level %s", cell.getX(),
                cell.getY(), cell.getZ(), cell.getChunk().getLoadLevel()));
        if (noDrop == null && cell.getChunk().getLoadLevel() != Chunk.LoadLevel.ENTITY_TICKING) {
            // Entities in a chunk that is not entity-ticking cannot be found again, so a
            // drop check there can only produce a false failure.
            noDrop = "NOT RUN: the probe cell's chunk is not entity-ticking";
        }
        int[] tally = new int[4]; // state ok, state fail, drop pass, drop fail
        Block finalCell = cell;
        step(sender, breaker, noDrop, world, cell, jobs, 0, tally, () -> {
            finalCell.setBlockData(cellWas, false);
            finalCell.getRelative(0, -1, 0).setBlockData(floorWas, false);
        });
        return true;
    }

    /** Probe jobs[i], then schedule jobs[i + 1]. Strictly sequential. */
    private void step(CommandSender sender, Player breaker, String noDrop, World world,
                      Block block, List<Job> jobs, int i, int[] tally, Runnable restore) {
        if (i >= jobs.size()) {
            restore.run();
            sender.sendMessage("  state: " + tally[0] + " ok, " + tally[1] + " fail   drop: "
                    + (noDrop != null ? noDrop : tally[2] + " pass, " + tally[3] + " fail"));
            sender.sendMessage("  not covered: restart persistence, vanilla collateral "
                    + "(observe a client)");
            return;
        }
        Job job = jobs.get(i);
        Block floor = block.getRelative(0, -1, 0);
        floor.setType(Material.STONE, false);
        clearDrops(block);

        sender.sendMessage("  [" + (i + 1) + "/" + jobs.size() + "] " + job.id()
                + (job.key().isEmpty() ? "" : " {" + job.key() + "}"));
        List<String> problems = probe(sender, job, block);
        // State and drop are reported independently, so one failing subsystem
        // cannot hide the other.
        if (problems.isEmpty()) {
            tally[0]++;
            sender.sendMessage("      state: ok");
        } else {
            tally[1]++;
            problems.forEach(p -> sender.sendMessage("      state: FAIL " + p));
        }

        Runnable next = () -> {
            block.setType(Material.AIR, false);
            floor.setType(Material.AIR, false);
            clearDrops(block);
            // Region-scoped, not global: this reads and writes blocks at a specific
            // position, which under Folia belongs to that position's region thread.
            Schedulers.globalLater(plugin,
                    () -> Schedulers.atLocation(plugin, block.getLocation(),
                            () -> step(sender, breaker, noDrop, world, block, jobs, i + 1,
                                    tally, restore)),
                    1L);
        };

        if (noDrop != null || CraftEngineBlocks.getCustomBlockState(block) == null) {
            sender.sendMessage("      drop:  " + (noDrop != null ? noDrop
                    : "NOT RUN: nothing was placed to break"));
            next.run();
            return;
        }

        // Break with a pickaxe, then give the player their item back: the result must
        // not depend on whatever they happened to be holding.
        trace.clear();
        spawned.clear();
        watched = block;
        ItemStack held = breaker.getInventory().getItemInMainHand();
        breaker.getInventory().setItemInMainHand(new ItemStack(Material.NETHERITE_PICKAXE));
        boolean broken;
        try {
            broken = breaker.breakBlock(block);
        } finally {
            breaker.getInventory().setItemInMainHand(held);
        }
        if (!broken) {
            tally[3]++;
            sender.sendMessage("      drop:  FAIL player break was refused (BlockBreakEvent "
                    + "cancelled, spawn protection, or out of world)");
            next.run();
            return;
        }

        Schedulers.globalLater(plugin, () -> Schedulers.atLocation(plugin, block.getLocation(), () -> {
            List<String> drops = dropsAround(block);
            int want = expectedDrops(job);
            String verdict;
            if (drops.size() == want && drops.stream().allMatch(job.id()::equals)) {
                tally[2]++;
                verdict = "PASS " + drops.get(0);
            } else {
                tally[3]++;
                String carrierItem = job.carrier().split("\\[", 2)[0];
                if (drops.isEmpty()) {
                    verdict = "FAIL dropped NOTHING through the player path - loot defect";
                } else if (drops.contains(carrierItem)) {
                    verdict = "FAIL CARRIER LEAKAGE: dropped " + drops + " (carrier "
                            + carrierItem + "), expected " + job.id();
                } else {
                    verdict = "FAIL dropped " + drops + ", expected exactly " + want + " x "
                            + job.id();
                }
            }
            sender.sendMessage("      drop:  " + verdict);
            if (!verdict.startsWith("PASS")) {
                // Where is the drop now, if anywhere?
                for (java.util.UUID id : spawned) {
                    org.bukkit.entity.Entity entity = Bukkit.getEntity(id);
                    if (entity == null || !entity.isValid()) {
                        trace.add("at collection: spawned item " + id + " no longer exists");
                    } else {
                        var at = entity.getLocation();
                        trace.add(String.format("at collection: spawned item still exists at "
                                + "%.2f,%.2f,%.2f (in box: %s)", at.getX(), at.getY(), at.getZ(),
                                dropBox(block).overlaps(entity.getBoundingBox())));
                    }
                }
                if (trace.isEmpty()) {
                    sender.sendMessage("      trace: no break, drop or spawn event at the cell");
                }
                trace.forEach(t -> sender.sendMessage("      trace: " + t));
            }
            watched = null;
            next.run();
        }), SETTLE_TICKS);
    }

    /**
     * How many items the generated loot says this state drops.
     *
     * <p>Read from content.json, not assumed: a block on CraftEngine's slab loot
     * template drops two when its state is {@code type=double}, everything else one.
     */
    private int expectedDrops(Job job) {
        JsonObject block = content.getAsJsonObject("blocks").getAsJsonObject(job.id());
        JsonObject loot = block == null ? null : block.getAsJsonObject("loot");
        boolean slabLoot = loot != null && loot.has("template")
                && "default:loot_table/slab".equals(loot.get("template").getAsString());
        return slabLoot && "double".equals(job.properties().get("type")) ? 2 : 1;
    }

    /** Place one generated state and compare the live result against the pack. */
    private List<String> probe(CommandSender sender, Job job, Block block) {
        List<String> problems = new ArrayList<>();
        BlockDefinition definition = CraftEngineBlocks.byId(Key.of(job.id()));
        if (definition == null) {
            problems.add("block is not registered with CraftEngine");
            return problems;
        }
        // Whether the generated loot: reached CraftEngine at all. A null here means the
        // drop check cannot pass, and says why before any break happens.
        sender.sendMessage("      loot:      " + (definition.loot() != null
                ? "loaded" : "MISSING (no loot table on the live block)"));
        if (definition.loot() == null) {
            problems.add("live block has no loot table");
        }

        // The expected state is the generated appearance itself, not the carrier's
        // properties: a pillar's carrier is a full cube with no axis at all.
        ImmutableBlockState desired = definition.defaultState();
        for (Map.Entry<String, String> e : job.properties().entrySet()) {
            Property<?> property = definition.getProperty(e.getKey());
            if (property == null) {
                problems.add("generated state names " + e.getKey()
                        + ", which the live block does not declare");
                continue;
            }
            desired = stateWith(desired, property, e.getValue());
        }

        // The collision to expect is the carrier's own: place the carrier state as a
        // real vanilla block first and record its boxes. A hard-coded FULL_CUBE held
        // only while every carrier was a full cube; stairs ride copper stairs.
        List<String> expectedBoxes;
        try {
            block.setBlockData(Bukkit.createBlockData(job.carrier()), false);
            expectedBoxes = boxes(block);
        } catch (IllegalArgumentException e) {
            problems.add("carrier " + job.carrier() + " is not a valid vanilla state");
            return problems;
        }
        block.setType(Material.AIR, false);
        if (!CraftEngineBlocks.place(block.getLocation(), desired, false, false)) {
            problems.add("place() returned false");
            return problems;
        }
        ImmutableBlockState live = CraftEngineBlocks.getCustomBlockState(block);
        if (live == null) {
            problems.add("placed block is not a CraftEngine block");
            return problems;
        }

        String liveCarrier = live.visualBlockState().getAsString();
        String liveProjection = live.getPropertiesAsString();
        String wantProjection = String.join(",", flattened(job.properties()));
        if (!liveProjection.equals(wantProjection)) {
            problems.add("state " + liveProjection + " != generated " + wantProjection);
        }
        if (!liveCarrier.equals(job.carrier())) {
            problems.add("carrier " + liveCarrier + " != allocated " + job.carrier());
        }
        String collision = describe(block);
        List<String> liveBoxes = boxes(block);
        if (!liveBoxes.equals(expectedBoxes)) {
            problems.add("collision " + liveBoxes + " != carrier's own " + expectedBoxes);
        } else {
            collision += "  (matches the carrier's vanilla collision)";
        }
        sender.sendMessage("      state:     " + liveProjection);
        sender.sendMessage("      carrier:   " + liveCarrier);
        sender.sendMessage("      collision: " + collision);
        String declaredModel = declaredModel(job.id(), job.key());
        sender.sendMessage("      model:     " + declaredModel
                + (declaredModel.isEmpty() ? "" : "  (declared by the pack)"));
        return problems;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ImmutableBlockState stateWith(ImmutableBlockState state, Property<?> property,
                                                String value) {
        return state.with((Property) property, ((Property) property).valueByName(value));
    }

    private static List<String> flattened(Map<String, String> want) {
        List<String> parts = new ArrayList<>();
        want.forEach((k, v) -> parts.add(k + "=" + v));
        return parts;
    }

    /** Collision boxes relative to the block, rounded, in a stable order. */
    private static List<String> boxes(Block block) {
        List<String> out = new ArrayList<>();
        for (BoundingBox box : block.getCollisionShape().getBoundingBoxes()) {
            out.add(String.format(java.util.Locale.ROOT, "[%.3f,%.3f,%.3f..%.3f,%.3f,%.3f]",
                    box.getMinX(), box.getMinY(), box.getMinZ(),
                    box.getMaxX(), box.getMaxY(), box.getMaxZ()));
        }
        out.sort(null);
        return out;
    }

    /** VoxelShape exposes only getBoundingBoxes(); there is no isEmpty/getMinX. */
    private static String describe(Block block) {
        var boxes = block.getCollisionShape().getBoundingBoxes();
        if (boxes.isEmpty()) {
            return "EMPTY";
        }
        double minX = 1, minZ = 1, maxX = 0, maxZ = 0;
        for (BoundingBox box : boxes) {
            minX = Math.min(minX, box.getMinX());
            minZ = Math.min(minZ, box.getMinZ());
            maxX = Math.max(maxX, box.getMaxX());
            maxZ = Math.max(maxZ, box.getMaxZ());
        }
        boolean full = minX <= 0.001 && maxX >= 0.999 && minZ <= 0.001 && maxZ >= 0.999;
        return full ? "FULL_CUBE"
                : String.format("SHAPED[%.2f..%.2f, %.2f..%.2f]", minX, maxX, minZ, maxZ);
    }

    /** Where a drop from {@code block} can be: on the floor, around the block centre. */
    private static BoundingBox dropBox(Block block) {
        return new BoundingBox(block.getX() - 0.5, block.getY() - 0.05, block.getZ() - 0.5,
                block.getX() + 1.5, block.getY() + 1.5, block.getZ() + 1.5);
    }

    private static void clearDrops(Block block) {
        block.getWorld().getNearbyEntities(dropBox(block), e -> e instanceof Item)
                .forEach(org.bukkit.entity.Entity::remove);
    }

    private static List<String> dropsAround(Block block) {
        List<String> out = new ArrayList<>();
        for (org.bukkit.entity.Entity entity : block.getWorld()
                .getNearbyEntities(dropBox(block), e -> e instanceof Item)) {
            ItemStack stack = ((Item) entity).getItemStack();
            if (stack.getType() == Material.AIR) {
                continue;
            }
            String name = describe(stack);
            for (int n = 0; n < stack.getAmount(); n++) {
                out.add(name);
            }
        }
        return out;
    }

    // -- expectations, read from the generated pack ---------------------------

    /**
     * The model the pack declares for this state, from content.json.
     *
     * <p>A declaration check, not a render check: CraftEngine exposes no public
     * accessor for the model a live block resolved to, and rendering is client-side
     * anyway. It confirms the pack is self-consistent with its own allocation.
     */
    private String declaredModel(String id, String projection) {
        JsonObject block = content.getAsJsonObject("blocks").getAsJsonObject(id);
        if (block == null) {
            return "";
        }
        for (var element : block.getAsJsonArray("appearances")) {
            JsonObject appearance = element.getAsJsonObject();
            if (!projection.equals(appearance.get("key").getAsString())) {
                continue;
            }
            JsonObject model = appearance.getAsJsonObject("model");
            if (model == null) {
                return "";
            }
            if (model.has("models")) {
                return model.getAsJsonArray("models").get(0).getAsJsonObject()
                        .get("path").getAsString();
            }
            String path = model.get("path").getAsString();
            StringBuilder out = new StringBuilder(path);
            for (String axis : List.of("x", "y", "z")) {
                if (model.has(axis)) {
                    out.append(' ').append(axis).append('=').append(model.get(axis));
                }
            }
            if (model.has("uvlock") && model.get("uvlock").getAsBoolean()) {
                out.append(" uvlock");
            }
            return out.toString();
        }
        return "";
    }

    private JsonArray statesFor(String id) {
        var states = allocation.getAsJsonObject("assigned").get(id);
        return states == null || states.isJsonNull() ? new JsonArray() : states.getAsJsonArray();
    }

    /** Accept the plural spelling people type; the content calls it "cube". */
    private static String normalise(String family) {
        return switch (family) {
            case "cubes", "pillars" -> family.equals("cubes") ? "cube" : "pillar";
            case "pillar" -> "pillar";
            case "panes" -> "pane";
            case "walls" -> "wall";
            case "slabs" -> "slab";
            case "stairs" -> "stairs";
            default -> family;
        };
    }

    private List<String> served(String family, int limit) {
        String want = normalise(family);
        List<String> out = new ArrayList<>();
        var assigned = allocation.getAsJsonObject("assigned");
        var blocks = content.getAsJsonObject("blocks");
        for (Map.Entry<String, JsonElement> entry : assigned.entrySet()) {
            String id = entry.getKey();
            if (id.startsWith("minecraft:")) {
                continue;
            }
            JsonObject block = blocks.getAsJsonObject(id);
            if (block == null || !want.equals(block.get("family").getAsString())) {
                continue;
            }
            out.add(id);
        }
        out.sort(String::compareTo);
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    private static Map<String, String> parse(String state) {
        Map<String, String> out = new TreeMap<>();
        int open = state.indexOf('[');
        if (open < 0) {
            return out;
        }
        for (String pair : state.substring(open + 1, state.lastIndexOf(']')).split(",")) {
            int i = pair.indexOf('=');
            if (i > 0) {
                out.put(pair.substring(0, i), pair.substring(i + 1));
            }
        }
        return out;
    }

    private boolean load() {
        Path base = Bukkit.getPluginsFolder().toPath()
                .resolve("CraftEngine/resources").resolve(PACK).resolve("intermediate");
        try {
            content = JsonParser.parseString(
                    Files.readString(base.resolve("content.json"))).getAsJsonObject();
            allocation = JsonParser.parseString(
                    Files.readString(base.resolve("allocation.json"))).getAsJsonObject();
            return true;
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().warning("1b could not read pack data: " + e);
            return false;
        }
    }

}