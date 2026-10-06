package net.cinchtail.cinchsmissingblocks.cmb;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Player-facing messages, from plugins/CMB/lang.yml with the jar's lang.yml behind it
 * for any key the file lacks. & colour codes; {prefix} and {placeholders} filled in.
 */
public final class Lang {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final JavaPlugin plugin;
    private YamlConfiguration messages = new YamlConfiguration();

    public Lang(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** (Re)reads lang.yml, writing the default first if there is none. */
    public void load() {
        File file = new File(plugin.getDataFolder(), "lang.yml");
        try (InputStream bundled = plugin.getResource("lang.yml")) {
            if (!file.isFile() && bundled != null) {
                Files.createDirectories(file.toPath().getParent());
                Files.copy(bundled, file.toPath());
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Could not write lang.yml: " + e.getMessage());
        }
        YamlConfiguration loaded = YamlConfiguration.loadConfiguration(file);
        try (InputStream bundled = plugin.getResource("lang.yml")) {
            if (bundled != null) {
                loaded.setDefaults(YamlConfiguration.loadConfiguration(
                        new InputStreamReader(bundled, StandardCharsets.UTF_8)));
            }
        } catch (IOException ignored) {
            // No defaults: missing keys show as their key.
        }
        messages = loaded;
    }

    /** One message. */
    public Component get(String key, Map<String, String> placeholders) {
        return LEGACY.deserialize(fill(messages.getString(key, key), placeholders));
    }

    public Component get(String key) {
        return get(key, Map.of());
    }

    /** A message stored as a list of lines; lines that come out empty are dropped. */
    public List<Component> lines(String key, Map<String, String> placeholders) {
        List<Component> out = new ArrayList<>();
        for (String line : messages.getStringList(key)) {
            for (String part : fill(line, placeholders).split("\n", -1)) {
                if (!part.isBlank()) {
                    out.add(LEGACY.deserialize(part));
                }
            }
        }
        return out;
    }

    /** The raw text of a message, for building others from it. */
    public String raw(String key) {
        return messages.getString(key, "");
    }

    /**
     * Raw text with placeholders filled in.
     *
     * <p>Separate from {@link #get} because the startup banner is console output assembled
     * as one string: a List<Component> would be split across lines by the logger, and the
     * art above it has to stay intact.
     */
    public String raw(String key, Map<String, String> placeholders) {
        return fill(messages.getString(key, key), placeholders);
    }

    private String fill(String text, Map<String, String> placeholders) {
        String out = text.replace("{prefix}", messages.getString("prefix", ""));
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            out = out.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return out;
    }
}
