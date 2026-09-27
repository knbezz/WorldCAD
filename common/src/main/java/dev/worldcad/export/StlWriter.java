package dev.worldcad.export;

import dev.worldcad.mesh.Mesh;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Binary STL writer (the de-facto format every slicer accepts). Units are millimetres, Z up.
 * <pre>
 * UINT8[80]    header (must NOT start with "solid", or some readers treat it as ASCII)
 * UINT32       triangle count
 * per triangle: REAL32[3] normal, REAL32[3] v1, v2, v3, UINT16 attribute (0)   = 50 bytes
 * </pre>
 * All little-endian. ~5× smaller and much faster to parse than ASCII STL.
 */
public final class StlWriter {
    private static final int TRIANGLE_BYTES = 50;

    private StlWriter() {
    }

    public static void write(Path target, Mesh mesh, double mmPerBlock, ExportOptions.Origin origin, String title) throws IOException {
        if (mesh.triangleCount() == 0) throw new IOException("Mesh is empty");
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        ModelTransform tf = new ModelTransform(mesh, mmPerBlock, origin);

        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(tmp), 1 << 16)) {
            ByteBuffer header = ByteBuffer.allocate(84).order(ByteOrder.LITTLE_ENDIAN);
            byte[] text = ("WorldCAD " + title).getBytes(StandardCharsets.US_ASCII);
            header.put(text, 0, Math.min(text.length, 80));
            header.position(80);
            header.putInt(mesh.triangleCount());
            out.write(header.array());

            ByteBuffer buf = ByteBuffer.allocate(TRIANGLE_BYTES * 1024).order(ByteOrder.LITTLE_ENDIAN);
            for (int t = 0; t < mesh.triangleCount(); t++) {
                int a = mesh.corner(t, 0), b = mesh.corner(t, 1), c = mesh.corner(t, 2);
                double ax = tf.x(a), ay = tf.y(a), az = tf.z(a);
                double bx = tf.x(b), by = tf.y(b), bz = tf.z(b);
                double cx = tf.x(c), cy = tf.y(c), cz = tf.z(c);

                // Normal = (b - a) × (c - a), normalised. Slicers mostly recompute it, but keep it correct.
                double ux = bx - ax, uy = by - ay, uz = bz - az;
                double vx = cx - ax, vy = cy - ay, vz = cz - az;
                double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
                double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (len > 0) { nx /= len; ny /= len; nz /= len; }

                if (buf.remaining() < TRIANGLE_BYTES) flush(buf, out);
                buf.putFloat((float) nx).putFloat((float) ny).putFloat((float) nz);
                buf.putFloat((float) ax).putFloat((float) ay).putFloat((float) az);
                buf.putFloat((float) bx).putFloat((float) by).putFloat((float) bz);
                buf.putFloat((float) cx).putFloat((float) cy).putFloat((float) cz);
                buf.putShort((short) 0);
            }
            flush(buf, out);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }

        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void flush(ByteBuffer buf, OutputStream out) throws IOException {
        out.write(buf.array(), 0, buf.position());
        buf.clear();
    }
}
