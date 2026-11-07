package com.tom500101.rlcompass.locate.impl;

import java.util.List;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.tom500101.rlcompass.cache.SearchMemory;
import com.tom500101.rlcompass.config.CompassConfig;
import com.tom500101.rlcompass.network.PacketDeepScanResult;
import com.tom500101.rlcompass.network.PacketHandler;

/**
 * Fast deep scan for the RLCraft 2.9 Author's Hall, kept in its own file on purpose.
 *
 * <p>This is the scanner that found a real Author's Hall at radius 5600 in about 16 minutes
 * (2026-11-05 23:18-23:34). It is deliberately <b>not</b> the parameterised {@link RcFastScan}:
 * the generic scanner was widened to all ultrarare/rare targets and later tightened, and neither
 * the 128-draw prefilter nor the narrower threshold reproduced that result. Restored verbatim in
 * behaviour on user request (2026-11-06); Shivaxi and P_IceCity keep the generic tile scan, and
 * only the Author's Hall is routed here.
 *
 * <p>The loop: take RC's own chunk seed, read the first {@link #PREFILTER_DRAWS}
 * {@code nextDouble()} rolls and skip the chunk when none is below {@link #PREFILTER_CHANCE};
 * only survivors get a biome lookup, the {@code ultrarare} category gate and the real RC replay.
 */
public final class AuthorHallFastScan {

    private static final Logger LOG = LogManager.getLogger("rlcompass");
    private static final RcUltraRareLocator.Target TARGET = RcUltraRareLocator.AUTHOR_HALL;

    /** RC has five categories in this pack; 64 rolls comfortably covers the target's own roll. */
    private static final int PREFILTER_DRAWS = 64;
    /** Author's Hall chance was measured at 1.5e-4 / 20 = 7.5e-6; 1e-4 leaves a 13x margin. */
    private static final double PREFILTER_CHANCE = 1.0E-4;

    private static final long TICK_BUDGET_NANOS = 4_000_000L;
    /**
     * Experimental budget used when {@code CompassConfig.turboScan} is on. 40 ms of a 50 ms tick
     * leaves little room for anything else, so TPS drops to roughly 14-16 while scanning; in
     * exchange the 5600-chunk Author's Hall run drops from ~21 minutes to ~2.
     */
    private static final long TURBO_TICK_BUDGET_NANOS = 40_000_000L;
    /**
     * Chunks between deadline checks. Reading {@code System.nanoTime()} per chunk cost ~4 s over a
     * 156M-chunk run; a slice of 128 chunks stays well under budget unless nearly all of them
     * survive the prefilter, which happens for 0.64% of chunks.
     */
    private static final int BUDGET_CHECK_EVERY = 128;
    private static final int PROGRESS_EVERY = 1_000_000;
    private static final int CHAT_EVERY = 5_000_000;

    private static RcUltraRareLocator.Session session;
    private static World world;
    private static EntityPlayerMP player;
    private static int radius;
    private static int centerX;
    private static int centerZ;
    private static int ring;
    private static int index;
    private static int scanned;
    private static int prefiltered;
    private static int eligible;
    private static int candidates;
    private static int rejected;
    private static long startedAt;
    private static int nextProgress;
    private static int nextChat;
    /** First verified hit, kept for the asynchronous result packet. */
    private static BlockPos lastHit;
    /** Ring order makes the first hit the nearest one, so the scan can stop there. */
    private static boolean stopAfterFirst = true;

    private AuthorHallFastScan() {
    }

    /**
     * Current per-tick slice: 40 ms in experimental turbo mode, 4 ms otherwise.
     *
     * <p>Read every tick rather than captured at start, so toggling the switch applies to a running
     * scan on the next tick.
     */
    private static long tickBudgetNanos() {
        return CompassConfig.turboScan ? TURBO_TICK_BUDGET_NANOS : TICK_BUDGET_NANOS;
    }

    /** Suffix for scan log lines, so a turbo run is never confused with a normal one. */
    private static String tickBudgetTag() {
        long budget = tickBudgetNanos();
        return CompassConfig.turboScan
                ? " [turbo " + (budget / 1_000_000L) + " ms/tick]" : "";
    }

    public static boolean active() {
        return session != null;
    }

    /** Starts (or reports) a scan centred on {@code around}, radius in chunks. */
    public static String start(World scanWorld, EntityPlayerMP scanPlayer, BlockPos around,
                               int radiusChunks) {
        return start(scanWorld, scanPlayer, around, radiusChunks, true);
    }

