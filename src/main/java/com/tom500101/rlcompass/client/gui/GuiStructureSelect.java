package com.tom500101.rlcompass.client.gui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.init.SoundEvents;

import com.tom500101.rlcompass.client.ClientSearchStatus;
import com.tom500101.rlcompass.config.CompassConfig;
import com.tom500101.rlcompass.locate.StructureLocatorRegistry;
import com.tom500101.rlcompass.locate.StructureVariant;
import com.tom500101.rlcompass.network.PacketHandler;
import com.tom500101.rlcompass.network.PacketSearchRequest;
import com.tom500101.rlcompass.util.StructureNameUtil;

/**
 * Structure picker shown when the compass is right-clicked.
 *
 * <p>Laid out on one translucent sheet: a header with the title and the filter box, an action column
 * on the left, and the scrollable catalog on the right. There are no borders, separators or panels
 * inside the sheet — the reference screen draws text and flat fills only, and every widget adds its
 * own translucent layer. All the measurements are derived from {@code width}/{@code height} so the
 * screen reflows on any GUI scale instead of assuming one fixed size.
 *
 * <p>The id list comes from the server (see {@code PacketOpenCompassGui}) because it depends on
 * which mods are installed and which ids the config blacklist removed. Picking an entry sends a
 * search request and closes the screen; the answer arrives later as a {@code PacketStructureResult}.
 */
public class GuiStructureSelect extends GuiScreen {

    private static final int SEARCH_BUTTON_ID = 0;
    private static final int WAREHOUSE_BUTTON_ID = 3;
    private static final int SORT_BUTTON_ID = 1;
    private static final int CANCEL_BUTTON_ID = 2;

    // Reference geometry: a 110px action column on the left, the list column starting at x=130,
    // 10px screen edges, 20px controls. Fixed pixels on purpose — the reference does not reflow.
    private static final int LEFT_X = 10;
    private static final int LEFT_WIDTH = 110;
    private static final int LIST_LEFT = 130;
    private static final int LIST_TOP = 40;
    private static final int EDGE = 10;
    private static final int BUTTON_HEIGHT = 20;
    private static final int SEARCH_BOX_WIDTH = 150;
    private static final int SLOT_HEIGHT = 50;

    private final List<StructureSearchEntry> allEntries = new ArrayList<StructureSearchEntry>();
    private final List<String> baseIds = new ArrayList<String>();
    private final Set<String> expandedParents = new HashSet<String>();

    private TransparentButton searchButton;
    private TransparentButton warehouseButton;
    private TransparentButton sortButton;
    private TransparentButton cancelButton;
    private TransparentEditBox searchBox;
    private StructureSearchList selectionList;
    private RadiusSlider radiusSlider;

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

        searchButton = new TransparentButton(SEARCH_BUTTON_ID, LEFT_X, 40, LEFT_WIDTH,
                BUTTON_HEIGHT, I18n.format("gui.rlcompass.search"));
        sortButton = new TransparentButton(SORT_BUTTON_ID, LEFT_X, 65, LEFT_WIDTH,
                BUTTON_HEIGHT, sortMode.label());
        cancelButton = new TransparentButton(CANCEL_BUTTON_ID, LEFT_X, height - 30, LEFT_WIDTH,
                BUTTON_HEIGHT, I18n.format("gui.cancel"));
        warehouseButton = new TransparentButton(WAREHOUSE_BUTTON_ID, LEFT_X, 90, LEFT_WIDTH,
                BUTTON_HEIGHT, I18n.format("gui.rlcompass.warehouse"));
        buttonList.add(searchButton);
        buttonList.add(warehouseButton);
        buttonList.add(sortButton);
        buttonList.add(cancelButton);

        // Radius is the one setting a player actually needs to touch during a search: the ultra-rare
        // structures cannot be found at the default radius, so it lives on the action column instead
        // of behind a config file.
        radiusSlider = new RadiusSlider(LEFT_X, 115, LEFT_WIDTH, BUTTON_HEIGHT, 100, 100000, 100,
                CompassConfig.searchRadius);

