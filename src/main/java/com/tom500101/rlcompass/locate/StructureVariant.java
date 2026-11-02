package com.tom500101.rlcompass.locate;

import java.util.Locale;

/**
 * A sub-type of a registered structure.
 *
 * <p>1.12.2's structure registry knows one id per structure family, but several mods generate
 * themed variants behind that single id (Doomlike Dungeons' urban/desert/frozen themes, for
 * example). A locator may expose those variants; the picker then shows them as children of the
 * parent row and the search request carries the chosen variant id.
 *
 * <p>The source matters for honesty: a variant read from a config file can be listed, but the
 * generator may pick a different theme at runtime. Only {@link VariantSource#RUNTIME_API} can be
 * confirmed after the fact; the GUI and the search result label the rest accordingly.
 */
public final class StructureVariant {

    public enum VariantSource {
        /** Read from a config file or directory: listable, but the search cannot confirm it. */
        CONFIG_FILE,
        /** Read through the mod's own API: trusted and confirmable. */
        RUNTIME_API,
        /** Inferred from names or layout: low confidence, must be labelled as unconfirmed. */
        HEURISTIC
    }

    private final String variantId;
    private final String parentId;
    private final String displayName;
    private final VariantSource source;

    public StructureVariant(String variantId, String parentId, String displayName, VariantSource source) {
        if (variantId == null || variantId.trim().isEmpty()) {
            throw new IllegalArgumentException("variantId must not be empty");
        }
        if (parentId == null || parentId.trim().isEmpty()) {
            throw new IllegalArgumentException("parentId must not be empty");
        }
        this.variantId = variantId;
        this.parentId = parentId;
        this.displayName = displayName == null || displayName.trim().isEmpty()
                ? variantId
                : displayName;
        this.source = source == null ? VariantSource.HEURISTIC : source;
    }

    public String getVariantId() {
        return variantId;
    }

    public String getParentId() {
        return parentId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public VariantSource getSource() {
        return source;
    }

    /** Short, player-facing label for the source column: config / api / inferred. */
    public String sourceLabel() {
        switch (source) {
            case RUNTIME_API:
                return "api";
            case CONFIG_FILE:
                return "config";
            default:
                return "inferred";
        }
    }

    @Override
    public String toString() {
        return variantId + " (" + source.name().toLowerCase(Locale.ROOT) + ")";
    }
}
