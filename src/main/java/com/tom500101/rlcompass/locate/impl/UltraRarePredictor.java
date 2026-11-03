package com.tom500101.rlcompass.locate.impl;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.common.BiomeDictionary;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.tom500101.rlcompass.cache.SearchMemory;

/**
 * Candidate ranking for structures that are too rare to stumble across.
 *
 * <p>Recurrent Complex cannot say where it placed a natural structure: the decision is made from
 * the chunk's decoration random, which is shared with every other decoration step and therefore not
 * reproducible (see {@code docs/HANDOFF.md} §7.1). What <em>is</em> readable from the structure's
 * own pack file is its eligibility: the biome expressions, each with a weight, and the dimension
 * list. This class turns those into an ordered set of places worth walking to.
 *
 * <p>Two tiers:
 *
 * <ol>
 *   <li><b>Weighted biomes</b> — {@code generationBiomes} entries carry a {@code weight}; entries
 *       with a positive weight are the generator's preferred biomes and are offered first, entries
 *       with weight 0 are "allowed but not preferred" and come last. Within a tier the sweep is a
 *       coarse grid (128 blocks) walked outward, skipping anything {@link SearchMemory} already
 *       holds.</li>
 *   <li><b>Vanilla anchor</b> (not implemented yet) — structures that also declare a
 *       {@code vanilla} generation info are placed at vanilla structure positions, which this mod
 *       can enumerate exactly. Wiring those enumerators in here is the next step; until then such
 *       structures fall through to the biome tier.</li>
 * </ol>
 */
final class UltraRarePredictor {

    private static final Logger LOG = LogManager.getLogger("rlcompass");

    /** Sweep resolution in chunks; 8 chunks = 128 blocks. */
    static final int GRID_STEP = 8;

    /** One {@code generationBiomes} entry: an expression plus the generator's weight for it. */
    static final class Filter {
        final String expression;
        final double weight;

        Filter(String expression, double weight) {
            this.expression = expression;
            this.weight = weight;
        }
    }

    private static final Map<String, List<Filter>> FILTERS = new HashMap<String, List<Filter>>();
    private static final Map<String, Boolean> VANILLA = new HashMap<String, Boolean>();
    private static final Map<String, File> FILES = new HashMap<String, File>();

    private UltraRarePredictor() {
    }

    /** True when the pack also declares a vanilla-generation anchor for this structure. */
    static boolean hasVanillaAnchor(String structureId) {
        filters(structureId);
        synchronized (VANILLA) {
            Boolean value = VANILLA.get(structureId);
            return value != null && value.booleanValue();
        }
    }

    /**
     * The nearest unchecked place whose biome the structure accepts, preferring the generator's
     * higher-weight biomes. Returns {@code null} when nothing qualifies inside {@code radius}.
     */
    static BlockPos suggest(World world, BlockPos playerPos, int radius, String structureId,
                            SearchMemory memory) {
        List<Filter> all = filters(structureId);
        if (all.isEmpty()) {
            return null;
        }
        int centerChunkX = playerPos.getX() >> 4;
        int centerChunkZ = playerPos.getZ() >> 4;
        int maxRing = Math.max(1, (radius >> 4) / GRID_STEP + 1);
        double maxDistanceSq = (double) radius * (double) radius;

        // Preferred biomes first: a positive weight means the generator weights this biome up.
        double bestWeight = 0.0;
        for (Filter filter : all) {
            bestWeight = Math.max(bestWeight, filter.weight);
        }
        BlockPos preferred = sweep(world, playerPos, memory, structureId, all, bestWeight > 0.0,
                centerChunkX, centerChunkZ, maxRing, maxDistanceSq);
        if (preferred != null) {
            return preferred;
        }
        return sweep(world, playerPos, memory, structureId, all, false, centerChunkX, centerChunkZ,
                maxRing, maxDistanceSq);
    }

