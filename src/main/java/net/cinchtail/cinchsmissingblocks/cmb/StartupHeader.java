package net.cinchtail.cinchsmissingblocks.cmb;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import net.cinchtail.cinchsmissingblocks.cmb.config.CmbConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The startup banner: parse {@code startups.txt}, pick one at random, fill it in.
 *
 * <p>Format is the one GriefPrevention3D and GPExpansion share: an entry opens with
 * {@code {key}[}, closes with a lone {@code ]}, and carries {@code ${version}} and a
 * {@code ${startup}} placeholder. GPExpansion's variant is the closer fit here -- it ships
 * one lang.yml rather than per-locale message files, so there is no locale selection to do
 * and no pseudo-locale keys to fall back through.
 *
 * <p>The art itself is uncoloured on purpose: it is the one part an admin looks at before
 * they have decided to trust the colours, and the block characters differ enough in width
 * between fonts that colouring them by hand is worth doing by eye. Everything built
 * <em>around</em> the art -- the author and plugin tags, the details grid, the boot time --
 * is coloured here.
 *
 * <p>What the details grid is for is answering "what is actually running, and what did it
 * pick up" in one screen: the platform, the delivery mode, the state budget, and every
 * feature the config can turn on or off. Those are all decisions a server owner may have
 * made in a file an hour ago and has not seen take effect.
 */
public final class StartupHeader {

    private static final Pattern ENTRY_KEY = Pattern.compile("^(\\w+?)\\d+\\[$");
    private static final Pattern CLOSING_BRACKET = Pattern.compile("^\\]$");
    private static final Random RANDOM = new Random();

    /**
     * Two-column alignment, counting only the characters a player actually sees.
     *
     * <p>Wide enough for the longest real value here -- a Paper version string runs to
     * something like {@code Paper 26.3.build.141-beta}, which is 28 visible characters with
     * its label.
     */
    private static final int COL_WIDTH = 32;

    private final CmbPlugin plugin;

