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
import com.tom500101.rlcompass.locate.IStructureLocator;
import com.tom500101.rlcompass.locate.StructureResult;
import com.tom500101.rlcompass.util.Reflect;
import com.tom500101.rlcompass.util.StructureNameUtil;

/**
 * Locates Recurrent Complex's ultra-rare structures — Shivaxi's Sky City above all.
 *
 * <p>Shivaxi is not findable by scanning: it is a {@code natural} RC structure in the
 * {@code ultrarare} category ({@code assets/reccomplex/structures/active/categories/ultrarare.rcnc},
 * {@code defaultSpawnChance = 1.5E-4}) restricted to the {@code $SNOWY} biome tag. Only three
 * structures share that category (Shivaxi, P_IceCity, RLCraft_2_9_Update_Build), so the real rate is
 * roughly {@code 1.5e-4 * min(3/20, 1) / 3} per chunk before the placement test — far rarer than the
 * "one per 6666 chunks" the tooltip suggests.
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
 * <p>The replay can only say "this chunk was picked for Shivaxi"; whether the structure actually
 * fitted (RC requires 99.14% block conformity) is decided at generation time and cannot be answered
 * without reading the terrain. Predicted hits are therefore reported separately from recorded ones.
 *
 * <p>Work is bounded: only chunks whose default temperature marks them as snowy are replayed (RC's
 * own biome weight filters the rest), and the scan stops after {@link #MAX_MILLIS} so a search never
 * freezes the server for longer than a normal cache rebuild.
 */
public class ShivaxiLocator implements IStructureLocator {

    private static final Logger LOG = LogManager.getLogger("rlcompass");

    public static final String STRUCTURE_ID = "rlcompass:shivaxi";

    /** RC's registry id for the structure (the {@code .rcst} file name without extension). */
    private static final String TARGET = "Shivaxi";

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

    static boolean isTarget(String id) {
        return TARGET.equalsIgnoreCase(bareId(id));
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
        return Collections.singletonList(STRUCTURE_ID);
    }

    @Override
    public String sourceName() {
        return "RecurrentComplexReplay";
    }

    @Override
    public boolean canAnswer(World world, String id) {
        if (!STRUCTURE_ID.equals(StructureNameUtil.canonical(id))) {
            return false;
        }
        return Reflect.findClass(REGISTRY_CLASS) != null;
    }

    @Override
    public StructureResult findNearest(World world, BlockPos playerPos, String structureId, int radius) {
        if (world == null || playerPos == null) {
            return null;
        }
        if (!STRUCTURE_ID.equals(StructureNameUtil.canonical(structureId))) {
            return null;
        }
        if (!canAnswer(world, structureId)) {
            if (!warnedUnavailable) {
                warnedUnavailable = true;
                LOG.info("[SHIVAXI] Recurrent Complex is not installed; {} stays unavailable", STRUCTURE_ID);
            }
            return null;
        }
        List<Hit> hits = hits(world, playerPos, radius);
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
                : StructureResult.of(STRUCTURE_ID, best.pos,
                        best.recorded ? "RecurrentComplexRecord" : sourceName(), playerPos);
    }

    /** Recorded hits are authoritative; predictions fill the gaps. Both de-duplicated by chunk. */
    private static List<Hit> hits(World world, BlockPos center, int radius) {
        int centerChunkX = center.getX() >> 4;
        int centerChunkZ = center.getZ() >> 4;
        String key = world.getSaveHandler() == null
                ? world.getWorldInfo().getWorldName() + '|' + world.provider.getDimension()
                : world.getSaveHandler().getWorldDirectory().getAbsolutePath()
                        + '|' + world.provider.getDimension();
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
            if (!TARGET.equalsIgnoreCase(record.structureId)) {
                continue;
            }
            hits.add(new Hit(record.center, true));
        }

        // 2. Verified candidates left behind by the last deep scan. They cost nothing to reuse and
        //    stop a compass search from re-scanning ground the player already paid minutes for.
        hits.addAll(new ArrayList<Hit>(DEEP_HITS));

