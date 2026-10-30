package com.tom500101.rlcompass.locate;

import net.minecraft.util.math.BlockPos;

/**
 * An immutable located structure: what was found, where, and which adapter found it.
 */
public final class StructureResult {

    private final String structureId;
    private final BlockPos pos;
    private final String source;
    private final double distanceSq;

    public StructureResult(String structureId, BlockPos pos, String source, double distanceSq) {
        if (structureId == null) {
            throw new IllegalArgumentException("structureId must not be null");
        }
        if (pos == null) {
            throw new IllegalArgumentException("pos must not be null");
        }
        this.structureId = structureId;
        this.pos = pos.toImmutable();
        this.source = source == null ? "unknown" : source;
        this.distanceSq = distanceSq;
    }

    /**
     * Builds a result, deriving the squared distance from {@code from}. Locators should prefer this
     * so that the distance always refers to the position the player searched from.
     */
    public static StructureResult of(String structureId, BlockPos pos, String source, BlockPos from) {
        return new StructureResult(structureId, pos, source, horizontalDistanceSq(pos, from));
    }

    /**
     * Squared horizontal distance between two positions.
     *
     * <p>Horizontal only: the plan ranks structures "nearest first" from the player's point of view,
     * and a village 200 blocks away but 40 blocks underground must not outrank one 210 blocks away
     * at surface level.
     */
    public static double horizontalDistanceSq(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    /**
     * Picks the nearest of {@code candidates} within {@code radius} and wraps it in a result.
     * Returns {@code null} when the list is empty or nothing is close enough.
     */
    public static StructureResult nearestOf(String structureId, java.util.List<BlockPos> candidates,
                                            BlockPos playerPos, int radius, String source) {
        if (candidates == null || candidates.isEmpty() || playerPos == null) {
            return null;
        }
        double maxDistanceSq = (double) radius * (double) radius;
        double bestDistanceSq = Double.MAX_VALUE;
        BlockPos best = null;
        for (BlockPos candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            double distanceSq = horizontalDistanceSq(candidate, playerPos);
            if (distanceSq > maxDistanceSq || distanceSq >= bestDistanceSq) {
                continue;
            }
            bestDistanceSq = distanceSq;
            best = candidate;
        }
        return best == null ? null : new StructureResult(structureId, best, source, bestDistanceSq);
    }

    public String getStructureId() {
        return structureId;
    }
    public BlockPos getPos() {
        return pos;
    }

    public String getSource() {
        return source;
    }

    public double getDistanceSq() {
        return distanceSq;
    }

    public double getDistance() {
        return Math.sqrt(distanceSq);
    }

    @Override
    public String toString() {
        return structureId + " @ (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")"
                + " via " + source + " dist=" + Math.round(getDistance());
    }
}
