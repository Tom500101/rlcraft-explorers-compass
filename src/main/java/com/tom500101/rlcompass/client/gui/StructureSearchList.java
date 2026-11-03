package com.tom500101.rlcompass.client.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiSlot;
import net.minecraft.client.gui.ScaledResolution;

import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

/**
 * Scrollable list of structures, drawn as flat translucent rows.
 *
 * <p>{@code GuiSlot} is used only for its scroll bookkeeping ({@code amountScrolled},
 * {@code bindAmountScrolled}, {@code getMaxScroll}); its own rendering is bypassed because the
 * vanilla slot blits selection sprites and draws scroll buttons we do not want. Rows are clipped
 * with an explicit scissor box so a partially visible row cannot bleed over the catalog header.
 *
 * <p>A row paints no box of its own: the screen sheet shows through, and only hover/selection add a
 * translucent overlay ({@link GuiTheme#ROW_HOVER} / {@link GuiTheme#ROW_SELECTED}) plus a 2px gold
 * bar on the selected row. Rows are separated by a 1px hairline instead of a border, which is what
 * makes the list read as one continuous sheet rather than a stack of cards.
 */
public class StructureSearchList extends GuiSlot {

    private static final long DOUBLE_CLICK_MS = 250L;
    private static final int SELECTION_BAR_WIDTH = 2;
    /** Every name starts in the same column, so the +/- marker never shifts the text. */
    private static final int ROW_TEXT_LEFT = 16;
    private static final int VARIANT_INDENT = 12;

    private final GuiStructureSelect parent;
    private final List<StructureSearchEntry> entries = new ArrayList<StructureSearchEntry>();
    private int selectedIndex = -1;
    private int lastClickedIndex = -1;
    private long lastClickTime;

    public StructureSearchList(GuiStructureSelect parent, Minecraft mc, int left, int right, int top, int bottom,
                               int slotHeight) {
        super(mc, Math.max(1, right - left), Math.max(1, bottom - top), top, bottom, slotHeight);
        this.parent = parent;
        updateBounds(left, right, top, bottom);
    }

    /**
     * Repositions the viewport. {@code GuiSlot#setDimensions} is not used because it forces
     * {@code left} to 0 and {@code right} to the width, which breaks a list inset into a panel.
     */
    public void updateBounds(int left, int right, int top, int bottom) {
        this.left = left;
        this.right = Math.max(left + 1, right);
        this.top = top;
        this.bottom = Math.max(top + 1, bottom);
        this.width = this.right - this.left;
        this.height = this.bottom - this.top;
        bindAmountScrolled();
    }

