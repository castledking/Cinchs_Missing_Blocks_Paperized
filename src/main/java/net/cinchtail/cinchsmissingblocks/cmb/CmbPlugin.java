package net.cinchtail.cinchsmissingblocks.cmb;

import net.cinchtail.cinchsmissingblocks.cmb.config.CmbConfig;
import net.cinchtail.cinchsmissingblocks.cmb.config.ConfigLoader;
import net.cinchtail.cinchsmissingblocks.cmb.pack.PackDeliveryService;
import net.cinchtail.cinchsmissingblocks.cmb.pack.PackInstaller;
import net.cinchtail.cinchsmissingblocks.cmb.writer.PackWriter;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

/**
 * Companion behaviours for the Cinch's Missing Blocks Paperized pack.
 *
 * <p>The generator owns content. This plugin owns only the mechanics CraftEngine cannot
 * supply, because the built-in behaviours require block properties the pack
 * deliberately does not declare in order to fit the vanilla carrier-state budget. See
 * DESIGN.md.
 *
 * <p>Registration happens in {@link #onLoad()}, not {@code onEnable()}. CraftEngine
 * registers its own behaviour types during its {@code onLoad()} and only parses block
 * configuration in the enable phase, so {@code load: BEFORE} in paper-plugin.yml is
 * what guarantees this plugin's types exist by the time anything looks them up.
 */
public final class CmbPlugin extends JavaPlugin {

    private OwnershipVerifier ownershipVerifier;
    private ConfigLoader configLoader;
    private PackDeliveryService packDeliveryService;
    private PackWriter packWriter;
    private final Lang lang = new Lang(this);
    private SetupNotice setupNotice;
    /** When this plugin started loading, so the banner can say how long the boot took. */
    private final long startupStartTime = System.currentTimeMillis();
    /** Resolved once on enable: the mode actually in force, after install checks. */
    private CmbConfig.ResourcePack.Delivery delivery;

    /** Milliseconds since this plugin started loading, for the banner's boot line. */
    long startupStartTime() {
        return startupStartTime;
    }

    boolean debug() {
        // A system property because this has to be readable during onLoad(), where the
        // plugin data folder does not exist yet.
        return Boolean.getBoolean("cmb.debug");
    }

    @Override
    public void onLoad() {
        // Touching the class performs the registrations in its static initialiser.
        // Doing it here rather than in onEnable is the whole point of the plugin
        // ordering; see paper-plugin.yml.
        int registered = CmbBehaviors.ALL.size();
        getLogger().info("Registered " + registered + " CraftEngine block behaviors");

        // The pack goes in before CraftEngine reads its packs: every onLoad runs before
        // any onEnable, and CraftEngine loads after CMB (paper-plugin.yml).
        this.configLoader = new ConfigLoader(this);
        try {
            configLoader.load();
            installPack();
        } catch (Throwable t) {
            getLogger().log(Level.SEVERE, "Could not install the CMB pack", t);
        }
    }

    /**
     * Writes the jar's bundled pack into CraftEngine's resources, filtered by config.yml
     * (PackInstaller). {@code resource-pack.install: false} leaves the folder alone.
     */
    private void installPack() {
        CmbConfig cfg = configLoader.config();
        PackInstaller installer = new PackInstaller(this);
        if (!cfg.resourcePack().install()) {
            getLogger().info("resource-pack.install is false: leaving " + installer.target() + " as it is");
            return;
        }
        try {
            PackInstaller.Result result = installer.install(cfg);
            getLogger().info("Installed the CMB pack to " + installer.target() + ": "
                    + result.written() + " written, " + result.unchanged() + " unchanged, "
                    + result.deleted() + " deleted; " + result.removedIds()
                    + " ids turned off by config.yml");
        } catch (Throwable t) {
            getLogger().log(Level.SEVERE, "Could not install the CMB pack", t);
        }
    }

