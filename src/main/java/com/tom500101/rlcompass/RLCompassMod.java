package com.tom500101.rlcompass;

import java.util.List;

import net.minecraft.item.Item;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartedEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraft.command.ICommand;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.tom500101.rlcompass.cache.CacheRecorder;
import com.tom500101.rlcompass.cache.StructureCacheData;
import com.tom500101.rlcompass.client.ClientSetup;
import com.tom500101.rlcompass.command.CommandRLCompass;
import com.tom500101.rlcompass.config.CompassConfig;
import com.tom500101.rlcompass.item.ItemExplorersCompass;
import com.tom500101.rlcompass.cache.BattleTowersRegionScanner;
import com.tom500101.rlcompass.cache.RcGenerationRecorder;
import com.tom500101.rlcompass.cache.RecurrentComplexData;
import com.tom500101.rlcompass.locate.MapGenLocatorRegistrar;
import com.tom500101.rlcompass.locate.ModLocatorRegistrar;
import com.tom500101.rlcompass.locate.StructureLocatorRegistry;
import com.tom500101.rlcompass.locate.StructureResult;
import com.tom500101.rlcompass.locate.impl.BattleTowersLocator;
import com.tom500101.rlcompass.locate.impl.RuinsLocator;
import com.tom500101.rlcompass.network.PacketHandler;
import com.tom500101.rlcompass.util.StructureProbe;

/**
 * RLcraft Explorer's Compass — mod entry point.
 *
 * <p>Registers the network channel and the structure adapters, the {@code /rlcompass} command, and
 * two headless diagnostic hooks ({@code rlcompass.probe} / {@code rlcompass.selftest}) that exist
 * because a dedicated server cannot be driven from a script — see {@code docs/probe-result.md}.
 *
 * <p>The self-test runs twice: immediately on start, and again after a delay. Several mods build
 * their structure indices lazily (Ruins fills {@code RuinGenerator.registeredRuins} from a
 * background loader), so the first pass legitimately finds nothing where the second one succeeds.
 */
@Mod(
        modid = RLCompassMod.MODID,
        name = RLCompassMod.NAME,
        version = RLCompassMod.VERSION,
        acceptedMinecraftVersions = "[1.12.2]",
        useMetadata = true)
public class RLCompassMod {

    /** Mod id; must match {@code mcmod.info} and the asset/resource paths. */
    public static final String MODID = "rlcompass";

    /** Human-readable mod name shown in the Forge mod list. */
    public static final String NAME = "RLcraft Explorer's Compass";

    /** Displayed version; kept in sync with {@code mod_version} in gradle.properties. */
    public static final String VERSION = "1.0.0";

    /** When set to {@code true}, the structure probe runs automatically on server start. */
    public static final String PROBE_PROPERTY = "rlcompass.probe";

    /** When set to {@code true}, every registered structure is searched for once on server start. */
    public static final String SELFTEST_PROPERTY = "rlcompass.selftest";

    /** Ticks to wait before re-running the self-test (20 ticks = 1 second). */
    private static final int SELFTEST_DELAY_TICKS = 600;

    private static final Logger LOG = LogManager.getLogger(MODID);

    private int delayedSelfTestCountdown = -1;

