package net.cinchtail.cinchsmissingblocks.cmb.compat;

import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.cinchtail.cinchsmissingblocks.cmb.CmbPlugin;
import net.cinchtail.cinchsmissingblocks.cmb.scheduler.Schedulers;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.item.BukkitItemManager;
import net.momirealms.craftengine.bukkit.item.recipe.BukkitRecipeManager;
import net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine;
import net.momirealms.craftengine.bukkit.util.ItemStackUtils;
import net.momirealms.craftengine.core.item.recipe.CustomShapedRecipe;
import net.momirealms.craftengine.core.item.recipe.CustomShapelessRecipe;
import net.momirealms.craftengine.core.item.recipe.CustomCookingRecipe;
import net.momirealms.craftengine.core.item.recipe.Ingredient;
import net.momirealms.craftengine.core.item.recipe.Recipe;
import net.momirealms.craftengine.core.item.recipe.UniqueIdItem;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.bukkit.api.event.CraftEngineReloadEvent;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRecipeDiscoverEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.FurnaceInventory;
import org.bukkit.block.Furnace;
import org.bukkit.inventory.ItemStack;

/** Supplies per-player craftability without revoking recipes or changing their displays. */
public final class RecipeBookFilter implements Listener {
    private final CmbPlugin plugin;
    private final Set<UUID> pending = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Map<NamespacedKey, Boolean>> last = new ConcurrentHashMap<>();
    private volatile Map<NamespacedKey, BookRecipe> recipes = Map.of();
    private volatile NativeBook nativeBook;
    private boolean warned;

    private record BookRecipe(List<Predicate<UniqueIdItem>> ingredients, List<Object> displays) {}

    public RecipeBookFilter(CmbPlugin plugin) { this.plugin = plugin; }

    @EventHandler public void load(ServerLoadEvent event) { reload(); }
    @EventHandler public void reload(CraftEngineReloadEvent event) { reload(); }

    private void reload() {
        Schedulers.globalLater(plugin, () -> {
            try {
                Object manager = BukkitRecipeManager.minecraftRecipeManager();
                Field field;
                try { field = manager.getClass().getDeclaredField("recipeToDisplay"); }
                catch (NoSuchFieldException legacy) { return; } // Servers before 1.21.2 have no display entries.
                field.setAccessible(true);
                Map<NamespacedKey, BookRecipe> found = new HashMap<>();
                NativeBook adapter = new NativeBook(manager.getClass().getClassLoader());
                for (var entry : ((Map<?, ?>) field.get(manager)).entrySet()) {
                    NamespacedKey key = adapter.key(entry.getKey());
                    if (key == null || !isBundled(key)) continue;
                    Optional<Recipe> recipe = BukkitCraftEngine.instance().recipeManager().recipeById(Key.of(key.toString()));
                    if (recipe.isEmpty()) continue;
                    List<Predicate<UniqueIdItem>> ingredients = requirements(recipe.get());
                    if (ingredients == null) continue;
                    List<Object> displays = new ArrayList<>();
                    for (Object info : (List<?>) entry.getValue()) {
                        displays.add(info.getClass().getMethod("display").invoke(info));
                    }
                    found.put(key, new BookRecipe(ingredients, List.copyOf(displays)));
                }
                nativeBook = adapter;
                recipes = Map.copyOf(found);
                last.clear();
                Bukkit.getOnlinePlayers().forEach(this::queue);
            } catch (ReflectiveOperationException | RuntimeException error) { warn(error); }
        }, 2);
    }

    private boolean isBundled(NamespacedKey key) {
        // Include the two vanilla overrides which consume CMB items.
        return key.getNamespace().equals("cinchsmissingblocks") || key.toString().equals("minecraft:red_nether_bricks")
                || key.toString().equals("minecraft:nether_brick_fence");
    }

    private static List<Predicate<UniqueIdItem>> requirements(Recipe recipe) {
        List<Ingredient> slots;
        if (recipe instanceof CustomShapedRecipe shaped) {
            slots = Stream.of(shaped.parsedPattern().ingredients()).flatMap(Optional::stream).toList();
        } else if (recipe instanceof CustomShapelessRecipe shapeless) {
            slots = shapeless.ingredientsInUse();
        } else if (recipe instanceof CustomCookingRecipe cooking) {
            slots = cooking.ingredientsInUse();
        } else { return null; }
        List<Predicate<UniqueIdItem>> result = new ArrayList<>();
        for (Ingredient ingredient : slots) {
            for (int count = 0; count < ingredient.count(); count++) result.add(ingredient);
        }
        return List.copyOf(result);
    }

