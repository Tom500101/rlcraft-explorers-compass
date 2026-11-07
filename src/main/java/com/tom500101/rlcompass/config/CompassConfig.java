package com.tom500101.rlcompass.config;

import net.minecraftforge.common.config.Config;

import com.tom500101.rlcompass.RLCompassMod;

/**
 * Forge configuration for the compass.
 *
 * <p>Loaded automatically by Forge's {@code ConfigManager} at mod construction time because the
 * class carries {@link Config}; the field initialisers double as the defaults if the file cannot be
 * read.
 *
 * <p>{@code slowLocatorWarnMs} is not part of the original plan. It was added after the stage-1
 * probe measured {@code MapGenBetterMineshaft#getNearestStructurePos} at 9036 ms while every other
 * generator stayed under 200 ms — the registry needs a way to surface that at runtime instead of
 * silently freezing the server.
 */
@Config(modid = RLCompassMod.MODID, name = "rlcompass")
public final class CompassConfig {

    @Config.Comment({
            "Maximum search radius in blocks.",
            "Defaults to 10000. The upper bound is 100000: a themed Roguelike search replays every",
            "51-chunk grid candidate inside the radius, so its cost grows with the square of the",
            "radius, so its cost grows with the square of the radius while coverage grows linearly.",
            "Raise it for the ultra-rare structures: Shivaxi is roughly one per 1.3 million chunks."
    })
    @Config.RangeInt(min = 100, max = 100000)
    public static int searchRadius = 10000;

    @Config.Comment({
            "Structure ids that take no part in searching or in the selection GUI.",
            "Matches both the bare id (\"village\") and the namespaced one (\"minecraft:village\")."
    })
    public static String[] structureBlacklist = new String[0];

    @Config.Comment({
            "Log a warning when a single locator takes longer than this many milliseconds.",
            "Set to 0 to disable the warning."
    })
    @Config.RangeInt(min = 0, max = 120000)
    public static int slowLocatorWarnMs = 500;

    @Config.Comment("Show the exact target coordinates on the HUD. When false only distance and direction are shown.")
    public static boolean showExactCoordinates = true;

    @Config.Comment({
            "Debug only: log the chunk random state RC is about to use (48 lines, overworld only).",
            "Used to re-verify the ultra-rare replay after a Forge / RLTweaker update.",
            "Leave false in normal play; the same switch also exists as -Drlcompass.rcprobe=true."
    })
    public static boolean debugRandomProbe = false;

    @Config.Comment({
            "Experimental: raise the deep-scan tick budget from 4-8 ms to 40 ms.",
            "Applies to every Recurrent Complex target (ultra-rare, rare, adventure, villages,",
            "the End structures). Dragon roosts and battle towers are synchronous region scans",
            "and are not affected. Scans finish roughly 5-40x sooner, at the cost of a visibly",
            "lower TPS while one runs: the server spends ~80% of every tick inside the scan.",
            "Toggle in game with /rlcompass turbo [on|off]; the value is saved here, so it",
            "survives a jar update."
    })
    public static boolean turboScan = false;

    private CompassConfig() {
    }

    /** True when {@code structureId} is listed in {@link #structureBlacklist}. */
    public static boolean isBlacklisted(String structureId) {
        if (structureId == null || structureBlacklist == null) {
            return false;
        }
        String bare = structureId.indexOf(':') >= 0
                ? structureId.substring(structureId.indexOf(':') + 1)
                : structureId;
        for (String entry : structureBlacklist) {
            if (entry == null) {
                continue;
            }
            String candidate = entry.trim();
            if (candidate.isEmpty()) {
                continue;
            }
            String candidateBare = candidate.indexOf(':') >= 0
                    ? candidate.substring(candidate.indexOf(':') + 1)
                    : candidate;
            if (candidate.equalsIgnoreCase(structureId) || candidateBare.equalsIgnoreCase(bare)) {
                return true;
            }
        }
        return false;
    }
}
