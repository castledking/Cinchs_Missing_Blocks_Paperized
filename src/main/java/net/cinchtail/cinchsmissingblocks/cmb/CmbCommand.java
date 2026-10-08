package net.cinchtail.cinchsmissingblocks.cmb;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.cinchtail.cinchsmissingblocks.cmb.config.CmbConfig;
import net.cinchtail.cinchsmissingblocks.cmb.scheduler.Schedulers;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /cmb, which opens the item browser, /cmb reload all, /cmb reload tools, /cmb setupmsg,
 * /cmb item - CraftEngine's item browser and give, under CMB's name - and /cmb kill and
 * /cmb glow, which find CMB pieces around the player by category (PieceTools).
 *
 * <p>A bare {@code /cmb} is what an admin reaches for after {@code /cmb setupmsg}: setup
 * says what is installed, the browser shows them. It goes through {@link #item} rather
 * than dispatching to CraftEngine itself, so it shares that command's permission
 * handling and console fallback instead of reimplementing either.
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
                                    plugin.reload(ctx.getSource().getSender());
                                    return Command.SINGLE_SUCCESS;
                                })))
                .then(Commands.literal("setupmsg")
                        .requires(s -> s.getSender().hasPermission("cmb.admin"))
                        .executes(ctx -> {
                            plugin.setupNotice().send(ctx.getSource().getSender());
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("kill")
                        .requires(s -> s.getSender().hasPermission("cmb.kill"))
                        .executes(ctx -> {
                            ctx.getSource().getSender().sendMessage(plugin.lang().get("kill-usage"));
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.argument("args", StringArgumentType.greedyString())
                                .suggests((ctx, builder) -> suggestTools(builder))
                                .executes(ctx -> tools(ctx.getSource(), StringArgumentType.getString(ctx, "args"), true))))
                .then(Commands.literal("glow")
                        .requires(s -> s.getSender().hasPermission("cmb.glow"))
                        .executes(ctx -> toggleGlow(ctx.getSource()))
                        .then(Commands.argument("args", StringArgumentType.greedyString())
                                .suggests((ctx, builder) -> suggestTools(builder))
                                .executes(ctx -> tools(ctx.getSource(), StringArgumentType.getString(ctx, "args"), false))))
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

    // --- /cmb kill and /cmb glow ------------------------------------------------------

    /** {@code #category [radius]}, then kill or glow around the player. */
    private int tools(CommandSourceStack source, String args, boolean kill) {
        if (!(source.getExecutor() instanceof Player player)) {
            source.getSender().sendMessage(plugin.lang().get("players-only"));
            return 0;
        }
        String[] words = args.trim().split("\\s+");
        Set<PieceCategory> categories = categories(words[0]);
        if (categories == null) {
            player.sendMessage(plugin.lang().get("unknown-category", Map.of("tag", words[0],
                    "categories", "#all, " + String.join(", ", Arrays.stream(PieceCategory.values())
                            .map(c -> "#" + c.key()).toList()))));
            return 0;
        }
        CmbConfig.Tools settings = plugin.cmbConfig().tools();
        int radius = settings.defaultRadius();
        if (words.length > 1) {
            try {
                radius = Integer.parseInt(words[1]);
            } catch (NumberFormatException e) {
                player.sendMessage(plugin.lang().get(kill ? "kill-usage" : "glow-usage"));
                return 0;
            }
        }
        if (radius < 1 || radius > settings.maxRadius()) {
            player.sendMessage(plugin.lang().get("radius-out-of-range",
                    Map.of("max", Integer.toString(settings.maxRadius()))));
            return 0;
        }
        run(player, categories, radius, kill);
        return Command.SINGLE_SUCCESS;
    }

    /** No arguments: outlines off if they are on, else every category at the default radius. */
    private int toggleGlow(CommandSourceStack source) {
        if (!(source.getExecutor() instanceof Player player)) {
            source.getSender().sendMessage(plugin.lang().get("players-only"));
            return 0;
        }
        if (plugin.pieceTools().isGlowing(player)) {
            plugin.pieceTools().clearGlow(player);
            player.sendMessage(plugin.lang().get("glow-off"));
            return Command.SINGLE_SUCCESS;
        }
        run(player, EnumSet.allOf(PieceCategory.class), plugin.cmbConfig().tools().defaultRadius(), false);
        return Command.SINGLE_SUCCESS;
    }

    private void run(Player player, Set<PieceCategory> categories, int radius, boolean kill) {
        Location center = player.getLocation();
        // Where the pieces are, which is the player's region on Folia.
        Schedulers.atLocation(plugin, center, () -> {
            List<PieceTools.Found> found = PieceTools.find(center, radius, categories);
            Map<String, String> placeholders = Map.of(
                    "count", Integer.toString(found.size()),
                    "radius", Integer.toString(radius),
                    "breakdown", breakdown(PieceTools.counts(found)));
            if (kill) {
                PieceTools.kill(found);
                player.sendMessage(plugin.lang().get("kill-done", placeholders));
            } else {
                plugin.pieceTools().glow(player, found);
                player.sendMessage(plugin.lang().get("glow-done", placeholders));
            }
        });
    }

    /** The categories a tag names, or null if it names none. */
    private static Set<PieceCategory> categories(String tag) {
        if (PieceCategory.isAll(tag)) {
            return EnumSet.allOf(PieceCategory.class);
        }
        PieceCategory category = PieceCategory.parse(tag);
        return category == null ? null : EnumSet.of(category);
    }

    private static String breakdown(Map<PieceCategory, Integer> counts) {
        if (counts.isEmpty()) {
            return "none";
        }
        return String.join(", ", counts.entrySet().stream()
                .map(e -> e.getValue() + " " + e.getKey().key().replace('_', ' ')).toList());
    }

    private CompletableFuture<Suggestions> suggestTools(SuggestionsBuilder builder) {
        String typed = builder.getRemaining();
        if (typed.contains(" ")) {
            return builder.buildFuture();
        }
        String prefix = typed.toLowerCase(Locale.ROOT);
        List<String> tags = new java.util.ArrayList<>();
        tags.add("#all");
        for (PieceCategory c : PieceCategory.values()) {
            tags.add("#" + c.key());
        }
        tags.stream().filter(t -> t.startsWith(prefix) || t.substring(1).startsWith(prefix)).forEach(builder::suggest);
        return builder.buildFuture();
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
            dispatch(sender, line);
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
        dispatch(Bukkit.getConsoleSender(), line);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Runs a {@code ce ...} command from the global region thread.
     *
     * <p>A player reaches {@code /cmb item} from a region thread, and
     * {@code Bukkit.dispatchCommand} is global-tick-thread only: on Folia it throws
     * {@code Dispatching command async} rather than quietly doing nothing. On Paper the
     * global region scheduler is the main thread, so this is inline as before.
     */
    private void dispatch(CommandSender sender, String line) {
        Schedulers.global(plugin, () -> Bukkit.dispatchCommand(sender, line));
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
