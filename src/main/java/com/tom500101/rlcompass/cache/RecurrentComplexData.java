package com.tom500101.rlcompass.cache;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.tom500101.rlcompass.locate.StructureResult;

/**
 * Recurrent Complex's generated structures, kept somewhere RLCraft cannot erase them.
 *
 * <p>RC records what it places in a per-dimension {@code WorldSavedData}, but on RLCraft that record
 * is destroyed on every load: RLTweaker's {@code RecurrentHandler.RCCleanup} deletes the
 * {@code entries}, {@code checkedChunks} and {@code checkedChunksFinal} tags in
 * {@code WorldEvent.Load}. RC then writes that emptied map back at the next save, so the save file
 * only ever describes <em>the last session's</em> additions.
 *
 * <p>Two consequences, both handled here:
 *
 * <ol>
 *   <li>the live {@code entryMap} is empty, so the save file has to be read directly;</li>
 *   <li>each session overwrites the file with only its own additions, so reading the file alone
 *       still loses everything older. The union is therefore appended to
 *       {@code <world>/rlcompass/rc_structures_<dim>.tsv}, which nobody else touches.</li>
 * </ol>
 */
public final class RecurrentComplexData {

    private static final Logger LOG = LogManager.getLogger("rlcompass");

    private static final String DATA_DIRECTORY = "data";
    private static final String FILE_NAME = "reccomplex-structuredata.dat";
    private static final String CACHE_DIRECTORY = "rlcompass";
    private static final String CACHE_PREFIX = "rc_structures_";
    private static final String CACHE_SUFFIX = ".tsv";
    private static final String ENTRY_STRUCTURE_ID = "structureID";
    private static final String ENTRY_BOUNDING_BOX = "boundingBox";

    /** One generated structure: RC's id plus the centre of the box it occupies. */
    public static final class Record {
        public final String structureId;
        public final BlockPos center;

        public Record(String structureId, BlockPos center) {
            this.structureId = structureId;
            this.center = center;
        }

        String key() {
            return structureId + '\t' + center.getX() + '\t' + center.getY() + '\t' + center.getZ();
        }
    }

    /** Merged view per (world directory + dimension), so two saves never share a list. */
    private static final Map<String, List<Record>> CACHE = new HashMap<String, List<Record>>();
    private static final Map<String, Set<String>> KEYS = new HashMap<String, Set<String>>();
    private static final Set<String> WARNED = new HashSet<String>();

    private RecurrentComplexData() {
    }

    /**
     * Everything known about {@code world}: this mod's own records plus whatever RC's save file still
     * holds. New entries are appended to the cache file, so the list only ever grows.
     */
    public static List<Record> snapshot(World world) {
        String key = cacheKey(world);
        if (key == null) {
            return Collections.emptyList();
        }
        synchronized (CACHE) {
            List<Record> cached = CACHE.get(key);
            if (cached != null) {
                return cached;
            }
        }
        List<Record> records = new ArrayList<Record>();
        Set<String> seen = new HashSet<String>();
        File cacheFile = cacheFile(world);

        int stored = readCache(cacheFile, records, seen);
        List<Record> fromSave = readSaveFile(world);
        int added = 0;
        for (Record record : fromSave) {
            if (seen.add(record.key())) {
                records.add(record);
                added++;
            }
        }
        if (added > 0) {
            appendCache(cacheFile, records.subList(records.size() - added, records.size()));
        }

        synchronized (CACHE) {
            CACHE.put(key, records);
            KEYS.put(key, seen);
        }
        LOG.info("[RC] {} structures known ({} cached + {} from the last session)",
                records.size(), stored, added);
        return records;
    }

    /** Drops every snapshot; used by tests and when the world is reloaded. */
    public static void invalidate() {
        synchronized (CACHE) {
            CACHE.clear();
            KEYS.clear();
            WARNED.clear();
        }
    }

    // -------------------------------------------------------------- save file