    public static String start(World scanWorld, EntityPlayerMP scanPlayer, BlockPos around,
                               int radiusChunks, boolean firstOnly) {
        if (session != null) {
            tell(scanPlayer, "rlcompass.shivaxi.running", TARGET.rcName, scanned, candidates);
            return "a fast scan is already running: " + scanned + " chunks, " + candidates
                    + " candidate(s). Use /rlcompass scan cancel to stop it.";
        }
        RcUltraRareLocator.Session opened = RcUltraRareLocator.openSession(scanWorld);
        if (opened == null) {
            tell(scanPlayer, "rlcompass.shivaxi.unavailable");
            return "Recurrent Complex selectors unavailable";
        }
        session = opened;
        world = scanWorld;
        player = scanPlayer;
        radius = Math.max(8, Math.min(RcUltraRareLocator.MAX_CHUNK_RADIUS, radiusChunks));
        centerX = around.getX() >> 4;
        centerZ = around.getZ() >> 4;
        ring = 0;
        index = 0;
        scanned = 0;
        prefiltered = 0;
        eligible = 0;
        candidates = 0;
        rejected = 0;
        nextProgress = PROGRESS_EVERY;
        nextChat = CHAT_EVERY;
        stopAfterFirst = firstOnly;
        lastHit = null;
        startedAt = System.currentTimeMillis();
        long total = (2L * radius + 1L) * (2L * radius + 1L);
        LOG.info("[FASTHALL] fast scan for {} started at chunk({},{}) radius {} chunks "
                        + "({} chunks to walk); prefilter {} draws < {}{}",
                TARGET.rcName, centerX, centerZ, radius, total, PREFILTER_DRAWS, PREFILTER_CHANCE,
                tickBudgetTag());
        if (!session.isRegistered(TARGET)) {
            tell(player, "rlcompass.shivaxi.notRegistered", TARGET.rcName);
        }
        tell(player, "rlcompass.shivaxi.started", TARGET.rcName,
                Integer.valueOf(radius), Long.valueOf(total));
        return "fast scan of " + TARGET.rcName + " started: radius " + radius + " chunks, "
                + total + " chunks, centred on chunk(" + centerX + "," + centerZ + ")";
    }

    public static String stop() {
        if (session == null) {
            return "no scan is running";
        }
        String summary = summarize("stopped");
        tell(player, "rlcompass.shivaxi.stopped",
                Long.valueOf(scanned), Long.valueOf(eligible), Integer.valueOf(candidates),
                Long.valueOf((System.currentTimeMillis() - startedAt) / 1000L));
        PacketHandler.sendToPlayer(PacketDeepScanResult.cancelled(TARGET.id,
                world == null ? 0 : world.provider.getDimension()), player);
        clear();
        LOG.info("[FASTHALL] {}", summary);
        return summary;
    }

    private static void clear() {
        session = null;
        world = null;
        player = null;
        lastHit = null;
    }

    private static void finish() {
        long seconds = (System.currentTimeMillis() - startedAt) / 1000L;
        if (rejected > 0) {
            tell(player, "rlcompass.shivaxi.rejected", Integer.valueOf(rejected), "");
        }
        if (candidates == 0) {
            tell(player, "rlcompass.shivaxi.none",
                    Long.valueOf(scanned), Long.valueOf(eligible), Long.valueOf(seconds));
            PacketHandler.sendToPlayer(PacketDeepScanResult.miss(TARGET.id,
                    world == null ? 0 : world.provider.getDimension(), radius), player);
        } else {
            tell(player, "rlcompass.shivaxi.finished",
                    Long.valueOf(scanned), Long.valueOf(eligible),
                    Integer.valueOf(candidates), Long.valueOf(seconds));
            if (lastHit != null) {
                PacketHandler.sendToPlayer(PacketDeepScanResult.hit(TARGET.id, lastHit,
                        world == null ? 0 : world.provider.getDimension()), player);
            }
        }
        LOG.info("[FASTHALL] {}", summarize("finished"));
        clear();
    }

