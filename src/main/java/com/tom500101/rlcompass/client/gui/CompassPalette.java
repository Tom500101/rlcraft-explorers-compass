package com.tom500101.rlcompass.client.gui;

import net.minecraft.client.gui.Gui;

/**
 * Palette and chrome helpers shared by the compass screens.
 *
 * <p>The values follow the conventional 1.12.2 structure-locator look: a near-black blue
 * backdrop, layered dark panels with a 1px light border, a warm gold accent for interactive
 * highlights and a muted blue-grey for secondary text. Keeping the panel helper in one place
 * means every screen in the mod draws its borders and drop shadows identically.
 */
public final class CompassPalette {

    /** Full-screen backdrop behind the root panel. */
    public static final int SCREEN_BACKGROUND = 0xFF0A1016;

    public static final int PANEL_FILL = 0xFF121A22;
    public static final int PANEL_SHADOW = 0xFF0A1016;
    public static final int PANEL_BORDER = 0xFF6B8BA0;

    /** Highlight colour used for section labels, focus borders and the scrollbar thumb. */
    public static final int ACCENT = 0xFFD8C58C;

    public static final int MUTED_TEXT = 0xFF9EABB6;
    public static final int TEXT = 0xFFFFFFFF;
    public static final int TEXT_DISABLED = 0xFF7F8A93;

    public static final int EDIT_BOX_FILL = 0xFF10161D;

    public static final int ENTRY_FILL = 0xFF202A34;
    public static final int ENTRY_FILL_SELECTED = 0xFF314B63;
    public static final int ENTRY_BORDER = 0xFF4B6577;
    public static final int ENTRY_BORDER_SELECTED = 0xFFE1D2A2;

    public static final int BUTTON_FILL = 0xFF213142;
    public static final int BUTTON_FILL_HOVERED = 0xFF2E4860;
    public static final int BUTTON_FILL_DISABLED = 0xFF25303D;
    public static final int BUTTON_BORDER = 0xFF8FB6C9;
    public static final int BUTTON_BORDER_HOVERED = 0xFFD8C58C;
    public static final int BUTTON_BORDER_DISABLED = 0xFF414C57;

    private CompassPalette() {
    }

    /** Panel body with an offset drop shadow and a 1px border on all four sides. */
    public static void drawFramedPanel(int left, int top, int right, int bottom) {
        Gui.drawRect(left + 2, top + 2, right + 2, bottom + 2, PANEL_SHADOW);
        Gui.drawRect(left, top, right, bottom, PANEL_FILL);
        drawOutline(left, top, right, bottom, PANEL_BORDER);
    }

    public static void drawOutline(int left, int top, int right, int bottom, int color) {
        Gui.drawRect(left, top, right, top + 1, color);
        Gui.drawRect(left, bottom - 1, right, bottom, color);
        Gui.drawRect(left, top, left + 1, bottom, color);
        Gui.drawRect(right - 1, top, right, bottom, color);
    }

    /** Horizontal rule used to separate a panel header from its body. */
    public static void drawAccentRule(int left, int y, int right) {
        Gui.drawRect(left, y, right, y + 2, ACCENT);
    }
}
