package com.tom500101.rlcompass.locate.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.tom500101.rlcompass.cache.BattleTowersPositionsFile;
import com.tom500101.rlcompass.cache.BattleTowersRegionScanner;
import com.tom500101.rlcompass.locate.IStructureLocator;
import com.tom500101.rlcompass.locate.StructureResult;
import com.tom500101.rlcompass.util.Reflect;
import com.tom500101.rlcompass.util.StructureNameUtil;

/**
 * Locates Battle Towers from the positions the mod already keeps in memory.
 *
 * <p>The stage-1 probe found no {@code MapGenStructure} behind Battle Towers' {@code IWorldGenerator},
 * so the plan put it on the chunk-scanning cache path. That is unnecessary: the mod maintains a
 * static {@code Map<Integer dimension, WorldHandle> worldMap}, each handle holding an
 * {@code ArrayList<TowerPosition>} loaded from {@code BattletowerPositionsFile.txt}. Reading it is
 * exact — no feature-block guessing, no missed towers.
 *
 * <pre>
 * WorldGenHandler.worldMap (static Map&lt;Integer, WorldHandle&gt;)
 *   └─ WorldHandle.towerPositions : ArrayList&lt;TowerPosition&gt;
 *        └─ TowerPosition { int x, y, z, type; boolean underground }
 * </pre>
 *
 * <p>Another mod can replace the generator and suppress the load hook that fills that map
 * (RLTweaker's {@code Consistent Tower Generation} does exactly this). The positions file on disk is
 * then the only surviving record, so {@link BattleTowersPositionsFile} is read as a second source.
 */
public class BattleTowersLocator implements IStructureLocator {

    private static final Logger LOG = LogManager.getLogger("rlcompass");

    private static final String HANDLER_CLASS = "atomicstryker.battletowers.common.WorldGenHandler";
    public static final String STRUCTURE_ID = "rlcompass:battletower";

    private boolean warnedMissing;

    /** P0: logged once, because canAnswer runs on every picker open, search and tab completion. */
    private static boolean warnedNoData;
    private static boolean warnedReflection;

    @Override
    public Collection<String> getSupportedIds() {
        return Collections.singletonList(STRUCTURE_ID);
    }

    /**
     * P0: Battle Towers only answers when its own {@code worldMap} holds a handle for this
     * dimension. RLTweaker's {@code Consistent Tower Generation} bypasses both the load hook that
     * imports {@code BattletowerPositionsFile.txt} and the generator path that appends new towers,
     * so the map stays empty forever and every search would return {@code null}.
     */
    @Override
    public boolean canAnswer(World world, String id) {
        return STRUCTURE_ID.equals(StructureNameUtil.canonical(id)) && canAnswer(world);
    }

    @Override
    public boolean canAnswer(World world) {
        if (world == null) {
            return true;
        }
        Class<?> handler = Reflect.findClass(HANDLER_CLASS);
        boolean reflectionOk = false;
        if (handler != null) {
            Object rawMap = Reflect.readStatic(handler, "worldMap");
            if (rawMap instanceof Map) {
                reflectionOk = true;
                if (((Map<?, ?>) rawMap).get(world.provider.getDimension()) != null) {
                    return true;
                }
            }
        }
        // P1: the save may still hold a positions file even though the in-memory map is empty.
        if (!BattleTowersPositionsFile.read(world).isEmpty()) {
            return true;
        }
        // P2: the towers are still in the region files even when the mod no longer records them.
        // Only the cached result is consulted here: this runs on every picker open, and a
        // synchronous 64-region scan cost 2-3 s of freeze. The scan itself is warmed at world start.
        if (!BattleTowersRegionScanner.peek(world).isEmpty()) {
            return true;
        }
        BattleTowersRegionScanner.prewarm(world);
        if (handler == null) {
            return false;
        }
        if (!reflectionOk && !warnedReflection) {
            warnedReflection = true;
            LOG.info("[BT] reflection failed on WorldGenHandler.worldMap — "
                    + "BattleTowers may have changed; locator disabled");
        }
        if (!warnedNoData) {
            warnedNoData = true;
            LOG.info("[BT] no position data for dimension {} — another mod may have replaced "
                            + "BattleTowers generation (e.g. RLTweaker Consistent Tower Generation)",
                    world.provider.getDimension());
        }
        return false;
    }

    @Override
    public String sourceName() {
        return "BattleTowers";
    }

    @Override
    public StructureResult findNearest(World world, BlockPos playerPos, String structureId, int radius) {
        List<BlockPos> live = collectFromWorldMap(world);
        List<BlockPos> file = BattleTowersPositionsFile.read(world);
        List<BlockPos> scanned = BattleTowersRegionScanner.scan(world);
        String source;
        if (!scanned.isEmpty()) {
            source = "BattleTowersScan";
        } else if (live.isEmpty()) {
            source = file.isEmpty() ? sourceName() : "BattleTowersFile";
        } else {
            source = file.isEmpty() ? sourceName() : sourceName() + "+BattleTowersFile";
        }
        return StructureResult.nearestOf(STRUCTURE_ID, merge(merge(live, file), scanned),
                playerPos, radius, source);
    }

    /**
     * Every tower known in {@code world}'s dimension: the live map, the on-disk positions file and
     * the region-file scan. Also used by the cache recorder, so the cache sees every source.
     */
    public List<BlockPos> collectPositions(World world) {
        return merge(merge(collectFromWorldMap(world), BattleTowersPositionsFile.read(world)),
                BattleTowersRegionScanner.scan(world));
    }

    private static List<BlockPos> merge(List<BlockPos> live, List<BlockPos> file) {
        if (file.isEmpty()) {
            return live;
        }
        if (live.isEmpty()) {
            return file;
        }
        Set<BlockPos> merged = new LinkedHashSet<BlockPos>(live);
        merged.addAll(file);
        return new ArrayList<BlockPos>(merged);
    }

    /** Towers BattleTowers itself records in {@code WorldGenHandler.worldMap}. */
    private List<BlockPos> collectFromWorldMap(World world) {
        List<BlockPos> out = new ArrayList<BlockPos>();
        Class<?> handler = Reflect.findClass(HANDLER_CLASS);
        if (handler == null) {
            if (!warnedMissing) {
                warnedMissing = true;
                LOG.info("[BT] Battle Towers is not installed; {} stays unavailable", STRUCTURE_ID);
            }
            return out;
        }
        Object rawMap = Reflect.readStatic(handler, "worldMap");
        if (!(rawMap instanceof Map)) {
            return out;
        }
        Object handle = ((Map<?, ?>) rawMap).get(world.provider.getDimension());
        if (handle == null) {
            return out;
        }
        Object rawPositions = Reflect.read(handle, "towerPositions");
        if (!(rawPositions instanceof List)) {
            return out;
        }
        // Copy out first: the mod mutates this list while generating, and it gates access with
        // towerPositionsAccessLock during file flushes.
        Object[] snapshot;
        try {
            snapshot = ((List<?>) rawPositions).toArray();
        } catch (Throwable t) {
            LOG.debug("[BT] could not snapshot tower positions ({})", t.toString());
            return out;
        }
        for (Object position : snapshot) {
            if (position == null) {
                continue;
            }
            int x = Reflect.readInt(position, "x", Integer.MIN_VALUE);
            int z = Reflect.readInt(position, "z", Integer.MIN_VALUE);
            if (x == Integer.MIN_VALUE || z == Integer.MIN_VALUE) {
                continue;
            }
            out.add(new BlockPos(x, Reflect.readInt(position, "y", 64), z));
        }
        return out;
    }
}
