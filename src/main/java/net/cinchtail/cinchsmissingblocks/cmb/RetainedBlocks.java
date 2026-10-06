package net.cinchtail.cinchsmissingblocks.cmb;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;

/**
 * What the generator's capacity pass actually kept, read from the staged pack.
 *
 * <p>The generator writes {@code intermediate/content.json} (every canonical block and its
 * family) and {@code intermediate/allocation.json} (which of those got carrier states,
 * and why the rest were deferred) into the pack, and both are staged with it. A
 * diagnostic that picks its test subject from here can never quietly test a block the
 * allocator deferred - which is exactly what layer 1a did for four runs with
 * calcite_stairs.
 */
public final class RetainedBlocks {

    static final String NAMESPACE = "cinchsmissingblocks";

    private final JsonObject content;
    private final JsonObject allocation;

    private RetainedBlocks(JsonObject content, JsonObject allocation) {
        this.content = content;
        this.allocation = allocation;
    }

    /** The staged pack's intermediate directory under CraftEngine's resources. */
    static Path intermediate() {
        return Bukkit.getPluginsFolder().toPath()
                .resolve("CraftEngine/resources/" + NAMESPACE + "/intermediate");
    }

    public static RetainedBlocks load() throws IOException {
        Path dir = intermediate();
        return new RetainedBlocks(read(dir.resolve("content.json")),
                read(dir.resolve("allocation.json")));
    }

    private static JsonObject read(Path file) throws IOException {
        return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
    }

    /** Paperized blocks of {@code family} that received carriers, sorted by id. */
    public List<String> retained(String family) {
        JsonObject assigned = allocation.getAsJsonObject("assigned");
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, com.google.gson.JsonElement> entry
                : content.getAsJsonObject("blocks").entrySet()) {
            String id = entry.getKey();
            JsonObject block = entry.getValue().getAsJsonObject();
            if (id.startsWith(NAMESPACE + ":")
                    && block.has("family")
                    && family.equals(block.get("family").getAsString())
                    && assigned.has(id)) {
                out.add(id);
            }
        }
        out.sort(null);
        return out;
    }

    /** The allocator's reason for not serving {@code id}, or null if it was served. */
    public String deferralReason(String id) {
        JsonObject unsupported = allocation.getAsJsonObject("unsupported");
        return unsupported.has(id) ? unsupported.get(id).getAsString() : null;
    }

    public boolean isCanonical(String id) {
        return content.getAsJsonObject("blocks").has(id);
    }
}
