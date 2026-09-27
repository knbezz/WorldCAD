package dev.worldcad.mesh;

import dev.worldcad.shape.ShapeType;
import dev.worldcad.voxel.Material;
import dev.worldcad.voxel.VoxelGrid;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Random;

import static dev.worldcad.mesh.MeshExportTest.assertClosedManifold;
import static dev.worldcad.mesh.MeshExportTest.directedEdges;
import static org.junit.jupiter.api.Assertions.*;

class ShapeMeshTest {

    static VoxelGrid grid(int sx, int sy, int sz) {
        VoxelGrid g = new VoxelGrid(sx, sy, sz);
        g.addMaterial(new Material("solid", 0));
        return g;
    }

    static double volumeAlone(ShapeType t, int o) {
        VoxelGrid g = grid(1, 1, 1);
        g.setShape(0, 0, 0, t, o, 1);
        Mesh m = GreedyMesher.mesh(g, true);
        assertClosedManifold(m);
        return m.signedVolume();
    }

    @Test
    void everyShapeInEveryOrientationIsClosedWithTheSameVolume() {
        for (ShapeType t : ShapeType.values()) {
            double v0 = volumeAlone(t, 0);
            assertTrue(v0 > 0 && v0 < 1, t + " volume " + v0);
            for (int o = 1; o < t.orientations; o++) assertEquals(v0, volumeAlone(t, o), 1e-6, t + " o=" + o);
        }
        assertEquals(0.5, volumeAlone(ShapeType.SLOPE, 0), 1e-9);
        assertEquals(1 / 3.0, volumeAlone(ShapeType.SLOPE_OUTER, 0), 1e-9);
        assertEquals(2 / 3.0, volumeAlone(ShapeType.SLOPE_INNER, 0), 1e-9);
        assertEquals(Math.PI / 4, volumeAlone(ShapeType.ROUND_EDGE, 0), 0.01);
    }

    @Test
    void shapesStitchToBlocksWithoutSeams() {
        // slope sitting on a block, a block beside its triangular side (supported, so no
        // edge-only contact), and a pillar between two blocks
        VoxelGrid g = grid(4, 3, 3);
        g.set(1, 0, 1, 1);
        g.setShape(1, 1, 1, ShapeType.SLOPE, 0, 1);
        g.set(0, 0, 1, 1);
        g.set(0, 1, 1, 1);
        g.set(3, 0, 1, 1);
        g.setShape(3, 1, 1, ShapeType.PILLAR, 1, 1);
        g.set(3, 2, 1, 1);
        for (boolean merge : new boolean[]{true, false}) {
            Mesh m = GreedyMesher.mesh(g, merge);
            assertClosedManifold(m);
            assertEquals(5 + 0.5 + volumeAlone(ShapeType.PILLAR, 1), m.signedVolume(), 1e-6); // 5 full blocks
        }
    }

    @Test
    void roundEdgeAndCornerJoinSeamlessly() {
        VoxelGrid g = grid(2, 1, 1);
        g.setShape(0, 0, 0, ShapeType.ROUND_EDGE, 0, 1);
        g.setShape(1, 0, 0, ShapeType.ROUND_CORNER, 0, 1);
        Mesh m = GreedyMesher.mesh(g, true);
        assertClosedManifold(m);
        assertEquals(volumeAlone(ShapeType.ROUND_EDGE, 0) + volumeAlone(ShapeType.ROUND_CORNER, 0), m.signedVolume(), 1e-6);
    }

    /** Any mix of blocks and shapes, however odd, must never produce a crack (open edge). */
    @Test
    void randomMixesNeverCrack() {
        Random rnd = new Random(7);
        ShapeType[] types = ShapeType.values();
        for (int trial = 0; trial < 100; trial++) {
            VoxelGrid g = grid(5, 5, 5);
            for (int x = 0; x < 5; x++) for (int y = 0; y < 5; y++) for (int z = 0; z < 5; z++) {
                double r = rnd.nextDouble();
                if (r < 0.3) g.set(x, y, z, 1);
                else if (r < 0.6) {
                    ShapeType t = types[rnd.nextInt(types.length)];
                    g.setShape(x, y, z, t, rnd.nextInt(t.orientations), 1);
                }
            }
            Map<Long, Integer> directed = directedEdges(GreedyMesher.mesh(g, true));
            directed.forEach((e, n) -> {
                long reverse = ((e & 0xFFFFFFFFL) << 32) | (e >>> 32);
                assertEquals(n, directed.getOrDefault(reverse, 0), "open edge");
            });
        }
    }
}
