package net.cinchtail.cinchsmissingblocks.cmb.compat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import net.momirealms.craftengine.bukkit.item.recipe.BukkitRecipeManager;

/** The 26.3 command cache can retain the recipes from before plugin registration. */
public final class RecipeBookCompat {
    private RecipeBookCompat() {}

    public static void refresh(Logger logger) {
        Object manager = BukkitRecipeManager.minecraftRecipeManager();
        Method learnable;
        try {
            learnable = manager.getClass().getMethod("getLearnableRecipes");
        } catch (NoSuchMethodException olderServer) {
            return;
        }
        try {
            Collection<?> cached = (Collection<?>) learnable.invoke(manager);
            Collection<?> current = (Collection<?>) manager.getClass().getMethod("getRecipes").invoke(manager);
            List<Object> fresh = new ArrayList<>();
            for (Object holder : current) {
                Object recipe = holder.getClass().getMethod("value").invoke(holder);
                // Invoke the public Recipe interface method, rather than a method
                // on a possibly non-public injected implementation class.
                Method special = holder.getClass().getMethod("value").getReturnType().getMethod("isSpecial");
                if (!Boolean.TRUE.equals(special.invoke(recipe))) fresh.add(holder);
            }
            // RecipeHolder equality compares ids; an override with the same id
            // still replaces the holder/value and must replace the cached one.
            Set<Object> identities = Collections.newSetFromMap(new IdentityHashMap<>());
            identities.addAll(fresh);
            if (cached.size() == fresh.size() && identities.containsAll(cached)) return;
            Field cache = manager.getClass().getDeclaredField("learnableRecipes");
            cache.setAccessible(true);
            cache.set(manager, List.copyOf(fresh));
            logger.info("Refreshed the vanilla recipe command list after CraftEngine registration.");
        } catch (ReflectiveOperationException | RuntimeException ex) {
            logger.warning("Could not refresh the vanilla recipe command list: " + ex);
        }
    }
}
