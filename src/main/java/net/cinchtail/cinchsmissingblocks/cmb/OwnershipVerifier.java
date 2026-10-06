package net.cinchtail.cinchsmissingblocks.cmb;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import net.momirealms.craftengine.bukkit.api.event.CraftEngineReloadEvent;
import net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/**
 * Dumps CraftEngine's actual appearance bindings so pre-parse ownership discovery can
 * be checked for completeness.
 *
 * <p>The allocator has to decide which vanilla states are free <em>before</em> any pack
 * is parsed, because CraftEngine resolves a pack's {@code state:} while parsing it.
 * That rules out asking CraftEngine directly at the moment it matters, so ownership is
 * discovered from other packs' configuration and from CraftEngine's own persisted
 * auto_state cache.
 *
 * <p>The risk with that is silent under-coverage: a binding whose source we never
 * discovered looks identical to a clean allocation until the server logs a bind
 * failure. {@code blockOverrides()} is populated once parsing is done, so comparing it
 * against what the pre-parse sources found turns "I hope we saw everything" into a
 * measurement.
 *
 * <p>Enabled by {@code -Dcmb.debug=true}; writes
 * {@code plugins/cmb/block-overrides.json}.
 */
public final class OwnershipVerifier implements Listener {

    private final CmbPlugin plugin;

    public OwnershipVerifier(CmbPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Dumps the actual bindings once CraftEngine has parsed its packs.
     *
     * <p>Still on CraftEngine's own reload event, which is documented as the point
     * at which the block registry becomes readable.
     */
    @EventHandler
    public void onReload(CraftEngineReloadEvent event) {
        if (!plugin.debug()) {
            return;
        }
        dump();
    }

    /** Write {@code blockOverrides()} as a flat list of bound vanilla states. */
    public void dump() {
        Map<Key, Map<String, JsonElement>> overrides;
        try {
            overrides = BukkitCraftEngine.instance().blockManager().blockOverrides();
        } catch (Throwable t) {
            plugin.getLogger().warning("could not read blockOverrides(): " + t);
            return;
        }

        // Sorted so two runs are diffable, which is the whole point of the artifact.
        TreeMap<String, String> flat = new TreeMap<>();
        overrides.forEach((block, states) -> states.keySet().forEach(properties -> {
            String state = properties.isEmpty()
                    ? block.asString()
                    : block.asString() + "[" + properties + "]";
            flat.put(state, block.asString());
        }));

        JsonObject root = new JsonObject();
        root.addProperty("count", flat.size());
        JsonObject states = new JsonObject();
        flat.forEach(states::addProperty);
        root.add("states", states);

        Path out = plugin.getDataFolder().toPath().resolve("block-overrides.json");
        try {
            Files.createDirectories(out.getParent());
            Files.writeString(out, root.toString(), StandardCharsets.UTF_8);
            plugin.getLogger().info("wrote " + flat.size()
                    + " bound vanilla states to plugins/cmb/block-overrides.json");
        } catch (IOException e) {
            plugin.getLogger().warning("could not write block-overrides.json: " + e);
        }
    }

    /** Parse a previously written dump back into a state -> owning block map. */
    public static TreeMap<String, String> read(Path file) throws IOException {
        TreeMap<String, String> out = new TreeMap<>();
        if (!Files.isRegularFile(file)) {
            return out;
        }
        JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        JsonObject states = root.getAsJsonObject("states");
        for (String key : states.keySet()) {
            out.put(key, states.get(key).getAsString());
        }
        return out;
    }
}
