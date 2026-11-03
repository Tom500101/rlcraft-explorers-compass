package com.tom500101.rlcompass.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;

/**
 * Flat translucent button that fits the compass theme instead of the vanilla textured widget.
 *
 * <p>Vanilla's {@code GuiButton} blits {@code widgets.png}, which clashes with a translucent sheet.
 * This one paints a translucent fill and a 1px hairline, and swaps the border to the accent colour
 * while hovered so the primary action is obvious without a texture or a drop shadow.
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
        int textColor;
        if (!this.enabled) {
            fill = GuiTheme.BUTTON_DISABLED;
            border = GuiTheme.BORDER_DISABLED;
            textColor = GuiTheme.TEXT_DISABLED;
        } else if (this.hovered) {
            fill = GuiTheme.BUTTON_HOVERED;
            border = GuiTheme.ACCENT;
            textColor = GuiTheme.TEXT;
        } else {
            fill = GuiTheme.BUTTON;
            border = GuiTheme.BORDER;
            textColor = GuiTheme.TEXT;
        }

        Gui.drawRect(this.x, this.y, this.x + this.width, this.y + this.height, fill);
        GuiTheme.outline(this.x, this.y, this.x + this.width, this.y + this.height, border);

        drawCenteredString(mc.fontRenderer, this.displayString,
                this.x + this.width / 2, this.y + (this.height - 8) / 2, textColor);
    }
}
