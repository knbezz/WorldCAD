package dev.worldcad.selection;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * A player's corner-to-corner cuboid region. Both corners are inclusive block positions in the same
 * dimension; setting a corner in another dimension discards the other one.
 */
public final class SelectionBox {
    @Nullable private BlockPos pos1;
    @Nullable private BlockPos pos2;
    @Nullable private ResourceKey<Level> dimension;

    public void setCorner(int corner, ResourceKey<Level> dim, BlockPos pos) {
        if (dimension != null && !dimension.equals(dim)) {
            pos1 = pos2 = null;
        }
        dimension = dim;
        if (corner == 1) pos1 = pos.immutable();
        else pos2 = pos.immutable();
    }

    public void clear() {
        pos1 = pos2 = null;
        dimension = null;
    }

    public boolean isComplete() {
        return pos1 != null && pos2 != null;
    }

    @Nullable public BlockPos pos1() { return pos1; }
    @Nullable public BlockPos pos2() { return pos2; }
    @Nullable public ResourceKey<Level> dimension() { return dimension; }

    public BlockPos min() {
        requireComplete();
        return new BlockPos(Math.min(pos1.getX(), pos2.getX()), Math.min(pos1.getY(), pos2.getY()), Math.min(pos1.getZ(), pos2.getZ()));
    }

    public BlockPos max() {
        requireComplete();
        return new BlockPos(Math.max(pos1.getX(), pos2.getX()), Math.max(pos1.getY(), pos2.getY()), Math.max(pos1.getZ(), pos2.getZ()));
    }

    public int sizeX() { return max().getX() - min().getX() + 1; }
    public int sizeY() { return max().getY() - min().getY() + 1; }
    public int sizeZ() { return max().getZ() - min().getZ() + 1; }

    public long volume() {
        return isComplete() ? (long) sizeX() * sizeY() * sizeZ() : 0;
    }

    public String describe() {
        if (!isComplete()) {
            return "pos1=" + fmt(pos1) + ", pos2=" + fmt(pos2);
        }
        return sizeX() + "×" + sizeY() + "×" + sizeZ() + " (" + volume() + " blocks) from " + fmt(min()) + " to " + fmt(max());
    }

    private void requireComplete() {
        if (!isComplete()) throw new IllegalStateException("selection incomplete");
    }

    private static String fmt(@Nullable BlockPos p) {
        return p == null ? "unset" : "(" + p.getX() + ", " + p.getY() + ", " + p.getZ() + ")";
    }
}
