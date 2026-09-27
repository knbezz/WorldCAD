package dev.worldcad.export;

import dev.worldcad.mesh.Mesh;
import dev.worldcad.voxel.Material;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes a single-object 3MF package (3MF Core Specification 1.3).
 * <pre>
 * file.3mf (OPC / ZIP)
 * ├── [Content_Types].xml
 * ├── _rels/.rels            → points at the model part
 * └── 3D/3dmodel.model       → &lt;model&gt; &lt;resources&gt; &lt;basematerials/&gt; &lt;object&gt;&lt;mesh/&gt;&lt;/object&gt; &lt;/resources&gt; &lt;build/&gt;
 * </pre>
 * Colors use core {@code <basematerials>} with {@code displaycolor}; unlike the Materials
 * extension's {@code <m:colorgroup>}, every mainstream slicer reads these.
 * <p>
 * XML is streamed straight into the ZIP entry, so memory stays flat regardless of mesh size.
 * The file is written to a temp sibling and atomically moved into place, so a crash never leaves
 * a truncated .3mf behind.
 */
public final class ThreeMFWriter {
    private static final String CORE_NS = "http://schemas.microsoft.com/3dmanufacturing/core/2015/02";
    private static final int MATERIALS_ID = 1;
    private static final int OBJECT_ID = 2;

    private ThreeMFWriter() {
    }

    /**
     * @param mesh voxel-unit mesh in Minecraft axes (Y up). Converted here to millimetres, Z up.
     */
    public static void write(Path target, Mesh mesh, List<Material> materials, double mmPerBlock,
                             ExportOptions.Origin origin, String title) throws IOException {
        if (mesh.triangleCount() == 0) throw new IOException("Mesh is empty");
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".part");

        try (OutputStream fileOut = Files.newOutputStream(tmp);
             ZipOutputStream zip = new ZipOutputStream(fileOut, StandardCharsets.UTF_8)) {
            zip.setLevel(6);
            Writer w = new BufferedWriter(new OutputStreamWriter(zip, StandardCharsets.UTF_8), 1 << 16);

            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            w.write("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                     <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                     <Default Extension="model" ContentType="application/vnd.ms-package.3dmanufacturing-3dmodel+xml"/>
                    </Types>
                    """);
            w.flush();
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("_rels/.rels"));
            w.write("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                     <Relationship Target="/3D/3dmodel.model" Id="rel0" Type="http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel"/>
                    </Relationships>
                    """);
            w.flush();
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("3D/3dmodel.model"));
            writeModel(w, mesh, materials, mmPerBlock, origin, title);
            w.flush();
            zip.closeEntry();
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

    private static void writeModel(Writer w, Mesh mesh, List<Material> materials, double scale,
                                   ExportOptions.Origin origin, String title) throws IOException {
        ModelTransform tf = new ModelTransform(mesh, scale, origin);

        w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        w.write("<model unit=\"millimeter\" xml:lang=\"en-US\" xmlns=\"" + CORE_NS + "\">\n");
        w.write(" <metadata name=\"Title\">" + escape(title) + "</metadata>\n");
        w.write(" <metadata name=\"Application\">WorldCAD (Minecraft)</metadata>\n");
        w.write(" <resources>\n");

        w.write("  <basematerials id=\"" + MATERIALS_ID + "\">\n");
        for (Material m : materials) {
            w.write("   <base name=\"" + escape(m.name()) + "\" displaycolor=\""
                    + String.format(Locale.ROOT, "#%06XFF", m.rgb() & 0xFFFFFF) + "\"/>\n");
        }
        w.write("  </basematerials>\n");

        w.write("  <object id=\"" + OBJECT_ID + "\" type=\"model\" pid=\"" + MATERIALS_ID + "\" pindex=\"0\">\n");
        w.write("   <mesh>\n    <vertices>\n");
        StringBuilder sb = new StringBuilder(96);
        for (int i = 0; i < mesh.vertexCount(); i++) {
            sb.setLength(0);
            sb.append("     <vertex x=\"").append(num(tf.x(i)))
                    .append("\" y=\"").append(num(tf.y(i)))
                    .append("\" z=\"").append(num(tf.z(i)))
                    .append("\"/>\n");
            w.append(sb);
        }
        w.write("    </vertices>\n    <triangles>\n");
        for (int t = 0; t < mesh.triangleCount(); t++) {
            sb.setLength(0);
            sb.append("     <triangle v1=\"").append(mesh.corner(t, 0))
                    .append("\" v2=\"").append(mesh.corner(t, 1))
                    .append("\" v3=\"").append(mesh.corner(t, 2))
                    .append("\" pid=\"").append(MATERIALS_ID)
                    .append("\" p1=\"").append(mesh.material(t))
                    .append("\"/>\n");
            w.append(sb);
        }
        w.write("    </triangles>\n   </mesh>\n  </object>\n");
        w.write(" </resources>\n");
        w.write(" <build>\n  <item objectid=\"" + OBJECT_ID + "\"/>\n </build>\n");
        w.write("</model>\n");
    }

    /** Compact number: integers without ".0", otherwise rounded to 0.1 micron. Exponents are valid ST_Number. */
    static String num(double v) {
        double r = Math.round(v * 1e4) / 1e4;
        if (r == Math.rint(r) && Math.abs(r) < 1e15) return Long.toString((long) r);
        return Double.toString(r);
    }

    static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&apos;");
                default -> {
                    if (c >= 0x20 || c == '\t' || c == '\n' || c == '\r') out.append(c);
                }
            }
        }
        return out.toString();
    }
}
