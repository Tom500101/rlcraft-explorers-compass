package com.tom500101.rlcompass.locate.impl;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.biome.Biome;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.tom500101.rlcompass.cache.RecurrentComplexData;
import com.tom500101.rlcompass.cache.SearchMemory;
import com.tom500101.rlcompass.config.CompassConfig;
import com.tom500101.rlcompass.locate.IStructureLocator;
import com.tom500101.rlcompass.locate.StructureResult;
import com.tom500101.rlcompass.network.PacketDeepScanResult;
import com.tom500101.rlcompass.network.PacketHandler;
import com.tom500101.rlcompass.util.Reflect;
import com.tom500101.rlcompass.util.StructureNameUtil;

/**
 * Locates Recurrent Complex's ultrarare structures: Shivaxi's Sky City, the RLCraft 2.9 Author's
 * Hall and P_IceCity.
 *
 * <p>None of them is findable by scanning: they are {@code natural} RC structures in the
 * {@code ultrarare} category ({@code assets/reccomplex/structures/active/categories/ultrarare.rcnc},
 * {@code defaultSpawnChance = 1.5E-4}). RC rolls {@code chance * amountMultiplier} per chunk, where
 * {@code amountMultiplier = min(matchingStructures / 20, 1)}, and then picks uniformly among the
 * structures that match the biome. The two factors cancel: every target lands at
 * {@code 1.5e-4 / 20 = 7.5e-6} per matching chunk before the placement test, far rarer than the
 * "one per 6666 chunks" the tooltip suggests.
 *
 * <p>Targets differ only in RC's registry name and the biome that can hold them: Shivaxi is
 * {@code $SNOWY}, the Author's Hall is {@code MUTATED_FOREST}, P_IceCity is {@code ICE_FLATS}.
 * Records, replay, placement dry-run and the deep scan are shared by all three.
 *
 * <p>Two independent sources, in order of reliability:
 *
 * <ol>
 *   <li><b>RC's own record.</b> {@link RecurrentComplexData#snapshot(World)} merges the save's
 *       {@code reccomplex-structuredata.dat} with this mod's append-only history. A hit here is a
 *       structure that really exists, with its real centre.</li>
 *   <li><b>Deterministic replay.</b> {@code ChunkGeneratorOverworld.populate} seeds its random with
 *       {@code x * k + z * l ^ worldSeed} (k/l derived from {@code new Random(worldSeed)}), and
 *       {@code PopulateChunkEvent.Pre} — where RC plans its structures — fires immediately after,
 *       before any decoration consumes it. RC's own {@code MixingStructureSelector} is then called
 *       through reflection with that exact random, so the category rolls, the weighted pick and the
 *       per-structure {@code nextLong()} seed all come from RC itself rather than from a
 *       re-implementation. The surface position is RC's own formula:
 *       {@code new Random(seed ^ 1298319823120938102L)}, then {@code +8} inside the chunk.</li>
 * </ol>
 *
 * <p>The replay can only say "this chunk was picked for this target"; whether the structure actually
 * fitted (RC requires up to 99% block conformity) is decided at generation time and cannot be
 * answered without reading the terrain. Predicted hits are therefore reported separately from
 * recorded ones.
 *
 * <p>Work is bounded: only chunks whose biome can hold the target are replayed (RC's own biome
 * weight filters the rest), and the scan stops after {@link #MAX_MILLIS} so a search never freezes
 * the server for longer than a normal cache rebuild.
 */
public class RcUltraRareLocator implements IStructureLocator {

    private static final Logger LOG = LogManager.getLogger("rlcompass");

    /**
     * One ultrarare RC structure: the compass id, RC's registry name and the biome it needs.
     *
     * <p>All targets share one replay engine because RC rolls the category first and checks the
     * structure's own biome afterwards; only the id/name/biome filter differ.
     */
    public static final class Target {
        public final String id;
        public final String rcName;
        /**
         * RC's own biome expression, exactly as it appears in {@code structure.json}
         * ({@code $SNOWY}, {@code $SANDY}, {@code $HOT & $DRY & $SANDY}, ...). Empty means any biome.
         */
        public final String biomeExpr;
        /** RC category the structure lives in: {@code ultrarare}, {@code rare}, {@code adventure}. */
        public final String category;

        /** Lazily built {@code ivorius.reccomplex.utils.expression.BiomeExpression}. */
        private Object expression;
        private java.lang.reflect.Method expressionTest;
        private boolean expressionBroken;

        private Target(String id, String rcName, String biomeExpr, String category) {
            this.id = id;
            this.rcName = rcName;
            this.biomeExpr = biomeExpr;
            this.category = category;
        }

        /**
         * Asks RC itself whether this biome can hold the structure.
         *
         * <p>Hand-rolling the expression would be a second parser to keep in sync with RC, and the
         * expressions in RLCraft already use combinations ({@code $HOT & $DRY & $SANDY}). RC's
         * {@code BiomeExpression} is public, so reflection reuses the exact semantics. Every failure
         * path is permissive: a false negative would silently hide structures, a false positive only
         * costs a few replayed chunks.
         */
        boolean matchesBiome(Biome biome) {
            if (biome == null) {
                return false;
            }
            if (biomeExpr == null || biomeExpr.trim().isEmpty()) {
                return true;
            }
            if (!expressionBroken) {
                try {
                    if (expression == null) {
                        Class<?> type = Reflect.findClass(
                                "ivorius.reccomplex.utils.expression.BiomeExpression");
                        if (type == null) {
                            expressionBroken = true;
                        } else {
                            Object built = type.newInstance();
                            java.lang.reflect.Method set = Reflect.findMethod(type,
                                    "setExpression", String.class);
                            java.lang.reflect.Method test = Reflect.findMethod(type,
                                    "test", Object.class);
                            if (set == null || test == null) {
                                expressionBroken = true;
                            } else {
                                set.invoke(built, biomeExpr);
                                expression = built;
                                expressionTest = test;
                            }
                        }
                    }
                    if (expression != null && expressionTest != null) {
                        Object result = expressionTest.invoke(expression, biome);
                        if (result instanceof Boolean) {
                            return ((Boolean) result).booleanValue();
                        }
                    }
                } catch (Throwable t) {
                    expressionBroken = true;
                }
            }
            return looseMatch(biome);
        }

        /** Fallback when RC's expression class cannot be reached: never rejects a matching biome. */
        private boolean looseMatch(Biome biome) {
            String expr = biomeExpr.trim();
            if ("$SNOWY".equalsIgnoreCase(expr)) {
                return isSnowy(biome);
            }
            if (expr.indexOf('$') >= 0 || expr.indexOf(' ') >= 0
                    || expr.indexOf('&') >= 0 || expr.indexOf('|') >= 0) {
                return true;
            }
            net.minecraft.util.ResourceLocation name = biome.getRegistryName();
            if (name == null) {
                return true;
            }
            // 1.12.2's ResourceLocation exposes no stable path getter across mappings; toString()
            // is always "namespace:path".
            String full = name.toString();
            int colon = full.indexOf(':');
            return expr.equalsIgnoreCase(colon >= 0 ? full.substring(colon + 1) : full);
        }

        boolean matchesId(String id) {
            return rcName.equalsIgnoreCase(bareId(id));
        }
    }

    public static final Target SHIVAXI =
            new Target("rlcompass:shivaxi", "Shivaxi", "$SNOWY", "ultrarare");
    public static final Target AUTHOR_HALL = new Target("rlcompass:authorshall",
            "RLCraft_2_9_Update_Build", "MUTATED_FOREST", "ultrarare");
    public static final Target ICE_CITY =
            new Target("rlcompass:icecity", "P_IceCity", "ICE_FLATS", "ultrarare");

    // Rare / adventure RC structures. They share the replay engine; only the category gate, the
    // biome expression and the candidate budget differ.
    public static final Target AERIVETH = new Target("rlcompass:aeriveth",
            "P_Aeriveth", "$HOT & $DRY & $SANDY", "rare");
    public static final Target SANCTUARY = new Target("rlcompass:sanctuary",
            "P_SanctuaryOfAdmys", "$JUNGLE", "rare");
    public static final Target EROLITH = new Target("rlcompass:erolith",
            "P_TempleOfErolith", "$SANDY", "adventure");
    public static final Target PLAINS_CITY = new Target("rlcompass:plainscity",
            "P_PlainsCity", "", "adventure");
    public static final Target MERIDOR = new Target("rlcompass:meridor",
            "P_TownMeridor", "$HILLS", "adventure");
    public static final Target ISLAND_CITY = new Target("rlcompass:islandcity",
            "P_IslandCity", "$OCEAN", "adventure");
    public static final Target FOREST_STRONGHOLD = new Target("rlcompass:foreststronghold",
            "RLCraft_ForestStronghold", "$FOREST", "adventure");

    // End structures. Same replay engine, but the chunk random comes from ChunkGeneratorEnd and the
    // teleport uses the structure's own Y because the islands float above the void.
    public static final Target CRYSTAL_SHRINE = new Target("rlcompass:crystalshrine",
            "RLCraft_End_Crystal_Shrine", "$end", "adventure");
    public static final Target CORRUPTED_ISLAND = new Target("rlcompass:corruptedisland",
            "RLCraft_End_Corrupted_Island", "$end", "rare");
    public static final Target DEFILED_HUT = new Target("rlcompass:defiledhut",
            "RLCraft_End_Defiled_Island", "$end", "rare");

    private static final List<Target> TARGETS = Collections.unmodifiableList(
            java.util.Arrays.asList(SHIVAXI, AUTHOR_HALL, ICE_CITY,
                    AERIVETH, SANCTUARY, EROLITH, PLAINS_CITY, MERIDOR, ISLAND_CITY,
                    FOREST_STRONGHOLD, CRYSTAL_SHRINE, CORRUPTED_ISLAND, DEFILED_HUT));

    /** Resolves a compass id, a bare id or an RC name to a shipped target, or {@code null}. */
    public static Target targetFor(String id) {
        if (id == null) {
            return null;
        }
        String canonical = StructureNameUtil.canonical(id);
        String trimmed = id.trim().toLowerCase(java.util.Locale.ROOT);
        int colon = trimmed.indexOf(':');
        String bare = colon >= 0 ? trimmed.substring(colon + 1) : trimmed;
        for (Target target : TARGETS) {
            if (target.id.equals(canonical) || target.id.endsWith(":" + bare)
                    || target.matchesId(id)) {
                return target;
            }
        }
        return null;
    }

    /** All shipped targets, for commands and diagnostics. */
    public static List<Target> targets() {
        return TARGETS;
    }

    /**
     * How the biome at {@code pos} lines up with the shipped targets, one line per ultra-rare plus
     * a summary for the rest.
     *
     * <p>This answers the question a scan cannot: <em>am I standing in the right place?</em> A run
     * with {@code eligible = 0} means the biome filter never matched, so no radius will help; a run
     * with a healthy {@code eligible} and no candidate is just a missed roll. Without this the two
     * look identical from the chat output.
     */
    public static List<String> biomeReport(World world, BlockPos pos) {
        List<String> lines = new ArrayList<String>();
        if (world == null || pos == null) {
            lines.add("biome: unknown");
            return lines;
        }
        Biome biome = world.getBiome(pos);
        if (biome == null) {
            lines.add("biome: unknown (chunk not generated?)");
            return lines;
        }
        String registry = biome.getRegistryName() == null
                ? "?" : biome.getRegistryName().toString();
        lines.add("biome: " + registry + " (" + biome.getBiomeName() + ")");
        StringBuilder others = new StringBuilder();
        for (Target target : TARGETS) {
            boolean match = target.matchesBiome(biome);
            if ("ultrarare".equalsIgnoreCase(target.category)) {
                int colon = target.id.indexOf(':');
                lines.add("  " + (colon >= 0 ? target.id.substring(colon + 1) : target.id)
                        + ": " + (match ? "MATCH" : "no") + "  [" + target.biomeExpr + "]");
            } else if (match) {
                if (others.length() > 0) {
                    others.append(", ");
                }
                others.append(target.rcName);
            }
        }
        if (others.length() > 0) {
            lines.add("  also matches: " + others);
        }
        return lines;
    }

    private final Target target;

    public RcUltraRareLocator(Target target) {
        this.target = target == null ? SHIVAXI : target;
    }

    /**
     * RC hands out ids in several shapes depending on the code path: the bare file name
     * ({@code Shivaxi}), a namespaced id ({@code reccomplex:Shivaxi}) or with the extension
     * ({@code Shivaxi.rcst}). Comparisons must normalise all three, otherwise every match fails
     * silently — which is exactly what a 0/N replay looks like.
     */
    static String bareId(String id) {
        if (id == null) {
            return "";
        }
        String value = id.trim();
        int colon = value.indexOf(':');
        if (colon >= 0) {
            value = value.substring(colon + 1);
        }
        if (value.toLowerCase(java.util.Locale.ROOT).endsWith(".rcst")) {
            value = value.substring(0, value.length() - 5);
        }
        return value;
    }

    private static final String REGISTRY_CLASS =
            "ivorius.reccomplex.world.gen.feature.structure.StructureRegistry";
    private static final String NATURAL_GENERATION_CLASS =
            "ivorius.reccomplex.world.gen.feature.structure.generic.generation.NaturalGeneration";
    private static final String MIXING_SELECTOR_CLASS =
            "ivorius.reccomplex.world.gen.feature.selector.MixingStructureSelector";
    private static final String BASE_SELECTOR_CLASS =
            "ivorius.reccomplex.world.gen.feature.selector.StructureSelector";
    private static final String NATURAL_SELECTOR_CLASS =
            "ivorius.reccomplex.world.gen.feature.selector.NaturalStructureSelector";

    /** RC's own placement pipeline, used to dry-run the terrain check before reporting a candidate. */
    private static final String STRUCTURE_GENERATOR_CLASS =
            "ivorius.reccomplex.world.gen.feature.StructureGenerator";
    private static final String BLOCK_SURFACE_POS_CLASS = "ivorius.ivtoolkit.blocks.BlockSurfacePos";
    private static final String PLACER_INTERFACE_CLASS =
            "ivorius.reccomplex.world.gen.feature.structure.Placer";
    private static final String GENERATION_TYPE_CLASS =
            "ivorius.reccomplex.world.gen.feature.structure.generic.generation.GenerationType";
    private static final String STRUCTURE_CLASS =
            "ivorius.reccomplex.world.gen.feature.structure.Structure";

