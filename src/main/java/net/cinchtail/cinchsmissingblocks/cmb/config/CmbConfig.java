package net.cinchtail.cinchsmissingblocks.cmb.config;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The parsed contents of {@code plugins/CMB/config.yml}.
 *
 * <p>Deliberately split into two halves, because they are changed at different times and
 * a server owner needs to know which is which:
 *
 * <dl>
 *   <dt>pack-shaping</dt>
 *   <dd>{@link Content}, {@link DisabledBlocks}. These
 *       decide what the pack contains, so changing one changes the carrier allocation
 *       and only takes effect when the pack is regenerated. The plugin reads them to
 *       report what it expects, never to rewrite the pack.
 *   <dt>runtime</dt>
 *   <dd>{@link Features}, {@link ResourcePack}. Applied by {@code /cmb reload all} with
 *       no rebuild.
 * </dl>
 *
 * @param features runtime feature toggles
 * @param resourcePack who distributes the pack
 * @param content pack content toggles
 * @param compatibility what to do with content the server cannot represent
 * @param craftEngine state budget, mirrored from the generator so the two can be compared
 * @param disabledBlocks blocks the server refuses to serve regardless of anything else
 * @param tools /cmb kill and /cmb glow
 * @param updateChecker ask Modrinth for a newer release and tell admins
 */
public record CmbConfig(
        Features features,
        ResourcePack resourcePack,
        Content content,
        Compatibility compatibility,
        CraftEngineSettings craftEngine,
        DisabledBlocks disabledBlocks,
        Tools tools,
        boolean updateChecker
) {

    /**
     * /cmb kill and /cmb glow.
     *
     * @param defaultRadius blocks around the player when a command gives no radius
     * @param maxRadius the largest radius either command accepts
     * @param colors outline colour per category, as 0xRRGGBB, keyed by {@code PieceCategory.key()}
     * @param outlineBlock the block each outline is drawn with, as block data
     *     ({@code minecraft:white_stained_glass}), or {@link #OUTLINE_ONLY} for the glow
     *     alone; checked when the config loads
     */
    public record Tools(int defaultRadius, int maxRadius, java.util.Map<String, Integer> colors,
                        String outlineBlock) {

        /** No block: the outline is drawn with CMB's invisible cube, so only its glow shows. */
        public static final String OUTLINE_ONLY = "none";
        public static final String DEFAULT_OUTLINE_BLOCK = OUTLINE_ONLY;
    }

    /**
     * Vertical slabs: the enabled slab or stair stood on edge.
     *
     * <p>CraftEngine furniture rather than blocks, so no CraftEngine internal state is
     * spent on any of it. That is what makes this feature independent of the carrier
     * budget, and therefore independent of how much capacity the server has left.
     *
     * <p>There is no separate master switch: the feature is on if either source is,
     * which keeps "off" to the single unambiguous spelling of turning both off.
     *
     * @param cmb build one per material from the CMB slabs and stairs that received a carrier
     * @param vanilla build one per vanilla slab, which spends no carrier at all
     * @param disabled material names to withhold; wins over both {@link #cmb} and {@link #vanilla}
     */
    public record VerticalSlabs(boolean cmb, boolean vanilla, Set<String> disabled) {

        /** Whether either source is on. */
        public boolean enabled() {
            return cmb || vanilla;
        }

        public boolean isDisabled(String material) {
            return disabled.contains(material.toLowerCase(Locale.ROOT));
        }

        /**
         * Whether a material survives the toggles.
         *
         * @param material the material name, without a {@code _vertical} suffix
         * @return true when a vertical slab should be emitted for it
         */
        public boolean allows(String material) {
            return enabled() && !isDisabled(material);
        }
    }

    /**
     * @param verticalSlabs vertical slab sources and withheld materials
     * @param horizontalStairs the same, for horizontal stairs
     * @param doubles what two slabs in one cell become: {@code block} or {@code furniture}
     * @param furnitureFallback serve CMB stairs, slabs, walls, fences and panes as furniture
     * @param disableTerracotta withhold the terracotta stairs, slabs and walls
     * @param disableConcrete withhold the concrete stairs, slabs and walls
     * @param protectFurnitureCells refuse blocks placed into a furniture piece's cell
     */
    public record Features(VerticalSlabs verticalSlabs, VerticalSlabs horizontalStairs,
                           String doubles, boolean furnitureFallback,
                           boolean disableTerracotta, boolean disableConcrete,
                           boolean protectFurnitureCells) {}

    /**
     * @param delivery who sends the pack; exactly one owner, since two means competing packs
     */
    public record ResourcePack(Delivery delivery, boolean install) {
        public enum Delivery {
            CRAFTENGINE,
            RSPM;

            static Delivery parse(String raw) {
                return "rspm".equalsIgnoreCase(raw) ? RSPM : CRAFTENGINE;
            }
        }
    }

    /** @param wart how the warped nether wart can be found */
    public record Content(Wart wart) {}

    /**
     * The three ways to find warped nether wart, each with its own switch.
     *
     * @param fortress turn fortress nether wart patches warped, on a chunk's first load
     * @param fortressChance chance per patch, 0-1
     * @param biomeEdges seed soul sand + wart specks where soul sand valley meets a forest
     * @param biomeEdgeChance chance per edge spot, 0-1
     * @param nyliumPlanting nether wart planted on warped nylium grows warped
     */
    public record Wart(boolean fortress, double fortressChance, boolean biomeEdges,
                       double biomeEdgeChance, boolean nyliumPlanting) {}

    /**
     * @param unsupportedContent {@code disable} to skip unrepresentable content,
     *     {@code fail} to refuse the reload and keep the previous pack
     */
    public record Compatibility(Policy unsupportedContent) {
        public enum Policy {
            DISABLE,
            FAIL;

            static Policy parse(String raw) {
                return "fail".equalsIgnoreCase(raw) ? FAIL : DISABLE;
            }
        }

        public boolean failsOnUnsupported() {
            return unsupportedContent == Policy.FAIL;
        }
    }

    public record CraftEngineSettings(int maxInternalStates, int reservedStates) {

        /** States available to the pack after the reserve. */
        public int usable() {
            return Math.max(0, maxInternalStates - reservedStates);
        }
    }

    /**
     * Blocks the server refuses to serve, named without their namespace.
     *
     * @param blocks ids such as {@code calcite_brick_stairs}
     */
    public record DisabledBlocks(Set<String> blocks) {

        public boolean isDisabled(String blockId) {
            String bare = blockId.contains(":")
                    ? blockId.substring(blockId.indexOf(':') + 1)
                    : blockId;
            return blocks.contains(bare);
        }
    }
}