    @EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        PacketHandler.init();
        MapGenLocatorRegistrar.registerAll();
        ModLocatorRegistrar.registerAll();
        MinecraftForge.EVENT_BUS.register(this);
        // RC wipes its own structure records on load, so they are captured at generation time.
        MinecraftForge.EVENT_BUS.register(new RcGenerationRecorder());
        // Client classes do not exist on a dedicated server, so the HUD renderer is only touched here.
        if (event.getSide().isClient()) {
            ClientSetup.register();
        }
    }

    @EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandRLCompass());
    }

    @SubscribeEvent
    public void registerItems(RegistryEvent.Register<Item> event) {
        event.getRegistry().register(new ItemExplorersCompass());
        LOG.info("[REGISTRY] registered item {}", ItemExplorersCompass.REGISTRY_NAME);
    }

    @EventHandler
    public void serverStarted(FMLServerStartedEvent event) {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) {
            return;
        }
        WorldServer overworld = server.getWorld(0);
        if (overworld == null) {
            return;
        }
        // Battle Towers may no longer record positions (RLTweaker replaces the generator), so the
        // save's region files are scanned in the background before the first search asks for it.
        BattleTowersRegionScanner.prewarm(overworld);
        logRecurrentComplexCommands(server);
        if (Boolean.getBoolean(PROBE_PROPERTY)) {
            StructureProbe.probe(overworld, overworld.getSpawnPoint());
        }
        if (Boolean.getBoolean(SELFTEST_PROPERTY)) {
            LOG.info("[SELFTEST] immediate pass");
            runSelfTest(overworld);
            delayedSelfTestCountdown = SELFTEST_DELAY_TICKS;
        }
    }

    /**
     * RLTweaker's {@code RecurrentHandler.RCCleanup} deletes Recurrent Complex's recorded structures
     * on every world load. The save file still holds the previous session's entries at this point —
     * the wipe only touches memory until something saves — so the snapshot has to be taken here.
     */
    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        if (event.getWorld() != null && !event.getWorld().isRemote) {
            RecurrentComplexData.snapshot(event.getWorld());
        }
    }

    /** Drops the memoised Recurrent Complex list so a reloaded world is read again. */
    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (event.getWorld() != null && !event.getWorld().isRemote) {
            RecurrentComplexData.invalidate();
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || delayedSelfTestCountdown < 0) {
            return;
        }
        if (--delayedSelfTestCountdown > 0) {
            return;
        }
        delayedSelfTestCountdown = -1;
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        WorldServer overworld = server == null ? null : server.getWorld(0);
        if (overworld == null) {
            return;
        }
        LOG.info("[SELFTEST] delayed pass ({} ticks after start)", SELFTEST_DELAY_TICKS);
        runSelfTest(overworld);
    }

    /** Searches every selectable structure once and logs the outcome; headless verification harness. */
    private void runSelfTest(WorldServer world) {
        BlockPos origin = world.getSpawnPoint();
        int dimension = world.provider.getDimension();

        // Report the cache as it was loaded from disk *before* rebuilding — this is what proves the
        // WorldSavedData round-trips across a restart.
        StructureCacheData cache = CacheRecorder.get(world);
        if (cache == null) {
            LOG.info("[SELFTEST] cache unavailable");
        } else {
            LOG.info("[SELFTEST] cache on load: {} towers, {} ruins, {} total entries",
                    cache.countFor(dimension, BattleTowersLocator.STRUCTURE_ID),
                    cache.countFor(dimension, RuinsLocator.STRUCTURE_ID),
                    cache.totalEntries());
        }
        LOG.info("[SELFTEST] cache rebuild wrote {} positions", CacheRecorder.rebuild(world));

        // P0: world-aware filter — searching an id with no data source only logs a guaranteed null.
        List<String> ids = StructureLocatorRegistry.get().getSelectableIds(world);
        LOG.info("[SELFTEST] origin=({}, {}) radius={} ids={}",
                origin.getX(), origin.getZ(), CompassConfig.searchRadius, ids);
        for (String id : ids) {
            long startedAt = System.currentTimeMillis();
            StructureResult result = StructureLocatorRegistry.get()
                    .findNearest(world, origin, id, CompassConfig.searchRadius);
            LOG.info("[SELFTEST] {} -> {} ({} ms)", id, result, System.currentTimeMillis() - startedAt);
        }
        LOG.info("[SELFTEST] done");
    }

    /**
     * Recurrent Complex builds its command names as {@code commandPrefix + "gen"}, and the prefix is
     * read from the config at runtime — which is why the exact spelling is worth logging rather than
     * guessing when a command is reported as unknown.
     */
    private static void logRecurrentComplexCommands(MinecraftServer server) {
        try {
            StringBuilder names = new StringBuilder();
            for (ICommand command : server.getCommandManager().getCommands().values()) {
                if (command.getClass().getName().contains("reccomplex")
                        || command.getClass().getName().contains("ivorius")) {
                    if (names.length() > 0) {
                        names.append(", ");
                    }
                    names.append('/').append(command.getName());
                }
            }
            LOG.info("[RC] registered commands: {}", names.length() == 0 ? "<none>" : names.toString());
        } catch (Throwable t) {
            LOG.warn("[RC] could not list registered commands: {}", t.toString());
        }
    }
}
