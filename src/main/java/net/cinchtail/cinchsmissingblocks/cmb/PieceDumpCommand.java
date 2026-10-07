package net.cinchtail.cinchsmissingblocks.cmb;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/**
 * {@code /cmbpieces x1 y1 z1 x2 y2 z2} - every piece in a box, one line per piece: cell,
 * kind, variant. Debug only (-Dcmb.debug=true).
 *
 * <p>For comparing builds as text: a wall built row by row and the same wall built
 * column by column must dump identically, because a piece's shape is meant to be a
 * function of what surrounds it, not of the order things were placed in. Vanilla walls
 * are listed too, in the same notation (VANILLA_WALL), so vanilla can be the reference.
 * Lines go to the sender and the server log, sorted, so two dumps diff cleanly.
 */
final class PieceDumpCommand {

    private final Plugin plugin;

    PieceDumpCommand(Plugin plugin) {
        this.plugin = plugin;
    }

    LiteralCommandNode<CommandSourceStack> build() {
        var root = Commands.literal("cmbpieces")
                .requires(source -> source.getSender().hasPermission("cmb.debug"));
        String[] names = {"x1", "y1", "z1", "x2", "y2", "z2"};
        List<RequiredArgumentBuilder<CommandSourceStack, Integer>> args = new ArrayList<>();
        for (String name : names) {
            args.add(Commands.argument(name, IntegerArgumentType.integer()));
        }
        RequiredArgumentBuilder<CommandSourceStack, Integer> last = args.get(args.size() - 1).executes(this::dump);
        for (int i = args.size() - 2; i >= 0; i--) {
            last = args.get(i).then(last);
        }
        return root.then(last).build();
    }

    private int dump(CommandContext<CommandSourceStack> ctx) {
        int[] v = new int[6];
        String[] names = {"x1", "y1", "z1", "x2", "y2", "z2"};
        for (int i = 0; i < 6; i++) {
            v[i] = IntegerArgumentType.getInteger(ctx, names[i]);
        }
        Entity executor = ctx.getSource().getExecutor();
        World world = executor != null ? executor.getWorld() : Bukkit.getWorlds().getFirst();
        List<String> lines = new ArrayList<>();
        for (int x = Math.min(v[0], v[3]); x <= Math.max(v[0], v[3]); x++) {
            for (int y = Math.min(v[1], v[4]); y <= Math.max(v[1], v[4]); y++) {
                for (int z = Math.min(v[2], v[5]); z <= Math.max(v[2], v[5]); z++) {
                    org.bukkit.block.Block block = world.getBlockAt(x, y, z);
                    for (VerticalSlabListener.Plate plate : VerticalSlabListener.platesIn(block)) {
                        lines.add(x + " " + y + " " + z + " " + plate.kind() + " "
                                + plate.furniture().currentVariant().name());
                    }
                    // A vanilla wall, in the piece walls' notation: the reference a piece
                    // wall built the same way is compared against.
                    if (block.getBlockData() instanceof org.bukkit.block.data.type.Wall wall) {
                        StringBuilder shape = new StringBuilder();
                        for (org.bukkit.block.BlockFace face : new org.bukkit.block.BlockFace[] {
                                org.bukkit.block.BlockFace.NORTH, org.bukkit.block.BlockFace.EAST,
                                org.bukkit.block.BlockFace.SOUTH, org.bukkit.block.BlockFace.WEST}) {
                            shape.append(face.name().toLowerCase(java.util.Locale.ROOT).charAt(0))
                                    .append(wall.getHeight(face).ordinal());
                        }
                        lines.add(x + " " + y + " " + z + " VANILLA_WALL " + shape + "p" + (wall.isUp() ? 1 : 0));
                    }
                }
            }
        }
        lines.sort(null);
        plugin.getLogger().info("cmbpieces " + String.join(" ", java.util.Arrays.stream(v)
                .mapToObj(Integer::toString).toList()) + ": " + lines.size() + " pieces");
        for (String line : lines) {
            plugin.getLogger().info("cmbpieces | " + line);
            ctx.getSource().getSender().sendMessage(line);
        }
        return Command.SINGLE_SUCCESS;
    }
}
