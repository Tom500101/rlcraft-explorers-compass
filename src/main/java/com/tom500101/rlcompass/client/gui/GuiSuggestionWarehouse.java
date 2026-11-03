package com.tom500101.rlcompass.client.gui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiSlot;
import net.minecraft.client.resources.I18n;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import com.tom500101.rlcompass.cache.SearchMemory;

/**
 * The suggestion warehouse: everything the compass has ever offered, with its state.
 *
 * <p>Suggestions are cheap and unreliable by nature (they answer "this area can host it, you have
 * not checked"), so the player is the one who decides what a point turned out to be. Each row cycles
 * NEW → FOUND → MISSING, and a settled row is never offered again — which is what makes the next
 * search return somewhere new.
 *
 * <p>Single-player only: the warehouse lives in the save directory, which the integrated server
 * exposes but a remote server does not. On a remote world the list is empty.
 */
@SideOnly(Side.CLIENT)
public class GuiSuggestionWarehouse extends GuiScreen {

    private static final int AGAIN_BUTTON_ID = 0;
    private static final int DONE_BUTTON_ID = 1;
    private static final int STATE_BUTTON_WIDTH = 74;
    private static final int SLOT_HEIGHT = 18;

    private final GuiScreen parent;
    private final List<SearchMemory.Entry> entries = new ArrayList<SearchMemory.Entry>();

    private ListSlot list;
    private GuiButton againButton;
    private SearchMemory memory;

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
        list = new ListSlot(mc, width, height, 40, height - 40, SLOT_HEIGHT);
        againButton = new GuiButton(AGAIN_BUTTON_ID, width / 2 - 154, height - 32, 150, 20,
                I18n.format("gui.rlcompass.warehouse.searchAgain"));
        againButton.enabled = false;
        buttonList.add(againButton);
        buttonList.add(new GuiButton(DONE_BUTTON_ID, width / 2 + 4, height - 32, 150, 20,
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
            SearchMemory.Entry selected = list.getSelected();
            if (selected != null) {
                // Settle the old point first: the whole point of the warehouse is to move on.
                if (memory != null) {
                    memory.setState(selected.structureId, selected.pos,
                            SearchMemory.State.MISSING);
                }
                searchAgain(selected.structureId);
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
        drawCenteredString(fontRenderer, I18n.format("gui.rlcompass.warehouse.title"),
                width / 2, 16, 0xFFFFFF);
        if (entries.isEmpty()) {
            drawCenteredString(fontRenderer, I18n.format("gui.rlcompass.warehouse.empty"),
                    width / 2, height / 2, 0xA0A0A0);
        } else {
            list.drawScreen(mouseX, mouseY, partialTicks);
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        if (!entries.isEmpty()) {
            list.handleMouseInput();
        }
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

    /** Only visible rows are drawn, so a few hundred entries cost nothing. */
    private final class ListSlot extends GuiSlot {

        ListSlot(Minecraft mc, int width, int height, int top, int bottom, int slotHeight) {
            super(mc, width, height, top, bottom, slotHeight);
        }

        private SearchMemory.Entry selected;

        SearchMemory.Entry getSelected() {
            return selected;
        }

        @Override
        protected int getSize() {
            return entries.size();
        }

        @Override
        protected void elementClicked(int slotIndex, boolean isDoubleClick, int mouseX, int mouseY) {
            SearchMemory.Entry entry = entries.get(slotIndex);
            if (isStateButton(mouseX)) {
                cycle(entry);
                return;
            }
            selected = entry;
            againButton.enabled = true;
        }

        @Override
        protected boolean isSelected(int slotIndex) {
            return entries.get(slotIndex) == selected;
        }

        @Override
        protected void drawBackground() {
        }

        @Override
        protected void drawSlot(int slotIndex, int xPos, int yPos, int heightIn, int mouseXIn,
                                int mouseYIn, float partialTicks) {
            SearchMemory.Entry entry = entries.get(slotIndex);
            String label = entry.structureId + "  (" + entry.pos.getX() + ", " + entry.pos.getZ() + ")";
            fontRenderer.drawString(trim(label, width - STATE_BUTTON_WIDTH - 60), xPos + 4, yPos + 5,
                    0xE0E0E0);
            int buttonX = xPos + width - STATE_BUTTON_WIDTH - 8;
            int color = entry.state == SearchMemory.State.FOUND ? 0x2E7D32
                    : entry.state == SearchMemory.State.MISSING ? 0x8E2A2A : 0x3A5F8A;
            drawRect(buttonX, yPos + 1, buttonX + STATE_BUTTON_WIDTH, yPos + 15, 0xFF000000 | color);
            drawCenteredString(fontRenderer, stateLabel(entry.state),
                    buttonX + STATE_BUTTON_WIDTH / 2, yPos + 4, 0xFFFFFF);
        }

        @Override
        protected int getScrollBarX() {
            return width - 8;
        }

        private boolean isStateButton(int mouseX) {
            return mouseX >= width - STATE_BUTTON_WIDTH - 8;
        }

        private void cycle(SearchMemory.Entry entry) {
            if (memory == null) {
                return;
            }
            SearchMemory.State next = entry.state == SearchMemory.State.NEW
                    ? SearchMemory.State.FOUND
                    : entry.state == SearchMemory.State.FOUND
                    ? SearchMemory.State.MISSING
                    : SearchMemory.State.NEW;
            memory.setState(entry.structureId, entry.pos, next);
            initGui();
        }
    }

    private String trim(String text, int maxWidth) {
        if (fontRenderer.getStringWidth(text) <= maxWidth) {
            return text;
        }
        String cut = text;
        while (cut.length() > 4 && fontRenderer.getStringWidth(cut + "...") > maxWidth) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "...";
    }
}
