package net.cinchtail.cinchsmissingblocks.cmb;

import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import org.bukkit.entity.Player;

/**
 * Debug: what {@link VerticalSlabListener} sees for the vertical slab you are looking at.
 *
 * <p>{@code /cmbvslab} prints the slab's collider union, the cell it resolves to, and
 * the face its back covers. Water blocking keys entirely off that face, so a
 * {@code back=none} here means the plate's boxes did not line up with a cell face and
 * water will pass through it.
 */
public final class VerticalSlabProbe {

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("cmbvslab")
                .requires(source -> source.getSender().hasPermission("cmb.debug"))
                .executes(c -> {
                    if (!(c.getSource().getSender() instanceof Player player)) {
                        c.getSource().getSender().sendMessage("cmbvslab: players only");
                        return 0;
                    }
                    BukkitFurniture furniture = CraftEngineFurniture.rayTrace(player);
                    if (furniture == null) {
                        player.sendMessage("cmbvslab: not looking at furniture");
                        return 0;
                    }
                    VerticalSlabListener.Plate plate = VerticalSlabListener.plate(furniture);
                    if (plate == null) {
                        player.sendMessage("cmbvslab: " + furniture.id()
                                + " is not one of the CMB furniture pieces");
                        return 0;
                    }
                    double[] b = plate.box();
                    player.sendMessage("cmbvslab " + furniture.id() + " (" + plate.kind() + ")"
                            + " variant=" + furniture.currentVariant().name()
                            + " yaw=" + furniture.location().getYaw());
                    player.sendMessage(String.format("  box  (%.3f %.3f %.3f) .. (%.3f %.3f %.3f)",
                            b[0], b[1], b[2], b[3], b[4], b[5]));
                    player.sendMessage("  cell " + plate.cell().getX() + " " + plate.cell().getY()
                            + " " + plate.cell().getZ() + " (" + plate.cell().getType() + ")"
                            + "  back=" + (plate.back() == null ? "none" : plate.back())
                            + "  solid=" + plate.solid());
                    return 1;
                })
                .build();
    }
}
