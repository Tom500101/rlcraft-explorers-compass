package com.tom500101.rlcompass.client.gui;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

import com.tom500101.rlcompass.locate.StructureVariant;
import com.tom500101.rlcompass.util.StructureNameUtil;

/**
 * One row of the structure picker: either a parent structure or one of its variants.
 *
 * <p>Parents with variants carry the variant list and an expansion flag; children carry the
 * variant's id plus the parent id the search request must send. Rows without variants behave
 * exactly like the old flat entries (no marker, same indentation).
 */
public class StructureSearchEntry {

    private final String structureId;
    private final String parentId;
    private final String variantId;
    private final String displayName;
    private final String source;
    private final String namespace;
    private final String parentDisplayName;
    private final List<StructureVariant> variants;
    private boolean expanded;

    public StructureSearchEntry(String structureId) {
        this(structureId, Collections.<StructureVariant>emptyList(), false);
    }

    public StructureSearchEntry(String structureId, List<StructureVariant> variants, boolean expanded) {
        this.structureId = structureId == null ? "" : structureId;
        this.parentId = this.structureId;
        this.variantId = null;
        this.displayName = StructureNameUtil.displayName(this.structureId);
        this.namespace = namespaceOf(this.structureId);
        this.source = namespace;
        this.parentDisplayName = this.displayName;
        this.variants = variants == null ? Collections.<StructureVariant>emptyList() : variants;
        this.expanded = expanded && !this.variants.isEmpty();
    }

    public StructureSearchEntry(StructureVariant variant) {
        this.variantId = variant.getVariantId();
        this.structureId = variant.getVariantId();
        this.parentId = variant.getParentId();
        this.displayName = variant.getDisplayName();
        this.source = variant.sourceLabel();
        this.namespace = namespaceOf(variant.getParentId());
        this.parentDisplayName = StructureNameUtil.displayName(variant.getParentId());
        this.variants = Collections.emptyList();
        this.expanded = false;
    }

    private static String namespaceOf(String id) {
        int colon = id == null ? -1 : id.indexOf(':');
        return colon > 0 ? id.substring(0, colon) : "minecraft";
    }

    public String getStructureId() {
        return structureId;
    }

    /** Parent structure id the search request must use; equals {@link #getStructureId()} for parents. */
    public String getParentId() {
        return parentId;
    }

    /** Variant id, or {@code null} for a parent row. */
    public String getVariantId() {
        return variantId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getSource() {
        return source;
    }

    public List<StructureVariant> getVariants() {
        return variants;
    }

    public boolean isExpandable() {
        return !variants.isEmpty();
    }

    public boolean isExpanded() {
        return expanded;
    }

    public void setExpanded(boolean expanded) {
        this.expanded = expanded && isExpandable();
    }

    /**
     * Matches a raw search box term. A leading {@code @} switches the match from the display name
     * to the structure's namespace; everything else is a case-insensitive substring match on either
     * the display name or the full id. Variant rows also match their parent's name and id, so
     * filtering by "doomlike" keeps the children visible under the parent.
     */
    /**
     * True when a child variant matches {@code filter}. Parents are otherwise filtered on their own
     * name only, which would hide "Ice Dungeon" completely: the needle is in the child, not the parent.
     */
    public boolean hasMatchingVariant(String filter) {
        if (variants.isEmpty() || filter == null) {
            return false;
        }
        String needle = filter.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty() || needle.startsWith("@")) {
            return false;
        }
        for (StructureVariant variant : variants) {
            if (variant.getDisplayName().toLowerCase(Locale.ROOT).contains(needle)
                    || variant.getVariantId().toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    public boolean matches(String filter) {
        if (filter == null) {
            return true;
        }
        String needle = filter.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return true;
        }
        if (needle.startsWith("@")) {
            return namespace.toLowerCase(Locale.ROOT).contains(needle.substring(1));
        }
        if (displayName.toLowerCase(Locale.ROOT).contains(needle)
                || structureId.toLowerCase(Locale.ROOT).contains(needle)) {
            return true;
        }
        return variantId != null
                && (parentDisplayName.toLowerCase(Locale.ROOT).contains(needle)
                || parentId.toLowerCase(Locale.ROOT).contains(needle));
    }
}
