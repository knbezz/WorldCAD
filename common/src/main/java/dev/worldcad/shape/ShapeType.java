package dev.worldcad.shape;

import java.util.Locale;

/**
 * Non-cubic block shapes (WorldCAD Studio). Pure Java: the same definitions drive the in-game
 * model, the collision box and the exported mesh, so what you see is what you print.
 * <p>
 * Orientation index: {@code facing + 4 * half} for the stair-like shapes (facing 0..3 =
 * north, east, south, west; half 0 = bottom, 1 = top), and the axis 0..2 (x, y, z) for {@link #PILLAR}.
 */
public enum ShapeType {
    /** 45° ramp, high side toward {@code facing}. */
    SLOPE(8),
    /** Outer (convex) corner of two slopes: a pyramid corner. */
    SLOPE_OUTER(8),
    /** Inner (concave) corner of two slopes. */
    SLOPE_INNER(8),
    /** Convex quarter cylinder (radius 1 block), high side toward {@code facing}. */
    ROUND_EDGE(8),
    /** 1/8 sphere (radius 1 block); joins two rounded edges seamlessly. */
    ROUND_CORNER(8),
    /** Cylinder of radius 7/16 along an axis. */
    PILLAR(3);

    public final int orientations;

    ShapeType(int orientations) {
        this.orientations = orientations;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
