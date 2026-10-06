package net.cinchtail.cinchsmissingblocks.cmb.scheduler;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/**
 * Folia-safe scheduling, with no reflection.
 *
 * <p>Paper's API exposes Folia's schedulers directly
 * ({@code io.papermc.paper.threadedregions.scheduler.*}) and Paper implements them, so
 * calling them unconditionally is correct on both. That is better than the usual
 * reflection dance: no {@code getMethod} probing on a hot path, no method-signature
 * guessing across Folia versions, and a compile error rather than a runtime failure if a
 * signature ever changes.
 *
 * <p>The distinction that matters: on Folia there is no single main thread. "Global" is the
 * server-wide region that owns global state - plugin lifecycle, console commands, world
 * data - and it is the right target for this plugin's work. Anything touching an entity or
 * a position must go through that entity's or that location's scheduler instead, because
 * running it elsewhere is what produces Folia's region-access violations.
 */
public final class Schedulers {

    private Schedulers() {}

    /** Runs on the global region thread: plugin state and console commands. */
    public static ScheduledTask global(Plugin plugin, Runnable task) {
        return Bukkit.getGlobalRegionScheduler().run(plugin, ignored -> task.run());
    }

    /** Runs on the global region thread after {@code delayTicks}. */
    public static ScheduledTask globalLater(Plugin plugin, Runnable task, long delayTicks) {
        return Bukkit.getGlobalRegionScheduler()
                .runDelayed(plugin, ignored -> task.run(), Math.max(1L, delayTicks));
    }

    /**
     * Runs on the global region thread every {@code periodTicks}.
     *
     * @param retired run instead of {@code task} if the plugin is disabled mid-flight
     */
    public static ScheduledTask globalRepeating(
            Plugin plugin, Runnable task, Runnable retired, long delayTicks, long periodTicks) {
        long delay = Math.max(1L, delayTicks);
        long period = Math.max(1L, periodTicks);
        return Bukkit.getGlobalRegionScheduler()
                .runAtFixedRate(
                        plugin,
                        ignored -> {
                            if (!plugin.isEnabled()) {
                                retired.run();
                                return;
                            }
                            task.run();
                        },
                        delay,
                        period);
    }

    /**
     * Runs against a specific entity's region, which is the only safe way to touch one.
     *
     * @param retired run instead of {@code task} when the entity is gone or unloaded
     */
    public static ScheduledTask entity(Plugin plugin, Entity entity, Runnable task, Runnable retired) {
        return entity.getScheduler()
                .run(plugin, ignored -> task.run(), retired != null ? retired : () -> {});
    }

    /** Runs against the region owning a location, for block or world access. */
    public static void atLocation(Plugin plugin, Location location, Runnable task) {
        Bukkit.getRegionScheduler().execute(plugin, location, task);
    }

    /** Runs off-thread, for IO and other work that must not block a region. */
    public static ScheduledTask async(Plugin plugin, Runnable task) {
        return Bukkit.getAsyncScheduler()
                .runNow(plugin, ignored -> task.run());
    }
}