    /** Replaces the visible rows, keeping the previously selected id when it survived the filter. */
    public void setEntries(List<StructureSearchEntry> newEntries, String idToSelect) {
        entries.clear();
        entries.addAll(newEntries);
        selectedIndex = -1;
        if (idToSelect != null) {
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).getStructureId().equals(idToSelect)) {
                    selectedIndex = i;
                    break;
                }
            }
        }
        bindAmountScrolled();
    }

    public int getEntryCount() {
        return entries.size();
    }

    public boolean hasSelection() {
        return selectedIndex >= 0 && selectedIndex < entries.size();
    }

    public StructureSearchEntry getSelected() {
        return hasSelection() ? entries.get(selectedIndex) : null;
    }

    /** Read-only row access used by the client smoke test. */
    public StructureSearchEntry getEntry(int index) {
        return index >= 0 && index < entries.size() ? entries.get(index) : null;
    }

    public String getEntryId(int index) {
        StructureSearchEntry entry = getEntry(index);
        return entry == null ? null : entry.getStructureId();
    }

    public String getEntryDisplayName(int index) {
        StructureSearchEntry entry = getEntry(index);
        return entry == null ? "" : entry.getDisplayName();
    }

    /**
     * Test hook: two clicks on the same row within {@link #DOUBLE_CLICK_MS}, which is the same path
     * a player takes. Returns true when both clicks landed inside the list.
     */
    public boolean simulateDoubleClick(int index) {
        if (index < 0 || index >= entries.size()) {
            return false;
        }
        this.amountScrolled = 0;
        bindAmountScrolled();
        // Past the expand/collapse marker so a double click always means "search this row".
        int x = this.left + 24;
        int y = this.top + index * this.slotHeight + 2;
        boolean first = handleClick(x, y);
        boolean second = handleClick(x, y);
        return first && second;
    }

    /** Handles a left click on a row; returns false when the click was outside the list. */
    public boolean handleClick(int mouseX, int mouseY) {
        if (mouseX < this.left || mouseX > this.right - 10 || mouseY < this.top || mouseY > this.bottom) {
            return false;
        }
        int slotIndex = (mouseY - this.top + (int) this.amountScrolled) / this.slotHeight;
        if (slotIndex < 0 || slotIndex >= entries.size()) {
            return false;
        }
        long now = System.currentTimeMillis();
        boolean doubleClick = slotIndex == lastClickedIndex && now - lastClickTime <= DOUBLE_CLICK_MS;
        elementClicked(slotIndex, doubleClick, mouseX, mouseY);
        lastClickedIndex = slotIndex;
        lastClickTime = now;
        return true;
    }

    @Override
    protected int getSize() {
        return entries.size();
    }

    @Override
    protected int getContentHeight() {
        return entries.size() * this.slotHeight;
    }

    @Override
    protected int getScrollBarX() {
        return this.right - GuiTheme.SCROLLBAR_WIDTH - 2;
    }

    @Override
    protected void elementClicked(int slotIndex, boolean isDoubleClick, int mouseX, int mouseY) {
        if (slotIndex < 0 || slotIndex >= entries.size()) {
            return;
        }
        StructureSearchEntry entry = entries.get(slotIndex);
        // The marker is a separate hit target: single click toggles, double click still searches.
        if (entry.isExpandable() && !isDoubleClick && mouseX < this.left + 18) {
            parent.toggleExpansion(entry);
            return;
        }
        selectedIndex = slotIndex;
        parent.onSelectionChanged();
        if (isDoubleClick) {
            parent.searchFor(entry);
        }
    }

    @Override
    protected boolean isSelected(int slotIndex) {
        return slotIndex == selectedIndex;
    }

    @Override
    protected void drawBackground() {
        // The screen sheet is painted by drawScreen; the list itself stays transparent.
    }

    @Override
    public void drawScreen(int mouseXIn, int mouseYIn, float partialTicks) {
        ScaledResolution scaled = new ScaledResolution(this.mc);
        int scale = scaled.getScaleFactor();
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(this.left * scale,
                this.mc.displayHeight - this.bottom * scale,
                (this.right - this.left) * scale,
                (this.bottom - this.top) * scale);
        int contentTop = this.top - (int) this.amountScrolled;
        for (int i = 0; i < entries.size(); i++) {
            int slotY = contentTop + i * this.slotHeight;
            if (slotY + this.slotHeight >= this.top && slotY <= this.bottom) {
                drawSlot(i, this.left, slotY, this.slotHeight, mouseXIn, mouseYIn, partialTicks);
            }
        }
        GL11.glDisable(GL11.GL_SCISSOR_TEST);

        drawScrollbar();
    }

    @Override
    protected void drawSlot(int slotIndex, int xPos, int yPos, int heightIn, int mouseXIn, int mouseYIn,
                            float partialTicks) {
        StructureSearchEntry entry = entries.get(slotIndex);
        boolean selected = slotIndex == selectedIndex;
        int rowRight = this.right - GuiTheme.SCROLLBAR_WIDTH - 6;
        int rowBottom = yPos + heightIn;
        boolean hovered = mouseYIn >= this.top && mouseYIn < this.bottom
                && mouseXIn >= this.left && mouseXIn < rowRight
                && mouseYIn >= yPos && mouseYIn < rowBottom;

        // Selection first, so the hover overlay never stacks on top of it.
        if (selected) {
            Gui.drawRect(this.left, yPos, rowRight, rowBottom, GuiTheme.ROW_SELECTED);
            Gui.drawRect(this.left, yPos, this.left + SELECTION_BAR_WIDTH, rowBottom, GuiTheme.ACCENT);
        } else if (hovered) {
            Gui.drawRect(this.left, yPos, rowRight, rowBottom, GuiTheme.ROW_HOVER);
        }

        int textLeft = this.left + ROW_TEXT_LEFT;
        if (entry.isExpandable()) {
            this.mc.fontRenderer.drawStringWithShadow(entry.isExpanded() ? "-" : "+",
                    this.left + 5, yPos + 6, GuiTheme.ACCENT);
        }
        if (entry.getVariantId() != null) {
            textLeft += VARIANT_INDENT;
        }

        int textLimit = rowRight - 6 - textLeft;
        this.mc.fontRenderer.drawStringWithShadow(
                trim(entry.getDisplayName(), textLimit),
                textLeft, yPos + 6, selected ? GuiTheme.ACCENT : GuiTheme.TEXT);
        // Both halves are identifiers, so no translation wrapper: the separator is the label.
        this.mc.fontRenderer.drawStringWithShadow(
                trim(entry.getSource() + "  |  " + entry.getStructureId(), textLimit),
                textLeft, yPos + 20, GuiTheme.TEXT_MUTED);

        GuiTheme.hLine(this.left + 8, rowRight, rowBottom - 1);
    }

    @Override
    public void handleMouseInput() {
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            int direction = wheel > 0 ? -1 : 1;
            this.amountScrolled += direction * (this.slotHeight / 2.0F);
            bindAmountScrolled();
        }
    }

    private String trim(String text, int width) {
        if (width <= 0) {
            return "";
        }
        return this.mc.fontRenderer.trimStringToWidth(text, width);
    }

    private void drawScrollbar() {
        int viewport = this.bottom - this.top;
        int totalHeight = entries.size() * this.slotHeight;
        if (totalHeight <= viewport) {
            return;
        }
        int barRight = this.right - 2;
        int barLeft = barRight - GuiTheme.SCROLLBAR_WIDTH;
        Gui.drawRect(barLeft, this.top, barRight, this.bottom, GuiTheme.SCROLL_TRACK);

        int thumbHeight = Math.max(24, viewport * viewport / totalHeight);
        int maxThumbTop = Math.max(0, viewport - thumbHeight);
        int track = Math.max(1, totalHeight - viewport);
        int thumbTop = this.top + (int) (this.amountScrolled / (float) track * maxThumbTop);
        Gui.drawRect(barLeft, thumbTop, barRight, thumbTop + thumbHeight, GuiTheme.SCROLL_THUMB);
    }
}
