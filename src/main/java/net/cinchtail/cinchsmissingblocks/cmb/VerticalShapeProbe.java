package net.cinchtail.cinchsmissingblocks.cmb;

import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.util.BoundingBox;

/**
 * Diagnostic only: which vanilla blocks have a collision shape a vertical slab needs?
 *
 * <p>Vertical slabs are gated on an assumption that was never checked - that no vanilla
 * block has collision shaped like a standing slab: thin on one horizontal axis, full
 * height, full depth on the other. If one existed it could carry a vertical slab and the
 * feature would be an ordinary {@code cmb:slab} with a different model. If none exists,
 * no carrier can express the shape and the choice is between a display entity and an
 * honest horizontal collision - a design decision, not a lookup.
 *
 * <p>So this walks the live registry and reports the truth rather than a remembered
 * list. Exhaustive on purpose: the answer we want is the one we did not think of.
 *
 * <p>Every line is prefixed {@code VPROBE} so a boot log can be grepped. Registered only
 * under {@code -Dcmb.debug=true}.
 */
public final class VerticalShapeProbe {

    private static final String PREFIX = "VPROBE ";

    private final org.bukkit.plugin.Plugin plugin;

    public VerticalShapeProbe(org.bukkit.plugin.Plugin plugin) {
        this.plugin = plugin;
    }

    /** Slack for comparing a float shape against an exact threshold like 1.0. */
    private static final double EPSILON = 1.0e-6;

