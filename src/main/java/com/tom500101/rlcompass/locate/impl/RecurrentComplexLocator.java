package com.tom500101.rlcompass.locate.impl;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.gen.structure.StructureBoundingBox;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.tom500101.rlcompass.locate.IStructureLocator;
import com.tom500101.rlcompass.locate.StructureResult;
import com.tom500101.rlcompass.locate.StructureVariant;
import com.tom500101.rlcompass.util.Reflect;

/**
 * Locates Recurrent Complex structures.
 *
 * <p>The stage-1 probe showed RC replacing the vanilla stronghold/village/temple/monument generators
 * with wrappers, which means those four answer as the <em>vanilla</em> structures — RC's own content
 * (the "Forest Stronghold" style builds) never showed up anywhere.
 *
 * <p>It is reachable after all. RC keeps a {@code WorldSavedData} of everything it has generated:
 *
 * <pre>
 * WorldStructureGenerationData.get(World)            // per-dimension WorldSavedData
 *   └─ entryMap : Map&lt;UUID, Entry&gt;
 *        └─ Entry.getBoundingBox() : StructureBoundingBox
 *             └─ StructureEntry.getStructureID() : String
 * </pre>
 *
 * <p>RC structures are anchored structures generated at fixed predicted positions, so unlike the
 * other mods there is no "search" to do — the store already contains every instance.
 *
 * <h2>Variants</h2>
 * Because each entry records the exact structure that was placed, every RC structure id is a
 * searchable variant: filtering the store by {@code structureID} is exact, never an approximation.
 * The id list itself comes from the installed structure packs — {@code <gameDir>/structures/active}
 * plus the packs bundled in the mod jar — which the client can read without asking the server.
 * Only generated structures can be found, so a structure that has not been placed yet simply
 * reports "not found".
 */
public class RecurrentComplexLocator implements IStructureLocator {

    private static final Logger LOG = LogManager.getLogger("rlcompass");

    private static final String DATA_CLASS =
            "ivorius.reccomplex.world.gen.feature.WorldStructureGenerationData";
    private static final String MOD_ID = "reccomplex";
    /** RC packs are zipped {@code .rcst} archives; a loose file per structure. */
    private static final String STRUCTURE_EXTENSION = ".rcst";
    private static final String JAR_PREFIX = "assets/reccomplex/structures/active/";

    public static final String STRUCTURE_ID = "reccomplex:structure";

    /** Built once per session: the packs on disk do not change while the game is running. */
    private static List<StructureVariant> variantCache;

    private boolean warnedMissing;
    private boolean loggedEmpty;
    private String lastReported;

    @Override
    public Collection<String> getSupportedIds() {
        return Collections.singletonList(STRUCTURE_ID);
    }

    @Override
    public String sourceName() {
        return "RecurrentComplex";
    }

    @Override
    public List<StructureVariant> getVariants(World world) {
        return variants();
    }

    @Override
    public StructureResult findNearest(World world, BlockPos playerPos, String structureId, int radius) {
        return nearest(world, playerPos, radius, null);
    }

    /**
     * Typed search: keep only the entries RC recorded under {@code variantId}'s structure id.
     * No replay is involved — the store already knows which structure was placed.
     */
    @Override
    public StructureResult findNearest(World world, BlockPos playerPos, String structureId, int radius,
                                       String variantId) {
        if (variantId == null || variantId.isEmpty()) {
            return nearest(world, playerPos, radius, null);
        }
        String wanted = suffix(variantId);
        StructureResult result = nearest(world, playerPos, radius, wanted);
        return result == null ? null : result.withVariant(variantId, true);
    }

    private StructureResult nearest(World world, BlockPos playerPos, int radius, String wantedId) {
        Map<?, ?> entryMap = entries(world);
        if (entryMap == null || entryMap.isEmpty()) {
            return null;
        }
        double maxDistanceSq = (double) radius * (double) radius;
        double bestDistanceSq = Double.MAX_VALUE;
        BlockPos best = null;
        String bestLabel = null;

        Object[] entries;
        try {
            entries = entryMap.values().toArray();
        } catch (Throwable t) {
            return null;
        }
        for (Object entry : entries) {
            Object rawBox = Reflect.invoke(entry, "getBoundingBox");
            if (!(rawBox instanceof StructureBoundingBox)) {
                continue;
            }
            String label = null;
            if (wantedId != null) {
                Object id = Reflect.invoke(entry, "getStructureID");
                label = id instanceof String ? (String) id : null;
                if (!matches(label, wantedId)) {
                    continue;
                }
            }
            StructureBoundingBox box = (StructureBoundingBox) rawBox;
            BlockPos centre = new BlockPos(
                    (box.minX + box.maxX) / 2,
                    (box.minY + box.maxY) / 2,
                    (box.minZ + box.maxZ) / 2);
            double distanceSq = StructureResult.horizontalDistanceSq(centre, playerPos);
            if (distanceSq > maxDistanceSq || distanceSq >= bestDistanceSq) {
                continue;
            }
            bestDistanceSq = distanceSq;
            best = centre;
            bestLabel = label;
        }
        if (best == null) {
            if (wantedId != null) {
                LOG.debug("[RC] no '{}' generated within {} blocks ({} entries known)",
                        wantedId, radius, entryMap.size());
            }
            return null;
        }
        if (bestLabel != null && !bestLabel.equals(lastReported)) {
            lastReported = bestLabel;
            LOG.info("[RC] nearest structure is '{}' at ({}, {}, {})",
                    bestLabel, best.getX(), best.getY(), best.getZ());
        }
        return new StructureResult(STRUCTURE_ID, best, sourceName(), bestDistanceSq);
    }

