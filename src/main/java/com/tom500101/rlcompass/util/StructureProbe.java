package com.tom500101.rlcompass.util;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.gen.ChunkGeneratorOverworld;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraft.world.gen.IChunkGenerator;
import net.minecraft.world.gen.structure.MapGenStructure;
import net.minecraftforge.fml.common.IWorldGenerator;
import net.minecraftforge.fml.common.ObfuscationReflectionHelper;
import net.minecraftforge.fml.common.registry.GameRegistry;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Development-time structure probe (plan stage 1).
 *
 * <p>Prints, to the log, every reachable {@link MapGenStructure} instance in the running
 * environment together with the field it was found in and what {@code getStructureName()}
 * returns. Two sources are scanned:
 *
 * <ol>
 *   <li>the chunk generator of the world — {@link ChunkGeneratorOverworld} on the overworld, which
 *       is where vanilla and Forge-substituted generators (e.g. Better Mineshafts) live;</li>
 *   <li>{@code GameRegistry}'s registered {@link IWorldGenerator}s, one level of fields deep, which
 *       is where mods such as Roguelike Dungeons keep their own generators.</li>
 * </ol>
 *
 * <p>Field names are discovered at runtime rather than hard-coded on purpose: the probe runs in
 * the MCP-named dev workspace but has to describe the same classes that ship SRG-named in
 * production, and the whole point of this stage is to record which name is real.
 *
 * <p>This class is a diagnostic and is expected to be dropped (or kept behind the
 * {@code rlcompass.probe} system property) once the adapters exist.
 */
public final class StructureProbe {

    private static final Logger LOG = LogManager.getLogger("rlcompass");

    private StructureProbe() {
    }

    /**
     * Runs the full probe against {@code world} using {@code origin} as the reference point for
     * the nearest-structure checks.
     */
    public static void probe(World world, BlockPos origin) {
        if (world == null || origin == null) {
            LOG.warn("[PROBE] cannot probe: world or origin is null");
            return;
        }
        long startedAt = System.currentTimeMillis();
        LOG.info("[PROBE] ===== rlcompass structure probe =====");
        LOG.info("[PROBE] world={} dim={} origin=({}, {}, {})",
                world.getWorldInfo().getWorldName(),
                world.provider.getDimension(),
                origin.getX(), origin.getY(), origin.getZ());
        try {
            probeChunkGenerator(world, origin);
        } catch (Throwable t) {
            LOG.error("[PROBE] chunk generator probing failed", t);
        }
        try {
            probeWorldGenerators(world, origin);
        } catch (Throwable t) {
            LOG.error("[PROBE] world generator probing failed", t);
        }
        LOG.info("[PROBE] ===== probe finished in {} ms =====", System.currentTimeMillis() - startedAt);
    }

    // ------------------------------------------------------------------ section 1

    private static void probeChunkGenerator(World world, BlockPos origin) {
        LOG.info("[PROBE] -- section 1: chunk generator --");
        if (!(world.getChunkProvider() instanceof ChunkProviderServer)) {
            LOG.info("[PROBE]   chunk provider is not a ChunkProviderServer but {}",
                    world.getChunkProvider().getClass().getName());
            return;
        }
        IChunkGenerator generator = ((ChunkProviderServer) world.getChunkProvider()).chunkGenerator;
        LOG.info("[PROBE]   chunkGenerator = {}", generator.getClass().getName());
        if (generator instanceof ChunkGeneratorOverworld) {
            LOG.info("[PROBE]   vanilla overworld generator (its MapGenStructure fields may still be "
                    + "replaced by mods through InitMapGenEvent)");
        } else {
            LOG.info("[PROBE]   NOT ChunkGeneratorOverworld — a mod replaced the whole generator");
        }
        dumpDeclaredFields(generator);
        reportStructures("chunkGenerator", scanForStructures(generator), world, origin);
    }

    // ------------------------------------------------------------------ section 2

