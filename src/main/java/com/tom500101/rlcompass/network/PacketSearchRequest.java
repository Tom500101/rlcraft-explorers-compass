package com.tom500101.rlcompass.network;

import io.netty.buffer.ByteBuf;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;

import com.tom500101.rlcompass.item.ItemExplorersCompass;

/**
 * Client to server: the player picked a structure in the selection GUI.
 *
 * <p>The search itself never runs on the client — the locator registry reaches into server-side mod
 * data, and the 9-second mineshaft story is a reminder that this work belongs on the server thread
 * regardless of where it is triggered.
 */
public class PacketSearchRequest implements IMessage {

    private String structureId = "";

    /** Forge packet deserialisation hook. */
    public PacketSearchRequest() {
    }

    public PacketSearchRequest(String structureId) {
        this.structureId = structureId == null ? "" : structureId;
    }

    public String getStructureId() {
        return structureId;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        structureId = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, structureId);
    }

    public static class Handler implements IMessageHandler<PacketSearchRequest, IMessage> {

        @Override
        public IMessage onMessage(final PacketSearchRequest message, MessageContext ctx) {
            if (ctx.side != Side.SERVER) {
                return null;
            }
            final EntityPlayerMP player = ctx.getServerHandler().player;
            final MinecraftServer server = player.getServer();
            if (server == null) {
                return null;
            }
            // Handlers fire on the netty thread; structure lookups touch world state.
            server.addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    ItemExplorersCompass.search(player, message.getStructureId());
                }
            });
            return null;
        }
    }
}
