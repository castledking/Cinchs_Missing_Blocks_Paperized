package net.cinchtail.cinchsmissingblocks.cmb;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cinchtail.cinchsmissingblocks.cmb.compat.RecipeBookCompat;
import net.cinchtail.cinchsmissingblocks.cmb.scheduler.Schedulers;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.api.event.CraftEngineReloadEvent;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.UnsafeValues;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRecipeDiscoverEvent;
import org.bukkit.inventory.ItemStack;

/**
 * The mod's hidden recipe advancements, with native rewards and commands.
 *
 * <p>A vanilla inventory predicate cannot name a CraftEngine item: its material is
 * nether brick, shared by all CMB items. Inventory and recipe-discovery predicates
 * are bridged through Paper events; the original criterion names, requirements
 * and rewards stay native. Both /advancement grant and /recipe give work normally.
 */
public final class RecipeAdvancements implements Listener {
    private final CmbPlugin plugin;
    private final Map<NamespacedKey, JsonObject> definitions = new LinkedHashMap<>();
    private Map<String, List<Unlock>> byItem = Map.of();
    private Map<NamespacedKey, List<Unlock>> byRecipe = Map.of();

    private record Unlock(NamespacedKey advancement, String criterion, List<NamespacedKey> recipes) {}

    public RecipeAdvancements(CmbPlugin plugin) throws IOException {
        this.plugin = plugin;
        try (InputStream in = plugin.getResource("pack/intermediate/recipe-advancements.json")) {
            if (in == null) throw new IOException("Bundled recipe advancements are missing");
            JsonObject root = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            root.entrySet().forEach(entry -> definitions.put(nativeKey(entry.getKey()),
                    entry.getValue().getAsJsonObject().getAsJsonObject("advancement")));
        }
    }

    /** Vanilla's two overridden advancement ids get a companion, preserving vanilla's tree. */
    static NamespacedKey nativeKey(String source) {
        NamespacedKey key = NamespacedKey.fromString(source);
        if (key == null) throw new IllegalArgumentException("Invalid advancement key: " + source);
        return key.getNamespace().equals("minecraft")
                ? new NamespacedKey("cinchsmissingblocks", "recipes/vanilla/"
                        + key.getKey().replaceFirst("^recipes/", "")) : key;
    }

    /** Requirements/rewards stay native; Paper events award the original criteria. */
    static JsonObject nativeDefinition(JsonObject source) {
        JsonObject out = source.deepCopy();
        for (JsonElement value : out.getAsJsonObject("criteria").asMap().values()) {
            JsonObject criterion = value.getAsJsonObject();
            // 26.3's recipe-unlocked predicate needs a recipe holder registry
            // that Paper's dynamic advancement loader does not expose. Bridging
            // discovery also avoids relying on that version-specific JSON shape.
            criterion.addProperty("trigger", "minecraft:impossible");
            criterion.remove("conditions");
        }
        return out;
    }

    @EventHandler
    public void onReload(CraftEngineReloadEvent event) {
        // CraftEngine finishes native recipe registration around its reload event.
        Schedulers.globalLater(plugin, this::register, 1L);
    }

