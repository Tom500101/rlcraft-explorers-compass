package com.tom500101.rlcompass.command;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;

import com.tom500101.rlcompass.cache.CacheRecorder;
import com.tom500101.rlcompass.cache.StructureCacheData;
import com.tom500101.rlcompass.config.CompassConfig;
import com.tom500101.rlcompass.item.ItemExplorersCompass;
import com.tom500101.rlcompass.locate.StructureLocatorRegistry;
import com.tom500101.rlcompass.locate.StructureResult;
import com.tom500101.rlcompass.util.StructureDimensions;
import com.tom500101.rlcompass.util.StructureNameUtil;
import com.tom500101.rlcompass.util.StructureProbe;

/**
 * The {@code /rlcompass} command.
 *
 * <p>{@code probe} is the stage-1 diagnostic, {@code list} and {@code find} exercise the locator
 * framework from the console — the only way to drive a dedicated server without a player.
 */
public class CommandRLCompass extends CommandBase {

    private static final List<String> SUBCOMMANDS =
            Arrays.asList("probe", "list", "find", "search", "rebuildcache", "cache", "rccheck",
                    "shivaxi");

    @Override
    public String getName() {
        return "rlcompass";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/rlcompass <probe|list|find <structureId>>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 1) {
            throw new CommandException("usage: " + getUsage(sender));
        }
        World world = sender.getEntityWorld();
        BlockPos origin = sender.getPosition();
        String sub = args[0].toLowerCase(java.util.Locale.ROOT);

