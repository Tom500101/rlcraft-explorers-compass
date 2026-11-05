package com.tom500101.rlcompass.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;

/**
 * Flat, draggable slider for the search radius.
 *
 * <p>Vanilla's {@code GuiSlider} is welded to {@code GuiPageButtonList} and paints the textured
 * widget sheet, neither of which fits this screen. This control is a single translucent bar: the
 * filled part shows the current radius, the label sits centred on top, and dragging anywhere on the
 * bar jumps to that value — no thumb to hit.
 *
 * <p>It is deliberately not a {@code GuiButton}: dragging needs {@code mouseClickMove} and
 * {@code mouseReleased}, which the screen forwards here explicitly.
 */
public class RadiusSlider {

    private final int x;
    private final int y;
    private final int width;
    private final int height;
    private final int min;
    private final int max;
    private final int step;

    private int value;
    private boolean dragging;

    public RadiusSlider(int x, int y, int width, int height, int min, int max, int step, int value) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.min = min;
        this.max = max;
        this.step = Math.max(1, step);
        this.value = clamp(value);
    }

    public int getValue() {
        return value;
    }

    public boolean isDragging() {
        return dragging;
    }

    /** Starts a drag when the click lands on the bar. Returns true when it was consumed. */
    public boolean mousePressed(int mouseX, int mouseY) {
        if (mouseX < x || mouseX >= x + width || mouseY < y || mouseY >= y + height) {
            return false;
        }
        dragging = true;
        setFromMouse(mouseX);
        return true;
    }

    public void mouseDragged(int mouseX) {
        if (dragging) {
            setFromMouse(mouseX);
        }
    }

    /** Ends the drag; returns true when a drag was actually in progress. */
    public boolean mouseReleased() {
        if (!dragging) {
            return false;
        }
        dragging = false;
        return true;
    }

    private void setFromMouse(int mouseX) {
        float fraction = (float) (mouseX - x) / (float) width;
        fraction = Math.max(0.0F, Math.min(1.0F, fraction));
        int raw = min + Math.round(fraction * (max - min));
        // Snap to the step so the config does not fill with values like 12437.
        int snapped = Math.round((float) (raw - min) / step) * step + min;
        value = clamp(snapped);
    }

    private int clamp(int candidate) {
        int clamped = Math.max(min, Math.min(max, candidate));
        int snapped = Math.round((float) (clamped - min) / step) * step + min;
        return Math.max(min, Math.min(max, snapped));
    }

    public void draw(Minecraft mc, int mouseX, int mouseY) {
        boolean hovered = mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
        Gui.drawRect(x, y, x + width, y + height,
                hovered || dragging ? GuiTheme.CONTROL_HOVERED : GuiTheme.CONTROL);

        int filled = Math.round((float) width * (value - min) / (float) (max - min));
        if (filled > 0) {
            Gui.drawRect(x, y, x + filled, y + height, 0x30FFFFFF);
        }
        String label = net.minecraft.client.resources.I18n.format("gui.rlcompass.radiusValue",
                Integer.valueOf(value));
        mc.fontRenderer.drawString(label,
                x + (width - mc.fontRenderer.getStringWidth(label)) / 2,
                y + (height - 8) / 2, GuiTheme.TEXT);
    }
}
