package net.cinchtail.cinchsmissingblocks.cmb.pack;

import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Files;

public class RspmBootstrap {
    private static final String API_CLASS = "com.magmaguy.resourcepackmanager.api.ResourcePackManagerAPI";

    public static boolean registerLocal(Plugin plugin, Path packZip, String localPath) {
        if (packZip == null || !Files.exists(packZip)) return false;
        try {
            Class<?> api = Class.forName(API_CLASS);
            Method register = api.getMethod(
                    "registerLocalResourcePack",
                    String.class,
                    String.class,
                    boolean.class,
                    boolean.class,
                    boolean.class,
                    String.class);
            register.invoke(null, plugin.getName(), localPath, false, true, true, null);
            plugin.getLogger().info("Registered CMB resource pack with ResourcePackManager (" + localPath + ")");
            return true;
        } catch (Throwable t) {
            plugin.getLogger().fine("ResourcePackManager registration not ready: " + t);
            return false;
        }
    }
}
