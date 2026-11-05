package com.tom500101.rlcompass.locate.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.tom500101.rlcompass.cache.ModdedStructureRegionScanner;
import com.tom500101.rlcompass.locate.IStructureLocator;
import com.tom500101.rlcompass.locate.StructureResult;
import com.tom500101.rlcompass.locate.StructureVariant;
import com.tom500101.rlcompass.util.Reflect;
import com.tom500101.rlcompass.util.StructureNameUtil;

/**
 * Locates Ice and Fire dragon roosts and dragon caves from the blocks already written into the save.
 *
 * <p>Ice and Fire 1.7.1 (the version RLCraft 2.9.3 ships, via the RLCraft Edition fork) generates
 * both from {@code StructureGenerator.generate}: a per-chunk {@code Random.nextInt(chance)} plus a
 * biome and spawn-distance check. It keeps no position list, no {@code MapGenStructure} and no
 * {@code WorldSavedData}, and the random stream is shared with every other feature in the chunk, so
 * the decision cannot be replayed the way {@link MapGenLocator} replays a vanilla grid. The blocks
 * on disk are the only durable record.
 *
 * <p>This version has <b>no lightning dragons</b> — {@code IceAndFireConfig} has no lightning field
 * and the jar contains no lightning class — so only fire and ice are offered.
 *
 * <h2>Roost versus cave</h2>
 * Both are spheres of the same material ({@code chared_*} for fire, {@code frozen_*} for ice) and
 * both drop the same piles, so neither a Y span nor a block count separates them. The difference is
 * the inside: the cave generator fills a ball with stone, hollows the inner ellipsoid to air and
 * then paints the shell, so its per-level histogram has a run of empty levels between an upper and
 * a lower shell; the roost paints a solid cap down from the surface and its histogram is
 * continuous. {@link ModdedStructureRegionScanner} decides which one a cluster is by that shape
 * test, and this locator only filters the resulting family.
 */
public class DragonRoostLocator implements IStructureLocator {

    private static final Logger LOG = LogManager.getLogger("rlcompass");

    public static final String ROOST_ID = "rlcompass:dragonroost";
    public static final String CAVE_ID = "rlcompass:dragoncave";
    /** Kept for callers that only know about roosts. */
    public static final String STRUCTURE_ID = ROOST_ID;

    private static final String IAF_MAIN_CLASS = "com.github.alexthe666.iceandfire.IceAndFire";

    private static boolean warnedMissing;

    @Override
    public Collection<String> getSupportedIds() {
        return Arrays.asList(ROOST_ID, CAVE_ID);
    }

    @Override
    public String sourceName() {
        return "IceAndFireScan";
    }

    /**
     * Offered whenever Ice and Fire is installed (or the save already holds the matching blocks),
     * even if the current scan found nothing: a structure the player has not walked past yet is not
     * on disk, and hiding the row would make the compass look like it does not support it at all.
     */
    @Override
    public boolean canAnswer(World world, String id) {
        String family = familyFor(StructureNameUtil.canonical(id));
        if (family == null) {
            return false;
        }
        if (world == null) {
            return true;
        }
        if (Reflect.findClass(IAF_MAIN_CLASS) != null) {
            return true;
        }
        for (ModdedStructureRegionScanner.Hit hit : ModdedStructureRegionScanner.peek(world)) {
            if (family.equals(hit.family)) {
                return true;
            }
        }
        if (!warnedMissing) {
            warnedMissing = true;
            LOG.info("[IAF] Ice and Fire is not installed; {} / {} stay unavailable", ROOST_ID, CAVE_ID);
        }
        return false;
    }

    /** Maps a registered id to the scanner family it reads; null when the id is not ours. */
    private static String familyFor(String canonicalId) {
        if (ROOST_ID.equals(canonicalId)) {
            return ModdedStructureRegionScanner.FAMILY_ROOST;
        }
        if (CAVE_ID.equals(canonicalId)) {
            return ModdedStructureRegionScanner.FAMILY_CAVE;
        }
        return null;
    }

    @Override
    public StructureResult findNearest(World world, BlockPos playerPos, String structureId, int radius) {
        return nearest(world, playerPos, structureId, radius, null, null);
    }

    @Override
    public StructureResult findNearest(World world, BlockPos playerPos, String structureId, int radius,
                                       String variantId) {
        return nearest(world, playerPos, structureId, radius, variantId, null);
    }

    /**
     * Skips spots already in the warehouse, so a second search advances to the next roost/cave
     * instead of returning the same one.
     */
    @Override
    public StructureResult findNearest(World world, BlockPos playerPos, String structureId, int radius,
                                       String variantId,
                                       com.tom500101.rlcompass.cache.SearchMemory memory) {
        return nearest(world, playerPos, structureId, radius, variantId, memory);
    }

    private StructureResult nearest(World world, BlockPos playerPos, String structureId, int radius,
                                    String variantId,
                                    com.tom500101.rlcompass.cache.SearchMemory memory) {
        String canonicalId = StructureNameUtil.canonical(structureId);
        String family = familyFor(canonicalId);
        if (family == null) {
            return null;
        }
        String wanted = variantId == null ? null : variantSuffix(variantId);
        double maxDistanceSq = (double) radius * (double) radius;
        StructureResult best = null;
        boolean bestConfirmed = false;

        for (ModdedStructureRegionScanner.Hit hit : ModdedStructureRegionScanner.scan(world)) {
            if (!family.equals(hit.family)) {
                continue;
            }
            if (wanted != null && !wanted.equals(hit.kind)) {
                continue;
            }
            if (memory != null && memory.isKnown(canonicalId, hit.pos)) {
                continue;
            }
            double distanceSq = StructureResult.horizontalDistanceSq(hit.pos, playerPos);
            if (distanceSq > maxDistanceSq) {
                continue;
            }
            if (best == null || distanceSq < best.getDistanceSq()) {
                best = new StructureResult(canonicalId, hit.pos, sourceName(), distanceSq);
                bestConfirmed = hit.confirmed;
            }
        }
        if (best == null) {
            return null;
        }
        return variantId == null ? best : best.withVariant(variantId, bestConfirmed);
    }

    @Override
    public List<StructureVariant> getVariants(World world) {
        List<StructureVariant> out = new ArrayList<StructureVariant>();
        out.add(new StructureVariant(ROOST_ID + "/fire", ROOST_ID,
                "Fire Dragon Roost", StructureVariant.VariantSource.RUNTIME_API));
        out.add(new StructureVariant(ROOST_ID + "/ice", ROOST_ID,
                "Ice Dragon Roost", StructureVariant.VariantSource.RUNTIME_API));
        out.add(new StructureVariant(CAVE_ID + "/fire", CAVE_ID,
                "Fire Dragon Cave", StructureVariant.VariantSource.RUNTIME_API));
        out.add(new StructureVariant(CAVE_ID + "/ice", CAVE_ID,
                "Ice Dragon Cave", StructureVariant.VariantSource.RUNTIME_API));
        return out;
    }

    private static String variantSuffix(String variantId) {
        int slash = variantId.lastIndexOf('/');
        return (slash < 0 ? variantId : variantId.substring(slash + 1)).toLowerCase(Locale.ROOT);
    }
}
