package net.cinchtail.cinchsmissingblocks.cmb;

import static org.junit.jupiter.api.Assertions.assertTrue;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LegacyClientAssetsTest {
    @Test
    void allBundledModelDependenciesExistOnOldClients() throws Exception {
        Path root = Path.of("pack/resourcepack");
        JsonObject vanilla = JsonParser.parseString(Files.readString(
                Path.of("src/test/resources/client-1.21.1-assets.json"))).getAsJsonObject();
        Set<String> available = new HashSet<>();
        for (String kind : Set.of("models", "textures")) {
            vanilla.getAsJsonArray(kind).forEach(ref -> available.add(ref.getAsString()));
        }
        try (var paths = Files.walk(root)) {
            for (Path model : paths.filter(p -> p.toString().contains("/models/")
                    && p.toString().endsWith(".json")).toList()) {
                JsonObject data = JsonParser.parseString(Files.readString(model)).getAsJsonObject();
                if (data.has("parent")) {
                    check(root, available, "models", data.get("parent").getAsString(), ".json", model);
                }
                if (data.has("textures")) {
                    for (var ref : data.getAsJsonObject("textures").asMap().values()) {
                        check(root, available, "textures", ref.getAsString(), ".png", model);
                    }
                }
            }
        }
    }

    private static void check(Path root, Set<String> vanilla, String kind, String ref,
                              String suffix, Path model) {
        if (ref.startsWith("#") || ref.startsWith("builtin/")) return;
        String[] key = (ref.contains(":") ? ref : "minecraft:" + ref).split(":", 2);
        String file = "assets/" + key[0] + "/" + kind + "/" + key[1] + suffix;
        assertTrue(Files.isRegularFile(root.resolve(file)) || vanilla.contains(file),
                () -> model + " needs " + ref + ", missing on 1.21.1 clients");
    }
}
