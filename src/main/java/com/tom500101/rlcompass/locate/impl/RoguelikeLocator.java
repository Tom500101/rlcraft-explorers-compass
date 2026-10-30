package com.tom500101.rlcompass.locate.impl;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.tom500101.rlcompass.locate.IStructureLocator;
import com.tom500101.rlcompass.locate.StructureResult;
import com.tom500101.rlcompass.util.Reflect;

/**
 * Locates Roguelike Dungeons.
 *
 * <p>The stage-1 probe found {@code DungeonGenerator1_12} has no fields and the mod keeps no
 * {@code WorldSavedData}, which made this look like a feature-block-scanning job. It is not: the mod
 * exposes a pure placement predicate.
 *
 * <pre>
 * DungeonGenerator1_12.generate(...)
 *   └─ new Dungeon(worldEditor).spawnInChunk(random, chunkX, chunkZ)
 *
 * Dungeon.canSpawnInChunk(int chunkX, int chunkZ, WorldEditor)   ← public static, pure
 *   ├─ isSpawnFrequencyHit(chunkX, chunkZ)
 *   └─ isSpawnChanceHit(chunkX, chunkZ)
 * </pre>
 *
 * <p>{@code canSpawnInChunk} hashes the chunk coordinates and touches no world blocks, so walking
 * chunk rings outward is cheap — unlike mineshafts, where every candidate called {@code getBiome}.
 * The reported position is the chunk centre; the mod picks the exact spot inside the chunk with its
 * own random, which we deliberately do not reproduce.
 */
public class RoguelikeLocator implements IStructureLocator {

    private static final Logger LOG = LogManager.getLogger("rlcompass");

    private static final String DUNGEON_CLASS = "greymerk.roguelike.dungeon.Dungeon";
    private static final String WORLD_EDITOR_CLASS = "com.github.fnar.minecraft.WorldEditor1_12";
    /** The predicate's parameter type is the interface, not the 1.12 implementation. */
    private static final String WORLD_EDITOR_INTERFACE = "greymerk.roguelike.worldgen.WorldEditor";

    /** Hard cap so a huge radius cannot stall the server. */
    private static final long CHECK_BUDGET = 200_000L;

    public static final String STRUCTURE_ID = "roguelike:dungeon";

    private boolean resolved;
    private Method canSpawnInChunk;
    private Class<?> dungeonClass;
    private Class<?> worldEditorClass;
    private boolean warnedMissing;
    private boolean loggedFailure;

    @Override
    public Collection<String> getSupportedIds() {
        return Collections.singletonList(STRUCTURE_ID);
    }

    @Override
    public String sourceName() {
        return "Roguelike";
    }

    @Override
    public StructureResult findNearest(World world, BlockPos playerPos, String structureId, int radius) {
        resolve();
        if (canSpawnInChunk == null || worldEditorClass == null) {
            if (!warnedMissing) {
                warnedMissing = true;
                LOG.info("[ROGUELIKE] Roguelike Dungeons is not installed; {} stays unavailable", STRUCTURE_ID);
            }
            return null;
        }
        Object editor = Reflect.newInstance(worldEditorClass, new Class<?>[]{World.class}, world);
        if (editor == null) {
            return null;
        }

        int centerChunkX = playerPos.getX() >> 4;
        int centerChunkZ = playerPos.getZ() >> 4;
        int maxRing = Math.max(1, radius >> 4);
        double maxDistanceSq = (double) radius * (double) radius;
        long budget = CHECK_BUDGET;

        for (int ring = 0; ring <= maxRing; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.abs(dx) != ring && Math.abs(dz) != ring) {
                        continue;
                    }
                    if (--budget < 0) {
                        LOG.warn("[ROGUELIKE] gave up after {} chunk checks (radius {})", CHECK_BUDGET, radius);
                        return null;
                    }
                    int chunkX = centerChunkX + dx;
                    int chunkZ = centerChunkZ + dz;
                    Object hit;
                    try {
                        hit = canSpawnInChunk.invoke(null, chunkX, chunkZ, editor);
                    } catch (Throwable t) {
                        if (!loggedFailure) {
                            loggedFailure = true;
                            Throwable cause = t.getCause() == null ? t : t.getCause();
                            LOG.warn("[ROGUELIKE] canSpawnInChunk threw {}: {}",
                                    cause.getClass().getName(), cause.getMessage());
                        }
                        return null;
                    }
                    if (!(hit instanceof Boolean) || !((Boolean) hit)) {
                        continue;
                    }
                    BlockPos candidate = new BlockPos((chunkX << 4) + 8, 64, (chunkZ << 4) + 8);
                    if (StructureResult.horizontalDistanceSq(candidate, playerPos) > maxDistanceSq) {
                        continue;
                    }
                    return StructureResult.of(STRUCTURE_ID, candidate, sourceName(), playerPos);
                }
            }
        }
        return null;
    }

    private void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        dungeonClass = Reflect.findClass(DUNGEON_CLASS);
        worldEditorClass = Reflect.findClass(WORLD_EDITOR_CLASS);
        Class<?> editorInterface = Reflect.findClass(WORLD_EDITOR_INTERFACE);
        if (dungeonClass == null || worldEditorClass == null || editorInterface == null) {
            return;
        }
        canSpawnInChunk = Reflect.findMethod(dungeonClass, "canSpawnInChunk",
                int.class, int.class, editorInterface);
        if (canSpawnInChunk == null) {
            LOG.warn("[ROGUELIKE] could not resolve Dungeon.canSpawnInChunk(int, int, WorldEditor)");
        }
    }
}