    @EventHandler public void join(PlayerJoinEvent event) { queue(event.getPlayer()); }
    @EventHandler(ignoreCancelled = true) public void discover(PlayerRecipeDiscoverEvent event) {
        Map<NamespacedKey, Boolean> previous = last.get(event.getPlayer().getUniqueId());
        if (previous != null) previous.remove(event.getRecipe());
        queue(event.getPlayer());
    }
    @EventHandler public void slot(PlayerInventorySlotChangeEvent event) { queue(event.getPlayer()); }
    @EventHandler(ignoreCancelled = true) public void open(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) queue(player);
    }
    @EventHandler(priority = EventPriority.MONITOR) public void click(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) queue(player);
    }
    @EventHandler(priority = EventPriority.MONITOR) public void drag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) queue(player);
    }
    @EventHandler public void close(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) queue(player);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void smelt(FurnaceSmeltEvent event) {
        if (event.getBlock().getState() instanceof Furnace furnace) viewers(furnace.getInventory());
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void move(InventoryMoveItemEvent event) {
        if (event.getSource() instanceof FurnaceInventory) viewers(event.getSource());
        if (event.getDestination() instanceof FurnaceInventory) viewers(event.getDestination());
    }
    private void viewers(org.bukkit.inventory.Inventory inventory) {
        inventory.getViewers().forEach(viewer -> { if (viewer instanceof Player player) queue(player); });
    }
    @EventHandler public void quit(PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId()); last.remove(event.getPlayer().getUniqueId());
    }

    private void queue(Player player) {
        if (!pending.add(player.getUniqueId())) return;
        Schedulers.entity(plugin, player, () -> {
            pending.remove(player.getUniqueId());
            if (player.isOnline()) refresh(player);
        }, () -> pending.remove(player.getUniqueId()));
    }

    private void refresh(Player player) {
        NativeBook adapter = nativeBook;
        if (adapter == null) return;
        try {
            List<RecipeCraftability.Stock<UniqueIdItem>> inventory = new ArrayList<>();
            for (ItemStack stack : player.getInventory().getStorageContents()) add(inventory, stack, adapter, true);
            if (player.getOpenInventory().getTopInventory() instanceof CraftingInventory crafting) {
                for (ItemStack stack : crafting.getMatrix()) add(inventory, stack, adapter, false);
            } else if (player.getOpenInventory().getTopInventory() instanceof FurnaceInventory furnace) {
                add(inventory, furnace.getSmelting(), adapter, false);
            }
            Map<NamespacedKey, Boolean> previous = last.computeIfAbsent(player.getUniqueId(), id -> new HashMap<>());
            List<Object> updates = new ArrayList<>();
            Map<NamespacedKey, Boolean> sent = new HashMap<>();
            for (var entry : recipes.entrySet()) {
                if (!player.hasDiscoveredRecipe(entry.getKey())) {
                    previous.remove(entry.getKey());
                    continue;
                }
                boolean craftable = RecipeCraftability.canCraft(entry.getValue().ingredients(), inventory);
                if (Boolean.valueOf(craftable).equals(previous.get(entry.getKey()))) continue;
                for (Object display : entry.getValue().displays()) updates.add(adapter.entry(display, craftable));
                sent.put(entry.getKey(), craftable);
            }
            if (!updates.isEmpty()) BukkitAdaptor.adapt(player).sendPacket(adapter.packet.newInstance(updates, false), false);
            // Only cache successfully sent states, so a failed send can be retried.
            previous.putAll(sent);
        } catch (ReflectiveOperationException | RuntimeException error) { warn(error); }
    }

    private static void add(List<RecipeCraftability.Stock<UniqueIdItem>> inventory, ItemStack stack, NativeBook adapter, boolean protect)
            throws ReflectiveOperationException {
        if (stack == null || stack.getType().isAir() || protect && !adapter.usable(stack)) return;
        inventory.add(new RecipeCraftability.Stock<>(UniqueIdItem.of(BukkitItemManager.instance().wrap(stack)), stack.getAmount()));
    }

    private void warn(Exception error) {
        if (warned) return;
        warned = true;
        plugin.getLogger().log(java.util.logging.Level.WARNING, "Could not synchronize custom recipe craftability", error);
    }

    /** Version-sensitive packet construction stays here; original display/group/category are retained. */
    private static final class NativeBook {
        final Constructor<?> packet;
        final Constructor<?> entry;
        final Method usable;

        NativeBook(ClassLoader loader) throws ReflectiveOperationException {
            Class<?> type = Class.forName("net.minecraft.network.protocol.game.ClientboundRecipeBookAddPacket", true, loader);
            packet = type.getConstructor(List.class, boolean.class);
            Class<?> entryType = Class.forName(type.getName() + "$Entry", true, loader);
            Constructor<?> selected = null;
            for (Constructor<?> constructor : entryType.getConstructors()) {
                if (constructor.getParameterCount() == 2) { selected = constructor; break; }
            }
            if (selected == null) throw new NoSuchMethodException("Recipe book entry constructor");
            entry = selected;
            usable = Class.forName("net.minecraft.world.entity.player.Inventory", true, loader).getMethod(
                    "isUsableForCrafting", Class.forName("net.minecraft.world.item.ItemStack", true, loader));
        }

        NamespacedKey key(Object resourceKey) throws ReflectiveOperationException {
            Method location;
            try { location = resourceKey.getClass().getMethod("identifier"); }
            catch (NoSuchMethodException older) { location = resourceKey.getClass().getMethod("location"); }
            return NamespacedKey.fromString(location.invoke(resourceKey).toString());
        }

        boolean usable(ItemStack stack) throws ReflectiveOperationException {
            return Boolean.TRUE.equals(usable.invoke(null, ItemStackUtils.unwrap(stack)));
        }

        Object entry(Object display, boolean craftable) throws ReflectiveOperationException {
            RecordComponent[] components = display.getClass().getRecordComponents();
            Object[] values = new Object[components.length];
            Class<?>[] types = new Class<?>[components.length];
            for (int index = 0; index < components.length; index++) {
                types[index] = components[index].getType();
                values[index] = components[index].getName().equals("craftingRequirements")
                        ? craftable ? Optional.of(List.of()) : Optional.empty()
                        : components[index].getAccessor().invoke(display);
            }
            Object accurate = display.getClass().getConstructor(types).newInstance(values);
            return entry.newInstance(accurate, (byte) 0);
        }
    }
}