    private static void probeWorldGenerators(World world, BlockPos origin) {
        LOG.info("[PROBE] -- section 2: GameRegistry IWorldGenerator list --");
        Set<IWorldGenerator> generators = readWorldGenerators();
        if (generators == null) {
            LOG.info("[PROBE]   GameRegistry.worldGenerators is not reachable");
            return;
        }
        LOG.info("[PROBE]   registered IWorldGenerator count = {}", generators.size());
        int index = 0;
        for (IWorldGenerator generator : generators) {
            index++;
            LOG.info("[PROBE]   #{} IWorldGenerator = {}", index, generator.getClass().getName());
            dumpDeclaredFields(generator);
            reportStructures("worldGenerator#" + index, scanForStructures(generator), world, origin);
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<IWorldGenerator> readWorldGenerators() {
        Field field;
        try {
            // GameRegistry is a Forge class, so it is never obfuscated and the name is stable;
            // the helper is used first purely to stay within the "no bare field names" rule.
            field = ObfuscationReflectionHelper.findField(GameRegistry.class, "worldGenerators");
        } catch (Throwable first) {
            try {
                field = GameRegistry.class.getDeclaredField("worldGenerators");
                field.setAccessible(true);
            } catch (Throwable second) {
                LOG.warn("[PROBE]   cannot locate GameRegistry.worldGenerators ({})", second.toString());
                return null;
            }
        }
        try {
            Object value = field.get(null);
            if (value instanceof Set) {
                return (Set<IWorldGenerator>) value;
            }
            LOG.warn("[PROBE]   GameRegistry.worldGenerators is not a Set but {}", value);
        } catch (Throwable t) {
            LOG.warn("[PROBE]   cannot read GameRegistry.worldGenerators ({})", t.toString());
        }
        return null;
    }

    // ------------------------------------------------------------------ scanning

    private static void dumpDeclaredFields(Object holder) {
        for (Class<?> type = holder.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                LOG.info("[PROBE]     field {}.{} : {}",
                        type.getSimpleName(), field.getName(), field.getType().getName());
            }
        }
    }

    private static List<Found> scanForStructures(Object holder) {
        List<Found> found = new ArrayList<Found>();
        for (Class<?> type = holder.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                Object value;
                try {
                    field.setAccessible(true);
                    value = field.get(holder);
                } catch (Throwable ignored) {
                    continue;
                }
                collect(type.getSimpleName() + "." + field.getName(), field.getType(), value, found);
            }
        }
        return found;
    }

    private static void collect(String where, Class<?> declaredType, Object value, List<Found> found) {
        if (value == null) {
            return;
        }
        if (value instanceof MapGenStructure) {
            found.add(new Found(where, declaredType.getName(), (MapGenStructure) value));
            return;
        }
        if (value instanceof Collection) {
            int index = 0;
            for (Object element : (Collection<?>) value) {
                if (element instanceof MapGenStructure) {
                    found.add(new Found(where + "[" + index + "]", declaredType.getName(), (MapGenStructure) element));
                }
                index++;
            }
            return;
        }
        if (value instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (entry.getValue() instanceof MapGenStructure) {
                    found.add(new Found(where + "{" + entry.getKey() + "}",
                            declaredType.getName(), (MapGenStructure) entry.getValue()));
                }
            }
        }
    }

    private static void reportStructures(String owner, List<Found> found, World world, BlockPos origin) {
        if (found.isEmpty()) {
            LOG.info("[PROBE]   no MapGenStructure found under {}", owner);
            return;
        }
        for (Found entry : found) {
            String structureName;
            try {
                structureName = entry.structure.getStructureName();
            } catch (Throwable t) {
                structureName = "<getStructureName threw " + t.getClass().getSimpleName() + ">";
            }
            LOG.info("[PROBE]   FOUND {} | declaredType={} | actualClass={} | getStructureName()={}",
                    entry.where, entry.declaredType, entry.structure.getClass().getName(), structureName);
        }
        for (Found entry : found) {
            long startedAt = System.currentTimeMillis();
            try {
                BlockPos nearest = entry.structure.getNearestStructurePos(world, origin, false);
                long elapsed = System.currentTimeMillis() - startedAt;
                if (nearest == null) {
                    LOG.info("[PROBE]   NEAREST {} -> null ({} ms)", entry.where, elapsed);
                } else {
                    double dx = nearest.getX() - origin.getX();
                    double dz = nearest.getZ() - origin.getZ();
                    LOG.info("[PROBE]   NEAREST {} -> ({}, {}, {}) horizontalDist={} ({} ms)",
                            entry.where, nearest.getX(), nearest.getY(), nearest.getZ(),
                            Math.round(Math.sqrt(dx * dx + dz * dz)), elapsed);
                }
            } catch (Throwable t) {
                LOG.info("[PROBE]   NEAREST {} threw {}", entry.where, t.toString());
            }
        }
    }

    /** One discovered structure instance together with where it was found. */
    private static final class Found {

        private final String where;
        private final String declaredType;
        private final MapGenStructure structure;

        private Found(String where, String declaredType, MapGenStructure structure) {
            this.where = where;
            this.declaredType = declaredType;
            this.structure = structure;
        }
    }
}