    private void register() {
        RecipeBookCompat.refresh(plugin.getLogger());
        Map<NamespacedKey, String> pending = new LinkedHashMap<>();
        Map<String, List<Unlock>> index = new HashMap<>();
        Map<NamespacedKey, List<Unlock>> recipeIndex = new HashMap<>();
        int active = 0;
        for (Map.Entry<NamespacedKey, JsonObject> entry : definitions.entrySet()) {
            JsonObject source = entry.getValue();
            List<NamespacedKey> recipes = new ArrayList<>();
            for (JsonElement reward : source.getAsJsonObject("rewards").getAsJsonArray("recipes")) {
                recipes.add(NamespacedKey.fromString(reward.getAsString()));
            }
            // A switched-off piece must have neither a usable unlock nor a recipe reward.
            if (recipes.stream().anyMatch(key -> key == null || Bukkit.getRecipe(key) == null)) continue;
            active++;
            if (Bukkit.getAdvancement(entry.getKey()) == null) {
                pending.put(entry.getKey(), nativeDefinition(source).toString());
            }
            for (Map.Entry<String, JsonElement> criterion : source.getAsJsonObject("criteria").entrySet()) {
                JsonObject body = criterion.getValue().getAsJsonObject();
                Unlock unlock = new Unlock(entry.getKey(), criterion.getKey(), List.copyOf(recipes));
                if (body.get("trigger").getAsString().equals("minecraft:recipe_unlocked")) {
                    NamespacedKey recipe = NamespacedKey.fromString(body.getAsJsonObject("conditions")
                            .get("recipe").getAsString());
                    recipeIndex.computeIfAbsent(recipe, ignored -> new ArrayList<>()).add(unlock);
                    continue;
                }
                JsonElement items = body.getAsJsonObject("conditions").getAsJsonArray("items")
                        .get(0).getAsJsonObject().get("items");
                if (items.isJsonArray()) {
                    items.getAsJsonArray().forEach(item -> index.computeIfAbsent(item.getAsString(),
                            ignored -> new ArrayList<>()).add(unlock));
                } else {
                    index.computeIfAbsent(items.getAsString(), ignored -> new ArrayList<>()).add(unlock);
                }
            }
        }
        try {
            loadTransient(pending);
            byItem = Map.copyOf(index);
            byRecipe = Map.copyOf(recipeIndex);
            plugin.getLogger().info("Registered " + active + " recipe advancements from the mod.");
            for (Player player : Bukkit.getOnlinePlayers()) {
                Schedulers.entity(plugin, player, () -> scan(player), null);
            }
        } catch (ReflectiveOperationException | RuntimeException ex) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Could not register recipe advancements", ex);
        }
    }

    @SuppressWarnings("deprecation")
    private static void loadTransient(Map<NamespacedKey, String> pending) throws ReflectiveOperationException {
        if (pending.isEmpty()) return;
        UnsafeValues unsafe = Bukkit.getUnsafe();
        try {
            // New Paper can load the whole set without writing a data pack or
            // rebuilding every online player's advancement data once per recipe.
            Method batch = UnsafeValues.class.getMethod("loadAdvancements", Map.class, boolean.class);
            Object result = batch.invoke(unsafe, pending, false);
            if (result instanceof List<?> loaded && loaded.size() != pending.size()) {
                throw new IllegalStateException("Some recipe advancements failed to load");
            }
        } catch (NoSuchMethodException olderPaper) {
            // 1.21.1's API persists the JSON; remove the file immediately. Removal
            // does not unload the advancement from the running server.
            for (Map.Entry<NamespacedKey, String> entry : pending.entrySet()) {
                try {
                    if (unsafe.loadAdvancement(entry.getKey(), entry.getValue()) == null) {
                        throw new IllegalStateException("Could not load " + entry.getKey());
                    }
                } finally {
                    unsafe.removeAdvancement(entry.getKey());
                }
            }
        } catch (InvocationTargetException ex) {
            throw new IllegalStateException("Paper rejected the recipe advancements", ex.getCause());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryChange(PlayerInventorySlotChangeEvent event) {
        if (event.shouldTriggerAdvancements()) obtain(event.getPlayer(), event.getNewItemStack());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Schedulers.entity(plugin, event.getPlayer(), () -> scan(event.getPlayer()), null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRecipeDiscover(PlayerRecipeDiscoverEvent event) {
        List<Unlock> unlocks = byRecipe.getOrDefault(event.getRecipe(), List.of());
        if (unlocks.isEmpty()) return;
        // The discover event fires before the recipe enters the book. Award on
        // the next entity tick so its native reward cannot recursively discover
        // that same recipe while the original discovery is still in progress.
        Schedulers.entity(plugin, event.getPlayer(), () -> {
            for (Unlock unlock : unlocks) award(event.getPlayer(), unlock);
        }, null);
    }

    private void scan(Player player) {
        for (ItemStack item : player.getInventory().getContents()) obtain(player, item);
    }

    private void obtain(Player player, ItemStack item) {
        if (item == null || item.getType().isAir()) return;
        String id = CraftEngineItems.isCustomItem(item)
                ? CraftEngineItems.getCustomItemId(item).asString() : item.getType().getKey().toString();
        for (Unlock unlock : byItem.getOrDefault(id, List.of())) {
            award(player, unlock);
        }
    }

    private void award(Player player, Unlock unlock) {
        if (unlock.recipes().stream().anyMatch(key -> Bukkit.getRecipe(key) == null)) return;
        Advancement advancement = Bukkit.getAdvancement(unlock.advancement());
        if (advancement == null) return;
        AdvancementProgress progress = player.getAdvancementProgress(advancement);
        if (!progress.isDone()) progress.awardCriteria(unlock.criterion());
    }
}
