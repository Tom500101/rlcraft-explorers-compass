package com.tom500101.rlcompass.client.gui;

import net.minecraft.client.gui.Gui;

/**
 * Flat translucent theme shared by the compass screens.
 *
 * <p>Three values are the extracted spec (see {@code docs/HANDOFF.md} §8) and must not drift:
 * {@link #PANEL} is the single 50% black sheet behind the whole screen, {@link #ROW_SELECTED} is the
 * 25% black overlay stacked on the sheet for a selected row, and {@link #SCROLLBAR_WIDTH} is the
 * scrollbar width in GUI pixels. Everything else is derived from those three so the screens share
 * one palette; the comments say which is which.
 *
 * <p>All widget fills are translucent on purpose: the point of the flat list is that the world stays
 * visible behind the screen, so a control that paints an opaque box breaks the design. 1.12.2's
 * {@link Gui#drawRect} enables blending itself, so the alpha in these constants is honoured.
 */
public final class GuiTheme {

    /** Extracted. 50% black; drawn once behind the whole screen. */
    public static final int PANEL = 0x7F000000;

    /** Extracted. 25% black; layered on {@link #PANEL} for the selected row. */
    public static final int ROW_SELECTED = 0x3F000000;

    /** Extracted. Scrollbar width in GUI pixels. */
    public static final int SCROLLBAR_WIDTH = 6;

    /** Derived: half the selection overlay, enough to follow the mouse without reading as a selection. */
    public static final int ROW_HOVER = 0x28000000;

    /** Derived: 1px hairlines replace the old framed panel borders. */
    public static final int DIVIDER = 0x26FFFFFF;
    public static final int BORDER = 0x40FFFFFF;
    public static final int BORDER_DISABLED = 0x1FFFFFFF;

    public static final int TEXT = 0xFFFFFFFF;
    public static final int TEXT_MUTED = 0xFFB3BEC8;
    public static final int TEXT_DISABLED = 0xFF7A858F;

    /** Warm gold, used only for selection, focus and section labels. */
    public static final int ACCENT = 0xFFD8C58C;

    /** Derived: translucent widget fills, so the sheet stays visible through every control. */
    public static final int BUTTON = 0x33000000;
    public static final int BUTTON_HOVERED = 0x4D000000;
    public static final int BUTTON_DISABLED = 0x1F000000;
    public static final int FIELD = 0x4D000000;

    public static final int SCROLL_TRACK = 0x24000000;
    public static final int SCROLL_THUMB = 0xCCD8C58C;

    private GuiTheme() {
    }

    /** The one translucent sheet every screen sits on. */
    public static void drawBackdrop(int width, int height) {
        Gui.drawRect(0, 0, width, height, PANEL);
    }

    /** 1px horizontal hairline, {@code y} inclusive. */
    public static void hLine(int left, int right, int y) {
        Gui.drawRect(left, y, right, y + 1, DIVIDER);
    }

    /** 1px vertical hairline, {@code x} inclusive. */
    public static void vLine(int x, int top, int bottom) {
        Gui.drawRect(x, top, x + 1, bottom, DIVIDER);
    }

    /** 1px outline on all four sides; the flat replacement for a framed panel border. */
    public static void outline(int left, int top, int right, int bottom, int color) {
        Gui.drawRect(left, top, right, top + 1, color);
        Gui.drawRect(left, bottom - 1, right, bottom, color);
        Gui.drawRect(left, top, left + 1, bottom, color);
        Gui.drawRect(right - 1, top, right, bottom, color);
    }
}
