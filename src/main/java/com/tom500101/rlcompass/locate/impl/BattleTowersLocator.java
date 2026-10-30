package com.tom500101.rlcompass.locate.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.tom500101.rlcompass.locate.IStructureLocator;
import com.tom500101.rlcompass.locate.StructureResult;
import com.tom500101.rlcompass.util.Reflect;

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
 */
public class BattleTowersLocator implements IStructureLocator {

    private static final Logger LOG = LogManager.getLogger("rlcompass");

    private static final String HANDLER_CLASS = "atomicstryker.battletowers.common.WorldGenHandler";
    public static final String STRUCTURE_ID = "rlcompass:battletower";

    private boolean warnedMissing;

    @Override
    public Collection<String> getSupportedIds() {
        return Collections.singletonList(STRUCTURE_ID);
    }

    @Override
    public String sourceName() {
        return "BattleTowers";
    }

    @Override
    public StructureResult findNearest(World world, BlockPos playerPos, String structureId, int radius) {
        return StructureResult.nearestOf(STRUCTURE_ID, collectPositions(world), playerPos, radius, sourceName());
    }

    /**
     * Every tower the mod knows about in {@code world}'s dimension. Also used by the cache recorder.
     */
    public List<BlockPos> collectPositions(World world) {
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
