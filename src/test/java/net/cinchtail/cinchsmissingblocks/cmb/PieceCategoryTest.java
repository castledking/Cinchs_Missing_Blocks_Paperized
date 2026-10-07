package net.cinchtail.cinchsmissingblocks.cmb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Ids from the committed pack, one per category, and the command tags. */
class PieceCategoryTest {

    @Test
    void blockIds() {
        assertEquals(PieceCategory.BLOCKS, PieceCategory.of("andesite_bricks", true));
        assertEquals(PieceCategory.PILLARS, PieceCategory.of("andesite_brick_pillar", true));
        // A doubled slab is counted with its slab, as a block too.
        assertEquals(PieceCategory.SLABS, PieceCategory.of("andesite_brick_slab_double", true));
        assertEquals(PieceCategory.VERTICAL_SLABS, PieceCategory.of("andesite_brick_vertical_double", true));
    }

    @Test
    void furnitureIds() {
        assertEquals(PieceCategory.STAIRS, PieceCategory.of("andesite_brick_stairs", false));
        assertEquals(PieceCategory.HORIZONTAL_STAIRS, PieceCategory.of("andesite_brick_horizontal_stairs", false));
        assertEquals(PieceCategory.SLABS, PieceCategory.of("black_concrete_slab", false));
        assertEquals(PieceCategory.VERTICAL_SLABS, PieceCategory.of("andesite_brick_vertical", false));
        assertEquals(PieceCategory.WALLS, PieceCategory.of("andesite_brick_wall", false));
        assertEquals(PieceCategory.FENCES, PieceCategory.of("blue_nether_brick_fence", false));
        assertEquals(PieceCategory.PANES, PieceCategory.of("tinted_glass_pane", false));
    }

    @Test
    void theCropIsNotABuildingPiece() {
        assertNull(PieceCategory.of("warped_nether_wart", false));
        assertNull(PieceCategory.of("something_else", false));
    }

    @Test
    void tags() {
        assertEquals(PieceCategory.WALLS, PieceCategory.parse("#walls"));
        assertEquals(PieceCategory.WALLS, PieceCategory.parse("WALLS"));
        assertEquals(PieceCategory.PANES, PieceCategory.parse("#glass_panes"));
        assertEquals(PieceCategory.VERTICAL_SLABS, PieceCategory.parse("#vertical-slabs"));
        assertNull(PieceCategory.parse("#all"));
        assertTrue(PieceCategory.isAll("#all"));
        assertNull(PieceCategory.parse("#nope"));
    }
}
