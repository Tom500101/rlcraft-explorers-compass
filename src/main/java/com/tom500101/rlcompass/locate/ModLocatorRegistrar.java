package com.tom500101.rlcompass.locate;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.tom500101.rlcompass.locate.impl.BattleTowersLocator;
import com.tom500101.rlcompass.locate.impl.CacheLocator;
import com.tom500101.rlcompass.locate.impl.DoomlikeLocator;
import com.tom500101.rlcompass.locate.impl.DragonRoostLocator;
import com.tom500101.rlcompass.locate.impl.LycanitesDungeonLocator;
import com.tom500101.rlcompass.locate.impl.RecurrentComplexLocator;
import com.tom500101.rlcompass.locate.impl.RoguelikeLocator;
import com.tom500101.rlcompass.locate.impl.RuinsLocator;
import com.tom500101.rlcompass.locate.impl.ShivaxiLocator;

/**
 * Registers adapters for mods that expose their generated structures directly but have no
 * {@code MapGenStructure} (see {@code docs/probe-result.md} §3).
 */
public final class ModLocatorRegistrar {

    private static final Logger LOG = LogManager.getLogger("rlcompass");

    private ModLocatorRegistrar() {
    }

    public static void registerAll() {
        StructureLocatorRegistry registry = StructureLocatorRegistry.get();
        registry.register(new BattleTowersLocator());
        registry.register(new RuinsLocator());
        registry.register(new RecurrentComplexLocator());
        registry.register(new RoguelikeLocator());
        registry.register(new DoomlikeLocator());
        // Ice and Fire + Lycanites Mobs: no MapGenStructure, no position list for roosts. Both read
        // the world's region files (Lycanites prefers its own DungeonInstance records first).
        registry.register(new DragonRoostLocator());
        registry.register(new LycanitesDungeonLocator());
        // Ultra-rare Recurrent Complex structures (Shivaxi). Replays RC's own chunk random instead
        // of scanning blocks, so it is the only adapter here that does real computation per chunk.
        registry.register(new ShivaxiLocator());
        // Lower priority: a mirror of the above, used when a mod has not finished loading its own
        // index yet. Registered last so the log reads in evaluation order.
        registry.register(new CacheLocator());
        LOG.info("[REGISTRY] mod adapters registered, ids = {}", registry.getSupportedIds());
    }
}
