import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;

/**
 * Writes the vanilla blocks that need the correct tool to drop anything, one id per
 * line. Vanilla sets this in code (requiresCorrectToolForDrops), so the only faithful
 * source is a bootstrapped server. Run by tools/vanilla_tools.py; the output path is
 * the only argument.
 */
public final class VanillaToolRequirements {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        List<String> ids = new ArrayList<>();
        for (var block : BuiltInRegistries.BLOCK) {
            if (block.defaultBlockState().requiresCorrectToolForDrops()) {
                ids.add(BuiltInRegistries.BLOCK.getKey(block).getPath());
            }
        }
        ids.sort(null);
        // Paper wraps System.out in its logger, so the result goes to a file.
        Files.writeString(Path.of(args[0]), String.join("\n", ids) + "\n");
    }
}
