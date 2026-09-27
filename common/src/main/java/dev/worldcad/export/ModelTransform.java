package dev.worldcad.export;

import dev.worldcad.mesh.Mesh;

/**
 * Voxel-space mesh (Minecraft axes, Y up) → print space (millimetres, Z up, resting on Z = 0).
 * <p>
 * Minecraft (x, y, z) → (x, −z, y) is a +90° rotation about X (det = +1), so CCW winding and
 * outward normals survive unchanged. Then scale by mm per block and shift per {@link ExportOptions.Origin}.
 */
public final class ModelTransform {
    private final Mesh mesh;
    private final double scale;
    private final double offX, offY, offZ;

    public ModelTransform(Mesh mesh, double mmPerBlock, ExportOptions.Origin origin) {
        this.mesh = mesh;
        this.scale = mmPerBlock;
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (int i = 0; i < mesh.vertexCount(); i++) {
            double x = mesh.x(i), y = -mesh.z(i), z = mesh.y(i);
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            minZ = Math.min(minZ, z);
        }
        this.offX = origin == ExportOptions.Origin.CENTER ? (minX + maxX) / 2 : minX;
        this.offY = origin == ExportOptions.Origin.CENTER ? (minY + maxY) / 2 : minY;
        this.offZ = minZ; // always sit on the build plate
    }

    public double x(int v) { return (mesh.x(v) - offX) * scale; }
    public double y(int v) { return (-mesh.z(v) - offY) * scale; }
    public double z(int v) { return (mesh.y(v) - offZ) * scale; }
}
