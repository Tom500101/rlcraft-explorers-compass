package com.tom500101.rlcompass.locate.impl;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.gen.ChunkProviderServer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.tom500101.rlcompass.locate.IStructureLocator;
import com.tom500101.rlcompass.locate.StructureResult;
import com.tom500101.rlcompass.util.Reflect;

/**
 * Locates Lost Cities cities.
 *
 * <p>Lost Cities replaces the whole chunk generator instead of registering a {@code MapGenStructure},
 * so the stage-1 probe could not see it at all — it only showed the dimension (id 111) being loaded
 * and unloaded. The mod does ship a query API though:
 *
 * <pre>
 * ((ChunkProviderServer) world.getChunkProvider()).chunkGenerator
 *   instanceof mcjty.lostcities.api.ILostChunkGenerator
 *     └─ getChunkInfo(chunkX, chunkZ) → ILostChunkInfo.isCity()
 * </pre>
 *
 * <p>The search walks chunk rings outward and stops at the first city chunk, so the answer is the
 * city nearest by Chebyshev distance. {@code getChunkInfo} is a pure computation over the world seed —
 * it does not generate chunks — which is why scanning is affordable here when it was not for
 * mineshafts.
 */
public class LostCitiesLocator implements IStructureLocator {

    private static final Logger LOG = LogManager.getLogger("rlcompass");

    private static final String GENERATOR_INTERFACE = "mcjty.lostcities.api.ILostChunkGenerator";
    private static final String CHUNK_INFO_INTERFACE = "mcjty.lostcities.api.ILostChunkInfo";

    /** Hard cap on getChunkInfo calls per search, so a huge radius can never stall the server. */
    private static final long CHECK_BUDGET = 500_000L;

    public static final String STRUCTURE_ID = "lostcities:city";

    private boolean resolved;
    private Class<?> generatorInterface;
    private Method getChunkInfo;
    private Method isCity;
    private boolean warnedMissing;

    @Override
    public Collection<String> getSupportedIds() {
        return Collections.singletonList(STRUCTURE_ID);
    }

    @Override
    public String sourceName() {
        return "LostCities";
    }

    @Override
    public StructureResult findNearest(World world, BlockPos playerPos, String structureId, int radius) {
        resolve();
        if (generatorInterface == null || getChunkInfo == null || isCity == null) {
            if (!warnedMissing) {
                warnedMissing = true;
                LOG.info("[LOSTCITIES] Lost Cities is not installed; {} stays unavailable", STRUCTURE_ID);
            }
            return null;
        }
        if (!(world.getChunkProvider() instanceof ChunkProviderServer)) {
            return null;
        }
        Object generator = ((ChunkProviderServer) world.getChunkProvider()).chunkGenerator;
        if (!generatorInterface.isInstance(generator)) {
            // Not a Lost Cities dimension; nothing to do here.
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
                        LOG.warn("[LOSTCITIES] gave up after {} chunk checks (radius {})", CHECK_BUDGET, radius);
                        return null;
                    }
                    int chunkX = centerChunkX + dx;
                    int chunkZ = centerChunkZ + dz;
                    Object info = Reflect.invoke(generator, getChunkInfo, chunkX, chunkZ);
                    if (info == null) {
                        continue;
                    }
                    Object city = Reflect.invoke(info, isCity);
                    if (!(city instanceof Boolean) || !((Boolean) city)) {
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
        generatorInterface = Reflect.findClass(GENERATOR_INTERFACE);
        if (generatorInterface == null) {
            return;
        }
        getChunkInfo = Reflect.findMethod(generatorInterface, "getChunkInfo", int.class, int.class);
        Class<?> infoInterface = Reflect.findClass(CHUNK_INFO_INTERFACE);
        isCity = infoInterface == null ? null : Reflect.findMethod(infoInterface, "isCity");
    }
}
