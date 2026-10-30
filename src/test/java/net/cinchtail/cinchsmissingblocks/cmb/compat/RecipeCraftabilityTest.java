package net.cinchtail.cinchsmissingblocks.cmb.compat;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class RecipeCraftabilityTest {
    @Test void anotherCustomBlockOnTheSameVanillaMaterialIsNotAnIngredient() {
        assertFalse(RecipeCraftability.canCraft(List.of(one("cmb:calcite_bricks")),
                List.of(new RecipeCraftability.Stock<>("cmb:blue_nether_bricks", 64))));
        assertFalse(RecipeCraftability.canCraft(List.of(one("minecraft:nether_brick")),
                List.of(new RecipeCraftability.Stock<>("cmb:blue_nether_bricks", 64))));
    }

    @Test void repeatedPatternSlotsNeedTheirFullQuantityAndDoNotConsumeTheSnapshot() {
        List<Predicate<String>> slots = List.of(one("stone"), one("carpet"), one("carpet"));
        assertFalse(RecipeCraftability.canCraft(slots, List.of(new RecipeCraftability.Stock<>("stone", 1),
                new RecipeCraftability.Stock<>("carpet", 1))));
        var complete = List.of(new RecipeCraftability.Stock<>("stone", 1), new RecipeCraftability.Stock<>("carpet", 2));
        assertTrue(RecipeCraftability.canCraft(slots, complete));
        assertTrue(RecipeCraftability.canCraft(slots, complete));
    }

    @Test void overlappingAlternativesRequireAnAssignmentRatherThanGreedyConsumption() {
        assertTrue(RecipeCraftability.canCraft(List.of(Set.of("oak", "birch")::contains, one("oak")),
                List.of(new RecipeCraftability.Stock<>("oak", 1), new RecipeCraftability.Stock<>("birch", 1))));
        assertFalse(RecipeCraftability.canCraft(List.of(one("oak"), one("oak")),
                List.of(new RecipeCraftability.Stock<>("oak", 1), new RecipeCraftability.Stock<>("birch", 64))));
    }

    private static Predicate<String> one(String id) { return id::equals; }
}
