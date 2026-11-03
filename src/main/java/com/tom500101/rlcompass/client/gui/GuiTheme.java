package com.tom500101.rlcompass.client.gui;

/**
 * Flat translucent theme for the compass screens.
 *
 * <p>The list follows one rule: a single translucent black surface, and selection is that same
 * surface stacked a second time. There are no row borders, no separators and no accent colour —
 * depth comes only from the double layer on the selected row. That is why {@link #LIST_BACKGROUND}
 * and {@link #SELECTION_LAYER} are deliberately the same value.
 *
 * <p>Numbers are the extracted spec from the reference screen
 * ({@code github.com/MattCzyr/ExplorersCompass}, {@code gui/StructureSearchList} +
 * {@code util/RenderUtils}): surface alpha 0x7F, scrollbar track 0x2B, thumb 0xF2 (the reference's
 * "hovered" state 4). Implemented here independently, no upstream class is copied.
 */
public final class GuiTheme {

    /** Extracted: the list area, one 50% black surface. */
    public static final int LIST_BACKGROUND = 0x7F000000;

    /** Extracted: selection stacks the same surface again (0x7F over 0x7F ≈ 0xBF). */
    public static final int SELECTION_LAYER = 0x7F000000;

    /** Extracted: scrollbar width in GUI pixels. */
    public static final int SCROLLBAR_WIDTH = 6;

    /** Same surface value, used by buttons and the filter box. */
    public static final int CONTROL = 0x7F000000;

    /** Reference state table: hovered controls (alpha 0xB8). */
    public static final int CONTROL_HOVERED = 0xB8000000;

    /** Reference state table: disabled controls (alpha 0x2B). */
    public static final int CONTROL_DISABLED = 0x2B000000;

    /** Extracted: scrollbar track and thumb. The thumb is the darkest element on screen. */
    public static final int SCROLL_TRACK = 0x2B000000;
    public static final int SCROLL_THUMB = 0xF2000000;

    public static final int TEXT = 0xFFFFFFFF;
    public static final int TEXT_INFO = 0xFF808080;
    public static final int TEXT_HINT = 0xFF808080;
    public static final int TEXT_DISABLED = 0xFF808080;

    private GuiTheme() {
    }
}
