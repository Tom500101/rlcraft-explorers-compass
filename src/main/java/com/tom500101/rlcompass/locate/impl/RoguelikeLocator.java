package com.tom500101.rlcompass.locate.impl;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.Loader;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.tom500101.rlcompass.locate.IStructureLocator;
import com.tom500101.rlcompass.locate.StructureResult;
import com.tom500101.rlcompass.locate.StructureVariant;
import com.tom500101.rlcompass.locate.StructureVariant.VariantSource;
import com.tom500101.rlcompass.util.Reflect;

/**
 * Locates Roguelike Dungeons.
 *
 * <p>The stage-1 probe found {@code DungeonGenerator1_12} has no fields and the mod keeps no
 * {@code WorldSavedData}, which made this look like a feature-block-scanning job. It is not: the mod
 * exposes a pure placement predicate.
 *
 * <pre>
 * DungeonGenerator1_12.generate(...)
 *   └─ new Dungeon(worldEditor).spawnInChunk(random, chunkX, chunkZ)
 *
 * Dungeon.canSpawnInChunk(int chunkX, int chunkZ, WorldEditor)   ← public static, pure
 *   ├─ isSpawnFrequencyHit(chunkX, chunkZ)
 *   └─ isSpawnChanceHit(chunkX, chunkZ)
 * </pre>
 *
 * <p>{@code canSpawnInChunk} hashes the chunk coordinates and touches no world blocks, so walking
 * chunk rings outward is cheap — unlike mineshafts, where every candidate called {@code getBiome}.
 *
 * <h2>Why the chunk centre is not the dungeon</h2>
 * {@code canSpawnInChunk} is only the frequency/chance gate: on this pack every 51st chunk passes it
 * and {@code spawnChance=1.0}, so <em>every</em> candidate chunk passes. The real spot comes from
 * {@code Dungeon.selectLocation(random, chunkX*16, chunkZ*16)}, which draws up to
 * {@code spawnAttempts} points in a circle of radius {@code spawnFrequency*16/2} — 408 blocks here —
 * and keeps the first one whose terrain passes {@code canGenerateDungeonHere}. A chunk whose ten
 * attempts all fail generates nothing at all. Reporting the chunk centre therefore landed the player
 * up to 408 blocks away from a dungeon that might not exist; a real report had them teleport into
 * open ocean and find nothing after digging through every y level.
 *
 * <p>We replay that call instead of re-implementing it: {@code selectLocation} is invoked
 * reflectively with the same Forge chunk random the generator receives, so the returned coordinate
 * is the mod's own answer, not a re-derivation that could drift from it.
 *
 * <h2>Theme lookup</h2>
 * The theme is not stored anywhere, but it is <em>reproducible</em>: every generation call builds a
 * fresh {@code WorldEditor1_12} whose random is seeded from the world seed alone, and the first
 * consumer of that random is {@code SettingsResolver.chooseDungeonSetting}. {@code canSpawnInChunk}
 * runs before it and never touches that random, so replaying the call with our own fresh editor
 * yields the same settings — and therefore the same theme — the generator would pick.
 */
public class RoguelikeLocator implements IStructureLocator {

    private static final Logger LOG = LogManager.getLogger("rlcompass");

    private static final String DUNGEON_CLASS = "greymerk.roguelike.dungeon.Dungeon";
    private static final String WORLD_EDITOR_CLASS = "com.github.fnar.minecraft.WorldEditor1_12";
    /** The predicate's parameter type is the interface, not the 1.12 implementation. */
    private static final String WORLD_EDITOR_INTERFACE = "greymerk.roguelike.worldgen.WorldEditor";

    /**
     * Built-in theme registry. The generator stores no theme on the chunk, but the choice can be
     * replayed through the settings resolver — see the class comment.
     */
    private static final String THEMES_CLASS = "greymerk.roguelike.theme.Themes";

    private static final String SETTINGS_RESOLVER_CLASS =
            "greymerk.roguelike.dungeon.settings.SettingsResolver";
    private static final String DUNGEON_SETTINGS_CLASS =
            "greymerk.roguelike.dungeon.settings.DungeonSettings";
    private static final String LEVEL_SETTINGS_CLASS =
            "greymerk.roguelike.dungeon.settings.LevelSettings";
    private static final String COORD_CLASS = "greymerk.roguelike.worldgen.Coord";
    private static final String ROGUE_CONFIG_CLASS = "greymerk.roguelike.config.RogueConfig";
    /** Config entry listing vanilla structures whose neighbourhood vetoes a spawn. */
    private static final String STRUCTURE_DISTANCE_CONFIG =
            "VANILLA_STRUCTURES_TO_CHECK_MINIMUM_DISTANCE_FROM";
    private static final String UPPER_LIMIT_CONFIG = "UPPERLIMIT";
    /** {@code Dungeon.generate} pins the dungeon origin to this y; the tower builds upward. */
    private static final int DUNGEON_ORIGIN_Y = 50;