    /** 100000 blocks / 16, the largest radius the config and the GUI allow. */
    public static final int MAX_CHUNK_RADIUS = 6250;

    /** RC's salt for the in-chunk surface offset ({@code WorldGenStructures.randomSurfacePos}). */
    private static final long SURFACE_SEED = 1298319823120938102L;

    /**
     * Wall-clock budget for one compass search. The scan itself is cheap now (batched biome
     * sampling), but each candidate costs a placement dry-run that can generate the chunks under
     * the structure, so the budget is deliberately larger than the other locators'.
     */
    private static final long MAX_MILLIS = 8000L;

    /**
     * Conservative RNG prefilter for the generic {@code ultrarare} tile scan.
     *
     * <p>Ice plains covers ~2.6% of the overworld, so an ice-city scan spends most of its time in
     * {@code planChunk}'s reflection call for chunks that cannot hold the target. {@code 1e-3} is
     * ~44x the ultrarare per-chunk chance ({@code 2.25e-5}), so a real selection is always below
     * the threshold; the remaining assumption is that RC's target roll sits inside the first
     * {@link #ULTRARARE_PREFILTER_DRAWS} doubles, which is the same model the fast scanner uses.
     * The prefilter only skips wasted replays — the surviving chunks still go through the full
     * selector, the placement dry-run and the post-generation check.
     */
    private static final int ULTRARARE_PREFILTER_DRAWS = 128;
    private static final double ULTRARARE_PREFILTER_CHANCE = 1.0E-3;
    /**
     * Conservative RNG prefilter for the generic {@code ultrarare} tile scan. Enabled for normal
     * use; set to {@code false} only when measuring the unfiltered path.
     */
    private static final boolean ULTRARARE_PREFILTER_ENABLED = true;

    /** Snowy chunks are rare; without this pre-filter every chunk would pay for a reflection call. */
    private static final float SNOWY_TEMPERATURE = 0.3F;

    /**
     * RC keeps its categories in {@code ArrayListMultimap.create()}, i.e. a plain {@link HashMap}
     * keyed by {@code NaturalStructureSelector.Category}. {@code SimpleCategory} does not override
     * {@code hashCode}, so the key set iterates in <b>identity-hash order</b> — different on every
     * JVM start. Since {@code generatedStructures} walks that key set and spends one
     * {@code nextDouble()} per category, the same chunk draws different categories in different
     * sessions: RC's own worldgen is not reproducible across restarts.
     *
     * <p>Replacing the map with a {@code LinkedHashMultimap} in registry-id order removes that
     * wobble. It also makes the replay match what RC will actually do when the player walks into an
     * unexplored chunk, which is the whole point of the predictor.
     */
    private static final String GUAVA_MULTIMAP_CLASS = "com.google.common.collect.LinkedHashMultimap";
    private static final String CATEGORY_REGISTRY_FIELD = "CATEGORY_REGISTRY";

    private static final long CACHE_TTL_MS = 10 * 60 * 1000L;

    /** Half-width of the chunk window a diagnostic uses to find the chunk that planned a structure. */
    private static final int SEARCH_CHUNKS = 4;

    /** One located candidate. */
    static final class Hit {
        final BlockPos pos;
        /** True when RC's own record proves the structure exists; false for a replay prediction. */
        final boolean recorded;

        Hit(BlockPos pos, boolean recorded) {
            this.pos = pos;
            this.recorded = recorded;
        }
    }

    private static final class Cache {
        final long builtAt;
        final int radius;
        final int centerChunkX;
        final int centerChunkZ;
        final List<Hit> hits;

        Cache(long builtAt, int radius, int centerChunkX, int centerChunkZ, List<Hit> hits) {
            this.builtAt = builtAt;
            this.radius = radius;
            this.centerChunkX = centerChunkX;
            this.centerChunkZ = centerChunkZ;
            this.hits = hits;
        }
    }

    private static final Map<String, Cache> CACHES = new HashMap<String, Cache>();
    private static boolean warnedUnavailable;

    @Override
    public Collection<String> getSupportedIds() {
        return Collections.singletonList(target.id);
    }

    @Override
    public String sourceName() {
        return "RecurrentComplexReplay";
    }

    @Override
    public boolean canAnswer(World world, String id) {
        if (!target.id.equals(StructureNameUtil.canonical(id))) {
            return false;
        }
        return Reflect.findClass(REGISTRY_CLASS) != null;
    }

    @Override
    public StructureResult findNearest(World world, BlockPos playerPos, String structureId, int radius) {
        if (world == null || playerPos == null) {
            return null;
        }
        if (!target.id.equals(StructureNameUtil.canonical(structureId))) {
            return null;
        }
        if (!canAnswer(world, structureId)) {
            if (!warnedUnavailable) {
                warnedUnavailable = true;
                LOG.info("[ULTRARARE] Recurrent Complex is not installed; {} stays unavailable",
                        target.id);
            }
            return null;
        }
        List<Hit> hits = hits(world, playerPos, radius, target);
        Hit best = null;
        double bestDistanceSq = Double.MAX_VALUE;
        double maxDistanceSq = (double) radius * (double) radius;
        for (Hit hit : hits) {
            double distanceSq = StructureResult.horizontalDistanceSq(hit.pos, playerPos);
            if (distanceSq > maxDistanceSq) {
                continue;
            }
            // A recorded structure always outranks a prediction; among equals the nearest wins.
            boolean better;
            if (best == null) {
                better = true;
            } else if (hit.recorded != best.recorded) {
                better = hit.recorded;
            } else {
                better = distanceSq < bestDistanceSq;
            }
            if (better) {
                best = hit;
                bestDistanceSq = distanceSq;
            }
        }
        return best == null ? null
                : StructureResult.of(target.id, best.pos,
                        best.recorded ? "RecurrentComplexRecord" : sourceName(), playerPos);
    }

    /**
     * Nearest position RC actually recorded for this target (or that the warehouse already knows),
     * within {@code radiusBlocks}. A recorded structure is authoritative: re-scanning millions of
     * chunks for something the save already contains only wastes the player's time.
     */
    public static BlockPos knownHit(Target target, World world, BlockPos center, int radiusBlocks) {
        if (target == null || world == null || center == null) {
            return null;
        }
        // Square radius, matching how the deep scan walks (Chebyshev): a structure in the corner of
        // the scanned square is covered by the scan but sits outside a circular radius, so a
        // Euclidean test here would report "nothing recorded" for a scan that really could find it.
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        for (RecurrentComplexData.Record record : RecurrentComplexData.snapshot(world)) {
            if (!target.matchesId(record.structureId)) {
                continue;
            }
            if (Math.abs(record.center.getX() - center.getX()) > radiusBlocks
                    || Math.abs(record.center.getZ() - center.getZ()) > radiusBlocks) {
                continue;
            }
            double sq = StructureResult.horizontalDistanceSq(record.center, center);
            if (sq < bestSq) {
                bestSq = sq;
                best = record.center;
            }
        }
        SearchMemory memory = SearchMemory.get(world);
        if (memory != null) {
            for (SearchMemory.Entry entry : memory.entries()) {
                boolean matches = target.matchesId(entry.structureId)
                        || target.id.equals(StructureNameUtil.canonical(entry.structureId));
                if (!matches || entry.state == SearchMemory.State.MISSING) {
                    continue;
                }
                if (Math.abs(entry.pos.getX() - center.getX()) > radiusBlocks
                        || Math.abs(entry.pos.getZ() - center.getZ()) > radiusBlocks) {
                    continue;
                }
                double sq = StructureResult.horizontalDistanceSq(entry.pos, center);
                if (sq < bestSq) {
                    bestSq = sq;
                    best = entry.pos;
                }
            }
        }
        return best;
    }

    /** Recorded hits are authoritative; predictions fill the gaps. Both de-duplicated by chunk. */
    private static List<Hit> hits(World world, BlockPos center, int radius, Target target) {
        int centerChunkX = center.getX() >> 4;
        int centerChunkZ = center.getZ() >> 4;
        String key = (world.getSaveHandler() == null
                ? world.getWorldInfo().getWorldName() + '|' + world.provider.getDimension()
                : world.getSaveHandler().getWorldDirectory().getAbsolutePath()
                        + '|' + world.provider.getDimension())
                + '|' + target.id;
        long now = System.currentTimeMillis();
        synchronized (CACHES) {
            Cache cached = CACHES.get(key);
            // Reuse while the player stays in the middle half of the scanned square.
            if (cached != null && cached.radius >= radius && now - cached.builtAt < CACHE_TTL_MS
                    && Math.abs(cached.centerChunkX - centerChunkX) <= cached.radius / 32
                    && Math.abs(cached.centerChunkZ - centerChunkZ) <= cached.radius / 32) {
                return cached.hits;
            }
        }

        long startedAt = System.currentTimeMillis();
        List<Hit> hits = new ArrayList<Hit>();

        // 1. RC's own records: these are structures that really exist.
        for (RecurrentComplexData.Record record : RecurrentComplexData.snapshot(world)) {
            if (!target.matchesId(record.structureId)) {
                continue;
            }
            hits.add(new Hit(record.center, true));
        }

        // 1b. Warehouse entries: a deep-scan candidate survives a restart there even when the
        //     in-memory DEEP_HITS list is gone. MISSING means "checked, nothing there" and is skipped.
        SearchMemory memory = SearchMemory.get(world);
        if (memory != null) {
            for (SearchMemory.Entry entry : memory.entries()) {
                // The warehouse stores the compass id (rlcompass:authorshall), while RC records use
                // its registry name (RLCraft_2_9_Update_Build); accept both shapes.
                boolean matches = target.matchesId(entry.structureId)
                        || target.id.equals(StructureNameUtil.canonical(entry.structureId));
                if (!matches || entry.state == SearchMemory.State.MISSING) {
                    continue;
                }
                hits.add(new Hit(entry.pos, entry.state == SearchMemory.State.FOUND));
            }
        }

        // 2. Verified candidates left behind by the last deep scan. They cost nothing to reuse and
        //    stop a compass search from re-scanning ground the player already paid minutes for.
        hits.addAll(new ArrayList<Hit>(deepHits(target.id)));

        // 3. Deterministic replay over chunks whose biome can hold this structure.
        Replay replay = Replay.create(world);
        if (replay != null) {
            int predicted = replay.scan(hits, centerChunkX, centerChunkZ, radius, target);
            LOG.info("[ULTRARARE] replay scanned {} eligible chunks, predicted {} candidate(s) ({} ms)",
                    replay.scannedChunks, predicted, System.currentTimeMillis() - startedAt);
        }

        // Recorded hits come first, so a prediction within one chunk of a known structure is dropped.
        List<Hit> deduped = new ArrayList<Hit>();
        for (Hit hit : hits) {
            boolean duplicate = false;
            for (Hit kept : deduped) {
                if (Math.abs(kept.pos.getX() - hit.pos.getX()) <= 16
                        && Math.abs(kept.pos.getZ() - hit.pos.getZ()) <= 16) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                deduped.add(hit);
            }
        }

        List<Hit> result = Collections.unmodifiableList(deduped);
        synchronized (CACHES) {
            CACHES.put(key, new Cache(now, radius, centerChunkX, centerChunkZ, result));
        }
        LOG.info("[ULTRARARE] {} hit(s) for {} ({} recorded, {} ms)", result.size(), target.rcName,
                countRecorded(result), System.currentTimeMillis() - startedAt);
        return result;
    }