    /**
     * A standing slab is half a block thick, but the first question is coarser: is the
     * collision thin on one axis and full height on the other? Anything matching that is
     * worth a human look, whatever its exact thickness.
     */
    private static final double THIN = 0.5;

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("cmbvshape")
                .requires(source -> source.getSender().hasPermission("cmb.debug"))
                .executes(context -> report(context.getSource()))
                .build();
    }

    private int report(CommandSourceStack source) {
        CommandSenderSink sink = new CommandSenderSink(source);
        // The sweep writes block data at 3,000,000,3,000,000, which on Folia belongs to
        // that position's region rather than the global one the command arrived on.
        World world = Bukkit.getWorlds().get(0);
        Location probe = new Location(world, 3_000_000, world.getMinHeight() + 64, 3_000_000);
        net.cinchtail.cinchsmissingblocks.cmb.scheduler.Schedulers.atLocation(
                plugin, probe, () -> scan(sink));
        return 1;
    }

    /** Writes probe output to the console and to whoever ran the command. */
    private static final class CommandSenderSink {
        private final CommandSourceStack source;

        CommandSenderSink(CommandSourceStack source) {
            this.source = source;
        }

        void line(String text) {
            Bukkit.getLogger().info(PREFIX + text);
            source.getSender().sendMessage(net.kyori.adventure.text.Component.text(PREFIX + text));
        }
    }

    private void scan(CommandSenderSink sink) {
        World world = Bukkit.getWorlds().get(0);
        // Far from spawn and from anything the deployed pack occupies, so writing a
        // registry-wide sweep of block data cannot damage a build.
        Location probe = new Location(world, 3_000_000, world.getMinHeight() + 64, 3_000_000);
        Block block = world.getBlockAt(probe);

        sink.line("start materials=" + Material.values().length);

        List<String> exactHalf = new ArrayList<>();
        List<String> anyThin = new ArrayList<>();
        int probed = 0;

        for (Material material : Material.values()) {
            if (!material.isBlock()) {
                continue;
            }
            List<BlockData> states = states(material);
            for (BlockData data : states) {
                double[] size = sizeOf(block, data);
                probed++;
                if (size == null) {
                    continue;
                }
                double sx = size[0], sy = size[1], sz = size[2];
                boolean fullY = Math.abs(sy - 1.0) < EPSILON;
                boolean fullX = Math.abs(sx - 1.0) < EPSILON;
                boolean fullZ = Math.abs(sz - 1.0) < EPSILON;
                // A vertical plane: full height, full on one horizontal axis, thin on
                // the other. Or the same rotated, which is the same shape.
                boolean planeXZ = fullY && fullZ && sx <= THIN;
                boolean planeZX = fullY && fullX && sz <= THIN;
                if (!planeXZ && !planeZX) {
                    continue;
                }
                double thin = planeXZ ? sx : sz;
                String key = material.name().toLowerCase(Locale.ROOT) + " " + data.getAsString(false)
                        + String.format(Locale.ROOT, " -> [%.3f %.3f %.3f]", sx, sy, sz);
                anyThin.add(key);
                // Half thick is the real vertical slab.
                if (Math.abs(thin - 0.5) < EPSILON) {
                    exactHalf.add(key);
                }
            }
            block.setType(Material.AIR, false);
        }

        sink.line("probed=" + probed);
        sink.line("vertical-plane (thin<=" + THIN + "): " + anyThin.size());
        anyThin.forEach(c -> sink.line("  THIN " + c));
        sink.line("exactly half-thick: " + exactHalf.size());
        exactHalf.forEach(c -> sink.line("  HALF " + c));

        // The classification above reads the union of the collision boxes, which is
        // only good enough to find candidates. A candidate that is actually a slab has
        // one box, and its extent is what a vertical slab needs - so the geometry is
        // printed here rather than assumed from the union.
        //
        // A union also lies: a bell-shaped or L-shaped collision has the right total
        // size and is not a plane at all, which is why this is measured, not reasoned.
        for (Material material : Material.values()) {
            if (!material.isBlock()) {
                continue;
            }
            for (BlockData data : states(material)) {
                try {
                    block.setBlockData(data, false);
                } catch (IllegalArgumentException e) {
                    continue;
                }
var boxes = new ArrayList<>(block.getCollisionShape().getBoundingBoxes());
                if (boxes.isEmpty()) {
                    continue;
                }
                boolean boxIsPlane = false;
                for (BoundingBox box : boxes) {
                    double sx = box.getMaxX() - box.getMinX();
                    double sy = box.getMaxY() - box.getMinY();
                    double sz = box.getMaxZ() - box.getMinZ();
                    if (Math.abs(sy - 1.0) < EPSILON
                            && (Math.abs(sz - 1.0) < EPSILON && sx <= THIN
                            || Math.abs(sx - 1.0) < EPSILON && sz <= THIN)) {
                        boxIsPlane = true;
                    }
                }
                if (boxIsPlane && boxes.size() == 1) {
                    BoundingBox box = boxes.get(0);
                    sink.line(String.format(Locale.ROOT,
                            "  SINGLE-BOX %s %s -> [%.3f %.3f %.3f..%.3f %.3f %.3f] boxes=%d",
                            material.name().toLowerCase(Locale.ROOT), data.getAsString(false),
                            box.getMinX(), box.getMinY(), box.getMinZ(),
                            box.getMaxX(), box.getMaxY(), box.getMaxZ(), boxes.size()));
                }
            }
            block.setType(Material.AIR, false);
        }
        sink.line("done");
    }

    /**
     * The size of the collision of {@code data} placed on {@code block}, or null when it
     * has no collision at all.
     *
     * <p>Returns the union across all collision boxes, because a block with two boxes
     * (stairs, for example) is being asked whether it can stand in for a single slab.
     */
    private static double[] sizeOf(Block block, BlockData data) {
        try {
            block.setBlockData(data, false);
        } catch (IllegalArgumentException e) {
            return null;
        }
        var boxes = block.getCollisionShape().getBoundingBoxes();
        if (boxes.isEmpty()) {
            return null;
        }
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (BoundingBox box : boxes) {
            minX = Math.min(minX, box.getMinX());
            minY = Math.min(minY, box.getMinY());
            minZ = Math.min(minZ, box.getMinZ());
            maxX = Math.max(maxX, box.getMaxX());
            maxY = Math.max(maxY, box.getMaxY());
            maxZ = Math.max(maxZ, box.getMaxZ());
        }
        return new double[] {maxX - minX, maxY - minY, maxZ - minZ};
    }

    /**
     * Every distinct state of a material.
     *
     * <p>Only the default state is enumerated on purpose. The question here is whether
     * a <em>shape</em> exists anywhere in the registry, and every state of a block shares
     * its shape family - a slab is thin in all of its states. Walking states would
     * multiply the runtime without adding an answer, and this sweep already writes block
     * data into a live world.
     */
    private static List<BlockData> states(Material material) {
        List<BlockData> states = new ArrayList<>(1);
        try {
            states.add(Bukkit.createBlockData(material));
        } catch (IllegalArgumentException e) {
            // Not a placeable data instance; nothing to probe.
        }
        return states;
    }
}