    /** Hard cap so a huge radius cannot stall the server. */
    private static final long CHECK_BUDGET = 200_000L;
    /**
     * A spawn-candidate chunk is only worth replaying up to this many times per search. Each replay
     * reads blocks (and may load the chunk), so an ocean full of failed candidates must not turn a
     * search into a chunk-loading marathon.
     */
    private static final int MAX_SPAWN_CHECKS = 64;
    /**
     * A themed search now walks the whole grid (no candidate cap), so it needs a wall-clock guard:
     * the work is proportional to radius squared and the server thread is blocked meanwhile.
     */
    private static final long MAX_SEARCH_MS = 30_000L;

    /** Marks a variant that names a config setting instead of an enum theme. */
    private static final String SETTING_MARKER = "/setting/";
    /** Settings directories that hold choosable dungeons (not reusable components). */
    private static final String[] SETTING_DIRS = {"builtin", "fnar"};

    public static final String STRUCTURE_ID = "roguelike:dungeon";

    private boolean resolved;
    private Method canSpawnInChunk;
    private Class<?> dungeonClass;
    private Class<?> worldEditorClass;
    private boolean warnedMissing;
    private boolean loggedFailure;
    /** Enum constants never change, so the list is built once. */
    private List<StructureVariant> variants;

    private boolean themeLookupResolved;
    private boolean themeLookupBroken;
    private Object settingsResolver;
    private Method chooseDungeonSetting;
    private java.lang.reflect.Constructor<?> coordConstructor;
    private Method settingsGetLevels;
    private Method settingsGetId;
    private Method levelGetTheme;
    private Method themeGetBase;
    /** One INFO sample per session is enough to confirm the replay against a visited dungeon. */
    private boolean loggedThemeSample;
    private boolean themeDiagLogged;

    /**
     * Exact-origin replay. The mod's {@code selectLocation} returns only the first point whose
     * terrain check passes, and that check depends on neighbour chunks that were still being built
     * during population — replaying it on a finished world can pick an earlier attempt that never
     * received a dungeon. We therefore walk all {@code spawnAttempts} points ourselves and take the
     * first one that actually carries dungeon blocks.
     */
    private boolean originLookupResolved;
    private int spawnFrequency;
    private int spawnAttempts = 10;
    private int upperLimit = 100;

    @Override
    public Collection<String> getSupportedIds() {
        return Collections.singletonList(STRUCTURE_ID);
    }

    @Override
    public String sourceName() {
        return "Roguelike";
    }

    @Override
    public StructureResult findNearest(World world, BlockPos playerPos, String structureId, int radius) {
        return findNearest(world, playerPos, radius, null, null);
    }

