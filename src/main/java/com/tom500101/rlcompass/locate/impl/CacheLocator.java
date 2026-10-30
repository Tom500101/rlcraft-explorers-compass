package com.tom500101.rlcompass.locate.impl;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import com.tom500101.rlcompass.cache.CacheRecorder;
import com.tom500101.rlcompass.cache.StructureCacheData;
import com.tom500101.rlcompass.locate.IStructureLocator;
import com.tom500101.rlcompass.locate.StructureResult;
import com.tom500101.rlcompass.util.StructureNameUtil;

/**
 * Answers from the persistent {@link StructureCacheData}.
 *
 * <p>Runs at a lower priority than the live adapters so those stay authoritative; this one keeps the
 * compass usable when a mod has not finished loading its own index (Ruins fills its set from a
 * background thread, Battle Towers reads its file on world load).
 *
 * <p>Ids served are discovered from the cache itself on demand — the locator advertises a fixed list
 * because {@link IStructureLocator#getSupportedIds()} is static, but
 * {@link #findNearest(World, BlockPos, String, int)} answers for any id the cache actually holds.
 */
public class CacheLocator implements IStructureLocator {

    @Override
    public Collection<String> getSupportedIds() {
        // Ids the cache is allowed to mirror; keep in sync with the adapters feeding it.
        Set<String> ids = new LinkedHashSet<String>();
        ids.add(BattleTowersLocator.STRUCTURE_ID);
        ids.add(RuinsLocator.STRUCTURE_ID);
        return ids;
    }

    @Override
    public int priority() {
        return 200;
    }

    @Override
    public String sourceName() {
        return "Cache";
    }

    @Override
    public StructureResult findNearest(World world, BlockPos playerPos, String structureId, int radius) {
        StructureCacheData cache = CacheRecorder.get(world);
        if (cache == null) {
            return null;
        }
        String canonical = StructureNameUtil.canonical(structureId);
        BlockPos found = cache.findNearest(world.provider.getDimension(), canonical, playerPos, radius);
        if (found == null) {
            return null;
        }
        return StructureResult.of(canonical, found, sourceName(), playerPos);
    }
}
