package com.tom500101.rlcompass.locate;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.tom500101.rlcompass.locate.impl.MapGenLocator;
import com.tom500101.rlcompass.locate.impl.MineshaftLocator;

/**
 * Registers the standard {@code MapGenStructure} adapters.
 *
 * <p>Field names come from {@code docs/probe-result.md} §2, extracted from the SRG-named MC jar
 * ({@code build/rfg/srg_merged_minecraft.jar}), not from memory. The MCP names in the same table are
 * carried along as a fallback.
 */
public final class MapGenLocatorRegistrar {

    private static final Logger LOG = LogManager.getLogger("rlcompass");

    /** { SRG field name, MCP field name, structure id } */
    private static final String[][] FIELDS = {
            {"field_186004_w", "strongholdGenerator", "minecraft:stronghold"},
            {"field_186005_x", "villageGenerator", "minecraft:village"},
            {"field_186006_y", "mineshaftGenerator", "minecraft:mineshaft"},
            {"field_186007_z", "scatteredFeatureGenerator", "minecraft:temple"},
            {"field_185980_B", "oceanMonumentGenerator", "minecraft:monument"},
            {"field_191060_C", "woodlandMansionGenerator", "minecraft:mansion"},
    };

    private MapGenLocatorRegistrar() {
    }

    public static void registerAll() {
        StructureLocatorRegistry registry = StructureLocatorRegistry.get();
        for (String[] row : FIELDS) {
            if ("minecraft:mineshaft".equals(row[2])) {
                // Vanilla's own mineshaft search blocks the server thread for ~9 s; see
                // MineshaftLocator for the reproduction of why and the bounded replacement.
                registry.register(new MineshaftLocator(row[0], row[1], row[2]));
            } else {
                registry.register(new MapGenLocator(row[0], row[1], row[2]));
            }
        }
        LOG.info("[REGISTRY] MapGen adapters registered, ids = {}", registry.getSupportedIds());
    }
}
