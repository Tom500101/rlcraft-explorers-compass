package com.tom500101.rlcompass.locate.impl;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.gen.structure.StructureBoundingBox;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.tom500101.rlcompass.locate.IStructureLocator;
import com.tom500101.rlcompass.locate.StructureResult;
import com.tom500101.rlcompass.util.Reflect;

/**
 * Locates Recurrent Complex structures.
 *
 * <p>The stage-1 probe showed RC replacing the vanilla stronghold/village/temple/monument generators
 * with wrappers, which means those four answer as the <em>vanilla</em> structures — RC's own content
 * (the "Forest Stronghold" style builds) never showed up anywhere.
 *
 * <p>It is reachable after all. RC keeps a {@code WorldSavedData} of everything it has generated:
 *
 * <pre>
 * WorldStructureGenerationData.get(World)            // extends WorldSavedData
 *   └─ entryMap : Map&lt;UUID, Entry&gt;
 *        └─ Entry.getBoundingBox() : StructureBoundingBox
 *             └─ StructureEntry.getStructureID() : String
 * </pre>
 *
 * <p>RC structures are anchored structures generated at fixed predicted positions, so unlike the
 * other mods there is no "search" to do — the store already contains every instance.
 */
public class RecurrentComplexLocator implements IStructureLocator {

    private static final Logger LOG = LogManager.getLogger("rlcompass");

    private static final String DATA_CLASS =
            "ivorius.reccomplex.world.gen.feature.WorldStructureGenerationData";

    public static final String STRUCTURE_ID = "reccomplex:structure";

    private boolean warnedMissing;
    private boolean loggedEmpty;
    private String lastReported;

    @Override
    public Collection<String> getSupportedIds() {
        return Collections.singletonList(STRUCTURE_ID);
    }

    @Override
    public String sourceName() {
        return "RecurrentComplex";
    }

    @Override
    public StructureResult findNearest(World world, BlockPos playerPos, String structureId, int radius) {
        Class<?> dataClass = Reflect.findClass(DATA_CLASS);
        if (dataClass == null) {
            if (!warnedMissing) {
                warnedMissing = true;
                LOG.info("[RC] Recurrent Complex is not installed; {} stays unavailable", STRUCTURE_ID);
            }
            return null;
        }
        java.lang.reflect.Method getData = Reflect.findMethod(dataClass, "get", World.class);
        if (getData == null) {
            if (!loggedEmpty) {
                loggedEmpty = true;
                LOG.info("[RC] cannot resolve WorldStructureGenerationData.get(World)");
            }
            return null;
        }
        Object data;
        try {
            data = getData.invoke(null, world);
        } catch (Throwable t) {
            if (!loggedEmpty) {
                loggedEmpty = true;
                Throwable cause = t.getCause() == null ? t : t.getCause();
                LOG.info("[RC] WorldStructureGenerationData.get(World) threw {}: {}",
                        cause.getClass().getName(), cause.getMessage());
            }
            return null;
        }
        if (data == null) {
            if (!loggedEmpty) {
                loggedEmpty = true;
                LOG.info("[RC] WorldStructureGenerationData.get(World) returned null");
            }
            return null;
        }
        Object rawEntries = Reflect.read(data, "entryMap");
        if (!(rawEntries instanceof Map)) {
            LOG.info("[RC] WorldStructureGenerationData.entryMap not readable ({})", rawEntries);
            return null;
        }
        Map<?, ?> entryMap = (Map<?, ?>) rawEntries;
        if (entryMap.isEmpty()) {
            if (!loggedEmpty) {
                loggedEmpty = true;
                LOG.info("[RC] RC has not generated any structures in dimension {} yet",
                        world.provider.getDimension());
            }
            return null;
        }

        double maxDistanceSq = (double) radius * (double) radius;
        double bestDistanceSq = Double.MAX_VALUE;
        BlockPos best = null;
        String bestLabel = null;

        Object[] entries;
        try {
            entries = entryMap.values().toArray();
        } catch (Throwable t) {
            return null;
        }
        for (Object entry : entries) {
            Object rawBox = Reflect.invoke(entry, "getBoundingBox");
            if (!(rawBox instanceof StructureBoundingBox)) {
                continue;
            }
            StructureBoundingBox box = (StructureBoundingBox) rawBox;
            BlockPos centre = new BlockPos(
                    (box.minX + box.maxX) / 2,
                    (box.minY + box.maxY) / 2,
                    (box.minZ + box.maxZ) / 2);
            double distanceSq = StructureResult.horizontalDistanceSq(centre, playerPos);
            if (distanceSq > maxDistanceSq || distanceSq >= bestDistanceSq) {
                continue;
            }
            bestDistanceSq = distanceSq;
            best = centre;
            Object id = Reflect.invoke(entry, "getStructureID");
            bestLabel = id instanceof String ? (String) id : null;
        }
        if (best == null) {
            return null;
        }
        if (bestLabel != null && !bestLabel.equals(lastReported)) {
            lastReported = bestLabel;
            LOG.info("[RC] nearest structure is '{}' at ({}, {}, {})",
                    bestLabel, best.getX(), best.getY(), best.getZ());
        }
        return new StructureResult(STRUCTURE_ID, best, sourceName(), bestDistanceSq);
    }
}
