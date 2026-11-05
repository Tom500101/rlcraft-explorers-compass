package com.tom500101.rlcompass.network;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

/**
 * Owns the mod's network channel.
 *
 * <p>{@link #init()} must run during pre-init, before any player can join — Forge rejects channel
 * creation after the network registry is frozen.
 */
public final class PacketHandler {

    private static final String CHANNEL_NAME = "rlcompass";

    private static final int ID_STRUCTURE_RESULT = 0;
    private static final int ID_SEARCH_REQUEST = 1;
    private static final int ID_OPEN_GUI = 2;
    private static final int ID_TELEPORT_REQUEST = 3;

    private static SimpleNetworkWrapper channel;

    private PacketHandler() {
    }

    public static void init() {
        if (channel != null) {
            return;
        }
        channel = NetworkRegistry.INSTANCE.newSimpleChannel(CHANNEL_NAME);
        // Server -> client
        channel.registerMessage(PacketStructureResult.Handler.class, PacketStructureResult.class,
                ID_STRUCTURE_RESULT, Side.CLIENT);
        channel.registerMessage(PacketOpenCompassGui.Handler.class, PacketOpenCompassGui.class,
                ID_OPEN_GUI, Side.CLIENT);
        // Client -> server
        channel.registerMessage(PacketSearchRequest.Handler.class, PacketSearchRequest.class,
                ID_SEARCH_REQUEST, Side.SERVER);
        channel.registerMessage(PacketTeleportRequest.Handler.class, PacketTeleportRequest.class,
                ID_TELEPORT_REQUEST, Side.SERVER);
    }

    public static SimpleNetworkWrapper channel() {
        if (channel == null) {
            throw new IllegalStateException("PacketHandler.init() has not been called");
        }
        return channel;
    }

    public static void sendToPlayer(PacketStructureResult message, EntityPlayerMP player) {
        if (player == null) {
            return;
        }
        channel().sendTo(message, player);
    }

    public static void sendOpenGui(EntityPlayerMP player, java.util.List<String> structureIds) {
        if (player == null) {
            return;
        }
        channel().sendTo(new PacketOpenCompassGui(structureIds), player);
    }
}
