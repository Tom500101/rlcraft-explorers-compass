package com.tom500101.rlcompass.client.gui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiSlot;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.resources.I18n;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import com.tom500101.rlcompass.cache.SearchMemory;
import com.tom500101.rlcompass.network.PacketHandler;
import com.tom500101.rlcompass.network.PacketTeleportRequest;

/**
 * The cache warehouse: every answer the compass has ever given, with its state.
 *
 * <p>Layout follows the picker exactly — a 110px action column on the left, the list starting at
 * x=130, a 150x20 filter box at the head of the list column — so the two screens feel like one.
 * Each row is split into fixed columns (name, coordinates, state, delete) instead of right-aligning
 * a second string, which is what made the old row's text overlap when a name was long.
 *
 * <p>Behaviour:
 * <ul>
 *   <li><b>Create</b> — every search writes its answer here automatically.</li>
 *   <li><b>Read</b> — the filter box narrows by structure name or coordinates.</li>
 *   <li><b>Update</b> — clicking the state cell opens the three-item menu (new / found / missing).
 *       A row's position and id are never editable: they are facts about the world.</li>
 *   <li><b>Delete</b> — the x cell forgets the row, which makes that spot searchable again.</li>
 * </ul>
 *
 * <p>Single-player only: the warehouse lives in the save directory, which the integrated server
 * exposes but a remote server does not.
 */
@SideOnly(Side.CLIENT)
public class GuiSuggestionWarehouse extends GuiScreen {

    private static final int DONE_BUTTON_ID = 1;

    // Same geometry as GuiStructureSelect, on purpose.
    private static final int LEFT_X = 10;
    private static final int LEFT_WIDTH = 110;
    private static final int LIST_LEFT = 130;
    private static final int LIST_TOP = 40;
    private static final int EDGE = 10;
    private static final int BUTTON_HEIGHT = 20;
    private static final int SEARCH_BOX_WIDTH = 150;

    private static final int ROW_HEIGHT = 28;
    private static final int TELEPORT_WIDTH = 30;
    private static final int STATUS_WIDTH = 66;
    private static final int DELETE_WIDTH = 16;
    private static final int CELL_HEIGHT = 16;
    private static final int MENU_ROW_HEIGHT = 14;

    private static final SearchMemory.State[] STATES = {
            SearchMemory.State.NEW, SearchMemory.State.FOUND, SearchMemory.State.MISSING
    };
    /** Grey for unvisited, green for confirmed, red for "nothing there". */
    private static final int[] STATE_COLORS = {0xFF9AA5B1, 0xFF7FBF7F, 0xFFCF8A8A};

    private final GuiScreen parent;
    private final List<SearchMemory.Entry> entries = new ArrayList<SearchMemory.Entry>();

    private ListSlot list;
    private TransparentEditBox searchBox;
    private SearchMemory memory;
    private String lastFilter = "";
    /** False when the save has cheats off: the teleport cell is drawn disabled and ignores clicks. */
    private boolean teleportAllowed;

    private int menuRow = -1;
    private int menuLeft;
    private int menuTop;

    public GuiSuggestionWarehouse(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        buttonList.clear();
        memory = memory();

        buttonList.add(new TransparentButton(DONE_BUTTON_ID, LEFT_X, height - 30, LEFT_WIDTH,
                BUTTON_HEIGHT, I18n.format("gui.done")));

        searchBox = new TransparentEditBox(0, fontRenderer, LIST_LEFT, 10, SEARCH_BOX_WIDTH,
                BUTTON_HEIGHT);
        searchBox.setEnableBackgroundDrawing(false);
        searchBox.setCanLoseFocus(false);
        searchBox.setFocused(true);
        searchBox.setMaxStringLength(64);
        searchBox.setText(lastFilter);

        list = new ListSlot(mc, LIST_LEFT, width - EDGE, LIST_TOP, height - EDGE, ROW_HEIGHT);
        menuRow = -1;
        teleportAllowed = canUseCommands();
        reloadEntries();
    }

