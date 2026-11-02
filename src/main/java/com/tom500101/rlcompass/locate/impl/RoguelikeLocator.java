package com.tom500101.rlcompass.locate.impl;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.tom500101.rlcompass.locate.IStructureLocator;
import com.tom500101.rlcompass.locate.StructureResult;
import com.tom500101.rlcompass.locate.StructureVariant;
import com.tom500101.rlcompass.locate.StructureVariant.VariantSource;
import com.tom500101.rlcompass.util.Reflect;

/**
 * Locates Roguelike Dungeons.
 *
 * <p>The stage-1 probe found {@code DungeonGenerator1_12} has no fields and the mod keeps no
 * {@code WorldSavedData}, which made this look like a feature-block-scanning job. It is not: the mod
 * exposes a pure placement predicate.
 *
 * <pre>
 * DungeonGenerator1_12.generate(...)
 *   └─ new Dungeon(worldEditor).spawnInChunk(random, chunkX, chunkZ)
 *
 * Dungeon.canSpawnInChunk(int chunkX, int chunkZ, WorldEditor)   ← public static, pure
 *   ├─ isSpawnFrequencyHit(chunkX, chunkZ)
 *   └─ isSpawnChanceHit(chunkX, chunkZ)
 * </pre>
 *
 * <p>{@code canSpawnInChunk} hashes the chunk coordinates and touches no world blocks, so walking
 * chunk rings outward is cheap — unlike mineshafts, where every candidate called {@code getBiome}.
 * The reported position is the chunk centre; the mod picks the exact spot inside the chunk with its
 * own random, which we deliberately do not reproduce.
 *
 * <h2>Theme lookup</h2>
 * The theme is not stored anywhere, but it is <em>reproducible</em>: every generation call builds a
 * fresh {@code WorldEditor1_12} whose random is seeded from the world seed alone, and the first
 * consumer of that random is {@code SettingsResolver.chooseDungeonSetting}. {@code canSpawnInChunk}
 * runs before it and never touches that random, so replaying the call with our own fresh editor
 * yields the same settings — and therefore the same theme — the generator would pick.
 */
public class RoguelikeLocator implements IStructureLocator {

    private static final Logger LOG = LogManager.getLogger("rlcompass");

    private static final String DUNGEON_CLASS = "greymerk.roguelike.dungeon.Dungeon";
    private static final String WORLD_EDITOR_CLASS = "com.github.fnar.minecraft.WorldEditor1_12";
    /** The predicate's parameter type is the interface, not the 1.12 implementation. */
    private static final String WORLD_EDITOR_INTERFACE = "greymerk.roguelike.worldgen.WorldEditor";

    /**
     * Built-in theme registry. The generator stores no theme on the chunk, but the choice can be
     * replayed through the settings resolver — see the class comment.
     */
    private static final String THEMES_CLASS = "greymerk.roguelike.theme.Themes";

    private static final String SETTINGS_RESOLVER_CLASS =
            "greymerk.roguelike.dungeon.settings.SettingsResolver";
    private static final String DUNGEON_SETTINGS_CLASS =
            "greymerk.roguelike.dungeon.settings.DungeonSettings";
    private static final String LEVEL_SETTINGS_CLASS =
            "greymerk.roguelike.dungeon.settings.LevelSettings";
    private static final String COORD_CLASS = "greymerk.roguelike.worldgen.Coord";

    /** Hard cap so a huge radius cannot stall the server. */
    private static final long CHECK_BUDGET = 200_000L;

    public static final String STRUCTURE_ID = "roguelike:dungeon";

    private boolean resolved;
    private Method canSpawnInChunk;
    private Class<?> dungeonClass;
    private Class<?> worldEditorClass;
    private boolean warnedMissing;
    private boolean loggedFailure;
    /** Enum constants never change, so the list is built once. */
    private List<StructureVariant> variants;

    private boolean themeLookupResolved;
    private boolean themeLookupBroken;
    private Object settingsResolver;
    private Method chooseDungeonSetting;
    private java.lang.reflect.Constructor<?> coordConstructor;
    private Method settingsGetLevels;
    private Method levelGetTheme;
    private Method themeGetBase;
    /** One INFO sample per session is enough to confirm the replay against a visited dungeon. */
    private boolean loggedThemeSample;
    private boolean themeDiagLogged;

    @Override
    public Collection<String> getSupportedIds() {
        return Collections.singletonList(STRUCTURE_ID);
    }

    @Override
    public String sourceName() {
        return "Roguelike";
    }

    @Override
    public StructureResult findNearest(World world, BlockPos playerPos, String structureId, int radius) {
        return findNearest(world, playerPos, radius, null);
    }

