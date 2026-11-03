package com.tom500101.rlcompass.cache;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.storage.RegionFile;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Finds Battle Towers by their blocks, straight out of the save's {@code region/*.mca} files.
 *
 * <p>Why this exists: when another mod replaces Battle Towers' generator (RLTweaker's
 * {@code Consistent Tower Generation}), the mod's in-memory {@code worldMap} stays empty and the
 * positions file is never written. The towers are still in the world, though, and each type has its
 * own wall material — see {@code AS_WorldGenTower$TowerTypes}:
 *
 * <pre>
 * CobbleStone        cobblestone                          (no mossy at all)
 * CobbleStoneMossy   mossy_cobblestone + cobblestone
 * SandStone          sandstone
 * Ice                ice / packed_ice
 * SmoothStone        stone
 * Netherrack         netherrack + nether_brick floor
 * Jungle             mossy_cobblestone + jungle wood
 * </pre>
 *
 * <p>An earlier version only counted cobblestone + mossy_cobblestone and required 250 mossy, which
 * silently dropped every plain {@code CobbleStone} tower and every {@code Netherrack} tower. The
 * thresholds below are per material, and a hit carries its type so the picker can offer real
 * variants.
 *
 * <p>Reading the region files directly (instead of calling {@code World.getBlockState}) means no
 * chunk loading, no terrain generation and no main-thread stalls beyond the file I/O itself.
 *
 * <h2>Format notes</h2>
 * An Anvil chunk is zlib-compressed NBT. Rather than parsing the whole tree, this walks the
 * decompressed bytes looking for the {@code Blocks} byte-array tag of each section: tag id 7,
 * name length 6, name "Blocks", length 4096. That is the only payload needed, and it keeps the scan
 * at byte-scanning speed (~30 KB per chunk).
 */
public final class BattleTowersRegionScanner {

    private static final Logger LOG = LogManager.getLogger("rlcompass");

    private static final int BLOCK_COBBLE = 4;
    private static final int BLOCK_MOSSY = 48;
    private static final int BLOCK_SANDSTONE = 24;
    private static final int BLOCK_ICE = 79;
    private static final int BLOCK_PACKED_ICE = 174;
    private static final int BLOCK_NETHERRACK = 87;
    private static final int BLOCK_SOUL_SAND = 88;
    private static final int BLOCK_OBSIDIAN = 49;

    /**
     * Per-material floors. Cobblestone excludes village smithies (286-404 blocks); a real tower has
     * 700+. Netherrack never occurs in a tower-sized cluster outside the Nether, so it is a strong
     * signal on its own — a sampled nether tower had hundreds of netherrack with a nether-brick
     * floor.
     */
    private static final int MIN_COBBLE = 700;
    private static final int MIN_MOSSY = 200;
    /**
     * A Netherrack tower is netherrack walls with a soul-sand floor every 7 levels. Netherrack
     * alone is useless (a natural lava field chunk held 2783 blocks), but the floor count is not:
     * a real tower had 9 floors of 84-103 soul sand (866 total), while natural terrain in the same
     * save peaked at 149 and an underground fragment at 166.
     */
    private static final int MIN_NETHERRACK = 500;
    private static final int MIN_SOUL_SAND = 300;
    /**
     * A Mega tower is 3-4 towers fused into a 48x48 compound whose interior is wrapped in obsidian;
     * a sampled one held ~11,000 obsidian across ten chunks (644-1689 each). Natural obsidian in
     * the same save peaked at 213 per chunk, so 500 separates cleanly.
     */
    private static final int MIN_OBSIDIAN = 500;
    /**
     * Same-type hits closer than this are one tower (a tower spans 1-4 chunks, centres within ~8
     * blocks). Different types are never merged: two towers of different materials can stand next
     * to each other, and the old 24-block merge kept only one of them.
     */
    private static final int MERGE_DISTANCE = 16;

    private static final Map<String, Cache> CACHES = new HashMap<String, Cache>();

    private BattleTowersRegionScanner() {
    }

    /** One tower found on disk: where it stands and which material family it belongs to. */
    public static final class TowerHit {
        public final BlockPos pos;
        public final String type;

        TowerHit(BlockPos pos, String type) {
            this.pos = pos;
            this.type = type;
        }
    }

    /**
     * Towers recorded in the save's region files. Blocks the caller until a scan finishes, so only
     * search should use it.
     */
    public static List<TowerHit> scan(World world) {
        File regionDir = regionDirectory(world);
        if (regionDir == null || !regionDir.isDirectory()) {
            return Collections.emptyList();
        }
        return cacheFor(regionDir).refresh(regionDir);
    }

    /**
     * Cached towers without waiting for a scan. Used by {@code canAnswer}, which runs every time the
     * picker opens — a synchronous scan there cost 2-3 s on a 64-region save.
     */
    public static List<TowerHit> peek(World world) {
        File regionDir = regionDirectory(world);
        if (regionDir == null) {
            return Collections.emptyList();
        }
        synchronized (CACHES) {
            Cache cache = CACHES.get(regionDir.getAbsolutePath());
            return cache == null ? Collections.<TowerHit>emptyList() : cache.current();
        }
    }

