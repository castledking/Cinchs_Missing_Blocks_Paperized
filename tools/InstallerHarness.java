package net.cinchtail.cinchsmissingblocks.cmb.pack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;
import net.cinchtail.cinchsmissingblocks.cmb.config.CmbConfig;
import net.cinchtail.cinchsmissingblocks.cmb.config.ConfigLoader;

/**
 * Runs PackInstaller's real filter on a jar's bundled pack, without a server, for
 * tools/test_installer.py. Args: jar, out dir, then key=value overrides of the default
 * config: terracotta, concrete, viabackwards, fallback, doubles, vs.cmb, vs.vanilla,
 * hs.cmb, hs.vanilla, vs.disabled (comma list).
 */
public final class InstallerHarness {
    public static void main(String[] args) throws Exception {
        Map<String, String> o = new HashMap<>();
        for (int i = 2; i < args.length; i++) {
            String[] kv = args[i].split("=", 2);
            o.put(kv[0], kv[1]);
        }
        CmbConfig d = ConfigLoader.defaults();
        CmbConfig.Features f = d.features();
        CmbConfig.VerticalSlabs vs = new CmbConfig.VerticalSlabs(
                bool(o, "vs.cmb", f.verticalSlabs().cmb()), bool(o, "vs.vanilla", f.verticalSlabs().vanilla()),
                o.containsKey("vs.disabled") ? Set.of(o.get("vs.disabled").split(",")) : Set.of());
        CmbConfig.VerticalSlabs hs = new CmbConfig.VerticalSlabs(
                bool(o, "hs.cmb", f.horizontalStairs().cmb()), bool(o, "hs.vanilla", f.horizontalStairs().vanilla()), Set.of());
        CmbConfig cfg = new CmbConfig(
                new CmbConfig.Features(vs, hs, o.getOrDefault("doubles", f.doubles()),
                        bool(o, "fallback", f.furnitureFallback()),
                        !bool(o, "terracotta", !f.disableTerracotta()), !bool(o, "concrete", !f.disableConcrete()),
                        f.protectFurnitureCells()),
                d.resourcePack(), d.content(), d.compatibility(), d.craftEngine(), d.disabledBlocks());
        boolean via = bool(o, "viabackwards", false);

        try (ZipFile jar = new ZipFile(args[0])) {
            JsonObject manifest = JsonParser.parseString(read(jar, "pack/intermediate/pieces.json")).getAsJsonObject();
            Map<String, Set<String>> removed = new HashMap<>();
            Set<String> all = new HashSet<>();
            for (String section : List.of("items", "blocks", "furniture")) {
                Set<String> ids = new HashSet<>();
                for (Map.Entry<String, JsonElement> e : manifest.getAsJsonObject(section).entrySet()) {
                    for (JsonElement flag : e.getValue().getAsJsonArray()) {
                        if (!PackInstaller.on(flag.getAsString(), cfg, via)) {
                            ids.add(e.getKey());
                            break;
                        }
                    }
                }
                removed.put(section, ids);
                all.addAll(ids);
            }
            Method filter = PackInstaller.class.getDeclaredMethod("filter", byte[].class, Map.class, Set.class);
            filter.setAccessible(true);
            Path out = Paths.get(args[1]);
            for (String rel : read(jar, "pack/index.txt").split("\n")) {
                if (!rel.startsWith("configuration/")) {
                    continue;
                }
                byte[] data = jar.getInputStream(jar.getEntry("pack/" + rel)).readAllBytes();
                data = (byte[]) filter.invoke(null, data, removed, all);
                Path file = out.resolve(rel);
                Files.createDirectories(file.getParent());
                Files.write(file, data);
            }
        }
    }

    private static boolean bool(Map<String, String> o, String key, boolean fallback) {
        return o.containsKey(key) ? Boolean.parseBoolean(o.get(key)) : fallback;
    }

    private static String read(ZipFile jar, String name) throws Exception {
        return new String(jar.getInputStream(jar.getEntry(name)).readAllBytes(), StandardCharsets.UTF_8);
    }
}
