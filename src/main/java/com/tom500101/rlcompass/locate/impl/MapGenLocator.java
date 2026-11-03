package com.tom500101.rlcompass.locate.impl;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.gen.ChunkGeneratorOverworld;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraft.world.gen.IChunkGenerator;
import net.minecraft.world.gen.structure.MapGenStructure;
import net.minecraftforge.fml.common.ObfuscationReflectionHelper;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.tom500101.rlcompass.locate.IStructureLocator;
import com.tom500101.rlcompass.locate.StructureResult;

/**
 * Locates a structure through a {@link MapGenStructure} held in a {@link ChunkGeneratorOverworld}
 * field — the vanilla overworld generators plus whatever a mod substituted for them through
 * {@code InitMapGenEvent} (Better Mineshafts does exactly that).
 *
 * <p>The generator is resolved from the world on every call rather than captured once, so one
 * locator instance serves every dimension and survives the generator being swapped at world load.
 *
 * <h2>Field names</h2>
 * The SRG name is the primary key ({@code ObfuscationReflectionHelper.findField} maps it to whatever
 * the running environment calls the field). The MCP name is kept as a secondary candidate purely so
 * that a failed mapping degrades into "still works in dev" instead of "works nowhere".
 *
 * <h2>Radius</h2>
 * Generators do not honour the caller's radius — the stage-1 probe had {@code WoodlandMansion}
 * answer a 10000-block query with a target 20641 blocks out — so the filter here is mandatory, not
 * defensive.
 */
public class MapGenLocator implements IStructureLocator {

    private static final Logger LOG = LogManager.getLogger("rlcompass");

    private final String srgFieldName;
    private final String mcpFieldName;
    private final String structureId;

    private Field cachedField;

    public MapGenLocator(String srgFieldName, String mcpFieldName, String structureId) {
        this.srgFieldName = srgFieldName;
        this.mcpFieldName = mcpFieldName;
        this.structureId = structureId;
    }

    @Override
    public Collection<String> getSupportedIds() {
        return Collections.singletonList(structureId);
    }

    @Override
    public String sourceName() {
        return "MapGen[" + mcpFieldName + "]";
    }

    @Override
    public StructureResult findNearest(World world, BlockPos playerPos, String requestedId, int radius) {
        MapGenStructure generator = resolveGenerator(world);
        if (generator == null) {
            return null;
        }
        BlockPos nearest = generator.getNearestStructurePos(world, playerPos, false);
        if (nearest == null) {
            return null;
        }
        StructureResult result = StructureResult.of(
                // Trust the generator's own name so a substitution is reported as what it really is.
                com.tom500101.rlcompass.util.StructureNameUtil.idFor(generator.getStructureName()),
                nearest, sourceName(), playerPos);
        double maxDistanceSq = (double) radius * (double) radius;
        if (result.getDistanceSq() > maxDistanceSq) {
            LOG.debug("[MAPGEN] {} found {} but it is outside the {} block radius",
                    sourceName(), result, radius);
            return null;
        }
        return result;
    }

    /** Returns the live generator for {@code world}, or {@code null} when this world has none. */
    protected MapGenStructure resolveGenerator(World world) {
        if (world == null || !(world.getChunkProvider() instanceof ChunkProviderServer)) {
            return null;
        }
        IChunkGenerator chunkGenerator = ((ChunkProviderServer) world.getChunkProvider()).chunkGenerator;
        if (!(chunkGenerator instanceof ChunkGeneratorOverworld)) {
            return null;
        }
        Field field = field();
        if (field == null) {
            return null;
        }
        try {
            Object value = field.get(chunkGenerator);
            return value instanceof MapGenStructure ? (MapGenStructure) value : null;
        } catch (Throwable t) {
            LOG.error("[MAPGEN] could not read {}.{}", chunkGenerator.getClass().getSimpleName(), mcpFieldName, t);
            return null;
        }
    }

    /**
     * Every structure the generator would place within {@code radius}, nearest first.
     *
     * <p>{@code getNearestStructurePos} cannot be used to walk outward: it derives the cell index
     * from the start position and then snaps the candidate back onto the spacing grid
     * ({@code cell * spacing + rand}), so any start point inside the same cell returns the same
     * structure no matter how far it is nudged. The cell is {@code spacing} chunks wide — 512 blocks
     * at the vanilla default — which is why the old 48-block hop loop never advanced.
     *
     * <p>So the grid is enumerated instead: for every cell covering the radius, reproduce the
     * generator's own candidate ({@code setRandomSeed(cellX, cellZ, salt)} then two {@code nextInt}
     * calls) and keep it when its biome is one the generator accepts. Both halves are vanilla
     * behaviour — {@code canSpawnStructureAtCoords} is exactly "is the cell candidate" plus "is the
     * biome allowed" — so no reflection into the generator is needed.
     *
     * <p>The spacing itself is still read reflectively, but never trusted blindly: it is verified by
     * reproducing a structure {@code getNearestStructurePos} already returned, and brute-forced when
     * that check fails.
     *
     * @param seedConstant per-generator salt: 14357617 for scattered features, 10387312 for villages
     */
    protected List<BlockPos> enumerateCandidates(World world, MapGenStructure generator,
                                                 BlockPos playerPos, int radius, int seedConstant) {
        int spacing = resolveSpacing(world, generator, playerPos, seedConstant);
        int offsetRange = spacing - 8;
        if (offsetRange <= 0) {
            return Collections.emptyList();
        }
        int maxCells = Math.max(1, (radius >> 4) / spacing + 2);
        int centerCellX = Math.floorDiv(playerPos.getX() >> 4, spacing);
        int centerCellZ = Math.floorDiv(playerPos.getZ() >> 4, spacing);
        double maxDistanceSq = (double) radius * (double) radius;

        List<BlockPos> found = new ArrayList<BlockPos>();
        for (int dx = -maxCells; dx <= maxCells; dx++) {
            for (int dz = -maxCells; dz <= maxCells; dz++) {
                int cellX = centerCellX + dx;
                int cellZ = centerCellZ + dz;
                Random random = world.setRandomSeed(cellX, cellZ, seedConstant);
                int chunkX = cellX * spacing + random.nextInt(offsetRange);
                int chunkZ = cellZ * spacing + random.nextInt(offsetRange);
                BlockPos candidate = new BlockPos((chunkX << 4) + 8, 64, (chunkZ << 4) + 8);
                if (StructureResult.horizontalDistanceSq(candidate, playerPos) > maxDistanceSq) {
                    continue;
                }
                if (!acceptsBiome(world.getBiome(candidate))) {
                    continue;
                }
                found.add(candidate);
            }
        }
        Collections.sort(found, new Comparator<BlockPos>() {
            @Override
            public int compare(BlockPos a, BlockPos b) {
                return Double.compare(StructureResult.horizontalDistanceSq(a, playerPos),
                        StructureResult.horizontalDistanceSq(b, playerPos));
            }
        });
        return found;
    }

