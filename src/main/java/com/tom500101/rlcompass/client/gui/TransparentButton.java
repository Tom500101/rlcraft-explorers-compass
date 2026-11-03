package com.tom500101.rlcompass.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;

/**
 * Flat translucent button that fits the compass theme instead of the vanilla textured widget.
 *
 * <p>Vanilla's {@code GuiButton} blits {@code widgets.png}, which clashes with a flat screen. This
 * one paints a single translucent fill and nothing else — no border, no shadow, no gradient — and
 * only changes the fill on hover/disable, the same three states the reference screen uses.
 */
public class TransparentButton extends GuiButton {

    public TransparentButton(int buttonId, int x, int y, int widthIn, int heightIn, String buttonText) {
        super(buttonId, x, y, widthIn, heightIn, buttonText);
    }

    @Override
    public void drawButton(Minecraft mc, int mouseX, int mouseY, float partialTicks) {
        if (!this.visible) {
            return;
        }
        this.hovered = mouseX >= this.x && mouseY >= this.y
                && mouseX < this.x + this.width && mouseY < this.y + this.height;

        int fill = !this.enabled ? GuiTheme.CONTROL_DISABLED
                : this.hovered ? GuiTheme.CONTROL_HOVERED
                : GuiTheme.CONTROL;

        Gui.drawRect(this.x, this.y, this.x + this.width, this.y + this.height, fill);
        drawCenteredString(mc.fontRenderer, this.displayString,
                this.x + this.width / 2, this.y + (this.height - 8) / 2, GuiTheme.TEXT);
    }
}
