package net.cinchtail.cinchsmissingblocks.cmb.pack;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.cinchtail.cinchsmissingblocks.cmb.config.ConfigLoader;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackDeliveryServiceTest {
    @TempDir Path plugins;
    private final List<String> logs = new ArrayList<>();

    private Plugin plugin(boolean rspm) {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            public void publish(LogRecord record) { logs.add(record.getMessage()); }
            public void flush() {}
            public void close() {}
        });
        Plugin[] plugin = new Plugin[1];
        PluginManager manager = proxy(PluginManager.class, (method, args) ->
                method.equals("getPlugin") && rspm ? plugin[0] : null);
        Server server = proxy(Server.class, (method, args) ->
                method.equals("getPluginManager") ? manager : null);
        plugin[0] = proxy(Plugin.class, (method, args) -> switch (method) {
            case "getServer" -> server;
            case "getLogger" -> logger;
            case "getDataFolder" -> plugins.resolve("CMB").toFile();
            default -> null;
        });
        return plugin[0];
    }

    @Test void craftengineModeIgnoresBuildEventsEvenWithRspmInstalled() {
        PackDeliveryService service = new PackDeliveryService(plugin(true));
        service.resolve(ConfigLoader.defaults());
        logs.clear();
        // An inactive listener must return before reading the event or scheduling work.
        service.onPackGenerated(null);
        assertEquals(List.of(), logs);
        assertFalse(Files.exists(plugins.resolve("ResourcePackManager")));
    }

    @Test void absentRspmNeverReceivesBuildEvents() {
        PackDeliveryService service = new PackDeliveryService(plugin(false));
        service.resolve(ConfigLoader.defaults());
        logs.clear();
        service.onPackGenerated(null);
        assertEquals(List.of(), logs);
    }

    @Test void disablesRealMultilineSettingWithoutUncommentingExample() throws Exception {
        Path file = plugins.resolve("CraftEngine/config.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "# send-on-join: true\nresource-pack:\n  delivery:\n"
                + "    send-on-join: true # keep this comment\n    kick-if-declined: true\n");
        PackDeliveryService service = new PackDeliveryService(plugin(true));
        assertTrue(service.ensureCraftEngineDoesNotSend());
        String updated = Files.readString(file);
        assertTrue(updated.contains("# send-on-join: true"));
        assertTrue(updated.contains("    send-on-join: false # keep this comment"));
        assertTrue(updated.contains("# keep this comment"));
        assertTrue(updated.contains("    kick-if-declined: true"));
        assertTrue(service.ensureCraftEngineDoesNotSend());
        assertEquals(updated, Files.readString(file));
    }

    @Test void missingSendSettingIsNotReportedAsSuccess() throws Exception {
        Path file = plugins.resolve("CraftEngine/config.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "# send-on-join: true\nresource-pack: {}\n");
        assertFalse(new PackDeliveryService(plugin(true)).ensureCraftEngineDoesNotSend());
    }

    private interface Invocation { Object call(String method, Object[] args); }
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (object, method, args) -> invocation.call(method.getName(), args)));
    }
}
