package net.cinchtail.cinchsmissingblocks.cmb.pack;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

/** Removes CMB models which no installed configuration or asset can reach. */
final class PackModelPruner {
    private static final String MODELS = "resourcepack/assets/cinchsmissingblocks/models/";
    private static final String NAMESPACE = "cinchsmissingblocks:";

    private PackModelPruner() {}

    static void prune(Map<String, byte[]> files) {
        Map<String, String> paths = new HashMap<>();
        Map<String, Set<String>> dependencies = new HashMap<>();
        Set<String> roots = new HashSet<>();
        LoaderOptions options = new LoaderOptions();
        options.setCodePointLimit(Integer.MAX_VALUE);
        Yaml yaml = new Yaml(options);
        for (String path : files.keySet()) {
            if (path.startsWith(MODELS) && path.endsWith(".json")) {
                paths.put(NAMESPACE + path.substring(MODELS.length(), path.length() - 5), path);
            }
        }
        for (var file : files.entrySet()) {
            String path = file.getKey();
            if (path.startsWith("configuration/") && path.endsWith(".yml")) {
                collectYaml(yaml.load(new String(file.getValue(), StandardCharsets.UTF_8)), roots);
            } else if (path.startsWith("resourcepack/") && path.endsWith(".json")) {
                JsonElement json = JsonParser.parseString(new String(file.getValue(), StandardCharsets.UTF_8));
                if (path.startsWith(MODELS)) {
                    String model = NAMESPACE + path.substring(MODELS.length(), path.length() - 5);
                    Set<String> refs = new HashSet<>();
                    collectJson(json, refs, true);
                    dependencies.put(model, refs);
                } else {
                    // Includes every blockstate, legacy item override and overlay,
                    // even when it is only used by an older supported client.
                    collectJson(json, roots, false);
                }
            }
        }
        Set<String> reachable = new HashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>(roots);
        while (!pending.isEmpty()) {
            String model = pending.removeFirst();
            if (reachable.add(model)) pending.addAll(dependencies.getOrDefault(model, Set.of()));
        }
        paths.forEach((model, path) -> {
            if (!reachable.contains(model)) files.remove(path);
        });
    }

    private static void collectYaml(Object value, Set<String> refs) {
        if (value instanceof String text) {
            if (text.startsWith(NAMESPACE)) refs.add(text);
        } else if (value instanceof Map<?, ?> map) {
            // Keys can also be model IDs in a CE appearance map.
            map.forEach((key, child) -> { collectYaml(key, refs); collectYaml(child, refs); });
        } else if (value instanceof Iterable<?> list) {
            list.forEach(child -> collectYaml(child, refs));
        }
    }

    private static void collectJson(JsonElement value, Set<String> refs, boolean model) {
        if (value.isJsonObject()) {
            value.getAsJsonObject().entrySet().forEach(entry -> {
                JsonElement child = entry.getValue();
                if (child.isJsonPrimitive() && child.getAsJsonPrimitive().isString()) {
                    if (!model || entry.getKey().equals("parent") || entry.getKey().equals("model")) {
                        String ref = child.getAsString();
                        if (ref.startsWith(NAMESPACE)) refs.add(ref);
                    }
                } else {
                    collectJson(child, refs, model);
                }
            });
        } else if (value.isJsonArray()) {
            value.getAsJsonArray().forEach(child -> collectJson(child, refs, model));
        }
    }
}
