package dev.worldcad.export;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Parsed form of {@code /cad export <filename> [options...]}.
 *
 * @param fileName        sanitized base name, without extension
 * @param format          STL (default, geometry only) or 3MF (with block colors)
 * @param smooth         surface nets instead of blocky greedy mesh
 * @param relax           Laplacian relaxation passes for --smooth
 * @param merge           greedy-merge coplanar faces (off = one quad per block face, strictly manifold)
 * @param mmPerBlock      physical size of one block in the exported model
 * @param origin          where (0,0,0) lands in the exported model
 * @param includeFluids   export water/lava source and flowing blocks
 * @param includeNonSolid export blocks without collision (flowers, torches, grass...) as full cubes
 */
public record ExportOptions(String fileName, Format format, boolean smooth, int relax, boolean merge, double mmPerBlock,
                            Origin origin, boolean includeFluids, boolean includeNonSolid) {

    public enum Format {
        /** Binary STL: one solid, no colors. Every block counts as the same material. */
        STL("stl"),
        /** 3MF with a per-block-type color palette. */
        THREE_MF("3mf");

        public final String extension;

        Format(String extension) {
            this.extension = extension;
        }
    }

    public enum Origin {
        /** Minimum selection corner at (0,0,0). */
        CORNER,
        /** Centred on X/Y, resting on Z = 0: what slicers expect for a print bed. */
        CENTER
    }

    public static final List<String> FLAGS = List.of(
            "--format=stl", "--format=3mf", "--smooth", "--relax=", "--no-merge", "--scale=", "--origin=center", "--origin=corner",
            "--fluids", "--all-blocks");

    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_\\-]{1,64}");

    public static ExportOptions parse(String rawName, String flags) {
        String lower = rawName.toLowerCase(Locale.ROOT);
        String name = lower.endsWith(".3mf") || lower.endsWith(".stl")
                ? rawName.substring(0, rawName.length() - 4) : rawName;
        // Blocks path traversal (../, absolute paths) and Windows-reserved characters.
        if (!SAFE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Filename may only contain letters, digits, '_' and '-' (max 64).");
        }

        boolean smooth = false, merge = true, fluids = false, nonSolid = false;
        Format format = lower.endsWith(".3mf") ? Format.THREE_MF : Format.STL;
        int relax = 0;
        double scale = 10.0;
        Origin origin = Origin.CENTER;

        for (String tok : flags.trim().split("\\s+")) {
            if (tok.isEmpty()) continue;
            String key = tok, value = null;
            int eq = tok.indexOf('=');
            if (eq >= 0) {
                key = tok.substring(0, eq);
                value = tok.substring(eq + 1);
            }
            switch (key) {
                case "--format" -> format = switch (String.valueOf(value).toLowerCase(Locale.ROOT)) {
                    case "stl" -> Format.STL;
                    case "3mf" -> Format.THREE_MF;
                    default -> throw new IllegalArgumentException("Unsupported format: " + value + " (stl or 3mf)");
                };
                case "--smooth" -> smooth = true;
                case "--relax" -> {
                    smooth = true;
                    relax = parseInt(value, 0, 20, key);
                }
                case "--no-merge" -> merge = false;
                case "--scale" -> scale = parseDouble(value, 0.01, 10_000, key);
                case "--origin" -> origin = switch (String.valueOf(value).toLowerCase(Locale.ROOT)) {
                    case "corner" -> Origin.CORNER;
                    case "center" -> Origin.CENTER;
                    default -> throw new IllegalArgumentException("--origin must be corner or center");
                };
                case "--fluids" -> fluids = true;
                case "--all-blocks" -> nonSolid = true;
                default -> throw new IllegalArgumentException("Unknown option: " + tok);
            }
        }
        return new ExportOptions(name, format, smooth, relax, merge, scale, origin, fluids, nonSolid);
    }

    private static int parseInt(String v, int min, int max, String key) {
        try {
            int n = Integer.parseInt(v);
            if (n < min || n > max) throw new NumberFormatException();
            return n;
        } catch (NumberFormatException | NullPointerException e) {
            throw new IllegalArgumentException(key + " expects an integer in [" + min + ", " + max + "]");
        }
    }

    private static double parseDouble(String v, double min, double max, String key) {
        try {
            double n = Double.parseDouble(v);
            if (!(n >= min && n <= max)) throw new NumberFormatException();
            return n;
        } catch (NumberFormatException | NullPointerException e) {
            throw new IllegalArgumentException(key + " expects a number in [" + min + ", " + max + "] (mm per block)");
        }
    }
}
