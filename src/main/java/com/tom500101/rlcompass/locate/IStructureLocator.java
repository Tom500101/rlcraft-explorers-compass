package com.tom500101.rlcompass.locate;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

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
     * Variant-aware search. The default ignores {@code variantId} and answers for the parent, which
     * is the correct fallback for every locator that has no variant data.
     */
    default StructureResult findNearest(World world, BlockPos playerPos, String structureId, int radius,
                                        String variantId) {
        return findNearest(world, playerPos, structureId, radius);
    }

    /**
     * Variants this locator can expose for {@code world}. An empty list means "no variant data";
     * the picker then shows only the parent row. Locators that do return variants should keep the
     * parent searchable as well.
     */
    default List<StructureVariant> getVariants(World world) {
        return Collections.emptyList();
    }

    /**
     * Whether this locator can answer in {@code world} right now.
     *
     * <p>Registration is static, so an adapter can be wired up long before its data source exists,
     * or after another mod has replaced that data source. {@link
     * StructureLocatorRegistry#getSelectableIds(net.minecraft.world.World)} asks this before
     * offering an id, so the picker never lists something that can only answer {@code null}.
     *
     * <p>Defaults to {@code true} so existing adapters keep their current behaviour. New code
     * should call {@link #canAnswer(World, String)}: a locator that serves several ids can answer
     * for some of them and not others (the cache mirror is the usual case — it may hold ruins but
     * no towers).
     */
    default boolean canAnswer(World world) {
        return true;
    }

    /**
     * Per-id availability. {@code StructureLocatorRegistry#getSelectableIds(World)} asks this for
     * every id a locator advertises, so an id is listed only when at least one supporting locator
     * can answer <b>for that id</b>.
     *
     * <p>Defaults to {@link #canAnswer(World)} so existing locators keep their behaviour.
     */
    default boolean canAnswer(World world, String id) {
        return canAnswer(world);
    }

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
