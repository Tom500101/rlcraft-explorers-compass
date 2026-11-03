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
 * positions file is never written. The towers are still in the world, though, and they are
 * extremely recognisable: a 12x12 shell whose walls blend {@code cobblestone} with
 * {@code mossy_cobblestone} at roughly 1:1 over more than 40 blocks of height. A sampled tower had
 * 842 + 830 blocks; natural terrain and villages never come close.
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

    /**
     * Wall composition of a real tower. A sampled save produced seven 200/200 hits, of which three
     * were villages near spawn (cobble 286-404); every real tower had 700+ cobblestone and 250+
     * mossy. The tighter floor keeps all four towers and drops the villages.
     */
    private static final int MIN_COBBLE = 600;
    private static final int MIN_MOSSY = 250;
    /** Two hits closer than this on both axes are the same tower (one tower spans 1-4 chunks). */
    private static final int MERGE_DISTANCE = 24;

    private static final Map<String, Cache> CACHES = new HashMap<String, Cache>();

    private BattleTowersRegionScanner() {
    }

    /**
     * Towers recorded in the save's region files, as block positions with {@code y=64}. Blocks the
     * caller until a scan finishes, so only search should use it.
     */
    public static List<BlockPos> scan(World world) {
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
    public static List<BlockPos> peek(World world) {
        File regionDir = regionDirectory(world);
        if (regionDir == null) {
            return Collections.emptyList();
        }
        synchronized (CACHES) {
            Cache cache = CACHES.get(regionDir.getAbsolutePath());
            return cache == null ? Collections.<BlockPos>emptyList() : cache.current();
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
        private volatile List<BlockPos> towers = Collections.emptyList();
        private volatile boolean scanned;
        private volatile boolean scanning;

        boolean isScanned() {
            return scanned;
        }

        boolean isScanning() {
            return scanning;
        }

        /** Latest result; never waits for an in-flight scan. */
        List<BlockPos> current() {
            return towers;
        }

        synchronized List<BlockPos> refresh(File regionDir) {
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
                List<BlockPos> found = new ArrayList<BlockPos>();
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

    private static void scanRegion(File file, List<BlockPos> out) {
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
                    if (stats.cobble >= MIN_COBBLE && stats.mossy >= MIN_MOSSY) {
                        int chunkX = regionX * 32 + x;
                        int chunkZ = regionZ * 32 + z;
                        out.add(new BlockPos(chunkX * 16 + stats.centerX(), 64,
                                chunkZ * 16 + stats.centerZ()));
                    }
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
                int id = data[base + j] & 0xFF;
                if (id == 4) {
                    stats.addCobble(j & 15, (j >> 4) & 15);
                } else if (id == 48) {
                    stats.addMossy(j & 15, (j >> 4) & 15);
                }
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

    /** Collapses the 1-4 chunks a single tower spans into one position. */
    private static List<BlockPos> merge(List<BlockPos> raw) {
        List<BlockPos> out = new ArrayList<BlockPos>();
        for (BlockPos pos : raw) {
            boolean duplicate = false;
            for (BlockPos kept : out) {
                if (Math.abs(kept.getX() - pos.getX()) <= MERGE_DISTANCE
                        && Math.abs(kept.getZ() - pos.getZ()) <= MERGE_DISTANCE) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                out.add(pos);
            }
        }
        return out;
    }

    private static final class Stats {
        int cobble;
        int mossy;
        long sumX;
        long sumZ;
        int count;

        void addCobble(int x, int z) {
            cobble++;
            sumX += x;
            sumZ += z;
            count++;
        }

        void addMossy(int x, int z) {
            mossy++;
            sumX += x;
            sumZ += z;
            count++;
        }

        int centerX() {
            return count == 0 ? 8 : (int) Math.round((double) sumX / count);
        }

        int centerZ() {
            return count == 0 ? 8 : (int) Math.round((double) sumZ / count);
        }
    }
}
