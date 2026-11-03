package com.tom500101.rlcompass.cache;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Remembers what has already been searched, so repeated searches advance instead of repeating.
 *
 * <p>Rare structures — the reason this mod exists — are usually found by sweeping a region, not by
 * one exact answer. A locator that can only ever return "the nearest" makes the second search
 * useless: it returns the same spot until the player walks somewhere else. This store gives every
 * answer a state and lets the search skip the ones already dealt with.
 *
 * <p>States:
 * <ul>
 *   <li>{@link State#NEW} — suggested, not looked at yet;</li>
 *   <li>{@link State#FOUND} — the player got there and the structure is really there. A search will
 *       skip it from now on;</li>
 *   <li>{@link State#MISSING} — the player got there and nothing was there. Skipped from now on.</li>
 * </ul>
 *
 * <p>Only the transition NEW → FOUND/MISSING is automatic, and it is decided by a locator that can
 * actually confirm presence (Recurrent Complex's generation records). A state is never invented:
 * if nothing can confirm, the entry stays NEW.
 *
 * <p>Storage is one append-only TSV per world and dimension,
 * {@code <world>/rlcompass/search_memory_<dim>.tsv}, lines {@code id \t x \t y \t z \t state}. The
 * last line for a key wins, so updating a state never rewrites earlier history.
 */
public final class SearchMemory {

    private static final Logger LOG = LogManager.getLogger("rlcompass");

    public enum State {
        NEW,
        FOUND,
        MISSING
    }

    /** One remembered candidate. */
    public static final class Entry {
        public final String structureId;
        public final BlockPos pos;
        public final State state;

        Entry(String structureId, BlockPos pos, State state) {
            this.structureId = structureId;
            this.pos = pos;
            this.state = state;
        }
    }

    private static final String DIRECTORY = "rlcompass";
    private static final String PREFIX = "search_memory_";
    private static final String SUFFIX = ".tsv";

    private static final Map<String, SearchMemory> INSTANCES = new HashMap<String, SearchMemory>();

    private final File file;
    private final Map<String, Entry> entries = new HashMap<String, Entry>();

    private SearchMemory(File file) {
        this.file = file;
        load();
    }

    public static SearchMemory get(World world) {
        File file = fileFor(world);
        if (file == null) {
            return null;
        }
        String key = file.getAbsolutePath();
        synchronized (INSTANCES) {
            SearchMemory memory = INSTANCES.get(key);
            if (memory == null) {
                memory = new SearchMemory(file);
                INSTANCES.put(key, memory);
            }
            return memory;
        }
    }

    public static void invalidate() {
        synchronized (INSTANCES) {
            INSTANCES.clear();
        }
    }

    /** True when this exact point is settled (found or missing) and should not be offered again. */
    public boolean isSettled(String structureId, BlockPos pos) {
        Entry entry = entries.get(key(structureId, pos.getX(), pos.getZ()));
        return entry != null && entry.state != State.NEW;
    }

    /** True when the candidate is on record under any state. */
    public boolean isKnown(String structureId, BlockPos pos) {
        return entries.containsKey(key(structureId, pos.getX(), pos.getZ()));
    }

    public List<Entry> entries() {
        return new ArrayList<Entry>(entries.values());
    }

    /** Remembers a suggestion as NEW; an existing entry keeps its state. */
    public void remember(String structureId, BlockPos pos) {
        String key = key(structureId, pos.getX(), pos.getZ());
        if (entries.containsKey(key)) {
            return;
        }
        Entry entry = new Entry(structureId, pos, State.NEW);
        entries.put(key, entry);
        append(entry);
    }

    /**
     * Settles every NEW candidate of {@code structureId} the player is standing next to.
     *
     * @param confirmedPositions structures of this id that are actually known to exist; a candidate
     *                           within {@code confirmRadius} of one becomes FOUND, otherwise MISSING
     */
    public void settleNear(String structureId, BlockPos playerPos, int nearRadius,
                           List<BlockPos> confirmedPositions, int confirmRadius) {
        double nearSq = (double) nearRadius * (double) nearRadius;
        double confirmSq = (double) confirmRadius * (double) confirmRadius;
        List<Entry> changed = new ArrayList<Entry>();
        synchronized (entries) {
            for (Entry entry : new ArrayList<Entry>(entries.values())) {
                if (entry.state != State.NEW || !entry.structureId.equalsIgnoreCase(structureId)) {
                    continue;
                }
                if (distanceSq(entry.pos, playerPos) > nearSq) {
                    continue;
                }
                boolean found = false;
                for (BlockPos confirmed : confirmedPositions) {
                    if (distanceSq(confirmed, entry.pos) <= confirmSq) {
                        found = true;
                        break;
                    }
                }
                Entry updated = new Entry(entry.structureId, entry.pos,
                        found ? State.FOUND : State.MISSING);
                entries.put(key(entry.structureId, entry.pos.getX(), entry.pos.getZ()), updated);
                changed.add(updated);
            }
        }
        for (Entry entry : changed) {
            append(entry);
            LOG.info("[MEMORY] {} at ({}, {}) -> {}", entry.structureId, entry.pos.getX(),
                    entry.pos.getZ(), entry.state);
        }
    }

    // ------------------------------------------------------------------ storage

    private void load() {
        if (file == null || !file.isFile()) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                String[] parts = line.split("\t");
                if (parts.length != 5) {
                    continue;
                }
                try {
                    State state = State.valueOf(parts[4]);
                    BlockPos pos = new BlockPos(Integer.parseInt(parts[1]),
                            Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
                    entries.put(key(parts[0], pos.getX(), pos.getZ()),
                            new Entry(parts[0], pos, state));
                } catch (IllegalArgumentException ignored) {
                    // Malformed line: skip it rather than lose the rest.
                }
            }
        } catch (Throwable t) {
            LOG.warn("[MEMORY] could not read {}: {}", file.getName(), t.toString());
        }
    }

    private void append(Entry entry) {
        if (file == null) {
            return;
        }
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                return;
            }
            PrintWriter writer = new PrintWriter(new java.io.OutputStreamWriter(
                    new java.io.FileOutputStream(file, true), StandardCharsets.UTF_8));
            try {
                writer.println(entry.structureId + '\t' + entry.pos.getX() + '\t' + entry.pos.getY()
                        + '\t' + entry.pos.getZ() + '\t' + entry.state.name());
            } finally {
                writer.close();
            }
        } catch (Throwable t) {
            LOG.warn("[MEMORY] could not write {}: {}", file.getName(), t.toString());
        }
    }

    private static File fileFor(World world) {
        if (world == null || world.getSaveHandler() == null) {
            return null;
        }
        File root;
        try {
            root = world.getSaveHandler().getWorldDirectory();
        } catch (Throwable t) {
            return null;
        }
        if (root == null) {
            return null;
        }
        return new File(new File(root, DIRECTORY),
                PREFIX + world.provider.getDimension() + SUFFIX);
    }

    private static String key(String structureId, int x, int z) {
        return structureId.toLowerCase(java.util.Locale.ROOT) + '@' + x + ',' + z;
    }

    private static double distanceSq(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    /** Distinct structure ids the player has already dealt with; used for diagnostics. */
    public Set<String> settledIds() {
        Set<String> ids = new HashSet<String>();
        for (Entry entry : entries.values()) {
            if (entry.state != State.NEW) {
                ids.add(entry.structureId);
            }
        }
        return ids;
    }

    /** Player override from the warehouse screen; the last write wins in the TSV. */
    public void setState(String structureId, BlockPos pos, State state) {
        String key = key(structureId, pos.getX(), pos.getZ());
        synchronized (entries) {
            Entry existing = entries.get(key);
            if (existing != null && existing.state == state) {
                return;
            }
            Entry updated = new Entry(structureId, pos, state);
            entries.put(key, updated);
            append(updated);
        }
        LOG.info("[MEMORY] {} at ({}, {}) set to {}", structureId, pos.getX(), pos.getZ(), state);
    }

    /** Every entry, ordered so the warehouse list is stable between openings. */
    public List<Entry> sortedEntries() {
        List<Entry> out;
        synchronized (entries) {
            out = new ArrayList<Entry>(entries.values());
        }
        java.util.Collections.sort(out, new java.util.Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                int byId = a.structureId.compareToIgnoreCase(b.structureId);
                if (byId != 0) {
                    return byId;
                }
                int byX = Integer.compare(a.pos.getX(), b.pos.getX());
                return byX != 0 ? byX : Integer.compare(a.pos.getZ(), b.pos.getZ());
            }
        });
        return out;
    }
}
