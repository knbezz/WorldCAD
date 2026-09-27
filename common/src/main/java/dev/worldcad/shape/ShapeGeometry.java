package dev.worldcad.shape;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Exact polygon geometry of every {@link ShapeType} in every orientation, in cell-local
 * coordinates [0, 1]³ (Minecraft axes). Polygons are convex and wound CCW seen from outside.
 * <p>
 * Faces lying on a cell side carry that side and a {@link Kind} so the exporter can stitch them
 * to neighbours: identical faces cancel, a full neighbour face gets the exact
 * {@linkplain #complement complement} of a partial one. All coordinates are snapped to 1/{@value #SNAP}
 * of a block, so faces built by different code paths weld bit-for-bit.
 */
public final class ShapeGeometry {
    public static final int SNAP = 2048;
    /** Segments per 90° of arc. Chord error at 10 mm/block: 0.05 mm. */
    public static final int ARC_SEGMENTS = 8;
    public static final double PILLAR_RADIUS = 7.0 / 16.0;

    /** Side indices match {@code net.minecraft.core.Direction#get3DDataValue()}. Opposite side = {@code side ^ 1}. */
    public static final int DOWN = 0, UP = 1, NORTH = 2, SOUTH = 3, WEST = 4, EAST = 5;
    public static final int[][] SIDE_NORMAL = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};

    public enum Kind { FULL, TRIANGLE, QUARTER_DISC, DISC, SURFACE }

    public static final class Face {
        public final double[] xyz;
        /** Cell side this face lies on, or -1 for surfaces inside the cell. */
        public final int side;
        public final Kind kind;
        private final long[] keys; // sorted snapped points, for region equality

        Face(double[] xyz, int side, Kind kind) {
            this.xyz = xyz;
            this.side = side;
            this.kind = kind;
            this.keys = new long[xyz.length / 3];
            for (int i = 0; i < keys.length; i++) keys[i] = key(xyz[3 * i], xyz[3 * i + 1], xyz[3 * i + 2]);
            Arrays.sort(keys);
        }

        public int size() { return xyz.length / 3; }
        public double x(int i) { return xyz[3 * i]; }
        public double y(int i) { return xyz[3 * i + 1]; }
        public double z(int i) { return xyz[3 * i + 2]; }

        /**
         * Triangles covering this polygon, flat xyz × 3 per triangle. Discs fan from their centre
         * (an interior point, never on an edge); everything else fans from vertex 0.
         */
        public double[] triangulate() {
            int n = size();
            if (kind == Kind.DISC) {
                double cx = 0, cy = 0, cz = 0;
                for (int i = 0; i < n; i++) { cx += x(i); cy += y(i); cz += z(i); }
                double[] c = snap(cx / n, cy / n, cz / n);
                double[] out = new double[9 * n];
                for (int i = 0; i < n; i++) {
                    int j = (i + 1) % n;
                    put(out, 9 * i, c[0], c[1], c[2], x(i), y(i), z(i), x(j), y(j), z(j));
                }
                return out;
            }
            double[] out = new double[9 * (n - 2)];
            for (int i = 1; i < n - 1; i++) {
                put(out, 9 * (i - 1), x(0), y(0), z(0), x(i), y(i), z(i), x(i + 1), y(i + 1), z(i + 1));
            }
            return out;
        }
    }

    private static final double[][] ARC = arcTable();
    private static final Face[][][] FACES = new Face[ShapeType.values().length][][];
    private static final Face[][][] BY_SIDE = new Face[ShapeType.values().length][][];

    static {
        for (ShapeType t : ShapeType.values()) {
            FACES[t.ordinal()] = new Face[t.orientations][];
            BY_SIDE[t.ordinal()] = new Face[t.orientations][6];
            List<Face> canonical = canonical(t);
            for (int o = 0; o < t.orientations; o++) {
                Face[] faces = new Face[canonical.size()];
                for (int i = 0; i < faces.length; i++) {
                    faces[i] = transform(canonical.get(i), t, o);
                    if (faces[i].side >= 0) BY_SIDE[t.ordinal()][o][faces[i].side] = faces[i];
                }
                FACES[t.ordinal()][o] = faces;
            }
        }
    }

    private ShapeGeometry() {
    }

    public static Face[] faces(ShapeType type, int orientation) {
        return FACES[type.ordinal()][orientation];
    }

    /** The face lying on {@code side}, or null if the shape doesn't touch that side with an area. */
    public static Face face(ShapeType type, int orientation, int side) {
        return BY_SIDE[type.ordinal()][orientation][side];
    }

    /** True if {@code a} (in its cell) and {@code b} (in the cell at {@code +offset}) cover the same region. */
    public static boolean sameRegion(Face a, Face b, int[] offset) {
        if (a.keys.length != b.keys.length) return false;
        long[] shifted = new long[b.size()];
        for (int i = 0; i < shifted.length; i++) {
            shifted[i] = key(b.x(i) + offset[0], b.y(i) + offset[1], b.z(i) + offset[2]);
        }
        Arrays.sort(shifted);
        return Arrays.equals(a.keys, shifted);
    }

    /**
     * Triangles covering (cell side square) minus {@code partial}, wound to face {@code normal}.
     * This is the visible part of a full face that a partial neighbour face only partly covers.
     */
    public static double[] complement(Face partial, int[] normal) {
        int axis = partial.side >> 1 == 0 ? 1 : partial.side >> 1 == 1 ? 2 : 0; // DOWN/UP→y, N/S→z, W/E→x
        double plane = (partial.side & 1) == 1 ? 1 : 0;
        int u = (axis + 1) % 3, v = (axis + 2) % 3;
        double[][] corners = new double[4][3];
        for (int i = 0; i < 4; i++) {
            corners[i][axis] = plane;
            corners[i][u] = (i == 1 || i == 2) ? 1 : 0;
            corners[i][v] = (i >= 2) ? 1 : 0;
        }
        List<double[]> tris = new ArrayList<>();
        switch (partial.kind) {
            case FULL, SURFACE -> { }
            case TRIANGLE -> {
                double[] m = missingCorner(partial, corners);
                double[] h1 = null, h2 = null;
                for (double[] c : corners) {
                    if (differsInOneAxis(c, m)) { if (h1 == null) h1 = c; else h2 = c; }
                }
                tris.add(concat(m, h1, h2));
            }
            case QUARTER_DISC -> {
                double[] far = missingCorner(partial, corners);
                int n = partial.size(), c0 = -1;
                for (int i = 0; i < n; i++) {
                    if (partial.xyz[3 * i + u] != far[u] && partial.xyz[3 * i + v] != far[v]
                            && isCorner(partial, i, u, v)) c0 = i;
                }
                for (int s = 1; s < n - 1; s++) { // arc = polygon without the disc centre, in order
                    int i = (c0 + s) % n, j = (c0 + s + 1) % n;
                    tris.add(concat(far, point(partial, i), point(partial, j)));
                }
            }
            case DISC -> {
                int n = partial.size();
                final double cu = 0.5, cv = 0.5; // pillars are centred in the cell
                for (int i = 0; i < n; i++) {
                    double[] a = point(partial, i), b = point(partial, (i + 1) % n);
                    double mu = (a[u] + b[u]) / 2 - cu, mv = (a[v] + b[v]) / 2 - cv;
                    tris.add(concat(corner(corners, u, v, mu > 0, mv > 0), a, b));
                    // A point on the disc's u or v axis faces a square edge: fill the strip to that edge.
                    double du = a[u] - cu, dv = a[v] - cv;
                    if (Math.abs(dv) < 1e-9) {
                        tris.add(concat(corner(corners, u, v, du > 0, true), corner(corners, u, v, du > 0, false), a));
                    } else if (Math.abs(du) < 1e-9) {
                        tris.add(concat(corner(corners, u, v, true, dv > 0), corner(corners, u, v, false, dv > 0), a));
                    }
                }
            }
        }
        double[] out = new double[9 * tris.size()];
        for (int t = 0; t < tris.size(); t++) {
            double[] tri = tris.get(t);
            if (dotNormal(tri, normal) < 0) { // wind toward the requested normal
                double[] swapped = {tri[0], tri[1], tri[2], tri[6], tri[7], tri[8], tri[3], tri[4], tri[5]};
                tri = swapped;
            }
            System.arraycopy(tri, 0, out, 9 * t, 9);
        }
        return out;
    }

    // ------------------------------------------------------------------ canonical shapes
    // Canonical frame: facing north (high side at z = 0), bottom half; pillar along y.

    private static List<Face> canonical(ShapeType t) {
        List<Face> f = new ArrayList<>();
        switch (t) {
            case SLOPE -> {
                side(f, DOWN, Kind.FULL, 0, 0, 0, 1, 0, 0, 1, 0, 1, 0, 0, 1);
                side(f, NORTH, Kind.FULL, 0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0);
                side(f, WEST, Kind.TRIANGLE, 0, 0, 0, 0, 0, 1, 0, 1, 0);
                side(f, EAST, Kind.TRIANGLE, 1, 0, 0, 1, 0, 1, 1, 1, 0);
                surface(f, new double[]{0, 1, 1}, 0, 0, 1, 1, 0, 1, 1, 1, 0, 0, 1, 0);
            }
            case SLOPE_OUTER -> { // y <= 1 - max(x, z)
                side(f, DOWN, Kind.FULL, 0, 0, 0, 1, 0, 0, 1, 0, 1, 0, 0, 1);
                side(f, NORTH, Kind.TRIANGLE, 0, 0, 0, 1, 0, 0, 0, 1, 0);
                side(f, WEST, Kind.TRIANGLE, 0, 0, 0, 0, 0, 1, 0, 1, 0);
                surface(f, new double[]{0, 1, 1}, 0, 1, 0, 0, 0, 1, 1, 0, 1);
                surface(f, new double[]{1, 1, 0}, 0, 1, 0, 1, 0, 1, 1, 0, 0);
            }
            case SLOPE_INNER -> { // y <= 1 - min(x, z)
                side(f, DOWN, Kind.FULL, 0, 0, 0, 1, 0, 0, 1, 0, 1, 0, 0, 1);
                side(f, NORTH, Kind.FULL, 0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0);
                side(f, WEST, Kind.FULL, 0, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1, 0);
                side(f, EAST, Kind.TRIANGLE, 1, 0, 0, 1, 1, 0, 1, 0, 1);
                side(f, SOUTH, Kind.TRIANGLE, 0, 0, 1, 0, 1, 1, 1, 0, 1);
                surface(f, new double[]{0, 1, 1}, 0, 1, 0, 1, 1, 0, 1, 0, 1);
                surface(f, new double[]{1, 1, 0}, 0, 1, 0, 1, 0, 1, 0, 1, 1);
            }
            case ROUND_EDGE -> { // y² + z² <= 1, axis along x
                side(f, DOWN, Kind.FULL, 0, 0, 0, 1, 0, 0, 1, 0, 1, 0, 0, 1);
                side(f, NORTH, Kind.FULL, 0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0);
                for (int x = 0; x <= 1; x++) {
                    double[] p = new double[3 * (ARC_SEGMENTS + 2)];
                    p[0] = x; // disc centre on the axis, first so fans start there
                    for (int k = 0; k <= ARC_SEGMENTS; k++) {
                        p[3 * (k + 1)] = x; p[3 * (k + 1) + 1] = ARC[k][1]; p[3 * (k + 1) + 2] = ARC[k][0];
                    }
                    side(f, x == 0 ? WEST : EAST, Kind.QUARTER_DISC, p);
                }
                for (int k = 0; k < ARC_SEGMENTS; k++) {
                    double y0 = ARC[k][1], z0 = ARC[k][0], y1 = ARC[k + 1][1], z1 = ARC[k + 1][0];
                    surface(f, new double[]{0, y0 + y1, z0 + z1}, 0, y0, z0, 1, y0, z0, 1, y1, z1, 0, y1, z1);
                }
            }
            case ROUND_CORNER -> { // x² + y² + z² <= 1
                double[] down = new double[3 * (ARC_SEGMENTS + 2)];
                double[] north = new double[3 * (ARC_SEGMENTS + 2)];
                double[] west = new double[3 * (ARC_SEGMENTS + 2)];
                for (int k = 0; k <= ARC_SEGMENTS; k++) {
                    int i = 3 * (k + 1);
                    down[i] = ARC[k][0]; down[i + 2] = ARC[k][1];            // (cos, 0, sin)
                    north[i] = ARC[k][0]; north[i + 1] = ARC[k][1];          // (cos, sin, 0)
                    west[i + 1] = ARC[k][1]; west[i + 2] = ARC[k][0];        // (0, sin, cos) — same as ROUND_EDGE
                }
                side(f, DOWN, Kind.QUARTER_DISC, down);
                side(f, NORTH, Kind.QUARTER_DISC, north);
                side(f, WEST, Kind.QUARTER_DISC, west);
                // θ from +y (ARC[i] = {cos θ, sin θ}), φ from +x toward +z.
                for (int i = 0; i < ARC_SEGMENTS; i++) {
                    for (int j = 0; j < ARC_SEGMENTS; j++) {
                        double[] a = sphere(i, j), b = sphere(i + 1, j), c = sphere(i + 1, j + 1), d = sphere(i, j + 1);
                        if (i == 0) {
                            surface(f, centroid(a, b, c), cat(a, b, c));
                        } else {
                            surface(f, centroid(a, b, c), cat(a, b, c));
                            surface(f, centroid(a, c, d), cat(a, c, d));
                        }
                    }
                }
            }
            case PILLAR -> {
                int n = 4 * ARC_SEGMENTS;
                double[][] ring = new double[n][];
                for (int q = 0; q < 4; q++) {
                    for (int k = 0; k < ARC_SEGMENTS; k++) {
                        double c = ARC[k][0], s = ARC[k][1];
                        double[] d = switch (q) { case 0 -> new double[]{c, s}; case 1 -> new double[]{-s, c};
                            case 2 -> new double[]{-c, -s}; default -> new double[]{s, -c}; };
                        ring[q * ARC_SEGMENTS + k] = new double[]{0.5 + PILLAR_RADIUS * d[0], 0.5 + PILLAR_RADIUS * d[1]};
                    }
                }
                double[] bottom = new double[3 * n], top = new double[3 * n];
                for (int k = 0; k < n; k++) {
                    bottom[3 * k] = ring[k][0]; bottom[3 * k + 2] = ring[k][1];
                    top[3 * k] = ring[k][0]; top[3 * k + 1] = 1; top[3 * k + 2] = ring[k][1];
                }
                side(f, DOWN, Kind.DISC, bottom);
                side(f, UP, Kind.DISC, top);
                for (int k = 0; k < n; k++) {
                    double[] a = ring[k], b = ring[(k + 1) % n];
                    surface(f, new double[]{a[0] + b[0] - 1, 0, a[1] + b[1] - 1},
                            a[0], 0, a[1], b[0], 0, b[1], b[0], 1, b[1], a[0], 1, a[1]);
                }
            }
        }
        return f;
    }

    private static double[] sphere(int i, int j) {
        double cosT = ARC[i][0], sinT = ARC[i][1], cosP = ARC[j][0], sinP = ARC[j][1];
        return new double[]{sinT * cosP, cosT, sinT * sinP};
    }

    // ------------------------------------------------------------------ orientation

    private static Face transform(Face f, ShapeType t, int o) {
        int n = f.size();
        double[] out = new double[3 * n];
        for (int i = 0; i < n; i++) {
            double x = f.x(i) - 0.5, y = f.y(i) - 0.5, z = f.z(i) - 0.5, tmp;
            if (t == ShapeType.PILLAR) {
                if (o == 0) { tmp = x; x = y; y = -tmp; }        // y axis → x axis
                else if (o == 2) { tmp = y; y = -z; z = tmp; }   // y axis → z axis
            } else {
                switch (o & 3) {
                    case 1 -> { tmp = x; x = -z; z = tmp; }      // north → east
                    case 2 -> { x = -x; z = -z; }                // north → south
                    case 3 -> { tmp = x; x = z; z = -tmp; }      // north → west
                    default -> { }
                }
                if (o >= 4) y = -y;                               // upside-down (a mirror)
            }
            double[] s = snap(x + 0.5, y + 0.5, z + 0.5);
            System.arraycopy(s, 0, out, 3 * i, 3);
        }
        boolean mirrored = t != ShapeType.PILLAR && o >= 4;
        if (mirrored) { // mirroring flips winding: reverse, keeping vertex 0 (disc centres stay first)
            for (int a = 1, b = n - 1; a < b; a++, b--) {
                for (int k = 0; k < 3; k++) { double tmp = out[3 * a + k]; out[3 * a + k] = out[3 * b + k]; out[3 * b + k] = tmp; }
            }
        }
        return new Face(out, f.side < 0 ? -1 : sideOf(out), f.kind);
    }

    private static int sideOf(double[] p) {
        for (int axis = 0; axis < 3; axis++) {
            for (int v = 0; v <= 1; v++) {
                boolean all = true;
                for (int i = axis; i < p.length; i += 3) all &= p[i] == v;
                if (all) return axis == 0 ? (v == 0 ? WEST : EAST) : axis == 1 ? (v == 0 ? DOWN : UP) : (v == 0 ? NORTH : SOUTH);
            }
        }
        throw new IllegalStateException("boundary face not on a cell side");
    }

    // ------------------------------------------------------------------ helpers

    private static void side(List<Face> out, int side, Kind kind, double... xyz) {
        out.add(oriented(xyz, SIDE_NORMAL[side][0], SIDE_NORMAL[side][1], SIDE_NORMAL[side][2], side, kind));
    }

    private static void surface(List<Face> out, double[] outward, double... xyz) {
        out.add(oriented(xyz, outward[0], outward[1], outward[2], -1, Kind.SURFACE));
    }

    /** Snaps and winds the polygon so its normal agrees with the outward hint. */
    private static Face oriented(double[] xyz, double hx, double hy, double hz, int side, Kind kind) {
        double[] p = new double[xyz.length];
        for (int i = 0; i < xyz.length; i += 3) System.arraycopy(snap(xyz[i], xyz[i + 1], xyz[i + 2]), 0, p, i, 3);
        double nx = 0, ny = 0, nz = 0; // Newell normal
        int n = p.length / 3;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            nx += (p[3 * i + 1] - p[3 * j + 1]) * (p[3 * i + 2] + p[3 * j + 2]);
            ny += (p[3 * i + 2] - p[3 * j + 2]) * (p[3 * i] + p[3 * j]);
            nz += (p[3 * i] - p[3 * j]) * (p[3 * i + 1] + p[3 * j + 1]);
        }
        if (nx * hx + ny * hy + nz * hz < 0) {
            for (int a = 1, b = n - 1; a < b; a++, b--) {
                for (int k = 0; k < 3; k++) { double t = p[3 * a + k]; p[3 * a + k] = p[3 * b + k]; p[3 * b + k] = t; }
            }
        }
        return new Face(p, side, kind);
    }

    /** cos/sin of k·90°/N; exactly symmetric (ARC[N-k] = swap(ARC[k])) with exact endpoints. */
    private static double[][] arcTable() {
        double[][] a = new double[ARC_SEGMENTS + 1][];
        for (int k = 0; k <= ARC_SEGMENTS / 2; k++) {
            double ang = k * Math.PI / 2 / ARC_SEGMENTS;
            a[k] = k == 0 ? new double[]{1, 0} : new double[]{Math.cos(ang), Math.sin(ang)};
            a[ARC_SEGMENTS - k] = new double[]{a[k][1], a[k][0]};
        }
        return a;
    }

    public static double[] snap(double x, double y, double z) {
        return new double[]{Math.round(x * SNAP) / (double) SNAP, Math.round(y * SNAP) / (double) SNAP, Math.round(z * SNAP) / (double) SNAP};
    }

    static long key(double x, double y, double z) {
        long X = Math.round(x * SNAP) + SNAP, Y = Math.round(y * SNAP) + SNAP, Z = Math.round(z * SNAP) + SNAP;
        return (X << 42) | (Y << 21) | Z;
    }

    private static double[] missingCorner(Face f, double[][] corners) {
        for (double[] c : corners) {
            long k = key(c[0], c[1], c[2]);
            if (Arrays.binarySearch(f.keys, k) < 0) return c;
        }
        throw new IllegalStateException("partial face covers every corner");
    }

    private static boolean isCorner(Face f, int i, int u, int v) {
        double a = f.xyz[3 * i + u], b = f.xyz[3 * i + v];
        return (a == 0 || a == 1) && (b == 0 || b == 1);
    }

    private static boolean differsInOneAxis(double[] a, double[] b) {
        int d = 0;
        for (int k = 0; k < 3; k++) if (a[k] != b[k]) d++;
        return d == 1;
    }

    private static double[] corner(double[][] corners, int u, int v, boolean highU, boolean highV) {
        for (double[] c : corners) if ((c[u] == 1) == highU && (c[v] == 1) == highV) return c;
        throw new IllegalStateException();
    }

    private static double[] point(Face f, int i) {
        return new double[]{f.x(i), f.y(i), f.z(i)};
    }

    private static double dotNormal(double[] t, int[] n) {
        double ux = t[3] - t[0], uy = t[4] - t[1], uz = t[5] - t[2];
        double vx = t[6] - t[0], vy = t[7] - t[1], vz = t[8] - t[2];
        return (uy * vz - uz * vy) * n[0] + (uz * vx - ux * vz) * n[1] + (ux * vy - uy * vx) * n[2];
    }

    private static double[] concat(double[] a, double[] b, double[] c) {
        return new double[]{a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2]};
    }

    private static double[] cat(double[]... pts) {
        double[] out = new double[3 * pts.length];
        for (int i = 0; i < pts.length; i++) System.arraycopy(pts[i], 0, out, 3 * i, 3);
        return out;
    }

    private static double[] centroid(double[] a, double[] b, double[] c) {
        return new double[]{a[0] + b[0] + c[0], a[1] + b[1] + c[1], a[2] + b[2] + c[2]};
    }

    private static void put(double[] out, int o, double... v) {
        System.arraycopy(v, 0, out, o, 9);
    }

    // ------------------------------------------------------------------ collision

    /**
     * Stair-stepped boxes approximating the shape (for collision / outline), {minX,minY,minZ,maxX,maxY,maxZ}
     * in cell-local units, already oriented.
     */
    public static List<double[]> boxes(ShapeType t, int orientation, int res) {
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < res; i++) {
            for (int k = 0; k < res; k++) {
                double x = (i + 0.5) / res, z = (k + 0.5) / res;
                double h = switch (t) {
                    case SLOPE -> 1 - z;
                    case SLOPE_OUTER -> 1 - Math.max(x, z);
                    case SLOPE_INNER -> 1 - Math.min(x, z);
                    case ROUND_EDGE -> Math.sqrt(Math.max(0, 1 - z * z));
                    case ROUND_CORNER -> Math.sqrt(Math.max(0, 1 - x * x - z * z));
                    case PILLAR -> (x - 0.5) * (x - 0.5) + (z - 0.5) * (z - 0.5) <= PILLAR_RADIUS * PILLAR_RADIUS ? 1 : 0;
                };
                double hq = Math.round(h * res) / (double) res;
                if (hq <= 0) continue;
                Face box = new Face(new double[]{(double) i / res, 0, (double) k / res, (i + 1.0) / res, hq, (k + 1.0) / res}, -1, Kind.SURFACE);
                Face moved = transform(box, t, orientation);
                out.add(new double[]{
                        Math.min(moved.x(0), moved.x(1)), Math.min(moved.y(0), moved.y(1)), Math.min(moved.z(0), moved.z(1)),
                        Math.max(moved.x(0), moved.x(1)), Math.max(moved.y(0), moved.y(1)), Math.max(moved.z(0), moved.z(1))});
            }
        }
        return out;
    }
}
