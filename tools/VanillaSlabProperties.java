import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;

/**
 * Prints what no vanilla data file records about each slab: hardness, blast
 * resistance, sound group and whether it needs the correct tool to drop. Vanilla sets
 * these in code, so the only faithful source is the server itself.
 *
 * <p>Run by tools/vanilla_slabs.py against a Paper server's own jar and libraries;
 * writes JSON to the path given as the only argument.
 */
public final class VanillaSlabProperties {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (var block : BuiltInRegistries.BLOCK) {
            String id = BuiltInRegistries.BLOCK.getKey(block).getPath();
            if (!id.endsWith("_slab")) {
                continue;
            }
            var state = block.defaultBlockState();
            String sound = BuiltInRegistries.SOUND_EVENT.getKey(state.getSoundType().getBreakSound()).getPath();
            out.append(first ? "" : ",").append('"').append(id).append("\":{")
                    .append("\"hardness\":").append(state.getDestroySpeed(null, null))
                    .append(",\"resistance\":").append(block.getExplosionResistance())
                    .append(",\"sound\":\"").append(sound.replaceFirst("^block\\.", "").replaceFirst("\\.break$", "")).append('"')
                    .append(",\"requires_tool\":").append(state.requiresCorrectToolForDrops())
                    .append('}');
            first = false;
        }
        // Paper wraps System.out in its logger, so the result goes to a file.
        Files.writeString(Path.of(args[0]), out.append('}'));
    }
}
