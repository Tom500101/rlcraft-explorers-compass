package com.tom500101.rlcompass.util;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
        DISPLAY_NAME.put("rlcompass:authorshall", "作者大厅");
        DISPLAY_NAME.put("rlcompass:icecity", "作者故乡");
        DISPLAY_NAME.put("rlcompass:aeriveth", "沙漠黄金殿");
        DISPLAY_NAME.put("rlcompass:sanctuary", "绿宝石穹顶");
        DISPLAY_NAME.put("rlcompass:erolith", "普通黄金殿");
        DISPLAY_NAME.put("rlcompass:plainscity", "城镇（平原）");
        DISPLAY_NAME.put("rlcompass:meridor", "城镇（Meridor）");
        DISPLAY_NAME.put("rlcompass:islandcity", "海港城市");
        DISPLAY_NAME.put("rlcompass:foreststronghold", "罪恶学院");
        DISPLAY_NAME.put("rlcompass:crystalshrine", "末地解密");
        DISPLAY_NAME.put("rlcompass:corruptedisland", "末地污秽岛");
        DISPLAY_NAME.put("rlcompass:defiledhut", "末地污秽小屋");
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
     * <p>RC's three ultrarare structures share a {@code 7.5e-6}-per-chunk selection rate, i.e. one
     * per ~133k chunks of their own biome; at the default 10000-block radius there is barely a 1%
     * chance of any being inside. The picker uses this to warn before the player waits for nothing.
     */
    public static boolean isUltraRare(String id) {
        String canonical = canonical(id);
        return "rlcompass:shivaxi".equals(canonical)
                || "rlcompass:authorshall".equals(canonical)
                || "rlcompass:icecity".equals(canonical);
    }

    /**
     * Ids the compass can search with the tick-sliced RC deep scans.
     *
     * <p>Kept here as plain strings because the client has to decide between an asynchronous deep
     * scan and the synchronous locator without loading {@code RcUltraRareLocator}, which references
     * server-only classes. Must stay in sync with {@code RcUltraRareLocator.TARGETS}.
     */
    private static final Set<String> DEEP_SCAN_IDS = new HashSet<String>(Arrays.asList(
            "rlcompass:shivaxi",
            "rlcompass:authorshall",
            "rlcompass:icecity",
            "rlcompass:aeriveth",
            "rlcompass:sanctuary",
            "rlcompass:erolith",
            "rlcompass:plainscity",
            "rlcompass:meridor",
            "rlcompass:islandcity",
            "rlcompass:foreststronghold",
            "rlcompass:crystalshrine",
            "rlcompass:corruptedisland",
            "rlcompass:defiledhut"));

    /** Whether this id is served by the asynchronous RC deep scans rather than the locator chain. */
    public static boolean isDeepScanTarget(String id) {
        return DEEP_SCAN_IDS.contains(canonical(id));
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
