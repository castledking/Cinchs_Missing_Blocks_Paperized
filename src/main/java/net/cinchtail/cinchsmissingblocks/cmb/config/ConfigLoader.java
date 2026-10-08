package net.cinchtail.cinchsmissingblocks.cmb.config;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.stream.Collectors;
import net.cinchtail.cinchsmissingblocks.cmb.PieceCategory;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

/**
 * Reads {@code plugins/CMB/config.yml}.
 *
 * <p>Every value has a default, and a missing or unreadable file yields {@link #defaults()}
 * rather than an exception. A malformed config must not be able to stop the plugin
 * enabling: the behaviours it registers are what make served stairs work at all, so
 * failing here would take working blocks offline over a typo in a settings file.
 *
 * <p>Unknown keys are reported rather than ignored. The failure this guards against is a
 * config that reads as if it took effect and did not - a renamed toggle silently reverting
 * to its default is indistinguishable from "the feature is broken".
 */
public final class ConfigLoader {

    private final Plugin plugin;
    private final File configFile;
    private FileConfiguration raw;
    private CmbConfig parsed;

    public ConfigLoader(Plugin plugin) {
        this.plugin = plugin;
        this.configFile = new File(plugin.getDataFolder(), "config.yml");
        writeDefaultIfAbsent();
        load();
    }

    /**
     * Writes the bundled default config on first run only.
     *
     * <p>Never overwrites: an admin's edits are the point of the file, and a plugin that
     * rewrote it on every boot would make configuration unpersistable.
     */
    private void writeDefaultIfAbsent() {
        if (configFile.isFile()) {
            return;
        }
        try (InputStream bundled = plugin.getResource("config.yml")) {
            if (bundled == null) {
                plugin.getLogger().warning("Bundled config.yml is missing from the jar.");
                return;
            }
            Files.createDirectories(configFile.toPath().getParent());
            Files.copy(bundled, configFile.toPath());
            plugin.getLogger().info("Wrote default " + configFile.getName());
        } catch (IOException e) {
            plugin.getLogger()
                    .warning("Could not write default " + configFile.getName() + ": " + e.getMessage());
        }
    }

    /** Re-reads the file. Called on enable and by {@code /cmb reload all}. */
    public void load() {
        raw = YamlConfiguration.loadConfiguration(configFile);
        parsed = parse(raw);
    }

    /** @return the most recently parsed config, never null */
    public CmbConfig config() {
        return parsed != null ? parsed : defaults();
    }

    private CmbConfig parse(FileConfiguration c) {
        Set<String> seenKeys = new LinkedHashSet<>();
        for (String key : c.getKeys(true)) {
            if (!key.contains(".")) {
                seenKeys.add(key);
            }
        }
        reportUnknownKeys(seenKeys);

        return new CmbConfig(
                new CmbConfig.Features(
                        new CmbConfig.VerticalSlabs(
                                c.getBoolean("features.vertical-slabs.cmb", true),
                                c.getBoolean("features.vertical-slabs.vanilla", true),
                                lower(c.getStringList("features.vertical-slabs.disabled"))),
                        new CmbConfig.VerticalSlabs(
                                c.getBoolean("features.horizontal-stairs.cmb", true),
                                c.getBoolean("features.horizontal-stairs.vanilla", true),
                                lower(c.getStringList("features.horizontal-stairs.disabled"))),
                        "furniture".equalsIgnoreCase(c.getString("features.vertical-slabs.doubles", "block"))
                                ? "furniture" : "block",
                        c.getBoolean("features.furniture-fallback", true),
                        c.getBoolean("features.disable-terracotta", true),
                        c.getBoolean("features.disable-concrete", true),
                        c.getBoolean("features.protect-furniture-cells", true)),
                new CmbConfig.ResourcePack(
                        CmbConfig.ResourcePack.Delivery.parse(
                                c.getString("resource-pack.delivery", "craftengine")),
                        c.getBoolean("resource-pack.install", true)),
                new CmbConfig.Content(new CmbConfig.Wart(
                        c.getBoolean("content.warped-nether-wart.fortress", true),
                        c.getDouble("content.warped-nether-wart.fortress-chance", 0.25),
                        c.getBoolean("content.warped-nether-wart.biome-edges", true),
                        c.getDouble("content.warped-nether-wart.biome-edge-chance", 0.35),
                        c.getBoolean("content.warped-nether-wart.nylium-planting", true))),
                new CmbConfig.Compatibility(
                        CmbConfig.Compatibility.Policy.parse(
                                c.getString("compatibility.unsupported-content", "disable"))),
                new CmbConfig.CraftEngineSettings(
                        c.getInt("craftengine.max-internal-states", 5120),
                        c.getInt("craftengine.reserved-states", 64)),
                new CmbConfig.DisabledBlocks(lower(c.getStringList("disabled-blocks"))),
                tools(c),
                c.getBoolean("update-checker", true));
    }

    /** The tools section; a colour that doesn't parse keeps its default, with a warning. */
    private CmbConfig.Tools tools(FileConfiguration c) {
        int defaultRadius = Math.max(1, c.getInt("tools.default-radius", 8));
        int maxRadius = Math.max(defaultRadius, c.getInt("tools.max-radius", 32));
        Map<String, Integer> colors = new LinkedHashMap<>();
        for (PieceCategory category
                : PieceCategory.values()) {
            String raw = c.getString("tools.glow-colors." + category.key());
            Integer rgb = raw == null ? null : parseColor(raw);
            if (raw != null && rgb == null) {
                plugin.getLogger().warning("tools.glow-colors." + category.key() + ": '" + raw
                        + "' is not a #RRGGBB colour; using the default");
            }
            colors.put(category.key(), rgb == null ? category.defaultColor : rgb);
        }
        return new CmbConfig.Tools(defaultRadius, maxRadius, Map.copyOf(colors),
                outlineBlock(c.getString("tools.block-outline")));
    }

