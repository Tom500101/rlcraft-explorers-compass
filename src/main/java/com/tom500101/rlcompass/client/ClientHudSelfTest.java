package com.tom500101.rlcompass.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Client-side smoke test for the HUD.
 *
 * <p>The HUD only draws when there is a target, and a scripted run cannot hold an item or click a GUI,
 * so "the client started without crashing" would say nothing about whether the renderer works. With
 * {@code -Drlcompass.clienttest=true} this plants a synthetic target 500 blocks east as soon as a
 * player exists; {@code CompassHudRenderer} then logs once it has actually rendered a few frames.
 */
public class ClientHudSelfTest {

    public static final String PROPERTY = "rlcompass.clienttest";

    private boolean injected;

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || injected || !Boolean.getBoolean(PROPERTY)) {
            return;
        }
        EntityPlayerSP player = Minecraft.getMinecraft().player;
        if (player == null) {
            return;
        }
        BlockPos fakeTarget = player.getPosition().east(500);
        ClientCompassState.setTarget("minecraft:village", fakeTarget, player.dimension);
        injected = true;
    }
}
