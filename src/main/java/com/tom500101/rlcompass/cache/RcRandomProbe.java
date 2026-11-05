package com.tom500101.rlcompass.cache;

import java.lang.reflect.Field;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.terraingen.PopulateChunkEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Diagnostic probe: dumps the real internal state of the chunk random RC is about to use.
 *
 * <p>{@code ChunkGeneratorOverworld.populate} seeds its random and immediately fires
 * {@code PopulateChunkEvent.Pre}. Whether the seed is really {@code x*k + z*l ^ worldSeed} decides if
 * the ultra-rare replay can predict unexplored chunks at all, and no amount of offline reasoning
 * settles it: another mod may have consumed the random first. This probe runs at
 * {@link EventPriority#HIGHEST} — before RLTweaker's handler and before RC's — and reads
 * {@code java.util.Random.seed} reflectively without consuming anything.
 *
 * <p>Each line prints the observed 48-bit state and the seed it implies
 * ({@code state == (seed ^ 0x5DEECE66D) & (2^48-1)}), so the caller can compare it with the formula
 * without another round trip. Only the first {@link #LIMIT} overworld chunks are logged.
 */
public final class RcRandomProbe {

    private static final Logger LOG = LogManager.getLogger("rlcompass");

    /** Java's multiplier; the state is {@code (seed ^ MULTIPLIER) & MASK}. */
    private static final long MULTIPLIER = 0x5DEECE66DL;
    private static final long MASK = (1L << 48) - 1;

    /**
     * Diagnostic switch. The probe already served its purpose — it proved the populate random is
     * {@code x*k + z*l ^ worldSeed} — so it stays silent unless started with
     * {@code -Drlcompass.probe=true}. Kept in the tree because it is the fastest way to re-verify
     * the formula after a Forge or RLTweaker update.
     */
    private static final boolean ENABLED = Boolean.getBoolean("rlcompass.probe");

    private static final int LIMIT = 48;

    private static int logged;

    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public void onPopulate(PopulateChunkEvent.Pre event) {
        if (!ENABLED || logged >= LIMIT) {
            return;
        }
        World world = event.getWorld();
        if (!(world instanceof WorldServer) || world.provider == null
                || world.provider.getDimension() != 0) {
            return;
        }
        Random random = event.getRand();
        long state = readState(random);
        if (state < 0) {
            return;
        }
        logged++;
        long impliedSeed = (state ^ MULTIPLIER) & MASK;
        Object generator = event.getGenerator();
        String generatorName = generator == null ? "null" : generator.getClass().getName();
        long generatorSeed = generator == null ? Long.MIN_VALUE : readLong(generator, "seed");
        LOG.info("[RCPROBE] chunk({},{}) worldSeed={} generator={} generatorSeed={} "
                        + "state={} impliedSeed={}",
                event.getChunkX(), event.getChunkZ(), world.getSeed(),
                generatorName, generatorSeed, state, impliedSeed);
    }

    /** Reads a primitive long field, walking up the hierarchy; {@code Long.MIN_VALUE} if absent. */
    private static long readLong(Object target, String name) {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.getLong(target);
            } catch (NoSuchFieldException missing) {
                type = type.getSuperclass();
            } catch (Throwable t) {
                return Long.MIN_VALUE;
            }
        }
        return Long.MIN_VALUE;
    }

    /** Reads {@code java.util.Random.seed}, which is an {@code AtomicLong} on Java 8. */
    private static long readState(Random random) {
        try {
            Field field = Random.class.getDeclaredField("seed");
            field.setAccessible(true);
            Object value = field.get(random);
            if (value instanceof AtomicLong) {
                return ((AtomicLong) value).get() & MASK;
            }
        } catch (Throwable t) {
            LOG.warn("[RCPROBE] could not read Random.seed: {}", t.toString());
        }
        return -1L;
    }

    /** Lets a command re-arm the probe after the first batch. */
    public static void reset() {
        logged = 0;
    }
}
