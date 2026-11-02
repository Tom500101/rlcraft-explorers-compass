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

    /** Doomlike ships one config file per theme; the file name is the theme name. */
    private static final String THEME_DIRECTORY = "DLDungeonsJBG/themes";

    /** Kept small on purpose — each check may generate a chunk. */
    private static final long CHECK_BUDGET = 20_000L;

    public static final String STRUCTURE_ID = "doomlike:dungeon";

    private boolean resolved;
    private Method isDungeonCenter;
    private Object handler;
    private boolean warnedMissing;

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
        resolve();
        if (isDungeonCenter == null || handler == null) {
            if (!warnedMissing) {
                warnedMissing = true;
                LOG.info("[DOOMLIKE] Doomlike Dungeons is not installed; {} stays unavailable", STRUCTURE_ID);
            }
            return null;
        }

        long seed = world.getSeed();
        int dimension = world.provider.getDimension();
        int centerChunkX = playerPos.getX() >> 4;
        int centerChunkZ = playerPos.getZ() >> 4;
        int maxRing = Math.max(1, radius >> 4);
        double maxDistanceSq = (double) radius * (double) radius;
        long budget = CHECK_BUDGET;
        long startedAt = System.currentTimeMillis();

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
                    BlockPos candidate = new BlockPos((chunkX << 4) + 8, 64, (chunkZ << 4) + 8);
                    if (StructureResult.horizontalDistanceSq(candidate, playerPos) > maxDistanceSq) {
                        continue;
                    }
                    return StructureResult.of(STRUCTURE_ID, candidate, sourceName(), playerPos);
                }
            }
        }
        return null;
    }

    /**
     * Themes are config files; the directory is the only reliable list. There is no runtime theme
     * query, so these are CONFIG_FILE variants: the picker can list them, but a search can never
     * confirm which theme the generator actually placed.
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

    /**
     * The marker matches {@link RoguelikeLocator}: Doomlike also picks the theme while generating
     * and stores nothing, so a row here is a label the search cannot filter on.
     */
    private static String displayNameFor(String theme) {
        if (theme.isEmpty()) {
            return "Dungeon ≈";
        }
        return Character.toUpperCase(theme.charAt(0)) + theme.substring(1) + " Dungeon ≈";
    }

    /**
     * Themed search is a request for the parent plus a label: Doomlike picks the theme at
     * generation time and exposes no lookup, so the result is always marked unconfirmed.
     */
    @Override
    public StructureResult findNearest(World world, BlockPos playerPos, String structureId, int radius,
                                       String variantId) {
        StructureResult result = findNearest(world, playerPos, structureId, radius);
        if (result == null || variantId == null || variantId.isEmpty()) {
            return result;
        }
        return result.withVariant(variantId, false);
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
    }
}
