package com.tom500101.rlcompass.util;

import java.util.Locale;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Landing spot for 作者故乡 ({@code P_IceCity}), and for that structure only.
 *
 * <p>Every other structure keeps {@link SurfaceTeleport}'s rule — "the first standable block found
 * while walking down from the top of the column". That rule fails here: the ice city is sunken, its
 * ground floor sits below the ice plains surface and its atrium is open to the sky, so the top-down
 * walk passes through the whole building and stops on the basement floor. Players were teleported
 * into the ice cavern underneath instead of onto the city.
 *
 * <p>This class therefore anchors the search to the structure's own Y: the window reaches well above
 * the anchor so a roof or an upper floor wins over a basement one, and only slightly below it so the
 * player is never dropped under the building. A column with nothing walkable inside the window (the
 * atrium itself) is skipped and the ring walk continues outwards, which lands the player on a
 * neighbouring platform of the same level instead of at the bottom.
 *
 * <p>Kept in a separate class on purpose: the generic rule is shared by dragon caves, dungeons, sky
 * cities and the End structures, and none of them should inherit this exception.
 */
public final class IceCityTeleport {

    /** How far from the requested column a landing spot may be. */
    private static final int SEARCH_RADIUS = 16;

    /** Never place the player below this Y. */
    private static final int MIN_Y = 2;

    /** The window's lower edge relative to the structure's Y; keeps the basement out. */
    private static final int BELOW = 6;

    /** The window's upper edge; high enough to prefer the roof over the foundation. */
    private static final int ABOVE = 36;

    private IceCityTeleport() {
    }

    /**
     * True when {@code structureId} names the ice city.
     *
     * <p>The warehouse stores {@code rlcompass:icecity} for scan results and {@code P_IceCity} for
     * entries that came from Recurrent Complex's own records, so both shapes are accepted.
     */
    public static boolean matches(String structureId) {
        if (structureId == null) {
            return false;
        }
        String id = structureId.trim().toLowerCase(Locale.ROOT);
        return id.endsWith("icecity") || id.endsWith("p_icecity");
    }

    /**
     * Returns a standing position on the city, or {@code null} when the window holds nothing usable
     * (the caller then falls back to the generic surface rule).
     */
    public static BlockPos find(World world, int x, int y, int z) {
        if (world == null) {
            return null;
        }
        int height = world.getActualHeight();
        if (y <= MIN_Y || y >= height - 2) {
            return null;
        }
        int maxY = Math.min(y + ABOVE, height - 2);
        int minY = Math.max(MIN_Y, y - BELOW);
        for (int radius = 0; radius <= SEARCH_RADIUS; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    BlockPos spot = findInColumn(world, x + dx, z + dz, minY, maxY);
                    if (spot != null) {
                        return spot;
                    }
                }
            }
        }
        return null;
    }

    /** One column, top down, inside {@code [minY, maxY]}. The result is where the feet go. */
    private static BlockPos findInColumn(World world, int x, int z, int minY, int maxY) {
        BlockPos anchor = new BlockPos(x, 0, z);
        if (!world.isBlockLoaded(anchor)) {
            world.getChunk(x >> 4, z >> 4);
        }
        int start = Math.min(Math.min(world.getActualHeight() - 1, world.getHeight(x, z) + 1), maxY);
        for (int y = start; y >= minY; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            IBlockState state = world.getBlockState(pos);
            Material material = state.getMaterial();

            if (material == Material.AIR) {
                continue;
            }
            if (material.isLiquid() || isHazard(state.getBlock())) {
                // Water or lava inside the window: this column is not a landing pad.
                return null;
            }
            if (!material.isSolid() || isTreePart(state.getBlock())) {
                continue;
            }
            if (isClear(world, pos.up()) && isClear(world, pos.up(2))) {
                return pos.up();
            }
            // Solid right above the ground (a ceiling): keep looking downwards inside the window.
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

    /** Leaves and logs are skipped so a tree growing through the city does not become the spot. */
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
