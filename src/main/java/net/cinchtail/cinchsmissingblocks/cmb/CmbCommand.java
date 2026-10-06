package net.cinchtail.cinchsmissingblocks.cmb;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /cmb reload all, /cmb setupmsg, and /cmb item - CraftEngine's item browser and give,
 * under CMB's name.
 *
 * <p>/cmb item is a pass-through to {@code /ce item browser|give}: the arguments and
 * their meaning are CraftEngine's, and so are the tab completions, asked of the
 * server's command map for the matching {@code ce item ...} line (no reflection into
 * CraftEngine). They are trimmed to the two subcommands and, at the item argument, to
 * CMB's own items, without the display-only ones a furniture piece draws with.
 *
 * <p>The CraftEngine command runs as the sender when they have CraftEngine's own
 * permission for it; otherwise, with cmb.item, from the console on their behalf, so
 * CMB's admins don't also need CraftEngine permissions.
 */
public class CmbCommand {

    private static final String NAMESPACE = "cinchsmissingblocks:";
    private static final List<String> SUBCOMMANDS = List.of("browser", "give");

    private final CmbPlugin plugin;

    public CmbCommand(CmbPlugin plugin) {
        this.plugin = plugin;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("cmb")
                .then(Commands.literal("reload")
                        .requires(s -> s.getSender().hasPermission("cmb.admin"))
                        .then(Commands.literal("all")
                                .executes(ctx -> {
                                    CommandSender sender = ctx.getSource().getSender();
                                    sender.sendMessage(plugin.lang().get(
                                            plugin.reload() ? "reload-success" : "reload-failed"));
                                    return Command.SINGLE_SUCCESS;
                                })))
                .then(Commands.literal("setupmsg")
                        .requires(s -> s.getSender().hasPermission("cmb.admin"))
                        .executes(ctx -> {
                            plugin.setupNotice().send(ctx.getSource().getSender());
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("item")
                        .requires(s -> s.getSender().hasPermission("cmb.item"))
                        .executes(ctx -> {
                            ctx.getSource().getSender().sendMessage(plugin.lang().get("item-usage"));
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.argument("args", StringArgumentType.greedyString())
                                .suggests((ctx, builder) -> suggest(builder))
                                .executes(ctx -> item(ctx.getSource().getSender(),
                                        StringArgumentType.getString(ctx, "args")))))
                .build();
    }

    private int item(CommandSender sender, String args) {
        String[] words = args.trim().split("\\s+");
        String sub = words[0].toLowerCase(Locale.ROOT);
        if (!SUBCOMMANDS.contains(sub)) {
            sender.sendMessage(plugin.lang().get("item-usage"));
            return 0;
        }
        String line = "ce item " + args.trim();
        String cePermission = sub.equals("give")
                ? "ce.command.admin.give_item"
                : words.length > 1 ? "ce.command.admin.item_browser" : "ce.command.player.item_browser";
        if (sender.hasPermission(cePermission)) {
            Bukkit.dispatchCommand(sender, line);
            return Command.SINGLE_SUCCESS;
        }
        // On the sender's behalf: the console has CraftEngine's permissions, but a
        // browser has to open for someone, so a bare `browser` names the sender.
        if (sub.equals("browser") && words.length == 1) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(plugin.lang().get("item-usage"));
                return 0;
            }
            line += " " + player.getName();
        }
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line);
        return Command.SINGLE_SUCCESS;
    }

    /** CraftEngine's completions for the same `ce item ...` line, trimmed to CMB. */
    private CompletableFuture<Suggestions> suggest(SuggestionsBuilder builder) {
        String typed = builder.getRemaining();
        int lastSpace = typed.lastIndexOf(' ');
        SuggestionsBuilder last = builder.createOffset(builder.getStart() + lastSpace + 1);
        String prefix = typed.substring(lastSpace + 1).toLowerCase(Locale.ROOT);
        String[] words = typed.split(" ", -1);
        if (words.length == 1) {
            SUBCOMMANDS.stream().filter(s -> s.startsWith(prefix)).forEach(last::suggest);
            return last.buildFuture();
        }
        if (!SUBCOMMANDS.contains(words[0].toLowerCase(Locale.ROOT))) {
            return last.buildFuture();
        }
        boolean itemArgument = words[0].equalsIgnoreCase("give") && words.length == 3;
        List<String> options = Bukkit.getCommandMap().tabComplete(Bukkit.getConsoleSender(), "ce item " + typed);
        if (options != null) {
            for (String option : options) {
                if (itemArgument && (!option.startsWith(NAMESPACE) || option.contains("__"))) {
                    continue;
                }
                last.suggest(option);
            }
        }
        if (itemArgument && options != null && options.isEmpty() && !prefix.contains(":")) {
            // CraftEngine completes ids from the namespace on; offer it.
            last.suggest(NAMESPACE);
        }
        return last.buildFuture();
    }
}
