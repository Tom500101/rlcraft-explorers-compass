package com.tom500101.rlcompass.client.gui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.init.SoundEvents;

import com.tom500101.rlcompass.locate.StructureLocatorRegistry;
import com.tom500101.rlcompass.locate.StructureVariant;
import com.tom500101.rlcompass.network.PacketHandler;
import com.tom500101.rlcompass.network.PacketSearchRequest;

/**
 * Structure picker shown when the compass is right-clicked.
 *
 * <p>Laid out as three framed panels over a dark backdrop: a header with the title and the filter
 * box, an action column on the left, and the scrollable structure catalog on the right. All the
 * measurements are derived from {@code width}/{@code height} so the screen reflows on any GUI
 * scale instead of assuming one fixed size.
 *
 * <p>The id list comes from the server (see {@code PacketOpenCompassGui}) because it depends on
 * which mods are installed and which ids the config blacklist removed. Picking an entry sends a
 * search request and closes the screen; the answer arrives later as a {@code PacketStructureResult}.
 */
public class GuiStructureSelect extends GuiScreen {

    private static final int SEARCH_BUTTON_ID = 0;
    private static final int SORT_BUTTON_ID = 1;
    private static final int CANCEL_BUTTON_ID = 2;

    private static final int RESULTS_HEADER_HEIGHT = 40;
    private static final int SLOT_HEIGHT = 44;
    private static final int PANEL_GAP = 14;

    private final List<StructureSearchEntry> allEntries = new ArrayList<StructureSearchEntry>();
    private final List<String> baseIds = new ArrayList<String>();
    private final Set<String> expandedParents = new HashSet<String>();

    private TransparentButton searchButton;
    private TransparentButton sortButton;
    private TransparentButton cancelButton;
    private TransparentEditBox searchBox;
    private StructureSearchList selectionList;

    private SortMode sortMode = SortMode.NAME;
    private String lastFilter = "";

    public GuiStructureSelect(List<String> structureIds) {
        if (structureIds != null) {
            baseIds.addAll(structureIds);
        }
        // Deterministic base order; the active SortMode decides the final ordering.
        Collections.sort(baseIds);
    }

    /** Rebuilds parent rows (and their variant lists) from the ids the server sent. */
    private void rebuildEntries() {
        allEntries.clear();
        for (String id : baseIds) {
            List<StructureVariant> variants = StructureLocatorRegistry.get().getVariants(mc.world, id);
            allEntries.add(new StructureSearchEntry(id, variants, expandedParents.contains(id)));
        }
    }

    @Override
    public void initGui() {
        buttonList.clear();

        searchButton = new TransparentButton(SEARCH_BUTTON_ID, 0, 0, 180, 20,
                I18n.format("gui.rlcompass.search"));
        sortButton = new TransparentButton(SORT_BUTTON_ID, 0, 0, 80, 18, sortMode.label());
        cancelButton = new TransparentButton(CANCEL_BUTTON_ID, 0, 0, 80, 18, I18n.format("gui.cancel"));
        buttonList.add(searchButton);
        buttonList.add(sortButton);
        buttonList.add(cancelButton);

        searchBox = new TransparentEditBox(0, fontRenderer, 0, 0, 180, 12);
        searchBox.setEnableBackgroundDrawing(false);
        searchBox.setCanLoseFocus(false);
        searchBox.setFocused(true);
        searchBox.setMaxStringLength(64);
        searchBox.setText(lastFilter);

        selectionList = new StructureSearchList(this, mc, 0, 1, 0, 1, SLOT_HEIGHT);
        rebuildEntries();

        layoutControls();
        refreshEntries(false);
    }

