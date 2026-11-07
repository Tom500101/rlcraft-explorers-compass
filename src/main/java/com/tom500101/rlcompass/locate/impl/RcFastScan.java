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
 * Fast deep scan for Recurrent Complex targets whose biome is rare.
 *
 * <p>The generic deep scan samples a 256x256 biome tile for every tile, then replays the few chunks
 * whose biome can hold the target. For a target that only spawns in one biome (flower forest, snow,
 * ice plains, jungle, desert) almost all of that sampling is wasted.
 *
 * <p>This scanner inverts the order: it first runs a conservative RNG prefilter on the same chunk
 * seed RC's populate event receives, and only asks for the biome when one of the first
 * {@link #PREFILTER_DRAWS} rolls is below {@link #prefilterChance()}. The real replay still makes
 * every decision, so the prefilter can only let extra chunks through — provided its threshold is at
 * least the target's own chance, which {@link #prefilterChance()} guarantees per category.
 *
 * <p>Used for {@code ultrarare} and {@code rare} targets. Adventure structures are common enough
 * that the generic tile scan is cheaper than a prefilter whose pass rate would exceed 50%.
 */
public final class RcFastScan {

    private static final Logger LOG = LogManager.getLogger("rlcompass");
    /** Set by {@link #start}; the scanner itself is target-agnostic. */
    private static RcUltraRareLocator.Target target = RcUltraRareLocator.AUTHOR_HALL;

    /**
     * Category rolls examined per chunk.
     *
     * <p>RC spends one {@code nextDouble()} per structure that matches the biome, not one per
     * category — a forest selector in this pack holds ~95 entries, so a 64-draw prefilter could sit
     * entirely before the target's own roll and silently drop real hits. 128 covers every biome
     * measured so far; raising it further only costs time, never correctness.
     */
    private static final int PREFILTER_DRAWS = 128;

    /**
     * Prefilter threshold, per category. These are the empirically proven values — do not
     * "optimise" them again without an in-game A/B test on a target that actually spawns.
     *
     * <p>A tightened pair ({@code 3.5e-5} / {@code 6e-4}, 2026-11-06 13:03) was reverted after
     * six ultrarare scans (2 x authorshall 5600 + 4 x P_IceCity) produced zero candidates while
     * a rare target in the same session still hit. The offline set-comparison that approved it only
     * proved threshold monotonicity on one fixed 128-draw sequence; it never proved that RC's real
     * per-structure threshold equals the derived {@code 2.25e-5}, nor that the target's roll is
     * among those 128 draws. Actual rolls sit close to {@code 1e-4}, so the wide threshold is what
     * catches them (as false positives that the real replay then confirms). See HANDOFF §37.
     *
     * <p>ultrarare: {@code 1e-4}. rare: {@code 8e-4}.
     */
    private static double prefilterChance() {
        if (target != null && "rare".equalsIgnoreCase(target.category)) {
            return 8.0E-4;
        }
        return 1.0E-4;
    }

    /** Twice the generic scanner slice: this loop is a cheap RNG filter, not biome work. */
    private static final long TICK_BUDGET_NANOS = 8_000_000L;
    /** Experimental budget used when {@code CompassConfig.turboScan} is on; see HANDOFF §45. */
    private static final long TURBO_TICK_BUDGET_NANOS = 40_000_000L;
    private static final int PROGRESS_EVERY = 1_000_000;
    private static final int CHAT_EVERY = 5_000_000;

    /** Current per-tick slice; read every tick so the switch applies without restarting a scan. */
    private static long tickBudgetNanos() {
        return CompassConfig.turboScan ? TURBO_TICK_BUDGET_NANOS : TICK_BUDGET_NANOS;
    }

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

    private RcFastScan() {
    }

    public static boolean active() {
        return session != null;
    }

    /** RC name of the target being scanned, or {@code null} when idle. */
    public static String runningName() {
        return target == null ? null : target.rcName;
    }

    /** Starts (or reports) a scan centred on {@code around}, radius in chunks. */
    public static String start(RcUltraRareLocator.Target scanTarget, World scanWorld,
                               EntityPlayerMP scanPlayer, BlockPos around, int radiusChunks) {
        return start(scanTarget, scanWorld, scanPlayer, around, radiusChunks, true);
    }

    public static String start(RcUltraRareLocator.Target scanTarget, World scanWorld,
                               EntityPlayerMP scanPlayer, BlockPos around, int radiusChunks,
                               boolean firstOnly) {
        if (session != null) {
            tell(scanPlayer, "rlcompass.shivaxi.running", target.rcName, scanned, candidates);
            return "a fast scan is already running: " + scanned + " chunks, " + candidates
                    + " candidate(s). Use /rlcompass scan cancel to stop it.";
        }
        RcUltraRareLocator.Session opened = RcUltraRareLocator.openSession(scanWorld);
        if (opened == null) {
            tell(scanPlayer, "rlcompass.shivaxi.unavailable");
            return "Recurrent Complex selectors unavailable";
        }
        session = opened;
        target = scanTarget == null ? RcUltraRareLocator.AUTHOR_HALL : scanTarget;
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
                        + "({} chunks to walk); prefilter {} draws < {}",
                target.rcName, centerX, centerZ, radius, total, PREFILTER_DRAWS, prefilterChance());
        if (!session.isRegistered(target)) {
            tell(player, "rlcompass.shivaxi.notRegistered", target.rcName);
        }
        tell(player, "rlcompass.shivaxi.started", target.rcName,
                Integer.valueOf(radius), Long.valueOf(total));
        return "fast scan of " + target.rcName + " started: radius " + radius + " chunks, "
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
        PacketHandler.sendToPlayer(PacketDeepScanResult.cancelled(target == null
                ? "" : target.id, world == null ? 0 : world.provider.getDimension()), player);
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
            PacketHandler.sendToPlayer(PacketDeepScanResult.miss(
                    target == null ? "" : target.id,
                    world == null ? 0 : world.provider.getDimension(), radius), player);
        } else {
            tell(player, "rlcompass.shivaxi.finished",
                    Long.valueOf(scanned), Long.valueOf(eligible),
                    Integer.valueOf(candidates), Long.valueOf(seconds));
            if (lastHit != null) {
                PacketHandler.sendToPlayer(PacketDeepScanResult.hit(
                        target == null ? "" : target.id, lastHit,
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
        while (System.nanoTime() < deadline) {
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
            if (!active.maySelect(chunkX, chunkZ, PREFILTER_DRAWS, prefilterChance())) {
                continue;
            }
            prefiltered++;
            Biome biome = scanWorld.getBiome(
                    new BlockPos(chunkX * 16 + 8, 64, chunkZ * 16 + 8));
            if (!target.matchesBiome(biome) || !active.hasCategory(biome, target.category)) {
                continue;
            }
            eligible++;
            int[] refused = new int[1];
            List<BlockPos> found = active.candidates(target, biome, chunkX, chunkZ, refused);
            rejected += refused[0];
            for (BlockPos pos : found) {
                candidates++;
                if (lastHit == null) {
                    lastHit = pos;
                }
                RcUltraRareLocator.rememberDeepHit(target, pos);
                try {
                    SearchMemory.get(scanWorld).remember(target.id, pos);
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
                tell(player, "rlcompass.shivaxi.firstHit", target.rcName,
                        Integer.valueOf(candidates),
                        Long.valueOf((System.currentTimeMillis() - startedAt) / 1000L));
                if (lastHit != null) {
                    PacketHandler.sendToPlayer(PacketDeepScanResult.hit(target.id, lastHit,
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
        return "fast scan " + state + " (" + target.rcName + "): " + scanned + " chunks, "
                + prefiltered + " prefiltered, " + eligible + " eligible, " + candidates
                + " candidate(s), " + rejected + " rejected by the placer in "
                + ((System.currentTimeMillis() - startedAt) / 1000L) + " s"
                + (CompassConfig.turboScan ? " [turbo 40 ms/tick]" : "");
    }

    private static void tell(EntityPlayerMP target, String key, Object... args) {
        if (target == null) {
            return;
        }
        try {
            target.sendMessage(new TextComponentTranslation(key, args));
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

    /** Registered on Forge's server tick bus, like the generic scanner's ticker. */
    public static final class Ticker {
        @SubscribeEvent
        public void onServerTick(TickEvent.ServerTickEvent event) {
            if (event.phase == TickEvent.Phase.END) {
                RcFastScan.tick();
            }
        }
    }
}
