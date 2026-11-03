package com.tom500101.rlcompass.client.gui;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiTextField;

/**
 * Search field drawn as a flat translucent bar.
 *
 * <p>{@code GuiTextField} draws its own vanilla box, so the background drawing is disabled and the
 * chrome is painted here with a few pixels of padding around the text: one translucent fill, no
 * border. Upstream's field has no focus ring either — the caret is the only focus cue.
 *
 * <p>The padding is why this class exposes visual bounds: the clickable area is larger than the text
 * area, otherwise players have to aim at a 12px-tall strip to focus the field.
 */
public class TransparentEditBox extends GuiTextField {

    private static final int PADDING_X = 8;
    private static final int PADDING_Y = 6;

    public TransparentEditBox(int id, FontRenderer fontRenderer, int x, int y, int width, int height) {
        super(id, fontRenderer, x, y, width, height);
    }

    public int getVisualLeft() {
        return this.x - PADDING_X;
    }

    public int getVisualTop() {
        return this.y - PADDING_Y;
    }

    public int getVisualRight() {
        return this.x + this.width + PADDING_X;
    }

    public int getVisualBottom() {
        return this.y + this.height + PADDING_Y;
    }

    @Override
    public boolean mouseClicked(int mouseX, int mouseY, int mouseButton) {
        boolean insideVisual = mouseX >= getVisualLeft() && mouseX < getVisualRight()
                && mouseY >= getVisualTop() && mouseY < getVisualBottom();
        if (insideVisual && mouseButton == 0) {
            // GuiTextField only reacts to clicks inside the text area, so clamp into it.
            int clampedX = Math.max(this.x, Math.min(mouseX, this.x + this.width - 1));
            int clampedY = Math.max(this.y, Math.min(mouseY, this.y + this.height - 1));
            return super.mouseClicked(clampedX, clampedY, mouseButton);
        }
        return super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    public void drawTextBox() {
        if (!this.getVisible()) {
            return;
        }
        Gui.drawRect(getVisualLeft(), getVisualTop(), getVisualRight(), getVisualBottom(),
                GuiTheme.CONTROL);
        super.drawTextBox();
    }
}