        switch (sub) {
            case "probe":
                StructureProbe.probe(world, origin);
                reply(sender, "structure probe finished, results are in the server log");
                return;

            case "list": {
                // P0: world-aware filter — hide ids whose locator has no data source in this world.
                List<String> ids = StructureLocatorRegistry.get().getSelectableIds(world);
                if (ids.isEmpty()) {
                    reply(sender, "no structures registered");
                    return;
                }
                // Show the dimensions next to each id so an admin can see at a glance why a search
                // in the current world can only answer "wrong dimension".
                StringBuilder listed = new StringBuilder("searchable: ");
                for (int i = 0; i < ids.size(); i++) {
                    if (i > 0) {
                        listed.append(", ");
                    }
                    listed.append(ids.get(i));
                    int[] dimensions = StructureDimensions.of(ids.get(i));
                    if (dimensions.length > 0) {
                        listed.append(' ').append(dimensionNames(dimensions));
                    }
                }
                reply(sender, listed.toString());
                return;
            }

            case "find": {
                if (args.length < 2) {
                    throw new CommandException("usage: /rlcompass find <structureId>");
                }
                String structureId = args[1];
                if (CompassConfig.isBlacklisted(structureId)) {
                    reply(sender, structureId + " is blacklisted in the config");
                    return;
                }
                String canonicalId = StructureLocatorRegistry.canonical(structureId);
                int dimension = world.provider.getDimension();
                // A structure that only generates elsewhere was never searched, so say that instead
                // of the generic "nothing within N blocks".
                if (!StructureDimensions.allows(canonicalId, null, dimension)) {
                    reply(sender, structureId + " only generates in "
                            + dimensionNames(StructureDimensions.of(canonicalId))
                            + "; you are in " + StructureDimensions.nameOf(dimension));
                    return;
                }
                // Distinguish "this world cannot answer for the id" from "searched, nothing in
                // range". The picker already hides unavailable ids; the command must say so too.
                if (!StructureLocatorRegistry.get().getSelectableIds(world).contains(canonicalId)) {
                    reply(sender, structureId + " is not available in this world "
                            + "(another mod may have replaced its generation)");
                    return;
                }
                long startedAt = System.currentTimeMillis();
                StructureResult result = StructureLocatorRegistry.get()
                        .findNearest(world, origin, structureId, CompassConfig.searchRadius);
                long elapsed = System.currentTimeMillis() - startedAt;
                if (result == null) {
                    reply(sender, "no " + structureId + " within " + CompassConfig.searchRadius
                            + " blocks (" + elapsed + " ms)");
                } else {
                    StringBuilder text = new StringBuilder();
                    text.append(StructureNameUtil.displayName(result.getStructureId()))
                            .append(" at (").append(result.getPos().getX()).append(", ")
                            .append(result.getPos().getY()).append(", ")
                            .append(result.getPos().getZ()).append("), ")
                            .append(Math.round(result.getDistance())).append(" blocks away, found by ")
                            .append(result.getSource()).append(" (").append(elapsed).append(" ms)");
                    if (result.getVariantId() != null) {
                        text.append(result.isVariantConfirmed()
                                ? " [theme: " + result.getVariantId() + "]"
                                : " [theme unknown - themed search is approximate; verify after generation]");
                    }
                    reply(sender, text.toString());
                }
                return;
            }

            case "search": {
                if (args.length < 2) {
                    throw new CommandException("usage: /rlcompass search <structureId>");
                }
                EntityPlayerMP player = getCommandSenderAsPlayer(sender);
                int playerDimension = player.getEntityWorld().provider.getDimension();
                // Same guard the item applies, so the console reply never contradicts the chat text.
                if (!StructureDimensions.allows(args[1], null, playerDimension)) {
                    reply(sender, args[1] + " only generates in "
                            + dimensionNames(StructureDimensions.of(args[1]))
                            + "; the player is in " + StructureDimensions.nameOf(playerDimension));
                    return;
                }
                // Same code path the item takes, including the packet back to the client.
                StructureResult result = ItemExplorersCompass.search(player, args[1]);
                if (result == null) {
                    reply(sender, "nothing found for " + args[1]);
                } else {
                    reply(sender, "sent to client: " + result);
                }
                return;
            }

            case "shivaxi": {
                // Long-running replacement for the 200-block search: walks outward chunk by chunk
                // and replays RC on every snowy one. Tick-sliced, so the game keeps running.
                if (args.length >= 2 && "cancel".equalsIgnoreCase(args[1])) {
                    reply(sender, com.tom500101.rlcompass.locate.impl.ShivaxiLocator.stopDeepScan());
                    return;
                }
                int radiusChunks = 1200;
                if (args.length >= 2) {
                    try {
                        radiusChunks = Math.max(8, Math.min(com.tom500101.rlcompass.locate.impl.ShivaxiLocator.MAX_CHUNK_RADIUS,
                                Integer.parseInt(args[1])));
                    } catch (NumberFormatException ignored) {
                        // keep the default
                    }
                }
                EntityPlayerMP player = getCommandSenderAsPlayer(sender);
                reply(sender, com.tom500101.rlcompass.locate.impl.ShivaxiLocator.startDeepScan(
                        world, player, player.getPosition(), radiusChunks));
                return;
            }

            case "rccheck": {
                // Accuracy check for the ultra-rare replay: can it reproduce structures RC really
                // placed? Without this, a prediction for an unexplored chunk is just a guess.
                // The optional count matters: records written before the category order was pinned
                // came from a different (identity-hash) order and can never be replayed, so checking
                // only the newest entries is what actually validates the mechanism.
                int newest = 10;
                if (args.length >= 2) {
                    try {
                        newest = Math.max(1, Math.min(200, Integer.parseInt(args[1])));
                    } catch (NumberFormatException ignored) {
                        // keep the default
                    }
                }
                reply(sender, com.tom500101.rlcompass.locate.impl.ShivaxiLocator.diagnose(world, newest));
                return;
            }

            case "rebuildcache": {
                long startedAt = System.currentTimeMillis();
                int written = CacheRecorder.rebuild(world);
                long elapsed = System.currentTimeMillis() - startedAt;
                if (written < 0) {
                    reply(sender, "cache storage unavailable, nothing recorded");
                } else {
                    reply(sender, "cache rebuilt for dimension " + world.provider.getDimension()
                            + ": " + written + " positions (" + elapsed + " ms)");
                }
                return;
            }

            case "cache": {
                StructureCacheData cache = CacheRecorder.get(world);
                if (cache == null) {
                    reply(sender, "cache storage unavailable");
                    return;
                }
                int dimension = world.provider.getDimension();
                List<String> ids = cache.idsFor(dimension);
                if (ids.isEmpty()) {
                    reply(sender, "cache is empty for dimension " + dimension
                            + " (" + cache.totalEntries() + " entries in other dimensions)");
                    return;
                }
                StringBuilder text = new StringBuilder("cache for dimension " + dimension + ": ");
                for (int i = 0; i < ids.size(); i++) {
                    if (i > 0) {
                        text.append(", ");
                    }
                    text.append(ids.get(i)).append('=').append(cache.countFor(dimension, ids.get(i)));
                }
                reply(sender, text.toString());
                return;
            }

            default:
                throw new CommandException("unknown subcommand: " + args[0] + " (usage: " + getUsage(sender) + ")");
        }
    }

    private static void reply(ICommandSender sender, String message) {
        sender.sendMessage(new TextComponentString("[rlcompass] " + message));
    }

    /**
     * "overworld / nether" for command output. The command is server-side and does not know the
     * caller's language, so the raw dimension names are used instead of localised labels.
     */
    private static String dimensionNames(int[] dimensions) {
        if (dimensions == null || dimensions.length == 0) {
            return "unknown dimensions";
        }
        StringBuilder text = new StringBuilder();
        for (int dimension : dimensions) {
            if (text.length() > 0) {
                text.append(" / ");
            }
            text.append(StructureDimensions.nameOf(dimension));
        }
        return text.toString();
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender,
                                          String[] args, BlockPos targetPos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, SUBCOMMANDS.toArray(new String[0]));
        }
        if (args.length == 2 && "find".equalsIgnoreCase(args[0])) {
            // P0: world-aware filter — do not suggest ids that cannot answer here.
            return getListOfStringsMatchingLastWord(args,
                    StructureLocatorRegistry.get().getSelectableIds(sender.getEntityWorld())
                            .toArray(new String[0]));
        }
        return Collections.emptyList();
    }
}
