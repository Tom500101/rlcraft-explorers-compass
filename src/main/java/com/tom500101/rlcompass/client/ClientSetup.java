package com.tom500101.rlcompass.client;

import net.minecraftforge.common.MinecraftForge;

/**
 * Client-only wiring.
 *
 * <p>Kept separate from {@code RLCompassMod} on purpose: this class is loaded on both sides but only
 * its {@link #register()} body touches {@link CompassHudRenderer}, which in turn references
 * {@code net.minecraft.client.*}. A dedicated server does not ship the client classes, so the mod
 * entry point must never initialise the renderer unconditionally.
 */
public final class ClientSetup {

    private static final org.apache.logging.log4j.Logger LOG =
            org.apache.logging.log4j.LogManager.getLogger("rlcompass");

    private ClientSetup() {
    }

    public static void register() {
        MinecraftForge.EVENT_BUS.register(new CompassHudRenderer());
        MinecraftForge.EVENT_BUS.register(new ClientHudSelfTest());
        LOG.info("[CLIENT] HUD renderer registered");
    }
}