    /** Reads RC's per-dimension store, or {@code null} when RC is absent or unreadable. */
    private Map<?, ?> entries(World world) {
        Class<?> dataClass = Reflect.findClass(DATA_CLASS);
        if (dataClass == null) {
            if (!warnedMissing) {
                warnedMissing = true;
                LOG.info("[RC] Recurrent Complex is not installed; {} stays unavailable", STRUCTURE_ID);
            }
            return null;
        }
        java.lang.reflect.Method getData = Reflect.findMethod(dataClass, "get", World.class);
        if (getData == null) {
            if (!loggedEmpty) {
                loggedEmpty = true;
                LOG.info("[RC] cannot resolve WorldStructureGenerationData.get(World)");
            }
            return null;
        }
        Object data;
        try {
            data = getData.invoke(null, world);
        } catch (Throwable t) {
            if (!loggedEmpty) {
                loggedEmpty = true;
                Throwable cause = t.getCause() == null ? t : t.getCause();
                LOG.info("[RC] WorldStructureGenerationData.get(World) threw {}: {}",
                        cause.getClass().getName(), cause.getMessage());
            }
            return null;
        }
        if (data == null) {
            if (!loggedEmpty) {
                loggedEmpty = true;
                LOG.info("[RC] WorldStructureGenerationData.get(World) returned null");
            }
            return null;
        }
        Object rawEntries = Reflect.read(data, "entryMap");
        if (!(rawEntries instanceof Map)) {
            LOG.info("[RC] WorldStructureGenerationData.entryMap not readable ({})", rawEntries);
            return null;
        }
        Map<?, ?> entryMap = (Map<?, ?>) rawEntries;
        if (entryMap.isEmpty() && !loggedEmpty) {
            loggedEmpty = true;
            LOG.info("[RC] RC has not generated any structures in dimension {} yet",
                    world.provider.getDimension());
        }
        return entryMap;
    }

    /**
     * The recorded id may be the bare file name or a pack-relative path, depending on how the pack
     * was shipped, so a path suffix counts as a match too.
     */
    private static boolean matches(String recordedId, String wantedId) {
        if (recordedId == null) {
            return false;
        }
        if (recordedId.equalsIgnoreCase(wantedId)) {
            return true;
        }
        return recordedId.toLowerCase(Locale.ROOT).endsWith("/" + wantedId.toLowerCase(Locale.ROOT));
    }

    private static String suffix(String variantId) {
        int slash = variantId.lastIndexOf('/');
        return slash < 0 ? variantId : variantId.substring(slash + 1);
    }

    // ---------------------------------------------------------------- variants

    private static synchronized List<StructureVariant> variants() {
        if (variantCache != null) {
            return variantCache;
        }
        Set<String> ids = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        File directory = activeStructureDirectory();
        if (directory != null) {
            collectFromDirectory(directory, ids);
        }
        collectFromJar(ids);

        List<StructureVariant> out = new ArrayList<StructureVariant>(ids.size());
        for (String id : ids) {
            out.add(new StructureVariant(STRUCTURE_ID + "/" + id, STRUCTURE_ID, id,
                    StructureVariant.VariantSource.RUNTIME_API));
        }
        variantCache = out;
        LOG.info("[RC] {} structure packs available as variants", out.size());
        return out;
    }

    private static File activeStructureDirectory() {
        try {
            File configDir = Loader.instance().getConfigDir();
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

    private static void collectFromDirectory(File dir, Set<String> ids) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                collectFromDirectory(file, ids);
            } else {
                addId(file.getName(), ids);
            }
        }
    }

    private static void collectFromJar(Set<String> ids) {
        try {
            ModContainer container = Loader.instance().getIndexedModList().get(MOD_ID);
            File source = container == null ? null : container.getSource();
            if (source == null || !source.isFile()) {
                return;
            }
            ZipFile zip = new ZipFile(source);
            try {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory() || !entry.getName().startsWith(JAR_PREFIX)) {
                        continue;
                    }
                    addId(entry.getName().substring(entry.getName().lastIndexOf('/') + 1), ids);
                }
            } finally {
                zip.close();
            }
        } catch (Throwable t) {
            LOG.warn("[RC] could not list bundled structure packs: {}", t.toString());
        }
    }

    private static void addId(String fileName, Set<String> ids) {
        if (fileName == null || !fileName.toLowerCase(Locale.ROOT).endsWith(STRUCTURE_EXTENSION)) {
            return;
        }
        String id = fileName.substring(0, fileName.length() - STRUCTURE_EXTENSION.length()).trim();
        if (!id.isEmpty()) {
            ids.add(id);
        }
    }
}
