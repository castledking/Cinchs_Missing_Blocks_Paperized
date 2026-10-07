package net.cinchtail.cinchsmissingblocks.cmb.pack;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void anUnknownSwitchKeepsTheId() {
        assertTrue(PackInstaller.on("from-a-newer-build", DEFAULTS, withVanillaConcrete(false), null));
    }
}
