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
import com.tom500101.rlcompass.locate.impl.RcUltraRareLocator;

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
        // Recurrent Complex structures located by replaying RC's own chunk random instead of
        // scanning blocks. The ultrarare trio is joined by the rare / adventure structures from the
        // hot list; only the category gate and biome expression differ between them.
        registry.register(new RcUltraRareLocator(RcUltraRareLocator.SHIVAXI));
        registry.register(new RcUltraRareLocator(RcUltraRareLocator.AUTHOR_HALL));
        registry.register(new RcUltraRareLocator(RcUltraRareLocator.ICE_CITY));
        registry.register(new RcUltraRareLocator(RcUltraRareLocator.AERIVETH));
        registry.register(new RcUltraRareLocator(RcUltraRareLocator.SANCTUARY));
        registry.register(new RcUltraRareLocator(RcUltraRareLocator.EROLITH));
        registry.register(new RcUltraRareLocator(RcUltraRareLocator.PLAINS_CITY));
        registry.register(new RcUltraRareLocator(RcUltraRareLocator.MERIDOR));
        registry.register(new RcUltraRareLocator(RcUltraRareLocator.ISLAND_CITY));
        registry.register(new RcUltraRareLocator(RcUltraRareLocator.FOREST_STRONGHOLD));
        registry.register(new RcUltraRareLocator(RcUltraRareLocator.CRYSTAL_SHRINE));
        registry.register(new RcUltraRareLocator(RcUltraRareLocator.CORRUPTED_ISLAND));
        registry.register(new RcUltraRareLocator(RcUltraRareLocator.DEFILED_HUT));
        // Lower priority: a mirror of the above, used when a mod has not finished loading its own
        // index yet. Registered last so the log reads in evaluation order.
        registry.register(new CacheLocator());
        LOG.info("[REGISTRY] mod adapters registered, ids = {}", registry.getSupportedIds());
    }
}
