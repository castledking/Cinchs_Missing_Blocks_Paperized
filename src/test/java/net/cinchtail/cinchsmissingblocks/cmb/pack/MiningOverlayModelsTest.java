package net.cinchtail.cinchsmissingblocks.cmb.pack;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MiningOverlayModelsTest {
    @Test void onlyActiveGeometryIsExpandedAndItSurvivesModelPruning() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("intermediate/mining-models.json", ("{\"items\":{\"cinchsmissingblocks:wall\":\"post\","
                + "\"cinchsmissingblocks:disabled\":\"unused\"},\"models\":{\"post\":{\"elements\":[]},"
                + "\"unused\":{\"elements\":[]}}}").getBytes(StandardCharsets.UTF_8));
        MiningOverlayModels.install(files, Set.of("cinchsmissingblocks:disabled"));
        PackModelPruner.prune(files);
        for (int stage = 0; stage < 10; stage++) {
            assertTrue(files.containsKey("resourcepack/assets/cinchsmissingblocks/models/mining/post_" + stage + ".json"));
            assertTrue(files.containsKey("resourcepack/assets/cinchsmissingblocks/items/mining/post_" + stage + ".json"));
        }
        assertTrue(files.keySet().stream().noneMatch(path -> path.contains("unused_")));
    }
}
