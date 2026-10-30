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
            Arrays.asList("probe", "list", "find", "search", "rebuildcache", "cache");

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
                List<String> ids = StructureLocatorRegistry.get().getSelectableIds();
                reply(sender, ids.isEmpty()
                        ? "no structures registered"
                        : "searchable: " + String.join(", ", ids));
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
                long startedAt = System.currentTimeMillis();
                StructureResult result = StructureLocatorRegistry.get()
                        .findNearest(world, origin, structureId, CompassConfig.searchRadius);
                long elapsed = System.currentTimeMillis() - startedAt;
                if (result == null) {
                    reply(sender, "no " + structureId + " within " + CompassConfig.searchRadius
                            + " blocks (" + elapsed + " ms)");
                } else {
                    reply(sender, StructureNameUtil.displayName(result.getStructureId())
                            + " at (" + result.getPos().getX() + ", " + result.getPos().getY()
                            + ", " + result.getPos().getZ() + "), " + Math.round(result.getDistance())
                            + " blocks away, found by " + result.getSource()
                            + " (" + elapsed + " ms)");
                }
                return;
            }

            case "search": {
                if (args.length < 2) {
                    throw new CommandException("usage: /rlcompass search <structureId>");
                }
                // Same code path the item takes, including the packet back to the client.
                EntityPlayerMP player = getCommandSenderAsPlayer(sender);
                StructureResult result = ItemExplorersCompass.search(player, args[1]);
                if (result == null) {
                    reply(sender, "nothing found for " + args[1]);
                } else {
                    reply(sender, "sent to client: " + result);
                }
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

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender,
                                          String[] args, BlockPos targetPos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, SUBCOMMANDS.toArray(new String[0]));
        }
        if (args.length == 2 && "find".equalsIgnoreCase(args[0])) {
            return getListOfStringsMatchingLastWord(args,
                    StructureLocatorRegistry.get().getSelectableIds().toArray(new String[0]));
        }
        return Collections.emptyList();
    }
}