    private static int countRecorded(List<Hit> hits) {
        int count = 0;
        for (Hit hit : hits) {
            if (hit.recorded) {
                count++;
            }
        }
        return count;
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    /** Drops cached predictions; called when the player changes dimension or the save reloads. */
    public static void invalidate() {
        synchronized (CACHES) {
            CACHES.clear();
        }
    }

    /**
     * Reflection bridge to RC's selector. Everything below is RC's own code — the replay only
     * supplies the chunk random and reads the answer, so category rolls, weights and the
     * per-structure seed stay in sync with the mod even if its internals change.
     */
    private static final class Replay {

        private final World world;
        private final long worldSeed;
        private final long k;
        private final long l;
        /**
         * The End seeds its chunk random differently: {@code ChunkGeneratorEnd} uses
         * {@code x * 341873128712L + z * 132897987541L} with no world seed at all.
         */
        private final boolean endDimension;

        /** {@link #placementY} sentinel: the dry-run could not decide, so the candidate stays unverified. */
        static final int Y_UNKNOWN = Integer.MIN_VALUE;
        private final ChunkPos spawnChunk;
        private final Object selectorCache;
        private final Class<?> registryClass;
        private final Object registry;
        private final Method selectorGet;
        private final Method generatedStructures;
        private final Method structureId;
        private final Method pairLeft;
        private final Method pairRight;
        /** {@code StructureSelector.weightedStructureInfos}, the map whose key order decides draws. */
        private final Field weightsField;
        /**
         * {@code StructureSelector.totalWeights}, the trove map {@code amountMultiplier} reads.
         * Pinning only rewrites {@code weightedStructureInfos}, so if this map holds zero for a
         * category every {@code structuresInBiome} call returns 0 and the replay plans nothing.
         */
        private final Field totalWeightsField;
        /** One-shot diagnostic so the first selector a scan touches reports its weights. */
        private boolean selectorProbed;
        private final Object categoryRegistry;
        private final Method categoryId;
        /** Selectors already rewritten to the deterministic order; identity, one per biome. */
        private final Set<Object> pinnedSelectors =
                Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        /** Rebuilt category maps, keyed by biome and ordering, so a diagnostic does not rebuild them. */
        private final Map<String, Object> permutedMaps = new HashMap<String, Object>();
        /** One selector per biome, so the hot path never touches reflection twice. */
        private final Map<Biome, Object> selectorByBiome = new IdentityHashMap<Biome, Object>();
        /** Cached "this biome carries category X" answers, keyed by category first. */
        private final Map<String, Map<Biome, Boolean>> categoryByBiome =
                new HashMap<String, Map<Biome, Boolean>>();

        int scannedChunks;

        private Replay(World world, long worldSeed, long k, long l, ChunkPos spawnChunk,
                       Object selectorCache, Class<?> registryClass, Object registry,
                       Method selectorGet, Method generatedStructures, Method structureId,
                       Method pairLeft, Method pairRight) {
            this.world = world;
            this.worldSeed = worldSeed;
            this.endDimension = world != null && world.provider != null
                    && world.provider.getDimension() == 1;
            this.k = k;
            this.l = l;
            this.spawnChunk = spawnChunk;
            this.selectorCache = selectorCache;
            this.registryClass = registryClass;
            this.registry = registry;
            this.selectorGet = selectorGet;
            this.generatedStructures = generatedStructures;
            this.structureId = structureId;
            this.pairLeft = pairLeft;
            this.pairRight = pairRight;
            // The field lives on StructureSelector, not on CachedStructureSelectors — looking it up
            // on the cache class silently returned null and made the pinning a no-op.
            Class<?> selectorClass = Reflect.findClass(BASE_SELECTOR_CLASS);
            this.weightsField = selectorClass == null ? null
                    : Reflect.findField(selectorClass, "weightedStructureInfos");
            this.totalWeightsField = selectorClass == null ? null
                    : Reflect.findField(selectorClass, "totalWeights");
            Class<?> categoryRegistryClass = Reflect.findClass(
                    "ivorius.reccomplex.files.SimpleLeveledRegistry");
            // CATEGORY_REGISTRY lives on NaturalStructureSelector, not on NaturalGeneration. Reading
            // it from the wrong class silently fell back to identity-hash names, which made the
            // "pinned" order vary between sessions again.
            this.categoryRegistry = Reflect.readStatic(
                    Reflect.findClass(NATURAL_SELECTOR_CLASS), CATEGORY_REGISTRY_FIELD);
            this.categoryId = categoryRegistryClass == null ? null
                    : Reflect.findMethod(categoryRegistryClass, "id", Object.class);
        }

        static Replay create(World world) {
            Class<?> registryClass = Reflect.findClass(REGISTRY_CLASS);
            Class<?> naturalClass = Reflect.findClass(NATURAL_GENERATION_CLASS);
            if (registryClass == null || naturalClass == null) {
                return null;
            }
            Object registry = Reflect.readStatic(registryClass, "INSTANCE");
            if (registry == null) {
                return null;
            }
            Object selectorCache = Reflect.invokeStatic(naturalClass, "selectors",
                    new Class<?>[]{registryClass}, registry);
            if (selectorCache == null) {
                return null;
            }
            // RC caches one selector per (biome, dimension). If anything asked for a selector before
            // the structure registry finished loading, that empty selector stays cached for the rest
            // of the session and every replay sees "no structures planned". Clearing once here is
            // what turns a silently empty replay into a real one.
            if (!selectorsCleared) {
                selectorsCleared = true;
                Method clear = Reflect.findMethod(selectorCache.getClass(), "clear");
                if (clear != null) {
                    try {
                        clear.invoke(selectorCache);
                        LOG.info("[ULTRARARE] cleared RC's cached structure selectors");
                    } catch (Throwable t) {
                        LOG.warn("[ULTRARARE] could not clear RC selector cache: {}", t.toString());
                    }
                }
            }
            Method selectorGet = Reflect.findMethod(selectorCache.getClass(), "get",
                    Biome.class, WorldProvider.class);
            if (selectorGet == null) {
                return null;
            }
            Class<?> mixingClass = Reflect.findClass(MIXING_SELECTOR_CLASS);
            if (mixingClass == null) {
                return null;
            }
            Method generatedStructures;
            try {
                generatedStructures = mixingClass.getMethod("generatedStructures",
                        Random.class, Biome.class, WorldProvider.class, Float.class);
            } catch (Throwable t) {
                LOG.warn("[ULTRARARE] RC selector API changed: {}", t.toString());
                return null;
            }
            Method structureId = Reflect.findMethod(registryClass, "id", Object.class);
            Method pairLeft = Reflect.findMethod(org.apache.commons.lang3.tuple.Pair.class, "getLeft");
            Method pairRight = Reflect.findMethod(org.apache.commons.lang3.tuple.Pair.class, "getRight");
            if (structureId == null || pairLeft == null) {
                return null;
            }
            long seed = world.getSeed();
            Random base = new Random(seed);
            long k = base.nextLong() / 2L * 2L + 1L;
            long l = base.nextLong() / 2L * 2L + 1L;
            return new Replay(world, seed, k, l, new ChunkPos(world.getSpawnPoint()),
                    selectorCache, registryClass, registry, selectorGet, generatedStructures,
                    structureId, pairLeft, pairRight);
        }

        /** The selector RC would use for this biome/dimension, or null. */
        Object selectorFor(Biome biome) {
            try {
                return selectorGet.invoke(selectorCache, biome, world.provider);
            } catch (Throwable t) {
                return null;
            }
        }

        /**
         * The same selector, with its category iteration order pinned to registry-id order.
         *
         * <p>{@code MixingStructureSelector.generatedStructures} walks
         * {@code weightedStructureInfos.keySet()} and spends one {@code nextDouble()} per category,
         * so the key order decides which chance each draw is compared against. RC builds that map
         * with {@code ArrayListMultimap.create()} — a {@code HashMap} keyed by objects that never
         * override {@code hashCode} — so the order is identity-hash order and changes on every JVM
         * start. Pinning it makes RC's own generation and this replay agree, in this session and in
         * every later one.
         */
        Object pinnedSelector(Biome biome) {
            // Cached per biome: a deep scan asks for the same selector hundreds of thousands of
            // times, and the reflective get rebuilds a Guava Pair key every call.
            Object known = selectorByBiome.get(biome);
            if (known != null) {
                return known;
            }
            Object selector = selectorFor(biome);
            if (selector == null) {
                return null;
            }
            selectorByBiome.put(biome, selector);
            synchronized (pinnedSelectors) {
                if (pinnedSelectors.contains(selector)) {
                    return selector;
                }
                pinnedSelectors.add(selector);
            }
            pin(selector);
            ensureSpawnChances(selector);
            return selector;
        }

        /**
         * RC's {@code GenerationInfo.spawnChance} defaults to 0 when the {@code .rcst} omits it,
         * and {@code spawnChance()} then uses that 0 for every matching biome — the structure can
         * never be selected. The three shipped {@code ultrarare} structures all omit it, so patch
         * the in-memory category back to the {@code ultrarare.rcnc} default. Only the
         * {@code ultrarare} category is touched; other categories keep their data as-is.
         *
         * <p>Done on the live selector rather than by editing {@code .rcst}: rewriting the archive
         * makes RC reload new {@code Structure} instances while the selector keeps old ones, and
         * {@code StructureRegistry.id(structure)} then returns null for every plan.
         */
        private void ensureSpawnChances(Object selector) {
            try {
                Object weights = weightsField == null ? null : weightsField.get(selector);
                if (weights == null) {
                    return;
                }
                Method keySet = Reflect.findMethod(weights.getClass(), "keySet");
                Object keys = keySet == null ? null : keySet.invoke(weights);
                if (!(keys instanceof Collection)) {
                    return;
                }
                for (Object category : (Collection<?>) keys) {
                    if (!"ultrarare".equalsIgnoreCase(categoryName(category))) {
                        continue;
                    }
                    Field infosField = Reflect.findField(category.getClass(), "generationInfos");
                    if (infosField == null) {
                        continue;
                    }
                    Object infos = infosField.get(category);
                    if (!(infos instanceof List)) {
                        continue;
                    }
                    for (Object info : (List<?>) infos) {
                        Field chance = Reflect.findField(info.getClass(), "spawnChance");
                        if (chance == null) {
                            continue;
                        }
                        if (chance.getDouble(info) <= 0.0) {
                            chance.setDouble(info, 1.5E-4);
                            LOG.info("[ULTRARARE] patched missing spawnChance on {} to 1.5E-4",
                                    categoryName(category));
                        }
                    }
                }
            } catch (Throwable t) {
                LOG.warn("[ULTRARARE] could not patch spawnChance: {}", t.toString());
            }
        }

        private void pin(Object selector) {
            if (weightsField == null) {
                if (!warnedNoWeightsField) {
                    warnedNoWeightsField = true;
                    LOG.warn("[ULTRARARE] could not find StructureSelector.weightedStructureInfos — "
                            + "category order stays identity-hashed and the replay will not match");
                }
                return;
            }
            try {
                Object original = weightsField.get(selector);
                if (original == null) {
                    return;
                }
                if (original.getClass().getName().endsWith("LinkedHashMultimap")) {
                    return;
                }
                Method keySet = Reflect.findMethod(original.getClass(), "keySet");
                Object keys = keySet == null ? null : keySet.invoke(original);
                if (!(keys instanceof Collection)) {
                    return;
                }
                final List<Object> order = new ArrayList<Object>((Collection<?>) keys);
                final StringBuilder before = new StringBuilder();
                for (Object key : order) {
                    before.append(categoryName(key)).append(' ');
                }
                Collections.sort(order, new Comparator<Object>() {
                    @Override
                    public int compare(Object a, Object b) {
                        return String.valueOf(categoryName(a)).compareTo(String.valueOf(categoryName(b)));
                    }
                });
                Object rebuilt = rebuild(original, order);
                if (rebuilt == null) {
                    LOG.warn("[ULTRARARE] could not rebuild the category map; order stays {}", before);
                    return;
                }
                weightsField.set(selector, rebuilt);
                StringBuilder after = new StringBuilder();
                for (Object key : order) {
                    after.append(categoryName(key)).append(' ');
                }
                // One line per distinct ordering: there is a selector per (biome, dimension), so
                // logging every pin drowned the log in dozens of identical lines.
                String signature = after.toString();
                if (LOGGED_ORDERS.add(signature)) {
                    LOG.info("[ULTRARARE] pinned RC category order: was [{}] now [{}]", before, after);
                }
            } catch (Throwable t) {
                LOG.warn("[ULTRARARE] could not pin category order: {}", t.toString());
            }
        }

        /** A {@code LinkedHashMultimap} holding exactly the same entries, in the order given. */
        private Object rebuild(Object original, List<Object> order) {
            try {
                Class<?> type = Class.forName(GUAVA_MULTIMAP_CLASS);
                Object created = Reflect.invokeStatic(type, "create", new Class<?>[0]);
                Method put = Reflect.findMethod(type, "put", Object.class, Object.class);
                Method get = Reflect.findMethod(original.getClass(), "get", Object.class);
                if (created == null || put == null || get == null) {
                    return null;
                }
                for (Object key : order) {
                    Object values = get.invoke(original, key);
                    if (!(values instanceof Collection)) {
                        continue;
                    }
                    for (Object value : (Collection<?>) values) {
                        put.invoke(created, key, value);
                    }
                }
                return created;
            } catch (Throwable t) {
                return null;
            }
        }

        private String categoryName(Object category) {
            if (categoryRegistry != null && categoryId != null) {
                try {
                    Object id = categoryId.invoke(categoryRegistry, category);
                    if (id instanceof String) {
                        return (String) id;
                    }
                } catch (Throwable ignored) {
                    // fall through to the class name
                }
            }
            return String.valueOf(category);
        }

        /**
         * Every ordering of this biome's categories, capped at {@code 5! = 120}.
         *
         * <p>Used by the diagnostic: records written before the order was pinned were generated
         * under a different (identity-hash) ordering, so they can only be reproduced by trying the
         * orderings that could have been in effect back then. Element 0 is the pinned order.
         */
        List<List<Object>> categoryOrders(Biome biome) {
            List<Object> keys = keysOf(pinnedSelector(biome));
            if (keys.size() < 2 || keys.size() > 5) {
                return Collections.emptyList();
            }
            List<List<Object>> out = new ArrayList<List<Object>>();
            permute(new ArrayList<Object>(keys), 0, out);
            return out;
        }

        private List<Object> keysOf(Object selector) {
            Object map = readWeights(selector);
            Method keySet = map == null ? null : Reflect.findMethod(map.getClass(), "keySet");
            Object keys;
            try {
                keys = keySet == null ? null : keySet.invoke(map);
            } catch (Throwable t) {
                return Collections.emptyList();
            }
            return keys instanceof Collection
                    ? new ArrayList<Object>((Collection<?>) keys) : Collections.<Object>emptyList();
        }

        private static void permute(List<Object> items, int index, List<List<Object>> out) {
            if (out.size() >= 120) {
                return;
            }
            if (index >= items.size()) {
                out.add(new ArrayList<Object>(items));
                return;
            }
            for (int i = index; i < items.size(); i++) {
                Collections.swap(items, index, i);
                permute(items, index + 1, out);
                Collections.swap(items, index, i);
            }
        }

        /**
         * Whether this biome's selector carries {@code category} at all.
         *
         * <p>RC only puts a category into a selector when at least one of its structures matches the
         * biome, so this is precisely "an attempt of that category is possible here". Using it as the
         * scan's denominator is far more honest than a temperature test, which also counts taiga.
         */
        boolean hasCategory(Biome biome, String category) {
            if (category == null || category.isEmpty()) {
                return true;
            }
            Map<Biome, Boolean> cache;
            synchronized (categoryByBiome) {
                cache = categoryByBiome.get(category);
                if (cache == null) {
                    cache = new IdentityHashMap<Biome, Boolean>();
                    categoryByBiome.put(category, cache);
                }
            }
            Boolean known = cache.get(biome);
            if (known != null) {
                return known.booleanValue();
            }
            boolean found = false;
            for (Object key : keysOf(pinnedSelector(biome))) {
                if (category.equalsIgnoreCase(categoryName(key))) {
                    found = true;
                    break;
                }
            }
            cache.put(biome, Boolean.valueOf(found));
            return found;
        }

        private Object readWeights(Object selector) {
            if (selector == null || weightsField == null) {
                return null;
            }
            try {
                return weightsField.get(selector);
            } catch (Throwable t) {
                return null;
            }
        }

        /** Category map in a specific order, built once and cached per biome + ordering. */
        private Object categoryMap(Biome biome, List<Object> order) {
            StringBuilder signature = new StringBuilder();
            for (Object key : order) {
                signature.append(categoryName(key)).append(',');
            }
            String cacheKey = String.valueOf(biome.getRegistryName()) + '|' + signature;
            synchronized (permutedMaps) {
                Object cached = permutedMaps.get(cacheKey);
                if (cached != null) {
                    return cached;
                }
            }
            Object built = rebuild(readWeights(pinnedSelector(biome)), order);
            if (built != null) {
                synchronized (permutedMaps) {
                    permutedMaps.put(cacheKey, built);
                }
            }
            return built;
        }

        /** Plans a chunk as if RC's categories iterated in {@code order}, then restores the map. */
        List<Planned> planChunkInOrder(Biome biome, int chunkX, int chunkZ, List<Object> order) {
            Object selector = pinnedSelector(biome);
            Object map = categoryMap(biome, order);
            if (selector == null || map == null || weightsField == null) {
                return planChunk(biome, chunkX, chunkZ);
            }
            Object original;
            try {
                original = weightsField.get(selector);
                weightsField.set(selector, map);
            } catch (Throwable t) {
                return planChunk(biome, chunkX, chunkZ);
            }
            try {
                List<Planned> planned = new ArrayList<Planned>();
                planChunkWith(biome, chunkX, chunkZ,
                        chunkRandomAt(chunkX, chunkZ), planned);
                return planned;
            } finally {
                try {
                    weightsField.set(selector, original);
                } catch (Throwable ignored) {
                    // the map stays as-is; the next plan call re-pins if needed
                }
            }
        }

        /**
         * True when RC has already processed this chunk.
         *
         * <p>{@code WorldStructureGenerationData.checkedChunks} is exactly "RC already ran its
         * selection here". Once a chunk is in that set its structure layout is final: if the target
         * is not in the save's records, no future generation will add it. Reporting a prediction for
         * such a chunk produces the "teleported to an empty field" failure — the chunk was generated
         * before the scan, RC refused the spot back then (terrain, overlap, biome edge), and the
         * replay happily repeats the selection while ignoring the refusal.
         */
        boolean alreadyProcessed(int chunkX, int chunkZ) {
            try {
                Class<?> type = Reflect.findClass(
                        "ivorius.reccomplex.world.gen.feature.WorldStructureGenerationData");
                if (type == null) {
                    return false;
                }
                Object data = Reflect.invokeStatic(type, "get",
                        new Class<?>[]{World.class}, world);
                Object checked = Reflect.read(data, "checkedChunks");
                if (checked instanceof java.util.Set) {
                    return ((java.util.Set<?>) checked).contains(
                            new net.minecraft.util.math.ChunkPos(chunkX, chunkZ));
                }
            } catch (Throwable ignored) {
                // unknown: keep the candidate rather than hiding a real one
            }
            return false;
        }

        /**
         * Whether RC actually placed {@code target} near {@code (x, z)}.
         *
         * <p>The placement dry-run reads blocks, and reading a block in an ungenerated chunk makes
         * the world generate and populate it — which is the moment RC decides for real. So by the
         * time this is called the answer is final and already on disk. A candidate that RC refused
         * (overlap with an earlier structure, bad terrain) simply has no entry here.
         */
        boolean hasStructureNear(Target target, int x, int z) {
            try {
                Class<?> type = Reflect.findClass(
                        "ivorius.reccomplex.world.gen.feature.WorldStructureGenerationData");
                if (type == null) {
                    return true;
                }
                Object data = Reflect.invokeStatic(type, "get",
                        new Class<?>[]{World.class}, world);
                if (data == null) {
                    return true;
                }
                Method byId = Reflect.findMethod(data.getClass(), "getEntriesByID", String.class);
                if (byId == null) {
                    return true;
                }
                Object result = byId.invoke(data, target.rcName);
                if (!(result instanceof java.util.Set)) {
                    return true;
                }
                for (Object entry : (java.util.Set<?>) result) {
                    Object box = Reflect.invoke(entry, "getBoundingBox");
                    if (box instanceof net.minecraft.world.gen.structure.StructureBoundingBox) {
                        net.minecraft.world.gen.structure.StructureBoundingBox bb =
                                (net.minecraft.world.gen.structure.StructureBoundingBox) box;
                        int cx = (bb.minX + bb.maxX) / 2;
                        int cz = (bb.minZ + bb.maxZ) / 2;
                        // The recorded anchor can sit up to three chunks from the planned chunk.
                        if (Math.abs(cx - x) <= 96 && Math.abs(cz - z) <= 96) {
                            return true;
                        }
                    }
                }
                // RC's save data can be unreadable (a UTFDataFormatException on load), and then it
                // reads back as an empty set: every real structure looks like "RC did not place it".
                // That is exactly how two existing ice cities were discarded on 2026-11-07 while
                // their blocks were standing in the region files. Fall back to this mod's
                // append-only TSV, which is written from RC's own records and survives a corrupt
                // .dat.
                return hasRecordNear(target, x, z);
            } catch (Throwable ignored) {
                // unknown: keep the candidate rather than hiding a real one
            }
            return true;
        }

        /**
         * Same proximity test against this mod's append-only TSV history.
         *
         * <p>Used only when RC's own save data answers "nothing", because that answer is ambiguous:
         * the file can be unreadable and still load as an empty set. The TSV is written from RC's
         * records when a scan collects them, so a hit there is real evidence that the structure was
         * placed at some point.
         */
        private boolean hasRecordNear(Target target, int x, int z) {
            for (RecurrentComplexData.Record record : RecurrentComplexData.snapshot(world)) {
                if (record.structureId == null || !target.matchesId(record.structureId)) {
                    continue;
                }
                if (Math.abs(record.center.getX() - x) <= 96
                        && Math.abs(record.center.getZ() - z) <= 96) {
                    return true;
                }
            }
            return false;
        }

        /**
         * The Y RC recorded for {@code target} near {@code (x, z)}, or 64 when there is none.
         *
         * <p>Used as the teleport height when the placement dry-run refused the spot but the
         * structure exists anyway: RC's own record is the better anchor.
         */
        int recordY(Target target, int x, int z) {
            for (RecurrentComplexData.Record record : RecurrentComplexData.snapshot(world)) {
                if (record.structureId == null || !target.matchesId(record.structureId)) {
                    continue;
                }
                if (Math.abs(record.center.getX() - x) <= 96
                        && Math.abs(record.center.getZ() - z) <= 96) {
                    return record.center.getY();
                }
            }
            return 64;
        }

        /** The seed the chunk generator hands to RC for this chunk, in this dimension. */
        long chunkSeedAt(int chunkX, int chunkZ) {
            if (endDimension) {
                return (long) chunkX * 341873128712L + (long) chunkZ * 132897987541L;
            }
            return RcUltraRareLocator.chunkSeed(worldSeed, k, l, chunkX, chunkZ);
        }

        private Random chunkRandomAt(int chunkX, int chunkZ) {
            return new Random(chunkSeedAt(chunkX, chunkZ));
        }

        /** A fresh copy of the chunk random, for printing the draws without consuming the replay's. */
        Random chunkRandomFor(int chunkX, int chunkZ) {
            return chunkRandomAt(chunkX, chunkZ);
        }

        /**
         * How many candidates the compass search may verify through RC's placer.
         *
         * <p>Ultrarare targets are rare enough that verifying everything costs little. Adventure
         * structures can appear dozens of times inside the 8-second budget, and a dry run over a
         * 335x307 footprint is expensive, so only the nearest few are verified.
         */
        private static int verifyLimit(Target target) {
            return "ultrarare".equalsIgnoreCase(target.category) ? Integer.MAX_VALUE : 3;
        }

        /** Returns how many candidate positions were added. */
        int scan(List<Hit> out, int centerChunkX, int centerChunkZ, int radiusBlocks, Target target) {
            int chunkRadius = Math.max(1, radiusBlocks / 16);
            int tileRadius = (chunkRadius + TILE_CHUNKS - 1) / TILE_CHUNKS;
            net.minecraft.world.biome.BiomeProvider provider = world.getBiomeProvider();
            if (provider == null) {
                return 0;
            }
            if (deepBiomes == null) {
                deepBiomes = new Biome[TILE_BLOCKS * TILE_BLOCKS];
            }
            long deadline = System.currentTimeMillis() + MAX_MILLIS;
            int centerTileX = centerChunkX >> 4;
            int centerTileZ = centerChunkZ >> 4;
            int found = 0;
            int[] budget = new int[]{verifyLimit(target)};
            // Tiles are walked outward from the player, so a search that runs out of budget still
            // covers the ground closest to them instead of one arbitrary corner of the square.
            for (int ring = 0; ring <= tileRadius; ring++) {
                int length = ringLength(ring);
                for (int index = 0; index < length; index++) {
                    if (System.currentTimeMillis() > deadline) {
                        LOG.info("[ULTRARARE] search stopped at the {} ms budget after {} chunks",
                                MAX_MILLIS, scannedChunks);
                        return found;
                    }
                    int tileX = centerTileX + ringOffsetX(ring, index);
                    int tileZ = centerTileZ + ringOffsetZ(ring, index);
                    found += scanTileForSearch(provider, tileX, tileZ,
                            centerChunkX, centerChunkZ, chunkRadius, out, target, budget);
                    if (budget[0] <= 0) {
                        return found;
                    }
                }
            }
            return found;
        }

        /** One batched tile for the compass search: no chat, no progress, but placement verified. */
        private int scanTileForSearch(net.minecraft.world.biome.BiomeProvider provider,
                                      int tileX, int tileZ, int centerChunkX, int centerChunkZ,
                                      int chunkRadius, List<Hit> out, Target target, int[] budget) {
            try {
                provider.getBiomes(deepBiomes, tileX * TILE_BLOCKS, tileZ * TILE_BLOCKS,
                        TILE_BLOCKS, TILE_BLOCKS, false);
            } catch (Throwable t) {
                return 0;
            }
            int found = 0;
            for (int ddz = 0; ddz < TILE_CHUNKS; ddz++) {
                for (int ddx = 0; ddx < TILE_CHUNKS; ddx++) {
                    int chunkX = tileX * TILE_CHUNKS + ddx;
                    int chunkZ = tileZ * TILE_CHUNKS + ddz;
                    if (Math.abs(chunkX - centerChunkX) > chunkRadius
                            || Math.abs(chunkZ - centerChunkZ) > chunkRadius) {
                        continue;
                    }
                    Biome biome = deepBiomes[(ddz * 16) * TILE_BLOCKS + ddx * 16];
                    if (!target.matchesBiome(biome) || !hasCategory(biome, target.category)) {
                        continue;
                    }
                    // Generic ultrarare scans spend nearly all their time inside planChunk's
                    // reflection call. Skip the chunks the RNG cannot select; survivors still run
                    // the full selector, the placement dry-run and the post-generation check.
                    if (ULTRARARE_PREFILTER_ENABLED
                            && "ultrarare".equalsIgnoreCase(target.category)
                            && !rngPrefilter(chunkSeedAt(chunkX, chunkZ),
                                    ULTRARARE_PREFILTER_DRAWS, ULTRARARE_PREFILTER_CHANCE)) {
                        continue;
                    }
                    if (budget[0] <= 0) {
                        return found;
                    }
                    found += scanChunk(biome, chunkX, chunkZ, out, target, budget);
                }
            }
            return found;
        }

        private int scanChunk(Biome biome, int chunkX, int chunkZ, List<Hit> out, Target target,
                              int[] budget) {
            int found = 0;
            if (budget[0] <= 0) {
                return 0;
            }
            // RC already finalised this chunk's structure set; replaying it is pure waste. This
            // used to be checked after planChunk, which paid for the selector call first.
            if (alreadyProcessed(chunkX, chunkZ)) {
                deepSkipped++;
                return 0;
            }
            deepPlanCalls++;
            for (Planned planned : planChunk(biome, chunkX, chunkZ)) {
                deepPlanned++;
                if (!target.matchesId(planned.id)) {
                    continue;
                }
                deepTargetPlanned++;
                // WorldGenStructures.randomSurfacePos: +8 inside the chunk, offset by RC's salt.
                Random positionRandom = new Random(planned.seed ^ SURFACE_SEED);
                int x = chunkX * 16 + positionRandom.nextInt(16) + 8;
                int z = chunkZ * 16 + positionRandom.nextInt(16) + 8;
                if (alreadyProcessed(chunkX, chunkZ)) {
                    LOG.info("[ULTRARARE] prediction at ({}, {}) ignored: chunk({},{}) was already "
                            + "processed by RC, so its structure set is final", x, z, chunkX, chunkZ);
                    continue;
                }
                // Same dry-run the deep scan uses: selection is exact, terrain is not. Without this
                // the compass happily points at a spot RC would refuse to build on. The budget counts
                // attempts, not successes: a 335x307 footprint is expensive even when it fails.
                if (budget[0] <= 0) {
                    return found;
                }
                budget[0]--;
                // An existing record is definitive; the dry-run only supplies the teleport Y and is
                // not allowed to reject a spot RC may well accept at populate time.
                boolean accepted = hasStructureNear(target, x, z);
                Placement placement = accepted ? null : placementY(planned, x, z);
                if (!accepted && materialize(chunkX, chunkZ)) {
                    accepted = hasStructureNear(target, x, z);
                }
                if (!accepted) {
                    LOG.info("[ULTRARARE] prediction at ({}, {}) skipped: RC did not place it", x, z);
                    continue;
                }
                int y = placement != null && placement.known() ? placement.y : recordY(target, x, z);
                out.add(new Hit(new BlockPos(x, y, z), false));
                found++;
            }
            return found;
        }

        /**
         * Every structure RC would plan for this chunk, with the seed it would hand to
         * {@code StructureGenerator}. Both random sources are concatenated; a caller looking for one
         * id just scans the list.
         */
        /**
         * What RC's {@code planStructuresInChunk} would plan for this chunk.
         *
         * <p>Only the vanilla populate random is used. A {@code [RCPROBE]} run compared the raw
         * {@code java.util.Random} state RC is handed against this formula on six live chunks and
         * matched all six, so the second "RC chunkSeed" source that used to be replayed here only
         * produced false positives.
         */
        List<Planned> planChunk(Biome biome, int chunkX, int chunkZ) {
            List<Planned> planned = new ArrayList<Planned>();
            planChunkWith(biome, chunkX, chunkZ,
                    chunkRandomAt(chunkX, chunkZ), planned);
            return planned;
        }

        private static Class<?> generatorClass;
        private static Class<?> surfacePosClass;
        private static Class<?> placerInterface;
        private static Class<?> generationTypeClass;
        private static Class<?> structureInterface;
        private static java.lang.reflect.Constructor<?> surfacePosConstructor;
        private static boolean placementClassesResolved;

        private static synchronized void resolvePlacementClasses() {
            if (placementClassesResolved) {
                return;
            }
            placementClassesResolved = true;
            generatorClass = Reflect.findClass(STRUCTURE_GENERATOR_CLASS);
            surfacePosClass = Reflect.findClass(BLOCK_SURFACE_POS_CLASS);
            placerInterface = Reflect.findClass(PLACER_INTERFACE_CLASS);
            generationTypeClass = Reflect.findClass(GENERATION_TYPE_CLASS);
            structureInterface = Reflect.findClass(STRUCTURE_CLASS);
            if (surfacePosClass != null) {
                try {
                    surfacePosConstructor = surfacePosClass.getConstructor(int.class, int.class);
                } catch (Throwable ignored) {
                    surfacePosConstructor = null;
                }
            }
        }

        /**
         * Dry-runs RC's own placement check at a candidate, without placing a single block.
         *
         * <p>{@code StructureGenerator.boundingBox()} runs the whole placement pipeline —
         * {@code Environment.inNature} plus {@code Placer.place(context, structure.blockCollection())}
         * — and returns {@link java.util.Optional#empty()} when the placer refuses the spot (the
         * {@code DONT_GENERATE} return). That is exactly the check that rejected three of the four
         * candidates in the first live run: selection was right, the terrain was not.
         *
         * <p>The check reads world blocks, so it materialises the chunks under the structure. It
         * never writes blocks and never records the structure.
         *
         * <p>Returns {@code true} when the answer cannot be computed, so an unverifiable candidate
         * is still reported rather than silently dropped.
         */
        boolean wouldPlace(Planned planned, int x, int z) {
            return placementY(planned, x, z) != null;
        }

        /**
         * Runs RC's placement dry-run and returns the structure's base Y.
         *
         * <p>{@code null} means RC refused the spot (terrain does not fit). {@link #Y_UNKNOWN} means
         * the dry-run could not answer — the caller keeps the candidate unverified, exactly like
         * before. Any other value is {@code StructureBoundingBox.minY}, which the End teleport needs
         * because a floating island has no meaningful "surface" above the structure.
         */
        Placement placementY(Planned planned, int x, int z) {
            if (planned.structure == null || planned.generation == null
                    || !(world instanceof net.minecraft.world.WorldServer)) {
                return Placement.unknown();
            }
            resolvePlacementClasses();
            if (generatorClass == null || surfacePosClass == null || surfacePosConstructor == null
                    || placerInterface == null || generationTypeClass == null
                    || structureInterface == null) {
                return Placement.unknown();
            }
            try {
                Object generator = Reflect.newInstance(generatorClass, new Class<?>[0]);
                Object placer = Reflect.read(planned.generation, "placer");
                if (generator == null || placer == null) {
                    return Placement.unknown();
                }
                invokeWith(generator, "structure", new Class<?>[]{structureInterface}, planned.structure);
                invokeWith(generator, "world",
                        new Class<?>[]{net.minecraft.world.WorldServer.class}, world);
                invokeWith(generator, "generationInfo",
                        new Class<?>[]{generationTypeClass}, planned.generation);
                invokeWith(generator, "seed", new Class<?>[]{Long.class}, Long.valueOf(planned.seed));
                Object surfacePos = surfacePosConstructor.newInstance(
                        Integer.valueOf(x), Integer.valueOf(z));
                invokeWith(generator, "randomPosition",
                        new Class<?>[]{surfacePosClass, placerInterface}, surfacePos, placer);
                invokeWith(generator, "fromCenter", new Class<?>[]{boolean.class}, Boolean.TRUE);
                Object box = Reflect.invoke(generator, "boundingBox");
                if (!(box instanceof java.util.Optional) || !((java.util.Optional<?>) box).isPresent()) {
                    return null;
                }
                Object value = ((java.util.Optional<?>) box).get();
                net.minecraft.world.gen.structure.StructureBoundingBox bb =
                        value instanceof net.minecraft.world.gen.structure.StructureBoundingBox
                                ? (net.minecraft.world.gen.structure.StructureBoundingBox) value : null;
                return new Placement(boxMinY(value), bb);
            } catch (Throwable t) {
                if (!warnedPlacement) {
                    warnedPlacement = true;
                    LOG.warn("[ULTRARARE] placement dry-run failed ({}); candidates stay unverified",
                            t.toString());
                }
                return Placement.unknown();
            }
        }

        /**
         * One placement dry-run: the base Y RC would use, plus the footprint it would occupy.
         *
         * <p>The footprint is only used for diagnostics now. RC's own overlap test runs when the
         * chunk is <em>populated</em>, which a block read never reaches, so a candidate keeps the
         * original rule instead: it is reported once RC has a record of it (作者故乡 excepted).
         */
        static final class Placement {
            final int y;
            final net.minecraft.world.gen.structure.StructureBoundingBox box;

            Placement(int y, net.minecraft.world.gen.structure.StructureBoundingBox box) {
                this.y = y;
                this.box = box;
            }

            static Placement unknown() {
                return new Placement(Y_UNKNOWN, null);
            }

            boolean known() {
                return y != Y_UNKNOWN;
            }
        }


        /**
         * Forces the chunk through population, which is the moment RC actually places structures.
         *
         * <p>This is the fix for "the structure is really there but the compass says no": the
         * placement dry-run only generates terrain, while RC decides at populate time and writes its
         * record then. Without this step a scan can never see the decision it is asking about, so
         * "no record" was indistinguishable from "refused" — and on 2026-11-07 that discarded two
         * ice cities that every world with this seed generates at the same coordinates.
         *
         * <p>{@code Chunk.populate} is guarded by {@code isTerrainPopulated}, so a chunk the player
         * already visited is left alone. Nothing is written that the game would not have written on
         * arrival; the scan only stops guessing.
         *
         * @return true when the chunk is populated and RC's answer is final
         */
        boolean materialize(int chunkX, int chunkZ) {
            try {
                if (!(world instanceof net.minecraft.world.WorldServer)) {
                    return false;
                }
                net.minecraft.world.gen.ChunkProviderServer provider =
                        (net.minecraft.world.gen.ChunkProviderServer)
                                ((net.minecraft.world.WorldServer) world).getChunkProvider();
                // Chunk.populate bails out unless the four direct neighbours are already loaded
                // (it looks them up with getLoadedChunk). Generating only the centre chunk is
                // therefore a no-op: RC never runs, and the structure appears later, when the player
                // finally loads the area. Measured 2026-11-07 17:45 — (-23516, 6814) was recorded
                // only when the player teleported, although the scan had "populated" that chunk.
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx != 0 || dz != 0) {
                            provider.provideChunk(chunkX + dx, chunkZ + dz);
                        }
                    }
                }
                net.minecraft.world.chunk.Chunk chunk = provider.provideChunk(chunkX, chunkZ);
                if (chunk == null) {
                    return false;
                }
                if (!chunk.isTerrainPopulated()) {
                    chunk.populate(provider, provider.chunkGenerator);
                }
                return true;
            } catch (Throwable t) {
                if (!warnedMaterialize) {
                    warnedMaterialize = true;
                    LOG.warn("[ULTRARARE] could not populate a candidate chunk ({}); candidates "
                            + "stay unverified", t.toString());
                }
                return false;
            }
        }

        /**
         * Reads {@code StructureBoundingBox.minY}.
         *
         * <p>This must be a direct field access, not {@code Reflect.read(box, "minY")}: Minecraft
         * members are reobfuscated to SRG names ({@code field_78895_b}) at build time, and a string
         * lookup would silently return nothing in production while working in the IDE.
         */
        private int boxMinY(Object box) {
            if (box instanceof net.minecraft.world.gen.structure.StructureBoundingBox) {
                return ((net.minecraft.world.gen.structure.StructureBoundingBox) box).minY;
            }
            return Y_UNKNOWN;
        }

        private static void invokeWith(Object target, String name, Class<?>[] types, Object... args) {
            Method method = Reflect.findMethod(target.getClass(), name, types);
            if (method == null) {
                return;
            }
            try {
                method.invoke(target, args);
            } catch (Throwable ignored) {
                // a rejected setter leaves the default, which is also what RC would use
            }
        }

        /** One planned structure: RC's registry id plus the seed its generator would receive. */
        static final class Planned {
            final String id;
            final long seed;
            /** RC's structure object and its natural generation, used for the placement dry-run. */
            final Object structure;
            final Object generation;

            Planned(String id, long seed) {
                this(id, seed, null, null);
            }

            Planned(String id, long seed, Object structure, Object generation) {
                this.id = id;
                this.seed = seed;
                this.structure = structure;
                this.generation = generation;
            }
        }

        /**
         * Logs the first selector's weights so a "0 plans" scan can be explained: a non-zero
         * {@code weightsSize} with empty/zero {@code totals} means RC's own {@code amountMultiplier}
         * is zero for this biome, and no amount of scanning will ever plan a structure there.
         */
        private void probeSelector(Object selector, Biome biome, int chunkX, int chunkZ,
                                   float distance) {
            if (selectorProbed) {
                return;
            }
            selectorProbed = true;
            try {
                Object weights = weightsField == null ? null : weightsField.get(selector);
                int weightsSize = -1;
                if (weights != null) {
                    Method size = Reflect.findMethod(weights.getClass(), "size");
                    if (size != null) {
                        Object value = size.invoke(weights);
                        if (value instanceof Number) {
                            weightsSize = ((Number) value).intValue();
                        }
                    }
                }
                StringBuilder chances = new StringBuilder();
                if (weights != null) {
                    Method keySet = Reflect.findMethod(weights.getClass(), "keySet");
                    Object keys = keySet == null ? null : keySet.invoke(weights);
                    if (keys instanceof Collection) {
                        for (Object category : (Collection<?>) keys) {
                            Method chanceMethod = Reflect.findMethod(category.getClass(),
                                    "spawnChance", Biome.class,
                                    net.minecraft.world.WorldProvider.class);
                            if (chanceMethod == null) {
                                continue;
                            }
                            Object value = chanceMethod.invoke(category, biome, world.provider);
                            if (chances.length() > 0) {
                                chances.append(", ");
                            }
                            chances.append(categoryName(category)).append('=');
                            chances.append(value instanceof double[]
                                    ? java.util.Arrays.toString((double[]) value)
                                    : String.valueOf(value));
                        }
                    }
                }
                LOG.info("[ULTRARARE] selector probe at chunk({},{}): biome={} distance={} "
                                + "weightsSize={} spawnChances=[{}]",
                        chunkX, chunkZ, biome.getRegistryName(), Float.valueOf(distance),
                        Integer.valueOf(weightsSize), chances);
            } catch (Throwable t) {
                LOG.info("[ULTRARARE] selector probe failed: {}", t.toString());
            }
        }

        private void planChunkWith(Biome biome, int chunkX, int chunkZ, Random random,
                                   List<Planned> out) {
            Object selector = pinnedSelector(biome);
            if (selector == null) {
                return;
            }
            float distance = (float) Math.sqrt(
                    (double) (chunkX - spawnChunk.x) * (chunkX - spawnChunk.x)
                            + (double) (chunkZ - spawnChunk.z) * (chunkZ - spawnChunk.z));
            probeSelector(selector, biome, chunkX, chunkZ, distance);
            List<?> pairs;
            try {
                pairs = (List<?>) generatedStructures.invoke(selector, random, biome,
                        world.provider, Float.valueOf(distance));
            } catch (Throwable t) {
                if (!warnedReplay) {
                    warnedReplay = true;
                    LOG.warn("[ULTRARARE] replay call failed: {}", t.toString());
                }
                return;
            }
            scannedChunks++;
            if (pairs == null || pairs.isEmpty()) {
                return;
            }
            deepPairs += pairs.size();
            // RC's planStructuresInChunk consumes one nextLong per planned structure, in list order.
            for (Object pair : pairs) {
                long structureSeed = random.nextLong();
                Object structure;
                try {
                    structure = pairLeft.invoke(pair);
                } catch (Throwable t) {
                    deepPairFailures++;
                    continue;
                }
                String id;
                try {
                    id = (String) structureId.invoke(registry, structure);
                } catch (Throwable t) {
                    deepPairFailures++;
                    continue;
                }
                if (id != null) {
                    Object generation = null;
                    if (pairRight != null) {
                        try {
                            generation = pairRight.invoke(pair);
                        } catch (Throwable ignored) {
                            // verification will be skipped, the candidate is still reported
                        }
                    }
                    out.add(new Planned(id, structureSeed, structure, generation));
                }
            }
        }
    }

    /**
     * A handle on RC's replay engine for external scanners.
     *
     * <p>It exposes only what a scanner needs — the chunk seed, a conservative RNG prefilter, and
     * plan/verify for one chunk. It does not touch the locator's caches or deep-scan state, so a
     * fast scan cannot disturb a running compass search or the generic deep scan used by the other
     * two targets.
     */
    public static final class Session {

        /** java.util.Random's LCG, inlined below. The prefilter is the hot path of every fast scan. */
        private static final long RNG_MULTIPLIER = 0x5DEECE66DL;
        private static final long RNG_ADDEND = 0xBL;
        private static final long RNG_MASK = (1L << 48) - 1;
        private static final double RNG_DOUBLE_UNIT = 0x1.0p-53;

        private final Replay replay;

        private Session(Replay replay) {
            this.replay = replay;
        }

        public boolean isRegistered(Target target) {
            return RcUltraRareLocator.isRegistered(replay, target);
        }

        public boolean hasCategory(Biome biome, String category) {
            return replay.hasCategory(biome, category);
        }

        /** The exact seed {@code ChunkGeneratorOverworld.populate} hands to RC's selector. */
        public long chunkSeed(int chunkX, int chunkZ) {
            return replay.chunkSeedAt(chunkX, chunkZ);
        }

        /**
         * Conservative RNG prefilter: true when any of the first {@code draws} category rolls is
         * below {@code chance}.
         *
         * <p>The caller must still run the real replay. Because the target's own roll is one of
         * those draws and its chance is below {@code chance}, a skipped chunk can never contain a
         * selection; the filter can only produce false positives.
         *
         * <p>Java 8's {@code Random} advances its seed through an {@code AtomicLong} CAS, which
         * costs ~1.9 us for a 128-draw chunk — the dominant cost of a fast scan. This inlines the
         * exact same recurrence (including {@code setSeed}'s XOR scramble and the two-step
         * {@code next(26)} / {@code next(27)} of {@code nextDouble()}) at ~0.23 us. Verified
         * bit-for-bit against {@code Random} on 120,000 real chunk seeds (15,360,000 doubles,
         * zero mismatches) before replacing it. Do not "simplify" the shifts: they are the
         * {@code 48 - bits} truncation of Random's LCG.
         */
        public boolean maySelect(int chunkX, int chunkZ, int draws, double chance) {
            return rngPrefilter(chunkSeed(chunkX, chunkZ), draws, chance);
        }

        /**
         * Plans one chunk and returns the positions RC's own placer accepts for {@code target}.
         * {@code rejected[0]} receives how many target plans the placer refused.
         */
        public List<BlockPos> candidates(Target target, Biome biome, int chunkX, int chunkZ,
                                         int[] rejected) {
            List<BlockPos> out = new ArrayList<BlockPos>();
            for (Replay.Planned planned : replay.planChunk(biome, chunkX, chunkZ)) {
                if (!target.matchesId(planned.id)) {
                    continue;
                }
                Random positionRandom = new Random(planned.seed ^ SURFACE_SEED);
                int x = chunkX * 16 + positionRandom.nextInt(16) + 8;
                int z = chunkZ * 16 + positionRandom.nextInt(16) + 8;
                if (replay.alreadyProcessed(chunkX, chunkZ)) {
                    continue;
                }
                // An existing record is definitive; the dry-run only supplies the teleport Y.
                boolean accepted = replay.hasStructureNear(target, x, z);
                Replay.Placement placement = accepted ? null : replay.placementY(planned, x, z);
                if (!accepted && replay.materialize(chunkX, chunkZ)) {
                    accepted = replay.hasStructureNear(target, x, z);
                }
                if (!accepted) {
                    if (rejected != null && rejected.length > 0) {
                        rejected[0]++;
                    }
                    continue;
                }
                int y = placement != null && placement.known()
                        ? placement.y : replay.recordY(target, x, z);
                out.add(new BlockPos(x, y, z));
            }
            return out;
        }
    }

    /** Opens a replay session, or {@code null} when Recurrent Complex is unavailable. */
    public static Session openSession(World world) {
        Replay replay = Replay.create(world);
        return replay == null ? null : new Session(replay);
    }

    /** Lets an external scanner publish a verified candidate into the compass's reuse list. */
    public static void rememberDeepHit(Target target, BlockPos pos) {
        deepHits(target.id).add(new Hit(pos, false));
    }

    /**
     * Replays the chunk of every recorded natural structure and reports how many are planned again.
     *
     * <p>This is the only honest accuracy check available: the records are real placements RC wrote
     * to disk, so a correct replay must plan the same structure for the same chunk. A low hit rate
     * means the random state assumed here is wrong, and predictions for unexplored chunks cannot be
     * trusted. Exposed as {@code /rlcompass rccheck}.
     */
    public static String diagnose(World world, int newestN) {
        Replay replay = Replay.create(world);
        if (replay == null) {
            return "Recurrent Complex selectors unavailable";
        }
        Class<?> registryClass = Reflect.findClass(REGISTRY_CLASS);
        Class<?> naturalClass = Reflect.findClass(NATURAL_GENERATION_CLASS);
        Class<?> structureClass = Reflect.findClass(
                "ivorius.reccomplex.world.gen.feature.structure.Structure");
        Method registryGet = registryClass == null ? null
                : Reflect.findMethod(registryClass, "get", String.class);
        Method generationTypes = structureClass == null ? null
                : Reflect.findMethod(structureClass, "generationTypes", Class.class);

        List<RecurrentComplexData.Record> records = RecurrentComplexData.snapshot(world);
        // Two numbers decide everything: how many structures RC actually registered, and how many
        // entries the per-biome selector holds. If either is zero the replay can never hit.
        Object registeredIds = Reflect.invoke(replay.registry, "ids");
        int registrySize = registeredIds instanceof java.util.Collection
                ? ((java.util.Collection<?>) registeredIds).size() : -1;
        LOG.info("[ULTRARARE] rccheck: registry holds {} structure ids", registrySize);
        int checked = 0;
        int hit = 0;
        int someOrder = 0;
        int skipped = 0;
        StringBuilder misses = new StringBuilder();
        StringBuilder offsets = new StringBuilder();
        StringBuilder orderHits = new StringBuilder();
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        // Newest first: records written before the current world seed are useless for a replay.
        for (int i = records.size() - 1; i >= 0 && checked < newestN; i--) {
            RecurrentComplexData.Record record = records.get(i);
            if (record.structureId == null) {
                continue;
            }
            // Only structures that actually carry a natural generation info can ever be replayed;
            // village pieces and structureList children are placed by other code paths.
            if (registryGet != null && generationTypes != null) {
                Object structure = lookupStructure(registryGet, replay.registry, record.structureId);
                if (structure == null) {
                    skipped++;
                    continue;
                }
                try {
                    List<?> types = (List<?>) generationTypes.invoke(structure, naturalClass);
                    if (types == null || types.isEmpty()) {
                        skipped++;
                        continue;
                    }
                } catch (Throwable t) {
                    skipped++;
                    continue;
                }
            }
            int chunkX = record.center.getX() >> 4;
            int chunkZ = record.center.getZ() >> 4;
            probe.setPos(chunkX * 16, 0, chunkZ * 16);
            Biome biome = world.getBiome(probe);
            if (biome == null) {
                continue;
            }
            checked++;
            if (checked == 1) {
                Object selector = replay.pinnedSelector(biome);
                Object weights = selector == null ? null : Reflect.read(selector, "weightedStructureInfos");
                if (weights != null) {
                    try {
                        Method sizeMethod = Reflect.findMethod(weights.getClass(), "size");
                        Method keySetMethod = Reflect.findMethod(weights.getClass(), "keySet");
                        int entries = sizeMethod == null ? -1
                                : ((Number) sizeMethod.invoke(weights)).intValue();
                        Object keys = keySetMethod == null ? null : keySetMethod.invoke(weights);
                        // Guava's keySet() is a package-private class, so go through Collection.
                        int categories = keys instanceof java.util.Collection
                                ? ((java.util.Collection<?>) keys).size() : -1;
                        LOG.info("[ULTRARARE] rccheck: selector for {} holds {} entries in {} categories",
                                biome.getRegistryName(), entries, categories);
                        // The iteration order is what decides which draw each category is compared
                        // against; printing it makes the pinning visible on every run.
                        StringBuilder order = new StringBuilder();
                        if (keys instanceof java.util.Collection) {
                            for (Object key : (java.util.Collection<?>) keys) {
                                order.append(replay.categoryName(key)).append(' ');
                            }
                        }
                        LOG.info("[ULTRARARE] rccheck: iteration order = [{}] (map type {})",
                                order, weights.getClass().getName());
                    } catch (Throwable t) {
                        LOG.warn("[ULTRARARE] could not read selector contents: {}", t.toString());
                    }
                }
                // The first draws the replay will make, so the probability comparison can be checked
                // offline against the category chances instead of guessed.
                Random peek = replay.chunkRandomFor(chunkX, chunkZ);
                StringBuilder rolls = new StringBuilder();
                for (int draw = 0; draw < 6; draw++) {
                    rolls.append(String.format(java.util.Locale.ROOT, "%.6f", peek.nextDouble())).append(' ');
                }
                LOG.info("[ULTRARARE] rccheck: first draws at chunk({},{}) = {}", chunkX, chunkZ, rolls);
            }
            // A structure is planned from one chunk but its bounding box can span neighbours, and the
            // recorded centre may sit in a different chunk than the one that planned it — a sky city
            // is dozens of blocks wide. Scanning a 9x9 window is what separates "wrong random" from
            // "wrong chunk", and reporting the offset proves which one it was.
            boolean found = false;
            int offsetX = 0;
            int offsetZ = 0;
            for (int dx = -SEARCH_CHUNKS; dx <= SEARCH_CHUNKS && !found; dx++) {
                for (int dz = -SEARCH_CHUNKS; dz <= SEARCH_CHUNKS && !found; dz++) {
                    int neighbourX = chunkX + dx;
                    int neighbourZ = chunkZ + dz;
                    probe.setPos(neighbourX * 16, 0, neighbourZ * 16);
                    Biome neighbourBiome = world.getBiome(probe);
                    if (neighbourBiome == null) {
                        continue;
                    }
                    for (Replay.Planned entry : replay.planChunk(neighbourBiome, neighbourX, neighbourZ)) {
                        if (bareId(record.structureId).equalsIgnoreCase(bareId(entry.id))) {
                            found = true;
                            offsetX = dx;
                            offsetZ = dz;
                            break;
                        }
                    }
                }
            }
            if (found) {
                hit++;
                if (offsetX != 0 || offsetZ != 0) {
                    offsets.append('(').append(offsetX).append(',').append(offsetZ).append(") ");
                    LOG.info("[ULTRARARE] rccheck: '{}' recorded at chunk({},{}) but planned from ({},{})",
                            record.structureId, chunkX, chunkZ,
                            chunkX + offsetX, chunkZ + offsetZ);
                }
            } else {
                // A miss under the pinned order means one of two things: the record predates the pin
                // (RC's category order used to follow identity hashes), or the replay is wrong. Trying
                // every other ordering separates them — a record that reproduces under *some* order
                // proves the seed -> random -> selector chain is correct and only the order differed.
                int otherOrder = -1;
                List<List<Object>> orders = replay.categoryOrders(biome);
                for (int oi = 1; oi < orders.size() && !found; oi++) {
                    List<Object> order = orders.get(oi);
                    for (int dx = -1; dx <= 1 && !found; dx++) {
                        for (int dz = -1; dz <= 1 && !found; dz++) {
                            int nx = chunkX + dx;
                            int nz = chunkZ + dz;
                            probe.setPos(nx * 16, 0, nz * 16);
                            Biome neighbour = world.getBiome(probe);
                            if (neighbour == null) {
                                continue;
                            }
                            for (Replay.Planned entry
                                    : replay.planChunkInOrder(neighbour, nx, nz, order)) {
                                if (bareId(record.structureId).equalsIgnoreCase(bareId(entry.id))) {
                                    found = true;
                                    otherOrder = oi;
                                    break;
                                }
                            }
                        }
                    }
                }
                if (found) {
                    someOrder++;
                    if (orderHits.length() < 160) {
                        orderHits.append(otherOrder).append(' ');
                    }
                }
            }
            if (!found && misses.length() < 320) {
                misses.append(record.structureId).append("@{").append(chunkX).append(',')
                        .append(chunkZ).append("} ");
            }
        }
        LOG.info("[ULTRARARE] rccheck: pinned order reproduced {}/{} natural records, {} more under "
                        + "another category order ({} skipped as non-natural); offsets: [{}]; "
                        + "order indexes: [{}]; misses: {}",
                hit, checked, someOrder, skipped, offsets, orderHits, misses);
        return "replay (pinned order) reproduced " + hit + "/" + checked
                + ", plus " + someOrder + " under another ordering (of " + skipped
                + " non-natural skipped)" + (offsets.length() == 0 ? "" : "; offsets " + offsets)
                + (misses.length() == 0 ? "" : "; misses: " + misses);
    }

    /** RC's registry keys appear bare, namespaced or with the extension; try all three. */
    private static Object lookupStructure(Method registryGet, Object registry, String recordedId) {
        String bare = bareId(recordedId);
        String[] candidates = {recordedId, bare, "reccomplex:" + bare, bare + ".rcst"};
        for (String candidate : candidates) {
            try {
                Object structure = registryGet.invoke(registry, candidate);
                if (structure != null) {
                    return structure;
                }
            } catch (Throwable ignored) {
                // try the next shape
            }
        }
        return null;
    }

    private static boolean warnedReplay;

    // ------------------------------------------------------------------------------------------
    // Deep scan: walk chunks outward from the player and replay RC's selection on every snowy one.
    //
    // Shivaxi's real rate is about 1.5e-4 (category chance) * 0.15 (amountMultiplier for a 3-entry
    // category) / 3 (its share of the category) = 7.5e-6 per snowy chunk, so a 200-block search can
    // never find one. This scans millions of chunks instead, spread over server ticks so the game
    // stays responsive.
    // ------------------------------------------------------------------------------------------

    /**
     * Work slice per server tick. Cheap enough to stay invisible, large enough to finish.
     *
     * <p>{@code World.getBiome} recomputes a 16x16 block biome grid on a cache miss, which measured
     * at ~54 microseconds per chunk — 250k chunks took 339 s. Sampling a whole 256x256 block tile in
     * one {@code getBiomes} call amortises the GenLayer chain over 256 chunks instead.
     */
    private static final long TICK_BUDGET_NANOS = 8_000_000L;
    /**
     * Experimental budget used when {@code CompassConfig.turboScan} is on.
     *
     * <p>40 ms of a 50 ms tick leaves the server barely enough room for its own work, so TPS drops
     * to roughly 14-16 while a scan runs. It is the single cheapest speed-up available: the scan is
     * only ~8% duty cycle at 8 ms, so 92% of a normal run is spent waiting for the next tick. Above
     * ~40 ms the curve flattens — the remaining time is real CPU work, not waiting.
     */
    private static final long TURBO_TICK_BUDGET_NANOS = 40_000_000L;
    /**
     * Turbo-mode RNG prefilter for the generic ultrarare scan, identical to the parameters the
     * Author's Hall scanner has been running with ({@code AuthorHallFastScan}): 64 rolls,
     * threshold {@code 1e-4}, which passes 0.64% of chunks.
     *
     * <p>This is what makes the two paths comparable. Without it the generic scan has to sample
     * the biome of every chunk (a 256-chunk {@code getBiomes} call per tile, ~4.3 us/chunk) and
     * then replay every chunk that matches the biome (2.5% for ice, 5% for snowy). With it, only
     * the survivors are sampled, one {@code getBiome} each.
     *
     * <p>Risk, recorded deliberately: the filter assumes the {@code ultrarare} roll lands within
     * the first 64 {@code nextDouble()} calls. The pinned category order puts ultrarare last, and
     * every passing category before it consumes extra rolls, so this is empirical rather than
     * proven — see HANDOFF §36 / §38. It is only enabled in turbo mode, so a normal scan keeps the
     * exhaustive path.
     */
    private static final int TURBO_PREFILTER_DRAWS = 64;
    private static final double TURBO_PREFILTER_CHANCE = 1.0E-4;

    /** Chunks per side of one batched biome tile; 16 chunks = 256 blocks. */
    private static final int TILE_CHUNKS = 16;
    private static final int TILE_BLOCKS = TILE_CHUNKS * 16;
    /** How often to say something, so a long scan does not look hung. */
    private static final int PROGRESS_EVERY = 5_000_000;
    /** Chat is noisier than the log, so it gets a line less often than the log. */
    private static final int CHAT_EVERY = 10_000_000;

    private static Replay deepReplay;
    private static Target deepTarget;
    private static World deepWorld;
    private static net.minecraft.entity.player.EntityPlayerMP deepPlayer;
    private static int deepRadius;
    private static int deepCenterX;
    private static int deepCenterZ;
    private static int deepTileRadius;
    private static int deepRing;
    private static int deepIndex;
    private static int deepScanned;
    /** Chunks that survived the turbo prefilter; only these get a biome lookup. */
    private static int deepPrefiltered;
    private static int deepSnowy;
    private static int deepCandidates;
    private static int deepRejected;
    /**
     * Chunks RC already finished. A replay there would re-answer a settled question, and the answer
     * would be "nothing", which is exactly what made a real structure look unfindable: the hit was
     * generated on the previous run, so the second scan skipped its chunk and reported 0 candidates.
     */
    private static int deepSkipped;
    /**
     * Diagnostics for "0 candidates": how many structures the replay planned at all, and how many
     * of those were the target. Zero target plans means the selector never picked it (chance,
     * biome or category); a non-zero count with zero candidates means the placer or the
     * post-generation check refused every one.
     */
    private static long deepPlanned;
    private static long deepTargetPlanned;
    /** Diagnostic counters for "0 plans": selector calls, pairs returned, and pairs we failed to read. */
    private static int deepPlanCalls;
    private static int deepPairs;
    private static int deepPairFailures;
    private static long deepStartedAt;
    private static int deepNextProgress;
    private static int deepNextChat;
    /**
     * Stop once a candidate is found. The scan walks rings outward, so the first hit is in the
     * nearest ring — waiting for the whole square only adds farther duplicates.
     */
    private static boolean deepStopAfterFirst = true;
    private static boolean deepFirstHit;
    /** Verified candidates per target id, so a scan for one structure never leaks into another. */
    private static final Map<String, List<Hit>> DEEP_HITS =
            new HashMap<String, List<Hit>>();

    private static List<Hit> deepHits(String targetId) {
        synchronized (DEEP_HITS) {
            List<Hit> list = DEEP_HITS.get(targetId);
            if (list == null) {
                list = new ArrayList<Hit>();
                DEEP_HITS.put(targetId, list);
            }
            return list;
        }
    }
    /** Positions RC's placer refused. Kept so a wrong dry-run can never hide a real candidate. */
    private static final List<BlockPos> DEEP_REJECTED = new ArrayList<BlockPos>();
    /** Reused biome buffer for one tile; 256x256 blocks. */
    private static Biome[] deepBiomes;
    /** Reused position for the per-chunk biome lookup in turbo mode; no allocation per survivor. */
    private static final BlockPos.MutableBlockPos DEEP_PROBE = new BlockPos.MutableBlockPos();
    /** {@code BiomeDictionary} lookups are not free and there are only a few dozen biomes. */
    private static final Map<Biome, Boolean> SNOWY_CACHE = new IdentityHashMap<Biome, Boolean>();

    public static boolean deepScanActive() {
        return deepReplay != null;
    }

    /** RC name of the target the generic deep scan is walking, or {@code null} when idle. */
    public static String runningName() {
        return deepTarget == null ? null : deepTarget.rcName;
    }

    /** The discovered hit closest to {@code player}; used for the async result packet. */
    private static Hit nearestHit(String targetId, net.minecraft.entity.player.EntityPlayerMP player) {
        Hit best = null;
        double bestDistanceSq = Double.MAX_VALUE;
        for (Hit hit : deepHits(targetId)) {
            double distanceSq = player == null
                    ? 0.0 : StructureResult.horizontalDistanceSq(hit.pos, player.getPosition());
            if (best == null || distanceSq < bestDistanceSq) {
                best = hit;
                bestDistanceSq = distanceSq;
            }
        }
        return best;
    }

    /**
     * Starts (or reports) a scan that walks {@code radiusChunks} chunks outward from {@code world}.
     * Progress and results go to the player's chat as well as the log, so a five-minute scan does
     * not require watching {@code latest.log}.
     */
    public static String startDeepScan(Target target, World world,
                                       net.minecraft.entity.player.EntityPlayerMP player,
                                       BlockPos around, int radiusChunks) {
        return startDeepScan(target, world, player, around, radiusChunks, true);
    }

    /**
     * @param firstOnly stop at the first candidate instead of mapping the whole square; on by
     *                  default because the player only needs the nearest one.
     */
    public static String startDeepScan(Target target, World world,
                                       net.minecraft.entity.player.EntityPlayerMP player,
                                       BlockPos around, int radiusChunks, boolean firstOnly) {
        if (deepReplay != null) {
            tell(player, "rlcompass.shivaxi.running",
                    deepTarget == null ? "?" : deepTarget.rcName,
                    Integer.valueOf(deepScanned), Integer.valueOf(deepCandidates));
            return "a scan is already running (" + (deepTarget == null ? "?" : deepTarget.rcName)
                    + "): " + deepScanned + " chunks, " + deepCandidates
                    + " candidate(s). Use /rlcompass scan cancel to stop it.";
        }
        Replay replay = Replay.create(world);
        if (replay == null) {
            tell(player, "rlcompass.shivaxi.unavailable");
            return "Recurrent Complex selectors unavailable";
        }
        deepReplay = replay;
        deepTarget = target == null ? SHIVAXI : target;
        deepWorld = world;
        deepPlayer = player;
        deepRadius = Math.max(8, Math.min(MAX_CHUNK_RADIUS, radiusChunks));
        deepCenterX = around.getX() >> 4;
        deepCenterZ = around.getZ() >> 4;
        deepTileRadius = (deepRadius + TILE_CHUNKS - 1) / TILE_CHUNKS;
        if (deepBiomes == null) {
            deepBiomes = new Biome[TILE_BLOCKS * TILE_BLOCKS];
        }
        deepRing = 0;
        deepIndex = 0;
        deepScanned = 0;
        deepPrefiltered = 0;
        deepSnowy = 0;
        deepCandidates = 0;
        deepRejected = 0;
        deepSkipped = 0;
        deepPlanned = 0;
        deepTargetPlanned = 0;
        deepPlanCalls = 0;
        deepPairs = 0;
        deepPairFailures = 0;
        deepNextProgress = PROGRESS_EVERY;
        deepNextChat = CHAT_EVERY;
        deepStopAfterFirst = firstOnly;
        deepFirstHit = false;
        deepStartedAt = System.currentTimeMillis();
        deepHits(deepTarget.id).clear();
        DEEP_REJECTED.clear();
        SNOWY_CACHE.clear();
        long total = (2L * deepRadius + 1L) * (2L * deepRadius + 1L);
        boolean turboPrefilter = CompassConfig.turboScan
                && "ultrarare".equalsIgnoreCase(deepTarget.category);
        LOG.info("[ULTRARARE] deep scan for {} started at chunk({},{}) radius {} chunks "
                        + "({} blocks, {} chunks to walk){}{}",
                deepTarget.rcName, deepCenterX, deepCenterZ, deepRadius,
                (long) deepRadius * 16L, total, turboTag(),
                turboPrefilter ? "; prefilter " + TURBO_PREFILTER_DRAWS + " draws < "
                        + TURBO_PREFILTER_CHANCE : "");
        // If the structure is not in RC's registry, no amount of scanning can ever hit it — say so
        // before the player waits five minutes for nothing.
        if (!isRegistered(replay, deepTarget)) {
            tell(player, "rlcompass.shivaxi.notRegistered", deepTarget.rcName);
        }
        tell(player, "rlcompass.shivaxi.started", deepTarget.rcName,
                Integer.valueOf(deepRadius), Long.valueOf(total));
        return "deep scan of " + deepTarget.rcName + " started: radius " + deepRadius
                + " chunks, " + total + " chunks, centred on chunk(" + deepCenterX + ","
                + deepCenterZ + ")";
    }

    /** Whether RC's registry actually holds this target; a no-op scan is worth flagging. */
    private static boolean isRegistered(Replay replay, Target target) {
        Class<?> registryClass = Reflect.findClass(REGISTRY_CLASS);
        if (registryClass == null || replay == null) {
            return true;
        }
        Method get = Reflect.findMethod(registryClass, "get", String.class);
        return get == null || lookupStructure(get, replay.registry, target.rcName) != null;
    }

    /** Sends a translated line to the player running the scan, if they are still online. */
    private static void tell(net.minecraft.entity.player.EntityPlayerMP player, String key, Object... args) {
        if (player == null) {
            return;
        }
        try {
            player.sendMessage(new net.minecraft.util.text.TextComponentTranslation(key, args));
        } catch (Throwable ignored) {
            // player logged out mid-scan
        }
    }

    public static String stopDeepScan() {
        if (deepReplay == null) {
            return "no scan is running";
        }
        String summary = summarizeDeepScan("stopped");
        Target target = deepTarget;
        World world = deepWorld;
        net.minecraft.entity.player.EntityPlayerMP player = deepPlayer;
        tell(player, "rlcompass.shivaxi.stopped",
                Long.valueOf(deepScanned), Long.valueOf(deepSnowy), Integer.valueOf(deepCandidates),
                Long.valueOf((System.currentTimeMillis() - deepStartedAt) / 1000L));
        if (target != null) {
            PacketHandler.sendToPlayer(PacketDeepScanResult.cancelled(target.id,
                    world == null ? 0 : world.provider.getDimension()), player);
        }
        deepReplay = null;
        deepTarget = null;
        deepWorld = null;
        deepPlayer = null;
        return summary;
    }

    /**
     * Current per-tick slice for the running target: 40 ms in experimental turbo mode, 8 ms
     * otherwise.
     *
     * <p>Read every tick rather than captured at start, so toggling the switch applies to a running
     * scan on the next tick.
     *
     * <p>The turbo budget is gated on {@code ultrarare}: this scanner also serves {@code adventure}
     * and the village-family targets, and the switch is only meant for the three ultra-rares.
     */
    private static long tickBudgetNanos() {
        return turboActiveFor(deepTarget) ? TURBO_TICK_BUDGET_NANOS : TICK_BUDGET_NANOS;
    }

    /**
     * Whether the experimental budget applies to {@code target}.
     *
     * <p>Applies to every RC target, not just the ultra-rares: the village family, the adventure
     * structures and the End structures all run the same tick-sliced tile scan, and users asked for
     * the whole hot-structure list to benefit. Non-RC locators (dragon roost, battletowers) are
     * synchronous region scans and are not affected by this switch at all.
     */
    private static boolean turboActiveFor(Target target) {
        return CompassConfig.turboScan && target != null;
    }


    /** Suffix for scan log lines, so a turbo run is never confused with a normal one. */
    static String turboTag() {
        return turboActiveFor(deepTarget) ? " [turbo " + (TURBO_TICK_BUDGET_NANOS / 1_000_000L)
                + " ms/tick]" : "";
    }

    /** Called from the server tick; does a few milliseconds of work and returns. */
    public static void tickDeepScan() {
        Replay replay = deepReplay;
        Target target = deepTarget;
        World world = deepWorld;
        if (replay == null || target == null || world == null) {
            return;
        }
        net.minecraft.world.biome.BiomeProvider provider = world.getBiomeProvider();
        if (provider == null) {
            return;
        }
        long deadline = System.nanoTime() + tickBudgetNanos();
        while (System.nanoTime() < deadline) {
            if (deepFirstHit) {
                String summary = summarizeDeepScan("first hit");
                long seconds = (System.currentTimeMillis() - deepStartedAt) / 1000L;
                tell(deepPlayer, "rlcompass.shivaxi.firstHit", target.rcName,
                        Integer.valueOf(deepCandidates), Long.valueOf(seconds));
                Hit nearest = nearestHit(target.id, deepPlayer);
                if (nearest != null) {
                    PacketHandler.sendToPlayer(PacketDeepScanResult.hit(target.id, nearest.pos,
                            world.provider.getDimension()), deepPlayer);
                }
                deepReplay = null;
                deepTarget = null;
                deepWorld = null;
                deepPlayer = null;
                LOG.info("[ULTRARARE] {}", summary);
                return;
            }
            if (deepRing > deepTileRadius) {
                String summary = summarizeDeepScan("finished");
                long seconds = (System.currentTimeMillis() - deepStartedAt) / 1000L;
                if (deepRejected > 0) {
                    StringBuilder rejected = new StringBuilder();
                    for (int i = 0; i < DEEP_REJECTED.size() && i < 6; i++) {
                        BlockPos pos = DEEP_REJECTED.get(i);
                        rejected.append('(').append(pos.getX()).append(',')
                                .append(pos.getZ()).append(") ");
                    }
                    tell(deepPlayer, "rlcompass.shivaxi.rejected",
                            Integer.valueOf(deepRejected), rejected.toString());
                }
                if (deepCandidates == 0) {
                    tell(deepPlayer, "rlcompass.shivaxi.none",
                            Long.valueOf(deepScanned), Long.valueOf(deepSnowy), Long.valueOf(seconds));
                    if (deepSkipped > 0) {
                        // Otherwise "0 candidates" reads like "it is not there", while the structure
                        // may be sitting in the save: its chunk finished on an earlier run.
                        tell(deepPlayer, "rlcompass.shivaxi.alreadyGenerated",
                                Integer.valueOf(deepSkipped));
                    }
                    PacketHandler.sendToPlayer(PacketDeepScanResult.miss(target.id,
                            world.provider.getDimension(), deepRadius), deepPlayer);
                } else {
                    tell(deepPlayer, "rlcompass.shivaxi.finished",
                            Long.valueOf(deepScanned), Long.valueOf(deepSnowy),
                            Integer.valueOf(deepCandidates), Long.valueOf(seconds));
                    Hit nearest = nearestHit(target.id, deepPlayer);
                    if (nearest != null) {
                        PacketHandler.sendToPlayer(PacketDeepScanResult.hit(target.id, nearest.pos,
                                world.provider.getDimension()), deepPlayer);
                    }
                }
                deepReplay = null;
                deepTarget = null;
                deepWorld = null;
                deepPlayer = null;
                LOG.info("[ULTRARARE] {}", summary);
                return;
            }
            int length = ringLength(deepRing);
            int tileX = (deepCenterX >> 4) + ringOffsetX(deepRing, deepIndex);
            int tileZ = (deepCenterZ >> 4) + ringOffsetZ(deepRing, deepIndex);
            deepIndex++;
            if (deepIndex >= length) {
                deepRing++;
                deepIndex = 0;
            }
            scanTile(replay, provider, world, tileX, tileZ, target);
        }
    }

    /**
     * Replays the chunks of one 16x16-chunk tile whose biome can hold {@code target}.
     *
     * <p>Two orders, chosen by mode:
     *
     * <ul>
     *   <li><b>Normal</b> — sample the whole tile in one {@code getBiomes} call (~4.3 us/chunk),
     *       then replay the chunks whose biome matches (2.5% for ice, 5% for snowy). Exhaustive:
     *       every chunk is looked at.</li>
     *   <li><b>Turbo + ultrarare</b> — roll the 64-draw prefilter first (0.2 us/chunk, 0.64%
     *       survive), then a single {@code getBiome} per survivor. Sampling up front would cost
     *       the same whether or not a chunk can hold the target, which is where the generic scan
     *       used to spend two thirds of its time.</li>
     * </ul>
     */
    private static void scanTile(Replay replay,
                                 net.minecraft.world.biome.BiomeProvider provider,
                                 World world,
                                 int tileX, int tileZ, Target target) {
        boolean prefilter = CompassConfig.turboScan
                && "ultrarare".equalsIgnoreCase(target.category);
        Biome[] biomes = deepBiomes;
        if (!prefilter) {
            try {
                provider.getBiomes(biomes, tileX * TILE_BLOCKS, tileZ * TILE_BLOCKS,
                        TILE_BLOCKS, TILE_BLOCKS, false);
            } catch (Throwable t) {
                // IntCache can refuse very large requests; skip this tile rather than abort the scan.
                return;
            }
        }
        for (int ddz = 0; ddz < TILE_CHUNKS; ddz++) {
            for (int ddx = 0; ddx < TILE_CHUNKS; ddx++) {
                int chunkX = tileX * TILE_CHUNKS + ddx;
                int chunkZ = tileZ * TILE_CHUNKS + ddz;
                if (Math.abs(chunkX - deepCenterX) > deepRadius
                        || Math.abs(chunkZ - deepCenterZ) > deepRadius) {
                    continue;
                }
                deepScanned++;
                Biome biome;
                if (prefilter) {
                    if (!rngPrefilter(replay.chunkSeedAt(chunkX, chunkZ),
                            TURBO_PREFILTER_DRAWS, TURBO_PREFILTER_CHANCE)) {
                        continue;
                    }
                    deepPrefiltered++;
                    DEEP_PROBE.setPos(chunkX * 16 + 8, 64, chunkZ * 16 + 8);
                    biome = world.getBiome(DEEP_PROBE);
                    if (biome == null) {
                        continue;
                    }
                } else {
                    biome = biomes[(ddz * 16) * TILE_BLOCKS + ddx * 16];
                }
                if (!target.matchesBiome(biome)) {
                    continue;
                }
                // Only a biome that actually carries the ultrarare category can ever produce the
                // target; count it as the real denominator.
                if (!replay.hasCategory(biome, target.category)) {
                    continue;
                }
                deepSnowy++;
                for (Replay.Planned planned : replay.planChunk(biome, chunkX, chunkZ)) {
                    if (!target.matchesId(planned.id)) {
                        continue;
                    }
                    // WorldGenStructures.randomSurfacePos: the in-chunk offset comes from the seed.
                    Random positionRandom = new Random(planned.seed ^ SURFACE_SEED);
                    int x = chunkX * 16 + positionRandom.nextInt(16) + 8;
                    int z = chunkZ * 16 + positionRandom.nextInt(16) + 8;
                    // A chunk RC already processed will never receive the structure later; the
                    // selection below is a replay of a decision that was already made and refused.
                    if (replay.alreadyProcessed(chunkX, chunkZ)) {
                        LOG.info("[ULTRARARE] candidate at ({}, {}) chunk({},{}) skipped: RC already "
                                + "processed that chunk", x, z, chunkX, chunkZ);
                        continue;
                    }
                    // An existing record is definitive: RC already placed it here.
                    boolean confirmed = replay.hasStructureNear(target, x, z);
                    // The dry-run now only supplies the teleport Y. Its refusal is NOT authoritative:
                    // RC decides for real at populate time, and it accepts spots the dry-run rejects.
                    // Measured 2026-11-07: (-23516, 6814) is a real ice city in this very save, yet
                    // the ICE surface check fails there during the dry-run, which used to hide it.
                    Replay.Placement placement = confirmed ? null : replay.placementY(planned, x, z);
                    if (!confirmed && replay.materialize(chunkX, chunkZ)) {
                        confirmed = replay.hasStructureNear(target, x, z);
                    }
                    if (!confirmed) {
                        deepRejected++;
                        DEEP_REJECTED.add(new BlockPos(x, 64, z));
                        LOG.info("[ULTRARARE] candidate at ({}, {}) chunk({},{}) skipped: RC did not "
                                        + "place it even after the chunk was populated",
                                x, z, chunkX, chunkZ);
                        continue;
                    }
                    deepCandidates++;
                    // Teleport Y: the dry-run's base when it managed to answer, otherwise the height
                    // RC recorded for the structure (a confirmed candidate is already in the save).
                    int candidateY = placement != null && placement.known()
                            ? placement.y : replay.recordY(target, x, z);
                    BlockPos candidate = new BlockPos(x, candidateY, z);
                    deepHits(target.id).add(new Hit(candidate, false));
                    // Record it in the warehouse so a spot that turns out to be empty can be marked
                    // instead of being offered again next time.
                    try {
                        com.tom500101.rlcompass.cache.SearchMemory.get(deepWorld)
                                .remember(target.id, candidate);
                    } catch (Throwable ignored) {
                        // warehouse is a convenience; never let it break the scan
                    }
                    // The recorded anchor of an RC structure sits up to ~3 chunks from the chunk that
                    // planned it, so the structure can be up to ~48 blocks away from this point.
                    LOG.info("[ULTRARARE] deep scan candidate #{} for {} at ({}, ~, {}) chunk({},{}) {}; "
                                    + "{} chunks scanned",
                            deepCandidates, target.rcName, x, z, chunkX, chunkZ,
                            confirmed ? "confirmed by RC's record"
                                    : "predicted, RC decides when the chunk is populated",
                            deepScanned);
                    tell(deepPlayer, "rlcompass.shivaxi.candidate", Integer.valueOf(deepCandidates),
                            Integer.valueOf(chunkX), Integer.valueOf(chunkZ),
                            Integer.valueOf(x), Integer.valueOf(z));
                    if (deepStopAfterFirst) {
                        // Ring order means this is already the nearest candidate; stop the scan.
                        deepFirstHit = true;
                        return;
                    }
                }
                if (deepScanned >= deepNextChat) {
                    deepNextChat += CHAT_EVERY;
                    tell(deepPlayer, "rlcompass.shivaxi.progress",
                            Long.valueOf(deepScanned), Long.valueOf(deepTotal()),
                            Long.valueOf(deepSnowy), Integer.valueOf(deepCandidates),
                            Long.valueOf((System.currentTimeMillis() - deepStartedAt) / 1000L));
                }
                if (deepScanned >= deepNextProgress) {
                    deepNextProgress += PROGRESS_EVERY;
                    LOG.info("[ULTRARARE] deep scan progress: {} chunks, {} prefiltered, "
                                    + "{} eligible, {} candidate(s), tile ring {}/{}, {} s",
                            deepScanned, deepPrefiltered, deepSnowy, deepCandidates,
                            deepRing, deepTileRadius,
                            (System.currentTimeMillis() - deepStartedAt) / 1000L);
                }
            }
        }
    }

    /** Total chunks the current scan will visit, for chat progress lines. */
    private static long deepTotal() {
        return (2L * deepRadius + 1L) * (2L * deepRadius + 1L);
    }

    private static String summarizeDeepScan(String state) {
        StringBuilder hits = new StringBuilder();
        List<Hit> found = deepTarget == null
                ? Collections.<Hit>emptyList() : deepHits(deepTarget.id);
        for (int i = 0; i < found.size() && i < 12; i++) {
            BlockPos pos = found.get(i).pos;
            hits.append('(').append(pos.getX()).append(',').append(pos.getZ()).append(") ");
        }
        return "deep scan " + state + " (" + (deepTarget == null ? "?" : deepTarget.rcName) + "): "
                + deepScanned + " chunks, " + deepPrefiltered + " prefiltered, "
                + deepSnowy + " eligible, "
                + deepCandidates + " candidate(s), " + deepRejected + " rejected by the placer, "
                + deepSkipped + " already generated in "
                + ((System.currentTimeMillis() - deepStartedAt) / 1000L) + " s"
                + turboTag()
                + (hits.length() == 0 ? "" : "; " + hits);
    }

    private static int ringLength(int ring) {
        return ring == 0 ? 1 : 8 * ring;
    }

    private static int ringOffsetX(int ring, int index) {
        if (ring == 0) {
            return 0;
        }
        int side = index / (2 * ring);
        int step = index % (2 * ring);
        switch (side) {
            case 0:
                return -ring + step;
            case 1:
                return ring;
            case 2:
                return ring - step;
            default:
                return -ring;
        }
    }

    private static int ringOffsetZ(int ring, int index) {
        if (ring == 0) {
            return 0;
        }
        int side = index / (2 * ring);
        int step = index % (2 * ring);
        switch (side) {
            case 0:
                return -ring;
            case 1:
                return -ring + step;
            case 2:
                return ring;
            default:
                return ring - step;
        }
    }

    /** RC's structure filter is the {@code $SNOWY} biome tag; temperature is a safety net. */
    private static boolean isSnowy(Biome biome) {
        Boolean cached = SNOWY_CACHE.get(biome);
        if (cached != null) {
            return cached.booleanValue();
        }
        boolean snowy;
        try {
            snowy = net.minecraftforge.common.BiomeDictionary.hasType(
                    biome, net.minecraftforge.common.BiomeDictionary.Type.SNOWY)
                    || biome.getDefaultTemperature() <= SNOWY_TEMPERATURE;
        } catch (Throwable t) {
            snowy = biome.getDefaultTemperature() <= SNOWY_TEMPERATURE;
        }
        SNOWY_CACHE.put(biome, Boolean.valueOf(snowy));
        return snowy;
    }

    /** Registers the deep scan with Forge's server tick. */
    public static final class Ticker {
        @net.minecraftforge.fml.common.eventhandler.SubscribeEvent
        public void onServerTick(net.minecraftforge.fml.common.gameevent.TickEvent.ServerTickEvent event) {
            if (event.phase == net.minecraftforge.fml.common.gameevent.TickEvent.Phase.END) {
                tickDeepScan();
            }
        }
    }

    /** One-shot guard: RC's selector cache is rebuilt once per session, not on every search. */
    private static boolean selectorsCleared;
    /** One-shot guard so a missing reflective field is loud instead of a silent 0/10. */
    private static boolean warnedNoWeightsField;
    /** One-shot guard for the placement dry-run, which is best-effort. */
    private static boolean warnedPlacement;
    /** One-shot guard for the forced population, which is best-effort too. */
    private static boolean warnedMaterialize;
    /** Orderings already reported, so a per-biome selector does not repeat the same line. */
    private static final Set<String> LOGGED_ORDERS =
            Collections.synchronizedSet(new java.util.HashSet<String>());

    /** {@code ChunkGeneratorOverworld.populate}'s random, before any decoration touches it. */
    static long chunkSeed(long worldSeed, long k, long l, int chunkX, int chunkZ) {
        return (long) chunkX * k + (long) chunkZ * l ^ worldSeed;
    }

    private static Random chunkRandom(long worldSeed, long k, long l, int chunkX, int chunkZ) {
        return new Random(chunkSeed(worldSeed, k, l, chunkX, chunkZ));
    }

    /**
     * Shared RNG prefilter: true when any of the first {@code draws} doubles is below
     * {@code chance}. Inlines {@code java.util.Random}'s LCG exactly; the bit-level equivalence is
     * documented on {@link Session#maySelect(int, int, int, double)}.
     */
    static boolean rngPrefilter(long seed, int draws, double chance) {
        long state = (seed ^ Session.RNG_MULTIPLIER) & Session.RNG_MASK;
        for (int i = 0; i < draws; i++) {
            state = (state * Session.RNG_MULTIPLIER + Session.RNG_ADDEND) & Session.RNG_MASK;
            long high = state >>> 22;                    // Random.next(26)
            state = (state * Session.RNG_MULTIPLIER + Session.RNG_ADDEND) & Session.RNG_MASK;
            long low = state >>> 21;                     // Random.next(27)
            if (((high << 27) + low) * Session.RNG_DOUBLE_UNIT < chance) {
                return true;
            }
        }
        return false;
    }
}
