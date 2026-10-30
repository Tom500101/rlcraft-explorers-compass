package com.tom500101.rlcompass.network;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.List;

import io.netty.buffer.ByteBuf;

import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;

import com.tom500101.rlcompass.client.gui.GuiStructureSelect;

/**
 * Server to client: open the structure selection screen with this list of searchable ids.
 *
 * <p>The list is sent rather than hard-coded on the client because it depends on which mods are
 * installed and which ids the config blacklist removes — both server-side facts.
 */
public class PacketOpenCompassGui implements IMessage {

    private List<String> structureIds = new ArrayList<String>();

    /** Forge packet deserialisation hook. */
    public PacketOpenCompassGui() {
    }

    public PacketOpenCompassGui(List<String> structureIds) {
        this.structureIds = structureIds == null ? new ArrayList<String>() : new ArrayList<String>(structureIds);
    }

    public List<String> getStructureIds() {
        return structureIds;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        structureIds = new ArrayList<String>();
        int count = buf.readInt();
        for (int i = 0; i < count; i++) {
            int length = buf.readInt();
            byte[] bytes = new byte[length];
            buf.readBytes(bytes);
            structureIds.add(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(structureIds.size());
        for (String id : structureIds) {
            byte[] bytes = id.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            buf.writeInt(bytes.length);
            buf.writeBytes(bytes);
        }
    }

    public static class Handler implements IMessageHandler<PacketOpenCompassGui, IMessage> {

        @Override
        public IMessage onMessage(final PacketOpenCompassGui message, MessageContext ctx) {
            if (ctx.side != Side.CLIENT) {
                return null;
            }
            Minecraft.getMinecraft().addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    Minecraft.getMinecraft().displayGuiScreen(new GuiStructureSelect(message.getStructureIds()));
                }
            });
            return null;
        }
    }
}
