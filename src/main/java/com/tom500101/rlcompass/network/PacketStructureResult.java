package com.tom500101.rlcompass.network;

import io.netty.buffer.ByteBuf;

import net.minecraft.client.Minecraft;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;

import com.tom500101.rlcompass.client.ClientCompassState;

/**
 * Server to client: the outcome of a compass search.
 *
 * <p>Carries both the positive and the negative outcome so the HUD can tell "still searching" apart
 * from "nothing in range" instead of silently keeping a stale target.
 */
public class PacketStructureResult implements IMessage {

    private boolean found;
    private String structureId = "";
    private int dimension;
    private BlockPos pos;

    /** Forge packet deserialisation hook. */
    public PacketStructureResult() {
    }

    private PacketStructureResult(boolean found, String structureId, int dimension, BlockPos pos) {
        this.found = found;
        this.structureId = structureId == null ? "" : structureId;
        this.dimension = dimension;
        this.pos = pos;
    }

    public static PacketStructureResult found(String structureId, BlockPos pos, int dimension) {
        return new PacketStructureResult(true, structureId, dimension, pos);
    }

    public static PacketStructureResult notFound(String structureId, int dimension) {
        return new PacketStructureResult(false, structureId, dimension, null);
    }

    public boolean isFound() {
        return found && pos != null;
    }

    public String getStructureId() {
        return structureId;
    }

    public int getDimension() {
        return dimension;
    }

    public BlockPos getPos() {
        return pos;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        found = buf.readBoolean();
        structureId = ByteBufUtils.readUTF8String(buf);
        dimension = buf.readInt();
        if (found) {
            pos = new BlockPos(buf.readInt(), buf.readInt(), buf.readInt());
        } else {
            pos = null;
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        boolean writePos = found && pos != null;
        buf.writeBoolean(writePos);
        ByteBufUtils.writeUTF8String(buf, structureId);
        buf.writeInt(dimension);
        if (writePos) {
            buf.writeInt(pos.getX());
            buf.writeInt(pos.getY());
            buf.writeInt(pos.getZ());
        }
    }

    /** Applies the result on the client's main thread. */
    public static class Handler implements IMessageHandler<PacketStructureResult, IMessage> {

        @Override
        public IMessage onMessage(final PacketStructureResult message, MessageContext ctx) {
            if (ctx.side != Side.CLIENT) {
                return null;
            }
            // SimpleNetworkWrapper handlers fire on the netty thread; touching client state from
            // there races the render loop, so hop back onto the main thread.
            Minecraft.getMinecraft().addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    if (message.isFound()) {
                        ClientCompassState.setTarget(
                                message.getStructureId(), message.getPos(), message.getDimension());
                    } else {
                        ClientCompassState.clear();
                    }
                }
            });
            return null;
        }
    }
}
