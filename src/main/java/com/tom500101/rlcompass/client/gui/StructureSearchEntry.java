package com.tom500101.rlcompass.client.gui;

import java.util.Locale;

import com.tom500101.rlcompass.util.StructureNameUtil;

/**
 * One row of the structure picker.
 *
 * <p>Ids are the only thing the compass really needs, but a bare registry id reads badly
 * ("rlcompass:battletower"), so each row keeps a display name and the source namespace next to
 * it. The namespace doubles as the {@code @} filter target in the search box, which is how a
 * player narrows the list to one mod's structures in RLCraft's very crowded catalog.
 */
public class StructureSearchEntry {

    private final String structureId;
    private final String displayName;
    private final String source;

    public StructureSearchEntry(String structureId) {
        this.structureId = structureId == null ? "" : structureId;
        this.displayName = StructureNameUtil.displayName(this.structureId);
        int colon = this.structureId.indexOf(':');
        this.source = colon > 0 ? this.structureId.substring(0, colon) : "minecraft";
    }

    public String getStructureId() {
        return structureId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getSource() {
        return source;
    }

    /**
     * Matches a raw search box term. A leading {@code @} switches the match from the display name
     * to the source namespace; everything else is a case-insensitive substring match on either the
     * display name or the full id.
     */
    public boolean matches(String filter) {
        if (filter == null) {
            return true;
        }
        String needle = filter.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return true;
        }
        if (needle.startsWith("@")) {
            return source.toLowerCase(Locale.ROOT).contains(needle.substring(1));
        }
        return displayName.toLowerCase(Locale.ROOT).contains(needle)
                || structureId.toLowerCase(Locale.ROOT).contains(needle);
    }
}