    /** Called from the server tick; a few milliseconds of work and returns. */
    private static void tick() {
        RcUltraRareLocator.Session active = session;
        World scanWorld = world;
        if (active == null || scanWorld == null) {
            return;
        }
        long deadline = System.nanoTime() + tickBudgetNanos();
        int untilCheck = 0;
        while (true) {
            if (untilCheck <= 0) {
                untilCheck = BUDGET_CHECK_EVERY;
                if (System.nanoTime() >= deadline) {
                    return;
                }
            }
            untilCheck--;
            if (ring > radius) {
                finish();
                return;
            }
            int length = ring == 0 ? 1 : 8 * ring;
            int dx = ringOffsetX(ring, index);
            int dz = ringOffsetZ(ring, index);
            index++;
            if (index >= length) {
                ring++;
                index = 0;
            }
            int chunkX = centerX + dx;
            int chunkZ = centerZ + dz;
            scanned++;
            if (scanned >= nextChat) {
                nextChat += CHAT_EVERY;
                tell(player, "rlcompass.shivaxi.progress",
                        Long.valueOf(scanned), Long.valueOf(total()),
                        Long.valueOf(eligible), Integer.valueOf(candidates),
                        Long.valueOf((System.currentTimeMillis() - startedAt) / 1000L));
            }
            if (scanned >= nextProgress) {
                nextProgress += PROGRESS_EVERY;
                LOG.info("[FASTHALL] progress: {} chunks, {} prefiltered, {} eligible, "
                                + "{} candidate(s), ring {}/{}, {} s",
                        scanned, prefiltered, eligible, candidates, ring, radius,
                        (System.currentTimeMillis() - startedAt) / 1000L);
            }
            if (!active.maySelect(chunkX, chunkZ, PREFILTER_DRAWS, PREFILTER_CHANCE)) {
                continue;
            }
            prefiltered++;
            Biome biome = scanWorld.getBiome(
                    new BlockPos(chunkX * 16 + 8, 64, chunkZ * 16 + 8));
            if (!TARGET.matchesBiome(biome) || !active.hasCategory(biome, TARGET.category)) {
                continue;
            }
            eligible++;
            int[] refused = new int[1];
            List<BlockPos> found = active.candidates(TARGET, biome, chunkX, chunkZ, refused);
            rejected += refused[0];
            for (BlockPos pos : found) {
                candidates++;
                if (lastHit == null) {
                    lastHit = pos;
                }
                RcUltraRareLocator.rememberDeepHit(TARGET, pos);
                try {
                    SearchMemory.get(scanWorld).remember(TARGET.id, pos);
                } catch (Throwable ignored) {
                    // warehouse is a convenience; never let it break the scan
                }
                LOG.info("[FASTHALL] candidate #{} at ({}, ~, {}) chunk({},{}) verified",
                        candidates, pos.getX(), pos.getZ(), chunkX, chunkZ);
                tell(player, "rlcompass.shivaxi.candidate", Integer.valueOf(candidates),
                        Integer.valueOf(chunkX), Integer.valueOf(chunkZ),
                        Integer.valueOf(pos.getX()), Integer.valueOf(pos.getZ()));
            }
            if (stopAfterFirst && candidates > 0) {
                tell(player, "rlcompass.shivaxi.firstHit", TARGET.rcName,
                        Integer.valueOf(candidates),
                        Long.valueOf((System.currentTimeMillis() - startedAt) / 1000L));
                if (lastHit != null) {
                    PacketHandler.sendToPlayer(PacketDeepScanResult.hit(TARGET.id, lastHit,
                            scanWorld.provider.getDimension()), player);
                }
                LOG.info("[FASTHALL] {}", summarize("first hit"));
                clear();
                return;
            }
        }
    }

    private static long total() {
        return (2L * radius + 1L) * (2L * radius + 1L);
    }

    private static String summarize(String state) {
        return "fast scan " + state + " (" + TARGET.rcName + "): " + scanned + " chunks, "
                + prefiltered + " prefiltered, " + eligible + " eligible, " + candidates
                + " candidate(s), " + rejected + " rejected by the placer in "
                + ((System.currentTimeMillis() - startedAt) / 1000L) + " s" + tickBudgetTag();
    }

    private static void tell(EntityPlayerMP recipient, String key, Object... args) {
        if (recipient == null) {
            return;
        }
        try {
            recipient.sendMessage(new TextComponentTranslation(key, args));
        } catch (Throwable ignored) {
            // player logged out mid-scan
        }
    }

    private static int ringOffsetX(int ring, int index) {
        if (ring == 0) {
            return 0;
        }
        int side = index / (2 * ring);
        int step = index % (2 * ring);
        switch (side) {
            case 0:
                return -ring + step;
            case 1:
                return ring;
            case 2:
                return ring - step;
            default:
                return -ring;
        }
    }

    private static int ringOffsetZ(int ring, int index) {
        if (ring == 0) {
            return 0;
        }
        int side = index / (2 * ring);
        int step = index % (2 * ring);
        switch (side) {
            case 0:
                return -ring;
            case 1:
                return -ring + step;
            case 2:
                return ring;
            default:
                return ring - step;
        }
    }

    /** Registered on Forge's server tick bus alongside the other scanners. */
    public static final class Ticker {
        @SubscribeEvent
        public void onServerTick(TickEvent.ServerTickEvent event) {
            if (event.phase == TickEvent.Phase.END) {
                AuthorHallFastScan.tick();
            }
        }
    }
}
