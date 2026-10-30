package net.cinchtail.cinchsmissingblocks.cmb.pack;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PackModelPrunerTest {
    private static final String MODELS = "resourcepack/assets/cinchsmissingblocks/models/";

    @Test
    void filteredConfigurationRetainsParentsAndAllClientRootsButDropsDisabledGeometry() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        put(files, "configuration/items.yml", "items:\n  cinchsmissingblocks:enabled:\n    model:\n      path: cinchsmissingblocks:item/enabled\n");
        put(files, MODELS + "item/enabled.json", "{\"parent\":\"cinchsmissingblocks:block/shared\"}");
        put(files, MODELS + "block/shared.json", "{\"parent\":\"minecraft:block/cube_all\"}");
        put(files, MODELS + "item/disabled.json", "{\"parent\":\"cinchsmissingblocks:block/disabled\"}");
        put(files, MODELS + "block/disabled.json", "{\"elements\":[]}");
        put(files, "resourcepack/assets/minecraft/models/item/paper.json", "{\"overrides\":[{\"model\":\"cinchsmissingblocks:item/legacy\"}]}");
        put(files, MODELS + "item/legacy.json", "{\"elements\":[]}");
        put(files, "resourcepack/older/assets/minecraft/blockstates/stone.json", "{\"variants\":{\"\":{\"model\":\"cinchsmissingblocks:block/older\"}}}");
        put(files, MODELS + "block/older.json", "{\"elements\":[]}");
        put(files, "resourcepack/assets/cinchsmissingblocks/textures/block/disabled.png", "pixels");

        PackModelPruner.prune(files);

        assertFalse(files.containsKey(MODELS + "item/disabled.json"));
        assertFalse(files.containsKey(MODELS + "block/disabled.json"));
        for (String kept : new String[]{"item/enabled", "block/shared", "item/legacy", "block/older"}) {
            assertTrue(files.containsKey(MODELS + kept + ".json"), kept);
        }
        assertTrue(files.containsKey("resourcepack/assets/cinchsmissingblocks/textures/block/disabled.png"));
    }

    private static void put(Map<String, byte[]> files, String path, String text) {
        files.put(path, text.getBytes(StandardCharsets.UTF_8));
    }
}
