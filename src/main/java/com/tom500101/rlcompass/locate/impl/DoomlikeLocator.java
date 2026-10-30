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
 * Locates Doomlike Dungeons.
 *
 * <p>The stage-1 probe found {@code GenerationHandler} carries only static config
 * ({@code frequency}, {@code factor}, {@code minXZ}, {@code mrand}, {@code dimensions}) and no
 * position store, which made this look like a feature-block job. The mod does expose the placement
 * rule, though:
 *
 * <pre>
 * GenerationHandler.isDungeonCenter(World, long worldSeed, int chunkX, int chunkZ, int dimension)
 * </pre>
 *
 * <p>That method is private but side-effect free apart from {@code World#getBiome}, and the mod
 * itself calls it in exactly this way while walking a bounded chunk window.
 *
 * <h2>Cost</h2>
 * Unlike {@link RoguelikeLocator}, every candidate here pays a {@code getBiome} call, which can
 * generate a chunk — the same shape of problem that made vanilla mineshafts take 9 seconds. The check
 * budget is therefore deliberately small; dungeons are dense enough that the nearest one shows up
 * well inside it.
 */
public class DoomlikeLocator implements IStructureLocator {

    private static final Logger LOG = LogManager.getLogger("rlcompass");

    private static final String HANDLER_CLASS = "jaredbgreat.dldungeons.GenerationHandler";

    /** Kept small on purpose — each check may generate a chunk. */
    private static final long CHECK_BUDGET = 20_000L;

    public static final String STRUCTURE_ID = "doomlike:dungeon";

    private boolean resolved;
    private Method isDungeonCenter;
    private Object handler;
    private boolean warnedMissing;

    @Override
    public Collection<String> getSupportedIds() {
        return Collections.singletonList(STRUCTURE_ID);
    }

    @Override
    public String sourceName() {
        return "Doomlike";
    }

    @Override
    public StructureResult findNearest(World world, BlockPos playerPos, String structureId, int radius) {
        resolve();
        if (isDungeonCenter == null || handler == null) {
            if (!warnedMissing) {
                warnedMissing = true;
                LOG.info("[DOOMLIKE] Doomlike Dungeons is not installed; {} stays unavailable", STRUCTURE_ID);
            }
            return null;
        }

        long seed = world.getSeed();
        int dimension = world.provider.getDimension();
        int centerChunkX = playerPos.getX() >> 4;
        int centerChunkZ = playerPos.getZ() >> 4;
        int maxRing = Math.max(1, radius >> 4);
        double maxDistanceSq = (double) radius * (double) radius;
        long budget = CHECK_BUDGET;
        long startedAt = System.currentTimeMillis();

        for (int ring = 0; ring <= maxRing; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.abs(dx) != ring && Math.abs(dz) != ring) {
                        continue;
                    }
                    if (--budget < 0) {
                        LOG.warn("[DOOMLIKE] gave up after {} chunk checks (radius {}, {} ms)",
                                CHECK_BUDGET, radius, System.currentTimeMillis() - startedAt);
                        return null;
                    }
                    int chunkX = centerChunkX + dx;
                    int chunkZ = centerChunkZ + dz;
                    Object hit = Reflect.invoke(handler, isDungeonCenter, world, seed, chunkX, chunkZ, dimension);
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
        Class<?> handlerClass = Reflect.findClass(HANDLER_CLASS);
        if (handlerClass == null) {
            return;
        }
        // GenerationHandler holds no instance state (all config is static), so a fresh instance is
        // equivalent to the one Forge registered.
        handler = Reflect.newInstance(handlerClass, new Class<?>[0]);
        if (handler == null) {
            LOG.warn("[DOOMLIKE] could not instantiate {}", HANDLER_CLASS);
            return;
        }
        isDungeonCenter = Reflect.findMethod(handlerClass, "isDungeonCenter",
                World.class, long.class, int.class, int.class, int.class);
        if (isDungeonCenter == null) {
            LOG.warn("[DOOMLIKE] could not resolve GenerationHandler.isDungeonCenter(...)");
        }
    }
}
