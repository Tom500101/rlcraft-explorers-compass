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
import com.tom500101.rlcompass.locate.impl.AuthorHallFastScan;
import com.tom500101.rlcompass.locate.impl.RcFastScan;
import com.tom500101.rlcompass.locate.impl.RcUltraRareLocator;
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
                    "shivaxi", "scan");

    @Override
    public String getName() {
        return "rlcompass";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/rlcompass <list|biome|find <id>|scan <id> [radius|cancel]|turbo [on|off]>";
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
                    reply(sender, stopAllScans());
                    return;
                }
                if (RcUltraRareLocator.deepScanActive() || RcFastScan.active()
                        || AuthorHallFastScan.active()) {
                    throw new CommandException(
                            "another scan is running; /rlcompass scan cancel it first");
                }
                EntityPlayerMP player = getCommandSenderAsPlayer(sender);
                int radiusChunks = parseScanRadius(args.length >= 2 ? args[1] : null);
                // A structure RC already recorded is authoritative. Re-scanning it would skip the
                // chunk as "already generated" and report nothing, which reads as "not found".
                BlockPos recordedShivaxi = RcUltraRareLocator.knownHit(RcUltraRareLocator.SHIVAXI,
                        world, player.getPosition(), radiusChunks * 16);
                if (recordedShivaxi != null) {
                    reply(sender, recordedHint(RcUltraRareLocator.SHIVAXI, recordedShivaxi));
                    return;
                }
                // Same tile scan as `scan shivaxi`: the RNG prefilter cannot see ultrarare rolls.
                reply(sender, RcUltraRareLocator.startDeepScan(RcUltraRareLocator.SHIVAXI,
                        world, player, player.getPosition(), radiusChunks));
                return;
            }

            case "scan": {
                // Generic form: /rlcompass scan <id> [radius|cancel]. The id may be the compass id
                // (rlcompass:authorshall) or RC's registry name (RLCraft_2_9_Update_Build).
                if (args.length >= 2 && "cancel".equalsIgnoreCase(args[1])) {
                    reply(sender, stopAllScans());
                    return;
                }
                if (args.length < 2) {
                    throw new CommandException("usage: /rlcompass scan <id> [radius] [all|cancel]"
                            + " — ids: shivaxi, authorshall, icecity, aeriveth, sanctuary,"
                            + " erolith, plainscity, meridor, islandcity, foreststronghold, crystalshrine, corruptedisland, defiledhut");
                }
                RcUltraRareLocator.Target target = RcUltraRareLocator.targetFor(args[1]);
                if (target == null) {
                    throw new CommandException("unknown structure: " + args[1]
                            + " (try shivaxi, authorshall, icecity, aeriveth, sanctuary,"
                            + " erolith, plainscity, meridor, islandcity, foreststronghold, crystalshrine, corruptedisland or defiledhut)");
                }
                // Default stops at the first (nearest) candidate; a trailing "all" maps the whole
                // square like before, which is only useful for statistics.
                boolean firstOnly = args.length < 4 || !"all".equalsIgnoreCase(args[3]);
                EntityPlayerMP player = getCommandSenderAsPlayer(sender);
                int radiusChunks = parseScanRadius(args.length >= 3 ? args[2] : null);
                // Same short-circuit the GUI path has had since §44: a recorded structure is a fact,
                // and re-scanning it would silently skip the finished chunk. `all` still forces a
                // full pass, which is what the statistics runs want.
                if (firstOnly) {
                    BlockPos recorded = RcUltraRareLocator.knownHit(target, world,
                            player.getPosition(), radiusChunks * 16);
                    if (recorded != null) {
                        reply(sender, recordedHint(target, recorded));
                        return;
                    }
                }
                // Three scanners, restored to the 2026-11-05 layout on user request:
                //   authorshall -> AuthorHallFastScan (dedicated file, 64 draws @ 1e-4, 4 ms/tick)
                //   rare        -> RcFastScan (parameterised RNG prefilter)
                //   everything else (shivaxi / icecity / adventure) -> generic tile deep scan
                // See HANDOFF §38 / §39.
                boolean authorHall = "rlcompass:authorshall".equalsIgnoreCase(target.id);
                boolean fastCategory = "rare".equalsIgnoreCase(target.category);
                if (authorHall) {
                    if (RcFastScan.active() || RcUltraRareLocator.deepScanActive()) {
                        throw new CommandException(
                                "another scan is running; /rlcompass scan cancel it first");
                    }
                    reply(sender, AuthorHallFastScan.start(world, player,
                            player.getPosition(), radiusChunks, firstOnly));
                } else if (fastCategory) {
                    if (AuthorHallFastScan.active() || RcUltraRareLocator.deepScanActive()) {
                        throw new CommandException(
                                "another scan is running; /rlcompass scan cancel it first");
                    }
                    reply(sender, RcFastScan.start(target, world, player, player.getPosition(),
                            radiusChunks, firstOnly));
                } else {
                    if (AuthorHallFastScan.active() || RcFastScan.active()) {
                        throw new CommandException(
                                "a fast scan is running; /rlcompass scan cancel it first");
                    }
                    reply(sender, RcUltraRareLocator.startDeepScan(target, world, player,
                            player.getPosition(), radiusChunks, firstOnly));
                }
                return;
            }

            case "turbo": {
                // Experimental switch for the three ultra-rare targets only. It widens the
                // per-tick slice of AuthorHallFastScan / the generic tile scan from 4-8 ms to
                // 40 ms, which is what actually decides their wall-clock time: at 8 ms the scan
                // sits idle for 84% of every tick. `rare` (RcFastScan) is deliberately untouched.
                if (args.length < 2) {
                    reply(sender, "turbo is " + (CompassConfig.turboScan ? "ON" : "OFF")
                            + " (40 ms/tick for authorshall / icecity / shivaxi);"
                            + " use /rlcompass turbo on|off");
                    return;
                }
                String mode = args[1].toLowerCase(java.util.Locale.ROOT);
                boolean enable;
                if ("on".equals(mode) || "true".equals(mode) || "1".equals(mode)) {
                    enable = true;
                } else if ("off".equals(mode) || "false".equals(mode) || "0".equals(mode)) {
                    enable = false;
                } else {
                    throw new CommandException("usage: /rlcompass turbo [on|off]");
                }
                CompassConfig.turboScan = enable;
                try {
                    net.minecraftforge.common.config.ConfigManager.sync(
                            com.tom500101.rlcompass.RLCompassMod.MODID,
                            net.minecraftforge.common.config.Config.Type.INSTANCE);
                } catch (Throwable ignored) {
                    // an unwritable config must not break the command; the value still applies
                }
                // sync() re-reads the file and can put the old value back; write it again so the
                // toggle takes effect on the very next tick instead of the next launch.
                CompassConfig.turboScan = enable;
                boolean running = AuthorHallFastScan.active() || RcFastScan.active()
                        || RcUltraRareLocator.deepScanActive();
                reply(sender, enable
                        ? "turbo ON: ultra-rare scans may now use 40 ms of every tick"
                          + " (expect TPS 14-16 while one runs); only authorshall / icecity /"
                          + " shivaxi are affected"
                        : "turbo OFF: ultra-rare scans are back to their normal 4-8 ms per tick");
                if (running) {
                    reply(sender, "the running scan switches from the next tick, no restart needed");
                }
                return;
            }

            case "biome": {
                // The one question a scan cannot answer: am I standing in the right place?
                // eligible = 0 means the biome never matched, and no radius will fix that.
                for (String line : RcUltraRareLocator.biomeReport(world, origin)) {
                    reply(sender, line);
                }
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
                reply(sender, RcUltraRareLocator.diagnose(world, newest));
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

    /** One-line answer for "this structure is already in the save" instead of running a scan. */
    private static String recordedHint(RcUltraRareLocator.Target target, BlockPos pos) {
        return "already recorded: " + target.rcName + " at (" + pos.getX() + ", "
                + pos.getY() + ", " + pos.getZ() + ") — append `all` to force a full re-scan";
    }

    /** Stops whichever scanner is running; each reports "no scan" when idle. */
    private static String stopAllScans() {
        String[] results = {AuthorHallFastScan.stop(), RcFastScan.stop(),
                RcUltraRareLocator.stopDeepScan()};
        StringBuilder text = new StringBuilder();
        for (String result : results) {
            if ("no scan is running".equals(result)) {
                continue;
            }
            if (text.length() > 0) {
                text.append(" | ");
            }
            text.append(result);
        }
        return text.length() == 0 ? "no scan is running" : text.toString();
    }

    /** Clamps the optional radius argument; a missing or malformed value keeps the 1200 default. */
    private static int parseScanRadius(String raw) {
        int radius = 1200;
        if (raw == null) {
            return radius;
        }
        try {
            radius = Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {
            return radius;
        }
        return Math.max(8, Math.min(RcUltraRareLocator.MAX_CHUNK_RADIUS, radius));
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
