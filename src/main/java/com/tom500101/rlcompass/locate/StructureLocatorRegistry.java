package com.tom500101.rlcompass.locate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    /** Mirrors the {@code @Config.RangeInt} bounds on {@link CompassConfig#searchRadius}. */
    private static final int MIN_RADIUS = 100;
    private static final int MAX_RADIUS = 20000;

    /** Variant lists may scan a config directory; 60s keeps repeated picker opens cheap. */
    private static final long VARIANT_CACHE_TTL_MS = 60_000L;
    private static final Map<String, CachedVariants> VARIANT_CACHE = new HashMap<String, CachedVariants>();

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

    /**
     * Ids the selection GUI may show: supported minus blacklisted, sorted for stable display.
     *
     * <p>This does <b>not</b> check whether a locator's data source exists; use
     * {@link #getSelectableIds(World)} when a world is available.
     */
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
     * Ids the selection GUI may show in {@code world}: like {@link #getSelectableIds()}, but each
     * id is kept only when at least one supporting locator answers
     * {@link IStructureLocator#canAnswer(World, String)} == true <b>for that id</b>. Checking per
     * locator instead would leak ids: the cache mirror can hold ruins while having no towers.
     *
     * <p>{@code null} keeps the legacy behaviour and performs no availability filtering, which is
     * what the title-screen smoke test needs.
     */
    public List<String> getSelectableIds(World world) {
        if (world == null) {
            return getSelectableIds();
        }
        Set<String> ids = new LinkedHashSet<String>();
        for (IStructureLocator locator : locators) {
            Collection<String> supported = locator.getSupportedIds();
            if (supported == null) {
                continue;
            }
            for (String id : supported) {
                if (id == null || id.trim().isEmpty()) {
                    continue;
                }
                String canonicalId = canonical(id);
                if (locator.canAnswer(world, canonicalId)) {
                    ids.add(canonicalId);
                }
            }
        }
        List<String> selectable = new ArrayList<String>();
        for (String id : ids) {
            if (!CompassConfig.isBlacklisted(id)) {
                selectable.add(id);
            }
        }
        Collections.sort(selectable);
        return selectable;
    }

    /**
     * Variants exposed by the locators supporting {@code parentId}, sorted by display name.
     * Cached for {@link #VARIANT_CACHE_TTL_MS} per dimension because the source is usually a config
     * directory scan.
     */
    public List<StructureVariant> getVariants(World world, String parentId) {
        if (world == null || parentId == null) {
            return Collections.emptyList();
        }
        String canonicalParent = canonical(parentId);
        String key = world.provider.getDimension() + "|" + canonicalParent;
        long now = System.currentTimeMillis();
        synchronized (VARIANT_CACHE) {
            CachedVariants cached = VARIANT_CACHE.get(key);
            if (cached != null && now - cached.loadedAt < VARIANT_CACHE_TTL_MS) {
                return cached.variants;
            }
        }

        List<StructureVariant> collected = new ArrayList<StructureVariant>();
        for (IStructureLocator locator : locators) {
            if (!supports(locator, canonicalParent)) {
                continue;
            }
            List<StructureVariant> variants;
            try {
                variants = locator.getVariants(world);
            } catch (Throwable t) {
                LOG.error("[REGISTRY] {} threw while listing variants of {}",
                        locator.sourceName(), parentId, t);
                continue;
            }
            if (variants == null) {
                continue;
            }
            for (StructureVariant variant : variants) {
                if (variant != null && canonicalParent.equals(canonical(variant.getParentId()))) {
                    collected.add(variant);
                }
            }
        }
        Collections.sort(collected, new Comparator<StructureVariant>() {
            @Override
            public int compare(StructureVariant a, StructureVariant b) {
                return a.getDisplayName().compareToIgnoreCase(b.getDisplayName());
            }
        });
        List<StructureVariant> result = Collections.unmodifiableList(collected);
        synchronized (VARIANT_CACHE) {
            VARIANT_CACHE.put(key, new CachedVariants(now, result));
        }
        return result;
    }

    /**
     * Finds the nearest structure of {@code structureId} within {@code radius} blocks of
     * {@code playerPos}, or {@code null} when none was found.
     */
    public StructureResult findNearest(World world, BlockPos playerPos, String structureId, int radius) {
        return findNearest(world, playerPos, structureId, radius, null);
    }

    /**
     * Variant-aware search: {@code variantId} is handed to every supporting locator; locators that
     * do not understand it fall back to the parent search via the interface default.
     */
    public StructureResult findNearest(World world, BlockPos playerPos, String structureId, int radius,
                                       String variantId) {
        if (world == null || playerPos == null || structureId == null) {
            return null;
        }
        if (CompassConfig.isBlacklisted(structureId)) {
            LOG.info("[REGISTRY] refusing to search {} — blacklisted in config", structureId);
            return null;
        }
        // Clamp defensively: the annotation on CompassConfig only guards the config-file path, and a
        // radius of 0 would silently discard every candidate while an unbounded one could stall the
        // server inside a locator's spiral.
        int effectiveRadius = Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, radius));
        String wanted = canonical(structureId);
        double maxDistanceSq = (double) effectiveRadius * (double) effectiveRadius;
        StructureResult best = null;
        long searchStartedAt = System.currentTimeMillis();

        for (IStructureLocator locator : locators) {
            if (!supports(locator, wanted)) {
                continue;
            }
            long startedAt = System.currentTimeMillis();
            StructureResult candidate;
            try {
                candidate = locator.findNearest(world, playerPos, structureId, effectiveRadius, variantId);
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
                        candidate, Math.round(candidate.getDistance()), effectiveRadius);
                continue;
            }
            if (best == null || candidate.getDistanceSq() < best.getDistanceSq()) {
                best = candidate;
            }
        }

        LOG.info("[REGISTRY] findNearest({}{}) from ({}, {}) -> {} ({} ms)",
                structureId,
                variantId == null || variantId.isEmpty() ? "" : " variant=" + variantId,
                playerPos.getX(), playerPos.getZ(), best,
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

    private static final class CachedVariants {
        final long loadedAt;
        final List<StructureVariant> variants;

        CachedVariants(long loadedAt, List<StructureVariant> variants) {
            this.loadedAt = loadedAt;
            this.variants = variants;
        }
    }
}
