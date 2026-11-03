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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 *   <li><b>Vanilla anchor</b> — a {@code generationInfos} entry of type {@code vanilla} registers the
 *       structure as a <em>village piece</em> (Recurrent Complex hands it to
 *       {@code VillagerRegistry.IVillageCreationHandler}), so the structure can only stand inside a
 *       vanilla village. Those candidates are enumerated exactly and filtered by the entry's own
 *       {@code biomeExpression}; only when no village matches does the search fall back to the biome
 *       tier. Evidence: {@code VanillaGenerationClassFactory} / {@code GenericVillageCreationHandler}
 *       and the 30 village-part pack files (docs/HANDOFF.md §9).</li>
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

    /** One suggestion plus how it was obtained, so callers can label it honestly. */
    static final class Suggestion {
        final BlockPos pos;
        final boolean vanillaAnchor;

        Suggestion(BlockPos pos, boolean vanillaAnchor) {
            this.pos = pos;
            this.vanillaAnchor = vanillaAnchor;
        }
    }

    private static final Map<String, List<Filter>> FILTERS = new HashMap<String, List<Filter>>();
    /** Key present means the structure declares a vanilla anchor; value may be an empty list. */
    private static final Map<String, List<String>> ANCHORS = new HashMap<String, List<String>>();
    private static final Map<String, File> FILES = new HashMap<String, File>();
    private static final Pattern VANILLA_TYPE = Pattern.compile("\"type\"\\s*:\\s*\"vanilla\"");

    private UltraRarePredictor() {
    }

    /** True when the pack also declares a vanilla-generation anchor for this structure. */
    static boolean hasVanillaAnchor(String structureId) {
        filters(structureId);
        synchronized (ANCHORS) {
            return ANCHORS.containsKey(structureId);
        }
    }

    /** The anchor's {@code biomeExpression}s; an empty list means "any biome". */
    private static List<String> anchorExpressions(String structureId) {
        synchronized (ANCHORS) {
            List<String> expressions = ANCHORS.get(structureId);
            return expressions == null ? java.util.Collections.<String>emptyList() : expressions;
        }
    }

    /**
     * The nearest unchecked place the structure can occupy, preferring the exact vanilla anchor when
     * the pack declares one, then the generator's higher-weight biomes. Returns {@code null} when
     * nothing qualifies inside {@code radius}.
     */
    static Suggestion suggest(World world, BlockPos playerPos, int radius, String structureId,
                              SearchMemory memory) {
        if (hasVanillaAnchor(structureId)) {
            BlockPos anchored = anchoredSuggestion(world, playerPos, radius, structureId, memory);
            if (anchored != null) {
                return new Suggestion(anchored, true);
            }
            LOG.info("[RC] no anchored village matched '{}' within {} blocks; falling back to biomes",
                    structureId, radius);
        }

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
            return new Suggestion(preferred, false);
        }
        BlockPos fallback = sweep(world, playerPos, memory, structureId, all, false, centerChunkX,
                centerChunkZ, maxRing, maxDistanceSq);
        return fallback == null ? null : new Suggestion(fallback, false);
    }

    /**
     * Enumerates the exact positions the anchor allows. RC registers a {@code vanilla} generation as
     * a village piece ({@code GenericVillageCreationHandler}), so the structure can only stand inside
     * a vanilla village; the village centre is the right granularity because the exact building is
     * chosen during village layout and cannot be replayed.
     */
    private static BlockPos anchoredSuggestion(World world, BlockPos playerPos, int radius,
                                               String structureId, SearchMemory memory) {
        List<String> expressions = anchorExpressions(structureId);
        VillageLocator villages = new VillageLocator("field_186005_x", "villageGenerator");
        for (BlockPos candidate : villages.enumerateVillages(world, playerPos, radius)) {
            if (memory != null && memory.isKnown(structureId, candidate)) {
                continue;
            }
            if (!matchesAny(world.getBiome(candidate), expressions)) {
                continue;
            }
            if (memory != null) {
                memory.remember(structureId, candidate);
            }
            LOG.info("[RC] vanilla anchor for '{}': village at ({}, {}){}", structureId,
                    candidate.getX(), candidate.getZ(),
                    expressions.isEmpty() ? "" : " matches " + expressions);
            return candidate;
        }
        return null;
    }

    /** True when the biome matches any anchor expression; no expressions means "any biome". */
    private static boolean matchesAny(Biome biome, List<String> expressions) {
        if (expressions.isEmpty()) {
            return true;
        }
        for (String expression : expressions) {
            if (matchesExpression(biome, expression)) {
                return true;
            }
        }
        return false;
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

    /**
     * RC's biome-expression subset that appears in pack files: {@code &}, {@code |}, {@code !},
     * parentheses, {@code $TAG}, {@code type=TAG}, {@code id=name} and bare biome names.
     *
     * <p>A recursive-descent parser rather than string splitting: {@code !($HOT | $DRY)} is a real
     * expression in this pack, and splitting on {@code |} would evaluate it as two broken halves.
     * Malformed expressions evaluate to {@code false} instead of throwing.
     */
    static boolean matchesExpression(Biome biome, String expression) {
        if (expression == null || expression.trim().isEmpty()) {
            return true;
        }
        if (biome == null) {
            return false;
        }
        Cursor cursor = new Cursor(biome, expression);
        try {
            boolean value = parseOr(cursor);
            cursor.skipSpace();
            return cursor.atEnd() && value;
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    private static boolean parseOr(Cursor cursor) {
        boolean value = parseAnd(cursor);
        while (true) {
            cursor.skipSpace();
            if (!cursor.consume('|')) {
                return value;
            }
            boolean right = parseAnd(cursor);
            value = value || right;
        }
    }

    private static boolean parseAnd(Cursor cursor) {
        boolean value = parseUnary(cursor);
        while (true) {
            cursor.skipSpace();
            if (!cursor.consume('&')) {
                return value;
            }
            boolean right = parseUnary(cursor);
            value = value && right;
        }
    }

    private static boolean parseUnary(Cursor cursor) {
        cursor.skipSpace();
        if (cursor.consume('!')) {
            return !parseUnary(cursor);
        }
        if (cursor.consume('(')) {
            boolean value = parseOr(cursor);
            cursor.skipSpace();
            cursor.consume(')');
            return value;
        }
        return atomMatches(cursor.biome, cursor.readAtom());
    }

    private static boolean atomMatches(Biome biome, String rawToken) {
        String token = rawToken.trim();
        if (token.isEmpty()) {
            return false;
        }
        if (token.startsWith("$")) {
            return hasType(biome, token.substring(1));
        }
        if (token.startsWith("type=")) {
            return hasType(biome, token.substring(5));
        }
        if (token.startsWith("id=")) {
            return idMatches(biome, token.substring(3));
        }
        if (token.startsWith("name=")) {
            // RC's display name is the registry path without the namespace; same comparison.
            return idMatches(biome, token.substring(5));
        }
        return idMatches(biome, token);
    }

    private static boolean hasType(Biome biome, String name) {
        try {
            String key = name.trim();
            // RC writes both "type=savanna" and "type=$SAVANNA" depending on how the pack was authored.
            if (key.startsWith("$")) {
                key = key.substring(1);
            }
            BiomeDictionary.Type type = BiomeDictionary.Type.getType(key.toUpperCase(Locale.ROOT));
            return type != null && BiomeDictionary.hasType(biome, type);
        } catch (RuntimeException unknown) {
            return false;
        }
    }

    private static boolean idMatches(Biome biome, String wanted) {
        String needle = wanted.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return false;
        }
        String path = biome.getRegistryName() == null
                ? ""
                : biome.getRegistryName().toString().toLowerCase(Locale.ROOT);
        return path.equals(needle) || path.endsWith(":" + needle);
    }

    /** Character cursor for the expression parser. */
    private static final class Cursor {
        private final Biome biome;
        private final String text;
        private int index;

        Cursor(Biome biome, String text) {
            this.biome = biome;
            this.text = text;
        }

        void skipSpace() {
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
                index++;
            }
        }

        boolean consume(char expected) {
            skipSpace();
            if (index < text.length() && text.charAt(index) == expected) {
                index++;
                return true;
            }
            return false;
        }

        boolean atEnd() {
            return index >= text.length();
        }

        String readAtom() {
            skipSpace();
            int start = index;
            while (index < text.length()) {
                char ch = text.charAt(index);
                if (ch == '&' || ch == '|' || ch == '(' || ch == ')' || ch == '!'
                        || Character.isWhitespace(ch)) {
                    break;
                }
                index++;
            }
            return text.substring(start, index);
        }
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
        List<String> anchors = null;
        File file = structureFile(structureId);
        if (file != null && file.isFile()) {
            try {
                ZipFile zip = new ZipFile(file);
                try {
                    ZipEntry entry = zip.getEntry("structure.json");
                    if (entry != null) {
                        String json = new String(readAll(zip.getInputStream(entry)),
                                StandardCharsets.UTF_8);
                        anchors = parseAnchorExpressions(json);
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
        }
        synchronized (ANCHORS) {
            if (anchors != null) {
                ANCHORS.put(structureId, anchors);
            }
        }
        return out;
    }

    /**
     * Decodes the JSON string escapes RC writes into pack files. Gson's HTML-safe mode turns
     * {@code &} into {@code \\u0026} and {@code =} into {@code \\u003d}, so the raw text of an
     * expression like {@code $THEMED_TREES & !$BIRCH_TREES} is not the expression itself. Without this
     * the hand-rolled scanners would compare against escape sequences and every conjunction would
     * silently match nothing.
     */
    private static String unescapeJson(String raw) {
        if (raw.indexOf('\\') < 0) {
            return raw;
        }
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch != '\\' || i + 1 >= raw.length()) {
                out.append(ch);
                continue;
            }
            char next = raw.charAt(++i);
            switch (next) {
                case 'u':
                    if (i + 4 < raw.length()) {
                        try {
                            out.append((char) Integer.parseInt(raw.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (NumberFormatException malformed) {
                            out.append(next);
                        }
                    } else {
                        out.append(next);
                    }
                    break;
                case 'n':
                    out.append('\n');
                    break;
                case 'r':
                    out.append('\r');
                    break;
                case 't':
                    out.append('\t');
                    break;
                default:
                    // \" \\ \/ and anything else: the escaped character stands for itself.
                    out.append(next);
                    break;
            }
        }
        return out.toString();
    }

    /**
     * Collects {@code biomeExpression}s from every {@code generationInfos} entry whose type is
     * {@code vanilla}. Returns {@code null} when the structure declares no such entry at all — the
     * distinction matters, because "anchored with no biome restriction" is not the same as "not
     * anchored".
     *
     * <p>Same hand-rolled scan as {@link #parseBiomeFilters}: the file is generated by RC, so the
     * key order is fixed and a JSON dependency is not worth adding to the mod jar.
     */
    private static List<String> parseAnchorExpressions(String json) {
        List<String> out = new ArrayList<String>();
        Matcher matcher = VANILLA_TYPE.matcher(json);
        boolean found = false;
        while (matcher.find()) {
            found = true;
            int nextType = json.indexOf("\"type\"", matcher.end());
            String segment = nextType < 0
                    ? json.substring(matcher.end())
                    : json.substring(matcher.end(), nextType);
            int at = segment.indexOf("\"biomeExpression\"");
            if (at < 0) {
                out.add("");
                continue;
            }
            int start = segment.indexOf('"', at + 17);
            int end = start < 0 ? -1 : segment.indexOf('"', start + 1);
            out.add(start >= 0 && end > start
                    ? unescapeJson(segment.substring(start + 1, end)).trim()
                    : "");
        }
        return found ? out : null;
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
            String expression = unescapeJson(json.substring(start + 1, end)).trim();
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
