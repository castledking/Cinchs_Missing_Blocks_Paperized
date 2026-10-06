package net.cinchtail.cinchsmissingblocks.cmb;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.api.event.CraftEngineReloadEvent;
import net.momirealms.craftengine.bukkit.block.BukkitBlockManager;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.cinchtail.cinchsmissingblocks.cmb.scheduler.Schedulers;

/**
 * Diagnostic only: at what lifecycle point does {@code CraftEngineBlocks.byId} resolve?
 *
 * <p>Three triggers were guessed for layer 1a and all three saw {@code null}. Rather than
 * guess a fourth, this records {@code byId} at every lifecycle point we can observe, then
 * polls once per tick from {@code ServerLoadEvent + 1} until the first success. It
 * answers one question and does nothing else: it never runs the matrix.
 *
 * <p>Why polling is legitimate here when it would not be as a trigger: CraftEngine fills
 * its block registry from an async future chain during its delayed enable, and fires
 * {@code CraftEngineReloadEvent} through {@code runDelayed}. Neither is ordered against
 * {@code ServerLoadEvent}, so the boundary has to be measured before it can be relied on.
 *
 * <p>Two blocks are probed on purpose. The first run probed only calcite_stairs and it
 * never resolved, with the registry already holding 309 blocks: calcite_stairs is one of
 * the nine deferred stairs and is not in the pack at all. Watching a deferred block
 * next to a served one keeps "absent" from ever being read as "too early" again.
 *
 * <p>Every line is prefixed {@code PROBE} so a boot log can be grepped for the result.
 * Enabled by {@code -Dcmb.debug=true}.
 */
public final class RegistryTimingProbe implements Listener {

    /** A stair the generator serves, and one it defers on carrier capacity. */
    static final List<String> BLOCKS = List.of(
            "cinchsmissingblocks:polished_calcite_stairs",
            "cinchsmissingblocks:calcite_stairs");

    /** Give up after a minute of ticks; an unresolved block is then a real failure. */
    private static final int MAX_TICKS = 20 * 60;

    private final CmbPlugin plugin;
    private int serverLoadTick = -1;
    private int reloadTick = -1;
    private final Map<String, Integer> resolvedAt = new LinkedHashMap<>();
    private ScheduledTask poll;

    public RegistryTimingProbe(CmbPlugin plugin) {
        this.plugin = plugin;
    }

    /** Called from onEnable, so the first observation predates every event. */
    public void start() {
        observe("onEnable");
    }

    @EventHandler
    public void onServerLoad(ServerLoadEvent event) {
        serverLoadTick = Bukkit.getCurrentTick();
        observe("ServerLoadEvent(" + event.getType() + ")");
        if (poll != null) {
            return;
        }
        // Folia-safe: the global region thread, with a retired callback so a disabled
        // plugin stops polling instead of throwing on every tick.
        poll = Schedulers.globalRepeating(plugin, this::tick, () -> {}, 1L, 1L);
    }

    @EventHandler
    public void onCraftEngineReload(CraftEngineReloadEvent event) {
        reloadTick = Bukkit.getCurrentTick();
        observe("CraftEngineReloadEvent");
    }

    private void tick() {
        int now = Bukkit.getCurrentTick();
        int elapsed = now - serverLoadTick;
        for (String block : BLOCKS) {
            if (!resolvedAt.containsKey(block) && resolves(block)) {
                resolvedAt.put(block, now);
                log("FIRST RESOLVED " + block + "  tick=" + now
                        + "  serverLoad+" + elapsed
                        + "  craftEngineReload=" + (reloadTick < 0
                                ? "not yet fired"
                                : "tick " + reloadTick + " (serverLoad+"
                                        + (reloadTick - serverLoadTick) + ")")
                        + "  loadedBlocks=" + loadedBlocks());
            }
        }
        if (resolvedAt.size() == BLOCKS.size()) {
            poll.cancel();
        } else if (elapsed >= MAX_TICKS) {
            for (String block : BLOCKS) {
                if (!resolvedAt.containsKey(block)) {
                    log("NEVER RESOLVED " + block + " within " + MAX_TICKS
                            + " ticks of ServerLoadEvent  craftEngineReload="
                            + (reloadTick < 0 ? "not fired" : "tick " + reloadTick)
                            + "  loadedBlocks=" + loadedBlocks());
                }
            }
            poll.cancel();
        }
    }

    private void observe(String point) {
        StringBuilder byId = new StringBuilder();
        for (String block : BLOCKS) {
            byId.append("  ").append(block).append('=')
                    .append(resolves(block) ? "resolved" : "null");
        }
        log(point + "  tick=" + Bukkit.getCurrentTick()
                + "  thread=" + Thread.currentThread().getName()
                + "  loadedBlocks=" + loadedBlocks() + byId);
    }

    private static boolean resolves(String block) {
        try {
            return CraftEngineBlocks.byId(Key.of(block)) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Registry size, so "registry empty" and "registry populated without this block"
     * read differently. The second would mean the block failed to load, not a timing
     * problem.
     */
    private static String loadedBlocks() {
        try {
            return String.valueOf(BukkitBlockManager.instance().loadedBlocks().size());
        } catch (Throwable t) {
            return "unavailable (" + t.getClass().getSimpleName() + ")";
        }
    }

    private void log(String message) {
        plugin.getLogger().info("PROBE " + message);
    }
}
