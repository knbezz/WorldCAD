package dev.worldcad.voxel;

import dev.worldcad.shape.ShapeType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * Dense, palette-indexed snapshot of a cuboid region, in Minecraft axes (X east, Y up, Z south).
 * <p>
 * Cell value 0 means empty; any other value {@code v} refers to {@code materials().get(v - 1)}.
 * Shape blocks (WorldCAD Studio) are stored separately and leave their cell value at 0, so the
 * cube mesher treats them as "not a full block".
 * The grid is filled on the server thread and then handed to the export thread; after that
 * hand-off (which is a happens-before edge via the executor) it is only read.
 */
public final class VoxelGrid {
    public static final int MAX_MATERIALS = Short.MAX_VALUE;
    /** Mesh vertex keys pack 21 bits per axis at 1/2048 block precision. */
    public static final int MAX_SIDE = 1023;

    public final int sx, sy, sz;
    private final short[] cells;
    private final List<Material> materials = new ArrayList<>();
    /** cell index → {@code material << 8 | type << 4 | orientation}. Sparse: shapes are usually few. */
    private final Map<Integer, Integer> shapes = new HashMap<>();

    public VoxelGrid(int sx, int sy, int sz) {
        if (sx <= 0 || sy <= 0 || sz <= 0) throw new IllegalArgumentException("empty grid");
        if (sx > MAX_SIDE || sy > MAX_SIDE || sz > MAX_SIDE) {
            throw new IllegalArgumentException("each side must be at most " + MAX_SIDE + " blocks");
        }
        this.sx = sx;
        this.sy = sy;
        this.sz = sz;
        this.cells = new short[sx * sy * sz];
    }

    /** Registers a material and returns its cell value (palette index + 1). */
    public int addMaterial(Material m) {
        if (materials.size() >= MAX_MATERIALS) throw new IllegalStateException("too many materials");
        materials.add(m);
        return materials.size();
    }

    public List<Material> materials() {
        return Collections.unmodifiableList(materials);
    }

    public void set(int x, int y, int z, int value) {
        cells[index(x, y, z)] = (short) value;
    }

    /** Places a shape block; {@code value} is a material cell value as returned by {@link #addMaterial}. */
    public void setShape(int x, int y, int z, ShapeType type, int orientation, int value) {
        if (orientation < 0 || orientation >= type.orientations) throw new IllegalArgumentException("bad orientation");
        int i = index(x, y, z);
        cells[i] = 0;
        shapes.put(i, value << 8 | type.ordinal() << 4 | orientation);
    }

    /** Cell value of a full block, or 0 (air, shape block, or outside: the grid is padded with air). */
    public int get(int x, int y, int z) {
        if (!inside(x, y, z)) return 0;
        return cells[index(x, y, z)];
    }

    /** Packed shape code at a cell, or -1. Decode with {@link #shapeType}, {@link #shapeOrientation}, {@link #shapeValue}. */
    public int shape(int x, int y, int z) {
        if (shapes.isEmpty() || !inside(x, y, z)) return -1;
        Integer code = shapes.get(index(x, y, z));
        return code == null ? -1 : code;
    }

    /** Material cell value for full blocks and shape blocks alike (0 = empty). */
    public int value(int x, int y, int z) {
        int v = get(x, y, z);
        if (v != 0) return v;
        int s = shape(x, y, z);
        return s < 0 ? 0 : shapeValue(s);
    }

    public static ShapeType shapeType(int code) { return ShapeType.values()[(code >> 4) & 0xF]; }
    public static int shapeOrientation(int code) { return code & 0xF; }
    public static int shapeValue(int code) { return code >>> 8; }

    /** Calls {@code action} with the cell index of every shape block (decode with {@link #cellX} etc.). */
    public void forEachShape(IntConsumer action) {
        shapes.keySet().forEach(action::accept);
    }

    public int shapeCount() { return shapes.size(); }
    public int cellX(int index) { return index % sx; }
    public int cellY(int index) { return (index / sx) % sy; }
    public int cellZ(int index) { return index / (sx * sy); }

    public boolean solid(int x, int y, int z) {
        return get(x, y, z) != 0;
    }

    public long solidCount() {
        long n = 0;
        for (short c : cells) if (c != 0) n++;
        return n;
    }

    private boolean inside(int x, int y, int z) {
        return x >= 0 && y >= 0 && z >= 0 && x < sx && y < sy && z < sz;
    }

    private int index(int x, int y, int z) {
        return x + sx * (y + sy * z);
    }
}
