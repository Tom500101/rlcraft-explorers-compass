package com.tom500101.rlcompass.network;

import io.netty.buffer.ByteBuf;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;

import com.tom500101.rlcompass.util.IceCityTeleport;
import com.tom500101.rlcompass.util.SurfaceTeleport;

/**
 * Client to server: "teleport me to the surface above this warehouse entry".
 *
 * <p>The client never decides the Y. The stored position is the structure's origin, which for a
 * dragon cave or a dungeon is inside a mountain; the server recomputes a safe surface spot at that
 * X/Z and puts the player there. Permission is the same gate the {@code /rlcompass} command uses,
 * so this is available exactly when cheats are on in single player, or to OPs on a server.
 */
public class PacketTeleportRequest implements IMessage {

    private String structureId = "";
    private int x;
    private int y;
    private int z;
    private int dimension;

    /** Forge packet deserialisation hook. */
    public PacketTeleportRequest() {
    }

    public PacketTeleportRequest(String structureId, int x, int y, int z, int dimension) {
        this.structureId = structureId == null ? "" : structureId;
        this.x = x;
        this.y = y;
        this.z = z;
        this.dimension = dimension;
    }

    public String getStructureId() {
        return structureId;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getZ() {
        return z;
    }

    public int getDimension() {
        return dimension;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        structureId = ByteBufUtils.readUTF8String(buf);
        x = buf.readInt();
        y = buf.readInt();
        z = buf.readInt();
        dimension = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, structureId);
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        buf.writeInt(dimension);
    }

    public static class Handler implements IMessageHandler<PacketTeleportRequest, IMessage> {

        @Override
        public IMessage onMessage(final PacketTeleportRequest message, MessageContext ctx) {
            if (ctx.side != Side.SERVER) {
                return null;
            }
            final EntityPlayerMP player = ctx.getServerHandler().player;
            final MinecraftServer server = player.getServer();
            if (server == null) {
                return null;
            }
            // Handlers fire on the netty thread; teleporting and loading chunks must run on the
            // server thread.
            server.addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    teleport(player, message);
                }
            });
            return null;
        }

        private static void teleport(EntityPlayerMP player, PacketTeleportRequest message) {
            if (!player.canUseCommand(2, "rlcompass")) {
                player.sendMessage(new TextComponentTranslation("rlcompass.teleport.noPermission"));
                return;
            }
            if (player.dimension != message.getDimension()) {
                player.sendMessage(new TextComponentTranslation("rlcompass.teleport.wrongDimension"));
                return;
            }
            // 作者故乡 is sunken: it needs the structure-anchored search, everything else keeps the
            // generic surface rule. The exception lives in its own class so no other structure can
            // inherit it.
            net.minecraft.world.World world = player.getEntityWorld();
            BlockPos spot = IceCityTeleport.matches(message.getStructureId())
                    ? IceCityTeleport.find(world, message.getX(), message.getY(), message.getZ())
                    : null;
            if (spot == null) {
                spot = SurfaceTeleport.findNear(world, message.getX(), message.getY(),
                        message.getZ());
            }
            if (spot == null) {
                player.sendMessage(new TextComponentTranslation("rlcompass.teleport.unsafe"));
                return;
            }
            player.dismountRidingEntity();
            player.setPositionAndUpdate(spot.getX() + 0.5D, spot.getY(), spot.getZ() + 0.5D);
            player.fallDistance = 0.0F;
            player.sendMessage(new TextComponentTranslation("rlcompass.teleport.success",
                    message.getStructureId(), spot.getX(), spot.getY(), spot.getZ()));
        }
    }
}
