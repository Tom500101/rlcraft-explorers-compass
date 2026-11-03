package com.tom500101.rlcompass.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import org.lwjgl.opengl.GL11;

import com.tom500101.rlcompass.config.CompassConfig;
import com.tom500101.rlcompass.item.ItemExplorersCompass;
import com.tom500101.rlcompass.util.StructureNameUtil;

/**
 * Draws the active compass target: a text line with name / distance / compass point, plus an arrow
 * pointing at the target relative to where the player is looking.
 *
 * <p>The target is only dropped when the player changes dimension: the stored coordinates belong to
 * the old dimension. Arriving at the target keeps it — the compass stays pointed at the structure
 * so the player can still see where it is while standing in it.
 *
 * <p>The overlay is bound to the item: it is drawn only while the compass is held in the main or
 * off hand, and hidden the moment the player switches to another slot. The tracked target survives
 * that, so taking the compass out again restores the display.
 *
 * <p>The text line is drawn in {@code Post(ALL)} next to the arrow instead of the Forge text layer
 * ({@code RenderGameOverlayEvent.Text}), because that layer sits in the top-left corner where other
 * mods' HUD panels cover it.
 */
public class CompassHudRenderer {

    private static final org.apache.logging.log4j.Logger LOG =
            org.apache.logging.log4j.LogManager.getLogger("rlcompass");

