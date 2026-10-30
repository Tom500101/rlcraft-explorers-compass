package com.tom500101.rlcompass.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.GlStateManager;
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
 * <p>The target is dropped when the player changes dimension (the stored coordinates belong to the
 * old dimension) or arrives within {@link #ARRIVAL_RADIUS} blocks, which is the plan's stage-7
 * cleanup rule.
 */
public class CompassHudRenderer {

    private static final org.apache.logging.log4j.Logger LOG =
            org.apache.logging.log4j.LogManager.getLogger("rlcompass");

    private static final double ARRIVAL_RADIUS = 16.0;
    private static final String[] COMPASS_POINTS = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};

    private static int renderCount;

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

        // The tracked-target line and the on-screen arrow are independent of what is currently held.
        EntityPlayerSP active = activePlayer(player);
        if (active == null) {
            return;
        }
        BlockPos target = ClientCompassState.getPos();
        double distance = horizontalDistance(active, target);
        String label = StructureNameUtil.displayName(ClientCompassState.getStructureId());
        StringBuilder line = new StringBuilder();
        line.append("Compass: ").append(label).append("  ").append(Math.round(distance)).append(" m");
        if (CompassConfig.showExactCoordinates) {
            line.append("  [").append(target.getX()).append(", ").append(target.getZ()).append(']');
        }
        line.append("  ").append(compassPoint(active, target));
        event.getLeft().add(line.toString());

        if (Boolean.getBoolean(ClientHudSelfTest.PROPERTY)) {
            renderCount++;
            if (renderCount == 5) {
                LOG.info("[CLIENTTEST] HUD rendered {} frames, target={} at {}",
                        renderCount, ClientCompassState.getStructureId(), target);
            }
        }
    }

    @SubscribeEvent
    public void onRenderPost(RenderGameOverlayEvent.Post event) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.ALL) {
            return;
        }
        Minecraft minecraft = Minecraft.getMinecraft();
        EntityPlayerSP player = activePlayer(minecraft.player);
        if (player == null) {
            return;
        }
        BlockPos target = ClientCompassState.getPos();
        float screenWidth = event.getResolution().getScaledWidth();
        float screenHeight = event.getResolution().getScaledHeight();

        GlStateManager.pushMatrix();
        GlStateManager.translate(screenWidth / 2.0F, screenHeight / 2.0F + 40.0F, 0.0F);
        // GL rotation is counter-clockwise; the yaw offset below is clockwise.
        GlStateManager.rotate((float) -relativeYaw(player, target), 0.0F, 0.0F, 1.0F);
        GlStateManager.disableTexture2D();
        GlStateManager.color(1.0F, 0.85F, 0.2F, 1.0F);
        GL11.glBegin(GL11.GL_TRIANGLES);
        GL11.glVertex2f(0.0F, -8.0F);
        GL11.glVertex2f(-6.0F, 6.0F);
        GL11.glVertex2f(6.0F, 6.0F);
        GL11.glEnd();
        GlStateManager.enableTexture2D();
        GlStateManager.popMatrix();
    }

    /** True when the compass is held in either the main hand or the off hand. */
    private static boolean holdingCompass(EntityPlayerSP player) {
        return isCompass(player.getHeldItem(EnumHand.MAIN_HAND))
                || isCompass(player.getHeldItem(EnumHand.OFF_HAND));
    }

    private static boolean isCompass(ItemStack stack) {
        return !stack.isEmpty() && ItemExplorersCompass.INSTANCE != null
                && stack.getItem() == ItemExplorersCompass.INSTANCE;
    }

    /**
     * Returns the player to draw the tracked target for, or {@code null} when there is no target —
     * including the two cases that must also clear the target (dimension change, arrival).
     */
    private static EntityPlayerSP activePlayer(EntityPlayerSP player) {
        if (player == null || Minecraft.getMinecraft().world == null || !ClientCompassState.hasTarget()) {
            return null;
        }
        if (player.dimension != ClientCompassState.getDimension()) {
            ClientCompassState.clear();
            return null;
        }
        BlockPos target = ClientCompassState.getPos();
        if (horizontalDistance(player, target) < ARRIVAL_RADIUS) {
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
