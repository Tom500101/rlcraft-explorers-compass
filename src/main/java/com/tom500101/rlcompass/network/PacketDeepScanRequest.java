package com.tom500101.rlcompass.network;

import io.netty.buffer.ByteBuf;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;

import com.tom500101.rlcompass.locate.DeepScanService;

/**
 * Client to server: start (or stop) a tick-sliced deep scan from the compass picker.
 *
 * <p>This deliberately bypasses {@code /rlcompass scan}. The command requires permission level 2,
 * so on a normal survival world without cheats it is unreachable — which made the only reliable
 * way to search an ultrarare structure unavailable to the players who need it. Opening the picker
 * and clicking a row is the normal gameplay action, so the scan is driven from here instead.
 *
 * <p>The radius travels with the request rather than being read from the server config: the slider
 * lives on the client, and on a dedicated server the two {@code rlcompass.cfg} files are not the
 * same file.
 */
public class PacketDeepScanRequest implements IMessage {

    private boolean cancel;
    private String structureId = "";
    /** Requested radius in blocks, matching what the picker's slider shows. */
    private int radiusBlocks;

    /** Forge packet deserialisation hook. */
    public PacketDeepScanRequest() {
    }

    public PacketDeepScanRequest(String structureId, int radiusBlocks) {
        this.cancel = false;
        this.structureId = structureId == null ? "" : structureId;
        this.radiusBlocks = radiusBlocks;
    }

    private PacketDeepScanRequest(boolean cancel) {
        this.cancel = cancel;
        this.structureId = "";
    }

    public static PacketDeepScanRequest cancel() {
        return new PacketDeepScanRequest(true);
    }

    public boolean isCancel() {
        return cancel;
    }

    public String getStructureId() {
        return structureId;
    }

    public int getRadiusBlocks() {
        return radiusBlocks;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        cancel = buf.readBoolean();
        structureId = ByteBufUtils.readUTF8String(buf);
        radiusBlocks = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(cancel);
        ByteBufUtils.writeUTF8String(buf, structureId);
        buf.writeInt(radiusBlocks);
    }

    public static class Handler implements IMessageHandler<PacketDeepScanRequest, IMessage> {

        @Override
        public IMessage onMessage(final PacketDeepScanRequest message, MessageContext ctx) {
            if (ctx.side != Side.SERVER) {
                return null;
            }
            final EntityPlayerMP player = ctx.getServerHandler().player;
            final MinecraftServer server = player.getServer();
            if (server == null) {
                return null;
            }
            // Handlers fire on the netty thread; both branches touch world state and the scanners.
            server.addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    if (message.isCancel()) {
                        DeepScanService.cancel(player);
                    } else {
                        DeepScanService.start(player, message.getStructureId(),
                                message.getRadiusBlocks());
                    }
                }
            });
            return null;
        }
    }
}