    @Override
    public void onEnable() {
        // CraftEngine is not optional. Every CMB block is a CraftEngine block, and the
        // behaviours registered in onLoad() are looked up by CraftEngine when it parses
        // the pack - without it the pack loads nothing and every registered cmb:* type is
        // an orphan. paper-plugin.yml already declares it `required`, so this only has to
        // cover the case of a server that somehow enabled us anyway.
        this.packDeliveryService = new PackDeliveryService(this);
        if (!packDeliveryService.craftEngineInstalled()) {
            getLogger()
                    .severe(
                            "CraftEngine is not installed, so there is nothing for CMB to do - "
                                    + "every CMB block is a CraftEngine block. Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        if (configLoader == null) {
            this.configLoader = new ConfigLoader(this);
        }
        this.packWriter = new PackWriter(this);
        lang.load();

        // Registered before CraftEngine's own startup build, so that build is delivered too.
        getServer().getPluginManager().registerEvents(packDeliveryService, this);

        // Configure only. Startup must not dispatch `ce reload all`: CraftEngine is still
        // enabling and builds its pack itself, and a reload fired into that raced it (a
        // restart reloaded configs but produced no zip). /cmb reload all is the only
        // trigger.
        applyConfig();
        this.ownershipVerifier = new OwnershipVerifier(this);
        getServer().getPluginManager().registerEvents(ownershipVerifier, this);
        // Water, waterlogging and doubling for vertical slabs. Inert when the pack has
        // none: it only acts on cinchsmissingblocks:*_vertical furniture.
        // Which cells hold a piece: the fast "no" for the per-event checks below.
        getServer().getPluginManager().registerEvents(new PieceIndex(this), this);
        // WorldGuard regions and GriefPrevention claims, for what CMB places and breaks.
        getServer().getPluginManager().registerEvents(new Protection(this), this);
        getServer().getPluginManager().registerEvents(new VerticalSlabListener(this), this);
        // Several hits to break a furniture piece, by hardness and tool.
        getServer().getPluginManager().registerEvents(new FurnitureMining(this), this);
        // The warped nether wart crop, and finding it.
        getServer().getPluginManager().registerEvents(new NetherWartCrops(this), this);
        // The first-join setup message for admins (/cmb setupmsg shows it again).
        this.setupNotice = new SetupNotice(this);
        getServer().getPluginManager().registerEvents(setupNotice, this);
        if (debug()) {
            try {
                Integration1bCommand integration = new Integration1bCommand(this);
                getLifecycleManager().registerEventHandler(
                        io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents.COMMANDS,
                        event -> event.registrar().register(integration.build()));
                // It also records the break/drop events of its own probe cell.
                getServer().getPluginManager().registerEvents(integration, this);
                getLogger().info("Registered cmb1b command");
            } catch (Throwable t) {
                // A missing diagnostic command must never disable the plugin.
                getLogger().warning("cmb1b not registered: " + t);
            }
        }
        if (debug()) {
            try {
                VerticalShapeProbe shapes = new VerticalShapeProbe(this);
                getLifecycleManager().registerEventHandler(
                        io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents.COMMANDS,
                        event -> event.registrar().register(shapes.build()));
                getLogger().info("Registered cmbvshape command");
                CraftProbeCommand craft = new CraftProbeCommand();
                getLifecycleManager().registerEventHandler(
                        io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents.COMMANDS,
                        event -> event.registrar().register(craft.build()));
                getLogger().info("Registered cmbcraft command");
                VerticalSlabProbe vslab = new VerticalSlabProbe();
                getLifecycleManager().registerEventHandler(
                        io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents.COMMANDS,
                        event -> event.registrar().register(vslab.build()));
                getLogger().info("Registered cmbvslab command");
            } catch (Throwable t) {
                getLogger().warning("cmbvshape not registered: " + t);
            }
        }
        if (debug()) {
            // Debug-only: the cmb:stairs state-space matrix (layer 1a). Registered
            // through Paper's command lifecycle, and never fatally - a missing
            // diagnostic command must not disable the behaviour plugin.
            try {
                StairsMatrixCommand matrix = new StairsMatrixCommand();
                getLifecycleManager().registerEventHandler(
                        io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents.COMMANDS,
                        event -> event.registrar().register(matrix.build()));
                // Layer 1a runs once CraftEngine's registry is populated; see
                // RegistryTimingProbe for the measurement behind this trigger.
                getServer().getPluginManager().registerEvents(matrix, this);
                getLogger().info("Registered cmbstairs command");
            } catch (Throwable t) {
                getLogger().warning("cmbstairs command not registered: " + t);
            }
        }
        try {
            CmbCommand cmd = new CmbCommand(this);
            getLifecycleManager().registerEventHandler(
                    io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents.COMMANDS,
                    event -> event.registrar().register(cmd.build()));
            getLogger().info("Registered cmb command");
        } catch (Throwable t) {
            getLogger().warning("cmb command not registered: " + t);
        }
        // Last, so the details grid reports the state actually in force: delivery is
        // resolved in applyConfig(), and the listeners above are what that state describes.
        printStartupBanner();

        if (debug()) {
            getLogger().info("ownership verifier active (-Dcmb.debug=true)");
            // Measures when CraftEngine's block registry becomes readable. Layer 1a
            // is not triggered automatically until that boundary is known.
            RegistryTimingProbe probe = new RegistryTimingProbe(this);
            getServer().getPluginManager().registerEvents(probe, this);
            probe.start();
        }
    }

    /**
     * The banner, on the console.
     *
     * <p>Console-only on purpose: a boot summary for whoever is reading the server log.
     * Sending it to a joining player would either spam chat or need a permission check that
     * answers a question /cmb setupmsg already answers.
     *
     * <p>A banner that cannot be built is logged and skipped. It is decoration, and a plugin
     * that refuses to enable over decoration is worse than one without it.
     */
    private void printStartupBanner() {
        try {
            String banner = new StartupHeader(this).getRandomHeader();
            if (banner != null) {
                // One sendMessage with the whole banner, not one log call per line.
                //
                // A logger call carries the plugin's name on every line, so splitting gave
                // twenty lines each prefixed [CMB]. Splitting also tore the lang values
                // apart: tag-plugin is one value holding its own newline, and breaking the
                // banner on "\n" turned it into two separate messages. Embedded newlines
                // are the console's business, not ours.
                org.bukkit.Bukkit.getConsoleSender().sendMessage(banner);
            }
        } catch (Throwable t) {
            getLogger().warning("Startup banner skipped: " + t);
        }
    }

    /** Player-facing messages (lang.yml). */
    public Lang lang() {
        return lang;
    }

    public SetupNotice setupNotice() {
        return setupNotice;
    }

    /** The loaded config.yml. */
    public CmbConfig cmbConfig() {
        return configLoader.config();
    }

    /** The delivery mode in force, after install checks. */
    public CmbConfig.ResourcePack.Delivery delivery() {
        return delivery;
    }

    /** /cmb reload all: re-read the config, then have CraftEngine reload and rebuild. */
    public boolean reload() {
        lang.load();
        if (!applyConfig()) {
            return false;
        }
        installPack();
        try {
            org.bukkit.Bukkit.dispatchCommand(org.bukkit.Bukkit.getConsoleSender(), "ce reload all");
            getLogger().info("Triggered ce reload all");
            return true;
        } catch (Throwable t) {
            getLogger().warning("Failed to trigger ce reload all: " + t.getMessage());
            return false;
        }
    }

    /**
     * Load the config, verify the deployed pack and resolve delivery - once, so the RSPM
     * hint is logged once per startup or reload. Triggers nothing in CraftEngine.
     *
     * @return false if loading failed
     */
    private boolean applyConfig() {
        try {
            if (configLoader != null) {
                configLoader.load();
                configLoader.logSummary(configLoader.config());
            }
            try {
                if (packWriter != null) {
                    // The pack is installed from the jar (installPack); this only checks
                    // the result, or a hand-copied pack when resource-pack.install is off.
                    if (!packWriter.verifyPack()) {
                        getLogger().warning("CMB pack is missing or incomplete; CraftEngine will "
                                + "load nothing. See the install message above.");
                    }
                }
            } catch (Throwable t) {
                getLogger().log(java.util.logging.Level.WARNING, "Could not verify CMB pack", t);
            }
            // Re-resolved every reload: an admin who installed or removed RSPM and ran
            // /cmb reload all should not need a restart to have it take effect.
            delivery = packDeliveryService.resolve(configLoader.config());
            if (delivery == CmbConfig.ResourcePack.Delivery.RSPM) {
                packDeliveryService.ensureCraftEngineDoesNotSend();
            }
            return true;
        } catch (Throwable t) {
            getLogger().log(Level.SEVERE, "Failed to reload CMB", t);
            return false;
        }
    }

}