        // 3. Deterministic replay over snowy chunks.
        Replay replay = Replay.create(world);
        if (replay != null) {
            int predicted = replay.scan(hits, centerChunkX, centerChunkZ, radius);
            LOG.info("[SHIVAXI] replay scanned {} snowy chunks, predicted {} candidate(s) ({} ms)",
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
        LOG.info("[SHIVAXI] {} hit(s) for {} ({} recorded, {} ms)", result.size(), TARGET,
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
        private final Object categoryRegistry;
        private final Method categoryId;
        /** Selectors already rewritten to the deterministic order; identity, one per biome. */
        private final Set<Object> pinnedSelectors =
                Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        /** Rebuilt category maps, keyed by biome and ordering, so a diagnostic does not rebuild them. */
        private final Map<String, Object> permutedMaps = new HashMap<String, Object>();
        /** One selector per biome, so the hot path never touches reflection twice. */
        private final Map<Biome, Object> selectorByBiome = new IdentityHashMap<Biome, Object>();
        /** Cached "this biome can roll ultrarare at all" answers. */
        private final Map<Biome, Boolean> ultraRareByBiome = new IdentityHashMap<Biome, Boolean>();

        int scannedChunks;

        private Replay(World world, long worldSeed, long k, long l, ChunkPos spawnChunk,
                       Object selectorCache, Class<?> registryClass, Object registry,
                       Method selectorGet, Method generatedStructures, Method structureId,
                       Method pairLeft, Method pairRight) {
            this.world = world;
            this.worldSeed = worldSeed;
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
                        LOG.info("[SHIVAXI] cleared RC's cached structure selectors");
                    } catch (Throwable t) {
                        LOG.warn("[SHIVAXI] could not clear RC selector cache: {}", t.toString());
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
                LOG.warn("[SHIVAXI] RC selector API changed: {}", t.toString());
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
            return selector;
        }

        private void pin(Object selector) {
            if (weightsField == null) {
                if (!warnedNoWeightsField) {
                    warnedNoWeightsField = true;
                    LOG.warn("[SHIVAXI] could not find StructureSelector.weightedStructureInfos — "
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
                    LOG.warn("[SHIVAXI] could not rebuild the category map; order stays {}", before);
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
                    LOG.info("[SHIVAXI] pinned RC category order: was [{}] now [{}]", before, after);
                }
            } catch (Throwable t) {
                LOG.warn("[SHIVAXI] could not pin category order: {}", t.toString());
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
         * Whether this biome's selector carries an {@code ultrarare} category at all.
         *
         * <p>RC only puts a category into a selector when at least one of its structures matches the
         * biome, so this is precisely "a Shivaxi attempt is possible here". Using it as the scan's
         * denominator is far more honest than a temperature test, which also counts taiga.
         */
        boolean hasUltraRare(Biome biome) {
            Boolean known = ultraRareByBiome.get(biome);
            if (known != null) {
                return known.booleanValue();
            }
            boolean found = false;
            for (Object key : keysOf(pinnedSelector(biome))) {
                if ("ultrarare".equalsIgnoreCase(categoryName(key))) {
                    found = true;
                    break;
                }
            }
            ultraRareByBiome.put(biome, Boolean.valueOf(found));
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
                        chunkRandom(worldSeed, k, l, chunkX, chunkZ), planned);
                return planned;
            } finally {
                try {
                    weightsField.set(selector, original);
                } catch (Throwable ignored) {
                    // the map stays as-is; the next plan call re-pins if needed
                }
            }
        }

        /** A fresh copy of the chunk random, for printing the draws without consuming the replay's. */
        Random chunkRandomFor(int chunkX, int chunkZ) {
            return chunkRandom(worldSeed, k, l, chunkX, chunkZ);
        }

        /** Returns how many candidate positions were added. */
        int scan(List<Hit> out, int centerChunkX, int centerChunkZ, int radiusBlocks) {
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
            // Tiles are walked outward from the player, so a search that runs out of budget still
            // covers the ground closest to them instead of one arbitrary corner of the square.
            for (int ring = 0; ring <= tileRadius; ring++) {
                int length = ringLength(ring);
                for (int index = 0; index < length; index++) {
                    if (System.currentTimeMillis() > deadline) {
                        LOG.info("[SHIVAXI] search stopped at the {} ms budget after {} chunks",
                                MAX_MILLIS, scannedChunks);
                        return found;
                    }
                    int tileX = centerTileX + ringOffsetX(ring, index);
                    int tileZ = centerTileZ + ringOffsetZ(ring, index);
                    found += scanTileForSearch(provider, tileX, tileZ,
                            centerChunkX, centerChunkZ, chunkRadius, out);
                }
            }
            return found;
        }

        /** One batched tile for the compass search: no chat, no progress, but placement verified. */
        private int scanTileForSearch(net.minecraft.world.biome.BiomeProvider provider,
                                      int tileX, int tileZ, int centerChunkX, int centerChunkZ,
                                      int chunkRadius, List<Hit> out) {
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
                    if (biome == null || !isSnowy(biome) || !hasUltraRare(biome)) {
                        continue;
                    }
                    found += scanChunk(biome, chunkX, chunkZ, out);
                }
            }
            return found;
        }

        private int scanChunk(Biome biome, int chunkX, int chunkZ, List<Hit> out) {
            int found = 0;
            for (Planned planned : planChunk(biome, chunkX, chunkZ)) {
                if (!isTarget(planned.id)) {
                    continue;
                }
                // WorldGenStructures.randomSurfacePos: +8 inside the chunk, offset by RC's salt.
                Random positionRandom = new Random(planned.seed ^ SURFACE_SEED);
                int x = chunkX * 16 + positionRandom.nextInt(16) + 8;
                int z = chunkZ * 16 + positionRandom.nextInt(16) + 8;
                // Same dry-run the deep scan uses: selection is exact, terrain is not. Without this
                // the compass happily points at a spot RC would refuse to build on.
                if (!wouldPlace(planned, x, z)) {
                    LOG.info("[SHIVAXI] compass candidate at ({}, {}) rejected by RC's placer", x, z);
                    continue;
                }
                out.add(new Hit(new BlockPos(x, 64, z), false));
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
                    chunkRandom(worldSeed, k, l, chunkX, chunkZ), planned);
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
            if (planned.structure == null || planned.generation == null
                    || !(world instanceof net.minecraft.world.WorldServer)) {
                return true;
            }
            resolvePlacementClasses();
            if (generatorClass == null || surfacePosClass == null || surfacePosConstructor == null
                    || placerInterface == null || generationTypeClass == null
                    || structureInterface == null) {
                return true;
            }
            try {
                Object generator = Reflect.newInstance(generatorClass, new Class<?>[0]);
                Object placer = Reflect.read(planned.generation, "placer");
                if (generator == null || placer == null) {
                    return true;
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
                return !(box instanceof java.util.Optional) || ((java.util.Optional<?>) box).isPresent();
            } catch (Throwable t) {
                if (!warnedPlacement) {
                    warnedPlacement = true;
                    LOG.warn("[SHIVAXI] placement dry-run failed ({}); candidates stay unverified",
                            t.toString());
                }
                return true;
            }
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
            /** Only filled for {@link #TARGET}: RC's structure object and its natural generation. */
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

        private void planChunkWith(Biome biome, int chunkX, int chunkZ, Random random,
                                   List<Planned> out) {
            Object selector = pinnedSelector(biome);
            if (selector == null) {
                return;
            }
            List<?> pairs;
            try {
                float distance = (float) Math.sqrt(
                        (double) (chunkX - spawnChunk.x) * (chunkX - spawnChunk.x)
                                + (double) (chunkZ - spawnChunk.z) * (chunkZ - spawnChunk.z));
                pairs = (List<?>) generatedStructures.invoke(selector, random, biome,
                        world.provider, Float.valueOf(distance));
            } catch (Throwable t) {
                if (!warnedReplay) {
                    warnedReplay = true;
                    LOG.warn("[SHIVAXI] replay call failed: {}", t.toString());
                }
                return;
            }
            scannedChunks++;
            if (pairs == null || pairs.isEmpty()) {
                return;
            }
            // RC's planStructuresInChunk consumes one nextLong per planned structure, in list order.
            for (Object pair : pairs) {
                long structureSeed = random.nextLong();
                Object structure;
                try {
                    structure = pairLeft.invoke(pair);
                } catch (Throwable t) {
                    continue;
                }
                String id;
                try {
                    id = (String) structureId.invoke(registry, structure);
                } catch (Throwable t) {
                    continue;
                }
                if (id != null) {
                    Object generation = null;
                    if (TARGET.equalsIgnoreCase(bareId(id)) && pairRight != null) {
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
        LOG.info("[SHIVAXI] rccheck: registry holds {} structure ids", registrySize);
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
                        LOG.info("[SHIVAXI] rccheck: selector for {} holds {} entries in {} categories",
                                biome.getRegistryName(), entries, categories);
                        // The iteration order is what decides which draw each category is compared
                        // against; printing it makes the pinning visible on every run.
                        StringBuilder order = new StringBuilder();
                        if (keys instanceof java.util.Collection) {
                            for (Object key : (java.util.Collection<?>) keys) {
                                order.append(replay.categoryName(key)).append(' ');
                            }
                        }
                        LOG.info("[SHIVAXI] rccheck: iteration order = [{}] (map type {})",
                                order, weights.getClass().getName());
                    } catch (Throwable t) {
                        LOG.warn("[SHIVAXI] could not read selector contents: {}", t.toString());
                    }
                }
                // The first draws the replay will make, so the probability comparison can be checked
                // offline against the category chances instead of guessed.
                Random peek = replay.chunkRandomFor(chunkX, chunkZ);
                StringBuilder rolls = new StringBuilder();
                for (int draw = 0; draw < 6; draw++) {
                    rolls.append(String.format(java.util.Locale.ROOT, "%.6f", peek.nextDouble())).append(' ');
                }
                LOG.info("[SHIVAXI] rccheck: first draws at chunk({},{}) = {}", chunkX, chunkZ, rolls);
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
                    LOG.info("[SHIVAXI] rccheck: '{}' recorded at chunk({},{}) but planned from ({},{})",
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
        LOG.info("[SHIVAXI] rccheck: pinned order reproduced {}/{} natural records, {} more under "
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
    private static final long TICK_BUDGET_NANOS = 4_000_000L;
    /** Chunks per side of one batched biome tile; 16 chunks = 256 blocks. */
    private static final int TILE_CHUNKS = 16;
    private static final int TILE_BLOCKS = TILE_CHUNKS * 16;
    /** How often to say something, so a long scan does not look hung. */
    private static final int PROGRESS_EVERY = 250_000;
    /** Chat is noisier than the log, so it gets a line every million chunks instead. */
    private static final int CHAT_EVERY = 1_000_000;

    private static Replay deepReplay;
    private static World deepWorld;
    private static net.minecraft.entity.player.EntityPlayerMP deepPlayer;
    private static int deepRadius;
    private static int deepCenterX;
    private static int deepCenterZ;
    private static int deepTileRadius;
    private static int deepRing;
    private static int deepIndex;
    private static int deepScanned;
    private static int deepSnowy;
    private static int deepCandidates;
    private static int deepRejected;
    private static long deepStartedAt;
    private static int deepNextProgress;
    private static int deepNextChat;
    private static final List<Hit> DEEP_HITS = new ArrayList<Hit>();
    /** Positions RC's placer refused. Kept so a wrong dry-run can never hide a real candidate. */
    private static final List<BlockPos> DEEP_REJECTED = new ArrayList<BlockPos>();
    /** Reused biome buffer for one tile; 256x256 blocks. */
    private static Biome[] deepBiomes;
    /** {@code BiomeDictionary} lookups are not free and there are only a few dozen biomes. */
    private static final Map<Biome, Boolean> SNOWY_CACHE = new IdentityHashMap<Biome, Boolean>();

    public static boolean deepScanActive() {
        return deepReplay != null;
    }

    /**
     * Starts (or reports) a scan that walks {@code radiusChunks} chunks outward from {@code world}.
     * Progress and results go to the player's chat as well as the log, so a five-minute scan does
     * not require watching {@code latest.log}.
     */
    public static String startDeepScan(World world, net.minecraft.entity.player.EntityPlayerMP player,
                                       BlockPos around, int radiusChunks) {
        if (deepReplay != null) {
            tell(player, "rlcompass.shivaxi.running", Integer.valueOf(deepScanned),
                    Integer.valueOf(deepCandidates));
            return "a scan is already running: " + deepScanned + " chunks, " + deepCandidates
                    + " candidate(s). Use /rlcompass shivaxi cancel to stop it.";
        }
        Replay replay = Replay.create(world);
        if (replay == null) {
            tell(player, "rlcompass.shivaxi.unavailable");
            return "Recurrent Complex selectors unavailable";
        }
        deepReplay = replay;
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
        deepSnowy = 0;
        deepCandidates = 0;
        deepRejected = 0;
        deepNextProgress = PROGRESS_EVERY;
        deepNextChat = CHAT_EVERY;
        deepStartedAt = System.currentTimeMillis();
        DEEP_HITS.clear();
        DEEP_REJECTED.clear();
        SNOWY_CACHE.clear();
        long total = (2L * deepRadius + 1L) * (2L * deepRadius + 1L);
        LOG.info("[SHIVAXI] deep scan started at chunk({},{}) radius {} chunks ({} blocks, {} chunks to walk)",
                deepCenterX, deepCenterZ, deepRadius, (long) deepRadius * 16L, total);
        // If the structure is not in RC's registry, no amount of scanning can ever hit it — say so
        // before the player waits five minutes for nothing.
        if (!isRegistered(replay)) {
            tell(player, "rlcompass.shivaxi.notRegistered");
        }
        tell(player, "rlcompass.shivaxi.started", Integer.valueOf(deepRadius), Long.valueOf(total));
        return "deep scan started: radius " + deepRadius + " chunks, " + total
                + " chunks, centred on chunk(" + deepCenterX + "," + deepCenterZ + ")";
    }

    /** Whether RC's registry actually holds the cloud city; a no-op scan is worth flagging. */
    private static boolean isRegistered(Replay replay) {
        Class<?> registryClass = Reflect.findClass(REGISTRY_CLASS);
        if (registryClass == null || replay == null) {
            return true;
        }
        Method get = Reflect.findMethod(registryClass, "get", String.class);
        return get == null || lookupStructure(get, replay.registry, TARGET) != null;
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
        tell(deepPlayer, "rlcompass.shivaxi.stopped",
                Long.valueOf(deepScanned), Long.valueOf(deepSnowy), Integer.valueOf(deepCandidates),
                Long.valueOf((System.currentTimeMillis() - deepStartedAt) / 1000L));
        deepReplay = null;
        deepWorld = null;
        deepPlayer = null;
        return summary;
    }

    /** Called from the server tick; does a few milliseconds of work and returns. */
    public static void tickDeepScan() {
        Replay replay = deepReplay;
        World world = deepWorld;
        if (replay == null || world == null) {
            return;
        }
        net.minecraft.world.biome.BiomeProvider provider = world.getBiomeProvider();
        if (provider == null) {
            return;
        }
        long deadline = System.nanoTime() + TICK_BUDGET_NANOS;
        while (System.nanoTime() < deadline) {
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
                } else {
                    tell(deepPlayer, "rlcompass.shivaxi.finished",
                            Long.valueOf(deepScanned), Long.valueOf(deepSnowy),
                            Integer.valueOf(deepCandidates), Long.valueOf(seconds));
                }
                deepReplay = null;
                deepWorld = null;
                deepPlayer = null;
                LOG.info("[SHIVAXI] {}", summary);
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
            scanTile(replay, provider, tileX, tileZ);
        }
    }

    /**
     * Samples one 16x16-chunk tile in a single {@code getBiomes} call, then replays only the snowy
     * chunks inside it.
     */
    private static void scanTile(Replay replay,
                                 net.minecraft.world.biome.BiomeProvider provider,
                                 int tileX, int tileZ) {
        Biome[] biomes = deepBiomes;
        try {
            provider.getBiomes(biomes, tileX * TILE_BLOCKS, tileZ * TILE_BLOCKS,
                    TILE_BLOCKS, TILE_BLOCKS, false);
        } catch (Throwable t) {
            // IntCache can refuse very large requests; skip this tile rather than abort the scan.
            return;
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
                Biome biome = biomes[(ddz * 16) * TILE_BLOCKS + ddx * 16];
                if (biome == null || !isSnowy(biome)) {
                    continue;
                }
                // Temperature also catches taiga; only a biome that actually carries the ultrarare
                // category can ever produce Shivaxi, so count this as the real denominator.
                if (!replay.hasUltraRare(biome)) {
                    continue;
                }
                deepSnowy++;
                for (Replay.Planned planned : replay.planChunk(biome, chunkX, chunkZ)) {
                    if (!TARGET.equalsIgnoreCase(bareId(planned.id))) {
                        continue;
                    }
                    // WorldGenStructures.randomSurfacePos: the in-chunk offset comes from the seed.
                    Random positionRandom = new Random(planned.seed ^ SURFACE_SEED);
                    int x = chunkX * 16 + positionRandom.nextInt(16) + 8;
                    int z = chunkZ * 16 + positionRandom.nextInt(16) + 8;
                    // Selection said yes; ask RC's own placer whether the terrain agrees. This is the
                    // step that rejected three of the first four live candidates.
                    if (!replay.wouldPlace(planned, x, z)) {
                        deepRejected++;
                        DEEP_REJECTED.add(new BlockPos(x, 64, z));
                        LOG.info("[SHIVAXI] candidate at ({}, {}) chunk({},{}) rejected by RC's placer",
                                x, z, chunkX, chunkZ);
                        continue;
                    }
                    deepCandidates++;
                    BlockPos candidate = new BlockPos(x, 64, z);
                    DEEP_HITS.add(new Hit(candidate, false));
                    // Record it in the warehouse so a spot that turns out to be empty can be marked
                    // instead of being offered again next time.
                    try {
                        com.tom500101.rlcompass.cache.SearchMemory.get(deepWorld)
                                .remember(STRUCTURE_ID, candidate);
                    } catch (Throwable ignored) {
                        // warehouse is a convenience; never let it break the scan
                    }
                    // The recorded anchor of an RC structure sits up to ~3 chunks from the chunk that
                    // planned it, so the city can be up to ~48 blocks away from this point.
                    LOG.info("[SHIVAXI] deep scan candidate #{} at ({}, ~, {}) chunk({},{}) verified "
                                    + "by RC's placer; {} chunks scanned",
                            deepCandidates, x, z, chunkX, chunkZ, deepScanned);
                    tell(deepPlayer, "rlcompass.shivaxi.candidate", Integer.valueOf(deepCandidates),
                            Integer.valueOf(chunkX), Integer.valueOf(chunkZ),
                            Integer.valueOf(x), Integer.valueOf(z));
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
                    LOG.info("[SHIVAXI] deep scan progress: {} chunks, {} snowy, {} candidate(s), "
                                    + "tile ring {}/{}, {} s",
                            deepScanned, deepSnowy, deepCandidates, deepRing, deepTileRadius,
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
        for (int i = 0; i < DEEP_HITS.size() && i < 12; i++) {
            BlockPos pos = DEEP_HITS.get(i).pos;
            hits.append('(').append(pos.getX()).append(',').append(pos.getZ()).append(") ");
        }
        return "deep scan " + state + ": " + deepScanned + " chunks, " + deepSnowy + " snowy, "
                + deepCandidates + " candidate(s), " + deepRejected + " rejected by the placer in "
                + ((System.currentTimeMillis() - deepStartedAt) / 1000L) + " s"
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
    /** Orderings already reported, so a per-biome selector does not repeat the same line. */
    private static final Set<String> LOGGED_ORDERS =
            Collections.synchronizedSet(new java.util.HashSet<String>());

    /** {@code ChunkGeneratorOverworld.populate}'s random, before any decoration touches it. */
    private static Random chunkRandom(long worldSeed, long k, long l, int chunkX, int chunkZ) {
        long seed = (long) chunkX * k + (long) chunkZ * l ^ worldSeed;
        return new Random(seed);
    }
}
