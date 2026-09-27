package dev.worldcad.mesh;

import java.util.Arrays;

/**
 * Minimal indexed triangle mesh with a per-triangle material index.
 * <p>
 * Coordinates are in voxel units, Minecraft axes. Triangles are wound counter-clockwise when
 * seen from outside (outward normals, right-hand rule), which is what 3MF requires.
 * Backed by primitive arrays so a multi-million-triangle export doesn't box every number.
 */
public final class Mesh {
    private float[] verts = new float[3 * 1024];
    private int vertexCount;
    private int[] tris = new int[4 * 2048]; // v1, v2, v3, material
    private int triangleCount;

    public int addVertex(float x, float y, float z) {
        if (3 * vertexCount + 3 > verts.length) verts = Arrays.copyOf(verts, verts.length * 2);
        int i = 3 * vertexCount;
        verts[i] = x;
        verts[i + 1] = y;
        verts[i + 2] = z;
        return vertexCount++;
    }

    public void addTriangle(int a, int b, int c, int material) {
        if (4 * triangleCount + 4 > tris.length) tris = Arrays.copyOf(tris, tris.length * 2);
        int i = 4 * triangleCount;
        tris[i] = a;
        tris[i + 1] = b;
        tris[i + 2] = c;
        tris[i + 3] = material;
        triangleCount++;
    }

    /**
     * Adds quad a-b-c-d (CCW from outside) as two triangles, splitting along the shorter diagonal.
     * For planar rectangles either split is fine; for non-planar surface-nets quads the shorter
     * diagonal avoids slivers and folds.
     */
    public void addQuad(int a, int b, int c, int d, int material) {
        if (dist2(a, c) <= dist2(b, d)) {
            addTriangle(a, b, c, material);
            addTriangle(a, c, d, material);
        } else {
            addTriangle(a, b, d, material);
            addTriangle(b, c, d, material);
        }
    }

    public int vertexCount() { return vertexCount; }
    public int triangleCount() { return triangleCount; }
    public float x(int v) { return verts[3 * v]; }
    public float y(int v) { return verts[3 * v + 1]; }
    public float z(int v) { return verts[3 * v + 2]; }
    /** k-th (0..2) vertex index of triangle t. */
    public int corner(int t, int k) { return tris[4 * t + k]; }
    public int material(int t) { return tris[4 * t + 3]; }

    /** Signed enclosed volume in voxel units^3 (divergence theorem). Positive iff normals face outward. */
    public double signedVolume() {
        double sum = 0;
        for (int t = 0; t < triangleCount; t++) {
            int a = corner(t, 0), b = corner(t, 1), c = corner(t, 2);
            double ax = x(a), ay = y(a), az = z(a);
            double bx = x(b), by = y(b), bz = z(b);
            double cx = x(c), cy = y(c), cz = z(c);
            sum += ax * (by * cz - bz * cy) - ay * (bx * cz - bz * cx) + az * (bx * cy - by * cx);
        }
        return sum / 6.0;
    }

    private double dist2(int a, int b) {
        double dx = x(a) - x(b), dy = y(a) - y(b), dz = z(a) - z(b);
        return dx * dx + dy * dy + dz * dz;
    }
}
