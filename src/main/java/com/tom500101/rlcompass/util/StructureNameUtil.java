package com.tom500101.rlcompass.util;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Turns the names Forge's structure generators report through {@code getStructureName()} into the
 * stable ids the compass uses everywhere else, and normalises ids for comparison.
 *
 * <p>{@code getStructureName()} returns bare capitalised words ({@code "Village"}, {@code "Temple"})
 * because that is what vanilla's {@code /locate} matches on. The compass uses lowercase namespaced
 * ids so that modded structures can live in their own namespace without colliding.
 */
public final class StructureNameUtil {

    private static final Map<String, String> ID_BY_VANILLA_NAME = new LinkedHashMap<String, String>();
    private static final Map<String, String> DISPLAY_NAME = new LinkedHashMap<String, String>();

    static {
        register("Stronghold", "minecraft:stronghold", "Stronghold");
        register("Village", "minecraft:village", "Village");
        register("Mineshaft", "minecraft:mineshaft", "Mineshaft");
        // Scattered features cover desert pyramids, jungle temples, witch huts and igloos.
        register("Temple", "minecraft:temple", "Temple / Witch Hut");
        register("Monument", "minecraft:monument", "Ocean Monument");
        register("Mansion", "minecraft:mansion", "Woodland Mansion");
        // Mod-provided ids (no vanilla getStructureName() equivalent).
        DISPLAY_NAME.put("rlcompass:battletower", "Battle Tower");
        DISPLAY_NAME.put("rlcompass:ruin", "Ruin");
        DISPLAY_NAME.put("reccomplex:structure", "Recurrent Complex Structure");
        DISPLAY_NAME.put("roguelike:dungeon", "Roguelike Dungeon");
        DISPLAY_NAME.put("doomlike:dungeon", "Doomlike Dungeon");
        DISPLAY_NAME.put("rlcompass:dragonroost", "Dragon Roost");
        DISPLAY_NAME.put("rlcompass:dragoncave", "Dragon Cave");
        DISPLAY_NAME.put("rlcompass:lycanitesdungeon", "Lycanites Dungeon");
        DISPLAY_NAME.put("rlcompass:shivaxi", "Shivaxi (Sky City)");
    }

    private StructureNameUtil() {
    }

    private static void register(String vanillaName, String id, String displayName) {
        ID_BY_VANILLA_NAME.put(vanillaName.toLowerCase(Locale.ROOT), id);
        DISPLAY_NAME.put(id, displayName);
    }

    /**
     * Normalises a structure id so that {@code village} and {@code minecraft:village} are the same
     * id, while {@code rlcompass:ruin} keeps its own namespace.
     */
    public static String canonical(String id) {
        if (id == null) {
            return "";
        }
        String trimmed = id.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty()) {
            return "";
        }
        return trimmed.indexOf(':') >= 0 ? trimmed : "minecraft:" + trimmed;
    }

    /**
     * Structures whose per-chunk chance is so low that the default radius cannot contain one.
     *
     * <p>Shivaxi's real rate is about {@code 7.5e-6} per snowy chunk, i.e. one per ~1.3 million
     * chunks; at the default 10000-block radius there is barely a 1% chance of any being inside.
     * The picker uses this to tell the player to raise the radius before they wait for nothing.
     */
    public static boolean isUltraRare(String id) {
        return "rlcompass:shivaxi".equals(canonical(id));
    }

    /** Maps a {@code getStructureName()} value to a namespaced id. */
    public static String idFor(String structureName) {        if (structureName == null || structureName.trim().isEmpty()) {
            return "minecraft:unknown";
        }
        String key = structureName.trim().toLowerCase(Locale.ROOT);
        String known = ID_BY_VANILLA_NAME.get(key);
        return known != null ? known : "minecraft:" + key;
    }

    /** Human-readable label for an id; falls back to the raw id's local part. */
    public static String displayName(String id) {
        if (id == null) {
            return "Unknown";
        }
        String known = DISPLAY_NAME.get(canonical(id));
        if (known != null) {
            return known;
        }
        int colon = id.indexOf(':');
        String bare = colon >= 0 ? id.substring(colon + 1) : id;
        if (bare.isEmpty()) {
            return id;
        }
        return Character.toUpperCase(bare.charAt(0)) + bare.substring(1);
    }
}
