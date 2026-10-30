package com.tom500101.rlcompass.locate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.tom500101.rlcompass.config.CompassConfig;

/**
 * Holds every registered {@link IStructureLocator} and answers "nearest structure of type X".
 *
 * <p>All locators supporting the requested id are consulted — no short-circuiting on priority —
 * because a later adapter may know about a closer structure. Results are filtered to the requested
 * radius here as a safety net (see the contract on {@link IStructureLocator}) and the closest
 * surviving candidate wins.
 */
public final class StructureLocatorRegistry {

    private static final Logger LOG = LogManager.getLogger("rlcompass");

    private static final StructureLocatorRegistry INSTANCE = new StructureLocatorRegistry();

    private final List<IStructureLocator> locators = new CopyOnWriteArrayList<IStructureLocator>();

    private StructureLocatorRegistry() {
    }

    public static StructureLocatorRegistry get() {
        return INSTANCE;
    }

    public void register(IStructureLocator locator) {
        if (locator == null) {
            return;
        }
        locators.add(locator);
        locators.sort(Comparator.comparingInt(IStructureLocator::priority));
        LOG.info("[REGISTRY] registered {} for {}", locator.sourceName(), locator.getSupportedIds());
    }

    public void registerAll(IStructureLocator... more) {
        if (more == null) {
            return;
        }
        for (IStructureLocator locator : more) {
            register(locator);
        }
    }

    public List<IStructureLocator> getLocators() {
        return Collections.unmodifiableList(locators);
    }

    /** Every id claimed by any locator, canonicalised and deduplicated. */
    public Set<String> getSupportedIds() {
        Set<String> ids = new LinkedHashSet<String>();
        for (IStructureLocator locator : locators) {
            Collection<String> supported = locator.getSupportedIds();
            if (supported == null) {
                continue;
            }
            for (String id : supported) {
                if (id != null && !id.trim().isEmpty()) {
                    ids.add(canonical(id));
                }
            }
        }
        return ids;
    }

    /** Ids the selection GUI may show: supported minus blacklisted, sorted for stable display. */
    public List<String> getSelectableIds() {
        List<String> selectable = new ArrayList<String>();
        for (String id : getSupportedIds()) {
            if (!CompassConfig.isBlacklisted(id)) {
                selectable.add(id);
            }
        }
        Collections.sort(selectable);
        return selectable;
    }

    /**
     * Finds the nearest structure of {@code structureId} within {@code radius} blocks of
     * {@code playerPos}, or {@code null} when none was found.
     */
    public StructureResult findNearest(World world, BlockPos playerPos, String structureId, int radius) {
        if (world == null || playerPos == null || structureId == null) {
            return null;
        }
        if (CompassConfig.isBlacklisted(structureId)) {
            LOG.info("[REGISTRY] refusing to search {} — blacklisted in config", structureId);
            return null;
        }
        String wanted = canonical(structureId);
        double maxDistanceSq = (double) radius * (double) radius;
        StructureResult best = null;
        long searchStartedAt = System.currentTimeMillis();

        for (IStructureLocator locator : locators) {
            if (!supports(locator, wanted)) {
                continue;
            }
            long startedAt = System.currentTimeMillis();
            StructureResult candidate;
            try {
                candidate = locator.findNearest(world, playerPos, structureId, radius);
            } catch (Throwable t) {
                LOG.error("[REGISTRY] {} threw while locating {}", locator.sourceName(), structureId, t);
                continue;
            }
            long elapsed = System.currentTimeMillis() - startedAt;
            if (CompassConfig.slowLocatorWarnMs > 0 && elapsed >= CompassConfig.slowLocatorWarnMs) {
                LOG.warn("[REGISTRY] {} took {} ms locating {} (warn threshold {} ms) — "
                                + "move this work into a cached adapter",
                        locator.sourceName(), elapsed, structureId, CompassConfig.slowLocatorWarnMs);
            }
            if (candidate == null) {
                continue;
            }
            if (candidate.getDistanceSq() > maxDistanceSq) {
                LOG.debug("[REGISTRY] discarding {} — {} blocks away, outside the {} block radius",
                        candidate, Math.round(candidate.getDistance()), radius);
                continue;
            }
            if (best == null || candidate.getDistanceSq() < best.getDistanceSq()) {
                best = candidate;
            }
        }

        LOG.info("[REGISTRY] findNearest({}) from ({}, {}) -> {} ({} ms)",
                structureId, playerPos.getX(), playerPos.getZ(), best,
                System.currentTimeMillis() - searchStartedAt);
        return best;
    }

    private static boolean supports(IStructureLocator locator, String canonicalId) {
        Collection<String> supported = locator.getSupportedIds();
        if (supported == null) {
            return false;
        }
        for (String id : supported) {
            if (id != null && canonical(id).equals(canonicalId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Normalises a structure id so that {@code village} and {@code minecraft:village} are the same
     * id, while {@code reccomplex:forest} keeps its own namespace.
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
}
