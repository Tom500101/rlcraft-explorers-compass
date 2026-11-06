package com.tom500101.rlcompass.locate;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import com.tom500101.rlcompass.config.CompassConfig;
import com.tom500101.rlcompass.locate.impl.AuthorHallFastScan;
import com.tom500101.rlcompass.locate.impl.RcFastScan;
import com.tom500101.rlcompass.locate.impl.RcUltraRareLocator;
import com.tom500101.rlcompass.network.PacketDeepScanResult;
import com.tom500101.rlcompass.network.PacketHandler;
import com.tom500101.rlcompass.network.PacketStructureResult;
import com.tom500101.rlcompass.util.StructureDimensions;

/**
 * Starts, reports and cancels the tick-sliced RC deep scans from the compass picker.
 *
 * <p>The command path is not reused: {@code /rlcompass} requires permission level 2, so a survival
 * player without cheats cannot invoke it. The picker is the normal gameplay entry point and calls
 * this service directly over the network instead.
 *
 * <p>Routing mirrors {@code /rlcompass scan}: the Author's Hall keeps its dedicated scanner, other
 * {@code rare} targets use the parameterised prefilter, and {@code ultrarare} / {@code adventure}
 * targets use the generic tile scan. All three are tick-sliced (4-8 ms per tick), so the server
 * keeps running while the scan is in progress.
 */
public final class DeepScanService {

    private DeepScanService() {
    }

    /** True while any of the three scanners owns the server's scan slot. */
    public static boolean active() {
        return RcUltraRareLocator.deepScanActive() || AuthorHallFastScan.active() || RcFastScan.active();
    }

    /** RC registry name of the running scan, or {@code null} when idle. */
    public static String runningName() {
        if (AuthorHallFastScan.active()) {
            return RcUltraRareLocator.AUTHOR_HALL.rcName;
        }
        if (RcFastScan.active()) {
            return RcFastScan.runningName();
        }
        if (RcUltraRareLocator.deepScanActive()) {
            return RcUltraRareLocator.runningName();
        }
        return null;
    }

    /**
     * Starts a deep scan for {@code structureId} centred on the player, or reports why it cannot.
     * The result is sent back as {@link PacketDeepScanResult}; the return value is for logging.
     */
    public static String start(EntityPlayerMP player, String structureId, int radiusBlocks) {
        if (player == null) {
            return "no player";
        }
        RcUltraRareLocator.Target target = RcUltraRareLocator.targetFor(structureId);
        if (target == null) {
            send(player, PacketDeepScanResult.unavailable(structureId));
            return "unsupported structure: " + structureId;
        }
        World world = player.getEntityWorld();
        int dimension = world.provider.getDimension();
        // The picker already greys out other dimensions, but the request can be replayed; say where
        // the structure lives instead of pretending an empty radius was searched.
        if (!StructureDimensions.allows(target.id, null, dimension)) {
            PacketHandler.sendToPlayer(PacketStructureResult.wrongDimension(target.id, dimension,
                    null, StructureDimensions.of(target.id, null)), player);
            return "wrong dimension for " + target.id;
        }
        int blocks = radiusBlocks > 0 ? radiusBlocks : CompassConfig.searchRadius;
        int radiusChunks = Math.max(8, Math.min(RcUltraRareLocator.MAX_CHUNK_RADIUS, blocks / 16));

        // A structure RC already recorded (or the warehouse knows) is authoritative: return it
        // immediately instead of spending minutes re-scanning for something the save contains.
        BlockPos known = RcUltraRareLocator.knownHit(target, world, player.getPosition(), blocks);
        if (known != null) {
            send(player, PacketDeepScanResult.hit(target.id, known, dimension));
            return "recorded hit at " + known;
        }

        if (active()) {
            send(player, PacketDeepScanResult.busy(runningName()));
            return "another scan is running: " + runningName();
        }
        boolean started;
        String message;
        if (RcUltraRareLocator.AUTHOR_HALL.id.equals(target.id)) {
            message = AuthorHallFastScan.start(world, player, player.getPosition(), radiusChunks, true);
            started = AuthorHallFastScan.active();
        } else if ("rare".equalsIgnoreCase(target.category)) {
            message = RcFastScan.start(target, world, player, player.getPosition(), radiusChunks,
                    true);
            started = RcFastScan.active();
        } else {
            message = RcUltraRareLocator.startDeepScan(target, world, player, player.getPosition(),
                    radiusChunks, true);
            started = RcUltraRareLocator.deepScanActive();
        }
        if (started) {
            send(player, PacketDeepScanResult.started(target.id, dimension, radiusChunks));
        } else {
            send(player, PacketDeepScanResult.unavailable(target.id));
        }
        return message;
    }

    /** Stops whichever scanner is running; each one notifies its own initiator. */
    public static String cancel(EntityPlayerMP player) {
        StringBuilder text = new StringBuilder();
        append(text, AuthorHallFastScan.stop());
        append(text, RcFastScan.stop());
        append(text, RcUltraRareLocator.stopDeepScan());
        return text.length() == 0 ? "no scan is running" : text.toString();
    }

    private static void append(StringBuilder text, String result) {
        if (result == null || "no scan is running".equals(result)) {
            return;
        }
        if (text.length() > 0) {
            text.append(" | ");
        }
        text.append(result);
    }

    private static void send(EntityPlayerMP player, PacketDeepScanResult message) {
        PacketHandler.sendToPlayer(message, player);
    }
}