    private static final String[] COMPASS_POINTS = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};

    private static int renderCount;
    private static String lastLine;
    private static double lastDistance;
    private static String lastCompassPoint = "";
    private static double lastRelativeYaw = Double.NaN;

    /**
     * Clears the values captured for {@code rlcompass.clienttest}. Called between modes so a stale
     * line from a previous phase cannot satisfy an assertion.
     */
    static void resetTestCapture() {
        lastLine = null;
        lastDistance = 0.0D;
        lastCompassPoint = "";
        lastRelativeYaw = Double.NaN;
        renderCount = 0;
    }

    static String getLastLine() {
        return lastLine;
    }

    static double getLastDistance() {
        return lastDistance;
    }

    static String getLastCompassPoint() {
        return lastCompassPoint;
    }

    static double getLastRelativeYaw() {
        return lastRelativeYaw;
    }

    static int getRenderCount() {
        return renderCount;
    }

    @SubscribeEvent
    public void onRenderText(RenderGameOverlayEvent.Text event) {
        Minecraft minecraft = Minecraft.getMinecraft();
        EntityPlayerSP player = minecraft.player;
        if (player == null || minecraft.world == null) {
            return;
        }

        // While the compass is held (main or off hand), always show the player's own coordinates.
        if (holdingCompass(player)) {
            BlockPos pos = player.getPosition();
            event.getLeft().add("XYZ: " + pos.getX() + " / " + pos.getY() + " / " + pos.getZ());
        }

        // The compass target line is drawn in onRenderPost together with the arrow; Forge's text
        // layer is the top-left corner, which other HUD mods cover.
    }

    @SubscribeEvent
    public void onRenderPost(RenderGameOverlayEvent.Post event) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.ALL) {
            return;
        }
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.player == null || minecraft.world == null) {
            return;
        }
        // While the answer is in flight, replace the target line with a progress line: a themed
        // search can take a second or more, and a frozen screen reads as "nothing happened".
        if (ClientSearchStatus.isSearching()) {
            drawSearching(minecraft, event);
            return;
        }
        EntityPlayerSP player = activePlayer(minecraft.player);
        if (player == null) {
            return;
        }
        // The overlay belongs to the item: hide it as soon as the compass leaves the hand. The
        // target itself is kept, so putting it back restores the display. The smoke test forces
        // the overlay on because its fake player holds nothing.
        if (!holdingCompass(player) && !testOverlayActive()) {
            return;
        }
        BlockPos target = ClientCompassState.getPos();
        float screenWidth = event.getResolution().getScaledWidth();
        float screenHeight = event.getResolution().getScaledHeight();

        double relative = relativeYaw(player, target);
        GlStateManager.pushMatrix();
        GlStateManager.translate(screenWidth / 2.0F, screenHeight / 2.0F + 40.0F, 0.0F);
        // GL rotation is counter-clockwise; the yaw offset below is clockwise.
        GlStateManager.rotate((float) -relative, 0.0F, 0.0F, 1.0F);
        GlStateManager.disableTexture2D();
        GlStateManager.color(1.0F, 0.85F, 0.2F, 1.0F);
        GL11.glBegin(GL11.GL_TRIANGLES);
        GL11.glVertex2f(0.0F, -8.0F);
        GL11.glVertex2f(-6.0F, 6.0F);
        GL11.glVertex2f(6.0F, 6.0F);
        GL11.glEnd();
        GlStateManager.enableTexture2D();
        GlStateManager.popMatrix();

        // Target line, drawn right under the arrow. Kept in this layer (not the Forge text layer)
        // so no other HUD mod can paint over it.
        double distance = horizontalDistance(player, target);
        String point = compassPoint(player, target);
        String line = buildLine(distance, point, target);
        int textWidth = minecraft.fontRenderer.getStringWidth(line);
        int textX = Math.round(screenWidth / 2.0F) - textWidth / 2;
        int textY = Math.round(screenHeight / 2.0F) + 40 + 14;
        Gui.drawRect(textX - 2, textY - 1, textX + textWidth + 2, textY + 9, 0x70000000);
        minecraft.fontRenderer.drawStringWithShadow(line, textX, textY, 0xFFFFFF);

        if (ClientHudSelfTest.isHudCaptureActive()) {
            lastLine = line;
            lastDistance = distance;
            lastCompassPoint = point;
            renderCount++;
            if (renderCount == 5) {
                LOG.info("[CLIENTTEST] HUD rendered {} frames, target={} at {}",
                        renderCount, ClientCompassState.getStructureId(), target);
            }
        }
        if (ClientHudSelfTest.isArrowCaptureActive()) {
            lastRelativeYaw = relative;
        }
    }

    /** "Searching" plus three dots riding a sine wave, drawn where the target line normally sits. */
    private static void drawSearching(Minecraft minecraft, RenderGameOverlayEvent.Post event) {
        float screenWidth = event.getResolution().getScaledWidth();
        float screenHeight = event.getResolution().getScaledHeight();
        String label = I18n.format("rlcompass.hud.searching");
        int textWidth = minecraft.fontRenderer.getStringWidth(label);
        int dotsWidth = 3 * 5;
        int x = Math.round(screenWidth / 2.0F) - (textWidth + dotsWidth) / 2;
        int y = Math.round(screenHeight / 2.0F) + 54;
        Gui.drawRect(x - 2, y - 1, x + textWidth + dotsWidth + 2, y + 9, 0x70000000);
        minecraft.fontRenderer.drawStringWithShadow(label, x, y, 0xFFFFFF);
        long phase = System.currentTimeMillis() % 1200L;
        for (int i = 0; i < 3; i++) {
            double angle = (phase / 1200.0D) * Math.PI * 2.0D - i * 0.8D;
            int offset = (int) Math.round(Math.sin(angle) * 2.0D);
            minecraft.fontRenderer.drawStringWithShadow(".", x + textWidth + 2 + i * 5, y + offset, 0xFFD966);
        }
    }

    private static String buildLine(double distance, String point, BlockPos target) {        String label = StructureNameUtil.displayName(ClientCompassState.getStructureId());
        StringBuilder line = new StringBuilder();
        line.append("Compass: ").append(label).append("  ").append(Math.round(distance)).append(" m");
        if (CompassConfig.showExactCoordinates) {
            line.append("  [").append(target.getX()).append(", ").append(target.getZ()).append(']');
        }
        line.append("  ").append(point);
        if (ClientCompassState.getVariantId() != null && !ClientCompassState.isVariantConfirmed()) {
            // Doomlike and friends pick the theme at generation time; say so instead of pretending.
            line.append("  Theme: unknown (approx.)");
        }
        return line.toString();
    }

    /** True when the compass is held in either the main hand or the off hand. */
    private static boolean holdingCompass(EntityPlayerSP player) {
        return isCompass(player.getHeldItem(EnumHand.MAIN_HAND))
                || isCompass(player.getHeldItem(EnumHand.OFF_HAND));
    }

    /** The client smoke test has no compass in hand, so it renders the overlay by request. */
    private static boolean testOverlayActive() {
        return ClientHudSelfTest.isHudCaptureActive() || ClientHudSelfTest.isArrowCaptureActive();
    }

    private static boolean isCompass(ItemStack stack) {
        return !stack.isEmpty() && ItemExplorersCompass.INSTANCE != null
                && stack.getItem() == ItemExplorersCompass.INSTANCE;
    }

    /**
     * Returns the player to draw the tracked target for, or {@code null} when there is no target.
     * The target is only cleared on a dimension change; arriving at it keeps it on screen.
     */
    private static EntityPlayerSP activePlayer(EntityPlayerSP player) {
        if (player == null || Minecraft.getMinecraft().world == null || !ClientCompassState.hasTarget()) {
            return null;
        }
        if (player.dimension != ClientCompassState.getDimension()) {
            ClientCompassState.clear();
            return null;
        }
        return player;
    }

    private static double horizontalDistance(EntityPlayerSP player, BlockPos target) {
        double dx = target.getX() + 0.5D - player.posX;
        double dz = target.getZ() + 0.5D - player.posZ;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Yaw difference between where the player looks and where the target is, in Minecraft's yaw
     * convention (0 = +Z, increasing clockwise).
     */
    private static double relativeYaw(EntityPlayerSP player, BlockPos target) {
        double dx = target.getX() + 0.5D - player.posX;
        double dz = target.getZ() + 0.5D - player.posZ;
        double bearing = Math.toDegrees(Math.atan2(-dx, dz));
        double relative = (bearing - player.rotationYaw) % 360.0D;
        return relative < 0.0D ? relative + 360.0D : relative;
    }

    /** Compass point label for the target direction. */
    private static String compassPoint(EntityPlayerSP player, BlockPos target) {
        double dx = target.getX() + 0.5D - player.posX;
        double dz = target.getZ() + 0.5D - player.posZ;
        // atan2(-dx, dz) gives Minecraft yaw; map to an 8-point rose.
        double bearing = Math.toDegrees(Math.atan2(-dx, dz));
        int index = (int) Math.floor(((bearing % 360.0D) + 360.0D + 22.5D) / 45.0D) % 8;
        return COMPASS_POINTS[index];
    }
}
