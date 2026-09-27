package dev.worldcad.mesh;

import dev.worldcad.voxel.VoxelGrid;

import java.util.Arrays;

/**
 * Smoothed surface extraction ({@code --smooth}) using Naive Surface Nets.
 * <p>
 * Picked over Marching Cubes because it needs no 256-case lookup tables, yields about half the
 * triangles, and produces quads with no cracks. The dual grid has one cell between every
 * 2x2x2 group of voxel centres; each cell straddling the surface gets one vertex at the mean of
 * its sign-changing edge midpoints. Every solid/empty voxel pair then emits one quad joining the
 * four cells around that pair.
 * <p>
 * Output is closed, but the checkerboard case (two voxels touching only along an edge) yields
 * a non-manifold edge — the same limitation as the blocky mesher.
 */
public final class SurfaceNetsMesher {
    private SurfaceNetsMesher() {
    }

    public static Mesh mesh(VoxelGrid grid, int relaxIterations) {
        return new Run(grid).build(relaxIterations);
    }

    private static final class Run {
        private final VoxelGrid g;
        private final int cx, cy, cz;       // dual-cell grid dims; cell c spans voxel centres c..c+1, c in [-1, s-1]
        private final int[] cellVertex;
        private final Mesh mesh = new Mesh();

        Run(VoxelGrid g) {
            this.g = g;
            this.cx = g.sx + 1;
            this.cy = g.sy + 1;
            this.cz = g.sz + 1;
            this.cellVertex = new int[cx * cy * cz];
            Arrays.fill(cellVertex, -1);
        }

        Mesh build(int relaxIterations) {
            final int[] dims = {g.sx, g.sy, g.sz};
            final int[] p = new int[3];
            final int[] c = new int[3];

            for (int d = 0; d < 3; d++) {
                final int u = (d + 1) % 3;
                final int v = (d + 2) % 3;
                // Voxel pair (p, p + e_d); p[d] starts at -1 so the padding boundary is covered.
                for (p[d] = -1; p[d] < dims[d]; p[d]++) {
                    for (p[v] = 0; p[v] < dims[v]; p[v]++) {
                        for (p[u] = 0; p[u] < dims[u]; p[u]++) {
                            int a = g.value(p[0], p[1], p[2]);
                            int b = g.value(p[0] + (d == 0 ? 1 : 0), p[1] + (d == 1 ? 1 : 0), p[2] + (d == 2 ? 1 : 0));
                            if ((a != 0) == (b != 0)) continue;

                            c[d] = p[d];
                            c[u] = p[u] - 1; c[v] = p[v] - 1; int v00 = cell(c);
                            c[u] = p[u];                      int v10 = cell(c);
                            c[v] = p[v];                      int v11 = cell(c);
                            c[u] = p[u] - 1;                  int v01 = cell(c);

                            if (a != 0) {
                                mesh.addQuad(v00, v10, v11, v01, a - 1); // solid at p: normal +d
                            } else {
                                mesh.addQuad(v00, v01, v11, v10, b - 1);
                            }
                        }
                    }
                }
            }
            return relaxIterations > 0 ? Relax.apply(mesh, relaxIterations) : mesh;
        }

        private int cell(int[] c) {
            int idx = (c[0] + 1) + cx * ((c[1] + 1) + cy * (c[2] + 1));
            int id = cellVertex[idx];
            if (id >= 0) return id;

            // Mean of the midpoints of the cube edges whose endpoints differ in occupancy.
            double sx = 0, sy = 0, sz = 0;
            int n = 0;
            for (int corner = 0; corner < 8; corner++) {
                int ax = corner & 1, ay = (corner >> 1) & 1, az = (corner >> 2) & 1;
                boolean solidA = g.value(c[0] + ax, c[1] + ay, c[2] + az) != 0;
                for (int bit = 1; bit <= 4; bit <<= 1) {
                    if ((corner & bit) != 0) continue;
                    int other = corner | bit;
                    int bx = other & 1, by = (other >> 1) & 1, bz = (other >> 2) & 1;
                    if (solidA == (g.value(c[0] + bx, c[1] + by, c[2] + bz) != 0)) continue;
                    // voxel centres sit at integer + 0.5
                    sx += c[0] + (ax + bx) * 0.5 + 0.5;
                    sy += c[1] + (ay + by) * 0.5 + 0.5;
                    sz += c[2] + (az + bz) * 0.5 + 0.5;
                    n++;
                }
            }
            id = mesh.addVertex((float) (sx / n), (float) (sy / n), (float) (sz / n));
            cellVertex[idx] = id;
            return id;
        }
    }

    /** Optional Laplacian relaxation for a rounder look. Topology is unchanged; volume shrinks slightly. */
    private static final class Relax {
        static Mesh apply(Mesh in, int iterations) {
            int nv = in.vertexCount();
            float[] pos = new float[3 * nv];
            for (int i = 0; i < nv; i++) {
                pos[3 * i] = in.x(i);
                pos[3 * i + 1] = in.y(i);
                pos[3 * i + 2] = in.z(i);
            }
            for (int it = 0; it < iterations; it++) {
                float[] sum = new float[3 * nv];
                int[] cnt = new int[nv];
                for (int t = 0; t < in.triangleCount(); t++) {
                    for (int k = 0; k < 3; k++) {
                        int a = in.corner(t, k), b = in.corner(t, (k + 1) % 3);
                        sum[3 * a] += pos[3 * b]; sum[3 * a + 1] += pos[3 * b + 1]; sum[3 * a + 2] += pos[3 * b + 2];
                        cnt[a]++;
                    }
                }
                for (int i = 0; i < nv; i++) {
                    if (cnt[i] == 0) continue;
                    for (int k = 0; k < 3; k++) {
                        pos[3 * i + k] = 0.5f * pos[3 * i + k] + 0.5f * (sum[3 * i + k] / cnt[i]);
                    }
                }
            }
            Mesh out = new Mesh();
            for (int i = 0; i < nv; i++) out.addVertex(pos[3 * i], pos[3 * i + 1], pos[3 * i + 2]);
            for (int t = 0; t < in.triangleCount(); t++) {
                out.addTriangle(in.corner(t, 0), in.corner(t, 1), in.corner(t, 2), in.material(t));
            }
            return out;
        }
    }
}
