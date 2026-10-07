package net.cinchtail.cinchsmissingblocks.cmb;

import java.util.Locale;

/**
 * What a CMB piece is, for /cmb kill and /cmb glow: {@code #blocks}, {@code #walls}, ...
 *
 * <p>Read off the piece's id, the way {@link VerticalSlabListener#kindOf} reads furniture
 * kinds. A doubled slab is counted with its slab ({@code _slab_double} with the slabs,
 * {@code _vertical_double} with the vertical slabs) whether it was placed as a block or
 * as furniture, so {@code #slabs} finds every slab whichever form it took. The warped
 * nether wart crop is {@code #crops}.
 */
public enum PieceCategory {
    BLOCKS(0x55FF55),
    PILLARS(0x5555FF),
    STAIRS(0xFFFF55),
    SLABS(0xFFAA00),
    WALLS(0xFF5555),
    FENCES(0xAA0000),
    PANES(0xFF88CC),
    VERTICAL_SLABS(0x9933FF),
    HORIZONTAL_STAIRS(0xFF00FF),
    CROPS(0x55FFFF);

    /** The outline colour when config.yml doesn't set one. */
    public final int defaultColor;

    PieceCategory(int defaultColor) {
        this.defaultColor = defaultColor;
    }

    /** As written in commands and config: {@code vertical_slabs}. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The category of a CMB id's path (no namespace), or null if it has none. */
    public static PieceCategory of(String path, boolean block) {
        if (path.endsWith("_nether_wart")) {
            return CROPS;
        }
        if (path.endsWith("_vertical_double") || path.endsWith("_vertical")) {
            return VERTICAL_SLABS;
        }
        if (path.endsWith("_slab_double") || path.endsWith("_slab")) {
            return SLABS;
        }
        if (path.endsWith("_horizontal_stairs")) {
            return HORIZONTAL_STAIRS;
        }
        if (path.endsWith("_stairs")) {
            return STAIRS;
        }
        if (path.endsWith("_wall")) {
            return WALLS;
        }
        if (path.endsWith("_fence")) {
            return FENCES;
        }
        if (path.endsWith("_pane")) {
            return PANES;
        }
        if (path.endsWith("_pillar")) {
            return PILLARS;
        }
        return block ? BLOCKS : null;
    }

    /**
     * A command tag: {@code #walls}, {@code walls}, or an alias ({@code #glass_panes},
     * {@code #vslabs}, {@code #hstairs}). Null for {@code #all} and for anything unknown,
     * which the caller tells apart with {@link #isAll}.
     */
    public static PieceCategory parse(String tag) {
        String t = tag.toLowerCase(Locale.ROOT).replaceFirst("^#", "").replace('-', '_');
        return switch (t) {
            case "glass_panes" -> PANES;
            case "vslabs" -> VERTICAL_SLABS;
            case "hstairs" -> HORIZONTAL_STAIRS;
            default -> {
                for (PieceCategory c : values()) {
                    if (c.key().equals(t)) {
                        yield c;
                    }
                }
                yield null;
            }
        };
    }

    public static boolean isAll(String tag) {
        return tag.toLowerCase(Locale.ROOT).replaceFirst("^#", "").equals("all");
    }
}
