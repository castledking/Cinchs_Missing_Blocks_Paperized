package net.cinchtail.cinchsmissingblocks.cmb;

import java.util.Map;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.api.event.CraftEngineReloadEvent;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.plugin.config.Config;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

/**
 * CraftEngine's custom block states, as CraftEngine has them: how many CMB's blocks take,
 * how many every pack's take, and the limit, {@code block.serverside-blocks} in
 * CraftEngine's config.yml.
 *
 * <p>The startup header used to print config.yml's craftengine.max-internal-states less
 * reserved-states - a generator setting, the same every boot whatever was loaded, and not
 * CraftEngine's limit either. The real count only exists once CraftEngine has loaded the
 * packs, after the header, so it is logged then, on every load.
 */
record StateUsage(int cmb, int all, int limit) {

    static StateUsage now() {
        int cmb = 0;
        int all = 0;
        for (Map.Entry<Key, BlockDefinition> block : CraftEngineBlocks.loadedBlocks().entrySet()) {
            int states = block.getValue().variantProvider().states().size();
            all += states;
            if (VerticalSlabListener.NAMESPACE.equals(block.getKey().namespace())) {
                cmb += states;
            }
        }
        return new StateUsage(cmb, all, Config.serverSideBlocks());
    }

    static final class Report implements Listener {

        private final Plugin plugin;

        Report(Plugin plugin) {
            this.plugin = plugin;
        }

        @EventHandler
        public void onLoad(CraftEngineReloadEvent event) {
            StateUsage usage = now();
            String line = "CraftEngine block states: CMB uses " + usage.cmb() + ", all packs "
                    + usage.all() + ", of " + usage.limit() + " (block.serverside-blocks)";
            if (usage.all() > usage.limit()) {
                plugin.getLogger().warning(line + " - over the limit: raise block.serverside-blocks"
                        + " in CraftEngine's config.yml, or turn blocks off in CMB's");
            } else {
                plugin.getLogger().info(line);
            }
        }
    }
}
