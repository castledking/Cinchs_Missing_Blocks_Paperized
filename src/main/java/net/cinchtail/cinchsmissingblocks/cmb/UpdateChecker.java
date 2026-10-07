package net.cinchtail.cinchsmissingblocks.cmb;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import net.cinchtail.cinchsmissingblocks.cmb.scheduler.Schedulers;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Asks Modrinth whether a newer CMB release exists for this server, and says so: once in
 * the console, and to players with cmb.admin when they join.
 *
 * <p>Only releases built for this server's Minecraft version and for Paper count, so it
 * never points at a version that wouldn't load here. It checks on startup and every 12
 * hours, off the main thread; a failed request is logged once and otherwise ignored.
 * {@code update-checker: false} in config.yml turns it off.
 */
final class UpdateChecker implements Listener {

    /** CMB's Modrinth project. */
    static final String PROJECT = "wPFj1p2L";
    private static final long EVERY_TICKS = 12L * 60 * 60 * 20;

    private final CmbPlugin plugin;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private volatile String latest;
    private volatile boolean warned;

    UpdateChecker(CmbPlugin plugin) {
        this.plugin = plugin;
    }

    void start() {
        Schedulers.globalRepeating(plugin, this::check, () -> {}, 20L * 10, EVERY_TICKS);
    }

    private void check() {
        String current = plugin.getPluginMeta().getVersion();
        String query = "?loaders=" + encode("[\"paper\"]")
                + "&game_versions=" + encode("[\"" + Bukkit.getMinecraftVersion() + "\"]");
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("https://api.modrinth.com/v2/project/" + PROJECT + "/version" + query))
                .header("User-Agent", "castledking/CMB/" + current + " (update check)")
                .timeout(Duration.ofSeconds(15))
                .GET().build();
        http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete((response, error) -> {
            if (error != null || response.statusCode() != 200) {
                if (!warned) {
                    warned = true;
                    plugin.getLogger().info("Update check failed: "
                            + (error != null ? error.getMessage() : "HTTP " + response.statusCode()));
                }
                return;
            }
            String newest = newestRelease(response.body());
            if (newest != null && isNewer(newest, current) && !newest.equals(latest)) {
                latest = newest;
                plugin.getLogger().info("CMB " + newest + " is available (this server runs " + current
                        + "): " + url(newest));
            }
        });
    }

    /** The newest release in Modrinth's version list (which comes newest first). */
    static String newestRelease(String json) {
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonArray()) {
            return null;
        }
        JsonArray versions = root.getAsJsonArray();
        for (JsonElement element : versions) {
            JsonObject version = element.getAsJsonObject();
            if ("release".equals(version.get("version_type").getAsString())) {
                return version.get("version_number").getAsString();
            }
        }
        return null;
    }

    /**
     * Whether {@code candidate} is a later version than {@code current}, comparing the
     * dotted numbers part by part (1.10.0 is after 1.9.2). A suffix (-beta) only breaks a
     * tie, and makes the version earlier than the same number without one.
     */
    static boolean isNewer(String candidate, String current) {
        int[] a = numbers(candidate);
        int[] b = numbers(current);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) {
                return x > y;
            }
        }
        return !candidate.contains("-") && current.contains("-");
    }

    private static int[] numbers(String version) {
        String core = version.replaceFirst("^[vV]", "").split("[-+ ]", 2)[0];
        String[] parts = core.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i].replaceAll("\\D.*$", ""));
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }

    private static String url(String version) {
        return "https://modrinth.com/project/" + PROJECT + "/version/" + version;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        String newest = latest;
        Player player = event.getPlayer();
        if (newest != null && player.hasPermission("cmb.admin")) {
            player.sendMessage(plugin.lang().get("update-available", Map.of(
                    "latest", newest,
                    "current", plugin.getPluginMeta().getVersion(),
                    "url", url(newest))));
        }
    }
}
