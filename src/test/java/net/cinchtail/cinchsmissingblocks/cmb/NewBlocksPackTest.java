package net.cinchtail.cinchsmissingblocks.cmb;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class NewBlocksPackTest {
    @Test
    void everyNewUpstreamBlockHasAnItemPlacementAndRecipeReward() throws Exception {
        JsonObject expected = JsonParser.parseString(Files.readString(
                Path.of("src/test/resources/upstream-new-blocks.json"))).getAsJsonObject().getAsJsonObject("blocks");
        JsonObject pieces = JsonParser.parseString(Files.readString(Path.of("pack/intermediate/pieces.json")))
                .getAsJsonObject();
        Map<?, ?> recipes = (Map<?, ?>) new Yaml().loadAs(Files.readString(
                Path.of("pack/configuration/recipes.yml")), Map.class).get("recipes");
        Set<String> rewards = JsonParser.parseString(Files.readString(
                Path.of("pack/intermediate/recipe-advancements.json"))).getAsJsonObject().asMap().values()
                .stream().flatMap(rule -> rule.getAsJsonObject().getAsJsonObject("advancement")
                        .getAsJsonObject("rewards").getAsJsonArray("recipes").asList().stream())
                .map(value -> value.getAsString()).collect(Collectors.toSet());
        for (String id : expected.keySet()) {
            assertTrue(pieces.getAsJsonObject("items").has(id), "Missing browser item: " + id);
            assertTrue(pieces.getAsJsonObject("blocks").has(id) || pieces.getAsJsonObject("furniture").has(id),
                    "Missing placement: " + id);
            assertTrue(recipes.entrySet().stream().anyMatch(entry -> {
                Map<?, ?> recipe = (Map<?, ?>) entry.getValue();
                return recipe.get("result") instanceof Map<?, ?> result && id.equals(result.get("id"))
                        && rewards.contains(entry.getKey().toString());
            }), "Missing crafting/unlock recipe: " + id);
        }
        for (String color : Set.of("white", "orange", "magenta", "light_blue", "yellow", "lime", "pink",
                "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black")) {
            for (String material : Set.of("concrete", "terracotta")) {
                for (String shape : Set.of("slab", "stairs", "wall")) {
                    assertTrue(pieces.getAsJsonObject("items").has("cinchsmissingblocks:"
                            + color + "_" + material + "_" + shape), "Missing existing dyed variant");
                }
            }
        }
    }
}
