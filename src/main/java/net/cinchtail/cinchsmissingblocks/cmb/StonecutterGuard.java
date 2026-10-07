package net.cinchtail.cinchsmissingblocks.cmb;

import io.papermc.paper.event.player.PlayerStonecutterRecipeSelectEvent;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Makes a CMB stonecutting recipe take only its own ingredient.
 *
 * <p>CraftEngine registers a stonecutting recipe whose ingredient is a CraftEngine item by
 * that item's vanilla base type alone, and every CMB item's base is {@code nether_brick}. So
 * the stonecutter matched any CMB block - or a plain vanilla nether brick - against every
 * CMB recipe, and a selected recipe made its result: a chiseled block, or one nether brick,
 * could be cut into any CMB stair, slab or vertical slab.
 *
 * <p>The selection is where the server decides, so that is where this checks: a CMB recipe
 * is refused unless the input is exactly its ingredient - the same CraftEngine item, or the
 * same vanilla item and not a CraftEngine one. The ingredients are read from the pack this
 * jar bundles.
 */
final class StonecutterGuard implements Listener {

    private final Map<String, Set<String>> ingredients;

    StonecutterGuard(Plugin plugin) {
        Map<String, Set<String>> read = Map.of();
        try (InputStream in = plugin.getResource("pack/configuration/recipes.yml")) {
            if (in != null) {
                read = ingredients(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().warning("Could not read the stonecutting recipes: " + e);
        }
        this.ingredients = read;
    }

    /** Stonecutting recipe id -> the item ids it takes, from a CraftEngine recipes file. */
    @SuppressWarnings("unchecked")
    static Map<String, Set<String>> ingredients(String recipesYaml) {
        LoaderOptions loader = new LoaderOptions();
        loader.setCodePointLimit(Integer.MAX_VALUE);
        Object loaded = new Yaml(loader).load(recipesYaml);
        Map<String, Set<String>> out = new HashMap<>();
        if (!(loaded instanceof Map<?, ?> root) || !(root.get("recipes") instanceof Map<?, ?> recipes)) {
            return out;
        }
        for (Map.Entry<?, ?> entry : recipes.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> recipe) || !"stonecutting".equals(recipe.get("type"))) {
                continue;
            }
            Object ingredient = recipe.get("ingredient");
            if (ingredient instanceof String id) {
                out.put(String.valueOf(entry.getKey()), Set.of(id));
            } else if (ingredient instanceof List<?> ids) {
                out.put(String.valueOf(entry.getKey()), Set.copyOf((List<String>) ids));
            }
        }
        return out;
    }

    /** Whether an input with this id may be cut by a recipe taking these ingredients. */
    static boolean accepts(Set<String> ingredients, String input) {
        return input != null && ingredients.contains(input);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSelect(PlayerStonecutterRecipeSelectEvent event) {
        Set<String> needed = ingredients.get(event.getStonecuttingRecipe().getKey().toString());
        if (needed == null) {
            return;
        }
        if (!accepts(needed, id(event.getStonecutterInventory().getInputItem()))) {
            event.setCancelled(true);
        }
    }

    private static String id(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return null;
        }
        return CraftEngineItems.isCustomItem(stack)
                ? CraftEngineItems.getCustomItemId(stack).asString()
                : stack.getType().getKey().toString();
    }
}
