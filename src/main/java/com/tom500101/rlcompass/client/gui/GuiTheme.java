package com.tom500101.rlcompass.client.gui;

import net.minecraft.client.gui.Gui;

/**
 * Flat translucent theme shared by the compass screens.
 *
 * <p>Derived from the reference implementation's colour state table
 * ({@code github.com/MattCzyr/ExplorersCompass}, {@code util/RenderUtils}), re-expressed here as
 * plain constants. Nothing is copied: the two numbers we actually depend on are the extracted spec
 * from the previous pass — {@link #PANEL} (one 50% black sheet behind the whole screen),
 * {@link #ROW_SELECTED} (the 25% overlay stacked on it for the selected row) and
 * {@link #SCROLLBAR_WIDTH}. The reference's own selected state is a single {@code 0xB8000000} fill;
 * we keep the user-facing spec instead, which composites to roughly the same darkness.
 *
 * <p>There are no borders, no drop shadows and no accent colour on purpose: the reference screen is
 * text on a dimmed world, and every widget is a flat translucent fill. The disabled and hovered
 * states come from the same table (alpha 0x2B and 0xB8).
 */
public final class GuiTheme {

    /** Extracted: 50% black, drawn once behind the whole screen. */
    public static final int PANEL = 0x7F000000;

    /** Extracted: 25% black, layered on {@link #PANEL} for the selected row. */
    public static final int ROW_SELECTED = 0x3F000000;

    /** Extracted: scrollbar width in GUI pixels. */
    public static final int SCROLLBAR_WIDTH = 6;

    /** Reference state table: hovered controls and scrollbar thumb (alpha 0xB8). */
    public static final int CONTROL_HOVERED = 0xB8000000;

    /** Reference state table: disabled controls and scrollbar track (alpha 0x2B). */
    public static final int CONTROL_DISABLED = 0x2B000000;

    public static final int SCROLL_TRACK = CONTROL_DISABLED;
    public static final int SCROLL_THUMB = CONTROL_HOVERED;

    public static final int TEXT = 0xFFFFFFFF;
    public static final int TEXT_INFO = 0xFF808080;
    public static final int TEXT_HINT = 0xFF808080;
    public static final int TEXT_DISABLED = 0xFF808080;

    private GuiTheme() {
    }

    /** The one translucent sheet every screen sits on; the world stays visible behind it. */
    public static void drawBackdrop(int width, int height) {
        Gui.drawRect(0, 0, width, height, PANEL);
    }
}
