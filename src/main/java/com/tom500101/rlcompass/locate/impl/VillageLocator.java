package com.tom500101.rlcompass.locate.impl;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.gen.structure.MapGenStructure;

import com.tom500101.rlcompass.locate.StructureResult;
import com.tom500101.rlcompass.locate.StructureVariant;

/**
 * {@code minecraft:village} with its five visual styles.
 *
 * <p>A village has no explicit "type" field: {@code StructureVillagePieces} swaps its building
 * blocks by biome ({@code getBiomeSpecificBlockState}), so the style is a pure function of the biome
 * the village stands in. Grouping that biome back into the five families below is therefore exact,
 * and needs no random replay.
 */
public class VillageLocator extends MapGenLocator {

    public static final String ID = "minecraft:village";

    /** Salt {@code MapGenVillage.canSpawnStructureAtCoords} passes to setRandomSeed. */
    private static final int SEED_CONSTANT = 10387312;

    public VillageLocator(String srgFieldName, String mcpFieldName) {
        super(srgFieldName, mcpFieldName, ID);
    }

    @Override
    public List<StructureVariant> getVariants(World world) {
        List<StructureVariant> out = new ArrayList<StructureVariant>();
        out.add(new StructureVariant(ID + "/plains", ID, "Plains Village",
                StructureVariant.VariantSource.RUNTIME_API));
        out.add(new StructureVariant(ID + "/desert", ID, "Desert Village",
                StructureVariant.VariantSource.RUNTIME_API));
        out.add(new StructureVariant(ID + "/savanna", ID, "Savanna Village",
                StructureVariant.VariantSource.RUNTIME_API));
        out.add(new StructureVariant(ID + "/taiga", ID, "Taiga Village",
                StructureVariant.VariantSource.RUNTIME_API));
        out.add(new StructureVariant(ID + "/snowy", ID, "Snowy Village",
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
        List<BlockPos> candidates = enumerateCandidates(world, generator, playerPos, radius, SEED_CONSTANT);
        for (BlockPos candidate : candidates) {
            if (wanted.equals(typeAt(world, candidate))) {
                return StructureResult.of(ID, candidate, sourceName(), playerPos);
            }
        }
        return null;
    }

    /**
     * Buckets the village biome into the style it will be built in. Anything outside the four
     * special families falls back to plains, which is also what vanilla builds for its remaining
     * village biomes.
     */
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
        if (path.contains("savanna")) {
            return "savanna";
        }
        if (path.contains("taiga")) {
            return "taiga";
        }
        if (path.contains("ice") || path.contains("snow")) {
            return "snowy";
        }
        return "plains";
    }

    private static String suffix(String variantId) {
        int slash = variantId.lastIndexOf('/');
        return (slash < 0 ? variantId : variantId.substring(slash + 1)).toLowerCase(java.util.Locale.ROOT);
    }
}