    public StartupHeader(CmbPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * A randomly chosen banner, fully substituted, or null if the resource is missing or
     * unparseable. Null is not fatal: a missing banner is a missing banner, not a plugin
     * that refuses to enable.
     */
    public String getRandomHeader() {
        InputStream stream = plugin.getResource("startups.txt");
        if (stream == null) {
            return null;
        }
        List<String> entries = parse(stream);
        if (entries.isEmpty()) {
            return null;
        }
        String header = entries.get(RANDOM.nextInt(entries.size()));
        header = header.replace("${project.version}", plugin.getPluginMeta().getVersion());
        header = header.replace("${version}", plugin.getPluginMeta().getVersion());
        header = header.replace("${startup}", buildStartupBlock());
        return "\n" + translate(header);
    }

    /** {@code startups.txt} as a flat list of entry bodies. */
    private List<String> parse(InputStream stream) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inside = false;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!inside) {
                    Matcher key = ENTRY_KEY.matcher(line.trim());
                    if (key.matches()) {
                        current.setLength(0);
                        inside = true;
                    }
                } else if (CLOSING_BRACKET.matcher(line.trim()).matches()) {
                    if (current.length() > 0) {
                        result.add(current.toString());
                    }
                    inside = false;
                } else {
                    current.append(line).append('\n');
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Could not parse startups.txt: " + e.getMessage());
        }
        return result;
    }

    /**
     * What sits under the art: who wrote it, what it is, the details grid, and how long
     * the boot took.
     */
    private String buildStartupBlock() {
        StringBuilder sb = new StringBuilder();
        sb.append(plugin.lang().raw("startup.tag-author")).append("\n&r\n");
        sb.append(plugin.lang().raw("startup.tag-plugin")).append("\n&r\n");
        sb.append(plugin.lang().raw("startup.details")).append("\n&r\n");
        sb.append(buildDetails()).append("\n&r\n");
        sb.append(plugin.lang().raw("startup.boot-finished", Map.of(
                "boot", "&a" + (System.currentTimeMillis() - plugin.startupStartTime()) + "ms")));
        return sb.toString();
    }

    /**
     * Platform and delivery on the first line, then the pack, then every feature switch.
     *
     * <p>Feature toggles are the reason this grid exists. Each of them is a decision an
     * admin made in a YAML file, and a server that has quietly lost its walls looks exactly
     * like a server that never had them -- so the state is printed, not merely honoured.
     */
    private String buildDetails() {
        String on = "&aON";
        String off = "&cOFF";
        String yes = "&aYES";
        String no = "&cNO";
        CmbConfig cfg = plugin.cmbConfig();

        String platform = "&a" + platformVersion();
        String version = "&a" + plugin.getPluginMeta().getVersion();

        CmbConfig.ResourcePack.Delivery delivery = plugin.delivery();
        String deliveryText = delivery == null ? "&7n/a" : "&a" + delivery.name().toLowerCase(Locale.ROOT);
        boolean rspmInstalled = plugin.getServer().getPluginManager().getPlugin("ResourcePackManager") != null;

        String budget = "&a" + cfg.craftEngine().usable() + "&7/&a"
                + cfg.craftEngine().maxInternalStates();

        CmbConfig.VerticalSlabs vs = cfg.features().verticalSlabs();
        // Both feature switches are the same record; there is no HorizontalStairs type.
        CmbConfig.VerticalSlabs hs = cfg.features().horizontalStairs();
        boolean furniture = cfg.features().furnitureFallback();
        int disabled = cfg.disabledBlocks().blocks().size();

        List<String> lines = new ArrayList<>();
        lines.add(row("Platform:", platform, "Delivery:", deliveryText));
        lines.add(row("Version:", version, "RSPM:", rspmInstalled ? yes : no));
        lines.add(row("State budget:", budget, "Vert. slabs:", vs.enabled() ? yes : no));
        lines.add(row("Horiz. stairs:", hs.enabled() ? yes : no, "Furniture:", furniture ? yes : no));
        lines.add(row("Install:", cfg.resourcePack().install() ? yes : no,
                "Disabled:", disabled == 0 ? "&7none" : "&e" + disabled));
        return String.join("\n", lines);
    }

    private String platformVersion() {
        var server = plugin.getServer();
        return server.getName() + " " + server.getVersion();
    }

    /**
     * One two-column row, both labels grey and both values coloured by their caller.
     *
     * <p>The left value is truncated to fit rather than allowed to overflow, because
     * {@link #pad} can only add spaces -- a long value pushed the second column out of
     * alignment instead of keeping the grid square. Server version strings are exactly the
     * kind of value nobody controls and everybody notices, so the version is what gives way.
     */
    private static String row(String leftLabel, String leftValue, String rightLabel, String rightValue) {
        String left = fit(leftLabel, leftValue);
        StringBuilder sb = new StringBuilder("&7").append(pad(left, COL_WIDTH));
        sb.append(" &7").append(rightLabel).append(" ").append(rightValue);
        return sb.toString();
    }

    /**
     * Label plus value, with the value shortened by ellipsis if it would overrun.
     *
     * <p>Walks the raw string and counts only visible characters, carrying colour codes
     * through uncounted. Truncating by stripping the codes first and then searching for the
     * remaining prefix in the original looks equivalent and is not: the first code would
     * never match a stripped prefix, so the search bottomed out and returned nothing but
     * the ellipsis -- turning a 25-character version string into a bare dot.
     */
    private static String fit(String label, String value) {
        int room = COL_WIDTH - visibleLength(label + " ") - 1;
        if (room <= 1 || visibleLength(value) <= room) {
            return label + " " + value;
        }
        StringBuilder kept = new StringBuilder();
        int shown = 0;
        for (int i = 0; i < value.length(); ) {
            int code = colourCodeLength(value, i);
            if (code > 0) {
                kept.append(value, i, i + code);
                i += code;
                continue;
            }
            if (shown == room - 1) {
                break;
            }
            kept.append(value.charAt(i));
            shown++;
            i++;
        }
        return label + " " + kept + "&7…";
    }

    /** Length of the {@code &}/{@code §} code at {@code i}, or 0 if there is none. */
    private static int colourCodeLength(String text, int i) {
        if (i + 1 >= text.length()) {
            return 0;
        }
        char marker = text.charAt(i);
        if (marker != '&' && marker != '\u00a7') {
            return 0;
        }
        char code = Character.toLowerCase(text.charAt(i + 1));
        if ("0123456789abcdefklmnorx".indexOf(code) >= 0) {
            return 2;
        }
        if (code == '#' && i + 7 < text.length()) {
            return 8;
        }
        return 0;
    }

    private static int visibleLength(String text) {
        return strip(text).length();
    }

    private static String strip(String text) {
        return text.replaceAll("[&§][0-9a-fk-orA-FK-ORX]", "")
                .replaceAll("&#[0-9a-fA-F]{6}", "")
                .replaceAll("#[0-9a-fA-F]{6}", "");
    }

    /**
     * Pad to {@code width} as displayed, ignoring colour codes.
     *
     * <p>Padding the raw string instead would misalign every column by however many codes
     * the value happened to carry -- which is exactly the part that changes when a value
     * goes from {@code &a342} to {@code &7n/a}.
     */
    private static String pad(String text, int width) {
        int padding = width - visibleLength(text);
        if (padding <= 0) {
            return text;
        }
        return text + " ".repeat(padding);
    }

    private static String translate(String text) {
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', text);
    }
}
