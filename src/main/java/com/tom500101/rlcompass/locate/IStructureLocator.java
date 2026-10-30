package com.tom500101.rlcompass.locate;

import java.util.Collection;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * One way of locating one family of structures.
 *
 * <p>Implementations are registered on {@link StructureLocatorRegistry} and are consulted whenever
 * the player searches for a structure id they claim through {@link #getSupportedIds()}.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #findNearest} runs on the <b>server thread</b>. It must not block for long: the
 *       stage-1 probe measured vanilla generators at 0–200 ms, and anything near a second is a
 *       freeze the player feels. Expensive work belongs in the cache adapters.</li>
 *   <li>The returned position must lie within {@code radius} of {@code playerPos}. The registry
 *       re-checks this because the probe proved generators ignore radius — {@code WoodlandMansion}
 *       happily answered with a target 20641 blocks out for a 10000 radius query.</li>
 *   <li>Return {@code null} when nothing was found; do not throw for the ordinary "no structure"
 *       case. Throwables are caught and logged by the registry, but a thrown exception counts as a
 *       failed locator, not as "nothing there".</li>
 * </ul>
 */
public interface IStructureLocator {

    /**
     * Structure ids this locator can answer for, lowercase, optionally namespaced
     * (e.g. {@code "minecraft:village"}).
     */
    Collection<String> getSupportedIds();

    /**
     * Returns the nearest structure for {@code structureId} within {@code radius} blocks of
     * {@code playerPos}, or {@code null} when there is none.
     */
    StructureResult findNearest(World world, BlockPos playerPos, String structureId, int radius);

    /**
     * Lower runs first. Used only to order evaluation, not to short-circuit: every supporting
     * locator is asked so that the true nearest result wins.
     */
    default int priority() {
        return 100;
    }

    /** Short identifier used for logging and for {@link StructureResult#getSource()}. */
    default String sourceName() {
        return getClass().getSimpleName();
    }
}
