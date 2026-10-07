package net.cinchtail.cinchsmissingblocks.cmb.pack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.cinchtail.cinchsmissingblocks.cmb.config.CmbConfig;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.plugin.java.JavaPlugin;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Installs the CraftEngine pack bundled in the jar, as this server's config.yml wants it.
 *
 * <p>The jar carries one pack, generated at build time from the pinned upstream mod
 * with everything on (the release build config). {@code pack/intermediate/
 * pieces.json} says, for every item, block and furniture id, which config switches it
 * depends on; this removes the ids whose switches are off, every recipe that names
 * one, and their category entries, and writes the result to
 * {@code plugins/CraftEngine/resources/cinchsmissingblocks/}. Files it doesn't touch
 * are copied byte for byte; only files that changed are written, and files the bundle
 * no longer has are deleted, so the folder is exactly the bundle as filtered.
 *
 * <p>Runs in onLoad, before CraftEngine reads its packs, and on {@code /cmb reload all}
 * before CraftEngine's reload. {@code resource-pack.install: false} leaves the folder
 * alone, for a pack generated and copied in by hand.
 */
public final class PackInstaller {

    private static final String BUNDLE = "pack/";
    private static final List<String> SECTIONS = List.of("items", "blocks", "furniture");

    private final JavaPlugin plugin;