    /**
     * {@code requiredTheme} is an enum constant name (e.g. {@code STONE}); {@code requiredSetting} is
     * a settings id from the config (e.g. {@code fnar:dungeon_ocean_temple}). Either may be null.
     */
    private StructureResult findNearest(World world, BlockPos playerPos, int radius,
                                        String requiredTheme, String requiredSetting) {
        resolve();
        if (canSpawnInChunk == null || worldEditorClass == null) {
            if (!warnedMissing) {
                warnedMissing = true;
                LOG.info("[ROGUELIKE] Roguelike Dungeons is not installed; {} stays unavailable", STRUCTURE_ID);
            }
            return null;
        }
        Object editor = Reflect.newInstance(worldEditorClass, new Class<?>[]{World.class}, world);
        if (editor == null) {
            return null;
        }

        int centerChunkX = playerPos.getX() >> 4;
        int centerChunkZ = playerPos.getZ() >> 4;
        int maxRing = Math.max(1, radius >> 4);
        double maxDistanceSq = (double) radius * (double) radius;
        long budget = CHECK_BUDGET;
        boolean themed = requiredTheme != null || requiredSetting != null;
        SearchState state = new SearchState(themed, requiredTheme, requiredSetting);
        long startedAt = System.currentTimeMillis();

        {
            if (spawnFrequency > 1) {
                // canSpawnInChunk only accepts chunkX%f==0 && chunkZ%f==0, so walking every chunk in
                // the radius is wasted work: on this pack it is 200k hash calls for ~600 real
                // candidates, which used to cost 2.3 s. Enumerate the grid instead.
                for (int[] candidate : gridCandidates(playerPos, radius)) {
                    if (System.currentTimeMillis() - startedAt > MAX_SEARCH_MS) {
                        LOG.warn("[ROGUELIKE] stopped after {} ms without a match; radius {} is too "
                                + "large for a themed search", MAX_SEARCH_MS, radius);
                        return null;
                    }
                    StructureResult result = tryChunk(world, editor, playerPos,
                            candidate[0], candidate[1], state, maxDistanceSq);
                    if (result != null) {
                        return result;
                    }
                    if (state.stop) {
                        return null;
                    }
                }
            } else {
                for (int ring = 0; ring <= maxRing; ring++) {
                    for (int dx = -ring; dx <= ring; dx++) {
                        for (int dz = -ring; dz <= ring; dz++) {
                            if (Math.abs(dx) != ring && Math.abs(dz) != ring) {
                                continue;
                            }
                            if (--budget < 0) {
                                LOG.warn("[ROGUELIKE] gave up after {} chunk checks (radius {})",
                                        CHECK_BUDGET, radius);
                                return null;
                            }
                            StructureResult result = tryChunk(world, editor, playerPos,
                                    centerChunkX + dx, centerChunkZ + dz, state, maxDistanceSq);
                            if (result != null) {
                                return result;
                            }
                            if (state.stop) {
                                return null;
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    /** Mutable counters for one search; kept out of fields so concurrent searches cannot mix. */
    private static final class SearchState {
        final boolean themed;
        final String requiredTheme;
        final String requiredSetting;
        int originRejections;
        boolean stop;
        boolean loggedSample;

        SearchState(boolean themed, String requiredTheme, String requiredSetting) {
            this.themed = themed;
            this.requiredTheme = requiredTheme;
            this.requiredSetting = requiredSetting;
        }
    }

    /** One candidate chunk: gate, exact origin, block fingerprint, theme confirm. */
    private StructureResult tryChunk(World world, Object editor, BlockPos playerPos,
                                     int chunkX, int chunkZ, SearchState state, double maxDistanceSq) {
        if (!spawnAllowed(chunkX, chunkZ, editor)) {
            return null;
        }
        // Cheap biome-only pre-filter for themed searches. The origin can sit up to 408 blocks from
        // the chunk centre, so the centre alone is not enough — five samples inside the chunk cover
        // the ordinary cases without loading anything: getBiome works on ungenerated chunks.
        if (state.themed && !chunkMayMatch(world, chunkX, chunkZ, state)) {
            return null;
        }
        BlockPos origin = originFor(world, chunkX, chunkZ);
        if (origin == null) {
            return rejectOrigin(state, chunkX, chunkZ);
        }
        if (StructureResult.horizontalDistanceSq(origin, playerPos) > maxDistanceSq) {
            return null;
        }
        if (state.themed && !variantMatches(world, origin.getX(), origin.getZ(),
                state.requiredTheme, state.requiredSetting)) {
            return null;
        }
        if (!state.themed && !state.loggedSample) {
            state.loggedSample = true;
            String[] resolved = resolveAt(world, origin.getX(), origin.getZ());
            if (resolved != null && resolved[0] != null) {
                LOG.info("[ROGUELIKE] nearest dungeon at ({}, {}) uses theme {} ({})",
                        origin.getX(), origin.getZ(), resolved[0], resolved[1]);
            } else if (resolved != null) {
                LOG.info("[ROGUELIKE] nearest dungeon at ({}, {}) uses settings {}",
                        origin.getX(), origin.getZ(), resolved[1]);
            }
        }
        return StructureResult.of(STRUCTURE_ID, origin, sourceName(), playerPos);
    }

    private StructureResult rejectOrigin(SearchState state, int chunkX, int chunkZ) {
        if (originLookupReady() && ++state.originRejections >= MAX_SPAWN_CHECKS) {
            state.stop = true;
            LOG.info("[ROGUELIKE] {} candidate chunks near ({}, {}) had no dungeon after replay + "
                    + "block check; nothing to report", state.originRejections, chunkX, chunkZ);
        }
        return null;
    }

    /** Every chunk the frequency gate can accept, nearest first. */
    private List<int[]> gridCandidates(BlockPos playerPos, int radius) {
        int step = spawnFrequency;
        int minChunkX = (playerPos.getX() - radius) >> 4;
        int maxChunkX = (playerPos.getX() + radius) >> 4;
        int minChunkZ = (playerPos.getZ() - radius) >> 4;
        int maxChunkZ = (playerPos.getZ() + radius) >> 4;
        int startX = minChunkX - Math.floorMod(minChunkX, step);
        int startZ = minChunkZ - Math.floorMod(minChunkZ, step);
        final int px = playerPos.getX();
        final int pz = playerPos.getZ();
        List<int[]> out = new ArrayList<int[]>();
        for (int chunkX = startX; chunkX <= maxChunkX; chunkX += step) {
            for (int chunkZ = startZ; chunkZ <= maxChunkZ; chunkZ += step) {
                out.add(new int[]{chunkX, chunkZ});
            }
        }
        Collections.sort(out, new java.util.Comparator<int[]>() {
            @Override
            public int compare(int[] a, int[] b) {
                long da = chunkDistanceSq(a[0], a[1], px, pz);
                long db = chunkDistanceSq(b[0], b[1], px, pz);
                return da < db ? -1 : (da > db ? 1 : 0);
            }
        });
        return out;
    }

    private static long chunkDistanceSq(int chunkX, int chunkZ, int blockX, int blockZ) {
        long dx = (long) (chunkX << 4) + 8 - blockX;
        long dz = (long) (chunkZ << 4) + 8 - blockZ;
        return dx * dx + dz * dz;
    }

    /**
     * True when the column at {@code (blockX, blockZ)} actually carries dungeon material. Every
     * Roguelike theme shares this vocabulary (walls, pillars, doors, spawners, chests), so this is
     * what turns a replayed coordinate into "a dungeon really exists here".
     *
     * <p>Two passes: a small core first, because most of the ten attempts per chunk land on empty
     * terrain and the full 9x9x29 box would be 2000 block reads each.
     */
    private boolean looksLikeDungeon(World world, int blockX, int blockZ) {
        if (countDungeonBlocks(world, blockX, blockZ, 2, -4, 12, 8) < 8) {
            return false;
        }
        return countDungeonBlocks(world, blockX, blockZ, 4, -8, 20, 24) >= 24;
    }

    private int countDungeonBlocks(World world, int blockX, int blockZ, int radius,
                                   int dyMin, int dyMax, int stopAt) {
        int hits = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = dyMin; dy <= dyMax; dy++) {
                    cursor.setPos(blockX + dx, DUNGEON_ORIGIN_Y + dy, blockZ + dz);
                    if (isDungeonBlock(world.getBlockState(cursor).getBlock()) && ++hits >= stopAt) {
                        return hits;
                    }
                }
            }
        }
        return hits;
    }

    private static boolean isDungeonBlock(net.minecraft.block.Block block) {
        return block == net.minecraft.init.Blocks.STONEBRICK
                || block == net.minecraft.init.Blocks.COBBLESTONE
                || block == net.minecraft.init.Blocks.MOSSY_COBBLESTONE
                || block == net.minecraft.init.Blocks.PRISMARINE
                || block == net.minecraft.init.Blocks.QUARTZ_BLOCK
                || block == net.minecraft.init.Blocks.IRON_BARS
                || block == net.minecraft.init.Blocks.IRON_DOOR
                || block == net.minecraft.init.Blocks.MOB_SPAWNER
                || block == net.minecraft.init.Blocks.CHEST
                || block == net.minecraft.init.Blocks.TORCH;
    }

    private boolean spawnAllowed(int chunkX, int chunkZ, Object editor) {
        Object hit;
        try {
            hit = canSpawnInChunk.invoke(null, chunkX, chunkZ, editor);
        } catch (Throwable t) {
            if (!loggedFailure) {
                loggedFailure = true;
                Throwable cause = t.getCause() == null ? t : t.getCause();
                LOG.warn("[ROGUELIKE] canSpawnInChunk threw {}: {}",
                        cause.getClass().getName(), cause.getMessage());
            }
            return false;
        }
        return hit instanceof Boolean && (Boolean) hit;
    }

    /** Reads the theme/settings a dungeon would use at a block position; null when unreadable. */
    private boolean variantMatches(World world, int blockX, int blockZ,
                                   String requiredTheme, String requiredSetting) {
        String[] resolved = resolveAt(world, blockX, blockZ);
        if (resolved == null) {
            return false;
        }
        if (requiredSetting != null) {
            return resolved[1] != null && requiredSetting.equalsIgnoreCase(resolved[1]);
        }
        return resolved[0] != null && requiredTheme.equalsIgnoreCase(resolved[0]);
    }

    /**
     * Five-sample biome pre-filter for a themed search. Samples stay inside the chunk, so this only
     * catches the cases the generator would also decide the same way; the exact origin check after
     * it remains the authority. {@code getBiome} does not load a chunk, which is what keeps a
     * full-radius themed search affordable.
     */
    private boolean chunkMayMatch(World world, int chunkX, int chunkZ, SearchState state) {
        int cx = (chunkX << 4) + 8;
        int cz = (chunkZ << 4) + 8;
        return variantMatches(world, cx, cz, state.requiredTheme, state.requiredSetting)
                || variantMatches(world, cx - 5, cz - 5, state.requiredTheme, state.requiredSetting)
                || variantMatches(world, cx + 5, cz - 5, state.requiredTheme, state.requiredSetting)
                || variantMatches(world, cx - 5, cz + 5, state.requiredTheme, state.requiredSetting)
                || variantMatches(world, cx + 5, cz + 5, state.requiredTheme, state.requiredSetting);
    }

    /**
     * Walks the chunk's {@code spawnAttempts} candidate points in the order the generator drew them
     * and returns the first one that actually carries dungeon blocks. Deliberately does not call
     * {@code Dungeon.selectLocation}: that method stops at the first point whose terrain check
     * passes, and the check reads neighbour chunks that were still half-built during population, so
     * on a finished world it can stop on an attempt that never received a dungeon.
     */
    private BlockPos originFor(World world, int chunkX, int chunkZ) {
        if (spawnFrequency <= 1 || spawnAttempts <= 0) {
            return null;
        }
        java.util.Random random = chunkRandom(world.getSeed(), chunkX, chunkZ);
        int radiusMax = spawnFrequency * 8;
        for (int attempt = 0; attempt < spawnAttempts; attempt++) {
            int radius = random.nextInt(radiusMax);
            double angle = random.nextDouble() * 2.0 * Math.PI;
            int x = (chunkX << 4) + (int) (Math.cos(angle) * radius);
            int z = (chunkZ << 4) + (int) (Math.sin(angle) * radius);
            if (looksLikeDungeon(world, x, z)) {
                return new BlockPos(x, upperLimit, z);
            }
        }
        return null;
    }

    private boolean originLookupReady() {
        return spawnFrequency > 1 && spawnAttempts > 0;
    }

    /**
     * The random Forge hands to every {@code IWorldGenerator} for a chunk. Rebuilt from the world
     * seed so the replay draws exactly the numbers the generator drew.
     */
    private static java.util.Random chunkRandom(long worldSeed, int chunkX, int chunkZ) {
        java.util.Random forge = new java.util.Random(worldSeed);
        // GameRegistry.generateWorld writes ">> 2 + 1L", which Java parses as ">> 3".
        long xSeed = forge.nextLong() >> 3;
        long zSeed = forge.nextLong() >> 3;
        return new java.util.Random((xSeed * chunkX + zSeed * chunkZ) ^ worldSeed);
    }

    /**
     * The themes the generator may pick from, read straight off the mod's own enum — no config file
     * parsing, and always in sync with the installed version. If the enum cannot be initialised we
     * fall back to listing {@code theme/builtin/Theme*.class}, which needs no class initialisation.
     */
    @Override
    public List<StructureVariant> getVariants(World world) {
        if (variants != null) {
            return variants;
        }
        Set<String> names = new LinkedHashSet<String>();
        String origin = "enum";
        try {
            Class<?> themes = Class.forName(THEMES_CLASS);
            Object[] constants = themes.getEnumConstants();
            if (constants != null) {
                for (Object constant : constants) {
                    names.add(((Enum<?>) constant).name());
                }
            }
        } catch (Throwable t) {
            Throwable cause = t.getCause() == null ? t : t.getCause();
            LOG.warn("[ROGUELIKE] cannot read {}: {}: {}", THEMES_CLASS,
                    cause.getClass().getName(), cause.getMessage());
        }
        if (names.isEmpty()) {
            origin = "jar-scan";
            names.addAll(scanThemeClasses());
        }
        List<StructureVariant> built = new ArrayList<StructureVariant>();
        for (String name : names) {
            if (name == null || name.trim().isEmpty()) {
                continue;
            }
            built.add(new StructureVariant(
                    STRUCTURE_ID + "/" + name.toLowerCase(Locale.ROOT),
                    STRUCTURE_ID, displayNameFor(name), VariantSource.RUNTIME_API));
        }
        addSettingVariants(built);
        LOG.info("[ROGUELIKE] {} variants ({} themes via {} + config settings)",
                built.size(), names.size(), origin);
        variants = built.isEmpty()
                ? Collections.<StructureVariant>emptyList()
                : Collections.unmodifiableList(built);
        return variants;
    }

    /** Lists {@code theme/builtin/Theme*.class} without touching the enum. */
    private static List<String> scanThemeClasses() {
        List<String> names = new ArrayList<String>();
        Class<?> anchor = Reflect.findClass(DUNGEON_CLASS);
        if (anchor == null) {
            return names;
        }
        String prefix = "greymerk/roguelike/theme/builtin/";
        try {
            java.security.CodeSource source = anchor.getProtectionDomain().getCodeSource();
            if (source == null) {
                return names;
            }
            java.io.File location = new java.io.File(source.getLocation().toURI());
            if (location.isDirectory()) {
                java.io.File[] files = new java.io.File(location, prefix).listFiles();
                if (files != null) {
                    for (java.io.File file : files) {
                        addThemeName(names, file.getName());
                    }
                }
            } else {
                java.util.jar.JarFile jar = new java.util.jar.JarFile(location);
                try {
                    java.util.Enumeration<java.util.jar.JarEntry> entries = jar.entries();
                    while (entries.hasMoreElements()) {
                        String entry = entries.nextElement().getName();
                        if (entry.startsWith(prefix) && entry.endsWith(".class")
                                && entry.indexOf('$') < 0) {
                            addThemeName(names, entry.substring(prefix.length()));
                        }
                    }
                } finally {
                    jar.close();
                }
            }
        } catch (Throwable ignored) {
            // A missing or unreadable code source just means "no fallback list".
        }
        Collections.sort(names);
        return names;
    }

    private static void addThemeName(List<String> names, String fileName) {
        if (!fileName.endsWith(".class")) {
            return;
        }
        String simple = fileName.substring(0, fileName.length() - ".class".length());
        if (simple.startsWith("Theme") && simple.length() > "Theme".length()) {
            names.add(simple.substring("Theme".length()));
        }
    }

    /**
     * Adds one variant per choosable dungeon setting. Pack authors define whole dungeons here with
     * their own theme (the ocean temple is prismarine and quartz, not any enum theme), so without
     * these rows those dungeons match no variant at all.
     */
    private static void addSettingVariants(List<StructureVariant> out) {
        if (Loader.instance() == null) {
            return;
        }
        java.io.File root = new java.io.File(Loader.instance().getConfigDir(),
                "roguelike_dungeons/settings");
        for (String sub : SETTING_DIRS) {
            collectSettingFiles(new java.io.File(root, sub), out);
        }
    }

    private static void collectSettingFiles(java.io.File dir, List<StructureVariant> out) {
        java.io.File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (java.io.File file : files) {
            if (file.isDirectory()) {
                collectSettingFiles(file, out);
                continue;
            }
            if (!file.getName().endsWith(".json")) {
                continue;
            }
            String settingId = readSettingName(file);
            if (settingId == null || settingId.isEmpty() || settingId.endsWith("_base")) {
                continue;
            }
            out.add(new StructureVariant(STRUCTURE_ID + SETTING_MARKER + settingId, STRUCTURE_ID,
                    settingDisplayName(settingId), VariantSource.CONFIG_FILE));
        }
    }

    /** Reads the leading {@code "name"} field; a full JSON parser would be overkill here. */
    private static String readSettingName(java.io.File file) {
        java.io.BufferedReader reader = null;
        try {
            reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                    new java.io.FileInputStream(file), java.nio.charset.StandardCharsets.UTF_8));
            String line;
            int guard = 0;
            while ((line = reader.readLine()) != null && guard++ < 20) {
                int key = line.indexOf("\"name\"");
                if (key < 0) {
                    continue;
                }
                int first = line.indexOf('"', key + 6);
                int second = first < 0 ? -1 : line.indexOf('"', first + 1);
                if (first >= 0 && second > first) {
                    return line.substring(first + 1, second);
                }
            }
        } catch (Throwable ignored) {
            // Unreadable file: skip it rather than failing the whole variant list.
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Throwable ignored) {
                    // nothing to do
                }
            }
        }
        return null;
    }

