package com.tom500101.rlcompass.locate.impl;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.init.Biomes;
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
 * {@code minecraft:temple} with its four real flavours.
 *
 * <p>{@code MapGenScatteredFeature.Start} decides the flavour from the biome alone: jungle and
 * jungle hills give a jungle pyramid, swampland a witch hut, desert and desert hills a desert
 * pyramid, and ice plains / cold taiga an igloo. Reading the same biome back therefore cannot
 * disagree with what was built — no random replay, no block fingerprint.
 *
 * <p>A typed search cannot reuse {@code getNearestStructurePos} (see
 * {@link MapGenLocator#enumerateCandidates}), so it walks the generator's cell grid instead and
 * returns the nearest candidate whose biome produces the requested flavour.
 */
public class TempleLocator extends MapGenLocator {

    private static final Logger LOG = LogManager.getLogger("rlcompass");

    public static final String ID = "minecraft:temple";

    /** Salt {@code MapGenScatteredFeature.canSpawnStructureAtCoords} passes to setRandomSeed. */
    private static final int SEED_CONSTANT = 14357617;

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
        out.add(new StructureVariant(ID + "/igloo", ID, "Igloo",
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
            if (!wanted.equals(typeAt(world, candidate))) {
                continue;
            }
            LOG.debug("[TEMPLE] {} at {} ({} candidates in range)", wanted, candidate, candidates.size());
            return StructureResult.of(ID, candidate, sourceName(), playerPos);
        }
        LOG.info("[TEMPLE] no {} among {} structures within {} blocks", wanted, candidates.size(), radius);
        return null;
    }

    /**
     * Mirrors {@code MapGenScatteredFeature.Start} exactly: the mod compares biome identity, not
     * names. The name lookup is only a fallback for biome-replacing packs where the constant is a
     * different instance for the same biome.
     */
    private static String typeAt(World world, BlockPos pos) {
        Biome biome = world.getBiome(pos);
        if (biome == null) {
            return null;
        }
        if (biome == Biomes.JUNGLE || biome == Biomes.JUNGLE_HILLS) {
            return "jungle";
        }
        if (biome == Biomes.SWAMPLAND) {
            return "witch_hut";
        }
        if (biome == Biomes.ICE_PLAINS || biome == Biomes.COLD_TAIGA) {
            return "igloo";
        }
        if (biome == Biomes.DESERT || biome == Biomes.DESERT_HILLS) {
            return "desert";
        }
        ResourceLocation name = biome.getRegistryName();
        String path = name == null ? "" : name.toString();
        int colon = path.indexOf(':');
        if (colon >= 0) {
            path = path.substring(colon + 1);
        }
        if (path.contains("jungle")) {
            return "jungle";
        }
        if (path.contains("swamp")) {
            return "witch_hut";
        }
        if (path.contains("ice") || path.contains("cold_taiga") || path.contains("snow")) {
            return "igloo";
        }
        if (path.contains("desert")) {
            return "desert";
        }
        return null;
    }

    private static String suffix(String variantId) {
        int slash = variantId.lastIndexOf('/');
        return (slash < 0 ? variantId : variantId.substring(slash + 1)).toLowerCase(java.util.Locale.ROOT);
    }
}