    private static List<Record> readSaveFile(World world) {
        File file = saveFile(world);
        if (file == null || !file.isFile()) {
            return Collections.emptyList();
        }
        try {
            NBTTagCompound root = CompressedStreamTools.read(file);
            if (root == null || !root.hasKey("data", 10)) {
                return Collections.emptyList();
            }
            NBTTagCompound data = root.getCompoundTag("data");
            NBTTagList entries = data.getTagList("entries", 10);
            List<Record> out = new ArrayList<Record>(entries.tagCount());
            for (int i = 0; i < entries.tagCount(); i++) {
                NBTTagCompound entry = entries.getCompoundTagAt(i);
                if (!entry.hasKey(ENTRY_STRUCTURE_ID, 8)) {
                    continue;
                }
                int[] box = entry.getIntArray(ENTRY_BOUNDING_BOX);
                if (box.length != 6) {
                    continue;
                }
                BlockPos center = new BlockPos(
                        (box[0] + box[3]) / 2,
                        (box[1] + box[4]) / 2,
                        (box[2] + box[5]) / 2);
                out.add(new Record(entry.getString(ENTRY_STRUCTURE_ID), center));
            }
            return out;
        } catch (Throwable t) {
            warnOnce(file.getAbsolutePath(), "[RC] could not read " + file.getName() + ": " + t);
            return Collections.emptyList();
        }
    }

    private static File saveFile(World world) {
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
        String folder = world.provider.getSaveFolder();
        File base = folder == null ? root : new File(root, folder);
        return new File(new File(base, DATA_DIRECTORY), FILE_NAME);
    }

    // ------------------------------------------------------------ our records

    private static int readCache(File file, List<Record> out, Set<String> seen) {
        if (file == null || !file.isFile()) {
            return 0;
        }
        int read = 0;
        try {
            for (String line : java.nio.file.Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                String[] parts = line.split("\t");
                if (parts.length != 4) {
                    continue;
                }
                try {
                    Record record = new Record(parts[0], new BlockPos(
                            Integer.parseInt(parts[1]),
                            Integer.parseInt(parts[2]),
                            Integer.parseInt(parts[3])));
                    if (seen.add(record.key())) {
                        out.add(record);
                        read++;
                    }
                } catch (NumberFormatException ignored) {
                    // Malformed line: skip it rather than lose the whole cache.
                }
            }
        } catch (Throwable t) {
            warnOnce(file.getAbsolutePath(), "[RC] could not read " + file.getName() + ": " + t);
        }
        return read;
    }

    private static void appendCache(File file, List<Record> records) {
        if (file == null || records.isEmpty()) {
            return;
        }
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                warnOnce(file.getAbsolutePath(), "[RC] cannot create " + parent);
                return;
            }
            PrintWriter writer = new PrintWriter(new java.io.OutputStreamWriter(
                    new java.io.FileOutputStream(file, true), StandardCharsets.UTF_8));
            try {
                for (Record record : records) {
                    writer.println(record.structureId + '\t' + record.center.getX() + '\t'
                            + record.center.getY() + '\t' + record.center.getZ());
                }
            } finally {
                writer.close();
            }
        } catch (Throwable t) {
            warnOnce(file.getAbsolutePath(), "[RC] could not write " + file.getName() + ": " + t);
        }
    }

    private static File cacheFile(World world) {
        File root = worldRoot(world);
        if (root == null) {
            return null;
        }
        return new File(new File(root, CACHE_DIRECTORY),
                CACHE_PREFIX + world.provider.getDimension() + CACHE_SUFFIX);
    }

    private static String cacheKey(World world) {
        File root = worldRoot(world);
        return root == null ? null : root.getAbsolutePath() + '|' + world.provider.getDimension();
    }

    private static File worldRoot(World world) {
        if (world == null || world.getSaveHandler() == null) {
            return null;
        }
        try {
            return world.getSaveHandler().getWorldDirectory();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void warnOnce(String key, String message) {
        synchronized (WARNED) {
            if (WARNED.add(key)) {
                LOG.warn(message);
            }
        }
    }

    /** Convenience for the locator: squared distance is what the search compares. */
    public static double distanceSq(Record record, BlockPos from) {
        return StructureResult.horizontalDistanceSq(record.center, from);
    }
}
