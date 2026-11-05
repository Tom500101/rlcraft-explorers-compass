package com.tom500101.rlcompass.util;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Which dimensions a structure id can generate in.
 *
 * <p>The compass has no runtime API for "where does this structure spawn": vanilla's structure
 * registry is per-world and modded generators rarely expose their dimension filter. So this table is
 * maintained by hand from each source's actual generation rules, and it is deliberately
 * <b>conservative</b>: an id that is not listed returns an empty array, which means "unknown" and
 * never blocks a search or prints a dimension label. Only facts that were read out of the mod's own
 * code or assets are listed here.
 *
 * <p>Sources for the entries below:
 * <ul>
 *   <li>Vanilla structures: overworld only (strongholds, villages, mineshafts, scattered features,
 *       monuments and mansions are all registered on the overworld's generator).</li>
 *   <li>Roguelike / Doomlike dungeons and Ruins: overworld generators.</li>
 *   <li>Battle Towers: the mod generates in the overworld plus a netherrack tower for the nether,
 *       and the scanner reads both dimensions' region folders.</li>
 *   <li>Ice and Fire roosts and caves: the {@code StructureGenerator} entry point runs on the
 *       overworld only.</li>
 *   <li>Lycanites dungeons: read from the shipped schematics' {@code conditions} block —
 *       {@code demonictemple} and {@code ashenmausoleum} are nether-only, {@code desertcrypts},
 *       {@code lushtomb} and {@code streamshrine} are overworld, {@code shadowlabyrinth} adds an end
 *       variant ({@code shadowlabyrinth_random}), and {@code aberrantstation} covers the overworld
 *       plus an end/nether variant ({@code aberrantstation_random}).</li>
 *   <li>Recurrent Complex is intentionally absent: its structure packs contain nether and end
 *       structures under the same id, so the dimension is unknown.</li>
 * </ul>
 */
public final class StructureDimensions {

    public static final int OVERWORLD = 0;
    public static final int NETHER = -1;
    public static final int END = 1;

    /** Display order for labels and tooltips. */
    private static final int[] ORDER = {OVERWORLD, NETHER, END};

    private static final int[] NONE = new int[0];

    /** Id → dimensions. */
    private static final Map<String, int[]> BY_ID = new LinkedHashMap<String, int[]>();
    /** Variant id → dimensions; overrides the parent entry when present. */
    private static final Map<String, int[]> BY_VARIANT = new LinkedHashMap<String, int[]>();

    static {
        // Vanilla generators live on the overworld.
        register("minecraft:stronghold", OVERWORLD);
        register("minecraft:village", OVERWORLD);
        register("minecraft:mineshaft", OVERWORLD);
        register("minecraft:temple", OVERWORLD);
        register("minecraft:monument", OVERWORLD);
        register("minecraft:mansion", OVERWORLD);

        // Mod structures whose generator is bound to one dimension.
        register("rlcompass:ruin", OVERWORLD);
        register("rlcompass:dragonroost", OVERWORLD);
        register("rlcompass:dragoncave", OVERWORLD);
        register("roguelike:dungeon", OVERWORLD);
        register("doomlike:dungeon", OVERWORLD);
        // RC's ultrarare Shivaxi is an overworld structure restricted to the $SNOWY biome tag.
        register("rlcompass:shivaxi", OVERWORLD);

        // Battle Towers ships overworld towers plus a netherrack tower for the nether.
        register("rlcompass:battletower", OVERWORLD, NETHER);

        // Lycanites: union over its schematics.
        register("rlcompass:lycanitesdungeon", OVERWORLD, NETHER, END);

        // Lycanites variants, taken from each schematic's "conditions" block.
        registerVariant("rlcompass:lycanitesdungeon/aberrantstation", OVERWORLD, NETHER, END);
        registerVariant("rlcompass:lycanitesdungeon/demonictemple", NETHER);
        registerVariant("rlcompass:lycanitesdungeon/ashenmausoleum", NETHER);
        registerVariant("rlcompass:lycanitesdungeon/desertcrypts", OVERWORLD);
        registerVariant("rlcompass:lycanitesdungeon/lushtomb", OVERWORLD);
        registerVariant("rlcompass:lycanitesdungeon/streamshrine", OVERWORLD);
        registerVariant("rlcompass:lycanitesdungeon/shadowlabyrinth", OVERWORLD, END);
    }

    private StructureDimensions() {
    }

    private static void register(String id, int... dimensions) {
        BY_ID.put(StructureNameUtil.canonical(id), dimensions);
    }

    private static void registerVariant(String variantId, int... dimensions) {
        BY_VARIANT.put(StructureNameUtil.canonical(variantId), dimensions);
    }

    /**
     * Dimensions the structure can generate in, ordered overworld → nether → end, or an empty array
     * when the id is not in the table.
     */
    public static int[] of(String structureId) {
        return ordered(BY_ID.get(StructureNameUtil.canonical(structureId)));
    }

    /**
     * Dimensions for a concrete pick: the variant's own entry wins, otherwise the parent's.
     * Returns an empty array when neither is known.
     */
    public static int[] of(String structureId, String variantId) {
        if (variantId != null && !variantId.isEmpty()) {
            int[] variant = BY_VARIANT.get(StructureNameUtil.canonical(variantId));
            if (variant != null) {
                return ordered(variant);
            }
        }
        return of(structureId);
    }

    /**
     * Whether a search for this pick can possibly answer in {@code dimension}.
     *
     * <p>Unknown ids answer {@code true}: a missing table entry must never make the compass refuse
     * to search. Only a positive statement ("this structure is listed for other dimensions only")
     * blocks the search.
     */
    public static boolean allows(String structureId, String variantId, int dimension) {
        int[] dimensions = of(structureId, variantId);
        if (dimensions.length == 0) {
            return true;
        }
        for (int candidate : dimensions) {
            if (candidate == dimension) {
                return true;
            }
        }
        return false;
    }

    /** True when a dimension label can be shown for this pick. */
    public static boolean isKnown(String structureId, String variantId) {
        return of(structureId, variantId).length > 0;
    }

    /** De-duplicates and reorders to {@link #ORDER}, appending any unexpected dimension ids. */
    private static int[] ordered(int[] dimensions) {
        if (dimensions == null || dimensions.length == 0) {
            return NONE;
        }
        int[] out = new int[dimensions.length];
        int size = 0;
        for (int wanted : ORDER) {
            for (int value : dimensions) {
                if (value == wanted) {
                    out[size++] = value;
                    break;
                }
            }
        }
        for (int value : dimensions) {
            boolean seen = false;
            for (int i = 0; i < size; i++) {
                if (out[i] == value) {
                    seen = true;
                    break;
                }
            }
            if (!seen) {
                out[size++] = value;
            }
        }
        return size == out.length ? out : Arrays.copyOf(out, size);
    }

    /** Compact debug form for logs: {@code [0, -1]}. */
    public static String toString(int[] dimensions) {
        if (dimensions == null || dimensions.length == 0) {
            return "[]";
        }
        StringBuilder text = new StringBuilder("[");
        for (int i = 0; i < dimensions.length; i++) {
            if (i > 0) {
                text.append(", ");
            }
            text.append(dimensions[i]);
        }
        return text.append(']').toString();
    }

    /** Lowercase name for a dimension id, for log lines (not localised). */
    public static String nameOf(int dimension) {
        switch (dimension) {
            case OVERWORLD:
                return "overworld";
            case NETHER:
                return "nether";
            case END:
                return "end";
            default:
                return "dim" + dimension;
        }
    }

    /** Translation key for a dimension id, or {@code null} when it has no localised name. */
    public static String translationKey(int dimension) {
        switch (dimension) {
            case OVERWORLD:
                return "gui.rlcompass.dim.overworld";
            case NETHER:
                return "gui.rlcompass.dim.nether";
            case END:
                return "gui.rlcompass.dim.end";
            default:
                return null;
        }
    }

    /** English fallback for a dimension without a translation key. */
    public static String fallbackName(int dimension) {
        switch (dimension) {
            case OVERWORLD:
                return "Overworld";
            case NETHER:
                return "Nether";
            case END:
                return "The End";
            default:
                return "DIM " + dimension;
        }
    }
}
