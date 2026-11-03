package com.tom500101.rlcompass.cache;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.tom500101.rlcompass.locate.StructureResult;

/**
 * Reads Recurrent Complex's structure records straight from the save file.
 *
 * <p>RC keeps a per-dimension {@code WorldSavedData} listing everything it has generated, but on
 * RLCraft that list is useless at runtime: RLTweaker's {@code RecurrentHandler.RCCleanup} deletes the
 * {@code entries}, {@code checkedChunks} and {@code checkedChunksFinal} tags on every
 * {@code WorldEvent.Load}. The in-memory map is therefore always empty, and the mod's own data gets
 * rewritten empty at the next save.
 *
 * <p>The file on disk, however, still holds the previous session's entries when the world loads —
 * the wipe only touches memory until something saves. So a snapshot is taken there and kept for the
 * session; structures generated afterwards come from RC's live map and are merged by the locator.
 */
public final class RecurrentComplexData {

    private static final Logger LOG = LogManager.getLogger("rlcompass");

    private static final String DATA_DIRECTORY = "data";
    private static final String FILE_NAME = "reccomplex-structuredata.dat";
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
    }

    /** Keyed by the data file's absolute path, so two saves never share a snapshot. */
    private static final Map<String, List<Record>> CACHE = new HashMap<String, List<Record>>();

    private RecurrentComplexData() {
    }

    /**
     * Snapshot of the entries recorded in {@code world}'s save file. Reads once per file; later calls
     * return the cached list even after the game has overwritten the file.
     */
    public static List<Record> snapshot(World world) {
        File file = dataFile(world);
        if (file == null || !file.isFile()) {
            return Collections.emptyList();
        }
        String key = file.getAbsolutePath();
        synchronized (CACHE) {
            List<Record> cached = CACHE.get(key);
            if (cached != null) {
                return cached;
            }
        }
        List<Record> records;
        try {
            records = read(file);
        } catch (Throwable t) {
            LOG.warn("[RC] could not read {}: {}", file.getName(), t.toString());
            records = Collections.emptyList();
        }
        synchronized (CACHE) {
            CACHE.put(key, records);
        }
        LOG.info("[RC] saved structure records: {} entries from {}", records.size(), file.getParentFile());
        return records;
    }

    /** Drops every snapshot; used by tests and when the world is reloaded. */
    public static void invalidate() {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }

    private static List<Record> read(File file) throws Exception {
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
    }

    private static File dataFile(World world) {
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

    /** Convenience for the locator: squared distance is what the search compares. */
    public static double distanceSq(Record record, BlockPos from) {
        return StructureResult.horizontalDistanceSq(record.center, from);
    }
}
