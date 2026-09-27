package dev.worldcad.export;

import dev.worldcad.voxel.Material;
import dev.worldcad.voxel.VoxelGrid;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Copies world blocks into a {@link VoxelGrid}. MUST run on the server thread: world access is not
 * thread-safe. Everything after this (meshing, file writing) runs off-thread on the snapshot.
 */
public final class VoxelCapture {
    /** Neutral grey for blocks whose map color is "none" (glass, barriers...). */
    private static final int FALLBACK_RGB = 0x9E9E9E;

    private VoxelCapture() {
    }

    /** Throws if any chunk in range is unloaded: reading it would force a synchronous load/generation. */
    public static void requireLoaded(ServerLevel level, BlockPos min, BlockPos max) {
        for (int cx = min.getX() >> 4; cx <= max.getX() >> 4; cx++) {
            for (int cz = min.getZ() >> 4; cz <= max.getZ() >> 4; cz++) {
                if (!level.hasChunk(cx, cz)) {
                    throw new IllegalStateException("Part of the selection isn't loaded (chunk " + cx + ", " + cz + "); move closer to it.");
                }
            }
        }
    }

    public static VoxelGrid capture(ServerLevel level, BlockPos min, BlockPos max, ExportOptions opts) {
        requireLoaded(level, min, max);
        int sx = max.getX() - min.getX() + 1;
        int sy = max.getY() - min.getY() + 1;
        int sz = max.getZ() - min.getZ() + 1;
        VoxelGrid grid = new VoxelGrid(sx, sy, sz);
        // STL: every block is the same plain solid, so the mesher can merge faces across block types.
        // 3MF: one material per Block (not per BlockState) so e.g. stair rotations share a color entry.
        boolean singleMaterial = opts.format() == ExportOptions.Format.STL;
        int solidValue = singleMaterial ? grid.addMaterial(new Material("solid", FALLBACK_RGB)) : 0;
        Map<Block, Integer> palette = new IdentityHashMap<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        boolean any = false;

        for (int z = 0; z < sz; z++) {
            for (int y = 0; y < sy; y++) {
                for (int x = 0; x < sx; x++) {
                    pos.set(min.getX() + x, min.getY() + y, min.getZ() + z);
                    BlockState state = level.getBlockState(pos);
                    if (!include(level, pos, state, opts)) continue;
                    any = true;

                    Block block = state.getBlock();
                    Integer value = singleMaterial ? solidValue : palette.get(block);
                    if (value == null) {
                        int rgb = state.getMapColor(level, pos).col;
                        value = grid.addMaterial(new Material(String.valueOf(BuiltInRegistries.BLOCK.getKey(block)), rgb == 0 ? FALLBACK_RGB : rgb));
                        palette.put(block, value);
                    }
                    grid.set(x, y, z, value);
                }
            }
        }
        if (!any) throw new IllegalStateException("Selection contains no exportable blocks.");
        return grid;
    }

    private static boolean include(ServerLevel level, BlockPos pos, BlockState state, ExportOptions opts) {
        if (state.isAir()) return false;
        if (state.getBlock() instanceof LiquidBlock) return opts.includeFluids();
        // Waterlogged stairs etc. are kept: their Block is not a LiquidBlock.
        return opts.includeNonSolid() || !state.getCollisionShape(level, pos).isEmpty();
    }
}