    public PackInstaller(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public record Result(int written, int unchanged, int deleted, int removedIds) {}

    public Path target() {
        return Paths.get(plugin.getDataFolder().getParent(), "CraftEngine", "resources",
                "cinchsmissingblocks");
    }

    public Result install(CmbConfig cfg) throws IOException {
        List<String> index = new String(resource("index.txt"), StandardCharsets.UTF_8).lines()
                .filter(line -> !line.isBlank()).toList();
        Map<String, Set<String>> removed = removedIds(cfg);
        Set<String> removedAll = new HashSet<>();
        removed.values().forEach(removedAll::addAll);

        Path target = target();
        Files.createDirectories(target);
        int written = 0;
        int unchanged = 0;
        Set<Path> wanted = new HashSet<>();
        for (String rel : index) {
            byte[] data = resource(rel);
            if (rel.startsWith("configuration/") && rel.endsWith(".yml")) {
                data = filter(data, removed, removedAll);
            }
            Path file = target.resolve(rel);
            wanted.add(file);
            if (Files.isRegularFile(file) && Arrays.equals(Files.readAllBytes(file), data)) {
                unchanged++;
                continue;
            }
            Files.createDirectories(file.getParent());
            Files.write(file, data);
            written++;
        }
        int deleted = 0;
        try (Stream<Path> walk = Files.walk(target)) {
            for (Path file : walk.sorted(Comparator.reverseOrder()).toList()) {
                if (Files.isRegularFile(file) && !wanted.contains(file)) {
                    Files.delete(file);
                    deleted++;
                } else if (Files.isDirectory(file) && !file.equals(target)) {
                    try (Stream<Path> entries = Files.list(file)) {
                        if (entries.findAny().isEmpty()) {
                            Files.delete(file);
                        }
                    }
                }
            }
        }
        return new Result(written, unchanged, deleted, removedAll.size());
    }

    private byte[] resource(String rel) throws IOException {
        try (InputStream in = plugin.getResource(BUNDLE + rel)) {
            if (in == null) {
                throw new IOException("the jar has no bundled " + BUNDLE + rel);
            }
            return in.readAllBytes();
        }
    }

    // --- what to remove ---------------------------------------------------------

    /** Per section, the ids whose switches this config turns off. */
    private Map<String, Set<String>> removedIds(CmbConfig cfg) throws IOException {
        JsonObject manifest = JsonParser.parseString(
                new String(resource("intermediate/pieces.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        Server server = new Server(Bukkit.getPluginManager().getPlugin("ViaBackwards") != null,
                name -> Material.matchMaterial(name) != null);
        Map<String, String> materialBlocks = materialBlocks(resource("configuration/recipes.yml"));
        Map<String, Set<String>> removed = new HashMap<>();
        for (String section : SECTIONS) {
            Set<String> ids = new HashSet<>();
            JsonObject entries = manifest.getAsJsonObject(section);
            if (entries != null) {
                for (Map.Entry<String, JsonElement> entry : entries.entrySet()) {
                    List<String> flags = new ArrayList<>();
                    entry.getValue().getAsJsonArray().forEach(flag -> flags.add(flag.getAsString()));
                    String block = flags.stream().filter(f -> f.startsWith("block:"))
                            .map(f -> f.substring("block:".length())).findFirst().orElse(null);
                    if (vanillaMaterialMissing(flags, materialBlocks, server)) {
                        ids.add(entry.getKey());
                        continue;
                    }
                    for (String flag : flags) {
                        if (!on(flag, cfg, server, block)) {
                            ids.add(entry.getKey());
                            break;
                        }
                    }
                }
            }
            removed.put(section, ids);
        }
        return removed;
    }

    /**
     * Whether an id is a vertical slab or horizontal stair made from a vanilla material
     * this server's vanilla doesn't have: cinnabar, sulfur and poplar before 26.x, pale oak
     * and resin bricks before 1.21.4. Such a piece would draw with the missing texture and
     * its recipe wouldn't load, so it goes.
     *
     * <p>The material's block is read from the pack's own recipe for it (materialBlocks),
     * not from a version table, so a new Minecraft version needs no change here. A
     * material with no recipe to read, or a piece made from a CMB block, is kept.
     */
    static boolean vanillaMaterialMissing(List<String> flags, Map<String, String> materialBlocks, Server server) {
        String material = null;
        boolean vanilla = false;
        for (String flag : flags) {
            if (flag.equals("vertical-slabs.vanilla") || flag.equals("horizontal-stairs.vanilla")) {
                vanilla = true;
            } else if (flag.startsWith("vertical-slabs.material:") || flag.startsWith("horizontal-stairs.material:")) {
                material = flag.substring(flag.indexOf(':') + 1);
            }
        }
        if (!vanilla || material == null) {
            return false;
        }
        String block = materialBlocks.get(material);
        return block != null && !server.vanillaHas().test(block);
    }

    /**
     * Material -> the vanilla block it is cut from (no namespace), read off the vertical
     * slab and horizontal stair recipes: a vanilla material's pieces are crafted from its
     * full block, the same block whose texture they draw.
     */
    @SuppressWarnings("unchecked")
    static Map<String, String> materialBlocks(byte[] recipesYaml) {
        Map<String, String> blocks = new HashMap<>();
        LoaderOptions loader = new LoaderOptions();
        loader.setCodePointLimit(Integer.MAX_VALUE);
        Object loaded = new Yaml(loader).load(new String(recipesYaml, StandardCharsets.UTF_8));
        if (!(loaded instanceof Map<?, ?> root) || !(root.get("recipes") instanceof Map<?, ?> recipes)) {
            return blocks;
        }
        String prefix = "cinchsmissingblocks:";
        for (Map.Entry<?, ?> entry : recipes.entrySet()) {
            String key = String.valueOf(entry.getKey());
            String material = key.endsWith("_vertical") ? key.substring(prefix.length(), key.length() - "_vertical".length())
                    : key.endsWith("_horizontal_stairs")
                            ? key.substring(prefix.length(), key.length() - "_horizontal_stairs".length())
                            : null;
            if (material == null || !key.startsWith(prefix)
                    || !(entry.getValue() instanceof Map<?, ?> recipe)
                    || !(recipe.get("ingredients") instanceof Map<?, ?> ingredients)) {
                continue;
            }
            for (Object ingredient : ingredients.values()) {
                if (ingredient instanceof String id && id.startsWith("minecraft:")) {
                    blocks.putIfAbsent(material, id.substring("minecraft:".length()));
                }
            }
        }
        return blocks;
    }

    /**
     * What the running server has, as far as the switches ask: ViaBackwards, and whether
     * vanilla has a block or item by a given name (no namespace).
     */
    record Server(boolean viaBackwards, Predicate<String> vanillaHas) {}

    /**
     * One switch from pieces.json; "a|b" is on when either is. {@code block} is the CMB
     * block the id belongs to (its {@code block:} switch), or null.
     */
    static boolean on(String flag, CmbConfig cfg, Server server, String block) {
        if (flag.isEmpty()) {
            return true;
        }
        if (flag.contains("|")) {
            for (String part : flag.split("\\|")) {
                if (on(part, cfg, server, block)) {
                    return true;
                }
            }
            return false;
        }
        CmbConfig.Features features = cfg.features();
        int colon = flag.indexOf(':');
        String key = colon < 0 ? flag : flag.substring(0, colon);
        String arg = colon < 0 ? "" : flag.substring(colon + 1);
        return switch (key) {
            case "vertical-slabs.cmb" -> features.verticalSlabs().cmb();
            case "vertical-slabs.vanilla" -> features.verticalSlabs().vanilla();
            case "vertical-slabs.material" -> !features.verticalSlabs().isDisabled(arg);
            case "horizontal-stairs.cmb" -> features.horizontalStairs().cmb();
            case "horizontal-stairs.vanilla" -> features.horizontalStairs().vanilla();
            case "horizontal-stairs.material" -> !features.horizontalStairs().isDisabled(arg);
            case "furniture-fallback" -> features.furnitureFallback();
            case "block" -> !cfg.disabledBlocks().blocks().contains(arg);
            case "terracotta" -> !features.disableTerracotta();
            case "concrete" -> !features.disableConcrete();
            // A CMB block that vanilla later added under the same name (concrete slabs
            // and stairs, in 26.x) is a duplicate on a server whose vanilla has it -
            // unless ViaBackwards lets in older clients, which lack the vanilla one. The
            // generator flags it from the newest vanilla it was built against; whether
            // *this* server's vanilla has the block is only known here. On 1.21.x it never
            // did, so the piece stays. No block to check: keep it rather than guess.
            case "viabackwards" -> server.viaBackwards() || block == null
                    || !server.vanillaHas().test(block);
            case "doubles.block" -> "block".equals(features.doubles());
            case "doubles.furniture" -> "furniture".equals(features.doubles());
            // A switch this build doesn't know: keep the id rather than guess.
            default -> true;
        };
    }

    // --- filtering a configuration file -------------------------------------------

    /** The file without the removed ids, or the very same bytes when nothing went. */
    @SuppressWarnings("unchecked")
    private static byte[] filter(byte[] data, Map<String, Set<String>> removed, Set<String> removedAll) {
        if (removedAll.isEmpty()) {
            return data;
        }
        LoaderOptions loader = new LoaderOptions();
        loader.setCodePointLimit(Integer.MAX_VALUE);
        Object loaded = new Yaml(loader).load(new String(data, StandardCharsets.UTF_8));
        if (!(loaded instanceof Map<?, ?> root)) {
            return data;
        }
        boolean changed = false;
        for (String section : SECTIONS) {
            if (root.get(section) instanceof Map<?, ?> entries) {
                changed |= entries.keySet().removeIf(removed.get(section)::contains);
            }
        }
        // A recipe naming a removed item - as result or ingredient - would fail to load.
        if (root.get("recipes") instanceof Map<?, ?> recipes) {
            changed |= recipes.values().removeIf(recipe -> names(recipe, removedAll));
        }
        if (root.get("categories") instanceof Map<?, ?> categories) {
            Iterator<? extends Map.Entry<?, ?>> it = categories.entrySet().iterator();
            while (it.hasNext()) {
                if (!(it.next().getValue() instanceof Map<?, ?> category)
                        || !(category.get("list") instanceof List<?> list)) {
                    continue;
                }
                changed |= list.removeIf(removedAll::contains);
                if (list.isEmpty()) {
                    it.remove();
                    changed = true;
                } else if (removedAll.contains(category.get("icon"))) {
                    ((Map<String, Object>) category).put("icon", list.getFirst());
                    changed = true;
                }
            }
        }
        if (!changed) {
            return data;
        }
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setIndent(2);
        options.setWidth(Integer.MAX_VALUE);
        options.setSplitLines(false);
        return new Yaml(options).dump(root).getBytes(StandardCharsets.UTF_8);
    }

    private static boolean names(Object node, Set<String> ids) {
        if (node instanceof String text) {
            return ids.contains(text);
        }
        if (node instanceof Map<?, ?> map) {
            for (Object value : map.values()) {
                if (names(value, ids)) {
                    return true;
                }
            }
        }
        if (node instanceof List<?> list) {
            for (Object value : list) {
                if (names(value, ids)) {
                    return true;
                }
            }
        }
        return false;
    }
}
