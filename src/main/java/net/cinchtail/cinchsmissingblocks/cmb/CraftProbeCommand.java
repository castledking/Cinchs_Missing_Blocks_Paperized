package net.cinchtail.cinchsmissingblocks.cmb;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.item.BukkitItemDefinition;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemCraftResult;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;

/**
 * Debug: what does the server's crafting actually do with a given grid?
 *
 * <p>{@code /cmbcraft <item> [column|row]} puts three of a CraftEngine item in the middle
 * column (or middle row) of a 3x3 grid and reports two things separately:
 * <ul>
 *   <li>the recipe vanilla's matcher selects ({@code Bukkit.getCraftingRecipe}), because
 *   CraftEngine builds its result for whichever recipe vanilla matched;</li>
 *   <li>the final result after {@code PrepareItemCraftEvent}, which is what a crafting
 *   table shows ({@code Bukkit.craftItemResult} with the player fires that event, so
 *   CraftEngine's crafting listener runs exactly as in a real table).</li>
 * </ul>
 * Written to split the vertical-slab recipe defect: matched-but-emptied and
 * never-matched need different fixes.
 */
public final class CraftProbeCommand {

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("cmbcraft")
                .requires(source -> source.getSender().hasPermission("cmb.debug"))
                .then(Commands.argument("item", StringArgumentType.word())
                        .executes(c -> run(c.getSource().getSender(),
                                StringArgumentType.getString(c, "item"), "column"))
                        .then(Commands.argument("layout", StringArgumentType.word())
                                .executes(c -> run(c.getSource().getSender(),
                                        StringArgumentType.getString(c, "item"),
                                        StringArgumentType.getString(c, "layout")))))
                .build();
    }

    private int run(CommandSender sender, String item, String layout) {
        try {
            String id = item.contains(":") ? item : "cinchsmissingblocks:" + item;
            BukkitItemDefinition definition = CraftEngineItems.byId(id);
            if (definition == null) {
                sender.sendMessage("cmbcraft: " + id + " is not a CraftEngine item");
                return 0;
            }
            ItemStack[] matrix = new ItemStack[9];
            for (int i = 0; i < 9; i++) {
                matrix[i] = new ItemStack(Material.AIR);
            }
            int[] slots = "row".equals(layout) ? new int[] {3, 4, 5} : new int[] {1, 4, 7};
            for (int slot : slots) {
                matrix[slot] = definition.buildBukkitItem();
            }
            World world = sender instanceof Player p ? p.getWorld() : Bukkit.getWorlds().getFirst();

            Recipe matched = Bukkit.getCraftingRecipe(matrix.clone(), world);
            sender.sendMessage("cmbcraft " + id + " x3 (" + layout + ")");
            sender.sendMessage("  vanilla match: " + (matched == null ? "NONE"
                    : (matched instanceof Keyed k ? k.getKey().toString() : matched.getClass().getSimpleName())
                    + " -> " + describe(matched.getResult())));

            ItemCraftResult result = sender instanceof Player player
                    ? Bukkit.craftItemResult(matrix.clone(), world, player)
                    : Bukkit.craftItemResult(matrix.clone(), world);
            sender.sendMessage("  after PrepareItemCraftEvent"
                    + (sender instanceof Player ? "" : " (console: event not fired)")
                    + ": " + describe(result.getResult()));
            return 1;
        } catch (Throwable t) {
            sender.sendMessage("cmbcraft: ERROR " + t);
            return 0;
        }
    }

    private static String describe(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR) {
            return "EMPTY";
        }
        String name = CraftEngineItems.isCustomItem(stack)
                ? CraftEngineItems.getCustomItemId(stack).asString()
                : stack.getType().getKey().toString();
        return stack.getAmount() + " x " + name;
    }
}
