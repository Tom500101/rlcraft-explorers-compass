package com.tom500101.rlcompass.client;

import net.minecraft.util.math.BlockPos;

/**
 * The compass target the client is currently tracking.
 *
 * <p>Deliberately free of client-only imports so that the packet handler can touch it without
 * pulling {@code net.minecraft.client.*} into a class that also loads on a dedicated server.
 * Rendering lives in the HUD renderer, not here.
 */
public final class ClientCompassState {

    private static String structureId = "";
    private static BlockPos pos;
    private static int dimension;
    private static long updatedAt;

    private ClientCompassState() {
    }

    public static void setTarget(String id, BlockPos target, int dim) {
        if (target == null) {
            clear();
            return;
        }
        structureId = id == null ? "" : id;
        pos = target.toImmutable();
        dimension = dim;
        updatedAt = System.currentTimeMillis();
    }

    public static void clear() {
        structureId = "";
        pos = null;
        updatedAt = 0L;
    }

    public static boolean hasTarget() {
        return pos != null;
    }

    public static String getStructureId() {
        return structureId;
    }

    public static BlockPos getPos() {
        return pos;
    }

    public static int getDimension() {
        return dimension;
    }

    public static long getUpdatedAt() {
        return updatedAt;
    }
}
