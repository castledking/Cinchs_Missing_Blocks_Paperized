package net.cinchtail.cinchsmissingblocks.cmb.compat;

import java.util.List;
import java.util.function.Predicate;
import net.momirealms.craftengine.core.item.recipe.StackedContents;

/** Matches actual custom item identities and quantities, including alternatives. */
final class RecipeCraftability {
    record Stock<T>(T item, int count) {}

    private RecipeCraftability() {}

    static <T> boolean canCraft(List<? extends Predicate<T>> ingredients, List<Stock<T>> inventory) {
        StackedContents<T> contents = new StackedContents<>();
        for (Stock<T> stock : inventory) {
            if (stock.count() > 0) contents.add(stock.item(), stock.count());
        }
        List<StackedContents.IngredientInfo<T>> requirements = ingredients.stream()
                .<StackedContents.IngredientInfo<T>>map(predicate -> predicate::test).toList();
        return contents.tryPick(requirements);
    }
}
