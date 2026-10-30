package net.cinchtail.cinchsmissingblocks.cmb;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.bukkit.entity.furniture.element.ItemDisplayFurnitureElement;
import net.momirealms.craftengine.core.util.VersionHelper;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Crack displays clone each visible model part, including a wall's individual arms. */
final class FurnitureCracks {
    private record Part(ItemDisplay entity, String shape) {}
    private record Overlay(UUID target, Object snapshot, List<Part> parts, int stage) {}

    private final CmbPlugin plugin;
    private final Map<UUID, Overlay> overlays = new ConcurrentHashMap<>();
    private volatile Map<String, String> shapes = Map.of();
    private final Path file;
    private final Method itemModel;
    private final Method clientProtocol;
    private final Object viaApi;
    private long modified = Long.MIN_VALUE;

    FurnitureCracks(CmbPlugin plugin) {
        this.plugin = plugin;
        this.file = plugin.getDataFolder().toPath().getParent().resolve(
                "CraftEngine/resources/cinchsmissingblocks/intermediate/mining-models.json");
        Method method;
        try { method = ItemMeta.class.getMethod("setItemModel", NamespacedKey.class); }
        catch (NoSuchMethodException olderClientModels) { method = null; }
        this.itemModel = VersionHelper.isOrAbove1_21_4 ? method : null;
        Method protocol = null;
        Object api = null;
        var via = Bukkit.getPluginManager().getPlugin("ViaVersion");
        if (via != null) {
            try {
                ClassLoader loader = via.getClass().getClassLoader();
                api = Class.forName("com.viaversion.viaversion.api.Via", true, loader).getMethod("getAPI").invoke(null);
                protocol = Class.forName("com.viaversion.viaversion.api.ViaAPI", true, loader)
                        .getMethod("getPlayerVersion", UUID.class);
            } catch (ReflectiveOperationException | RuntimeException unavailable) {
                // Unknown translated clients must not see a floating vanilla paper
                // item when their protocol strips the custom item model component.
            }
        }
        this.clientProtocol = protocol;
        this.viaApi = api;
    }

    void update(Player player, BukkitFurniture furniture, double progress) {
        if (itemModel == null || !supportsModels(player)) return;
        reload();
        UUID id = player.getUniqueId();
        int stage = MiningProgress.stage(progress);
        Overlay old = overlays.get(id);
        if (old != null && old.target.equals(furniture.uuid()) && old.snapshot == furniture.snapshotState()) {
            if (old.stage == stage) return;
            old.parts.forEach(part -> part.entity.setItemStack(item(part.shape, stage)));
            overlays.put(id, new Overlay(old.target, old.snapshot, old.parts, stage));
            return;
        }
        clear(id);
        List<Part> parts = new ArrayList<>();
        for (var element : furniture.elements()) {
            if (!(element instanceof ItemDisplayFurnitureElement display)) continue;
            String shape = shapes.get(display.config.itemId.asString());
            if (shape == null) continue;
            var pos = display.position;
            var config = display.config;
            Location location = new Location(furniture.location().getWorld(), pos.x, pos.y, pos.z, pos.yRot, pos.xRot);
            ItemDisplay crack = location.getWorld().spawn(location, ItemDisplay.class, entity -> {
                entity.setPersistent(false);
                entity.setVisibleByDefault(false);
                entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.valueOf(config.displayContext.name()));
                entity.setTransformation(new Transformation(new Vector3f(config.translation),
                        new Quaternionf(config.rotation), new Vector3f(config.scale), new Quaternionf()));
                entity.setViewRange(.5f);
                entity.setShadowRadius(0);
                entity.setShadowStrength(0);
                entity.setItemStack(item(shape, stage));
            });
            player.showEntity(plugin, crack);
            parts.add(new Part(crack, shape));
        }
        overlays.put(id, new Overlay(furniture.uuid(), furniture.snapshotState(), parts, stage));
    }

    private boolean supportsModels(Player player) {
        if (clientProtocol == null) return Bukkit.getPluginManager().getPlugin("ViaVersion") == null;
        try { return (int) clientProtocol.invoke(viaApi, player.getUniqueId()) >= 769; }
        catch (ReflectiveOperationException | RuntimeException unavailable) { return false; }
    }

    private ItemStack item(String shape, int stage) {
        ItemStack stack = new ItemStack(Material.PAPER);
        ItemMeta meta = stack.getItemMeta();
        try { itemModel.invoke(meta, NamespacedKey.fromString("cinchsmissingblocks:mining/" + shape + "_" + stage)); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException("Could not set mining model", error); }
        stack.setItemMeta(meta);
        return stack;
    }

    void clear(UUID player) {
        Overlay overlay = overlays.remove(player);
        if (overlay != null) overlay.parts.forEach(part -> {
            if (!plugin.isEnabled()) part.entity.remove();
            else part.entity.getScheduler().run(plugin, task -> part.entity.remove(), () -> {});
        });
    }

    void clearAll() {
        List<UUID> players = new ArrayList<>(overlays.keySet());
        players.forEach(this::clear);
    }

    private synchronized void reload() {
        try {
            long stamp = Files.isRegularFile(file) ? Files.getLastModifiedTime(file).toMillis() : -1;
            if (stamp == modified) return;
            modified = stamp;
            Map<String, String> loaded = new HashMap<>();
            if (stamp >= 0) JsonParser.parseString(Files.readString(file)).getAsJsonObject().getAsJsonObject("items")
                    .entrySet().forEach(entry -> loaded.put(entry.getKey(), entry.getValue().getAsString()));
            shapes = Map.copyOf(loaded);
        } catch (IOException | RuntimeException error) {
            plugin.getLogger().warning("Could not read mining overlay models: " + error);
        }
    }
}