    /** {@code fnar:dungeon_ocean_temple} becomes {@code Ocean Temple Dungeon}. */
    private static String settingDisplayName(String settingId) {
        String bare = settingId;
        int colon = bare.indexOf(':');
        if (colon >= 0) {
            bare = bare.substring(colon + 1);
        }
        if (bare.startsWith("dungeon_")) {
            bare = bare.substring("dungeon_".length());
        }
        if (bare.endsWith("_no_loot")) {
            bare = bare.substring(0, bare.length() - "_no_loot".length());
        }
        StringBuilder out = new StringBuilder();
        for (String part : bare.split("_")) {
            if (part.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.length() == 0 ? "Dungeon" : out.append(" Dungeon").toString();
    }

    /**
     * {@code NETHER_FORTRESS} becomes {@code Nether Fortress Dungeon}. No marker: the theme is
     * replayed from the world seed, so a row here really is a filter the search can honour.
     */
    private static String displayNameFor(String theme) {
        StringBuilder out = new StringBuilder();
        for (String part : theme.split("_")) {
            if (part.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(part.charAt(0)))
                    .append(part.substring(1).toLowerCase(Locale.ROOT));
        }
        return out.length() == 0 ? "Dungeon" : out.append(" Dungeon").toString();
    }

    /**
     * A themed search is answered by replaying the settings choice for each candidate chunk and
     * keeping only the chunks whose theme matches — the position is exact, not a guess.
     */
    @Override
    public StructureResult findNearest(World world, BlockPos playerPos, String structureId, int radius,
                                       String variantId) {
        if (variantId == null || variantId.isEmpty()) {
            return findNearest(world, playerPos, radius, null, null);
        }
        if (variantId.contains(SETTING_MARKER)) {
            String setting = variantId.substring(variantId.indexOf(SETTING_MARKER) + SETTING_MARKER.length());
            StructureResult bySetting = findNearest(world, playerPos, radius, null, setting);
            return bySetting == null ? null : bySetting.withVariant(variantId, true);
        }
        String theme = themeFromVariantId(variantId);
        if (theme == null) {
            StructureResult any = findNearest(world, playerPos, radius, null, null);
            return any == null ? null : any.withVariant(variantId, false);
        }
        StructureResult result = findNearest(world, playerPos, radius, theme, null);
        return result == null ? null : result.withVariant(variantId, true);
    }

    /** {@code roguelike:dungeon/nether_fortress} becomes {@code NETHER_FORTRESS}. */
    private static String themeFromVariantId(String variantId) {
        int slash = variantId.lastIndexOf('/');
        if (slash < 0 || slash == variantId.length() - 1) {
            return null;
        }
        return variantId.substring(slash + 1).toUpperCase(Locale.ROOT);
    }

    private void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        dungeonClass = Reflect.findClass(DUNGEON_CLASS);
        worldEditorClass = Reflect.findClass(WORLD_EDITOR_CLASS);
        Class<?> editorInterface = Reflect.findClass(WORLD_EDITOR_INTERFACE);
        if (dungeonClass == null || worldEditorClass == null || editorInterface == null) {
            return;
        }
        canSpawnInChunk = Reflect.findMethod(dungeonClass, "canSpawnInChunk",
                int.class, int.class, editorInterface);
        if (canSpawnInChunk == null) {
            LOG.warn("[ROGUELIKE] could not resolve Dungeon.canSpawnInChunk(int, int, WorldEditor)");
            return;
        }
        resolveThemeLookup(editorInterface);
        resolveOriginLookup();
    }

    /**
     * Resolves the two numbers the origin replay needs: the chunk grid the frequency gate accepts
     * and how many points the generator tries inside it. Both come from the mod, so a config change
     * is followed without a code change.
     */
    private void resolveOriginLookup() {
        if (originLookupResolved) {
            return;
        }
        originLookupResolved = true;
        Method frequency = Reflect.findMethod(dungeonClass, "getSpawnFrequency");
        if (frequency != null) {
            Object value = Reflect.invoke(null, frequency);
            if (value instanceof Number) {
                spawnFrequency = ((Number) value).intValue();
            }
        }
        Class<?> configClass = Reflect.findClass(ROGUE_CONFIG_CLASS);
        if (configClass != null) {
            Method getInt = Reflect.findMethod(configClass, "getInt");
            Object frequencyConfig = Reflect.readStatic(configClass, "DUNGEONS_SPAWN_FREQUENCY");
            Object upper = Reflect.readStatic(configClass, UPPER_LIMIT_CONFIG);
            Object attempts = Reflect.readStatic(configClass, "DUNGEONS_SPAWN_ATTEMPTS");
            if (getInt != null) {
                // Fallback if getSpawnFrequency() was unavailable: the same 3 * max(2, config)
                // formula the method applies.
                if (spawnFrequency <= 1 && frequencyConfig != null) {
                    Object value = Reflect.invoke(frequencyConfig, getInt);
                    if (value instanceof Number) {
                        spawnFrequency = 3 * Math.max(2, ((Number) value).intValue());
                    }
                }
                if (upper != null) {
                    Object value = Reflect.invoke(upper, getInt);
                    if (value instanceof Number) {
                        upperLimit = ((Number) value).intValue();
                    }
                }
                if (attempts != null) {
                    Object value = Reflect.invoke(attempts, getInt);
                    if (value instanceof Number) {
                        spawnAttempts = ((Number) value).intValue();
                    }
                }
            }
        }
        LOG.info("[ROGUELIKE] spawn grid every {} chunks, {} attempts per chunk, first air at y={}",
                spawnFrequency, spawnAttempts, upperLimit);
        if (spawnFrequency <= 1) {
            LOG.warn("[ROGUELIKE] spawn frequency unknown; exact origins are disabled");
        }
    }

    private void resolveThemeLookup(Class<?> editorInterface) {
        if (themeLookupResolved) {
            return;
        }
        themeLookupResolved = true;
        Class<?> resolverClass = Reflect.findClass(SETTINGS_RESOLVER_CLASS);
        Class<?> settingsClass = Reflect.findClass(DUNGEON_SETTINGS_CLASS);
        Class<?> levelClass = Reflect.findClass(LEVEL_SETTINGS_CLASS);
        Class<?> coordClass = Reflect.findClass(COORD_CLASS);
        Class<?> themesClass = Reflect.findClass(THEMES_CLASS);
        if (resolverClass == null || settingsClass == null || levelClass == null
                || coordClass == null || themesClass == null) {
            themeLookupBroken = true;
            return;
        }
        settingsResolver = Reflect.readStatic(dungeonClass, "settingsResolver");
        chooseDungeonSetting = Reflect.findMethod(resolverClass, "chooseDungeonSetting",
                editorInterface, coordClass);
        settingsGetLevels = Reflect.findMethod(settingsClass, "getLevelSettings");
        settingsGetId = Reflect.findMethod(settingsClass, "getId");
        levelGetTheme = Reflect.findMethod(levelClass, "getTheme");
        themeGetBase = Reflect.findMethod(themesClass, "getThemeBase");
        try {
            coordConstructor = coordClass.getConstructor(int.class, int.class, int.class);
        } catch (Throwable t) {
            themeLookupBroken = true;
        }
        if (settingsResolver == null || chooseDungeonSetting == null || settingsGetLevels == null
                || levelGetTheme == null || themeGetBase == null || coordConstructor == null) {
            themeLookupBroken = true;
            LOG.info("[ROGUELIKE] theme replay unavailable; themed searches fall back to the parent");
        }
    }

    /**
     * Replays the settings choice for one block position with a fresh editor. That mirrors
     * generation exactly: the generator also builds a new editor per chunk, and nothing consumes its
     * random before {@code chooseDungeonSetting}. Returns {@code {themeName, settingsId}}; either
     * half may be null when that layer could not be read.
     */
    private String[] resolveAt(World world, int blockX, int blockZ) {
        if (themeLookupBroken || !themeLookupResolved || worldEditorClass == null) {
            return null;
        }
        Object resolver = settingsResolver != null
                ? settingsResolver
                : Reflect.readStatic(dungeonClass, "settingsResolver");
        if (resolver == null) {
            themeLookupBroken = true;
            themeDiag("settingsResolver is null");
            return null;
        }
        try {
            Object editor = Reflect.newInstance(worldEditorClass, new Class<?>[]{World.class}, world);
            if (editor == null) {
                themeDiag("editor creation failed");
                return null;
            }
            Object coord = coordConstructor.newInstance(blockX, 64, blockZ);
            Object chosen = chooseDungeonSetting.invoke(resolver, editor, coord);
            if (!(chosen instanceof java.util.Optional)) {
                themeDiag("chooseDungeonSetting returned " + (chosen == null ? "null" : chosen.getClass().getName()));
                return null;
            }
            java.util.Optional<?> optional = (java.util.Optional<?>) chosen;
            if (!optional.isPresent()) {
                themeDiag("no settings at " + blockX + "," + blockZ);
                return null;
            }
            Object settings = optional.get();
            Object levels = settingsGetLevels.invoke(settings);
            if (!(levels instanceof java.util.Map) || ((java.util.Map<?, ?>) levels).isEmpty()) {
                themeDiag("settings without levels: " + settings);
                return null;
            }
            Object firstLevel = ((java.util.Map<?, ?>) levels).values().iterator().next();
            String theme = themeNameOf(levelGetTheme.invoke(firstLevel));
            String id = settingsGetId == null ? null : String.valueOf(Reflect.invoke(settings, settingsGetId));
            if (theme == null) {
                themeDiag("unmatched theme " + describeTheme(levelGetTheme.invoke(firstLevel)));
            }
            return new String[]{theme, id};
        } catch (Throwable t) {
            themeLookupBroken = true;
            Throwable cause = t.getCause() == null ? t : t.getCause();
            themeDiag("threw " + cause.getClass().getName() + ": " + cause.getMessage());
            return null;
        }
    }

    private void themeDiag(String message) {
        if (themeDiagLogged) {
            return;
        }
        themeDiagLogged = true;
        LOG.info("[ROGUELIKE] theme replay: {}", message);
    }

    /**
     * Short label for a theme the enum does not know: the distinct block names it is built from.
     * The full {@code Theme.toString()} is over 1 KB and buries everything else in the log.
     */
    private static String describeTheme(Object theme) {
        if (theme == null) {
            return "null";
        }
        String text = String.valueOf(theme);
        Set<String> blocks = new LinkedHashSet<String>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"name\":\"([^\"]+)\"").matcher(text);
        while (matcher.find() && blocks.size() < 6) {
            blocks.add(matcher.group(1));
        }
        return blocks.isEmpty() ? text.substring(0, Math.min(80, text.length())) : blocks.toString();
    }

    /**
     * Maps a live {@code Theme} back to its enum constant name. Settings may hold a copy built by
     * {@code Theme.inherit}, so identity is tried first and the rendered form second.
     */
    private String themeNameOf(Object theme) {
        if (theme == null) {
            return null;
        }
        Class<?> themesClass = Reflect.findClass(THEMES_CLASS);
        if (themesClass == null) {
            return null;
        }
        Object[] constants = themesClass.getEnumConstants();
        if (constants == null) {
            return null;
        }
        for (Object constant : constants) {
            if (Reflect.invoke(constant, themeGetBase) == theme) {
                return ((Enum<?>) constant).name();
            }
        }
        String target = String.valueOf(theme);
        for (Object constant : constants) {
            Object base = Reflect.invoke(constant, themeGetBase);
            if (base != null && target.equals(String.valueOf(base))) {
                return ((Enum<?>) constant).name();
            }
        }
        return null;
    }
}
