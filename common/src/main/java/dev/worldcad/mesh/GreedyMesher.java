package dev.worldcad.mesh;

import dev.worldcad.shape.ShapeGeometry;
import dev.worldcad.shape.ShapeGeometry.Face;
import dev.worldcad.shape.ShapeType;
import dev.worldcad.voxel.VoxelGrid;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Surface extraction for blocky exports (plus Studio shape blocks).
 * <p>
 * Sweeps a plane along each axis; at each slice it builds a 2D mask of exposed faces
 * (signed by facing, keyed by material) and then merges runs of identical mask cells into the
 * largest rectangles it can (Lysenko's greedy meshing). Faces between two solid voxels are
 * never emitted, so interior geometry costs nothing.
 * <p>
 * Merged rectangles meet smaller neighbours at T-junctions (a neighbour's corner lying in the
 * middle of a rectangle's edge), which slicers report as open edges / holes. After merging,
 * {@link #emitWithoutTJunctions} inserts every such vertex into the rectangle's outline and
 * fans the result from the rectangle centre, so the output is crack-free with shared edges.
 * {@code merge = false} emits one quad per exposed face instead (bigger, same topology).
 * <p>
 * Shape blocks are emitted polygon by polygon and stitched to their neighbours (see
 * {@link #emitShapes}): coincident faces cancel, and where a full face meets a partial one the
 * exact uncovered remainder is emitted. Remaining limitations: voxels touching only along an
 * edge give a non-manifold edge, and two <em>different</em> partial faces pressed together
 * (e.g. a slope side against a rounded side) are both kept, overlapping.
 */
public final class GreedyMesher {
    private GreedyMesher() {
    }

    public static Mesh mesh(VoxelGrid grid, boolean merge) {
        final int[] dims = {grid.sx, grid.sy, grid.sz};
        final Mesh mesh = new Mesh();
        final VertexPool pool = new VertexPool(mesh);
        final QuadList quads = new QuadList();

        final int[] x = new int[3];
        final int[] q = new int[3];
        final int[] du = new int[3];
        final int[] dv = new int[3];

        for (int d = 0; d < 3; d++) {
            // (u, v, d) is a cyclic permutation of (x, y, z), so e_u x e_v = +e_d.
            final int u = (d + 1) % 3;
            final int v = (d + 2) % 3;
            final int w0 = dims[u];
            final int[] mask = new int[dims[u] * dims[v]];
            final int sideNeg = d == 0 ? ShapeGeometry.WEST : d == 1 ? ShapeGeometry.DOWN : ShapeGeometry.NORTH;
            final int sidePos = sideNeg + 1;
            q[0] = q[1] = q[2] = 0;
            q[d] = 1;

            for (x[d] = -1; x[d] < dims[d]; ) {
                // 1. Build the face mask for the plane between slice x[d] and x[d]+1.
                //    +m: face of material m-1 facing +d.  -m: face facing -d.
                //    A full block next to a shape that has a face on this side is left to emitShapes.
                int n = 0;
                for (x[v] = 0; x[v] < dims[v]; x[v]++) {
                    for (x[u] = 0; x[u] < dims[u]; x[u]++) {
                        int a = grid.get(x[0], x[1], x[2]);
                        int b = grid.get(x[0] + q[0], x[1] + q[1], x[2] + q[2]);
                        int m = 0;
                        if (a != 0 && b == 0) {
                            m = shapeHasFace(grid, x[0] + q[0], x[1] + q[1], x[2] + q[2], sideNeg) ? 0 : a;
                        } else if (a == 0 && b != 0) {
                            m = shapeHasFace(grid, x[0], x[1], x[2], sidePos) ? 0 : -b;
                        }
                        mask[n++] = m;
                    }
                }
                x[d]++; // the plane now sits at lattice coordinate x[d]

                // 2. Greedily consume the mask into rectangles.
                n = 0;
                for (int j = 0; j < dims[v]; j++) {
                    for (int i = 0; i < dims[u]; ) {
                        final int c = mask[n];
                        if (c == 0) {
                            i++;
                            n++;
                            continue;
                        }
                        int w = 1;
                        int h = 1;
                        if (merge) {
                            while (i + w < dims[u] && mask[n + w] == c) w++;
                            grow:
                            for (; j + h < dims[v]; h++) {
                                for (int k = 0; k < w; k++) {
                                    if (mask[n + k + h * w0] != c) break grow;
                                }
                            }
                        }

                        x[u] = i;
                        x[v] = j;
                        du[0] = du[1] = du[2] = 0;
                        dv[0] = dv[1] = dv[2] = 0;
                        du[u] = w;
                        dv[v] = h;

                        int p0 = pool.id(x[0], x[1], x[2]);
                        int p1 = pool.id(x[0] + du[0], x[1] + du[1], x[2] + du[2]);
                        int p2 = pool.id(x[0] + du[0] + dv[0], x[1] + du[1] + dv[1], x[2] + du[2] + dv[2]);
                        int p3 = pool.id(x[0] + dv[0], x[1] + dv[1], x[2] + dv[2]);

                        if (c > 0) {
                            quads.add(p0, p1, p2, p3, c - 1);  // CCW seen from +d
                        } else {
                            quads.add(p0, p3, p2, p1, -c - 1); // CCW seen from -d
                        }

                        for (int l = 0; l < h; l++) {
                            for (int k = 0; k < w; k++) mask[n + k + l * w0] = 0;
                        }
                        i += w;
                        n += w;
                    }
                }
            }
        }
        // Shapes first: their corner vertices must exist before T-junctions are resolved.
        emitShapes(grid, mesh, pool);
        // Triangulate rectangles only once every vertex exists: a T-junction vertex may come
        // from a quad emitted later in the sweep, or from a shape block.
        emitWithoutTJunctions(mesh, pool, quads);
        return mesh;
    }

    private static boolean shapeHasFace(VoxelGrid g, int x, int y, int z, int side) {
        int s = g.shape(x, y, z);
        return s >= 0 && ShapeGeometry.face(VoxelGrid.shapeType(s), VoxelGrid.shapeOrientation(s), side) != null;
    }

    /**
     * Emits every shape block, stitching each face that lies on a cell side to the neighbour:
     * <ul>
     *   <li>air / shape without a face there → emit the face</li>
     *   <li>full block → partial face: emit the full block's uncovered remainder; full face: nothing</li>
     *   <li>shape with the identical face → nothing (internal)</li>
     *   <li>our face full, neighbour's partial → emit the remainder of ours; the reverse → nothing</li>
     *   <li>two different partial faces → keep ours (known limitation)</li>
     * </ul>
     */
    private static void emitShapes(VoxelGrid g, Mesh mesh, VertexPool pool) {
        g.forEachShape(index -> {
            int x = g.cellX(index), y = g.cellY(index), z = g.cellZ(index);
            int code = g.shape(x, y, z);
            ShapeType type = VoxelGrid.shapeType(code);
            int orientation = VoxelGrid.shapeOrientation(code);
            int material = VoxelGrid.shapeValue(code) - 1;

            for (Face f : ShapeGeometry.faces(type, orientation)) {
                if (f.side < 0) {
                    emitTriangles(mesh, pool, f.triangulate(), x, y, z, material);
                    continue;
                }
                int[] n = ShapeGeometry.SIDE_NORMAL[f.side];
                int nx = x + n[0], ny = y + n[1], nz = z + n[2];

                int solid = g.get(nx, ny, nz);
                if (solid != 0) {
                    if (f.kind != ShapeGeometry.Kind.FULL) {
                        int[] towardUs = {-n[0], -n[1], -n[2]};
                        emitTriangles(mesh, pool, ShapeGeometry.complement(f, towardUs), x, y, z, solid - 1);
                    }
                    continue;
                }
                int nc = g.shape(nx, ny, nz);
                Face nf = nc < 0 ? null
                        : ShapeGeometry.face(VoxelGrid.shapeType(nc), VoxelGrid.shapeOrientation(nc), f.side ^ 1);
                if (nf == null) {
                    emitTriangles(mesh, pool, f.triangulate(), x, y, z, material);
                } else if (ShapeGeometry.sameRegion(f, nf, n)) {
                    // internal: both cancel
                } else if (f.kind == ShapeGeometry.Kind.FULL) {
                    emitTriangles(mesh, pool, ShapeGeometry.complement(nf, n), nx, ny, nz, material);
                } else if (nf.kind != ShapeGeometry.Kind.FULL) {
                    emitTriangles(mesh, pool, f.triangulate(), x, y, z, material);
                }
            }
        });
    }

    private static void emitTriangles(Mesh mesh, VertexPool pool, double[] tris, int ox, int oy, int oz, int material) {
        for (int t = 0; t < tris.length; t += 9) {
            int a = pool.id(ox + tris[t], oy + tris[t + 1], oz + tris[t + 2]);
            int b = pool.id(ox + tris[t + 3], oy + tris[t + 4], oz + tris[t + 5]);
            int c = pool.id(ox + tris[t + 6], oy + tris[t + 7], oz + tris[t + 8]);
            if (a != b && b != c && a != c) mesh.addTriangle(a, b, c, material);
        }
    }

    /**
     * For each rectangle, walk its four edges and collect every existing lattice vertex lying strictly
     * inside an edge. Plain rectangles become 2 triangles; rectangles with extra outline vertices are
     * fanned from a new centre vertex (fanning from a corner would create zero-area triangles along
     * the collinear edges). Both sides of every shared segment then use exactly the same vertices.
     */
    private static void emitWithoutTJunctions(Mesh mesh, VertexPool pool, QuadList quads) {
        int[] ring = new int[64];
        for (int qi = 0; qi < quads.size; qi++) {
            int base = 5 * qi;
            int material = quads.data[base + 4];
            int n = 0;
            for (int k = 0; k < 4; k++) {
                int a = quads.data[base + k], b = quads.data[base + (k + 1) % 4];
                if (n + 1 > ring.length) ring = Arrays.copyOf(ring, ring.length * 2);
                ring[n++] = a;
                int ax = (int) mesh.x(a), ay = (int) mesh.y(a), az = (int) mesh.z(a);
                int dx = Integer.signum((int) mesh.x(b) - ax), dy = Integer.signum((int) mesh.y(b) - ay), dz = Integer.signum((int) mesh.z(b) - az);
                int len = Math.abs((int) mesh.x(b) - ax) + Math.abs((int) mesh.y(b) - ay) + Math.abs((int) mesh.z(b) - az);
                for (int s = 1; s < len; s++) {
                    int mid = pool.find(ax + s * dx, ay + s * dy, az + s * dz);
                    if (mid < 0) continue;
                    if (n + 1 > ring.length) ring = Arrays.copyOf(ring, ring.length * 2);
                    ring[n++] = mid;
                }
            }
            if (n == 4) {
                mesh.addQuad(ring[0], ring[1], ring[2], ring[3], material);
                continue;
            }
            int c0 = quads.data[base], c2 = quads.data[base + 2];
            // Not pooled: an interior point must never weld to anything else.
            int centre = mesh.addVertex((mesh.x(c0) + mesh.x(c2)) / 2, (mesh.y(c0) + mesh.y(c2)) / 2, (mesh.z(c0) + mesh.z(c2)) / 2);
            for (int i = 0; i < n; i++) {
                mesh.addTriangle(centre, ring[i], ring[(i + 1) % n], material);
            }
        }
    }

    /** Flat growable list of quads: 4 vertex ids + material each. */
    private static final class QuadList {
        int[] data = new int[5 * 1024];
        int size;

        void add(int a, int b, int c, int d, int material) {
            if (5 * size + 5 > data.length) data = Arrays.copyOf(data, data.length * 2);
            int i = 5 * size++;
            data[i] = a;
            data[i + 1] = b;
            data[i + 2] = c;
            data[i + 3] = d;
            data[i + 4] = material;
        }
    }

    /**
     * Shared, welded vertices. Positions are snapped to 1/{@value ShapeGeometry#SNAP} block, so the
     * same point reached by the cube mesher and by shape geometry resolves to one vertex id.
     */
    private static final class VertexPool {
        private static final int BITS = 21;
        private final Mesh mesh;
        private final Map<Long, Integer> ids = new HashMap<>();

        VertexPool(Mesh mesh) {
            this.mesh = mesh;
        }

        int id(double x, double y, double z) {
            long X = Math.round(x * ShapeGeometry.SNAP), Y = Math.round(y * ShapeGeometry.SNAP), Z = Math.round(z * ShapeGeometry.SNAP);
            long key = X << (2 * BITS) | Y << BITS | Z;
            Integer id = ids.get(key);
            if (id == null) {
                id = mesh.addVertex((float) (X / (double) ShapeGeometry.SNAP), (float) (Y / (double) ShapeGeometry.SNAP),
                        (float) (Z / (double) ShapeGeometry.SNAP));
                ids.put(key, id);
            }
            return id;
        }

        /** Existing vertex at a lattice point, or -1. */
        int find(int x, int y, int z) {
            long key = (long) x * ShapeGeometry.SNAP << (2 * BITS) | (long) y * ShapeGeometry.SNAP << BITS | (long) z * ShapeGeometry.SNAP;
            Integer id = ids.get(key);
            return id == null ? -1 : id;
        }
    }
}
