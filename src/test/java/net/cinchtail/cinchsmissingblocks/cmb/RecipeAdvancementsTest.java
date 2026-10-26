package net.cinchtail.cinchsmissingblocks.cmb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class RecipeAdvancementsTest {
    @Test
    void nativeRewardsAndRecipeCommandsKeepTheModRequirements() {
        JsonObject source = JsonParser.parseString("""
                {
                  "parent":"minecraft:recipes/root",
                  "criteria":{
                    "has_stone_bricks":{"trigger":"minecraft:inventory_changed",
                      "conditions":{"items":[{"items":"minecraft:stone_bricks"}]}},
                    "has_the_recipe":{"trigger":"minecraft:recipe_unlocked",
                      "conditions":{"recipe":"cinchsmissingblocks:mossy_stone_bricks_from_moss_carpet"}}
                  },
                  "requirements":[["has_stone_bricks","has_the_recipe"]],
                  "rewards":{"recipes":["cinchsmissingblocks:mossy_stone_bricks_from_moss_carpet"]}
                }
                """).getAsJsonObject();
        JsonObject nativeAdvancement = RecipeAdvancements.nativeDefinition(source);
        assertEquals(source.get("requirements"), nativeAdvancement.get("requirements"));
        assertEquals(source.get("rewards"), nativeAdvancement.get("rewards"));
        assertEquals(source.getAsJsonObject("criteria").keySet(),
                nativeAdvancement.getAsJsonObject("criteria").keySet());
        JsonObject bridged = nativeAdvancement.getAsJsonObject("criteria").getAsJsonObject("has_stone_bricks");
        assertEquals("minecraft:impossible", bridged.get("trigger").getAsString());
        assertFalse(bridged.has("conditions"));
        assertEquals("minecraft:inventory_changed", source.getAsJsonObject("criteria")
                .getAsJsonObject("has_stone_bricks").get("trigger").getAsString());
        JsonObject recipeCriterion = nativeAdvancement.getAsJsonObject("criteria").getAsJsonObject("has_the_recipe");
        assertEquals("minecraft:impossible", recipeCriterion.get("trigger").getAsString());
        assertFalse(recipeCriterion.has("conditions"));
    }

    @Test
    void vanillaRecipeUnlocksGetACompanionWithoutReplacingVanillaAdvancements() {
        assertEquals("cinchsmissingblocks:recipes/vanilla/building_blocks/red_nether_bricks",
                RecipeAdvancements.nativeKey("minecraft:recipes/building_blocks/red_nether_bricks").toString());
        assertEquals("cinchsmissingblocks:recipes/building_blocks/red_nether_brick",
                RecipeAdvancements.nativeKey("cinchsmissingblocks:recipes/building_blocks/red_nether_brick").toString());
    }
}
