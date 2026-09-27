package dev.worldcad.mesh;

import dev.worldcad.export.ExportOptions;
import dev.worldcad.export.StlWriter;
import dev.worldcad.export.ThreeMFWriter;
import dev.worldcad.voxel.Material;
import dev.worldcad.voxel.VoxelGrid;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class MeshExportTest {

    /** 4×3×5 box of stone with a 2×2×2 wood step on top: an L-profile with no edge-only contacts. */
    static VoxelGrid sample() {
        VoxelGrid g = new VoxelGrid(6, 6, 6);
        int stone = g.addMaterial(new Material("minecraft:stone", 0x707070));
        int wood = g.addMaterial(new Material("minecraft:oak_planks", 0x8F7748));
        for (int x = 1; x < 5; x++) for (int y = 0; y < 3; y++) for (int z = 0; z < 5; z++) g.set(x, y, z, stone);
        for (int x = 1; x < 3; x++) for (int y = 3; y < 5; y++) for (int z = 0; z < 2; z++) g.set(x, y, z, wood);
        return g;
    }

    @Test
    void greedyVolumeMatchesVoxelCount() {
        VoxelGrid g = sample();
        Mesh merged = GreedyMesher.mesh(g, true);
        Mesh culled = GreedyMesher.mesh(g, false);
        assertEquals(g.solidCount(), merged.signedVolume(), 1e-6);
        assertEquals(g.solidCount(), culled.signedVolume(), 1e-6);
        assertTrue(merged.triangleCount() < culled.triangleCount());
    }

    @Test
    void unmergedAndSmoothMeshesAreClosedManifolds() {
        VoxelGrid g = sample();
        assertClosedManifold(GreedyMesher.mesh(g, false));
        assertClosedManifold(GreedyMesher.mesh(g, true));
        Mesh smooth = SurfaceNetsMesher.mesh(g, 0);
        assertClosedManifold(smooth);
        assertTrue(smooth.signedVolume() > 0);
    }

    /** Regression: a tree canopy exported with 262 open edges because merged faces left T-junctions. */
    @Test
    void mergedTreeLikeBlobHasNoOpenEdges() {
        Random rnd = new Random(42);
        VoxelGrid g = new VoxelGrid(14, 19, 17);
        int s = g.addMaterial(new Material("solid", 0));
        for (int y = 0; y < 8; y++) g.set(7, y, 8, s); // trunk
        for (int x = 0; x < 14; x++) for (int y = 6; y < 19; y++) for (int z = 0; z < 17; z++) {
            double r = Math.hypot(Math.hypot(x - 7, (y - 12) * 1.3), z - 8);
            if (r < 6.5 && rnd.nextDouble() < 0.85) g.set(x, y, z, s); // ragged leaf ball
        }
        Mesh merged = GreedyMesher.mesh(g, true);
        assertEquals(g.solidCount(), merged.signedVolume(), 1e-6);
        Map<Long, Integer> directed = directedEdges(merged);
        directed.forEach((e, n) -> {
            long reverse = ((e & 0xFFFFFFFFL) << 32) | (e >>> 32);
            assertEquals(n, directed.getOrDefault(reverse, 0), "open edge (crack / T-junction)");
        });
    }

    @Test
    void writesValid3mfPackage(@TempDir Path dir) throws Exception {
        VoxelGrid g = sample();
        Path out = dir.resolve("test.3mf");
        ThreeMFWriter.write(out, GreedyMesher.mesh(g, true), g.materials(), 10, ExportOptions.Origin.CENTER, "test");
        try (ZipFile zip = new ZipFile(out.toFile())) {
            assertNotNull(zip.getEntry("[Content_Types].xml"));
            assertNotNull(zip.getEntry("_rels/.rels"));
            String model = new String(zip.getInputStream(zip.getEntry("3D/3dmodel.model")).readAllBytes());
            assertTrue(model.contains("unit=\"millimeter\""));
            assertTrue(model.contains("displaycolor=\"#8F7748FF\""));
        }
        assertFalse(Files.exists(dir.resolve("test.3mf.part")));
    }

    @Test
    void singleBlockStlIsA10mmCube(@TempDir Path dir) throws Exception {
        VoxelGrid g = new VoxelGrid(1, 1, 1);
        g.set(0, 0, 0, g.addMaterial(new Material("solid", 0)));
        Path out = dir.resolve("cube.stl");
        StlWriter.write(out, GreedyMesher.mesh(g, true), 10, ExportOptions.Origin.CENTER, "cube");

        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(out)).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(84 + 12 * 50, b.capacity());
        assertEquals(12, b.getInt(80));
        double vol = 0;
        for (int t = 0; t < 12; t++) {
            int o = 84 + t * 50 + 12; // skip normal
            float[] v = new float[9];
            for (int k = 0; k < 9; k++) v[k] = b.getFloat(o + 4 * k);
            vol += v[0] * (v[4] * v[8] - v[5] * v[7]) - v[1] * (v[3] * v[8] - v[5] * v[6]) + v[2] * (v[3] * v[7] - v[4] * v[6]);
        }
        assertEquals(1000.0, vol / 6, 1e-3); // 10 mm × 10 mm × 10 mm, normals outward
    }

    @Test
    void rejectsPathTraversal() {
        assertThrows(IllegalArgumentException.class, () -> ExportOptions.parse("../evil", ""));
        assertEquals("castle", ExportOptions.parse("castle.3mf", "--smooth").fileName());
        assertEquals(ExportOptions.Format.STL, ExportOptions.parse("castle", "").format());
    }

    /** Every directed edge must appear exactly once and its reverse exactly once. */
    static void assertClosedManifold(Mesh m) {
        Map<Long, Integer> directed = directedEdges(m);
        directed.forEach((e, n) -> {
            assertEquals(1, n, "directed edge used twice (flipped triangle or non-manifold)");
            long reverse = ((e & 0xFFFFFFFFL) << 32) | (e >>> 32);
            assertEquals(1, directed.getOrDefault(reverse, 0), "open boundary edge");
        });
    }

    static Map<Long, Integer> directedEdges(Mesh m) {
        Map<Long, Integer> directed = new HashMap<>();
        for (int t = 0; t < m.triangleCount(); t++) {
            for (int k = 0; k < 3; k++) {
                long e = ((long) m.corner(t, k) << 32) | m.corner(t, (k + 1) % 3);
                directed.merge(e, 1, Integer::sum);
            }
        }
        return directed;
    }
}
