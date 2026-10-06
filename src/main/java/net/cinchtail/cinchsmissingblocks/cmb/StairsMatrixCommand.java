package net.cinchtail.cinchsmissingblocks.cmb;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.UpdateFlags;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.block.property.type.SingleBlockHalf;
import net.momirealms.craftengine.core.util.Direction;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.momirealms.craftengine.bukkit.api.event.CraftEngineReloadEvent;
import net.momirealms.craftengine.bukkit.block.BukkitBlockManager;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/**
 * Layer 1a of the cmb:stairs matrix: the state-space contract, checked in process.
 *
 * <p>The invariant the whole carrier design rests on:
 *
 * <pre>
 *   CMB's behavioural state projection must equal the carrier's projection.
 * </pre>
 *
 * cmb:stairs only ever manipulates the two properties it declares, {@code facing} and
 * {@code half}. If the carrier CraftEngine bound to that combination disagrees, the
 * collision shape comes from the wrong configuration and the block is silently wrong.
 *
 * <p>Deliberately does NOT cover the player-facing half. {@code getHorizontalDirection()}
 * returns {@code NORTH} when the context has no player, and neither
 * {@code FakeBukkitServerPlayer} nor a synthetic {@code Player} can supply a facing
 * without a connected client - see the command output. Placement direction is layer 1b
 * and needs a real player or bot; it is reported as NOT RUN rather than implied by an
 * 8/8 result here.
 *
 * <p>The subject is chosen from the allocator's retained set ({@link RetainedBlocks}),
 * never hard-coded: four runs probed calcite_stairs, which the capacity pass defers, and
 * read its absence from the registry as a timing problem. An explicitly requested
 * deferred block is refused with the allocator's reason.
 *
 * <p>Runs automatically on {@code CraftEngineReloadEvent} under {@code -Dcmb.debug=true}.
 * RegistryTimingProbe established that the block registry is empty at onEnable and
 * ServerLoadEvent and fully populated by that event, on cold and warm boots alike.
 */
public final class StairsMatrixCommand implements Listener {

    /** Preferred subject; used only if the allocator retained it. */
    static final String PREFERRED = "cinchsmissingblocks:polished_calcite_stairs";

