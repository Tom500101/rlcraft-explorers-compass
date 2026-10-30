package com.tom500101.rlcompass.locate.impl;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Collections;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
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
