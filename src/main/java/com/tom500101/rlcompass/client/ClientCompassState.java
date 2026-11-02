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
    private static String variantId;
    private static boolean variantConfirmed;

    private ClientCompassState() {
    }

    public static void setTarget(String id, BlockPos target, int dim) {
        setTarget(id, target, dim, null, false);
    }

    public static void setTarget(String id, BlockPos target, int dim, String variant, boolean confirmed) {
        if (target == null) {
            clear();
            return;
        }
        structureId = id == null ? "" : id;
        pos = target.toImmutable();
        dimension = dim;
        variantId = variant;
        variantConfirmed = confirmed;
        updatedAt = System.currentTimeMillis();
    }

    public static void clear() {
        structureId = "";
        pos = null;
        variantId = null;
        variantConfirmed = false;
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

    /** Requested variant id, or {@code null} when the target is the parent structure. */
    public static String getVariantId() {
        return variantId;
    }

    public static boolean isVariantConfirmed() {
        return variantConfirmed;
    }
}