    private static BlockPos sweep(World world, BlockPos playerPos, SearchMemory memory,
                                  String structureId, List<Filter> filters, boolean preferredOnly,
                                  int centerChunkX, int centerChunkZ, int maxRing,
                                  double maxDistanceSq) {
        for (int ring = 0; ring <= maxRing; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.abs(dx) != ring && Math.abs(dz) != ring) {
                        continue;
                    }
                    int chunkX = centerChunkX + dx * GRID_STEP;
                    int chunkZ = centerChunkZ + dz * GRID_STEP;
                    BlockPos candidate = new BlockPos((chunkX << 4) + 8, 64, (chunkZ << 4) + 8);
                    double distanceSq = distanceSq(candidate, playerPos);
                    if (distanceSq > maxDistanceSq) {
                        continue;
                    }
                    if (memory != null && memory.isKnown(structureId, candidate)) {
                        continue;
                    }
                    if (!matches(world.getBiome(candidate), filters, preferredOnly)) {
                        continue;
                    }
                    if (memory != null) {
                        memory.remember(structureId, candidate);
                    }
                    LOG.info("[RC] suggested area for '{}' at ({}, {}){}; not visited", structureId,
                            candidate.getX(), candidate.getZ(), preferredOnly ? " (preferred biome)" : "");
                    return candidate;
                }
            }
        }
        return null;
    }

    private static double distanceSq(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    private static boolean matches(Biome biome, List<Filter> filters, boolean preferredOnly) {
        if (biome == null) {
            return false;
        }
        for (Filter filter : filters) {
            if (preferredOnly && filter.weight <= 0.0) {
                continue;
            }
            if (matchesExpression(biome, filter.expression)) {
                return true;
            }
        }
        return false;
    }

    /** RC's matcher subset that appears in pack files: {@code &}, {@code |}, {@code !}, {@code $TAG}. */
    static boolean matchesExpression(Biome biome, String expression) {
        if (expression == null || expression.trim().isEmpty()) {
            return true;
        }
        for (String alternative : expression.split("\\|")) {
            if (matchesConjunction(biome, alternative)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesConjunction(Biome biome, String expression) {
        boolean any = false;
        for (String raw : expression.split("&")) {
            String token = raw.trim();
            if (token.isEmpty()) {
                continue;
            }
            any = true;
            boolean negate = token.startsWith("!");
            if (negate) {
                token = token.substring(1).trim();
            }
            boolean hit;
            if (token.startsWith("$")) {
                BiomeDictionary.Type type = BiomeDictionary.Type.getType(
                        token.substring(1).toUpperCase(Locale.ROOT));
                hit = type != null && BiomeDictionary.hasType(biome, type);
            } else {
                String path = biome.getRegistryName() == null ? ""
                        : biome.getRegistryName().toString().toLowerCase(Locale.ROOT);
                String wanted = token.toLowerCase(Locale.ROOT);
                hit = path.equals(wanted) || path.endsWith(":" + wanted);
            }
            if (hit == negate) {
                return false;
            }
        }
        return any;
    }

    /** Reads and caches the structure's biome filters, ordered by descending weight. */
    static List<Filter> filters(String structureId) {
        synchronized (FILTERS) {
            List<Filter> cached = FILTERS.get(structureId);
            if (cached != null) {
                return cached;
            }
        }
        List<Filter> out = new ArrayList<Filter>();
        boolean vanilla = false;
        File file = structureFile(structureId);
        if (file != null && file.isFile()) {
            try {
                ZipFile zip = new ZipFile(file);
                try {
                    ZipEntry entry = zip.getEntry("structure.json");
                    if (entry != null) {
                        String json = new String(readAll(zip.getInputStream(entry)),
                                StandardCharsets.UTF_8);
                        vanilla = json.contains("\"vanilla\"");
                        out.addAll(parseBiomeFilters(json));
                    }
                } finally {
                    zip.close();
                }
            } catch (Throwable t) {
                LOG.warn("[RC] could not read generation info of {}: {}", structureId, t.toString());
            }
        }
        Collections.sort(out, new Comparator<Filter>() {
            @Override
            public int compare(Filter a, Filter b) {
                return Double.compare(b.weight, a.weight);
            }
        });
        synchronized (FILTERS) {
            FILTERS.put(structureId, out);
            VANILLA.put(structureId, Boolean.valueOf(vanilla));
        }
        return out;
    }

    /**
     * Pulls {@code {"biomes": "...", "weight": n}} pairs out of the structure JSON. A hand-rolled
     * scan rather than a JSON parser: the file is generated by RC and the shape is fixed, and this
     * avoids adding a dependency to the mod jar.
     */
    private static List<Filter> parseBiomeFilters(String json) {
        List<Filter> out = new ArrayList<Filter>();
        int index = 0;
        while ((index = json.indexOf("\"biomes\"", index)) >= 0) {
            int start = json.indexOf('"', index + 8);
            int end = start < 0 ? -1 : json.indexOf('"', start + 1);
            if (start < 0 || end < 0) {
                break;
            }
            String expression = json.substring(start + 1, end).trim();
            double weight = 0.0;
            int weightAt = json.indexOf("\"weight\"", end);
            int close = json.indexOf('}', end);
            if (weightAt > 0 && (close < 0 || weightAt < close)) {
                int colon = json.indexOf(':', weightAt);
                int comma = json.indexOf(',', colon);
                int stop = comma < 0 ? close : Math.min(comma, close < 0 ? comma : close);
                if (colon > 0 && stop > colon) {
                    try {
                        weight = Double.parseDouble(json.substring(colon + 1, stop).trim());
                    } catch (NumberFormatException ignored) {
                        // Keep the default weight.
                    }
                }
            }
            if (!expression.isEmpty()) {
                out.add(new Filter(expression, weight));
            }
            index = end + 1;
        }
        return out;
    }

    private static byte[] readAll(InputStream stream) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = stream.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static File structureFile(String structureId) {
        synchronized (FILES) {
            if (FILES.isEmpty()) {
                File directory = activeDirectory();
                if (directory != null) {
                    collectFiles(directory, FILES);
                }
            }
            return FILES.get(structureId.toLowerCase(Locale.ROOT));
        }
    }

    private static File activeDirectory() {
        try {
            File configDir = net.minecraftforge.fml.common.Loader.instance().getConfigDir();
            File gameDir = configDir == null ? null : configDir.getParentFile();
            if (gameDir == null) {
                return null;
            }
            File dir = new File(new File(gameDir, "structures"), "active");
            return dir.isDirectory() ? dir : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void collectFiles(File dir, Map<String, File> out) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                collectFiles(file, out);
            } else if (file.getName().toLowerCase(Locale.ROOT).endsWith(".rcst")) {
                String id = file.getName().substring(0, file.getName().length() - 5);
                out.put(id.toLowerCase(Locale.ROOT), file);
            }
        }
    }
}