        searchBox = new TransparentEditBox(0, fontRenderer, LIST_LEFT, 10, SEARCH_BOX_WIDTH,
                BUTTON_HEIGHT);
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
        if (mouseButton == 0 && radiusSlider != null && radiusSlider.mousePressed(mouseX, mouseY)) {
            return;
        }
        if (mouseButton == 0 && selectionList.handleClick(mouseX, mouseY)) {
            return;
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
        searchBox.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton,
                                  long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        if (radiusSlider != null) {
            radiusSlider.mouseDragged(mouseX);
        }
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);
        // Persist only when the drag ends: ConfigManager.sync rewrites the whole file, so doing it
        // per pixel would hammer the disk.
        if (radiusSlider != null && radiusSlider.mouseReleased()) {
            persistRadius();
        }
    }

    /** Writes the slider value into the config field and flushes it to {@code config/rlcompass.cfg}. */
    private void persistRadius() {
        CompassConfig.searchRadius = radiusSlider.getValue();
        try {
            net.minecraftforge.common.config.ConfigManager.sync(
                    com.tom500101.rlcompass.RLCompassMod.MODID,
                    net.minecraftforge.common.config.Config.Type.INSTANCE);
        } catch (Throwable ignored) {
            // an unwritable config must not crash the screen; the value still applies this session
        }
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
        } else if (button.id == WAREHOUSE_BUTTON_ID) {
            mc.displayGuiScreen(new GuiSuggestionWarehouse(this));
        } else if (button.id == CANCEL_BUTTON_ID) {
            mc.displayGuiScreen(null);
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        // Reference geometry: title centred over the action column, search box at the head of the
        // list column, list filling the rest of the screen. Nothing else is drawn on the sheet.
        drawDefaultBackground();

        selectionList.drawScreen(mouseX, mouseY, partialTicks);

        drawCenteredString(fontRenderer, I18n.format("gui.rlcompass.title"),
                LEFT_X + LEFT_WIDTH / 2, 15, GuiTheme.TEXT);

        if (allEntries.isEmpty() || selectionList.getEntryCount() == 0) {
            String message = allEntries.isEmpty()
                    ? I18n.format("gui.rlcompass.empty")
                    : I18n.format("gui.rlcompass.noMatch");
            drawCenteredString(fontRenderer, message,
                    LIST_LEFT + (width - EDGE - LIST_LEFT) / 2, LIST_TOP + 30, GuiTheme.TEXT_INFO);
        }

        searchBox.drawTextBox();
        if (searchBox.getText().isEmpty()) {
            // Placeholder sits inside the field, mirroring the reference's hint text.
            fontRenderer.drawString(I18n.format("gui.rlcompass.filter"), searchBox.x + 4,
                    searchBox.y + 6, GuiTheme.TEXT_HINT);
        }

        radiusSlider.draw(mc, mouseX, mouseY);
        int hintY = 140;
        hintY = drawWrapped(I18n.format("gui.rlcompass.radiusHint"), hintY, GuiTheme.TEXT_INFO);
        StructureSearchEntry selected = selectionList.getSelected();
        if (selected != null && StructureNameUtil.isUltraRare(selected.getParentId())) {
            // Ultra-rare structures have a per-chunk chance in the 1e-5 range: the default radius
            // simply cannot contain one, and the player should know before waiting.
            drawWrapped(I18n.format("gui.rlcompass.radiusUltraRare"), hintY + 2, GuiTheme.TEXT);
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    /** Draws text wrapped to the action column; returns the y below the last line. */
    private int drawWrapped(String text, int y, int colour) {
        for (String line : fontRenderer.listFormattedStringToWidth(text, LEFT_WIDTH)) {
            fontRenderer.drawString(line, LEFT_X, y, colour);
            y += 10;
        }
        return y;
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
        if (!entry.isConcrete()) {
            // Folder rows only expand; searching them would ask for "the nearest <family>",
            // which the locator cannot answer meaningfully.
            toggleExpansion(entry);
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
        // A themed search can take a second or more on the server; the HUD shows the wait.
        ClientSearchStatus.begin();
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
        boolean typing = filter != null && !filter.trim().isEmpty();
        List<StructureSearchEntry> visibleParents = new ArrayList<StructureSearchEntry>();
        for (StructureSearchEntry entry : allEntries) {
            // A needle that only matches a child has to keep its parent visible, otherwise typing
            // "Ice Dungeon" would filter the whole Roguelike row away and show nothing.
            if (entry.matches(filter) || (typing && entry.hasMatchingVariant(filter))) {
                if (typing && entry.hasMatchingVariant(filter)) {
                    // Show the hits without requiring the user to expand first.
                    entry.setExpanded(true);
                }
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
        StructureSearchEntry selected = selectionList.getSelected();
        searchButton.enabled = selected != null && selected.isConcrete();
        sortButton.enabled = allEntries.size() > 1;
    }

    private void layoutControls() {
        searchButton.x = LEFT_X;
        searchButton.y = 40;
        searchButton.width = LEFT_WIDTH;
        searchButton.height = BUTTON_HEIGHT;

        sortButton.x = LEFT_X;
        sortButton.y = 65;
        sortButton.width = LEFT_WIDTH;
        sortButton.height = BUTTON_HEIGHT;

        warehouseButton.x = LEFT_X;
        warehouseButton.y = 90;
        warehouseButton.width = LEFT_WIDTH;
        warehouseButton.height = BUTTON_HEIGHT;

        cancelButton.x = LEFT_X;
        cancelButton.y = height - 30;
        cancelButton.width = LEFT_WIDTH;
        cancelButton.height = BUTTON_HEIGHT;

        searchBox.x = LIST_LEFT;
        searchBox.y = 10;
        searchBox.width = SEARCH_BOX_WIDTH;
        searchBox.height = BUTTON_HEIGHT;

        selectionList.updateBounds(LIST_LEFT, width - EDGE, LIST_TOP, height - EDGE);
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