    /**
     * Build the command tree.
     *
     * <p>Paper forbids {@code JavaPlugin#getCommand} during a plugin's startup, so
     * the command is declared in paper-plugin.yml and bound through
     * LifecycleEvents.COMMANDS instead. That keeps it completely orthogonal to the
     * CraftEngine behaviour registration in onLoad().
     */
    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("cmbstairs")
                .requires(source -> source.getSender().hasPermission("cmb.debug"))
                // greedyString: a namespaced id contains ':', which string() only
                // accepts quoted, so an unquoted id never reached the command.
                .then(Commands.argument("block", StringArgumentType.greedyString())
                        .executes(context -> run(context.getSource(),
                                StringArgumentType.getString(context, "block")))
                        .build())
                .executes(context -> run(context.getSource(), null))
                .build();
    }

    private int run(CommandSourceStack source, String blockId) {
        return guarded(source.getSender(), blockId);
    }

    @EventHandler
    public void onCraftEngineReload(CraftEngineReloadEvent event) {
        guarded(Bukkit.getConsoleSender(), null);
    }

    private int guarded(CommandSender sender, String requested) {
        try {
            String subject = selectSubject(sender, requested);
            return subject == null ? 0 : matrix(sender, subject);
        } catch (Throwable t) {
            // A diagnostic must never take the plugin down. Report and carry on so
            // cmb:stairs stays registered and the server keeps running.
            sender.sendMessage("CMB STAIRS - IN-PROCESS MATRIX");
            sender.sendMessage("  ERROR " + t);
            return 0;
        }
    }

    /**
     * The block to test, or null after reporting why there is none.
     *
     * <p>No fallback to a hard-coded id: if the allocation cannot be read, the matrix
     * does not know what exists and must not guess.
     */
    private String selectSubject(CommandSender sender, String requested) throws Exception {
        RetainedBlocks retained = RetainedBlocks.load();
        List<String> stairs = retained.retained("stairs");
        if (requested != null) {
            if (!retained.isCanonical(requested)) {
                sender.sendMessage("CMB STAIRS - NOT RUN: " + requested
                        + " is not a canonical Paperized block");
                return null;
            }
            String reason = retained.deferralReason(requested);
            if (reason != null || !stairs.contains(requested)) {
                sender.sendMessage("CMB STAIRS - NOT RUN: " + requested
                        + " was not retained by the allocator, so it is not a test"
                        + " subject. Reason: " + reason);
                return null;
            }
            sender.sendMessage("CMB STAIRS - subject " + requested + " (requested; retained)");
            return requested;
        }
        if (stairs.isEmpty()) {
            sender.sendMessage("CMB STAIRS - NOT RUN: the allocator retained no stairs");
            return null;
        }
        String subject = stairs.contains(PREFERRED) ? PREFERRED : stairs.getFirst();
        sender.sendMessage("CMB STAIRS - subject " + subject + " (selected from "
                + stairs.size() + " retained stairs"
                + (subject.equals(PREFERRED) ? "" : "; " + PREFERRED + " was not retained")
                + ")");
        return subject;
    }

    private static final List<Direction> FACINGS =
            List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST);
    private static final List<SingleBlockHalf> HALVES =
            List.of(SingleBlockHalf.BOTTOM, SingleBlockHalf.TOP);

    private int matrix(CommandSender sender, String blockId) {
        World world = Bukkit.getWorlds().getFirst();
        Location origin = new Location(world, 100.5, 100.0, 100.5);

        sender.sendMessage("CMB STAIRS - IN-PROCESS MATRIX");
        sender.sendMessage("  block: " + blockId);

        BlockDefinition definition = CraftEngineBlocks.byId(Key.of(blockId));
        if (definition == null) {
            // The subject is retained by the allocator, so its absence is real: either
            // the registry is still empty (we were called too early) or the block
            // failed to load. The registry size tells the two apart.
            int loaded = BukkitBlockManager.instance().loadedBlocks().size();
            sender.sendMessage("  FAIL retained block " + blockId
                    + " is not in CraftEngine's registry (loadedBlocks=" + loaded + "): "
                    + (loaded == 0 ? "registry still empty - called too early"
                                   : "the block failed to load; check 'issue(s) in file'"));
            return 1;
        }
        sender.sendMessage("  declared properties: " + definition.properties().stream()
                .map(Property::name).sorted().toList());

        Property<Direction> facing = null;
        Property<SingleBlockHalf> half = null;
        for (Property<?> property : definition.properties()) {
            if (property.name().equals("facing") && property.valueClass() == Direction.class) {
                @SuppressWarnings("unchecked")
                Property<Direction> cast = (Property<Direction>) property;
                facing = cast;
            } else if (property.name().equals("half")
                    && property.valueClass() == SingleBlockHalf.class) {
                @SuppressWarnings("unchecked")
                Property<SingleBlockHalf> cast = (Property<SingleBlockHalf>) property;
                half = cast;
            }
        }
        if (facing == null || half == null) {
            sender.sendMessage("  FAIL block does not declare both facing and half");
            return 0;
        }

        int failures = 0;
        int index = 0;
        for (Direction direction : FACINGS) {
            for (SingleBlockHalf wantedHalf : HALVES) {
                // Two apart: stairs now take corner shapes from their neighbours, so
                // adjacent probes could turn each other into corners.
                Location at = origin.clone().add(2 * index++, 0, 0);
                ImmutableBlockState desired = definition.defaultState()
                        .with(facing, direction)
                        .with(half, wantedHalf);

                String problem = placeAndVerify(sender, at, desired, direction, wantedHalf);
                if (problem != null) {
                    failures++;
                    sender.sendMessage("    FAIL " + direction + "/" + wantedHalf
                            + ": " + problem);
                }
            }
        }

        sender.sendMessage("  " + (8 - failures) + "/8 projection/carrier states "
                + (failures == 0 ? "PASS" : "FAIL"));
        sender.sendMessage("");
        sender.sendMessage("CMB STAIRS - PLAYER PLACEMENT");
        sender.sendMessage("  NOT RUN (requires a connected player or bot; "
                + "getHorizontalDirection() returns NORTH without one)");
        return failures;
    }

    /** {@code minecraft:x[a=1,b=2]} -> {a=1, b=2}. */
    private static Map<String, String> properties(String state) {
        Map<String, String> out = new LinkedHashMap<>();
        int open = state.indexOf('[');
        if (open < 0) {
            return out;
        }
        for (String pair : state.substring(open + 1, state.lastIndexOf(']')).split(",")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                out.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        return out;
    }

    /** Place one stair state and check it against its carrier. */
    private String placeAndVerify(CommandSender sender, Location at,
                                  ImmutableBlockState desired, Direction direction,
                                  SingleBlockHalf wantedHalf) {
        // Clear first. place() returns Level#setBlock's result, which is false when
        // the state is unchanged, so on a world kept from an earlier run every
        // placement "failed" without anything being wrong.
        at.getBlock().setType(org.bukkit.Material.AIR, false);
        if (!CraftEngineBlocks.place(at, desired, UpdateFlags.UPDATE_ALL, false, false)) {
            return "place failed";
        }
        Block block = at.getBlock();
        ImmutableBlockState actual = CraftEngineBlocks.getCustomBlockState(block);
        if (actual == null) {
            return "placed block is not a CraftEngine block";
        }

        String projection = actual.getPropertiesAsString();
        String carrier = actual.visualBlockState().getAsString();

        sender.sendMessage(String.format("    %-5s %-6s cmb=[%s]  carrier=%s",
                direction, wantedHalf, projection, carrier));

        // Exactly facing, half and shape. A stray waterlogged means the behaviour and
        // the allocation disagree about the state space, and would not be visible in
        // a looser comparison.
        List<String> names = actual.getProperties().stream()
                .map(Property::name).sorted().toList();
        if (!names.equals(List.of("facing", "half", "shape"))) {
            return "expected properties [facing, half, shape] but found " + names;
        }

        // Each probe stands alone, so the shape it must take is straight.
        String expectedProjection =
                "facing=" + direction.name().toLowerCase(Locale.ROOT)
                        + ",half=" + wantedHalf.name().toLowerCase(Locale.ROOT)
                        + ",shape=straight";
        if (!projection.equals(expectedProjection)) {
            return "cmb projection [" + projection + "] != [" + expectedProjection + "]";
        }

        // The carrier must agree on facing and half, and be shape=straight, or the
        // client predicts collision from the wrong shape. Read from the carrier's
        // canonical serialisation: getProperty returns the raw Minecraft enum, whose
        // toString is lowercase, so comparing it with Direction.name() would fail
        // every state and look like a behaviour defect.
        Map<String, String> carrierProps = properties(carrier);
        String wantFacing = direction.name().toLowerCase(Locale.ROOT);
        String wantHalf = wantedHalf.name().toLowerCase(Locale.ROOT);
        if (!wantFacing.equals(carrierProps.get("facing"))) {
            return "carrier facing " + carrierProps.get("facing") + " != " + wantFacing;
        }
        if (!wantHalf.equals(carrierProps.get("half"))) {
            return "carrier half " + carrierProps.get("half") + " != " + wantHalf;
        }
        if (!"straight".equals(carrierProps.get("shape"))) {
            return "carrier shape " + carrierProps.get("shape")
                    + " is not collision-safe (expected straight)";
        }
        return null;
    }

}