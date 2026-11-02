package com.tom500101.rlcompass.cache;

import java.util.List;

import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.storage.MapStorage;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.tom500101.rlcompass.locate.impl.BattleTowersLocator;
import com.tom500101.rlcompass.locate.impl.RuinsLocator;

/**
 * Fetches the {@link StructureCacheData} instance for a world and rebuilds it from the mods that
 * expose their own positions.
 */
public final class CacheRecorder {

    private static final Logger LOG = LogManager.getLogger("rlcompass");

    private CacheRecorder() {
    }

    /** Returns the cache, creating and registering it on first use. */
    public static StructureCacheData get(World world) {
        MapStorage storage = storage(world);
        if (storage == null) {
            return null;
        }
        StructureCacheData data = (StructureCacheData) storage.getOrLoadData(
                StructureCacheData.class, StructureCacheData.DATA_ID);
        if (data == null) {
            data = new StructureCacheData(StructureCacheData.DATA_ID);
            storage.setData(StructureCacheData.DATA_ID, data);
        }
        return data;
    }

    /**
     * Replaces the cached entries for {@code world}'s dimension with whatever the mods currently
     * report. Returns the number of positions written, or {@code -1} when the cache is unreachable.
     */
    public static int rebuild(World world) {
        StructureCacheData data = get(world);
        if (data == null) {
            return -1;
        }
        int dimension = world.provider.getDimension();
        // P1: collect first. An empty source (e.g. RLTweaker's Consistent Tower Generation with no
        // positions file) must not wipe a cache that a previous run already populated.
        List<BlockPos> towers = new BattleTowersLocator().collectPositions(world);
        List<BlockPos> ruins = new RuinsLocator().collectPositions(world);
        if (towers.isEmpty() && ruins.isEmpty()) {
            LOG.info("[CACHE] rebuild skipped for dimension {}: no positions from any source; "
                    + "existing cache kept", dimension);
            return 0;
        }
        data.clearDimension(dimension);
        int written = 0;
        for (BlockPos pos : towers) {
            data.record(dimension, BattleTowersLocator.STRUCTURE_ID, pos);
            written++;
        }
        for (BlockPos pos : ruins) {
            data.record(dimension, RuinsLocator.STRUCTURE_ID, pos);
            written++;
        }
        data.markDirty();
        LOG.info("[CACHE] rebuilt dimension {}: {} positions ({} entries total across dimensions)",
                dimension, written, data.totalEntries());
        return written;
    }

    /**
     * Always uses the overworld's map storage so a single file holds every dimension; a per-dimension
     * storage would scatter one compass cache across {@code DIM-1/}, {@code DIM1/}, …
     */
    private static MapStorage storage(World world) {
        if (world == null) {
            return null;
        }
        try {
            MinecraftServer server = world.getMinecraftServer();
            World anchor = server != null ? server.getWorld(0) : world;
            return anchor == null ? null : anchor.getMapStorage();
        } catch (Throwable t) {
            LOG.warn("[CACHE] cannot reach the map storage ({})", t.toString());
            return null;
        }
    }
}
