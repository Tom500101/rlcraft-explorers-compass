package com.tom500101.rlcompass.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;

/**
 * Flat button that fits the compass palette instead of the vanilla textured widget.
 *
 * <p>Vanilla's {@code GuiButton} blits {@code widgets.png}, which clashes badly with a dark
 * custom panel. This one paints a shadow offset, a solid fill and a 1px border, and swaps the
 * border to the accent colour while hovered so the primary action is obvious without a texture.
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

        int fill;
        int border;
        if (!this.enabled) {
            fill = CompassPalette.BUTTON_FILL_DISABLED;
            border = CompassPalette.BUTTON_BORDER_DISABLED;
        } else if (this.hovered) {
            fill = CompassPalette.BUTTON_FILL_HOVERED;
            border = CompassPalette.BUTTON_BORDER_HOVERED;
        } else {
            fill = CompassPalette.BUTTON_FILL;
            border = CompassPalette.BUTTON_BORDER;
        }

        Gui.drawRect(this.x + 1, this.y + 1, this.x + this.width + 1, this.y + this.height + 1,
                CompassPalette.PANEL_SHADOW);
        Gui.drawRect(this.x, this.y, this.x + this.width, this.y + this.height, fill);
        CompassPalette.drawOutline(this.x, this.y, this.x + this.width, this.y + this.height, border);

        int textColor = this.enabled ? CompassPalette.TEXT : CompassPalette.TEXT_DISABLED;
        drawCenteredString(mc.fontRenderer, this.displayString,
                this.x + this.width / 2, this.y + (this.height - 8) / 2, textColor);
    }
}
