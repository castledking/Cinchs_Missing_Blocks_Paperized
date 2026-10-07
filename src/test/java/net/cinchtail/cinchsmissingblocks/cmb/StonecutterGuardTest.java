package net.cinchtail.cinchsmissingblocks.cmb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StonecutterGuardTest {

    private static final Map<String, Set<String>> RECIPES = StonecutterGuard.ingredients("""
            recipes:
              "cinchsmissingblocks:andesite_brick_vertical_from_andesite_bricks_stonecutting":
                type: stonecutting
                ingredient: "cinchsmissingblocks:andesite_bricks"
                result: {id: "cinchsmissingblocks:andesite_brick_vertical", count: 1}
              "cinchsmissingblocks:stone_brick_vertical_from_stone_bricks_stonecutting":
                type: stonecutting
                ingredient: "minecraft:stone_bricks"
                result: {id: "cinchsmissingblocks:stone_brick_vertical", count: 1}
              "cinchsmissingblocks:andesite_brick_vertical":
                type: shaped
                ingredients: {"#": "cinchsmissingblocks:andesite_bricks"}
            """);

    @Test
    void readsStonecuttingRecipesOnly() {
        assertEquals(2, RECIPES.size());
    }

    @Test
    void aCmbIngredientTakesOnlyThatCmbItem() {
        Set<String> needed = RECIPES.get("cinchsmissingblocks:andesite_brick_vertical_from_andesite_bricks_stonecutting");
        assertTrue(StonecutterGuard.accepts(needed, "cinchsmissingblocks:andesite_bricks"));
        // The exploit: every CMB item is a nether_brick underneath.
        assertFalse(StonecutterGuard.accepts(needed, "cinchsmissingblocks:chiseled_andesite_bricks"));
        assertFalse(StonecutterGuard.accepts(needed, "minecraft:nether_brick"));
    }

    @Test
    void aVanillaIngredientTakesThatVanillaItem() {
        Set<String> needed = RECIPES.get("cinchsmissingblocks:stone_brick_vertical_from_stone_bricks_stonecutting");
        assertTrue(StonecutterGuard.accepts(needed, "minecraft:stone_bricks"));
        assertFalse(StonecutterGuard.accepts(needed, null));
    }
}
