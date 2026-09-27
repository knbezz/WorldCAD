package dev.worldcad.export;

import dev.worldcad.Compat;
import dev.worldcad.WorldCad;
import dev.worldcad.mesh.GreedyMesher;
import dev.worldcad.mesh.Mesh;
import dev.worldcad.mesh.SurfaceNetsMesher;
import dev.worldcad.selection.SelectionBox;
import dev.worldcad.voxel.VoxelGrid;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Orchestrates an export:
 * <ol>
 *   <li>server thread: snapshot blocks into a VoxelGrid (fast, bounded by MAX_VOLUME)</li>
 *   <li>export thread: mesh → STL/3MF → disk</li>
 *   <li>server thread: report the result in chat</li>
 * </ol>
 */
public final class ExportService {
    /** e.g. 256 × 64 × 256. Capture costs about one getBlockState per block on the main thread. */
    public static final long MAX_VOLUME = 4_194_304L;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "WorldCAD-Export");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private ExportService() {
    }

    public static Path exportDir() {
        return FabricLoader.getInstance().getGameDir().resolve("cad_exports");
    }

    /** Call on the server thread. Returns immediately; the result is reported to {@code source}. */
    public static void export(CommandSourceStack source, SelectionBox selection, ExportOptions opts) {
        MinecraftServer server = source.getServer();
        ServerLevel level = server.getLevel(selection.dimension());
        if (level == null) throw new IllegalStateException("The selection's dimension no longer exists.");

        long t0 = System.nanoTime();
        VoxelGrid grid = VoxelCapture.capture(level, selection.min(), selection.max(), opts);
        long captureMs = (System.nanoTime() - t0) / 1_000_000;

        Path target = exportDir().resolve(opts.fileName() + "." + opts.format().extension).toAbsolutePath().normalize();
        source.sendSuccess(() -> Component.literal("Exporting " + selection.describe() + " … (captured in " + captureMs + " ms)")
                .withStyle(ChatFormatting.GRAY), false);

        CompletableFuture.supplyAsync(() -> {
            Mesh mesh = opts.smooth()
                    ? SurfaceNetsMesher.mesh(grid, opts.relax())
                    : GreedyMesher.mesh(grid, opts.merge());
            try {
                if (opts.format() == ExportOptions.Format.STL) {
                    StlWriter.write(target, mesh, opts.mmPerBlock(), opts.origin(), opts.fileName());
                } else {
                    ThreeMFWriter.write(target, mesh, grid.materials(), opts.mmPerBlock(), opts.origin(), opts.fileName());
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return mesh;
        }, EXECUTOR).whenComplete((mesh, error) -> server.execute(() -> {
            if (error != null) {
                Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
                WorldCad.LOGGER.error("Export failed", cause);
                source.sendFailure(Component.literal("Export failed: " + cause.getMessage()));
                return;
            }
            long totalMs = (System.nanoTime() - t0) / 1_000_000;
            String path = target.toString();
            Component link = Component.literal(path).withStyle(style -> Compat.fileLink(style, path));
            source.sendSuccess(() -> Component.literal("✔ Exported " + mesh.triangleCount() + " triangles in "
                    + totalMs + " ms → ").append(link), false);
        }));
    }
}
