package net.cinchtail.cinchsmissingblocks.cmb.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Expands shared crack geometry only for display items installed on this server. */
final class MiningOverlayModels {
    private MiningOverlayModels() {}

    static void install(Map<String, byte[]> files, Set<String> removed) {
        byte[] source = files.get("intermediate/mining-models.json");
        if (source == null) return;
        JsonObject manifest = JsonParser.parseString(new String(source, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject items = manifest.getAsJsonObject("items");
        removed.forEach(items::remove);
        Set<String> shapes = new HashSet<>();
        items.entrySet().forEach(entry -> shapes.add(entry.getValue().getAsString()));
        for (String shape : shapes) {
            for (int stage = 0; stage < 10; stage++) {
                String path = "mining/" + shape + "_" + stage;
                String id = "cinchsmissingblocks:" + path;
                JsonObject model = manifest.getAsJsonObject("models").getAsJsonObject(shape).deepCopy();
                JsonObject textures = new JsonObject();
                textures.addProperty("crack", "minecraft:block/destroy_stage_" + stage);
                textures.addProperty("particle", "#crack");
                model.add("textures", textures);
                files.put("resourcepack/assets/cinchsmissingblocks/models/" + path + ".json", bytes(model));
                JsonObject definition = new JsonObject();
                JsonObject item = new JsonObject();
                item.addProperty("type", "minecraft:model");
                item.addProperty("model", id);
                JsonObject tint = new JsonObject();
                tint.addProperty("type", "minecraft:constant");
                tint.addProperty("value", 0);
                JsonArray tints = new JsonArray(); tints.add(tint); item.add("tints", tints);
                definition.add("model", item);
                files.put("resourcepack/assets/cinchsmissingblocks/items/" + path + ".json", bytes(definition));
            }
        }
        files.put("intermediate/mining-models.json", bytes(manifest));
    }

    private static byte[] bytes(JsonObject value) { return value.toString().getBytes(StandardCharsets.UTF_8); }
}
