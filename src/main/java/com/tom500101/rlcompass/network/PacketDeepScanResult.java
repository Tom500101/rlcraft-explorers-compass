package com.tom500101.rlcompass.network;

import io.netty.buffer.ByteBuf;

import net.minecraft.client.Minecraft;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;

import com.tom500101.rlcompass.client.ClientCompassState;
import com.tom500101.rlcompass.client.ClientSearchStatus;
import com.tom500101.rlcompass.util.StructureNameUtil;

/**
 * Server to client: the lifecycle of a tick-sliced deep scan.
 *
 * <p>A deep scan runs for minutes, so it cannot use the request/response shape of
 * {@link PacketStructureResult}. The client is told when the scan starts, and later when it hits,
 * runs to completion with nothing, or is cancelled. {@code BUSY} carries the name of the scan that
 * is already running so the player knows what to cancel instead of guessing.
 */
public class PacketDeepScanResult implements IMessage {

    public static final int STARTED = 0;
    public static final int HIT = 1;
    public static final int MISS = 2;
    public static final int CANCELLED = 3;
    public static final int BUSY = 4;
    public static final int UNAVAILABLE = 5;

    private int status;
    private String structureId = "";
    private BlockPos pos;
    private int dimension;
    private int radiusChunks;
    private String detail = "";

    /** Forge packet deserialisation hook. */
    public PacketDeepScanResult() {
    }

    private PacketDeepScanResult(int status, String structureId, BlockPos pos, int dimension,
                                 int radiusChunks, String detail) {
        this.status = status;
        this.structureId = structureId == null ? "" : structureId;
        this.pos = pos;
        this.dimension = dimension;
        this.radiusChunks = radiusChunks;
        this.detail = detail == null ? "" : detail;
    }

    public static PacketDeepScanResult started(String structureId, int dimension, int radiusChunks) {
        return new PacketDeepScanResult(STARTED, structureId, null, dimension, radiusChunks, "");
    }

    public static PacketDeepScanResult hit(String structureId, BlockPos pos, int dimension) {
        return new PacketDeepScanResult(HIT, structureId, pos, dimension, 0, "");
    }

    public static PacketDeepScanResult miss(String structureId, int dimension) {
        return new PacketDeepScanResult(MISS, structureId, null, dimension, 0, "");
    }

    /**
     * @param radiusChunks the radius the finished scan actually walked; the client uses it to tell
     *                     "nothing here" from "nothing anywhere the compass can reach".
     */
    public static PacketDeepScanResult miss(String structureId, int dimension, int radiusChunks) {
        return new PacketDeepScanResult(MISS, structureId, null, dimension, radiusChunks, "");
    }

    public static PacketDeepScanResult cancelled(String structureId, int dimension) {
        return new PacketDeepScanResult(CANCELLED, structureId, null, dimension, 0, "");
    }

    public static PacketDeepScanResult busy(String runningStructureId) {
        return new PacketDeepScanResult(BUSY, "", null, 0, 0,
                runningStructureId == null ? "?" : runningStructureId);
    }

    public static PacketDeepScanResult unavailable(String structureId) {
        return new PacketDeepScanResult(UNAVAILABLE, structureId, null, 0, 0, "");
    }

    public int getStatus() {
        return status;
    }

    public String getStructureId() {
        return structureId;
    }

    public BlockPos getPos() {
        return pos;
    }

    public int getDimension() {
        return dimension;
    }

    public int getRadiusChunks() {
        return radiusChunks;
    }

    public String getDetail() {
        return detail;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        status = buf.readByte();
        structureId = ByteBufUtils.readUTF8String(buf);
        dimension = buf.readInt();
        radiusChunks = buf.readInt();
        detail = ByteBufUtils.readUTF8String(buf);
        if (buf.readBoolean()) {
            pos = new BlockPos(buf.readInt(), buf.readInt(), buf.readInt());
        } else {
            pos = null;
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(status);
        ByteBufUtils.writeUTF8String(buf, structureId);
        buf.writeInt(dimension);
        buf.writeInt(radiusChunks);
        ByteBufUtils.writeUTF8String(buf, detail);
        boolean hasPos = pos != null;
        buf.writeBoolean(hasPos);
        if (hasPos) {
            buf.writeInt(pos.getX());
            buf.writeInt(pos.getY());
            buf.writeInt(pos.getZ());
        }
    }

    public static class Handler implements IMessageHandler<PacketDeepScanResult, IMessage> {

        @Override
        public IMessage onMessage(final PacketDeepScanResult message, MessageContext ctx) {
            if (ctx.side != Side.CLIENT) {
                return null;
            }
            Minecraft.getMinecraft().addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    Minecraft minecraft = Minecraft.getMinecraft();
                    String name = StructureNameUtil.displayName(message.getStructureId());
                    switch (message.getStatus()) {
                        case STARTED:
                            // Keep the indicator alive for the whole scan rather than the 30-second
                            // budget used by a synchronous search.
                            ClientSearchStatus.beginDeepScan();
                            chat(minecraft, "rlcompass.scan.started", name,
                                    Integer.valueOf(message.getRadiusChunks()));
                            break;
                        case HIT:
                            ClientSearchStatus.end();
                            if (message.getPos() != null) {
                                ClientCompassState.setTarget(message.getStructureId(),
                                        message.getPos(), message.getDimension(), null, false);
                                chat(minecraft, "rlcompass.scan.hit", name,
                                        Integer.valueOf(message.getPos().getX()),
                                        Integer.valueOf(message.getPos().getZ()));
                            }
                            break;
                        case MISS:
                            ClientSearchStatus.end();
                            ClientCompassState.clear();
                            // 6250 chunks is the 100000-block ceiling. At that point scanning
                            // harder cannot help; the player has to start from somewhere else.
                            chat(minecraft, message.getRadiusChunks() >= 6250
                                    ? "rlcompass.scan.missMaxRadius" : "rlcompass.scan.miss", name);
                            break;
                        case CANCELLED:
                            ClientSearchStatus.end();
                            chat(minecraft, "rlcompass.scan.cancelled", name);
                            break;
                        case BUSY:
                            ClientSearchStatus.end();
                            chat(minecraft, "rlcompass.scan.busy", message.getDetail());
                            break;
                        case UNAVAILABLE:
                            ClientSearchStatus.end();
                            chat(minecraft, "rlcompass.scan.unavailable", name);
                            break;
                        default:
                            break;
                    }
                }
            });
            return null;
        }

        private static void chat(Minecraft minecraft, String key, Object... args) {
            if (minecraft.ingameGUI == null) {
                return;
            }
            minecraft.ingameGUI.getChatGUI().printChatMessage(
                    new TextComponentTranslation(key, args));
        }
    }
}
