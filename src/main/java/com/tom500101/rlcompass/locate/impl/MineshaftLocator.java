package com.tom500101.rlcompass.locate.impl;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Random;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.gen.structure.MapGenStructure;
import net.minecraftforge.fml.common.ObfuscationReflectionHelper;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.tom500101.rlcompass.locate.StructureResult;

/**
 * Mineshaft locator that avoids vanilla's 9-second spiral.
 *
 * <h2>Why this exists</h2>
 * Vanilla {@code MapGenMineshaft#getNearestStructurePos} walks outward ring by ring for up to 1000
 * chunks and calls {@code canSpawnStructureAtCoords} for every cell. Better Mineshafts overrides
 * that method to call {@code World#getBiome} on each candidate — and {@code getBiome} generates
 * chunks. The stage-1 probe measured the result at <b>9036 ms</b> for a single search, with the
 * server thread blocked.
 *
 * <h2>What it does instead</h2>
 * The same spiral, but the cheap check runs first. Better Mineshafts' override is, in order, a biome
 * filter and then {@code rand.nextDouble() < Configuration.mineshaftSpawnRate}; the roll is ~0.4%
 * likely, so rolling it ourselves on a clone of the generator's {@code Random} rejects 99.6% of
 * candidates <em>before</em> any chunk gets generated. Only survivors are handed to the real method,
 * with the generator's own {@code Random} reseeded to exactly the state vanilla would have left it
 * in, so the answer is bit-for-bit the one vanilla would produce — just far cheaper.
 *
 * <p>If the spawn rate cannot be read (different mod build, plain vanilla mineshafts) this falls
 * back to the inherited search rather than guessing.
 */
public class MineshaftLocator extends MapGenLocator {

    private static final Logger LOG = LogManager.getLogger("rlcompass");

    private static final String BETTER_MINESHAFTS_CONFIG =
            "com.yungnickyoung.minecraft.bettermineshafts.config.Configuration";
    private static final String SPAWN_RATE_FIELD = "mineshaftSpawnRate";

    /** {@code MapGenBase#rand} — the generator's own Random instance. */
    private static final String RAND_SRG = "field_75038_b";
    /** {@code MapGenStructure#canSpawnStructureAtCoords(int, int)}. */
    private static final String CAN_SPAWN_SRG = "func_75047_a";

    private final Random preScreen = new Random();

    private Method canSpawnMethod;
    private Field generatorRandField;
    private Double spawnRate;
    private boolean spawnRateResolved;

    public MineshaftLocator(String srgFieldName, String mcpFieldName, String structureId) {
        super(srgFieldName, mcpFieldName, structureId);
    }

    @Override
    public StructureResult findNearest(World world, BlockPos playerPos, String requestedId, int radius) {
        MapGenStructure generator = resolveGenerator(world);
        if (generator == null) {
            return null;
        }
        Method canSpawn = canSpawnMethod();
        Field randField = generatorRandField();
        Double rate = spawnRate();
        if (canSpawn == null || randField == null || rate == null || rate <= 0.0D) {
            LOG.debug("[MAPGEN] no spawn rate available, using the vanilla mineshaft search");
            return super.findNearest(world, playerPos, requestedId, radius);
        }
        BlockPos found = boundedSearch(world, generator, canSpawn, randField, rate, playerPos, radius);
        if (found == null) {
            return null;
        }
        return StructureResult.of(requestedId, found, sourceName(), playerPos);
    }

    private BlockPos boundedSearch(World world, MapGenStructure generator, Method canSpawn,
                                   Field randField, double spawnRate, BlockPos playerPos, int radius) {
        long worldSeed = world.getSeed();
        int centerChunkX = playerPos.getX() >> 4;
        int centerChunkZ = playerPos.getZ() >> 4;
        int maxRing = Math.max(1, radius >> 4);
        double maxDistanceSq = (double) radius * (double) radius;
        Random generatorRand;
        try {
            generatorRand = (Random) randField.get(generator);
        } catch (Throwable t) {
            LOG.warn("[MAPGEN] cannot read the generator Random, falling back", t);
            return null;
        }
        if (generatorRand == null) {
            return null;
        }

        for (int ring = 0; ring <= maxRing; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    // Only the perimeter of each ring, exactly like the vanilla loop.
                    if (Math.abs(dx) != ring && Math.abs(dz) != ring) {
                        continue;
                    }
                    int chunkX = centerChunkX + dx;
                    int chunkZ = centerChunkZ + dz;
                    long chunkSeed = ((long) chunkX ^ (long) chunkZ) ^ worldSeed;

                    // Cheap reject: reproduce the generator's roll without touching the world.
                    preScreen.setSeed(chunkSeed);
                    preScreen.nextInt();
                    if (preScreen.nextDouble() >= spawnRate) {
                        continue;
                    }

                    // Survivor: leave the generator's Random in the state vanilla would have left it
                    // in, then ask the real implementation (which will repeat the same roll).
                    generatorRand.setSeed(chunkSeed);
                    generatorRand.nextInt();
                    boolean spawns;
                    try {
                        spawns = (Boolean) canSpawn.invoke(generator, chunkX, chunkZ);
                    } catch (Throwable t) {
                        LOG.error("[MAPGEN] canSpawnStructureAtCoords threw at chunk ({}, {})", chunkX, chunkZ, t);
                        return null;
                    }
                    if (!spawns) {
                        continue;
                    }
                    BlockPos candidate = new BlockPos((chunkX << 4) + 8, 64, (chunkZ << 4) + 8);
                    if (StructureResult.horizontalDistanceSq(candidate, playerPos) > maxDistanceSq) {
                        continue;
                    }
                    return candidate;
                }
            }
        }
        return null;
    }

    private Method canSpawnMethod() {
        if (canSpawnMethod != null) {
            return canSpawnMethod;
        }
        try {
            canSpawnMethod = ObfuscationReflectionHelper.findMethod(
                    MapGenStructure.class, CAN_SPAWN_SRG, boolean.class, int.class, int.class);
            return canSpawnMethod;
        } catch (Throwable t) {
            LOG.warn("[MAPGEN] cannot locate canSpawnStructureAtCoords ({})", t.toString());
            return null;
        }
    }

    private Field generatorRandField() {
        if (generatorRandField != null) {
            return generatorRandField;
        }
        try {
            generatorRandField = ObfuscationReflectionHelper.findField(
                    net.minecraft.world.gen.MapGenBase.class, RAND_SRG);
            return generatorRandField;
        } catch (Throwable t) {
            LOG.warn("[MAPGEN] cannot locate MapGenBase.rand ({})", t.toString());
            return null;
        }
    }

    private Double spawnRate() {
        if (spawnRateResolved) {
            return spawnRate;
        }
        spawnRateResolved = true;
        try {
            Class<?> config = Class.forName(BETTER_MINESHAFTS_CONFIG);
            Field field = config.getField(SPAWN_RATE_FIELD);
            Object value = field.get(null);
            if (value instanceof Double) {
                spawnRate = (Double) value;
                LOG.info("[MAPGEN] Better Mineshafts spawn rate = {}", spawnRate);
            }
        } catch (Throwable ignored) {
            LOG.info("[MAPGEN] no Better Mineshafts spawn rate found, vanilla mineshaft search will be used");
        }
        return spawnRate;
    }
}
