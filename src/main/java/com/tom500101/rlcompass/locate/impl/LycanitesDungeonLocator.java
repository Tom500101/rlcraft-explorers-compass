package com.tom500101.rlcompass.locate.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.tom500101.rlcompass.cache.ModdedStructureRegionScanner;
import com.tom500101.rlcompass.locate.IStructureLocator;
import com.tom500101.rlcompass.locate.StructureResult;
import com.tom500101.rlcompass.locate.StructureVariant;
import com.tom500101.rlcompass.util.Reflect;
import com.tom500101.rlcompass.util.StructureNameUtil;

/**
 * Locates Lycanites Mobs dungeons, preferring the mod's own exact records.
 *
 * <p>Lycanites keeps every dungeon it ever started in {@code ExtendedWorld}, a
 * {@code WorldSavedData} whose {@code dungeons} field is a
 * {@code Map<UUID, DungeonInstance>}. Each instance has an {@code originPos} and the
 * {@code DungeonSchematic} it was built from, so this is exact — position and theme both — and it
 * survives a restart because the data is persisted. Field and method names below come from
 * {@code javap} on {@code lycanitesmobs-1.12.2-2.0.8.9.jar}, not from memory.
 *
 * <p>A region-file scan is the fallback: dungeons built before the mod started persisting instances
 * (or a pack where the API changed) still left their theme blocks on disk. Those hits carry the
 * theme, not the schematic, and are labelled unconfirmed.
 *
 * <h2>Variants</h2>
 * The shipped jar defines nine schematics. {@code aberrantstation_random} and
 * {@code shadowlabyrinth_random} are dimension variants of the same two dungeons, so they fold into
 * their base name. Theme-to-schematic mappings come from the schematics' own {@code themes} field.
 */
public class LycanitesDungeonLocator implements IStructureLocator {

    private static final Logger LOG = LogManager.getLogger("rlcompass");

    public static final String STRUCTURE_ID = "rlcompass:lycanitesdungeon";

    private static final String EXTENDED_WORLD = "com.lycanitesmobs.ExtendedWorld";
    private static final String DUNGEON_MANAGER = "com.lycanitesmobs.core.dungeon.DungeonManager";

    private static final Map<String, String> DISPLAY_BY_SCHEMATIC = new LinkedHashMap<String, String>();
    private static final Map<String, String> SCHEMATIC_BY_THEME = new LinkedHashMap<String, String>();

    static {
        DISPLAY_BY_SCHEMATIC.put("aberrantstation", "Aberrant Station");
        DISPLAY_BY_SCHEMATIC.put("demonictemple", "Demonic Temple");
        DISPLAY_BY_SCHEMATIC.put("ashenmausoleum", "Ashen Mausoleum");
        DISPLAY_BY_SCHEMATIC.put("desertcrypts", "Desert Crypts");
        DISPLAY_BY_SCHEMATIC.put("lushtomb", "Lush Tomb");
        DISPLAY_BY_SCHEMATIC.put("shadowlabyrinth", "Shadow Labyrinth");
        DISPLAY_BY_SCHEMATIC.put("streamshrine", "Stream Shrine");

        SCHEMATIC_BY_THEME.put("aberrant", "aberrantstation");
        SCHEMATIC_BY_THEME.put("demonic", "demonictemple");
        SCHEMATIC_BY_THEME.put("ashen", "ashenmausoleum");
        SCHEMATIC_BY_THEME.put("desert", "desertcrypts");
        SCHEMATIC_BY_THEME.put("lush", "lushtomb");
        SCHEMATIC_BY_THEME.put("shadow", "shadowlabyrinth");
        SCHEMATIC_BY_THEME.put("stream", "streamshrine");
    }

    private static boolean warnedMissing;
    private static boolean warnedReflection;

    private static final class Candidate {
        final BlockPos pos;
        /** Normalised schematic name, or null when the theme is unknown. */
        final String kind;
        final boolean exact;

        Candidate(BlockPos pos, String kind, boolean exact) {
            this.pos = pos;
            this.kind = kind;
            this.exact = exact;
        }
    }

    @Override
    public Collection<String> getSupportedIds() {
        return Collections.singletonList(STRUCTURE_ID);
    }

    @Override
    public String sourceName() {
        return "LycanitesMobs";
    }

    @Override
    public boolean canAnswer(World world, String id) {
        if (!STRUCTURE_ID.equals(StructureNameUtil.canonical(id))) {
            return false;
        }
        if (world == null) {
            return true;
        }
        if (Reflect.findClass(EXTENDED_WORLD) != null) {
            return true;
        }
        for (ModdedStructureRegionScanner.Hit hit : ModdedStructureRegionScanner.peek(world)) {
            if (ModdedStructureRegionScanner.FAMILY_DUNGEON.equals(hit.family)) {
                return true;
            }
        }
        if (!warnedMissing) {
            warnedMissing = true;
            LOG.info("[LYC] Lycanites Mobs is not installed; {} stays unavailable", STRUCTURE_ID);
        }
        return false;
    }

    @Override
    public StructureResult findNearest(World world, BlockPos playerPos, String structureId, int radius) {
        return nearest(world, playerPos, radius, null, null);
    }

    @Override
    public StructureResult findNearest(World world, BlockPos playerPos, String structureId, int radius,
                                       String variantId) {
        return nearest(world, playerPos, radius, variantId, null);
    }

