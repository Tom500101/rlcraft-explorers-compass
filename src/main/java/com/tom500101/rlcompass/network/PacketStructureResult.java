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
    /**
     * True when the pick cannot generate in the dimension the player is standing in. This is a
     * distinct outcome from "not found": nothing was searched, so the client must name the
     * dimension mismatch instead of claiming the radius came up empty.
     */
    private boolean wrongDimension;
    /** Dimensions the structure does generate in, ordered overworld → nether → end. */
    private int[] expectedDimensions = new int[0];

    /** Forge packet deserialisation hook. */
    public PacketStructureResult() {
    }

    private PacketStructureResult(boolean found, String structureId, int dimension, BlockPos pos,
                                  String variantId, boolean variantConfirmed) {
        this(found, structureId, dimension, pos, variantId, variantConfirmed, false, null);
    }

    private PacketStructureResult(boolean found, String structureId, int dimension, BlockPos pos,
                                  String variantId, boolean variantConfirmed,
                                  boolean wrongDimension, int[] expectedDimensions) {
        this.found = found;
        this.structureId = structureId == null ? "" : structureId;
        this.dimension = dimension;
        this.pos = pos;
        this.variantId = variantId == null ? "" : variantId;
        this.variantConfirmed = variantConfirmed;
        this.wrongDimension = wrongDimension;
        this.expectedDimensions = expectedDimensions == null ? new int[0] : expectedDimensions;
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

    /**
     * The pick exists, but never generates in the dimension the player is in. Sent instead of a
     * plain "not found" so the client can say where it does generate.
     */
    public static PacketStructureResult wrongDimension(String structureId, int dimension,
                                                       String variantId, int[] expectedDimensions) {
        return new PacketStructureResult(false, structureId, dimension, null, variantId, false,
                true, expectedDimensions);
    }

    public boolean isFound() {
        return found && pos != null;
    }

    /** True when the search was refused because the structure belongs to other dimensions. */
    public boolean isWrongDimension() {
        return wrongDimension;
    }

    /** Dimensions the structure generates in; empty when the compass has no data for it. */
    public int[] getExpectedDimensions() {
        return expectedDimensions;
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
        wrongDimension = buf.readBoolean();
        // Clamp before allocating: a malformed packet must not ask for a huge array.
        int expectedCount = Math.max(0, Math.min(16, buf.readInt()));
        expectedDimensions = new int[expectedCount];
        for (int i = 0; i < expectedCount; i++) {
            expectedDimensions[i] = buf.readInt();
        }
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
        buf.writeBoolean(wrongDimension);
        buf.writeInt(expectedDimensions.length);
        for (int expected : expectedDimensions) {
            buf.writeInt(expected);
        }
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
                    } else if (message.isWrongDimension()) {
                        ClientCompassState.clear();
                        // Nothing was searched: say where the structure does generate instead of
                        // reporting an empty radius, which would be misleading.
                        Minecraft.getMinecraft().ingameGUI.getChatGUI().printChatMessage(
                                new TextComponentTranslation("rlcompass.search.wrongDimension",
                                        describe(message.getStructureId(), message.getVariantId()),
                                        dimensionsText(message.getExpectedDimensions()),
                                        dimensionsText(new int[]{message.getDimension()})));
                    } else {
                        ClientCompassState.clear();
                        // Ice and Fire keeps no position record and its random stream is shared with
                        // every other feature, so these two can only be found in generated chunks.
                        // A plain "not found" would read as a broken locator.
                        String key = isGeneratedOnly(message.getStructureId())
                                ? "rlcompass.search.notFoundGenerated"
                                : "rlcompass.search.notFound";
                        // A miss used to be completely silent: the target simply vanished. Say which
                        // structure (and theme, when one was picked) came up empty.
                        Minecraft.getMinecraft().ingameGUI.getChatGUI().printChatMessage(
                                new TextComponentTranslation(key,
                                        describe(message.getStructureId(), message.getVariantId())));
                    }
                }
            });
            return null;
        }

        /** Structures that exist only as blocks on disk, never as a replayable position record. */
        private static boolean isGeneratedOnly(String structureId) {
            String canonical = StructureNameUtil.canonical(structureId);
            return "rlcompass:dragoncave".equals(canonical)
                    || "rlcompass:dragonroost".equals(canonical);
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

        /** "Overworld / Nether" for a list of dimension ids, in the client's own language. */
        private static String dimensionsText(int[] dimensions) {
            if (dimensions == null || dimensions.length == 0) {
                return "?";
            }
            StringBuilder text = new StringBuilder();
            for (int dimension : dimensions) {
                if (text.length() > 0) {
                    text.append(" / ");
                }
                String key = com.tom500101.rlcompass.util.StructureDimensions.translationKey(dimension);
                text.append(key == null
                        ? com.tom500101.rlcompass.util.StructureDimensions.fallbackName(dimension)
                        : net.minecraft.client.resources.I18n.format(key));
            }
            return text.toString();
        }
    }
}
