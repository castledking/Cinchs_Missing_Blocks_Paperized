package net.cinchtail.cinchsmissingblocks.cmb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class TextureAssetsTest {
    @Test
    void bundledTexturesMatchCinchsPinnedSourceFiles() throws Exception {
        Path source = Path.of("texture-overrides");
        JsonObject files = JsonParser.parseString(Files.readString(source.resolve("manifest.json")))
                .getAsJsonObject().getAsJsonObject("files");
        assertTrue(files.has("assets/cinchsmissingblocks/textures/block/sandstone_brick_pillar_side.png"));
        assertTrue(files.has("assets/cinchsmissingblocks/textures/block/red_sandstone_brick_pillar_side.png"));
        for (String name : files.keySet()) {
            String expected = files.get(name).getAsString();
            assertEquals(expected, sha256(source.resolve(name)), "Upstream source: " + name);
            assertEquals(expected, sha256(Path.of("pack/resourcepack").resolve(name)),
                    "Bundled texture: " + name);
        }
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}