    /** The integrated server's save directory; {@code null} on a remote world. */
    private SearchMemory memory() {
        try {
            MinecraftServer server = mc.getIntegratedServer();
            if (server == null) {
                return null;
            }
            WorldServer world = server.getWorld(mc.player == null ? 0 : mc.player.dimension);
            return world == null ? null : SearchMemory.get(world);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * True when the integrated server would accept a command from this player — i.e. cheats are on,
     * or the player is an OP. The warehouse only exists in single player, so this is the same gate
     * the server applies to the teleport packet; checking it here just avoids a pointless round trip.
     */
    private boolean canUseCommands() {
        try {
            MinecraftServer server = mc.getIntegratedServer();
            return server != null && mc.player != null
                    && server.getPlayerList().canSendCommands(mc.player.getGameProfile());
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button.id == DONE_BUTTON_ID) {
            mc.displayGuiScreen(parent);
        }
    }

    @Override
    public void updateScreen() {
        searchBox.updateCursorCounter();
        if (!searchBox.getText().equals(lastFilter)) {
            lastFilter = searchBox.getText();
            reloadEntries();
        }
    }

    /** Rebuilds the visible rows from the filter box. */
    private void reloadEntries() {
        entries.clear();
        if (memory == null) {
            return;
        }
        String filter = searchBox == null ? "" : searchBox.getText().trim().toLowerCase(Locale.ROOT);
        for (SearchMemory.Entry entry : memory.sortedEntries()) {
            if (filter.isEmpty() || matches(entry, filter)) {
                entries.add(entry);
            }
        }
    }

    private static boolean matches(SearchMemory.Entry entry, String filter) {
        if (entry.structureId.toLowerCase(Locale.ROOT).contains(filter)) {
            return true;
        }
        String coords = entry.pos.getX() + " " + entry.pos.getZ();
        return coords.contains(filter) || ("(" + entry.pos.getX() + ", " + entry.pos.getZ() + ")")
                .contains(filter);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();

        if (entries.isEmpty()) {
            String message = memory == null || memory.isEmpty()
                    ? I18n.format("gui.rlcompass.warehouse.empty")
                    : I18n.format("gui.rlcompass.noMatch");
            drawCenteredString(fontRenderer, message,
                    LIST_LEFT + (width - EDGE - LIST_LEFT) / 2, LIST_TOP + 30, GuiTheme.TEXT_INFO);
        } else {
            list.drawScreen(mouseX, mouseY, partialTicks);
        }

        drawCenteredString(fontRenderer, I18n.format("gui.rlcompass.warehouse.title"),
                LEFT_X + LEFT_WIDTH / 2, 15, GuiTheme.TEXT);

        drawMenu(mouseX, mouseY);

        searchBox.drawTextBox();
        if (searchBox.getText().isEmpty()) {
            fontRenderer.drawString(I18n.format("gui.rlcompass.warehouse.filter"),
                    searchBox.x + 4, searchBox.y + 6, GuiTheme.TEXT_HINT);
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (menuRow >= 0) {
            if (mouseButton == 0) {
                int index = menuIndexAt(mouseX, mouseY);
                if (index >= 0) {
                    setState(menuRow, STATES[index]);
                }
            }
            menuRow = -1;
            return;
        }
        if (mouseButton == 0 && list.handleClick(mouseX, mouseY)) {
            return;
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
        searchBox.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (searchBox.textboxKeyTyped(typedChar, keyCode)) {
            lastFilter = searchBox.getText();
            reloadEntries();
            return;
        }
        if (keyCode == 1) {
            mc.displayGuiScreen(parent);
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void handleMouseInput() throws IOException {
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            menuRow = -1;
            list.scroll(wheel);
        }
        // The base class dispatches mouse-button events to mouseClicked (buttons + list routing).
        super.handleMouseInput();
    }

    // ------------------------------------------------------------------ state menu

    private void openMenu(int row) {
        if (row < 0 || row >= entries.size()) {
            return;
        }
        menuRow = row;
        menuLeft = list.statusLeft();
        int rowTop = list.rowTop(row);
        int menuHeight = STATES.length * MENU_ROW_HEIGHT;
        menuTop = rowTop + CELL_HEIGHT + 4;
        if (menuTop + menuHeight > height - 4) {
            menuTop = Math.max(4, rowTop - menuHeight);
        }
    }

    private int menuIndexAt(int mouseX, int mouseY) {
        if (menuRow < 0) {
            return -1;
        }
        if (mouseX < menuLeft || mouseX >= menuLeft + STATUS_WIDTH) {
            return -1;
        }
        int offset = mouseY - menuTop;
        if (offset < 0 || offset >= STATES.length * MENU_ROW_HEIGHT) {
            return -1;
        }
        return offset / MENU_ROW_HEIGHT;
    }

    private void drawMenu(int mouseX, int mouseY) {
        if (menuRow < 0 || menuRow >= entries.size()) {
            return;
        }
        int menuHeight = STATES.length * MENU_ROW_HEIGHT;
        Gui.drawRect(menuLeft, menuTop, menuLeft + STATUS_WIDTH, menuTop + menuHeight,
                GuiTheme.MENU_BACKGROUND);
        GuiTheme.outline(menuLeft, menuTop, menuLeft + STATUS_WIDTH, menuTop + menuHeight,
                GuiTheme.MENU_BORDER);

        for (int i = 0; i < STATES.length; i++) {
            int itemTop = menuTop + i * MENU_ROW_HEIGHT;
            if (mouseX >= menuLeft && mouseX < menuLeft + STATUS_WIDTH
                    && mouseY >= itemTop && mouseY < itemTop + MENU_ROW_HEIGHT) {
                Gui.drawRect(menuLeft + 1, itemTop, menuLeft + STATUS_WIDTH - 1,
                        itemTop + MENU_ROW_HEIGHT, GuiTheme.MENU_HOVER);
            }
            drawCenteredString(fontRenderer, stateLabel(STATES[i]),
                    menuLeft + STATUS_WIDTH / 2, itemTop + 3, STATE_COLORS[i]);
        }
    }

    private void setState(int row, SearchMemory.State state) {
        if (memory == null || row < 0 || row >= entries.size()) {
            return;
        }
        SearchMemory.Entry entry = entries.get(row);
        memory.setState(entry.structureId, entry.pos, state);
        reloadEntries();
    }

    private void delete(int row) {
        if (memory == null || row < 0 || row >= entries.size()) {
            return;
        }
        SearchMemory.Entry entry = entries.get(row);
        memory.remove(entry.structureId, entry.pos);
        reloadEntries();
    }

    /**
     * Asks the server to put the player on the surface above this entry.
     *
     * <p>The screen closes first: the answer arrives as a chat message (or a refusal), and leaving
     * the warehouse open would hide it. The server recomputes Y — the stored position is the
     * structure's origin, which may be deep underground.
     */
    private void teleport(int row) {
        if (row < 0 || row >= entries.size() || mc.player == null) {
            return;
        }
        SearchMemory.Entry entry = entries.get(row);
        PacketHandler.channel().sendToServer(new PacketTeleportRequest(entry.structureId,
                entry.pos.getX(), entry.pos.getY(), entry.pos.getZ(), mc.player.dimension));
        mc.displayGuiScreen(null);
    }

    private static String stateLabel(SearchMemory.State state) {
        switch (state) {
            case FOUND:
                return I18n.format("gui.rlcompass.warehouse.state.found");
            case MISSING:
                return I18n.format("gui.rlcompass.warehouse.state.missing");
            default:
                return I18n.format("gui.rlcompass.warehouse.state.new");
        }
    }

    private static int stateIndex(SearchMemory.State state) {
        switch (state) {
            case FOUND:
                return 1;
            case MISSING:
                return 2;
            default:
                return 0;
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    /** Flat list: one surface, selection stacks it again, 6px scrollbar, no row chrome. */
    private final class ListSlot extends GuiSlot {

        private int selected = -1;

        ListSlot(Minecraft mc, int left, int right, int top, int bottom, int slotHeight) {
            super(mc, Math.max(1, right - left), Math.max(1, bottom - top), top, bottom, slotHeight);
            this.left = left;
            this.right = right;
            this.top = top;
            this.bottom = bottom;
            this.width = right - left;
            this.height = bottom - top;
            bindAmountScrolled();
        }

        int rowTop(int index) {
            return this.top - (int) this.amountScrolled + index * this.slotHeight;
        }

        int deleteLeft() {
            return this.right - 2 - DELETE_WIDTH;
        }

        int statusLeft() {
            return deleteLeft() - 4 - STATUS_WIDTH;
        }

        int teleportLeft() {
            return statusLeft() - 4 - TELEPORT_WIDTH;
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
        protected void drawBackground() {
        }

        @Override
        public void drawScreen(int mouseXIn, int mouseYIn, float partialTicks) {
            Gui.drawRect(this.left, this.top, this.right, this.bottom, GuiTheme.LIST_BACKGROUND);

            ScaledResolution scaled = new ScaledResolution(this.mc);
            int scale = scaled.getScaleFactor();
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(this.left * scale,
                    this.mc.displayHeight - this.bottom * scale,
                    (this.right - this.left) * scale,
                    (this.bottom - this.top) * scale);
            int contentTop = this.top - (int) this.amountScrolled;
            for (int i = 0; i < entries.size(); i++) {
                int rowTop = contentTop + i * this.slotHeight;
                if (rowTop + this.slotHeight >= this.top && rowTop <= this.bottom) {
                    drawSlot(i, this.left, rowTop, this.slotHeight, mouseXIn, mouseYIn, partialTicks);
                }
            }
            GL11.glDisable(GL11.GL_SCISSOR_TEST);

            drawScrollbar();
        }

        @Override
        protected void drawSlot(int index, int xPos, int yPos, int heightIn, int mouseXIn,
                                int mouseYIn, float partialTicks) {
            SearchMemory.Entry entry = entries.get(index);
            int rowRight = this.right - 2;
            if (index == selected) {
                Gui.drawRect(this.left - 4, yPos - 4, rowRight + 4, yPos + heightIn + 4,
                        GuiTheme.SELECTION_LAYER);
            }

            int deleteLeft = deleteLeft();
            int statusLeft = statusLeft();
            int teleportLeft = teleportLeft();
            int textLeft = this.left + 6;
            int nameLimit = teleportLeft - 6 - textLeft;

            this.mc.fontRenderer.drawStringWithShadow(
                    trim(entry.structureId, nameLimit), textLeft, yPos + 4, GuiTheme.TEXT);
            this.mc.fontRenderer.drawStringWithShadow(
                    trim("(" + entry.pos.getX() + ", " + entry.pos.getZ() + ")", nameLimit),
                    textLeft, yPos + 15, GuiTheme.TEXT_INFO);

            // Teleport cell: server-side Y calculation, so an underground origin still lands on grass.
            // Dimmed when the save has cheats off — the server would refuse anyway.
            Gui.drawRect(teleportLeft, yPos + 6, teleportLeft + TELEPORT_WIDTH, yPos + 6 + CELL_HEIGHT,
                    teleportAllowed ? GuiTheme.CONTROL : GuiTheme.CONTROL_DISABLED);
            drawCenteredString(this.mc.fontRenderer,
                    I18n.format("gui.rlcompass.warehouse.teleport"),
                    teleportLeft + TELEPORT_WIDTH / 2, yPos + 10,
                    teleportAllowed ? GuiTheme.TEXT : GuiTheme.TEXT_DISABLED);

            // State cell: flat plate with a colour bar, so the three states read at a glance.
            Gui.drawRect(statusLeft, yPos + 6, statusLeft + STATUS_WIDTH, yPos + 6 + CELL_HEIGHT,
                    GuiTheme.CONTROL);
            Gui.drawRect(statusLeft, yPos + 6, statusLeft + 3, yPos + 6 + CELL_HEIGHT,
                    STATE_COLORS[stateIndex(entry.state)]);
            drawCenteredString(this.mc.fontRenderer, stateLabel(entry.state),
                    statusLeft + STATUS_WIDTH / 2, yPos + 10, GuiTheme.TEXT);

            // Delete cell: the only way a spot becomes searchable again.
            Gui.drawRect(deleteLeft, yPos + 6, deleteLeft + DELETE_WIDTH, yPos + 6 + CELL_HEIGHT,
                    GuiTheme.CONTROL);
            drawCenteredString(this.mc.fontRenderer, "x",
                    deleteLeft + DELETE_WIDTH / 2, yPos + 10, GuiTheme.TEXT);
        }

        @Override
        protected void elementClicked(int index, boolean isDoubleClick, int mouseX, int mouseY) {
            if (index < 0 || index >= entries.size()) {
                return;
            }
            if (mouseX >= deleteLeft()) {
                delete(index);
                return;
            }
            if (mouseX >= teleportLeft() && mouseX < teleportLeft() + TELEPORT_WIDTH) {
                if (teleportAllowed) {
                    teleport(index);
                }
                return;
            }
            if (mouseX >= statusLeft() && mouseX < statusLeft() + STATUS_WIDTH) {
                openMenu(index);
                return;
            }
            selected = index;
        }

        @Override
        protected boolean isSelected(int index) {
            return index == selected;
        }

        /** Handles a left click inside the viewport; returns false when it landed outside. */
        boolean handleClick(int mouseX, int mouseY) {
            if (mouseX < this.left || mouseX >= this.right
                    || mouseY < this.top || mouseY >= this.bottom) {
                return false;
            }
            int index = (mouseY - this.top + (int) this.amountScrolled) / this.slotHeight;
            if (index < 0 || index >= entries.size()) {
                return false;
            }
            elementClicked(index, false, mouseX, mouseY);
            return true;
        }

        void scroll(int wheel) {
            int direction = wheel > 0 ? -1 : 1;
            this.amountScrolled += direction * (this.slotHeight / 2.0F);
            bindAmountScrolled();
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

            int maxThumbHeight = Math.max(24, viewport - 16);
            int thumbHeight = Math.min(maxThumbHeight, Math.max(32, viewport * viewport / totalHeight));
            int travel = Math.max(0, viewport - 16 - thumbHeight);
            int track = Math.max(1, totalHeight - viewport);
            int thumbTop = this.top + 8 + (int) (this.amountScrolled / (float) track * travel);
            Gui.drawRect(barLeft, thumbTop, barRight, thumbTop + thumbHeight,
                    GuiTheme.SCROLL_THUMB);
        }

        private String trim(String text, int maxWidth) {
            if (maxWidth <= 0) {
                return "";
            }
            return this.mc.fontRenderer.trimStringToWidth(text, maxWidth);
        }
    }
}
