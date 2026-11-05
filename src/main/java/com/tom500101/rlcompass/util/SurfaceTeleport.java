package com.tom500101.rlcompass.util;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Finds a safe standing spot on the surface above a pair of coordinates.
 *
 * <p>The warehouse teleports players to structures they have already located, and the stored
 * position is the structure's origin — for a dragon cave or a dungeon that is inside a mountain.
 * Dropping the player at that exact Y would bury them, so the teleport always targets the surface
 * above the column instead. "Surface" here means: the topmost solid block of the column, with two
 * blocks of breathing room above it, on ground that is not a liquid, a cactus, magma, fire or a
 * portal.
 *
 * <p>If the exact column is unusable (ocean, lava sea, a tree trunk, the void) the search walks
 * outwards in rings up to {@link #SEARCH_RADIUS} blocks, so an ocean monument still lands on the
 * nearest shore rather than in the water.
 */
public final class SurfaceTeleport {

    /** How far from the requested column a landing spot may be. */
    private static final int SEARCH_RADIUS = 16;

    /** Never place the player below this Y; the bottom of the world is not a valid landing spot. */
    private static final int MIN_Y = 2;

    /** The nether's walkable space ends under the bedrock roof at Y=127. */
    private static final int NETHER_MAX_Y = 125;

    private SurfaceTeleport() {
    }

    /**
     * Returns a safe standing position near {@code (x, z)}, or {@code null} when every column within
     * {@link #SEARCH_RADIUS} is water, lava, a hazard or empty.
     */
    public static BlockPos find(World world, int x, int z) {
        if (world == null) {
            return null;
        }
        for (int radius = 0; radius <= SEARCH_RADIUS; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    BlockPos spot = findInColumn(world, x + dx, z + dz);
                    if (spot != null) {
                        return spot;
                    }
                }
            }
        }
        return null;
    }

    /** Scans one column from the top down; the returned position is where the player's feet go. */
    private static BlockPos findInColumn(World world, int x, int z) {
        BlockPos anchor = new BlockPos(x, 0, z);
        if (!world.isBlockLoaded(anchor)) {
            // Teleporting there loads the chunk anyway; do it before reading blocks so the scan sees
            // generated terrain instead of an empty chunk.
            world.getChunk(x >> 4, z >> 4);
        }

        int ceiling = world.provider.getDimension() == -1 ? NETHER_MAX_Y : world.getActualHeight() - 1;
        int start = Math.min(ceiling, world.getHeight(x, z) + 1);
        for (int y = start; y >= MIN_Y; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            IBlockState state = world.getBlockState(pos);
            Material material = state.getMaterial();

            if (material == Material.AIR) {
                continue;
            }
            if (material.isLiquid()) {
                // Ocean or lava sea: the player would land in it, and the seabed is not the surface.
                return null;
            }
            if (isHazard(state.getBlock())) {
                return null;
            }
            if (!material.isSolid()) {
                // Tall grass, flowers, vines: look through them at the ground below.
                continue;
            }
            if (isTreePart(state.getBlock())) {
                // Canopy or trunk: keep descending to the ground the tree stands on.
                continue;
            }
            if (isClear(world, pos.up()) && isClear(world, pos.up(2))) {
                return pos.up();
            }
            // Something solid sits directly above the ground (overhang, low roof, trunk).
            return null;
        }
        return null;
    }

    /** Blocks that are solid but must never be a landing pad. */
    private static boolean isHazard(Block block) {
        return block == Blocks.FIRE
                || block == Blocks.LAVA
                || block == Blocks.FLOWING_LAVA
                || block == Blocks.CACTUS
                || block == Blocks.MAGMA
                || block == Blocks.WEB
                || block == Blocks.BARRIER
                || block == Blocks.PORTAL
                || block == Blocks.END_PORTAL;
    }

    /** Leaves and logs are skipped so a jungle canopy does not become the landing spot. */
    private static boolean isTreePart(Block block) {
        return block == Blocks.LEAVES
                || block == Blocks.LEAVES2
                || block == Blocks.LOG
                || block == Blocks.LOG2;
    }

    /** Air, or a non-solid plant the player can stand in without suffocating. */
    private static boolean isClear(World world, BlockPos pos) {
        IBlockState state = world.getBlockState(pos);
        Material material = state.getMaterial();
        if (state.getBlock() == Blocks.FIRE) {
            return false;
        }
        return material == Material.AIR || (!material.isSolid() && !material.isLiquid());
    }
}
