package net.cinchtail.cinchsmissingblocks.cmb;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class ResinPackTest {
    @Test
    void resinFamilyHasBrowserItemsAndCommandUnlocks() throws Exception {
        Path pack = Path.of("pack");
        Map<?, ?> items = (Map<?, ?>) new Yaml().loadAs(
                Files.readString(pack.resolve("configuration/items.yml")), Map.class).get("items");
        for (String finish : Set.of("mossy", "cracked")) {
            for (String shape : Set.of("bricks", "brick_stairs", "brick_slab", "brick_wall")) {
                String id = "cinchsmissingblocks:" + finish + "_resin_" + shape;
                assertTrue(items.containsKey(id), "Missing browser item: " + id);
            }
        }
        Map<?, ?> recipes = (Map<?, ?>) new Yaml().loadAs(
                Files.readString(pack.resolve("configuration/recipes.yml")), Map.class).get("recipes");
        Set<String> rewards = JsonParser.parseString(Files.readString(
                pack.resolve("intermediate/recipe-advancements.json"))).getAsJsonObject().asMap().values()
                .stream().flatMap(rule -> rule.getAsJsonObject().getAsJsonObject("advancement")
                        .getAsJsonObject("rewards").getAsJsonArray("recipes").asList().stream())
                .map(value -> value.getAsString()).collect(Collectors.toSet());
        for (String moss : Set.of("pale_moss_block", "pale_moss_carpet", "pale_hanging_moss")) {
            String recipe = "cinchsmissingblocks:mossy_resin_bricks_from_" + moss;
            assertTrue(recipes.containsKey(recipe), "Missing crafting recipe: " + recipe);
            assertTrue(rewards.contains(recipe), "Missing advancement command reward: " + recipe);
        }
        assertTrue(Files.isRegularFile(pack.resolve(
                "resourcepack/assets/cinchsmissingblocks/textures/block/mossy_resin_bricks.png")));
    }
}
