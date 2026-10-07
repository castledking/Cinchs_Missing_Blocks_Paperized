package net.cinchtail.cinchsmissingblocks.cmb.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cinchtail.cinchsmissingblocks.cmb.config.CmbConfig;
import net.cinchtail.cinchsmissingblocks.cmb.config.ConfigLoader;
import org.junit.jupiter.api.Test;

/** The installer's switches, evaluated against a stand-in for the running server. */
class PackInstallerSwitchTest {

    private static final CmbConfig DEFAULTS = ConfigLoader.defaults();

    /** A 26.x server: vanilla has concrete slabs. */
    private static PackInstaller.Server withVanillaConcrete(boolean viaBackwards) {
        return new PackInstaller.Server(viaBackwards, Set.of("black_concrete_slab")::contains);
    }

    /** A 1.21.x server: vanilla never had concrete slabs. */
    private static PackInstaller.Server withoutVanillaConcrete(boolean viaBackwards) {
        return new PackInstaller.Server(viaBackwards, name -> false);
    }

    @Test
    void cmbConcreteSlabIsKeptWhereVanillaNeverHadOne() {
        // The bug: dropped on every server without ViaBackwards, 1.21.11 included.
        assertTrue(PackInstaller.on("viabackwards", DEFAULTS, withoutVanillaConcrete(false),
                "black_concrete_slab"));
    }

    @Test
    void cmbConcreteSlabIsADuplicateWhereVanillaHasOne() {
        assertFalse(PackInstaller.on("viabackwards", DEFAULTS, withVanillaConcrete(false),
                "black_concrete_slab"));
    }

    @Test
    void viaBackwardsKeepsItEvenWhereVanillaHasOne() {
        assertTrue(PackInstaller.on("viabackwards", DEFAULTS, withVanillaConcrete(true),
                "black_concrete_slab"));
    }

    @Test
    void anIdWithNoBlockToCheckIsKept() {
        assertTrue(PackInstaller.on("viabackwards", DEFAULTS, withVanillaConcrete(false), null));
    }

    @Test
    void eitherSideOfAPipeIsEnough() {
        assertTrue(PackInstaller.on("viabackwards|furniture-fallback", DEFAULTS,
                withVanillaConcrete(false), "black_concrete_slab"));
    }

    // --- vanilla materials the server doesn't have ----------------------------------

    private static final Map<String, String> MATERIAL_BLOCKS = PackInstaller.materialBlocks("""
            recipes:
              "cinchsmissingblocks:cinnabar_brick_vertical":
                type: shaped
                ingredients: {"#": "minecraft:cinnabar_bricks"}
              "cinchsmissingblocks:black_wool_horizontal_stairs":
                type: shaped
                ingredients: {"#": "minecraft:black_wool"}
              "cinchsmissingblocks:andesite_brick_vertical":
                type: shaped
                ingredients: {"#": "cinchsmissingblocks:andesite_bricks"}
            """.getBytes(StandardCharsets.UTF_8));

    /** A 1.21.11 server: wool, no cinnabar. */
    private static final PackInstaller.Server V1_21_11 =
            new PackInstaller.Server(false, Set.of("black_wool")::contains);

    @Test
    void theMaterialIsReadOffItsRecipe() {
        assertEquals("cinnabar_bricks", MATERIAL_BLOCKS.get("cinnabar_brick"));
        assertEquals("black_wool", MATERIAL_BLOCKS.get("black_wool"));
        assertFalse(MATERIAL_BLOCKS.containsKey("andesite_brick"), "a CMB block is not a vanilla material");
    }

    @Test
    void aVanillaMaterialTheServerLacksIsDropped() {
        assertTrue(PackInstaller.vanillaMaterialMissing(
                List.of("vertical-slabs.vanilla", "vertical-slabs.material:cinnabar_brick"), MATERIAL_BLOCKS, V1_21_11));
        assertTrue(PackInstaller.vanillaMaterialMissing(
                List.of("horizontal-stairs.vanilla", "horizontal-stairs.material:cinnabar_brick"), MATERIAL_BLOCKS, V1_21_11));
    }

    @Test
    void aVanillaMaterialTheServerHasIsKept() {
        // Wool slabs are 26.x, but wool is not: the piece draws and crafts from wool.
        assertFalse(PackInstaller.vanillaMaterialMissing(
                List.of("vertical-slabs.vanilla", "vertical-slabs.material:black_wool"), MATERIAL_BLOCKS, V1_21_11));
    }

    @Test
    void aCmbMaterialOrAnUnknownOneIsKept() {
        assertFalse(PackInstaller.vanillaMaterialMissing(
                List.of("vertical-slabs.cmb", "vertical-slabs.material:cinnabar_brick"), MATERIAL_BLOCKS, V1_21_11),
                "made from a CMB block, not vanilla's");
        assertFalse(PackInstaller.vanillaMaterialMissing(
                List.of("vertical-slabs.vanilla", "vertical-slabs.material:mystery"), MATERIAL_BLOCKS, V1_21_11),
                "no recipe to read: keep rather than guess");
    }

    @Test
    void anUnknownSwitchKeepsTheId() {
        assertTrue(PackInstaller.on("from-a-newer-build", DEFAULTS, withVanillaConcrete(false), null));
    }
}
