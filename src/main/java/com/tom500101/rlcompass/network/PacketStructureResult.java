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
    /** Empty on the wire when the search was for the parent structure. */
    private String variantId = "";
    private boolean variantConfirmed;

    /** Forge packet deserialisation hook. */
    public PacketStructureResult() {
    }

    private PacketStructureResult(boolean found, String structureId, int dimension, BlockPos pos,
                                  String variantId, boolean variantConfirmed) {
        this.found = found;
        this.structureId = structureId == null ? "" : structureId;
        this.dimension = dimension;
        this.pos = pos;
        this.variantId = variantId == null ? "" : variantId;
        this.variantConfirmed = variantConfirmed;
    }

    public static PacketStructureResult found(String structureId, BlockPos pos, int dimension) {
        return found(structureId, pos, dimension, null, false);
    }

    public static PacketStructureResult found(String structureId, BlockPos pos, int dimension,
                                              String variantId, boolean variantConfirmed) {
        return new PacketStructureResult(true, structureId, dimension, pos, variantId, variantConfirmed);
    }

    public static PacketStructureResult notFound(String structureId, int dimension) {
        return new PacketStructureResult(false, structureId, dimension, null, null, false);
    }

    /** Negative outcome for a themed search, so the client can say which theme came up empty. */
    public static PacketStructureResult notFound(String structureId, int dimension, String variantId) {
        return new PacketStructureResult(false, structureId, dimension, null, variantId, false);
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

    /** Requested variant id, or {@code null} when the search was for the parent. */
    public String getVariantId() {
        return variantId == null || variantId.isEmpty() ? null : variantId;
    }

    public boolean isVariantConfirmed() {
        return variantConfirmed;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        // This boolean is toBytes' writePos (found && pos != null), not the raw found bit: it says
        // whether the three coordinate ints were written at all. Reconstructing found from it keeps
        // the read side symmetric with the write side without inspecting pos, which is still unset
        // here and would always be null on a freshly allocated instance.
        found = buf.readBoolean();
        structureId = ByteBufUtils.readUTF8String(buf);
        dimension = buf.readInt();
        variantId = ByteBufUtils.readUTF8String(buf);
        variantConfirmed = buf.readBoolean();
        // Matches toBytes: the coordinates are present exactly when that flag was set.
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
        ByteBufUtils.writeUTF8String(buf, variantId == null ? "" : variantId);
        buf.writeBoolean(variantConfirmed);
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
                    ClientSearchStatus.end();
                    if (message.isFound()) {
                        ClientCompassState.setTarget(message.getStructureId(), message.getPos(),
                                message.getDimension(), message.getVariantId(),
                                message.isVariantConfirmed());
                    } else {
                        ClientCompassState.clear();
                        // A miss used to be completely silent: the target simply vanished. Say which
                        // structure (and theme, when one was picked) came up empty.
                        Minecraft.getMinecraft().ingameGUI.getChatGUI().printChatMessage(
                                new TextComponentTranslation("rlcompass.search.notFound",
                                        describe(message.getStructureId(), message.getVariantId())));
                    }
                }
            });
            return null;
        }

        /** "Ice Roguelike Dungeon" for a themed search, "Village" for a plain one. */
        private static String describe(String structureId, String variantId) {
            String parent = StructureNameUtil.displayName(structureId);
            if (variantId == null || variantId.isEmpty()) {
                return parent;
            }
            int slash = variantId.lastIndexOf('/');
            String theme = slash >= 0 ? variantId.substring(slash + 1) : variantId;
            StringBuilder pretty = new StringBuilder();
            for (String part : theme.split("_")) {
                if (part.isEmpty()) {
                    continue;
                }
                if (pretty.length() > 0) {
                    pretty.append(' ');
                }
                pretty.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
            return pretty.length() == 0 ? parent : pretty + " " + parent;
        }
    }
}
