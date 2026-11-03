package com.tom500101.rlcompass.client.gui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

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

/**
 * The suggestion warehouse: everything the compass has ever offered, with its state.
 *
 * <p>Suggestions are cheap and unreliable by nature (they answer "this area can host it, you have
 * not checked"), so the player decides what a point turned out to be. Clicking the state cell opens
 * a three-item menu — NEW / FOUND / MISSING — instead of cycling through values, because a mis-click
 * on a cycle silently skips a state. A settled row is never offered again, which is what makes the
 * next search return somewhere new.
 *
 * <p>Same flat list as the picker: one 0x7F surface over the dimmed world, selection stacks the
 * surface again, 6px scrollbar, no row borders or separators.
 *
 * <p>Single-player only: the warehouse lives in the save directory, which the integrated server
 * exposes but a remote server does not. On a remote world the list is empty.
 */
@SideOnly(Side.CLIENT)
public class GuiSuggestionWarehouse extends GuiScreen {

    private static final int AGAIN_BUTTON_ID = 0;
    private static final int DONE_BUTTON_ID = 1;

    private static final int LIST_LEFT = 10;
    private static final int LIST_TOP = 40;
    private static final int EDGE = 10;
    private static final int ROW_HEIGHT = 20;
    private static final int STATE_WIDTH = 76;
    private static final int MENU_ROW_HEIGHT = 13;

    private static final SearchMemory.State[] STATES = {
            SearchMemory.State.NEW, SearchMemory.State.FOUND, SearchMemory.State.MISSING
    };
    /** Grey for unvisited, green for confirmed, red for "nothing there". */
    private static final int[] STATE_COLORS = {0xFF9AA5B1, 0xFF7FBF7F, 0xFFCF8A8A};

    private final GuiScreen parent;
    private final List<SearchMemory.Entry> entries = new ArrayList<SearchMemory.Entry>();

    private ListSlot list;
    private TransparentButton againButton;
    private SearchMemory memory;

    private int selected = -1;
    private int menuRow = -1;
    private int menuLeft;
    private int menuTop;

