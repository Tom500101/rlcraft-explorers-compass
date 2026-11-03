package com.tom500101.rlcompass.locate.impl;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

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
 * Locates Doomlike Dungeons.
 *
 * <p>The stage-1 probe found {@code GenerationHandler} carries only static config
 * ({@code frequency}, {@code factor}, {@code minXZ}, {@code mrand}, {@code dimensions}) and no
 * position store, which made this look like a feature-block job. The mod does expose the placement
 * rule, though:
 *
 * <pre>
 * GenerationHandler.isDungeonCenter(World, long worldSeed, int chunkX, int chunkZ, int dimension)
 * </pre>
 *
 * <p>That method is private but side-effect free apart from {@code World#getBiome}, and the mod
 * itself calls it in exactly this way while walking a bounded chunk window.
 *
 * <h2>Cost</h2>
 * Unlike {@link RoguelikeLocator}, every candidate here pays a {@code getBiome} call, which can
 * generate a chunk — the same shape of problem that made vanilla mineshafts take 9 seconds. The check
 * budget is therefore deliberately small; dungeons are dense enough that the nearest one shows up
 * well inside it.
 */
public class DoomlikeLocator implements IStructureLocator {

    private static final Logger LOG = LogManager.getLogger("rlcompass");

    private static final String HANDLER_CLASS = "jaredbgreat.dldungeons.GenerationHandler";
    private static final String SPATIAL_HASH_CLASS = "jaredbgreat.dldungeons.util.math.SpatialHash";
    private static final String BIOME_SETS_CLASS = "jaredbgreat.dldungeons.themes.BiomeSets";
    private static final String THEME_CLASS = "jaredbgreat.dldungeons.themes.Theme";

    /** Doomlike ships one config file per theme; the file name is the theme name. */
    private static final String THEME_DIRECTORY = "DLDungeonsJBG/themes";

    /** Kept small on purpose — each check may generate a chunk. */
    private static final long CHECK_BUDGET = 20_000L;

    public static final String STRUCTURE_ID = "doomlike:dungeon";

    private boolean resolved;
    private Method isDungeonCenter;
    private Method randomFromSeed;
    private Method getTheme;
    private Object handler;
    private boolean warnedMissing;
    private boolean warnedReplay;

    @Override
    public Collection<String> getSupportedIds() {
        return Collections.singletonList(STRUCTURE_ID);
    }

    @Override
    public String sourceName() {
        return "Doomlike";
    }

    @Override
    public StructureResult findNearest(World world, BlockPos playerPos, String structureId, int radius) {
        return search(world, playerPos, radius, null);
    }

    /**
     * A themed search replays {@code BiomeSets.getTheme(biome, random, dimension)} with the same
     * {@code Random} the generator uses, so the answer is the real theme, not a label.
     */
    private StructureResult search(World world, BlockPos playerPos, int radius, String variantId) {
        resolve();
        if (isDungeonCenter == null || handler == null) {
            if (!warnedMissing) {
                warnedMissing = true;
                LOG.info("[DOOMLIKE] Doomlike Dungeons is not installed; {} stays unavailable", STRUCTURE_ID);
            }
            return null;
        }
        if (variantId != null && (randomFromSeed == null || getTheme == null)) {
            if (!warnedReplay) {
                warnedReplay = true;
                LOG.info("[DOOMLIKE] theme replay unavailable; themed searches fall back to the parent");
            }
            variantId = null;
        }

        long seed = world.getSeed();
        int dimension = world.provider.getDimension();
        int centerChunkX = playerPos.getX() >> 4;
        int centerChunkZ = playerPos.getZ() >> 4;
        int maxRing = Math.max(1, radius >> 4);
        double maxDistanceSq = (double) radius * (double) radius;
        long budget = CHECK_BUDGET;
        long startedAt = System.currentTimeMillis();
        String wanted = variantId == null ? null : variantSuffix(variantId);

        for (int ring = 0; ring <= maxRing; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.abs(dx) != ring && Math.abs(dz) != ring) {
                        continue;
                    }
                    if (--budget < 0) {
                        LOG.warn("[DOOMLIKE] gave up after {} chunk checks (radius {}, {} ms)",
                                CHECK_BUDGET, radius, System.currentTimeMillis() - startedAt);
                        return null;
                    }
                    int chunkX = centerChunkX + dx;
                    int chunkZ = centerChunkZ + dz;
                    Object hit = Reflect.invoke(handler, isDungeonCenter, world, seed, chunkX, chunkZ, dimension);
                    if (!(hit instanceof Boolean) || !((Boolean) hit)) {
                        continue;
                    }
                    if (wanted != null) {
                        String theme = themeAt(world, seed, dimension, chunkX, chunkZ);
                        if (theme == null || !wanted.equals(theme)) {
                            continue;
                        }
                    }
                    BlockPos candidate = new BlockPos((chunkX << 4) + 8, 64, (chunkZ << 4) + 8);
                    if (StructureResult.horizontalDistanceSq(candidate, playerPos) > maxDistanceSq) {
                        continue;
                    }
                    StructureResult result = StructureResult.of(STRUCTURE_ID, candidate, sourceName(), playerPos);
                    if (variantId == null) {
                        return result;
                    }
                    LOG.info("[DOOMLIKE] {} at ({}, {}) uses theme {}", variantId,
                            candidate.getX(), candidate.getZ(), wanted);
                    return result.withVariant(variantId, true);
                }
            }
        }
        return null;
    }

    /**
     * Replays the theme choice for one dungeon chunk. {@code Builder.placeDungeon} builds the
     * {@code Dungeon} with {@code SpatialHash.randomFromSeed(worldSeed, chunkX, chunkZ, dim)}, and
     * the first consumer of that random is {@code BiomeSets.getTheme(biome, random, dim)} — the same
     * order used here.
     */
    private String themeAt(World world, long seed, int dimension, int chunkX, int chunkZ) {
        Object random = Reflect.invoke(null, randomFromSeed, seed, chunkX, chunkZ, dimension);
        if (!(random instanceof java.util.Random)) {
            return null;
        }
        Object biome = world.getBiome(new BlockPos((chunkX << 4) + 8, 64, (chunkZ << 4) + 8));
        Object theme = Reflect.invoke(null, getTheme, biome, random, dimension);
        if (theme == null) {
            return null;
        }
        Object name = Reflect.read(theme, "name");
        return name == null ? null : normalizeThemeName(String.valueOf(name));
    }

    /** {@code doomlike:dungeon/desert} becomes {@code desert}. */
    private static String variantSuffix(String variantId) {
        int slash = variantId.lastIndexOf('/');
        return (slash < 0 ? variantId : variantId.substring(slash + 1)).toLowerCase(Locale.ROOT);
    }

    /** {@code .../themes/desert.cfg} and {@code desert} both become {@code desert}. */
    private static String normalizeThemeName(String raw) {
        String name = raw;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        return name.toLowerCase(Locale.ROOT);
    }

    /**
     * Themes are config files; the directory is the complete list. The generator picks one per
     * dungeon from the same list, and {@link #themeAt} can now replay that choice, so a row here is
     * a real filter.
     */
    @Override
    public List<StructureVariant> getVariants(World world) {
        if (Loader.instance() == null) {
            return Collections.emptyList();
        }
        File directory = new File(Loader.instance().getConfigDir(), THEME_DIRECTORY);
        File[] files = directory.listFiles();
        if (files == null) {
            return Collections.emptyList();
        }
        List<StructureVariant> variants = new ArrayList<StructureVariant>();
        for (File file : files) {
            if (file == null || !file.isFile()) {
                continue;
            }
            String name = file.getName();
            int dot = name.lastIndexOf('.');
            if (dot <= 0) {
                continue;
            }
            String extension = name.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (!"cfg".equals(extension) && !"json".equals(extension)) {
                continue;
            }
            String theme = name.substring(0, dot);
            if ("template".equalsIgnoreCase(theme)) {
                continue; // shipped example, not a generated theme
            }
            variants.add(new StructureVariant(STRUCTURE_ID + "/" + theme.toLowerCase(Locale.ROOT),
                    STRUCTURE_ID, displayNameFor(theme), VariantSource.CONFIG_FILE));
        }
        return variants;
    }

    /** {@code desert.cfg} becomes {@code Desert Dungeon}; the theme is replayable, so no marker. */
    private static String displayNameFor(String theme) {
        if (theme.isEmpty()) {
            return "Dungeon";
        }
        return Character.toUpperCase(theme.charAt(0)) + theme.substring(1) + " Dungeon";
    }

    @Override
    public StructureResult findNearest(World world, BlockPos playerPos, String structureId, int radius,
                                       String variantId) {
        if (variantId == null || variantId.isEmpty()) {
            return findNearest(world, playerPos, structureId, radius);
        }
        return search(world, playerPos, radius, variantId);
    }

    private void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        Class<?> handlerClass = Reflect.findClass(HANDLER_CLASS);
        if (handlerClass == null) {
            return;
        }
        // GenerationHandler holds no instance state (all config is static), so a fresh instance is
        // equivalent to the one Forge registered.
        handler = Reflect.newInstance(handlerClass, new Class<?>[0]);
        if (handler == null) {
            LOG.warn("[DOOMLIKE] could not instantiate {}", HANDLER_CLASS);
            return;
        }
        isDungeonCenter = Reflect.findMethod(handlerClass, "isDungeonCenter",
                World.class, long.class, int.class, int.class, int.class);
        if (isDungeonCenter == null) {
            LOG.warn("[DOOMLIKE] could not resolve GenerationHandler.isDungeonCenter(...)");
        }

        Class<?> spatialHash = Reflect.findClass(SPATIAL_HASH_CLASS);
        if (spatialHash != null) {
            randomFromSeed = Reflect.findMethod(spatialHash, "randomFromSeed",
                    long.class, int.class, int.class, int.class);
        }
        Class<?> biomeSets = Reflect.findClass(BIOME_SETS_CLASS);
        if (biomeSets != null) {
            getTheme = Reflect.findMethod(biomeSets, "getTheme",
                    net.minecraft.world.biome.Biome.class, java.util.Random.class, int.class);
        }
        if (randomFromSeed == null || getTheme == null) {
            LOG.info("[DOOMLIKE] theme replay unavailable; themed searches fall back to the parent");
        }
    }
}
