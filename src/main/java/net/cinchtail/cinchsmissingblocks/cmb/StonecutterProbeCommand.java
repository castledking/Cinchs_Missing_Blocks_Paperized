package net.cinchtail.cinchsmissingblocks.cmb;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.ArrayList;
import java.util.List;
import net.cinchtail.cinchsmissingblocks.cmb.scheduler.Schedulers;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.item.BukkitItemDefinition;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.StonecuttingRecipe;
import org.bukkit.inventory.view.StonecutterView;
import org.bukkit.plugin.Plugin;

/**
 * {@code /cmbcut <item>} - what a stonecutter offers for an item, asked of a real one.
 * Debug only (-Dcmb.debug=true).
 *
 * <p>Opens a stonecutter for the player, puts the item (a CraftEngine id or a vanilla one)
 * in its input, and lists, numbered, the recipes the stonecutter itself selected on the next
 * tick: the server's own matching, CraftEngine's custom ingredients included. The stonecutter
 * stays open, so a client can press a recipe's button (the index), and {@code /cmbcut ?}
 * then prints what the result slot holds - the whole path a player's click takes. Lines go
 * to the player and the server log.
 */
final class StonecutterProbeCommand {

    private final Plugin plugin;

    StonecutterProbeCommand(Plugin plugin) {
        this.plugin = plugin;
    }

    LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("cmbcut")
                .requires(source -> source.getSender().hasPermission("cmb.debug"))
                .then(Commands.argument("item", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            if (!(ctx.getSource().getExecutor() instanceof Player player)) {
                                ctx.getSource().getSender().sendMessage("cmbcut: run it as a player");
                                return 0;
                            }
                            probe(player, StringArgumentType.getString(ctx, "item").trim());
                            return Command.SINGLE_SUCCESS;
                        }))
                .build();
    }

    private void probe(Player player, String id) {
        if (id.equals("?")) {
            ItemStack result = player.getOpenInventory() instanceof StonecutterView view
                    ? view.getTopInventory().getItem(1) : null;
            String line = result == null || result.getType().isAir() ? "empty" : describe(result);
            plugin.getLogger().info("cmbcut result: " + line);
            player.sendMessage("cmbcut result: " + line);
            return;
        }
        ItemStack input = stack(id);
        if (input == null) {
            player.sendMessage("cmbcut: no such item " + id);
            return;
        }
        InventoryView opened = player.openStonecutter(null, true);
        if (!(opened instanceof StonecutterView view)) {
            player.sendMessage("cmbcut: could not open a stonecutter");
            return;
        }
        view.getTopInventory().setItem(0, input);
        Schedulers.entity(plugin, player, () -> {
            List<String> results = new ArrayList<>();
            int index = 0;
            for (StonecuttingRecipe recipe : view.getRecipes()) {
                results.add(index++ + " " + describe(recipe.getResult()) + " <- "
                        + recipe.getInputChoice().getClass().getSimpleName());
            }
            plugin.getLogger().info("cmbcut " + id + ": " + results.size() + " results");
            for (String result : results) {
                plugin.getLogger().info("cmbcut | " + result);
            }
            player.sendMessage("cmbcut " + id + ": " + results);
        }, () -> {});
    }

    private static ItemStack stack(String id) {
        BukkitItemDefinition custom = CraftEngineItems.byId(id);
        if (custom != null) {
            return custom.buildBukkitItem();
        }
        Material material = Material.matchMaterial(id);
        return material == null || !material.isItem() ? null : new ItemStack(material);
    }

    private static String describe(ItemStack stack) {
        String name = CraftEngineItems.isCustomItem(stack)
                ? CraftEngineItems.getCustomItemId(stack).asString()
                : stack.getType().getKey().toString();
        return name + " x" + stack.getAmount();
    }
}
