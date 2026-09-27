package dev.worldcad.selection;

import dev.worldcad.Compat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Draws each player's selection with vanilla dust particles, sent only to that player: pos1 red,
 * pos2 blue, the full box yellow. No client mod needed, so it works for unmodded players too.
 */
public final class SelectionOutline {
    private static final int INTERVAL_TICKS = 10;
    /** Cap per refresh so huge selections stay cheap; spacing grows instead. */
    private static final int MAX_PARTICLES = 600;
    private static final int RED = 0xFF4040, BLUE = 0x40A0FF, YELLOW = 0xFFD83A;

    private SelectionOutline() {
    }

    public static void tick(MinecraftServer server) {
        if (server.getTickCount() % INTERVAL_TICKS != 0) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            SelectionBox box = SelectionManager.peek(player.getUUID());
            if (box == null || box.dimension() == null) continue;
            ServerLevel level = Compat.level(player);
            if (!level.dimension().equals(box.dimension())) continue;

            if (box.isComplete()) {
                BlockPos min = box.min(), max = box.max();
                drawBox(level, player, min.getX(), min.getY(), min.getZ(), max.getX() + 1, max.getY() + 1, max.getZ() + 1, YELLOW);
            }
            if (box.pos1() != null) drawBlock(level, player, box.pos1(), RED);
            if (box.pos2() != null) drawBlock(level, player, box.pos2(), BLUE);
        }
    }

    private static void drawBlock(ServerLevel level, ServerPlayer player, BlockPos p, int rgb) {
        double e = 0.02; // just outside the block so the dust isn't hidden inside it
        drawBox(level, player, p.getX() - e, p.getY() - e, p.getZ() - e, p.getX() + 1 + e, p.getY() + 1 + e, p.getZ() + 1 + e, rgb);
    }

    private static void drawBox(ServerLevel level, ServerPlayer player,
                                double x0, double y0, double z0, double x1, double y1, double z1, int rgb) {
        double perimeter = 4 * ((x1 - x0) + (y1 - y0) + (z1 - z0));
        double step = Math.max(0.5, perimeter / MAX_PARTICLES);
        ParticleOptions dust = Compat.dust(rgb, step > 1 ? 1.5f : 1.0f);
        double[] xs = {x0, x1}, ys = {y0, y1}, zs = {z0, z1};
        for (double y : ys) for (double z : zs) line(level, player, dust, x0, y, z, x1, y, z, step);
        for (double x : xs) for (double z : zs) line(level, player, dust, x, y0, z, x, y1, z, step);
        for (double x : xs) for (double y : ys) line(level, player, dust, x, y, z0, x, y, z1, step);
    }

    private static void line(ServerLevel level, ServerPlayer player, ParticleOptions dust,
                             double ax, double ay, double az, double bx, double by, double bz, double step) {
        double len = Math.abs(bx - ax) + Math.abs(by - ay) + Math.abs(bz - az); // axis-aligned
        int n = Math.max(1, (int) Math.round(len / step));
        for (int i = 0; i <= n; i++) {
            double t = (double) i / n;
            Compat.particle(level, player, dust, ax + (bx - ax) * t, ay + (by - ay) * t, az + (bz - az) * t);
        }
    }
}
