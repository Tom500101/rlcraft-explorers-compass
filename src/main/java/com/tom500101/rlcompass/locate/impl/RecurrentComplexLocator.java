package com.tom500101.rlcompass.locate.impl;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.HashMap;
import java.util.Locale;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.gen.structure.StructureBoundingBox;
import net.minecraftforge.common.BiomeDictionary;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.tom500101.rlcompass.cache.RecurrentComplexData;
import com.tom500101.rlcompass.cache.SearchMemory;
import com.tom500101.rlcompass.locate.IStructureLocator;
import com.tom500101.rlcompass.locate.StructureResult;
import com.tom500101.rlcompass.locate.StructureVariant;
import com.tom500101.rlcompass.util.Reflect;

/**
 * Locates Recurrent Complex structures.
 *
 * <p>The stage-1 probe showed RC replacing the vanilla stronghold/village/temple/monument generators
 * with wrappers, which means those four answer as the <em>vanilla</em> structures — RC's own content
 * never showed up anywhere.
 *
 * <p>RC keeps a {@code WorldSavedData} of everything it has generated, and each entry records the
 * structure id that was placed:
 *
 * <pre>
 * WorldStructureGenerationData.get(World)            // per-dimension WorldSavedData
 *   └─ entryMap : Map&lt;UUID, Entry&gt;
 *        └─ Entry.getBoundingBox() : StructureBoundingBox
 *             └─ StructureEntry.getStructureID() : String
 * </pre>
 *
 * <p><b>On RLCraft that map is always empty.</b> RLTweaker's {@code RecurrentHandler.RCCleanup}
 * deletes the {@code entries} tag on every world load, so RC itself forgets what it placed. The
 * records are still in the save file at that moment, so {@link RecurrentComplexData} snapshots them
 * and this locator merges that snapshot with whatever RC generated during the current session.
 *
 * <h2>Variants</h2>
 * Because every record carries the exact structure id, each id is a searchable variant and filtering
 * by it is exact. The id list comes from the installed packs — {@code <gameDir>/structures/active}
 * plus the packs bundled in the mod jar — which the client can read without asking the server.
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
    private boolean loggedLive;
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
        return nearest(world, playerPos, radius, null, null);
    }

    /**
     * Typed search: keep only the records whose structure id matches. No replay is involved — the
     * save file already knows which structure was placed where.
     */
    @Override
    public StructureResult findNearest(World world, BlockPos playerPos, String structureId, int radius,
                                       String variantId) {
        if (variantId == null || variantId.isEmpty()) {
            return nearest(world, playerPos, radius, null, null);
        }
        return nearest(world, playerPos, radius, suffix(variantId), variantId);
    }

    private StructureResult nearest(World world, BlockPos playerPos, int radius, String wantedId,
                                    String variantId) {
        List<RecurrentComplexData.Record> records = records(world);
        settle(world, playerPos, wantedId, records);
        if (records.isEmpty()) {
            if (wantedId != null) {
                LOG.info("[RC] dimension {} has no structure records to search", world.provider.getDimension());
            }
            return null;
        }
        double maxDistanceSq = (double) radius * (double) radius;
        double bestDistanceSq = Double.MAX_VALUE;
        RecurrentComplexData.Record best = null;
        int considered = 0;
        for (RecurrentComplexData.Record record : records) {
            if (wantedId != null && !matches(record.structureId, wantedId)) {
                continue;
            }
            considered++;
            double distanceSq = RecurrentComplexData.distanceSq(record, playerPos);
            if (distanceSq > maxDistanceSq || distanceSq >= bestDistanceSq) {
                continue;
            }
            bestDistanceSq = distanceSq;
            best = record;
        }
        if (best == null) {
            if (wantedId != null) {
                LOG.info("[RC] no '{}' among {} matching records within {} blocks",
                        wantedId, considered, radius);
                StructureResult suggestion = suggest(world, playerPos, radius, wantedId);
                if (suggestion != null) {
                    return variantId == null ? suggestion : suggestion.withVariant(variantId, false);
                }
            }
            return null;
        }
        if (!best.structureId.equals(lastReported)) {
            lastReported = best.structureId;
            LOG.info("[RC] nearest structure is '{}' at ({}, {}, {})",
                    best.structureId, best.center.getX(), best.center.getY(), best.center.getZ());
        }
        StructureResult result = new StructureResult(STRUCTURE_ID, best.center, sourceName(), bestDistanceSq);
        return variantId == null ? result : result.withVariant(variantId, true);
    }

    /**
     * The save-file snapshot plus RC's live map. The snapshot covers everything placed in earlier
     * sessions (RC's own record of it was wiped at load), the live map covers this session.
     */
    private List<RecurrentComplexData.Record> records(World world) {
        List<RecurrentComplexData.Record> out = new ArrayList<RecurrentComplexData.Record>(
                RecurrentComplexData.snapshot(world));
        Map<?, ?> live = liveEntries(world);
        if (live != null && !live.isEmpty()) {
            int added = 0;
            for (Object entry : live.values()) {
                RecurrentComplexData.Record record = toRecord(entry);
                if (record != null) {
                    out.add(record);
                    added++;
                }
            }
            if (added > 0 && !loggedLive) {
                loggedLive = true;
                LOG.info("[RC] {} structures generated this session", added);
            }
        }
        return out;
    }

    private static RecurrentComplexData.Record toRecord(Object entry) {
        Object rawBox = Reflect.invoke(entry, "getBoundingBox");
        if (!(rawBox instanceof StructureBoundingBox)) {
            return null;
        }
        Object id = Reflect.invoke(entry, "getStructureID");
        if (!(id instanceof String)) {
            return null;
        }
        StructureBoundingBox box = (StructureBoundingBox) rawBox;
        BlockPos center = new BlockPos(
                (box.minX + box.maxX) / 2,
                (box.minY + box.maxY) / 2,
                (box.minZ + box.maxZ) / 2);
        return new RecurrentComplexData.Record((String) id, center);
    }

    /** RC's own map for this dimension, or {@code null} when RC is absent or its field moved. */
    private Map<?, ?> liveEntries(World world) {
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
            return null;
        }
        Object data;
        try {
            data = getData.invoke(null, world);
        } catch (Throwable t) {
            return null;
        }
        if (data == null) {
            return null;
        }
        Object rawEntries = Reflect.read(data, "entryMap");
        return rawEntries instanceof Map ? (Map<?, ?>) rawEntries : null;
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

    // -------------------------------------------------------------- suggestions

    private StructureResult suggest(World world, BlockPos playerPos, int radius, String structureId) {
        BlockPos candidate = UltraRarePredictor.suggest(world, playerPos, radius, structureId,
                SearchMemory.get(world));
        if (candidate == null) {
            return null;
        }
        LOG.info("[RC] suggested search area for '{}' at ({}, {})", structureId, candidate.getX(),
                candidate.getZ());
        return new StructureResult(STRUCTURE_ID, candidate, sourceName() + " (suggested)",
                StructureResult.horizontalDistanceSq(candidate, playerPos));
    }

    /** Moves NEW candidates the player is standing on to FOUND or MISSING, using the records. */
    private void settle(World world, BlockPos playerPos, String structureId,
                        List<RecurrentComplexData.Record> records) {
        if (structureId == null) {
            return;
        }
        SearchMemory memory = SearchMemory.get(world);
        if (memory == null) {
            return;
        }
        List<BlockPos> confirmed = new ArrayList<BlockPos>();
        for (RecurrentComplexData.Record record : records) {
            if (matches(record.structureId, structureId)) {
                confirmed.add(record.center);
            }
        }
        memory.settleNear(structureId, playerPos, 48, confirmed, 64);
    }
}