    /**
     * {@code requiredTheme} is an enum constant name (e.g. {@code STONE}) when the caller wants a
     * specific theme, or {@code null} for "any dungeon".
     */
    private StructureResult findNearest(World world, BlockPos playerPos, int radius, String requiredTheme) {
        resolve();
        if (canSpawnInChunk == null || worldEditorClass == null) {
            if (!warnedMissing) {
                warnedMissing = true;
                LOG.info("[ROGUELIKE] Roguelike Dungeons is not installed; {} stays unavailable", STRUCTURE_ID);
            }
            return null;
        }
        Object editor = Reflect.newInstance(worldEditorClass, new Class<?>[]{World.class}, world);
        if (editor == null) {
            return null;
        }

        int centerChunkX = playerPos.getX() >> 4;
        int centerChunkZ = playerPos.getZ() >> 4;
        int maxRing = Math.max(1, radius >> 4);
        double maxDistanceSq = (double) radius * (double) radius;
        long budget = CHECK_BUDGET;

        for (int ring = 0; ring <= maxRing; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.abs(dx) != ring && Math.abs(dz) != ring) {
                        continue;
                    }
                    if (--budget < 0) {
                        LOG.warn("[ROGUELIKE] gave up after {} chunk checks (radius {})", CHECK_BUDGET, radius);
                        return null;
                    }
                    int chunkX = centerChunkX + dx;
                    int chunkZ = centerChunkZ + dz;
                    Object hit;
                    try {
                        hit = canSpawnInChunk.invoke(null, chunkX, chunkZ, editor);
                    } catch (Throwable t) {
                        if (!loggedFailure) {
                            loggedFailure = true;
                            Throwable cause = t.getCause() == null ? t : t.getCause();
                            LOG.warn("[ROGUELIKE] canSpawnInChunk threw {}: {}",
                                    cause.getClass().getName(), cause.getMessage());
                        }
                        return null;
                    }
                    if (!(hit instanceof Boolean) || !((Boolean) hit)) {
                        continue;
                    }
                    BlockPos candidate = new BlockPos((chunkX << 4) + 8, 64, (chunkZ << 4) + 8);
                    if (StructureResult.horizontalDistanceSq(candidate, playerPos) > maxDistanceSq) {
                        continue;
                    }
                    String theme = themeNameAt(world, chunkX, chunkZ);
                    if (requiredTheme == null) {
                        if (!loggedThemeSample && theme != null) {
                            loggedThemeSample = true;
                            LOG.info("[ROGUELIKE] nearest dungeon at chunk ({}, {}) uses theme {}",
                                    chunkX, chunkZ, theme);
                        }
                    } else if (theme == null || !requiredTheme.equalsIgnoreCase(theme)) {
                        continue;
                    }
                    return StructureResult.of(STRUCTURE_ID, candidate, sourceName(), playerPos);
                }
            }
        }
        return null;
    }

    /**
     * The themes the generator may pick from, read straight off the mod's own enum — no config file
     * parsing, and always in sync with the installed version. If the enum cannot be initialised we
     * fall back to listing {@code theme/builtin/Theme*.class}, which needs no class initialisation.
     */
    @Override
    public List<StructureVariant> getVariants(World world) {
        if (variants != null) {
            return variants;
        }
        Set<String> names = new LinkedHashSet<String>();
        String origin = "enum";
        try {
            Class<?> themes = Class.forName(THEMES_CLASS);
            Object[] constants = themes.getEnumConstants();
            if (constants != null) {
                for (Object constant : constants) {
                    names.add(((Enum<?>) constant).name());
                }
            }
        } catch (Throwable t) {
            Throwable cause = t.getCause() == null ? t : t.getCause();
            LOG.warn("[ROGUELIKE] cannot read {}: {}: {}", THEMES_CLASS,
                    cause.getClass().getName(), cause.getMessage());
        }
        if (names.isEmpty()) {
            origin = "jar-scan";
            names.addAll(scanThemeClasses());
        }
        List<StructureVariant> built = new ArrayList<StructureVariant>();
        for (String name : names) {
            if (name == null || name.trim().isEmpty()) {
                continue;
            }
            built.add(new StructureVariant(
                    STRUCTURE_ID + "/" + name.toLowerCase(Locale.ROOT),
                    STRUCTURE_ID, displayNameFor(name), VariantSource.RUNTIME_API));
        }
        LOG.info("[ROGUELIKE] {} theme variants ({})", built.size(), origin);
        variants = built.isEmpty()
                ? Collections.<StructureVariant>emptyList()
                : Collections.unmodifiableList(built);
        return variants;
    }

    /** Lists {@code theme/builtin/Theme*.class} without touching the enum. */
    private static List<String> scanThemeClasses() {
        List<String> names = new ArrayList<String>();
        Class<?> anchor = Reflect.findClass(DUNGEON_CLASS);
        if (anchor == null) {
            return names;
        }
        String prefix = "greymerk/roguelike/theme/builtin/";
        try {
            java.security.CodeSource source = anchor.getProtectionDomain().getCodeSource();
            if (source == null) {
                return names;
            }
            java.io.File location = new java.io.File(source.getLocation().toURI());
            if (location.isDirectory()) {
                java.io.File[] files = new java.io.File(location, prefix).listFiles();
                if (files != null) {
                    for (java.io.File file : files) {
                        addThemeName(names, file.getName());
                    }
                }
            } else {
                java.util.jar.JarFile jar = new java.util.jar.JarFile(location);
                try {
                    java.util.Enumeration<java.util.jar.JarEntry> entries = jar.entries();
                    while (entries.hasMoreElements()) {
                        String entry = entries.nextElement().getName();
                        if (entry.startsWith(prefix) && entry.endsWith(".class")
                                && entry.indexOf('$') < 0) {
                            addThemeName(names, entry.substring(prefix.length()));
                        }
                    }
                } finally {
                    jar.close();
                }
            }
        } catch (Throwable ignored) {
            // A missing or unreadable code source just means "no fallback list".
        }
        Collections.sort(names);
        return names;
    }

    private static void addThemeName(List<String> names, String fileName) {
        if (!fileName.endsWith(".class")) {
            return;
        }
        String simple = fileName.substring(0, fileName.length() - ".class".length());
        if (simple.startsWith("Theme") && simple.length() > "Theme".length()) {
            names.add(simple.substring("Theme".length()));
        }
    }

    /**
     * {@code NETHER_FORTRESS} becomes {@code Nether Fortress Dungeon}. No marker: the theme is
     * replayed from the world seed, so a row here really is a filter the search can honour.
     */
    private static String displayNameFor(String theme) {
        StringBuilder out = new StringBuilder();
        for (String part : theme.split("_")) {
            if (part.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(part.charAt(0)))
                    .append(part.substring(1).toLowerCase(Locale.ROOT));
        }
        return out.length() == 0 ? "Dungeon" : out.append(" Dungeon").toString();
    }

    /**
     * A themed search is answered by replaying the settings choice for each candidate chunk and
     * keeping only the chunks whose theme matches — the position is exact, not a guess.
     */
    @Override
    public StructureResult findNearest(World world, BlockPos playerPos, String structureId, int radius,
                                       String variantId) {
        if (variantId == null || variantId.isEmpty()) {
            return findNearest(world, playerPos, radius, null);
        }
        String theme = themeFromVariantId(variantId);
        if (theme == null) {
            StructureResult any = findNearest(world, playerPos, radius, null);
            return any == null ? null : any.withVariant(variantId, false);
        }
        StructureResult result = findNearest(world, playerPos, radius, theme);
        return result == null ? null : result.withVariant(variantId, true);
    }

    /** {@code roguelike:dungeon/nether_fortress} becomes {@code NETHER_FORTRESS}. */
    private static String themeFromVariantId(String variantId) {
        int slash = variantId.lastIndexOf('/');
        if (slash < 0 || slash == variantId.length() - 1) {
            return null;
        }
        return variantId.substring(slash + 1).toUpperCase(Locale.ROOT);
    }

    private void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        dungeonClass = Reflect.findClass(DUNGEON_CLASS);
        worldEditorClass = Reflect.findClass(WORLD_EDITOR_CLASS);
        Class<?> editorInterface = Reflect.findClass(WORLD_EDITOR_INTERFACE);
        if (dungeonClass == null || worldEditorClass == null || editorInterface == null) {
            return;
        }
        canSpawnInChunk = Reflect.findMethod(dungeonClass, "canSpawnInChunk",
                int.class, int.class, editorInterface);
        if (canSpawnInChunk == null) {
            LOG.warn("[ROGUELIKE] could not resolve Dungeon.canSpawnInChunk(int, int, WorldEditor)");
            return;
        }
        resolveThemeLookup(editorInterface);
    }

    private void resolveThemeLookup(Class<?> editorInterface) {
        if (themeLookupResolved) {
            return;
        }
        themeLookupResolved = true;
        Class<?> resolverClass = Reflect.findClass(SETTINGS_RESOLVER_CLASS);
        Class<?> settingsClass = Reflect.findClass(DUNGEON_SETTINGS_CLASS);
        Class<?> levelClass = Reflect.findClass(LEVEL_SETTINGS_CLASS);
        Class<?> coordClass = Reflect.findClass(COORD_CLASS);
        Class<?> themesClass = Reflect.findClass(THEMES_CLASS);
        if (resolverClass == null || settingsClass == null || levelClass == null
                || coordClass == null || themesClass == null) {
            themeLookupBroken = true;
            return;
        }
        settingsResolver = Reflect.readStatic(dungeonClass, "settingsResolver");
        chooseDungeonSetting = Reflect.findMethod(resolverClass, "chooseDungeonSetting",
                editorInterface, coordClass);
        settingsGetLevels = Reflect.findMethod(settingsClass, "getLevelSettings");
        levelGetTheme = Reflect.findMethod(levelClass, "getTheme");
        themeGetBase = Reflect.findMethod(themesClass, "getThemeBase");
        try {
            coordConstructor = coordClass.getConstructor(int.class, int.class, int.class);
        } catch (Throwable t) {
            themeLookupBroken = true;
        }
        if (settingsResolver == null || chooseDungeonSetting == null || settingsGetLevels == null
                || levelGetTheme == null || themeGetBase == null || coordConstructor == null) {
            themeLookupBroken = true;
            LOG.info("[ROGUELIKE] theme replay unavailable; themed searches fall back to the parent");
        }
    }

    /**
     * Replays the settings choice for one chunk with a fresh editor. That mirrors generation exactly:
     * the generator also builds a new editor per chunk, and nothing consumes its random before
     * {@code chooseDungeonSetting}.
     */
    private String themeNameAt(World world, int chunkX, int chunkZ) {
        if (themeLookupBroken || !themeLookupResolved || worldEditorClass == null) {
            return null;
        }
        Object resolver = settingsResolver != null
                ? settingsResolver
                : Reflect.readStatic(dungeonClass, "settingsResolver");
        if (resolver == null) {
            themeLookupBroken = true;
            themeDiag("settingsResolver is null");
            return null;
        }
        try {
            Object editor = Reflect.newInstance(worldEditorClass, new Class<?>[]{World.class}, world);
            if (editor == null) {
                themeDiag("editor creation failed");
                return null;
            }
            Object coord = coordConstructor.newInstance((chunkX << 4) + 8, 64, (chunkZ << 4) + 8);
            Object chosen = chooseDungeonSetting.invoke(resolver, editor, coord);
            if (!(chosen instanceof java.util.Optional)) {
                themeDiag("chooseDungeonSetting returned " + (chosen == null ? "null" : chosen.getClass().getName()));
                return null;
            }
            java.util.Optional<?> optional = (java.util.Optional<?>) chosen;
            if (!optional.isPresent()) {
                themeDiag("no settings for chunk " + chunkX + "," + chunkZ);
                return null;
            }
            Object settings = optional.get();
            Object levels = settingsGetLevels.invoke(settings);
            if (!(levels instanceof java.util.Map) || ((java.util.Map<?, ?>) levels).isEmpty()) {
                themeDiag("settings without levels: " + settings);
                return null;
            }
            Object firstLevel = ((java.util.Map<?, ?>) levels).values().iterator().next();
            Object theme = levelGetTheme.invoke(firstLevel);
            String name = themeNameOf(theme);
            if (name == null) {
                themeDiag("unmatched theme " + theme + " (settings " + settings + ")");
            }
            return name;
        } catch (Throwable t) {
            themeLookupBroken = true;
            Throwable cause = t.getCause() == null ? t : t.getCause();
            themeDiag("threw " + cause.getClass().getName() + ": " + cause.getMessage());
            return null;
        }
    }

    private void themeDiag(String message) {
        if (themeDiagLogged) {
            return;
        }
        themeDiagLogged = true;
        LOG.info("[ROGUELIKE] theme replay: {}", message);
    }

    /**
     * Maps a live {@code Theme} back to its enum constant name. Settings may hold a copy built by
     * {@code Theme.inherit}, so identity is tried first and the rendered form second.
     */
    private String themeNameOf(Object theme) {
        if (theme == null) {
            return null;
        }
        Class<?> themesClass = Reflect.findClass(THEMES_CLASS);
        if (themesClass == null) {
            return null;
        }
        Object[] constants = themesClass.getEnumConstants();
        if (constants == null) {
            return null;
        }
        for (Object constant : constants) {
            if (Reflect.invoke(constant, themeGetBase) == theme) {
                return ((Enum<?>) constant).name();
            }
        }
        String target = String.valueOf(theme);
        for (Object constant : constants) {
            Object base = Reflect.invoke(constant, themeGetBase);
            if (base != null && target.equals(String.valueOf(base))) {
                return ((Enum<?>) constant).name();
            }
        }
        return null;
    }
}
