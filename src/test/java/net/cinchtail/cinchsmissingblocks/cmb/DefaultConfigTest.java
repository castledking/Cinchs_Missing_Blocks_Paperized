package net.cinchtail.cinchsmissingblocks.cmb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;
import net.cinchtail.cinchsmissingblocks.cmb.config.CmbConfig;
import net.cinchtail.cinchsmissingblocks.cmb.config.ConfigLoader;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class DefaultConfigTest {
    @Test
    void bundledConfigAndFallbackDefaultsAgreeOnContent() {
        YamlConfiguration file = YamlConfiguration.loadConfiguration(new InputStreamReader(
                Objects.requireNonNull(getClass().getResourceAsStream("/config.yml")), StandardCharsets.UTF_8));
        CmbConfig defaults = ConfigLoader.defaults();
        assertTrue(defaults.cheaperNetherBrickRecipe());
        assertEquals(defaults.cheaperNetherBrickRecipe(), file.getBoolean("cheaper-nether-brick-recipe"));
        assertFalse(defaults.features().disableTerracotta());
        assertFalse(defaults.features().disableConcrete());
        assertTrue(defaults.features().furnitureFallback());
        assertFalse(defaults.features().verticalSlabs().enabled());
        assertFalse(defaults.features().horizontalStairs().enabled());
        assertEquals(defaults.features().verticalSlabs().cmb(), file.getBoolean("features.vertical-slabs.cmb"));
        assertEquals(defaults.features().verticalSlabs().vanilla(), file.getBoolean("features.vertical-slabs.vanilla"));
        assertEquals(defaults.features().horizontalStairs().cmb(), file.getBoolean("features.horizontal-stairs.cmb"));
        assertEquals(defaults.features().horizontalStairs().vanilla(), file.getBoolean("features.horizontal-stairs.vanilla"));
        assertEquals(defaults.features().disableTerracotta(), file.getBoolean("features.disable-terracotta"));
        assertEquals(defaults.features().disableConcrete(), file.getBoolean("features.disable-concrete"));
        assertEquals(defaults.features().doubles(), file.getString("features.vertical-slabs.doubles"));
        assertEquals(defaults.disabledBlocks().blocks(), Set.copyOf(file.getStringList("disabled-blocks")));
        assertFalse(defaults.content().wart().fortress());
        assertFalse(defaults.content().wart().biomeEdges());
        assertFalse(defaults.content().wart().nyliumPlanting());
        assertEquals(defaults.content().wart().fortress(), file.getBoolean("content.warped-nether-wart.fortress"));
        assertEquals(defaults.content().wart().biomeEdges(), file.getBoolean("content.warped-nether-wart.biome-edges"));
        assertEquals(defaults.content().wart().nyliumPlanting(), file.getBoolean("content.warped-nether-wart.nylium-planting"));
    }
}
