package com.tom500101.rlcompass.cache;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.storage.WorldSavedData;

import com.tom500101.rlcompass.locate.StructureResult;

/**
 * Persistent store of known structure positions, keyed by dimension then structure id.
 *
 * <p>This is the plan's stage-4 cache. It exists for structures the compass cannot ask about at
 * query time; the two mods that turned out to expose live data (Battle Towers, Ruins) also feed it
 * so that positions survive a restart even before those mods have finished loading their own files.
 *
 * <p>Storage lives in the overworld's {@code MapStorage}, so one file covers every dimension and
 * entries are tagged with the dimension they belong to.
 */
public class StructureCacheData extends WorldSavedData {

    public static final String DATA_ID = "rlcompass_structure_cache";

    private static final String TAG_ENTRIES = "entries";
    private static final String TAG_DIMENSION = "dim";
    private static final String TAG_ID = "id";
    private static final String TAG_X = "x";
    private static final String TAG_Y = "y";
    private static final String TAG_Z = "z";

    private final Map<Integer, Map<String, List<BlockPos>>> byDimension =
            new HashMap<Integer, Map<String, List<BlockPos>>>();

    /** Required by {@code MapStorage.getOrLoadData}. */
    public StructureCacheData() {
        super(DATA_ID);
    }

    public StructureCacheData(String name) {
        super(name);
    }

    // ------------------------------------------------------------------ mutation

    /** Records a position; no-op when it is already present. */
    public void record(int dimension, String structureId, BlockPos pos) {
        if (structureId == null || pos == null) {
            return;
        }
        put(dimension, structureId, pos);
        markDirty();
    }

    private void put(int dimension, String structureId, BlockPos pos) {
        Map<String, List<BlockPos>> perId = byDimension.get(dimension);
        if (perId == null) {
            perId = new HashMap<String, List<BlockPos>>();
            byDimension.put(dimension, perId);
        }
        List<BlockPos> positions = perId.get(structureId);
        if (positions == null) {
            positions = new ArrayList<BlockPos>();
            perId.put(structureId, positions);
        }
        for (BlockPos existing : positions) {
            if (existing.equals(pos)) {
                return;
            }
        }
        positions.add(pos.toImmutable());
    }

    /** Drops everything. Callers should follow up with {@link #markDirty()}. */
    public void clearAll() {
        byDimension.clear();
    }

    /** Drops every entry belonging to {@code dimension}. */
    public void clearDimension(int dimension) {
        byDimension.remove(dimension);
    }

    // ------------------------------------------------------------------ query

    /** Returns the nearest recorded position of {@code structureId} within {@code radius}. */
    public BlockPos findNearest(int dimension, String structureId, BlockPos from, int radius) {
        Map<String, List<BlockPos>> perId = byDimension.get(dimension);
        if (perId == null) {
            return null;
        }
        List<BlockPos> positions = perId.get(structureId);
        if (positions == null || positions.isEmpty()) {
            return null;
        }
        double maxDistanceSq = (double) radius * (double) radius;
        double bestDistanceSq = Double.MAX_VALUE;
        BlockPos best = null;
        // Copy: the list is mutated by recorders on other ticks.
        for (BlockPos candidate : new ArrayList<BlockPos>(positions)) {
            double distanceSq = StructureResult.horizontalDistanceSq(candidate, from);
            if (distanceSq > maxDistanceSq || distanceSq >= bestDistanceSq) {
                continue;
            }
            bestDistanceSq = distanceSq;
            best = candidate;
        }
        return best;
    }

    /** Structure ids that have at least one entry in {@code dimension}. */
    public List<String> idsFor(int dimension) {
        Map<String, List<BlockPos>> perId = byDimension.get(dimension);
        if (perId == null) {
            return Collections.emptyList();
        }
        return new ArrayList<String>(perId.keySet());
    }

    /** Number of entries for {@code structureId} in {@code dimension}. */
    public int countFor(int dimension, String structureId) {
        Map<String, List<BlockPos>> perId = byDimension.get(dimension);
        if (perId == null) {
            return 0;
        }
        List<BlockPos> positions = perId.get(structureId);
        return positions == null ? 0 : positions.size();
    }

    public int totalEntries() {
        int total = 0;
        for (Map<String, List<BlockPos>> perId : byDimension.values()) {
            for (List<BlockPos> positions : perId.values()) {
                total += positions.size();
            }
        }
        return total;
    }

    // ------------------------------------------------------------------ persistence

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        byDimension.clear();
        NBTTagList entries = nbt.getTagList(TAG_ENTRIES, 10);
        for (int i = 0; i < entries.tagCount(); i++) {
            NBTTagCompound entry = entries.getCompoundTagAt(i);
            String id = entry.getString(TAG_ID);
            if (id.isEmpty()) {
                continue;
            }
            put(entry.getInteger(TAG_DIMENSION), id, new BlockPos(
                    entry.getInteger(TAG_X), entry.getInteger(TAG_Y), entry.getInteger(TAG_Z)));
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound) {
        NBTTagList entries = new NBTTagList();
        for (Map.Entry<Integer, Map<String, List<BlockPos>>> dimension : byDimension.entrySet()) {
            for (Map.Entry<String, List<BlockPos>> perId : dimension.getValue().entrySet()) {
                for (BlockPos pos : perId.getValue()) {
                    NBTTagCompound entry = new NBTTagCompound();
                    entry.setInteger(TAG_DIMENSION, dimension.getKey());
                    entry.setString(TAG_ID, perId.getKey());
                    entry.setInteger(TAG_X, pos.getX());
                    entry.setInteger(TAG_Y, pos.getY());
                    entry.setInteger(TAG_Z, pos.getZ());
                    entries.appendTag(entry);
                }
            }
        }
        compound.setTag(TAG_ENTRIES, entries);
        return compound;
    }
}