    /** Skips dungeons already cached in the warehouse so the next search advances. */
    @Override
    public StructureResult findNearest(World world, BlockPos playerPos, String structureId, int radius,
                                       String variantId,
                                       com.tom500101.rlcompass.cache.SearchMemory memory) {
        return nearest(world, playerPos, radius, variantId, memory);
    }

    private StructureResult nearest(World world, BlockPos playerPos, int radius, String variantId,
                                    com.tom500101.rlcompass.cache.SearchMemory memory) {
        String wanted = variantId == null ? null : variantSuffix(variantId);
        double maxDistanceSq = (double) radius * (double) radius;
        StructureResult best = null;
        boolean bestExact = false;

        for (Candidate candidate : candidates(world)) {
            if (wanted != null && !wanted.equals(candidate.kind)) {
                continue;
            }
            if (memory != null && memory.isKnown(STRUCTURE_ID, candidate.pos)) {
                continue;
            }
            double distanceSq = StructureResult.horizontalDistanceSq(candidate.pos, playerPos);
            if (distanceSq > maxDistanceSq) {
                continue;
            }
            if (best == null || distanceSq < best.getDistanceSq()) {
                String source = candidate.exact ? "LycanitesMobs" : "LycanitesScan";
                best = new StructureResult(STRUCTURE_ID, candidate.pos, source, distanceSq);
                bestExact = candidate.exact;
            }
        }
        if (best == null) {
            return null;
        }
        return variantId == null ? best : best.withVariant(variantId, bestExact);
    }

    /** Exact instances first; the block scan only when the mod's own records are empty. */
    private static List<Candidate> candidates(World world) {
        List<Candidate> out = new ArrayList<Candidate>();
        Class<?> extendedWorld = Reflect.findClass(EXTENDED_WORLD);
        if (extendedWorld != null) {
            Object data = Reflect.invokeStatic(extendedWorld, "getForWorld",
                    new Class<?>[]{World.class}, world);
            Object dungeons = Reflect.read(data, "dungeons");
            if (dungeons instanceof Map) {
                Object[] snapshot;
                try {
                    snapshot = ((Map<?, ?>) dungeons).values().toArray();
                } catch (Throwable t) {
                    snapshot = new Object[0];
                }
                for (Object instance : snapshot) {
                    Object rawPos = Reflect.read(instance, "originPos");
                    if (!(rawPos instanceof BlockPos)) {
                        continue;
                    }
                    Object schematic = Reflect.read(instance, "schematic");
                    out.add(new Candidate((BlockPos) rawPos, schematicKind(schematic), true));
                }
            } else if (!warnedReflection) {
                warnedReflection = true;
                LOG.info("[LYC] ExtendedWorld.dungeons is unavailable; falling back to a block scan");
            }
        }
        if (!out.isEmpty()) {
            return out;
        }
        for (ModdedStructureRegionScanner.Hit hit : ModdedStructureRegionScanner.scan(world)) {
            if (!ModdedStructureRegionScanner.FAMILY_DUNGEON.equals(hit.family)) {
                continue;
            }
            out.add(new Candidate(hit.pos, SCHEMATIC_BY_THEME.get(hit.kind), false));
        }
        return out;
    }

    /** Schematic name, falling back to its first theme, folded to the base (non-_random) name. */
    private static String schematicKind(Object schematic) {
        Object name = Reflect.read(schematic, "name");
        if (name instanceof String && !((String) name).isEmpty()) {
            return normalize((String) name);
        }
        Object themes = Reflect.read(schematic, "themes");
        if (themes instanceof List) {
            for (Object theme : (List<?>) themes) {
                if (theme instanceof String) {
                    String mapped = SCHEMATIC_BY_THEME.get(theme);
                    if (mapped != null) {
                        return mapped;
                    }
                }
            }
        }
        return null;
    }

    private static String normalize(String schematicName) {
        String value = schematicName.toLowerCase(Locale.ROOT);
        return value.endsWith("_random") ? value.substring(0, value.length() - 7) : value;
    }

    /**
     * Variants come from the mod's own {@code DungeonManager.schematics} when available, so a pack
     * that adds a dungeon is offered too; the built-in list is used when the manager is unavailable.
     */
    @Override
    public List<StructureVariant> getVariants(World world) {
        Set<String> names = new LinkedHashSet<String>();
        Class<?> managerClass = Reflect.findClass(DUNGEON_MANAGER);
        if (managerClass != null) {
            Object manager = Reflect.invokeStatic(managerClass, "getInstance", new Class<?>[0]);
            Object schematics = Reflect.read(manager, "schematics");
            if (schematics instanceof Map) {
                for (Object key : ((Map<?, ?>) schematics).keySet()) {
                    if (key instanceof String) {
                        names.add(normalize((String) key));
                    }
                }
            }
        }
        if (names.isEmpty()) {
            names.addAll(DISPLAY_BY_SCHEMATIC.keySet());
        }
        List<StructureVariant> out = new ArrayList<StructureVariant>();
        for (String name : names) {
            if (name.isEmpty()) {
                continue;
            }
            String display = DISPLAY_BY_SCHEMATIC.get(name);
            if (display == null) {
                display = Character.toUpperCase(name.charAt(0)) + name.substring(1);
            }
            out.add(new StructureVariant(STRUCTURE_ID + "/" + name, STRUCTURE_ID,
                    display, StructureVariant.VariantSource.RUNTIME_API));
        }
        return out;
    }

    private static String variantSuffix(String variantId) {
        int slash = variantId.lastIndexOf('/');
        return normalize(slash < 0 ? variantId : variantId.substring(slash + 1));
    }
}