    /**
     * Scans in the background so the first picker open or search does not pay for it. Safe off the
     * main thread: only the region files are touched, never the live world.
     */
    public static void prewarm(final World world) {
        if (world == null || world.provider.getDimension() != 0) {
            return;
        }
        final File regionDir = regionDirectory(world);
        if (regionDir == null || !regionDir.isDirectory()) {
            return;
        }
        Cache cache = cacheFor(regionDir);
        if (cache.isScanned() || cache.isScanning()) {
            return;
        }
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // Let the world finish loading before competing for disk.
                    Thread.sleep(2000L);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                try {
                    cacheFor(regionDir).refresh(regionDir);
                } catch (Throwable t) {
                    LOG.debug("[BT] background scan failed: {}", t.toString());
                }
            }
        }, "rlcompass-battletower-scan");
        thread.setDaemon(true);
        thread.start();
    }

    private static Cache cacheFor(File regionDir) {
        synchronized (CACHES) {
            String key = regionDir.getAbsolutePath();
            Cache cache = CACHES.get(key);
            if (cache == null) {
                cache = new Cache();
                CACHES.put(key, cache);
            }
            return cache;
        }
    }

    /** Drops the cache for one save; used by tests and when a world is reloaded. */
    public static void invalidate(World world) {
        File regionDir = regionDirectory(world);
        if (regionDir == null) {
            return;
        }
        synchronized (CACHES) {
            CACHES.remove(regionDir.getAbsolutePath());
        }
    }

    private static File regionDirectory(World world) {
        if (world == null || world.provider.getDimension() != 0) {
            return null;
        }
        try {
            File base = world.getSaveHandler().getWorldDirectory();
            String folder = world.provider.getSaveFolder();
            return folder == null
                    ? new File(base, "region")
                    : new File(new File(base, folder), "region");
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Per-save cache keyed by region file name and mtime: only files that changed since the last
     * look are re-read, so a search after the first one is a hash lookup.
     */
    private static final class Cache {
        private final Map<String, Long> stamps = new HashMap<String, Long>();
        /** Written by the scanner thread, read lock-free by the picker. */
        private volatile List<TowerHit> towers = Collections.emptyList();
        private volatile boolean scanned;
        private volatile boolean scanning;

        boolean isScanned() {
            return scanned;
        }

        boolean isScanning() {
            return scanning;
        }

        /** Latest result; never waits for an in-flight scan. */
        List<TowerHit> current() {
            return towers;
        }

        synchronized List<TowerHit> refresh(File regionDir) {
            File[] files = regionDir.listFiles();
            if (files == null || scanning) {
                return towers;
            }
            boolean dirty = !scanned;
            for (File file : files) {
                if (!file.getName().endsWith(".mca")) {
                    continue;
                }
                long stamp = file.lastModified();
                Long previous = stamps.get(file.getName());
                if (previous == null || previous.longValue() != stamp) {
                    dirty = true;
                    stamps.put(file.getName(), Long.valueOf(stamp));
                }
            }
            if (!dirty) {
                return towers;
            }
            scanning = true;
            try {
                List<TowerHit> found = new ArrayList<TowerHit>();
                for (File file : files) {
                    if (file.getName().endsWith(".mca")) {
                        scanRegion(file, found);
                    }
                }
                towers = merge(found);
                scanned = true;
                LOG.info("[BT] region scan found {} towers under {}", towers.size(),
                        regionDir.getParent());
                return towers;
            } finally {
                scanning = false;
            }
        }
    }

    private static void scanRegion(File file, List<TowerHit> out) {
        String[] parts = file.getName().split("\\.");
        if (parts.length < 4) {
            return;
        }
        int regionX;
        int regionZ;
        try {
            regionX = Integer.parseInt(parts[1]);
            regionZ = Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            return;
        }
        RegionFile region = null;
        try {
            region = new RegionFile(file);
            for (int x = 0; x < 32; x++) {
                for (int z = 0; z < 32; z++) {
                    if (!region.chunkExists(x, z)) {
                        continue;
                    }
                    DataInputStream in = region.getChunkDataInputStream(x, z);
                    if (in == null) {
                        continue;
                    }
                    Stats stats = new Stats();
                    scanBlocks(readAll(in), stats);
                    String type = stats.detectType();
                    if (type == null) {
                        continue;
                    }
                    int chunkX = regionX * 32 + x;
                    int chunkZ = regionZ * 32 + z;
                    // Chunk centre, not the block centroid: the centroid of a 48x48 Mega drifts
                    // more than the merge radius between neighbouring chunks, which used to split
                    // one compound into four hits.
                    out.add(new TowerHit(
                            new BlockPos(chunkX * 16 + 8, 64, chunkZ * 16 + 8), type));
                }
            }
        } catch (Throwable t) {
            LOG.debug("[BT] could not scan {}: {}", file.getName(), t.toString());
        } finally {
            if (region != null) {
                try {
                    region.close();
                } catch (IOException ignored) {
                    // Nothing useful to do on close failure.
                }
            }
        }
    }

    private static byte[] readAll(DataInputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(32768);
        try {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        } finally {
            in.close();
        }
        return out.toByteArray();
    }

    /**
     * Walks the NBT bytes for section {@code Blocks} arrays. Every array holds 4096 block ids in
     * {@code y*256 + z*16 + x} order, so a hit's in-chunk column is {@code j & 15} / {@code (j>>4) & 15}.
     */
    private static void scanBlocks(byte[] data, Stats stats) {
        for (int i = 0; i + 13 + 4096 <= data.length; i++) {
            if ((data[i] & 0xFF) != 7 || data[i + 1] != 0 || data[i + 2] != 6) {
                continue;
            }
            if (data[i + 3] != 'B' || data[i + 4] != 'l' || data[i + 5] != 'o'
                    || data[i + 6] != 'c' || data[i + 7] != 'k' || data[i + 8] != 's') {
                continue;
            }
            if (readInt(data, i + 9) != 4096) {
                continue;
            }
            int base = i + 13;
            for (int j = 0; j < 4096; j++) {
                stats.add(data[base + j] & 0xFF, j & 15, (j >> 4) & 15);
            }
            i = base + 4095;
        }
    }

    private static int readInt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    /**
     * Collapses the chunks one structure spans, but never merges different materials. The position
     * is averaged as hits accumulate: a Mega tower covers ~10 chunks, so keeping the first hit
     * would report a corner instead of the middle.
     */
    private static List<TowerHit> merge(List<TowerHit> raw) {
        List<TowerHit> out = new ArrayList<TowerHit>();
        List<int[]> sums = new ArrayList<int[]>();
        for (TowerHit hit : raw) {
            int match = -1;
            for (int i = 0; i < out.size(); i++) {
                TowerHit kept = out.get(i);
                int radius = mergeRadius(kept.type);
                if (kept.type.equals(hit.type)
                        && Math.abs(kept.pos.getX() - hit.pos.getX()) <= radius
                        && Math.abs(kept.pos.getZ() - hit.pos.getZ()) <= radius) {
                    match = i;
                    break;
                }
            }
            if (match < 0) {
                out.add(hit);
                sums.add(new int[]{hit.pos.getX(), hit.pos.getZ(), 1});
            } else {
                int[] acc = sums.get(match);
                acc[0] += hit.pos.getX();
                acc[1] += hit.pos.getZ();
                acc[2]++;
                out.set(match, new TowerHit(
                        new BlockPos(acc[0] / acc[2], 64, acc[1] / acc[2]), hit.type));
            }
        }
        return out;
    }

    /** A Mega compound spans 4x4 chunks (~48 blocks) and needs a wider merge radius. */
    private static int mergeRadius(String type) {
        return "mega".equals(type) ? 64 : MERGE_DISTANCE;
    }

    private static final class Stats {
        int cobble;
        int mossy;
        int netherrack;
        int soulSand;
        int obsidian;
        int sandstone;
        int ice;
        long sumX;
        long sumZ;
        int count;

        void add(int id, int x, int z) {
            boolean tracked = true;
            switch (id) {
                case BLOCK_COBBLE:
                    cobble++;
                    break;
                case BLOCK_MOSSY:
                    mossy++;
                    break;
                case BLOCK_NETHERRACK:
                    netherrack++;
                    break;
                case BLOCK_SOUL_SAND:
                    soulSand++;
                    break;
                case BLOCK_OBSIDIAN:
                    obsidian++;
                    break;
                case BLOCK_SANDSTONE:
                    sandstone++;
                    break;
                case BLOCK_ICE:
                case BLOCK_PACKED_ICE:
                    ice++;
                    break;
                default:
                    tracked = false;
                    break;
            }
            if (tracked) {
                sumX += x;
                sumZ += z;
                count++;
            }
        }

        /**
         * Material family, or {@code null} when this chunk holds no tower. Sandstone and ice are
         * deliberately not enabled yet: both occur naturally in tower-sized amounts in their own
         * biomes, so they need a shape check first.
         */
        String detectType() {
            if (obsidian >= MIN_OBSIDIAN) {
                return "mega";
            }
            if (netherrack >= MIN_NETHERRACK && soulSand >= MIN_SOUL_SAND) {
                return "netherrack";
            }
            if (cobble >= MIN_COBBLE && mossy >= MIN_MOSSY) {
                return "cobblestonemossy";
            }
            if (cobble >= MIN_COBBLE) {
                return "cobblestone";
            }
            return null;
        }

        int centerX() {
            return count == 0 ? 8 : (int) Math.round((double) sumX / count);
        }

        int centerZ() {
            return count == 0 ? 8 : (int) Math.round((double) sumZ / count);
        }
    }
}
