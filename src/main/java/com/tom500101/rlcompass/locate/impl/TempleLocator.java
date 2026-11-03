package com.tom500101.rlcompass.locate.impl;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.gen.structure.MapGenStructure;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.tom500101.rlcompass.locate.StructureResult;
import com.tom500101.rlcompass.locate.StructureVariant;

/**
 * {@code minecraft:temple} with its three real flavours.
 *
 * <p>{@code MapGenScatteredFeature} decides the flavour from the biome alone: its {@code Start}
 * constructor branches on {@code DESERT}/{@code DESERT_HILLS}, {@code JUNGLE}/{@code JUNGLE_HILLS}
 * and {@code SWAMPLAND}. Reading the same biome back therefore cannot disagree with what was built —
 * no random replay, no block fingerprint.
 *
 * <p>{@code getNearestStructurePos} only ever returns one structure, so a typed search walks
 * outward: find the nearest, check its biome, and if it is the wrong flavour resume the search from
 * a point past it.
 */
public class TempleLocator extends MapGenLocator {

    private static final Logger LOG = LogManager.getLogger("rlcompass");

    public static final String ID = "minecraft:temple";

    private static final int MAX_HOPS = 12;
    /** Structures are at most ~32 blocks wide; stepping past one must clear that. */
    private static final int HOP_DISTANCE = 48;

    public TempleLocator(String srgFieldName, String mcpFieldName) {
        super(srgFieldName, mcpFieldName, ID);
    }

    @Override
    public List<StructureVariant> getVariants(World world) {
        List<StructureVariant> out = new ArrayList<StructureVariant>();
        out.add(new StructureVariant(ID + "/desert", ID, "Desert Temple",
                StructureVariant.VariantSource.RUNTIME_API));
        out.add(new StructureVariant(ID + "/jungle", ID, "Jungle Temple",
                StructureVariant.VariantSource.RUNTIME_API));
        out.add(new StructureVariant(ID + "/witch_hut", ID, "Witch Hut",
                StructureVariant.VariantSource.RUNTIME_API));
        return out;
    }

    @Override
    public StructureResult findNearest(World world, BlockPos playerPos, String structureId, int radius,
                                       String variantId) {
        if (variantId == null || variantId.isEmpty()) {
            return findNearest(world, playerPos, structureId, radius);
        }
        String wanted = suffix(variantId);
        StructureResult result = nearestOfType(world, playerPos, radius, wanted);
        return result == null ? null : result.withVariant(variantId, true);
    }

    private StructureResult nearestOfType(World world, BlockPos playerPos, int radius, String wanted) {
        MapGenStructure generator = resolveGenerator(world);
        if (generator == null) {
            return null;
        }
        double maxDistanceSq = (double) radius * (double) radius;
        BlockPos from = playerPos;
        for (int hop = 0; hop < MAX_HOPS; hop++) {
            BlockPos found = generator.getNearestStructurePos(world, from, false);
            if (found == null || StructureResult.horizontalDistanceSq(found, playerPos) > maxDistanceSq) {
                return null;
            }
            String type = typeAt(world, found);
            if (wanted.equals(type)) {
                LOG.debug("[TEMPLE] {} at {} after {} hop(s)", wanted, found, hop);
                return StructureResult.of(ID, found, sourceName(), playerPos);
            }
            int dx = Integer.signum(found.getX() - playerPos.getX());
            int dz = Integer.signum(found.getZ() - playerPos.getZ());
            if (dx == 0 && dz == 0) {
                return null;
            }
            from = found.add(dx * HOP_DISTANCE, 0, dz * HOP_DISTANCE);
        }
        return null;
    }

    /** Mirrors {@code MapGenScatteredFeature.Start}: desert, jungle or swamp. */
    private static String typeAt(World world, BlockPos pos) {
        Biome biome = world.getBiome(pos);
        ResourceLocation name = biome == null ? null : biome.getRegistryName();
        String path = name == null ? "" : name.toString();
        int colon = path.indexOf(':');
        if (colon >= 0) {
            path = path.substring(colon + 1);
        }
        if (path.contains("desert")) {
            return "desert";
        }
        if (path.contains("jungle")) {
            return "jungle";
        }
        if (path.contains("swamp")) {
            return "witch_hut";
        }
        return null;
    }

    private static String suffix(String variantId) {
        int slash = variantId.lastIndexOf('/');
        return (slash < 0 ? variantId : variantId.substring(slash + 1)).toLowerCase(java.util.Locale.ROOT);
    }
}
