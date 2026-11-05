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
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.storage.RegionFile;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Finds Ice and Fire dragon roosts and Lycanites Mobs dungeons by their blocks in the save's
 * {@code region/*.mca} files.
 *
 * <p>Neither mod exposes a {@code MapGenStructure}. Lycanites does keep exact {@code DungeonInstance}
 * records, and {@link com.tom500101.rlcompass.locate.impl.LycanitesDungeonLocator} reads those
 * first; Ice and Fire keeps no position list at all. So the region files are the only record that
 * survives both mods, and this scanner also acts as the fallback when Lycanites' own API is missing.
 *
 * <h2>Why a second scanner</h2>
 * {@link BattleTowersRegionScanner} counts a handful of <em>vanilla</em> block ids (cobblestone,
 * sandstone, &hellip;) that fit in the low {@code Blocks} byte. Every block used here is from a mod:
 * in this pack Ice and Fire occupies ids 977-1017 and Lycanites Mobs 1318-1460. 1.12.2 stores such
 * ids across two arrays — {@code Blocks} (the low 8 bits) and {@code Add} (a 2048-byte nibble array
 * holding bits 8-11). Without {@code Add} the scanner would see a different vanilla block for every
 * one of them and find nothing.
 *
 * <p>Ids are <b>not hard-coded</b>: they are resolved from {@code Block.REGISTRY} at scan time, so
 * the scanner still works if a pack adds or removes mods and the numeric ids shift. The only fixed
 * data is the set of registry names, taken from the shipped jars' {@code blockstates/} and
 * {@code dungeons/themes/} resources.
 *
 * <h2>Thresholds</h2>
 * Measured against a real RLCraft 2.9.3 save (9982 chunks, 94 region files): a fire roost paints
 * 40-621 {@code chared_*} blocks per chunk and drops 1-10 {@code goldpile} blocks in the centre;
 * Lycanites themes put 154-499 theme blocks in a dungeon chunk, while stray blocks (a player-placed
 * crystal, a dungeon edge) contribute 1-13. The floors below sit between those two populations.
 *
 * <p>This is a heuristic over blocks that are already on disk: unexplored chunks cannot be found,
 * and a dragon burning terrain may leave {@code chared_*} without a roost. A hit carrying treasure
 * is marked confirmed; the rest are candidates.
 */
public final class ModdedStructureRegionScanner {

    private static final Logger LOG = LogManager.getLogger("rlcompass");

    public static final String FAMILY_ROOST = "dragonroost";
    public static final String FAMILY_CAVE = "dragoncave";
    public static final String FAMILY_DUNGEON = "lycanitesdungeon";

    /** A scorched/frozen roost is a large disc; treasure in the middle is a much stronger signal. */
    private static final int MIN_ROOST_SURFACE = 100;
    private static final int MIN_ROOST_SURFACE_WITH_TREASURE = 40;
    /** A dungeon sector uses hundreds of theme blocks; scattered leftovers stay in the low tens. */
    private static final int MIN_DUNGEON_BLOCKS = 80;
    /** Same-family hits closer than this are one structure (a roost/dungeon spans several chunks). */
    private static final int MERGE_DISTANCE = 64;

    /**
     * Shape test that separates a dragon <em>cave</em> from a dragon <em>roost</em>.
     *
     * <p>Both are spheres of the same material, so a Y span or a block count cannot tell them
     * apart — a roost paints a solid sphere cap down from the surface and spans 30+ levels too.
     * What differs is the inside: {@code WorldGenFireDragonCave} fills a ball with stone, hollows
     * the inner ellipsoid to air, then paints the shell, so a cave's per-level histogram has a run
     * of completely empty levels between an upper and a lower shell. A roost's histogram is
     * continuous in its central chunk; only its ragged surface chunks show small gaps.
     *
    /**
     * Treasure depth is the decisive test, and it is what the user pointed out: a cave's chests and
     * gold/silver piles sit on the floor of its hollow core, while a roost's pile sits on the
     * surface. Measured on a real save:
     *
     * <pre>
     * cave (162,333) gold Y 12 | (13,2) 18 | (13,1)/(12,2)/(14,2) same cave, 18-25 | (192,344) 16
     * roost (141,323) gold Y 57-64
     * </pre>
     *
     * <p>30 sits in the empty band between the two populations with room on both sides. When a
     * structure has no treasure at all, the shell-bottom test below takes over.
     */
    private static final int CAVE_TREASURE_MAX_Y = 30;
    /** Fallback: a cave's shell bottoms out at Y 0-8, a roost's never drops below Y 9. */
    private static final int CAVE_DEEP_FLOOR_MAX = 8;
    /** Last resort: a run of empty levels between an upper and a lower shell. */
    private static final int CAVE_EMPTY_RUN_MIN = 13;
    private static final int CAVE_SPAN_MIN = 24;

    // Category ids are indexes into the per-chunk counters.
    private static final int CAT_FIRE_SURFACE = 1;
    private static final int CAT_FIRE_TREASURE = 2;
    private static final int CAT_ICE_SURFACE = 3;
    private static final int CAT_ICE_TREASURE = 4;
    /** {@code nest} is shared by both roost kinds, so it is counted separately and added to both. */
    private static final int CAT_NEST = 5;
    private static final int CAT_ABERRANT = 10;
    private static final int CAT_DEMONIC = 11;
    private static final int CAT_ASHEN = 12;
    private static final int CAT_DESERT = 13;
    private static final int CAT_LUSH = 14;
    private static final int CAT_SHADOW = 15;
    private static final int CAT_STREAM = 16;
    private static final int CATEGORY_COUNT = 17;

    /** Registry names of the blocks Ice and Fire paints under a fire dragon roost. */
    private static final String[] FIRE_SURFACE = {
            "iceandfire:chared_dirt", "iceandfire:chared_grass", "iceandfire:chared_stone",
            "iceandfire:chared_cobblestone", "iceandfire:chared_gravel",
            "iceandfire:chared_grass_path", "iceandfire:ash"
    };
    private static final String[] FIRE_TREASURE = {"iceandfire:goldpile"};
    /** The ice roost is the exact mirror: frozen ground, silver piles, dragon ice. */
    private static final String[] ICE_SURFACE = {
            "iceandfire:frozen_dirt", "iceandfire:frozen_grass", "iceandfire:frozen_stone",
            "iceandfire:frozen_cobblestone", "iceandfire:frozen_gravel",
            "iceandfire:frozen_grass_path", "iceandfire:frozen_splinters",
            "iceandfire:dragon_ice", "iceandfire:dragon_ice_spikes"
    };
    private static final String[] ICE_TREASURE = {
            "iceandfire:silverpile", "iceandfire:egginice"
    };
    private static final String[] SHARED_TREASURE = {"iceandfire:nest"};

    private static volatile int[] categoryById;

    private static final Map<String, Cache> CACHES = new HashMap<String, Cache>();

    private ModdedStructureRegionScanner() {
    }

    /** One structure found on disk: its family, its concrete kind and how sure we are. */
    public static final class Hit {
        public final BlockPos pos;
        public final String family;
        public final String kind;
        public final boolean confirmed;
        /** Levels that held this material, used by the cave shape test; may be null. */
        final boolean[] yOccupied;
        final int minY;
        final int maxY;
        /**
         * Lowest Y of this chunk's gold/silver pile, or -1 when it holds none. A cave's treasure is
         * on its floor (Y 12-25), a roost's on the surface (Y 50+), so this is the decisive test.
         */
        final int treasureMinY;
        /**
         * True when <b>this chunk alone</b> shows the hollow core. Must be decided per chunk: the
         * union of a roost's chunks also contains empty levels (one chunk covers Y 15-23, another
         * 37-46, and nothing fills 24-36), so aggregating first turns every roost into a cave.
         */
        final boolean caveEvidence;

        Hit(BlockPos pos, String family, String kind, boolean confirmed) {
            this(pos, family, kind, confirmed, null, -1, -1, -1, false);
        }

        Hit(BlockPos pos, String family, String kind, boolean confirmed,
            boolean[] yOccupied, int minY, int maxY, int treasureMinY, boolean caveEvidence) {
            this.pos = pos;
            this.family = family;
            this.kind = kind;
            this.confirmed = confirmed;
            this.yOccupied = yOccupied;
            this.minY = minY;
            this.maxY = maxY;
            this.treasureMinY = treasureMinY;
            this.caveEvidence = caveEvidence;
        }
    }

    /** Blocks until a scan finishes; only search should use it. */
    public static List<Hit> scan(World world) {
        File regionDir = regionDirectory(world);
        if (regionDir == null || !regionDir.isDirectory()) {
            return Collections.emptyList();
        }
        return cacheFor(regionDir).refresh(regionDir);
    }

    /** Cached hits without waiting for a scan; used by {@code canAnswer}. */
    public static List<Hit> peek(World world) {
        File regionDir = regionDirectory(world);
        if (regionDir == null) {
            return Collections.emptyList();
        }
        synchronized (CACHES) {
            Cache cache = CACHES.get(regionDir.getAbsolutePath());
            return cache == null ? Collections.<Hit>emptyList() : cache.current();
        }
    }

    /** Scans in the background so the first picker open or search does not pay for the file I/O. */
    public static void prewarm(final World world) {
        if (world == null) {
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
                    Thread.sleep(2500L);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                try {
                    cacheFor(regionDir).refresh(regionDir);
                } catch (Throwable t) {
                    LOG.debug("[MODSCAN] background scan failed: {}", t.toString());
                }
            }
        }, "rlcompass-modded-structure-scan");
        thread.setDaemon(true);
        thread.start();
    }

    /** Drops the cache for one save, e.g. when a world is reloaded. */
    public static void invalidate(World world) {
        File regionDir = regionDirectory(world);
        if (regionDir == null) {
            return;
        }
        synchronized (CACHES) {
            CACHES.remove(regionDir.getAbsolutePath());
        }
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

    /**
     * Unlike the Battle Towers scanner this keeps every dimension: Lycanites dungeons generate in
     * the Nether ({@code demonictemple}, {@code ashenmausoleum}) and the End too.
     */
    private static File regionDirectory(World world) {
        if (world == null) {
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

    /** Per-save cache built incrementally: a changed region file is re-read, the rest are reused. */
    private static final class Cache {
        private final Map<String, Long> stamps = new HashMap<String, Long>();
        private final Map<String, List<Hit>> perFile = new HashMap<String, List<Hit>>();
        private volatile List<Hit> hits = Collections.emptyList();
        private volatile boolean scanned;
        private volatile boolean scanning;

        boolean isScanned() {
            return scanned;
        }

        boolean isScanning() {
            return scanning;
        }

        List<Hit> current() {
            return hits;
        }

        synchronized List<Hit> refresh(File regionDir) {
            File[] files = regionDir.listFiles();
            if (files == null || scanning) {
                return hits;
            }
            scanning = true;
            try {
                // Only re-read files whose mtime changed. A full re-read of 94 files cost 3.3 s and
                // ran on every search, because the game keeps touching region files while playing.
                int reRead = 0;
                int[] table = null;
                for (File file : files) {
                    if (!file.getName().endsWith(".mca")) {
                        continue;
                    }
                    long stamp = file.lastModified();
                    Long previous = stamps.get(file.getName());
                    if (previous != null && previous.longValue() == stamp
                            && perFile.containsKey(file.getName())) {
                        continue;
                    }
                    if (table == null) {
                        table = table();
                    }
                    List<Hit> found = new ArrayList<Hit>();
                    scanRegion(file, table, found);
                    perFile.put(file.getName(), found);
                    stamps.put(file.getName(), Long.valueOf(stamp));
                    reRead++;
                }
                java.util.Iterator<String> cached = perFile.keySet().iterator();
                while (cached.hasNext()) {
                    String name = cached.next();
                    if (!new File(regionDir, name).isFile()) {
                        cached.remove();
                        stamps.remove(name);
                    }
                }
                if (reRead == 0 && scanned) {
                    return hits;
                }
                List<Hit> all = new ArrayList<Hit>();
                for (List<Hit> found : perFile.values()) {
                    all.addAll(found);
                }
                hits = merge(all);
                scanned = true;
                LOG.info("[MODSCAN] region scan found {} structures under {} ({} region files re-read)",
                        hits.size(), regionDir.getParent(), reRead);
                return hits;
            } finally {
                scanning = false;
            }
        }
    }

    private static void scanRegion(File file, int[] table, List<Hit> out) {
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
                    scanSections(readAll(in), table, stats);
                    int chunkX = regionX * 32 + x;
                    int chunkZ = regionZ * 32 + z;
                    stats.emit(chunkX, chunkZ, out);
                }
            }
        } catch (Throwable t) {
            LOG.debug("[MODSCAN] could not scan {}: {}", file.getName(), t.toString());
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
     * Walks the decompressed NBT for section {@code Blocks} arrays and combines each with its
     * {@code Add} nibble array to get the real 12-bit block id.
     */
    private static void scanSections(byte[] data, int[] table, Stats stats) {
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
            int nextBlocks = findBlocksTag(data, base + 4096);
            int add = findAddTag(data, base, nextBlocks);
            // Every block we look for has id > 255, so a section without Add holds none of them.
            if (add < 0) {
                i = base + 4095;
                continue;
            }
            // Cheap reject: the ids we want live in the 0x300 and 0x500 nibble ranges.
            boolean possible = false;
            for (int k = 0; k < 2048; k++) {
                int value = data[add + k] & 0xFF;
                int low = value & 0x0F;
                int high = (value >> 4) & 0x0F;
                if (low == 3 || low == 5 || high == 3 || high == 5) {
                    possible = true;
                    break;
                }
            }
            if (possible) {
                int sectionY = readSectionY(data, i);
                for (int j = 0; j < 4096; j++) {
                    int nibble = data[add + (j >> 1)] & 0xFF;
                    int high = (j & 1) == 0 ? (nibble & 0x0F) : ((nibble >> 4) & 0x0F);
                    int id = (high << 8) | (data[base + j] & 0xFF);
                    int category = table[id];
                    if (category != 0) {
                        int y = sectionY < 0 ? -1 : sectionY * 16 + ((j >> 8) & 15);
                        stats.add(category, j & 15, (j >> 4) & 15, y);
                    }
                }
            }
            i = base + 4095;
        }
    }

    /**
     * Next {@code Blocks} tag header within a bounded window. The window only needs to cover one
     * section's trailing arrays (Data/Add/BlockLight/SkyLight ~8 KB); scanning to the end of every
     * chunk for every section would turn a large save into an O(n^2) walk.
     */
    private static int findBlocksTag(byte[] data, int from) {
        int limit = Math.min(data.length, from + 9000);
        for (int k = from; k + 9 <= limit; k++) {
            if ((data[k] & 0xFF) == 7 && data[k + 1] == 0 && data[k + 2] == 6
                    && data[k + 3] == 'B' && data[k + 4] == 'l' && data[k + 5] == 'o'
                    && data[k + 6] == 'c' && data[k + 7] == 'k' && data[k + 8] == 's') {
                return k;
            }
        }
        return data.length;
    }

    /**
     * Payload offset of the section's {@code Add} byte array.
     *
     * <p>Backward first: this pack's Anvil layout writes {@code Add} immediately <b>before</b>
     * {@code Blocks} (payload ends exactly 2048 bytes earlier), with {@code Y} after {@code Data}.
     * A forward-first search therefore found the <em>next</em> section's {@code Add}, failed the
     * {@code possible} sanity check, and silently dropped the whole section — which is how a cave's
     * upper shell disappeared and the cluster was reported as a roost.
     *
     * <p>Forward is kept as a fallback for the vanilla order ({@code Blocks, Data, Add, ...}).
     */
    private static int findAddTag(byte[] data, int blocksPayload, int nextBlocks) {
        int backStart = Math.max(0, blocksPayload - 2600);
        int found = matchAddTag(data, backStart, blocksPayload);
        if (found >= 0) {
            return found;
        }
        int forwardLimit = Math.min(nextBlocks, Math.min(data.length, blocksPayload + 4096 + 9000));
        return matchAddTag(data, blocksPayload + 4096, forwardLimit);
    }

    private static int matchAddTag(byte[] data, int from, int to) {
        for (int k = from; k + 11 <= to && k + 11 <= data.length; k++) {
            if ((data[k] & 0xFF) != 7 || data[k + 1] != 0 || data[k + 2] != 3) {
                continue;
            }
            if (data[k + 3] != 'A' || data[k + 4] != 'd' || data[k + 5] != 'd') {
                continue;
            }
            if (readInt(data, k + 6) != 2048) {
                continue;
            }
            int payload = k + 10;
            if (payload + 2048 <= data.length) {
                return payload;
            }
        }
        return -1;
    }

    private static int readInt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    /**
     * Reads the section's {@code Y} byte. Anvil usually writes {@code Y} after {@code Blocks} and
     * {@code Data}, sometimes before, so both directions are searched and the value is validated as
     * a real section index. -1 means "unknown" and the shape test then simply stays disabled for
     * that chunk.
     */
    private static int readSectionY(byte[] data, int blocksTagPos) {
        int scanFrom = blocksTagPos + 13 + 4096;
        if (scanFrom + 11 <= data.length
                && (data[scanFrom] & 0xFF) == 7 && data[scanFrom + 1] == 0 && data[scanFrom + 2] == 4
                && data[scanFrom + 3] == 'D' && data[scanFrom + 4] == 'a'
                && data[scanFrom + 5] == 't' && data[scanFrom + 6] == 'a'
                && readInt(data, scanFrom + 7) == 2048) {
            scanFrom += 7 + 4 + 2048;
        }
        int found = scanForwardY(data, scanFrom, 9000);
        if (found >= 0) {
            return found;
        }
        int end = Math.max(0, blocksTagPos - 9000);
        for (int k = blocksTagPos - 4; k >= end; k--) {
            int y = matchY(data, k);
            if (y >= 0) {
                return y;
            }
        }
        return -1;
    }

    private static int scanForwardY(byte[] data, int scanFrom, int window) {
        int limit = Math.min(data.length, scanFrom + window);
        for (int k = scanFrom; k + 4 < limit; k++) {
            int y = matchY(data, k);
            if (y >= 0) {
                return y;
            }
        }
        return -1;
    }

    /** Returns the section Y at a candidate tag position, or -1 when it is not a valid Y tag. */
    private static int matchY(byte[] data, int k) {
        if ((data[k] & 0xFF) == 1 && data[k + 1] == 0 && data[k + 2] == 1
                && data[k + 3] == 'Y') {
            int value = data[k + 4];
            value = value < 0 ? value + 256 : value;
            if (value >= 0 && value <= 15) {
                return value;
            }
        }
        return -1;
    }

    /**
     * Collapses the chunks one structure spans into a single centre per material. Roosts and caves
     * use the same materials, so the family is only decided after the whole span is collected: the
     * hollow core is usually visible in the central chunk alone, while the shell spills into
     * neighbours whose own histograms look continuous.
     */
    private static List<Hit> merge(List<Hit> raw) {
        List<Cluster> clusters = new ArrayList<Cluster>();
        for (Hit hit : raw) {
            Cluster match = null;
            for (Cluster cluster : clusters) {
                if (cluster.matches(hit)) {
                    match = cluster;
                    break;
                }
            }
            if (match == null) {
                clusters.add(new Cluster(hit));
            } else {
                match.add(hit);
            }
        }
        List<Hit> out = new ArrayList<Hit>(clusters.size());
        for (Cluster cluster : clusters) {
            out.add(cluster.toHit());
        }
        return out;
    }

    /** Accumulates one structure's chunks, including the union of their per-level histograms. */
    private static final class Cluster {
        private final String kind;
        private final String family;
        private long sumX;
        private long sumZ;
        private int count;
        private boolean confirmed;
        private boolean[] yOccupied;
        private int minY = -1;
        private int maxY = -1;
        private int treasureMinY = -1;
        private boolean caveEvidence;

        Cluster(Hit hit) {
            this.kind = hit.kind;
            this.family = hit.family;
            add(hit);
        }

        boolean matches(Hit hit) {
            return kind.equals(hit.kind) && family.equals(hit.family)
                    && Math.abs(avgX() - hit.pos.getX()) <= MERGE_DISTANCE
                    && Math.abs(avgZ() - hit.pos.getZ()) <= MERGE_DISTANCE;
        }

        void add(Hit hit) {
            sumX += hit.pos.getX();
            sumZ += hit.pos.getZ();
            count++;
            if (hit.confirmed) {
                confirmed = true;
            }
            if (hit.caveEvidence) {
                caveEvidence = true;
            }
            if (hit.treasureMinY >= 0) {
                treasureMinY = treasureMinY < 0
                        ? hit.treasureMinY : Math.min(treasureMinY, hit.treasureMinY);
            }
            if (hit.yOccupied != null) {
                if (yOccupied == null) {
                    yOccupied = new boolean[256];
                }
                for (int y = 0; y < 256 && y < hit.yOccupied.length; y++) {
                    if (hit.yOccupied[y]) {
                        yOccupied[y] = true;
                    }
                }
            }
            if (hit.minY >= 0) {
                minY = minY < 0 ? hit.minY : Math.min(minY, hit.minY);
                maxY = Math.max(maxY, hit.maxY);
            }
        }

        private int avgX() {
            return count == 0 ? 0 : (int) (sumX / count);
        }

        private int avgZ() {
            return count == 0 ? 0 : (int) (sumZ / count);
        }

        Hit toHit() {
            boolean cave = false;
            if (FAMILY_ROOST.equals(family)) {
                if (treasureMinY >= 0) {
                    // Decisive: treasure on the floor of a hollow core, not on the surface.
                    cave = treasureMinY <= CAVE_TREASURE_MAX_Y;
                } else {
                    // No treasure to go by; fall back to the shape of the shell.
                    cave = caveEvidence;
                }
            }
            return new Hit(new BlockPos(avgX(), 64, avgZ()), cave ? FAMILY_CAVE : family, kind,
                    confirmed, yOccupied, minY, maxY, treasureMinY, caveEvidence);
        }
    }

    /** Per-chunk counters, per-category centroids and the level histograms the shape test needs. */
    private static final class Stats {
        private final int[] counts = new int[CATEGORY_COUNT];
        private final long[] sumX = new long[CATEGORY_COUNT];
        private final long[] sumZ = new long[CATEGORY_COUNT];
        private final boolean[][] yOccupied = new boolean[CATEGORY_COUNT][256];
        private final int[] minY = new int[CATEGORY_COUNT];
        private final int[] maxY = new int[CATEGORY_COUNT];

        Stats() {
            java.util.Arrays.fill(minY, -1);
        }

        void add(int category, int x, int z, int y) {
            counts[category]++;
            sumX[category] += x;
            sumZ[category] += z;
            if (y >= 0 && y < 256) {
                yOccupied[category][y] = true;
                minY[category] = minY[category] < 0 ? y : Math.min(minY[category], y);
                maxY[category] = Math.max(maxY[category], y);
            }
        }

        private int centerX(int category) {
            return counts[category] == 0 ? 8
                    : (int) Math.round((double) sumX[category] / counts[category]);
        }

        private int centerZ(int category) {
            return counts[category] == 0 ? 8
                    : (int) Math.round((double) sumZ[category] / counts[category]);
        }

        /**
         * True when this chunk alone shows a hollow core: a run of empty levels between an upper and
         * a lower shell. Roosts never do — their painted cap is solid from top to bottom.
         *
         * <p>Also true when the material reaches very deep, which only the underground cave sphere
         * can do. This is the fallback for a cave whose central chunk is still being written while
         * its shell chunks are already readable.
         */
        private boolean hasCavity(int category) {
            int low = minY[category];
            int high = maxY[category];
            if (low < 0 || high - low < CAVE_SPAN_MIN) {
                return false;
            }
            if (low <= CAVE_DEEP_FLOOR_MAX) {
                return true;
            }
            int run = 0;
            int best = 0;
            for (int y = low + 1; y < high && y < yOccupied[category].length; y++) {
                if (!yOccupied[category][y]) {
                    best = Math.max(best, ++run);
                } else {
                    run = 0;
                }
            }
            return best >= CAVE_EMPTY_RUN_MIN;
        }

        private void emit(int chunkX, int chunkZ, List<Hit> out) {
            emitRoost(chunkX, chunkZ, out, CAT_FIRE_SURFACE, CAT_FIRE_TREASURE, "fire");
            emitRoost(chunkX, chunkZ, out, CAT_ICE_SURFACE, CAT_ICE_TREASURE, "ice");
            emitTheme(chunkX, chunkZ, out, CAT_ABERRANT, "aberrant");
            emitTheme(chunkX, chunkZ, out, CAT_DEMONIC, "demonic");
            emitTheme(chunkX, chunkZ, out, CAT_ASHEN, "ashen");
            emitTheme(chunkX, chunkZ, out, CAT_DESERT, "desert");
            emitTheme(chunkX, chunkZ, out, CAT_LUSH, "lush");
            emitTheme(chunkX, chunkZ, out, CAT_SHADOW, "shadow");
            emitTheme(chunkX, chunkZ, out, CAT_STREAM, "stream");
        }

        private void emitRoost(int chunkX, int chunkZ, List<Hit> out, int surfaceCat,
                               int treasureCat, String kind) {
            int surface = counts[surfaceCat];
            int treasure = counts[treasureCat] + counts[CAT_NEST];
            boolean confirmed = treasure > 0;
            if (surface < MIN_ROOST_SURFACE
                    && !(confirmed && surface >= MIN_ROOST_SURFACE_WITH_TREASURE)) {
                return;
            }
            int treasureMin = -1;
            if (counts[treasureCat] > 0 && minY[treasureCat] >= 0) {
                treasureMin = minY[treasureCat];
            }
            if (counts[CAT_NEST] > 0 && minY[CAT_NEST] >= 0) {
                treasureMin = treasureMin < 0
                        ? minY[CAT_NEST] : Math.min(treasureMin, minY[CAT_NEST]);
            }
            // Family is decided after clustering: one cave spans many chunks and only its centre
            // holds the treasure, so a per-chunk verdict would split it into a cave plus a phantom
            // roost made of the shell chunks around it.
            out.add(new Hit(new BlockPos(chunkX * 16 + centerX(surfaceCat), 64,
                    chunkZ * 16 + centerZ(surfaceCat)), FAMILY_ROOST, kind, confirmed,
                    yOccupied[surfaceCat], minY[surfaceCat], maxY[surfaceCat], treasureMin,
                    hasCavity(surfaceCat)));
        }

        private void emitTheme(int chunkX, int chunkZ, List<Hit> out, int category, String kind) {
            if (counts[category] < MIN_DUNGEON_BLOCKS) {
                return;
            }
            out.add(new Hit(new BlockPos(chunkX * 16 + centerX(category), 64,
                    chunkZ * 16 + centerZ(category)), FAMILY_DUNGEON, kind, false));
        }
    }

    /** Resolves registry names to the numeric ids actually used by this world's region files. */
    private static int[] table() {
        int[] cached = categoryById;
        if (cached != null) {
            return cached;
        }
        int[] result = new int[4096];
        assign(result, FIRE_SURFACE, CAT_FIRE_SURFACE);
        assign(result, FIRE_TREASURE, CAT_FIRE_TREASURE);
        assign(result, ICE_SURFACE, CAT_ICE_SURFACE);
        assign(result, ICE_TREASURE, CAT_ICE_TREASURE);
        assign(result, SHARED_TREASURE, CAT_NEST);

        int themes = 0;
        try {
            Set<ResourceLocation> keys = Block.REGISTRY.getKeys();
            for (ResourceLocation key : keys) {
                if (!"lycanitesmobs".equals(key.getNamespace())) {
                    continue;
                }
                int category = themeCategory(key.getPath());
                if (category == 0) {
                    continue;
                }
                int id = idOf(key);
                if (id >= 0 && id < result.length) {
                    result[id] = category;
                    themes++;
                }
            }
        } catch (Throwable t) {
            LOG.warn("[MODSCAN] could not enumerate Lycanites theme blocks: {}", t.toString());
        }
        LOG.info("[MODSCAN] resolved {} Ice and Fire + {} Lycanites theme block ids from the registry",
                countIceAndFire(result), themes);
        categoryById = result;
        return result;
    }

    private static int countIceAndFire(int[] table) {
        int count = 0;
        for (int category : table) {
            if (category >= CAT_FIRE_SURFACE && category <= CAT_NEST) {
                count++;
            }
        }
        return count;
    }

    private static void assign(int[] table, String[] names, int category) {
        for (String name : names) {
            int id = idOf(new ResourceLocation(name));
            if (id >= 0 && id < table.length) {
                table[id] = category;
            }
        }
    }

    private static int idOf(ResourceLocation key) {
        if (!Block.REGISTRY.containsKey(key)) {
            return -1;
        }
        Block block = Block.REGISTRY.getObject(key);
        return block == null ? -1 : Block.getIdFromBlock(block);
    }

    /** Theme prefix shared by a dungeon's floor, wall, stair, crystal and pillar blocks. */
    private static int themeCategory(String path) {
        if (path.startsWith("aberrant")) {
            return CAT_ABERRANT;
        }
        if (path.startsWith("demon")) {
            return CAT_DEMONIC;
        }
        if (path.startsWith("ashen")) {
            return CAT_ASHEN;
        }
        if (path.startsWith("desert")) {
            return CAT_DESERT;
        }
        if (path.startsWith("lush")) {
            return CAT_LUSH;
        }
        if (path.startsWith("shadow")) {
            return CAT_SHADOW;
        }
        if (path.startsWith("stream")) {
            return CAT_STREAM;
        }
        return 0;
    }
}
