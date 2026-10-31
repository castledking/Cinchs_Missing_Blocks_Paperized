package net.cinchtail.cinchsmissingblocks.cmb;

import static org.junit.jupiter.api.Assertions.*;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpdateNoticeTest {

    @TempDir Path dataDir;

    @Test
    void freshInstallRecordsVersionWithoutRequestingReload() {
        File dataFile = dataDir.resolve("data.yml").toFile();
        assertFalse(UpdateNotice.hasExistingInstall(dataDir.toFile()));

        UpdateNotice notice = new UpdateNotice(null, dataFile, "1.0.5", false, msg -> fail(msg));

        assertFalse(notice.isReloadNeeded());
        assertNull(notice.updatedFrom());
        YamlConfiguration saved = YamlConfiguration.loadConfiguration(dataFile);
        assertEquals("1.0.5", saved.getString("last-version"));
        assertFalse(saved.getBoolean("reload-needed"));

        // A subsequent restart on the same version stays quiet.
        UpdateNotice restart = new UpdateNotice(null, dataFile, "1.0.5", true, msg -> fail(msg));
        assertFalse(restart.isReloadNeeded());
        assertNull(restart.updatedFrom());
    }

    @Test
    void upgradeFromPreTrackingInstallTriggersReminderAndPreservesSeenList() throws Exception {
        Files.writeString(dataDir.resolve("config.yml"), "update-checker: true\n");
        File dataFile = dataDir.resolve("data.yml").toFile();
        YamlConfiguration existing = new YamlConfiguration();
        existing.set("setup-message-seen", List.of("83137bed-7566-3b46-9937-725a76f590aa"));
        existing.save(dataFile);

        assertTrue(UpdateNotice.hasExistingInstall(dataDir.toFile()));
        UpdateNotice notice = new UpdateNotice(null, dataFile, "1.0.5", true, msg -> fail(msg));

        assertTrue(notice.isReloadNeeded());
        assertNull(notice.updatedFrom());
        YamlConfiguration saved = YamlConfiguration.loadConfiguration(dataFile);
        assertEquals(List.of("83137bed-7566-3b46-9937-725a76f590aa"),
                saved.getStringList("setup-message-seen"));
        assertEquals("1.0.5", saved.getString("last-version"));
        assertTrue(saved.getBoolean("reload-needed"));
    }

    @Test
    void versionChangePersistsAcrossRestartsUntilReloadAllMarksItDone() {
        File dataFile = dataDir.resolve("data.yml").toFile();
        new UpdateNotice(null, dataFile, "1.0.4", false, msg -> fail(msg));

        // Upgrade 1.0.4 -> 1.0.5.
        UpdateNotice upgraded = new UpdateNotice(null, dataFile, "1.0.5", true, msg -> fail(msg));
        assertTrue(upgraded.isReloadNeeded());
        assertEquals("1.0.4", upgraded.updatedFrom());

        // Restarting before /cmb reload all must keep the reminder alive.
        UpdateNotice restarted = new UpdateNotice(null, dataFile, "1.0.5", true, msg -> fail(msg));
        assertTrue(restarted.isReloadNeeded());
        assertEquals("1.0.4", restarted.updatedFrom());

        // Jumping another version before reloading keeps the last reloaded version as updatedFrom.
        UpdateNotice jumped = new UpdateNotice(null, dataFile, "1.0.6", true, msg -> fail(msg));
        assertTrue(jumped.isReloadNeeded());
        assertEquals("1.0.4", jumped.updatedFrom());

        // Running /cmb reload all clears it now and across restarts.
        jumped.markReloaded();
        assertFalse(jumped.isReloadNeeded());
        assertNull(jumped.updatedFrom());

        UpdateNotice afterReloadRestart = new UpdateNotice(null, dataFile, "1.0.6", true, msg -> fail(msg));
        assertFalse(afterReloadRestart.isReloadNeeded());
        assertNull(afterReloadRestart.updatedFrom());
    }

    @Test
    void rendersDefaultsAgainstOlderServerLangFileAndAddsClickSuggestionForPlayers() throws Exception {
        // Simulate an older server's plugins/CMB/lang.yml that predates the new keys.
        Files.writeString(dataDir.resolve("lang.yml"), "prefix: \"&6[CMB] \"\n");
        Lang lang = new Lang(fakePlugin());
        lang.load();

        File dataFile = dataDir.resolve("data.yml").toFile();
        new UpdateNotice(null, dataFile, "1.0.4", false, msg -> fail(msg));
        UpdateNotice notice = new UpdateNotice(null, dataFile, "1.0.5", true, msg -> fail(msg));

        List<Component> consoleLines = new ArrayList<>();
        CommandSender console = proxy(CommandSender.class, (method, args) -> {
            if (method.equals("sendMessage") && args != null && args.length == 1
                    && args[0] instanceof Component c) {
                consoleLines.add(c);
            }
            return null;
        });
        notice.send(console, lang, true);
        assertEquals(3, consoleLines.size());
        String consoleText = String.join("\n", consoleLines.stream()
                .map(PlainTextComponentSerializer.plainText()::serialize).toList());
        assertTrue(consoleText.contains("from 1.0.4 to 1.0.5"), consoleText);
        assertTrue(consoleText.contains("/cmb reload all"), consoleText);
        assertTrue(consoleLines.stream().allMatch(c -> c.clickEvent() == null));

        // Join listener: non-admins are ignored; cmb.admin receives the delayed clickable notice.
        List<Component> playerLines = new ArrayList<>();
        Runnable[] scheduled = new Runnable[1];
        EntityScheduler scheduler = proxy(EntityScheduler.class, (method, args) -> {
            if (method.equals("runDelayed")) {
                @SuppressWarnings("unchecked")
                java.util.function.Consumer<Object> task = (java.util.function.Consumer<Object>) args[1];
                scheduled[0] = () -> task.accept(null);
            }
            return null;
        });

        Player nonAdmin = proxy(Player.class, (method, args) -> switch (method) {
            case "hasPermission" -> false;
            case "getScheduler" -> scheduler;
            default -> null;
        });
        notice.onJoin(new PlayerJoinEvent(nonAdmin, Component.empty()));
        assertNull(scheduled[0]);

        Player admin = proxy(Player.class, (method, args) -> switch (method) {
            case "hasPermission" -> "cmb.admin".equals(args[0]);
            case "getScheduler" -> scheduler;
            case "sendMessage" -> {
                if (args != null && args.length == 1 && args[0] instanceof Component c) {
                    playerLines.add(c);
                }
                yield null;
            }
            default -> null;
        });
        notice.onJoin(new PlayerJoinEvent(admin, Component.empty()));
        assertNotNull(scheduled[0]);

        // Test player rendering directly (including ClickEvent suggestion).
        notice.send(admin, lang, false);
        assertEquals(3, playerLines.size());
        String playerText = String.join("\n", playerLines.stream()
                .map(PlainTextComponentSerializer.plainText()::serialize).toList());
        assertTrue(playerText.contains("resource-pack.install is off"), playerText);
        Component actionLine = playerLines.getLast();
        assertEquals(ClickEvent.suggestCommand("/cmb reload all"), actionLine.clickEvent());

        // If /cmb reload all finishes before the 60-tick join delay fires, the queued task skips sending.
        playerLines.clear();
        notice.markReloaded();
        scheduled[0].run();
        assertEquals(List.of(), playerLines);
    }

    private Plugin fakePlugin() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        return proxy(Plugin.class, (method, args) -> switch (method) {
            case "getDataFolder" -> dataDir.toFile();
            case "getResource" -> getClass().getClassLoader().getResourceAsStream((String) args[0]);
            case "getLogger" -> logger;
            default -> null;
        });
    }

    private interface Invocation {
        Object call(String method, Object[] args);
    }

    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (object, method, args) -> switch (method.getName()) {
                    case "equals" -> object == args[0];
                    case "hashCode" -> System.identityHashCode(object);
                    case "toString" -> type.getSimpleName() + "Proxy";
                    default -> invocation.call(method.getName(), args);
                }));
    }
}