    public GuiSuggestionWarehouse(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        buttonList.clear();
        entries.clear();
        memory = memory();
        if (memory != null) {
            entries.addAll(memory.sortedEntries());
        }
        selected = -1;
        menuRow = -1;

        list = new ListSlot(mc, LIST_LEFT, width - EDGE, LIST_TOP, height - 44, ROW_HEIGHT);

        againButton = new TransparentButton(AGAIN_BUTTON_ID, width / 2 - 154, height - 32, 150, 20,
                I18n.format("gui.rlcompass.warehouse.searchAgain"));
        againButton.enabled = false;
        buttonList.add(againButton);
        buttonList.add(new TransparentButton(DONE_BUTTON_ID, width / 2 + 4, height - 32, 150, 20,
                I18n.format("gui.done")));
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

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button.id == DONE_BUTTON_ID) {
            mc.displayGuiScreen(parent);
        } else if (button.id == AGAIN_BUTTON_ID) {
            if (selected >= 0 && selected < entries.size()) {
                SearchMemory.Entry entry = entries.get(selected);
                // Settle the old point first: the whole point of the warehouse is to move on.
                if (memory != null) {
                    memory.setState(entry.structureId, entry.pos, SearchMemory.State.MISSING);
                }
                searchAgain(entry.structureId);
            }
        }
    }

    private void searchAgain(String structureId) {
        String parentId = "reccomplex:structure";
        String variantId = parentId + "/" + structureId;
        com.tom500101.rlcompass.client.ClientSearchStatus.begin();
        com.tom500101.rlcompass.network.PacketHandler.channel().sendToServer(
                new com.tom500101.rlcompass.network.PacketSearchRequest(parentId, variantId));
        mc.displayGuiScreen(null);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();

        if (entries.isEmpty()) {
            drawCenteredString(fontRenderer, I18n.format("gui.rlcompass.warehouse.empty"),
                    width / 2, LIST_TOP + 30, GuiTheme.TEXT_INFO);
        } else {
            list.drawScreen(mouseX, mouseY, partialTicks);
        }
        drawCenteredString(fontRenderer, I18n.format("gui.rlcompass.warehouse.title"),
                width / 2, 15, GuiTheme.TEXT);

        drawMenu(mouseX, mouseY);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    // ------------------------------------------------------------------ state menu

    /** Opens the three-item state menu under the clicked row (above it near the bottom edge). */
    private void openMenu(int row) {
        if (row < 0 || row >= entries.size()) {
            return;
        }
        menuRow = row;
        menuLeft = width - EDGE - STATE_WIDTH;
        int rowTop = list.rowTop(row);
        int menuHeight = STATES.length * MENU_ROW_HEIGHT;
        menuTop = rowTop + ROW_HEIGHT;
        if (menuTop + menuHeight > height - 4) {
            menuTop = Math.max(4, rowTop - menuHeight);
        }
    }

    private int menuIndexAt(int mouseX, int mouseY) {
        if (menuRow < 0) {
            return -1;
        }
        if (mouseX < menuLeft || mouseX >= menuLeft + STATE_WIDTH) {
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
        Gui.drawRect(menuLeft, menuTop, menuLeft + STATE_WIDTH, menuTop + menuHeight,
                GuiTheme.MENU_BACKGROUND);
        GuiTheme.outline(menuLeft, menuTop, menuLeft + STATE_WIDTH, menuTop + menuHeight,
                GuiTheme.MENU_BORDER);

        for (int i = 0; i < STATES.length; i++) {
            int itemTop = menuTop + i * MENU_ROW_HEIGHT;
            if (mouseX >= menuLeft && mouseX < menuLeft + STATE_WIDTH
                    && mouseY >= itemTop && mouseY < itemTop + MENU_ROW_HEIGHT) {
                Gui.drawRect(menuLeft + 1, itemTop, menuLeft + STATE_WIDTH - 1,
                        itemTop + MENU_ROW_HEIGHT, GuiTheme.MENU_HOVER);
            }
            drawCenteredString(fontRenderer, stateLabel(STATES[i]),
                    menuLeft + STATE_WIDTH / 2, itemTop + 3, STATE_COLORS[i]);
        }
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
            // A click anywhere else just dismisses the menu; it never falls through to the row.
            menuRow = -1;
            return;
        }
        // The list does not process clicks itself (its handleMouseInput is wheel-only), so the
        // screen routes them: rows first, then the bottom buttons.
        if (mouseButton == 0 && list.clickAt(mouseX, mouseY)) {
            return;
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    private void setState(int row, SearchMemory.State state) {
        if (memory == null || row < 0 || row >= entries.size()) {
            return;
        }
        SearchMemory.Entry entry = entries.get(row);
        memory.setState(entry.structureId, entry.pos, state);
        initGui();
    }

    @Override
    public void handleMouseInput() throws IOException {
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0) {
            return;
        }
        // Scrolling dismisses the menu: it is anchored to a row that is about to move.
        menuRow = -1;
        list.scroll(wheel);
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

    /** Flat list: one surface, selection stacks it again, 6px scrollbar, no row chrome. */
    private final class ListSlot extends GuiSlot {

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

        /** Screen y of a row, including the current scroll offset (GuiSlot has no such accessor). */
        int rowTop(int index) {
            return this.top - (int) this.amountScrolled + index * this.slotHeight;
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

            int cellRight = this.right - EDGE;
            int cellLeft = cellRight - STATE_WIDTH;
            String coordinates = "(" + entry.pos.getX() + ", " + entry.pos.getZ() + ")";
            String id = trim(entry.structureId, cellLeft - 8 - (this.left + 8));
            this.mc.fontRenderer.drawStringWithShadow(id, this.left + 8, yPos + 6, GuiTheme.TEXT);
            this.mc.fontRenderer.drawStringWithShadow(coordinates,
                    Math.max(this.left + 8,
                            cellLeft - 8 - this.mc.fontRenderer.getStringWidth(coordinates)),
                    yPos + 6, GuiTheme.TEXT_INFO);

            // State cell: a flat plate with a colour bar, so the three states read at a glance.
            Gui.drawRect(cellLeft, yPos + 2, cellRight, yPos + heightIn - 2, GuiTheme.CONTROL);
            Gui.drawRect(cellLeft, yPos + 2, cellLeft + 3, yPos + heightIn - 2,
                    STATE_COLORS[stateIndex(entry.state)]);
            drawCenteredString(this.mc.fontRenderer, stateLabel(entry.state),
                    (cellLeft + cellRight) / 2, yPos + 6, GuiTheme.TEXT);
        }

        @Override
        protected void elementClicked(int index, boolean isDoubleClick, int mouseX, int mouseY) {
            int cellLeft = this.right - EDGE - STATE_WIDTH;
            if (mouseX >= cellLeft) {
                openMenu(index);
                return;
            }
            selected = index;
            againButton.enabled = true;
        }

        @Override
        protected boolean isSelected(int index) {
            return index == selected;
        }

        /** Handles a left click inside the viewport; returns false when it landed outside. */
        boolean clickAt(int mouseX, int mouseY) {
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
}