    /**
     * Whether the generator would build here given this biome. Subclasses mirror their generator's
     * own biome list; the default accepts everything.
     */
    protected boolean acceptsBiome(Biome biome) {
        return true;
    }

    /**
     * The spacing between cells, in chunks. Read reflectively, then verified against a structure the
     * generator itself just reported: replaying its cell must land on the same chunk. That check
     * turns a wrong field name (or a pack that changed the spacing) into a brute-force search
     * instead of a silently empty result.
     */
    private static int resolveSpacing(World world, MapGenStructure generator, BlockPos playerPos,
                                      int seedConstant) {
        int reflected = spacingChunks(generator);
        BlockPos probe;
        try {
            probe = generator.getNearestStructurePos(world, playerPos, false);
        } catch (Throwable t) {
            return reflected;
        }
        if (probe == null) {
            return reflected;
        }
        if (reproduces(probe, reflected, world, seedConstant)) {
            return reflected;
        }
        for (int candidate = 9; candidate <= 128; candidate++) {
            if (candidate != reflected && reproduces(probe, candidate, world, seedConstant)) {
                LOG.warn("[MAPGEN] spacing {} does not reproduce {}; using {} instead",
                        reflected, probe, candidate);
                return candidate;
            }
        }
        LOG.warn("[MAPGEN] no spacing reproduces {}; keeping {} and reporting nothing", probe, reflected);
        return reflected;
    }

    private static boolean reproduces(BlockPos structure, int spacing, World world, int seedConstant) {
        int chunkX = structure.getX() >> 4;
        int chunkZ = structure.getZ() >> 4;
        int cellX = Math.floorDiv(chunkX, spacing);
        int cellZ = Math.floorDiv(chunkZ, spacing);
        Random random = world.setRandomSeed(cellX, cellZ, seedConstant);
        int expectedX = cellX * spacing + random.nextInt(spacing - 8);
        int expectedZ = cellZ * spacing + random.nextInt(spacing - 8);
        return expectedX == chunkX && expectedZ == chunkZ;
    }

    /** The spacing between structure cells, in chunks. Falls back to the vanilla default. */
    private static int spacingChunks(MapGenStructure generator) {
        Class<?> type = generator.getClass();
        synchronized (SPACING_CACHE) {
            Integer cached = SPACING_CACHE.get(type);
            if (cached != null) {
                return cached.intValue();
            }
        }
        int spacing = DEFAULT_SPACING;
        for (String name : SPACING_FIELD_SRG) {
            try {
                Field field = ObfuscationReflectionHelper.findField(type, name);
                Object value = field.get(generator);
                if (value instanceof Number) {
                    spacing = ((Number) value).intValue();
                    break;
                }
            } catch (Throwable ignored) {
                // Try the next name.
            }
        }
        if (spacing == DEFAULT_SPACING) {
            for (String name : SPACING_FIELD_MCP) {
                try {
                    Field field = type.getDeclaredField(name);
                    field.setAccessible(true);
                    Object value = field.get(generator);
                    if (value instanceof Number) {
                        spacing = ((Number) value).intValue();
                        break;
                    }
                } catch (Throwable ignored) {
                    // Try the next name.
                }
            }
        }
        if (spacing <= 8) {
            spacing = DEFAULT_SPACING;
        }
        synchronized (SPACING_CACHE) {
            SPACING_CACHE.put(type, Integer.valueOf(spacing));
        }
        return spacing;
    }

    private static final Map<Class<?>, Integer> SPACING_CACHE = new HashMap<Class<?>, Integer>();
    private static final String[] SPACING_FIELD_SRG = {"field_82669_g", "field_75054_f"};
    private static final String[] SPACING_FIELD_MCP = {
            "maxDistanceBetweenScatteredFeatures", "distance"};
    private static final int DEFAULT_SPACING = 32;

    private Field field() {
        if (cachedField != null) {
            return cachedField;
        }
        try {
            cachedField = ObfuscationReflectionHelper.findField(ChunkGeneratorOverworld.class, srgFieldName);
            return cachedField;
        } catch (Throwable primary) {
            try {
                Field raw = ChunkGeneratorOverworld.class.getDeclaredField(mcpFieldName);
                raw.setAccessible(true);
                cachedField = raw;
                return cachedField;
            } catch (Throwable secondary) {
                LOG.error("[MAPGEN] cannot locate field {} / {} on ChunkGeneratorOverworld",
                        srgFieldName, mcpFieldName, secondary);
                return null;
            }
        }
    }
}
