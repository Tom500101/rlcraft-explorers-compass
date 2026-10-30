package com.tom500101.rlcompass.locate.impl;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.tom500101.rlcompass.locate.IStructureLocator;
import com.tom500101.rlcompass.locate.StructureResult;
import com.tom500101.rlcompass.util.Reflect;

/**
 * Locates Ruins.
 *
 * <p>Ruins keeps its placements as bounding boxes, not points, so the reported position is the centre
 * of the box — a corner would be up to ~20 blocks off for the larger templates.
 *
 * <p>Two sources are tried in order:
 *
 * <ol>
 *   <li><b>The live set in memory</b> —
 *       {@code RuinsMod.generatorMap → WorldHandle.generator → RuinGenerator.registeredRuins}. This
 *       is exact, but unusable as the primary source: the map is keyed by something that is not the
 *       dimension id (the probe measured size 3 with no entry for dimension 0), and
 *       {@code registeredRuins} is filled by a background loader thread anyway.</li>
 *   <li><b>{@code RuinsPositionsFile.txt}</b> — the plain-text file Ruins writes per dimension:
 *       {@code xMin yMin zMin xMax yMax zMax templateName}, {@code #} lines ignored. This is what
 *       actually works, so it is the real path.</li>
 * </ol>
 *
 * <p>The file sits in the world directory for the overworld and in the provider's save folder
 * ({@code DIM-1}, {@code DIM1}, {@code LOST}, …) for other dimensions.
 */
public class RuinsLocator implements IStructureLocator {

    private static final Logger LOG = LogManager.getLogger("rlcompass");

    private static final String RUINS_MOD_ID = "ruins";
    private static final String POSITIONS_FILE = "RuinsPositionsFile.txt";

    public static final String STRUCTURE_ID = "rlcompass:ruin";

    private boolean warnedMissing;

    @Override
    public Collection<String> getSupportedIds() {
        return Collections.singletonList(STRUCTURE_ID);
    }

    @Override
    public String sourceName() {
        return "Ruins";
    }

    @Override
    public StructureResult findNearest(World world, BlockPos playerPos, String structureId, int radius) {
        return StructureResult.nearestOf(STRUCTURE_ID, collectPositions(world), playerPos, radius, sourceName());
    }

    /** Every ruin centre the mod knows about in {@code world}'s dimension. Used by the cache too. */
    public List<BlockPos> collectPositions(World world) {
        if (Reflect.findClass("atomicstryker.ruins.common.RuinData") == null) {
            if (!warnedMissing) {
                warnedMissing = true;
                LOG.info("[RUINS] Ruins is not installed; {} stays unavailable", STRUCTURE_ID);
            }
            return Collections.emptyList();
        }
        List<BlockPos> centres = fromMemory(world);
        if (!centres.isEmpty()) {
            return centres;
        }
        return fromPositionsFile(world);
    }

    // ------------------------------------------------------------------ source 1: live memory

    private List<BlockPos> fromMemory(World world) {
        List<BlockPos> out = new ArrayList<BlockPos>();
        Object ruinsMod = modInstance();
        if (ruinsMod == null) {
            return out;
        }
        Object rawMap = Reflect.read(ruinsMod, "generatorMap");
        if (!(rawMap instanceof Map)) {
            return out;
        }
        Object handle = ((Map<?, ?>) rawMap).get(world.provider.getDimension());
        if (handle == null) {
            return out;
        }
        Object generator = Reflect.read(handle, "generator");
        Object rawRuins = Reflect.read(generator, "registeredRuins");
        if (!(rawRuins instanceof Set)) {
            return out;
        }
        Object[] snapshot;
        try {
            snapshot = ((Set<?>) rawRuins).toArray();
        } catch (Throwable t) {
            return out;
        }
        for (Object ruin : snapshot) {
            BlockPos centre = centreOf(ruin);
            if (centre != null) {
                out.add(centre);
            }
        }
        return out;
    }

    private static BlockPos centreOf(Object ruin) {
        if (ruin == null) {
            return null;
        }
        int xMin = Reflect.readInt(ruin, "xMin", Integer.MIN_VALUE);
        int xMax = Reflect.readInt(ruin, "xMax", Integer.MIN_VALUE);
        int zMin = Reflect.readInt(ruin, "zMin", Integer.MIN_VALUE);
        int zMax = Reflect.readInt(ruin, "zMax", Integer.MIN_VALUE);
        int yMin = Reflect.readInt(ruin, "yMin", 64);
        int yMax = Reflect.readInt(ruin, "yMax", 64);
        if (xMin == Integer.MIN_VALUE || xMax == Integer.MIN_VALUE
                || zMin == Integer.MIN_VALUE || zMax == Integer.MIN_VALUE) {
            return null;
        }
        return new BlockPos((xMin + xMax) / 2, (yMin + yMax) / 2, (zMin + zMax) / 2);
    }

    // ------------------------------------------------------------------ source 2: positions file

    private List<BlockPos> fromPositionsFile(World world) {
        List<BlockPos> out = new ArrayList<BlockPos>();
        File file = positionsFile(world);
        if (file == null || !file.isFile()) {
            return out;
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.charAt(0) == '#') {
                    continue;
                }
                // xMin yMin zMin xMax yMax zMax templateName
                String[] parts = trimmed.split("\\s+");
                if (parts.length < 6) {
                    continue;
                }
                int xMin = Integer.parseInt(parts[0]);
                int yMin = Integer.parseInt(parts[1]);
                int zMin = Integer.parseInt(parts[2]);
                int xMax = Integer.parseInt(parts[3]);
                int yMax = Integer.parseInt(parts[4]);
                int zMax = Integer.parseInt(parts[5]);
                out.add(new BlockPos((xMin + xMax) / 2, (yMin + yMax) / 2, (zMin + zMax) / 2));
            }
        } catch (Throwable t) {
            LOG.warn("[RUINS] could not read {} ({})", file, t.toString());
            return out;
        }
        return out;
    }

    private static File positionsFile(World world) {
        try {
            File base = world.getSaveHandler().getWorldDirectory();
            if (base == null) {
                return null;
            }
            String folder = world.provider.getSaveFolder();
            return folder == null || folder.isEmpty()
                    ? new File(base, POSITIONS_FILE)
                    : new File(new File(base, folder), POSITIONS_FILE);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object modInstance() {
        try {
            ModContainer container = Loader.instance().getIndexedModList().get(RUINS_MOD_ID);
            return container == null ? null : container.getMod();
        } catch (Throwable t) {
            return null;
        }
    }
}
