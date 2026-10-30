package net.cinchtail.cinchsmissingblocks.cmb.compat;

import com.destroystokyo.paper.event.inventory.PrepareResultEvent;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.cinchtail.cinchsmissingblocks.cmb.scheduler.Schedulers;
import net.momirealms.craftengine.bukkit.item.recipe.BukkitRecipeManager;
import net.momirealms.craftengine.bukkit.util.ItemStackUtils;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.StonecutterInventory;
import org.bukkit.plugin.Plugin;

/** Keeps a stonecutter's client choices and server selection indices in the same order. */
public final class StonecutterRecipeFilter implements Listener {
    private final Plugin plugin;
    private final Map<String, Set<String>> ingredients;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private NativeStonecutter nativeMenu;
    private boolean unsupported;
    private boolean warned;

    private static final class Session {
        final Object menu;
        String input;
        Object filtered;
        Session(Object menu) { this.menu = menu; }
    }

    public StonecutterRecipeFilter(Plugin plugin, Map<String, Set<String>> ingredients) {
        this.plugin = plugin;
        this.ingredients = ingredients;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void open(InventoryOpenEvent event) {
        if (event.getView().getType() != InventoryType.STONECUTTER
                || !(event.getPlayer() instanceof Player player) || unsupported) return;
        try {
            if (nativeMenu == null) nativeMenu = new NativeStonecutter();
            Session session = new Session(event.getView().getClass().getMethod("getHandle").invoke(event.getView()));
            sessions.put(player.getUniqueId(), session);
            Schedulers.entity(plugin, player, () -> {
                if (sessions.get(player.getUniqueId()) == session) refresh(player, session);
            }, () -> sessions.remove(player.getUniqueId(), session));
        } catch (ClassNotFoundException oldProtocol) {
            unsupported = true; // Before 1.21.2 the vanilla protocol has no SingleInputSet.
        } catch (ReflectiveOperationException | RuntimeException error) { warn(error); }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void prepare(PrepareResultEvent event) {
        if (!(event.getInventory() instanceof StonecutterInventory) || !(event.getView().getPlayer() instanceof Player player)) return;
        Session session = sessions.get(player.getUniqueId());
        if (session != null && refresh(player, session)) event.setResult(null);
    }

    private boolean refresh(Player player, Session session) {
        if (!(player.getOpenInventory().getTopInventory() instanceof StonecutterInventory inventory)) return false;
        try {
            ItemStack input = inventory.getInputItem();
            String id = input == null || input.getType().isAir() ? "" : CraftEngineItems.isCustomItem(input)
                    ? CraftEngineItems.getCustomItemId(input).asString() : input.getType().getKey().toString();
            boolean changed = !id.equals(session.input);
            if (!changed && nativeMenu.visible.get(session.menu) == session.filtered) return false;
            Object manager = BukkitRecipeManager.minecraftRecipeManager();
            Object all = nativeMenu.all.invoke(manager);
            List<Object> filtered = new ArrayList<>();
            for (Object entry : (List<?>) nativeMenu.entries.invoke(all)) {
                String recipe = nativeMenu.key(entry);
                Set<String> needed = ingredients.get(recipe);
                if (needed == null || needed.contains(id)) filtered.add(entry);
            }
            Object choices = nativeMenu.set.newInstance(filtered);
            Object stack = ItemStackUtils.unwrap(input == null ? new ItemStack(Material.AIR) : input);
            session.filtered = nativeMenu.select.invoke(choices, stack);
            nativeMenu.visible.set(session.menu, session.filtered);
            if (changed) {
                // Reset both sides together: selecting button zero must mean the same
                // recipe on the client and server, including after replacing the input.
                Object selected = nativeMenu.selected.get(session.menu);
                nativeMenu.setSelected.invoke(selected, -1);
                inventory.setResult(null);
                Object packet = nativeMenu.packet.newInstance(nativeMenu.properties.invoke(manager), choices);
                var user = BukkitAdaptor.adapt(player);
                user.sendPacket(packet, false);
                int container = nativeMenu.container.getInt(session.menu);
                int state = (int) nativeMenu.state.invoke(session.menu);
                user.sendPacket(nativeMenu.slot.newInstance(container, state, 0,
                        ItemStackUtils.unwrap(new ItemStack(Material.AIR))), false);
                user.sendPacket(nativeMenu.slot.newInstance(container, state, 0, stack), false);
                session.input = id;
            }
            return changed;
        } catch (ReflectiveOperationException | RuntimeException error) { warn(error); }
        return false;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void close(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player) || event.getView().getType() != InventoryType.STONECUTTER) return;
        Session active = sessions.get(player.getUniqueId());
        if (active == null) return;
        try {
            // Paper may announce the new menu before closing the previous one.
            // A close for that previous menu must not retire the new session.
            Object menu = event.getView().getClass().getMethod("getHandle").invoke(event.getView());
            if (active.menu == menu) restore(player);
        } catch (ReflectiveOperationException | RuntimeException error) { warn(error); }
    }

    @EventHandler public void quit(PlayerQuitEvent event) { sessions.remove(event.getPlayer().getUniqueId()); }

    private void restore(Player player) {
        if (sessions.remove(player.getUniqueId()) == null || nativeMenu == null || !player.isOnline()) return;
        try {
            Object manager = BukkitRecipeManager.minecraftRecipeManager();
            BukkitAdaptor.adapt(player).sendPacket(nativeMenu.packet.newInstance(
                    nativeMenu.properties.invoke(manager), nativeMenu.all.invoke(manager)), false);
        } catch (ReflectiveOperationException | RuntimeException error) { warn(error); }
    }

    private void warn(Exception error) {
        if (warned) return;
        warned = true;
        plugin.getLogger().log(java.util.logging.Level.WARNING, "Could not filter custom stonecutter choices", error);
    }

    private static final class NativeStonecutter {
        final Constructor<?> set, packet, slot;
        final Method all, entries, select, properties, state, setSelected;
        final Field visible, selected, container;

        NativeStonecutter() throws ReflectiveOperationException {
            ClassLoader loader = BukkitRecipeManager.minecraftRecipeManager().getClass().getClassLoader();
            Class<?> setType = Class.forName("net.minecraft.world.item.crafting.SelectableRecipe$SingleInputSet", true, loader);
            Class<?> item = Class.forName("net.minecraft.world.item.ItemStack", true, loader);
            Class<?> menu = Class.forName("net.minecraft.world.inventory.StonecutterMenu", true, loader);
            Class<?> manager = BukkitRecipeManager.minecraftRecipeManager().getClass();
            set = setType.getConstructor(List.class);
            entries = setType.getMethod("entries");
            select = setType.getMethod("selectByInput", item);
            all = manager.getMethod("stonecutterRecipes");
            properties = manager.getMethod("getSynchronizedItemProperties");
            packet = Class.forName("net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket", true, loader)
                    .getConstructor(Map.class, setType);
            slot = Class.forName("net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket", true, loader)
                    .getConstructor(int.class, int.class, int.class, item);
            visible = menu.getDeclaredField("recipesForInput"); visible.setAccessible(true);
            selected = menu.getDeclaredField("selectedRecipeIndex"); selected.setAccessible(true);
            setSelected = Class.forName("net.minecraft.world.inventory.DataSlot", true, loader).getMethod("set", int.class);
            container = menu.getField("containerId");
            state = menu.getMethod("getStateId");
        }

        String key(Object entry) throws ReflectiveOperationException {
            Object choice = entry.getClass().getMethod("recipe").invoke(entry);
            Optional<?> holder = (Optional<?>) choice.getClass().getMethod("recipe").invoke(choice);
            if (holder.isEmpty()) return "";
            Object key = holder.get().getClass().getMethod("id").invoke(holder.get());
            Method location;
            try { location = key.getClass().getMethod("identifier"); }
            catch (NoSuchMethodException oldName) { location = key.getClass().getMethod("location"); }
            return location.invoke(key).toString();
        }
    }
}
