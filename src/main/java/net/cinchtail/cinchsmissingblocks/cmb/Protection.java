package net.cinchtail.cinchsmissingblocks.cmb;

import java.lang.reflect.Method;
import net.momirealms.craftengine.bukkit.api.event.FurnitureBreakEvent;
import net.momirealms.craftengine.bukkit.api.event.FurnitureHitEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

/**
 * Land protection for what CMB places and breaks itself: WorldGuard regions and
 * GriefPrevention claims (upstream and the GriefPrevention3D fork).
 *
 * <p>CMB places pieces through CraftEngine's API rather than as vanilla blocks, so no
 * BlockPlaceEvent reaches a protection plugin - pieces could be placed in someone
 * else's claim. Every placement the plugin makes, every bucket on a piece and every hit
 * on one asks here first. The plugins are asked directly, not through fake block
 * events, which loggers and job plugins would take for real placements.
 *
 * <p>Both hooks are optional: WorldGuard's classes are only touched when it is
 * installed, and GriefPrevention is called by reflection on the API both builds share
 * ({@code allowBuild} / {@code allowBreak}, which return null or the reason).
 */
public final class Protection implements Listener {

    private static CmbPlugin plugin;
    private static java.lang.reflect.Field griefPrevention;
    private static Method gpAllowBuild;
    private static Method gpAllowBreak;
    private static boolean worldGuard;

    public Protection(CmbPlugin owner) {
        plugin = owner;
        worldGuard = Bukkit.getPluginManager().getPlugin("WorldGuard") != null;
        Plugin gp = Bukkit.getPluginManager().getPlugin("GriefPrevention");
        if (gp == null) {
            gp = Bukkit.getPluginManager().getPlugin("GriefPrevention3D");
        }
        if (gp != null) {
            try {
                Class<?> type = gp.getClass();
                griefPrevention = type.getField("instance");
                gpAllowBuild = type.getMethod("allowBuild", Player.class, org.bukkit.Location.class, Material.class);
                gpAllowBreak = type.getMethod("allowBreak", Player.class, Block.class, org.bukkit.Location.class);
            } catch (ReflectiveOperationException e) {
                owner.getLogger().warning("GriefPrevention found but its API didn't match ("
                        + e + "); claims won't protect CMB pieces.");
                griefPrevention = null;
            }
        }
        owner.getLogger().info("Protection: WorldGuard " + (worldGuard ? "hooked" : "not installed")
                + ", GriefPrevention " + (griefPrevention != null ? "hooked (" + gp.getName() + ")" : "not installed"));
    }

    /** May the player put something into this cell (or change what is in it)? Tells them why not. */
    public static boolean canBuild(Player player, Block cell, Material material) {
        if (worldGuard && !WorldGuardHook.test(player, cell, true)) {
            deny(player, null);
            return false;
        }
        if (griefPrevention != null) {
            String reason = invoke(gpAllowBuild, player, cell.getLocation(), material);
            if (reason != null) {
                deny(player, reason);
                return false;
            }
        }
        return true;
    }

    /** May the player break what is in this cell? Tells them why not. */
    public static boolean canBreak(Player player, Block cell) {
        if (worldGuard && !WorldGuardHook.test(player, cell, false)) {
            deny(player, null);
            return false;
        }
        if (griefPrevention != null) {
            String reason = invoke(gpAllowBreak, player, cell, cell.getLocation());
            if (reason != null) {
                deny(player, reason);
                return false;
            }
        }
        return true;
    }

    private static String invoke(Method method, Object... args) {
        try {
            // Read each time: GriefPrevention only sets it when it enables.
            return (String) method.invoke(griefPrevention.get(null), args);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // A protection plugin that throws is not a reason to let the edit through.
            plugin.getLogger().warning("GriefPrevention check failed: " + e);
            return "";
        }
    }

    private static void deny(Player player, String reason) {
        if (reason == null || reason.isBlank()) {
            player.sendMessage(plugin.lang().get("protected"));
        } else {
            player.sendMessage(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                    .legacySection().deserialize(reason));
        }
    }

    /** Hitting a piece is the start of breaking it: refuse it before it counts. */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onHit(FurnitureHitEvent event) {
        VerticalSlabListener.Plate plate = VerticalSlabListener.plate(event.furniture());
        if (plate != null && !canBreak(event.player(), plate.cell())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onBreak(FurnitureBreakEvent event) {
        VerticalSlabListener.Plate plate = VerticalSlabListener.plate(event.furniture());
        if (plate != null && !canBreak(event.player(), plate.cell())) {
            event.setCancelled(true);
        }
    }

    /** Only loaded when WorldGuard is installed, so its classes are never needed otherwise. */
    private static final class WorldGuardHook {
        static boolean test(Player player, Block cell, boolean build) {
            com.sk89q.worldguard.LocalPlayer local =
                    com.sk89q.worldguard.bukkit.WorldGuardPlugin.inst().wrapPlayer(player);
            com.sk89q.worldguard.WorldGuard wg = com.sk89q.worldguard.WorldGuard.getInstance();
            if (wg.getPlatform().getSessionManager().hasBypass(local, local.getWorld())) {
                return true;
            }
            return wg.getPlatform().getRegionContainer().createQuery().testBuild(
                    com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(cell.getLocation()), local,
                    build ? com.sk89q.worldguard.protection.flags.Flags.BLOCK_PLACE
                          : com.sk89q.worldguard.protection.flags.Flags.BLOCK_BREAK);
        }
    }
}
