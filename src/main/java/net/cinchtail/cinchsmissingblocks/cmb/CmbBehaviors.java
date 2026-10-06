package net.cinchtail.cinchsmissingblocks.cmb;

import java.util.List;
import net.cinchtail.cinchsmissingblocks.cmb.block.SlabBehavior;
import net.cinchtail.cinchsmissingblocks.cmb.block.StairsBehavior;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorType;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviors;
import net.momirealms.craftengine.core.util.Key;

/**
 * Registration of CraftEngine block behaviours owned by this plugin.
 *
 * <p>CraftEngine resolves the {@code type} of a {@code behavior:} entry with
 * {@code Key.ce(type)}, which keeps an explicit namespace, so {@code cmb:stairs}
 * in a pack resolves to exactly {@code Key.of("cmb:stairs")} here. Every id in this
 * class is therefore namespaced, and {@link #assertNamespaced} enforces that: a bare
 * id would be looked up under the {@code craftengine:} namespace and silently never
 * match.
 *
 * <p>This is called from {@link CmbPlugin#onLoad()}. CraftEngine registers its own
 * behaviour types in its {@code onLoad()} and only parses block configuration in the
 * enable phase, so anything registered here is in place before it is needed.
 */
public final class CmbBehaviors {

    /** Namespace owned by this plugin. Every behaviour id must carry it. */
    public static final String NAMESPACE = "cmb";

    public static final Key STAIRS = key("stairs");
    public static final Key SLAB = key("slab");
    public static final Key WALL = key("wall");
    public static final Key BUTTON = key("button");
    public static final Key PRESSURE_PLATE = key("pressure_plate");

    /**
     * The behaviours this plugin provides, in registration order.
     *
     * <p>Only {@link #STAIRS} and {@link #SLAB} have implementations so far. The rest are declared
     * because the generated pack already references them and the gap should be
     * visible rather than discovered as an unknown-type error at load.
     */
    public static final List<BlockBehaviorType<?>> ALL = List.of(
            BlockBehaviors.register(assertNamespaced(STAIRS), StairsBehavior.FACTORY),
            BlockBehaviors.register(assertNamespaced(SLAB), SlabBehavior.FACTORY)
    );

    private CmbBehaviors() {
    }

    public static Key key(String path) {
        return Key.of(NAMESPACE + ":" + path);
    }

    /**
     * Guard against a behaviour id that would resolve into CraftEngine's namespace.
     *
     * <p>Not defensive for its own sake: the failure mode is a config that loads
     * cleanly and then reports {@code resource.block.behavior.unknown_type} for every
     * block, which is annoying to trace back to a missing colon.
     */
    public static Key assertNamespaced(Key key) {
        if (!NAMESPACE.equals(key.namespace())) {
            throw new IllegalArgumentException(
                    "behaviour id must be namespaced as " + NAMESPACE + ":*, got " + key.asString());
        }
        return key;
    }
}