    /**
     * tools.block-outline: NONE for the glow alone, or block data - a block
     * (WHITE_STAINED_GLASS), or a block with states
     * (white_stained_glass_pane[north=true,south=true]). Anything else keeps the default,
     * with a warning.
     */
    private String outlineBlock(String raw) {
        if (raw == null || raw.isBlank()) {
            return CmbConfig.Tools.DEFAULT_OUTLINE_BLOCK;
        }
        String id = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (id.equals(CmbConfig.Tools.OUTLINE_ONLY)) {
            return CmbConfig.Tools.OUTLINE_ONLY;
        }
        try {
            org.bukkit.block.data.BlockData data = org.bukkit.Bukkit.createBlockData(id);
            if (data.getMaterial().isBlock() && !data.getMaterial().isAir()) {
                return data.getAsString();
            }
        } catch (IllegalArgumentException e) {
            // falls through to the warning
        }
        plugin.getLogger().warning("tools.block-outline: '" + raw + "' is neither NONE nor a block; using "
                + CmbConfig.Tools.DEFAULT_OUTLINE_BLOCK);
        return CmbConfig.Tools.DEFAULT_OUTLINE_BLOCK;
    }

    static Integer parseColor(String raw) {
        String hex = raw.trim().replaceFirst("^#", "");
        if (!hex.matches("[0-9a-fA-F]{6}")) {
            return null;
        }
        return Integer.parseInt(hex, 16);
    }

    private static CmbConfig.Tools defaultTools() {
        Map<String, Integer> colors = new LinkedHashMap<>();
        for (PieceCategory category
                : PieceCategory.values()) {
            colors.put(category.key(), category.defaultColor);
        }
        return new CmbConfig.Tools(8, 32, Map.copyOf(colors), CmbConfig.Tools.DEFAULT_OUTLINE_BLOCK);
    }

    /** Top-level sections this build understands. Anything else is a typo or a stale key. */
    private static final Set<String> KNOWN_SECTIONS =
            Set.of("craftengine", "compatibility", "content", "features",
                   "resource-pack", "disabled-blocks", "tools", "update-checker");

    private void reportUnknownKeys(Set<String> seenKeys) {
        List<String> unknown = seenKeys.stream().filter(k -> !KNOWN_SECTIONS.contains(k)).toList();
        if (!unknown.isEmpty()) {
            plugin
                    .getLogger()
                    .warning(
                            "Unrecognised top-level config key(s): "
                                    + String.join(", ", unknown)
                                    + ". They are ignored; if you expected one to take effect,"
                                    + " check the spelling against the commented default config.");
        }
    }

    /**
     * Normalises id lists for case-insensitive comparison.
     *
     * <p>Kept as an ordered set so the first carrier still goes to the first configured
     * entry: carrier assignment follows config order, so a set that reordered ids would
     * quietly reshuffle which block gets which carrier.
     */
    private static Set<String> lower(List<String> values) {
        return values.stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** The shipped defaults, used when the file cannot be parsed. */
    public static CmbConfig defaults() {
        return new CmbConfig(
                new CmbConfig.Features(new CmbConfig.VerticalSlabs(true, true, Set.of()),
                        new CmbConfig.VerticalSlabs(true, true, Set.of()), "block", true, true, true, true),
                new CmbConfig.ResourcePack(CmbConfig.ResourcePack.Delivery.CRAFTENGINE, true),
                new CmbConfig.Content(new CmbConfig.Wart(true, 0.25, true, 0.35, true)),
                new CmbConfig.Compatibility(CmbConfig.Compatibility.Policy.DISABLE),
                new CmbConfig.CraftEngineSettings(5120, 64),
                new CmbConfig.DisabledBlocks(Set.of()),
                defaultTools(),
                true);
    }

    /** Logs the settings that change behaviour, so a reload states what it picked up. */
    public void logSummary(CmbConfig cfg) {
        CmbConfig.VerticalSlabs vs = cfg.features().verticalSlabs();
        plugin
                .getLogger()
                .info(
                        "vertical slabs: "
                                + (vs.enabled()
                                        ? "cmb=" + vs.cmb() + " vanilla=" + vs.vanilla()
                                        : "off")
                                + (vs.disabled().isEmpty()
                                        ? ""
                                        : " disabled=" + vs.disabled().size()));
        plugin
                .getLogger()
                .info(
                        "state budget: "
                                + cfg.craftEngine().usable()
                                + " usable of "
                                + cfg.craftEngine().maxInternalStates()
                                + " ("
                                + cfg.craftEngine().reservedStates()
                                + " reserved)");
        plugin
                .getLogger()
                .info("resource pack delivery: " + cfg.resourcePack().delivery().name().toLowerCase(Locale.ROOT));
        if (!cfg.disabledBlocks().blocks().isEmpty()) {
            plugin
                    .getLogger()
                    .info("disabled blocks: " + cfg.disabledBlocks().blocks().size());
        }
    }

    /** Test seam: parse an in-memory config. */
    CmbConfig parseForTest(FileConfiguration c) {
        return parse(c);
    }

    static {
        // Guard against the enum parser silently accepting a typo'd policy.
        assert CmbConfig.ResourcePack.Delivery.parse("nonsense")
                == CmbConfig.ResourcePack.Delivery.CRAFTENGINE;
    }
}