    @Override
    public void updateScreen() {
        searchBox.updateCursorCounter();
        if (!searchBox.getText().equals(lastFilter)) {
            lastFilter = searchBox.getText();
            refreshEntries(true);
        }
        // The window can be resized while the screen is open, so the layout is re-derived here.
        layoutControls();
        updateButtonState();
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        selectionList.handleMouseInput();
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (mouseButton == 0 && selectionList.handleClick(mouseX, mouseY)) {
            return;
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
        searchBox.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        // ESC is not consumed by the text field, so it still closes the screen while typing.
        if (searchBox.textboxKeyTyped(typedChar, keyCode)) {
            lastFilter = searchBox.getText();
            refreshEntries(true);
            return;
        }
        if (keyCode == 1) {
            mc.displayGuiScreen(null);
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button.id == SEARCH_BUTTON_ID) {
            StructureSearchEntry selected = selectionList.getSelected();
            if (selected != null) {
                // Parent + variant, never the composed variant id: the registry routes on the parent.
                searchFor(selected.getParentId(), selected.getVariantId());
            }
        } else if (button.id == SORT_BUTTON_ID) {
            sortMode = sortMode.next();
            sortButton.displayString = sortMode.label();
            refreshEntries(true);
        } else if (button.id == CANCEL_BUTTON_ID) {
            mc.displayGuiScreen(null);
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        int margin = getRootMargin();
        int headerTop = getHeaderTop();
        int headerBottom = headerTop + getHeaderHeight();
        int sidePanelLeft = getSidePanelLeft();
        int sidePanelRight = sidePanelLeft + getSidePanelWidth();
        int listLeft = getListLeft();
        int listTop = getListTop();
        int listRight = getListRight();
        int panelBottom = getPanelBottom();

        CompassPalette.drawFramedPanel(margin / 2, margin / 2, width - margin / 2, height - margin / 2);
        CompassPalette.drawFramedPanel(margin, headerTop, width - margin, headerBottom);
        CompassPalette.drawFramedPanel(sidePanelLeft, listTop, sidePanelRight, panelBottom);
        CompassPalette.drawFramedPanel(listLeft, listTop, listRight, panelBottom);

        CompassPalette.drawAccentRule(margin + 12, headerBottom - 12, width - margin - 12);

        selectionList.drawScreen(mouseX, mouseY, partialTicks);

        // Results header sits on top of the list so rows scroll underneath it.
        Gui.drawRect(listLeft + 1, listTop + 1, listRight - 1,
                listTop + RESULTS_HEADER_HEIGHT, CompassPalette.PANEL_FILL);
        Gui.drawRect(listLeft + 1, listTop + RESULTS_HEADER_HEIGHT - 1,
                listRight - 1, listTop + RESULTS_HEADER_HEIGHT, CompassPalette.PANEL_BORDER);

        fontRenderer.drawString(I18n.format("gui.rlcompass.title"), margin + 12, headerTop + 8,
                CompassPalette.TEXT);
        fontRenderer.drawString(I18n.format("gui.rlcompass.subtitle"), margin + 12, headerTop + 22,
                CompassPalette.MUTED_TEXT);

        fontRenderer.drawString(I18n.format("gui.rlcompass.actions"), sidePanelLeft + 12, listTop + 12,
                CompassPalette.ACCENT);
        // The @mod filter syntax does not fit in the header next to the box, so it lives here.
        fontRenderer.drawString(I18n.format("gui.rlcompass.hint"), sidePanelLeft + 12, listTop + 100,
                CompassPalette.MUTED_TEXT);
        // Variants marked ≈ are labels, not filters: the mod rolls the theme while generating.
        fontRenderer.drawString(I18n.format("gui.rlcompass.hint.variant"), sidePanelLeft + 12,
                listTop + 112, CompassPalette.MUTED_TEXT);
        fontRenderer.drawString(I18n.format("gui.rlcompass.catalog"), listLeft + 12, listTop + 12,
                CompassPalette.ACCENT);
        fontRenderer.drawString(
                I18n.format("gui.rlcompass.count", selectionList.getEntryCount(), allEntries.size()),
                listLeft + 12, listTop + 26, CompassPalette.MUTED_TEXT);

        if (allEntries.isEmpty()) {
            fontRenderer.drawString(I18n.format("gui.rlcompass.empty"), listLeft + 12,
                    listTop + RESULTS_HEADER_HEIGHT + 12, CompassPalette.MUTED_TEXT);
        } else if (selectionList.getEntryCount() == 0) {
            fontRenderer.drawString(I18n.format("gui.rlcompass.noMatch"), listLeft + 12,
                    listTop + RESULTS_HEADER_HEIGHT + 12, CompassPalette.MUTED_TEXT);
        }

        searchBox.drawTextBox();
        if (searchBox.getText().isEmpty()) {
            // Placeholder: the box has no room for a label above it at minimum header height.
            fontRenderer.drawString(I18n.format("gui.rlcompass.filter"), searchBox.x + 1,
                    searchBox.y + 2, CompassPalette.MUTED_TEXT);
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    /** Sends the search request for a plain structure id and closes the picker. */
    void searchFor(String structureId) {
        searchFor(new StructureSearchEntry(structureId));
    }

    /** Sends the search request for a row (parent or variant) and closes the picker. */
    void searchFor(StructureSearchEntry entry) {
        if (entry == null) {
            return;
        }
        searchFor(entry.getParentId(), entry.getVariantId());
    }

    /**
     * Sends parent + variant explicitly. The Search button and the double-click path both come
     * here, so a variant row can never leak its composed id into the packet.
     */
    void searchFor(String parentId, String variantId) {
        if (parentId == null || parentId.isEmpty()) {
            return;
        }
        mc.getSoundHandler().playSound(
                PositionedSoundRecord.getMasterRecord(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        PacketHandler.channel().sendToServer(new PacketSearchRequest(parentId, variantId));
        mc.displayGuiScreen(null);
    }

    /** Expands or collapses a parent row's variant list. */
    void toggleExpansion(StructureSearchEntry entry) {
        if (entry == null || !entry.isExpandable()) {
            return;
        }
        String id = entry.getStructureId();
        if (expandedParents.remove(id)) {
            entry.setExpanded(false);
        } else {
            expandedParents.add(id);
            entry.setExpanded(true);
        }
        refreshEntries(true);
    }

    /**
     * Test hook for the client smoke test ({@code rlcompass.clienttest=gui}); returns one
     * PASS/FAIL line per check, without the {@code [CLIENTTEST]} prefix. Only called while the
     * property is set, so the normal path is untouched.
     */
    public List<String> runClientTestSelfCheck() {
        List<String> out = new ArrayList<String>();
        if (selectionList == null || searchBox == null) {
            out.add("FAIL gui.init expected=initialised actual=null");
            return out;
        }
        out.add(result("gui.entries", 4, allEntries.size()));
        checkFilter(out, "", 4);
        checkFilter(out, "@minecraft", 2);
        checkFilter(out, "village", 1);
        checkFilter(out, "zzz", 0);
        searchBox.setText("");
        lastFilter = "";
        refreshEntries(false);

        sortMode = SortMode.NAME;
        refreshEntries(false);
        out.add(result("gui.sort.name", true, isSortedByName()));
        sortMode = SortMode.SOURCE;
        refreshEntries(false);
        out.add(result("gui.sort.source", true, isSortedBySource()));
        sortMode = SortMode.NAME;
        refreshEntries(false);
        return out;
    }

    /**
     * Test hook: double-clicks the first row, which sends the search request and closes the screen.
     * Returns the id that was requested, or null when the list is empty.
     */
    public String simulateSearchFirstRow() {
        if (selectionList == null || selectionList.getEntryCount() == 0) {
            return null;
        }
        String id = selectionList.getEntryId(0);
        selectionList.simulateDoubleClick(0);
        return id;
    }

    private void checkFilter(List<String> out, String filter, int expected) {
        searchBox.setText(filter);
        lastFilter = filter;
        refreshEntries(false);
        out.add(result("gui.filter[" + filter + "]", expected, selectionList.getEntryCount()));
    }

    private boolean isSortedByName() {
        for (int i = 1; i < selectionList.getEntryCount(); i++) {
            String previous = selectionList.getEntryDisplayName(i - 1);
            String current = selectionList.getEntryDisplayName(i);
            if (previous.compareToIgnoreCase(current) > 0) {
                return false;
            }
        }
        return true;
    }

    private boolean isSortedBySource() {
        for (int i = 1; i < selectionList.getEntryCount(); i++) {
            StructureSearchEntry previous = selectionList.getEntry(i - 1);
            StructureSearchEntry current = selectionList.getEntry(i);
            if (previous == null || current == null) {
                return false;
            }
            int bySource = previous.getSource().compareToIgnoreCase(current.getSource());
            if (bySource > 0) {
                return false;
            }
            if (bySource == 0
                    && previous.getDisplayName().compareToIgnoreCase(current.getDisplayName()) > 0) {
                return false;
            }
        }
        return true;
    }

    private static String result(String name, int expected, int actual) {
        return expected == actual
                ? "PASS " + name
                : "FAIL " + name + " expected=" + expected + " actual=" + actual;
    }

    private static String result(String name, boolean expected, boolean actual) {
        return expected == actual
                ? "PASS " + name
                : "FAIL " + name + " expected=" + expected + " actual=" + actual;
    }

    /** Called by the list after the selection changes. */
    public void onSelectionChanged() {
        updateButtonState();
    }

    private void refreshEntries(boolean maintainSelection) {
        if (selectionList == null) {
            return;
        }
        String previous = maintainSelection && selectionList.hasSelection()
                ? selectionList.getSelected().getStructureId()
                : null;

        String filter = searchBox == null ? "" : searchBox.getText();
        List<StructureSearchEntry> visibleParents = new ArrayList<StructureSearchEntry>();
        for (StructureSearchEntry entry : allEntries) {
            if (entry.matches(filter)) {
                visibleParents.add(entry);
            }
        }
        Collections.sort(visibleParents, sortMode.comparator());

        // Children stay directly under their parent, so the flattened list must not be re-sorted
        // globally after inserting them.
        List<StructureSearchEntry> filtered = new ArrayList<StructureSearchEntry>();
        for (StructureSearchEntry parent : visibleParents) {
            filtered.add(parent);
            if (!parent.isExpandable() || !parent.isExpanded()) {
                continue;
            }
            List<StructureVariant> variants = new ArrayList<StructureVariant>(parent.getVariants());
            Collections.sort(variants, new Comparator<StructureVariant>() {
                @Override
                public int compare(StructureVariant a, StructureVariant b) {
                    return a.getDisplayName().compareToIgnoreCase(b.getDisplayName());
                }
            });
            for (StructureVariant variant : variants) {
                StructureSearchEntry child = new StructureSearchEntry(variant);
                if (child.matches(filter)) {
                    filtered.add(child);
                }
            }
        }
        selectionList.setEntries(filtered, previous);
        updateButtonState();
    }

    private void updateButtonState() {
        if (selectionList == null || searchButton == null) {
            return;
        }
        searchButton.enabled = selectionList.hasSelection();
        sortButton.enabled = allEntries.size() > 1;
    }

    private void layoutControls() {
        int sidePanelLeft = getSidePanelLeft();
        int listTop = getListTop();
        int buttonWidth = getSidePanelWidth() - 28;
        int x = sidePanelLeft + PANEL_GAP;

        int y = listTop + 36;
        searchButton.x = x;
        searchButton.y = y;
        searchButton.width = buttonWidth;
        searchButton.height = 20;

        y += 27;
        int utilityWidth = (buttonWidth - 8) / 2;
        sortButton.x = x;
        sortButton.y = y;
        sortButton.width = utilityWidth;
        sortButton.height = 18;
        cancelButton.x = x + utilityWidth + 8;
        cancelButton.y = y;
        cancelButton.width = utilityWidth;
        cancelButton.height = 18;

        int filterWidth = Math.max(150, Math.min(260, width / 4));
        searchBox.width = filterWidth - 16;
        searchBox.height = 12;
        searchBox.x = width - getRootMargin() - 12 - filterWidth + 8;
        // Visual box spans y-6 .. y+18, so +44 keeps the bottom inside the shortest header (74).
        searchBox.y = getHeaderTop() + 44;

        selectionList.updateBounds(getListLeft() + 10, getListRight() - 10,
                getListTop() + RESULTS_HEADER_HEIGHT + 10, getPanelBottom() - 10);
    }

    private int getRootMargin() {
        return Math.max(10, Math.min(18, width / 80));
    }

    private int getHeaderTop() {
        return getRootMargin();
    }

    private int getHeaderHeight() {
        return Math.max(74, Math.min(88, height / 6));
    }

    private int getSidePanelLeft() {
        return getRootMargin();
    }

    private int getSidePanelWidth() {
        return Math.max(190, Math.min(220, width / 4));
    }

    private int getListLeft() {
        return getSidePanelLeft() + getSidePanelWidth() + PANEL_GAP;
    }

    private int getListRight() {
        return width - getRootMargin();
    }

    private int getListTop() {
        return getHeaderTop() + getHeaderHeight() + PANEL_GAP;
    }

    private int getPanelBottom() {
        return height - getRootMargin();
    }

    /** Sort orders the Sort button cycles through. */
    private enum SortMode {

        NAME("gui.rlcompass.sortName"),
        SOURCE("gui.rlcompass.sortSource");

        private final String translationKey;

        SortMode(String translationKey) {
            this.translationKey = translationKey;
        }

        SortMode next() {
            SortMode[] modes = values();
            return modes[(ordinal() + 1) % modes.length];
        }

        String label() {
            return I18n.format("gui.rlcompass.sortBy", I18n.format(translationKey));
        }

        Comparator<StructureSearchEntry> comparator() {
            if (this == SOURCE) {
                return new Comparator<StructureSearchEntry>() {
                    @Override
                    public int compare(StructureSearchEntry a, StructureSearchEntry b) {
                        int bySource = a.getSource().compareToIgnoreCase(b.getSource());
                        return bySource != 0
                                ? bySource
                                : a.getDisplayName().compareToIgnoreCase(b.getDisplayName());
                    }
                };
            }
            return new Comparator<StructureSearchEntry>() {
                @Override
                public int compare(StructureSearchEntry a, StructureSearchEntry b) {
                    return a.getDisplayName().compareToIgnoreCase(b.getDisplayName());
                }
            };
        }
    }
}
