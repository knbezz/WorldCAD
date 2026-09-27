package dev.worldcad;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.joml.Vector3f;

/** Minecraft 1.20.1: the calls whose signatures differ between supported versions. */
public final class Compat {
    private Compat() {
    }

    public static ServerLevel level(ServerPlayer player) {
        return player.serverLevel();
    }

    /** Operators, or the owner of a singleplayer / LAN world (no cheats needed). */
    public static boolean mayUseCad(CommandSourceStack source) {
        if (source.hasPermission(2)) return true;
        ServerPlayer player = source.getPlayer();
        return player != null && source.getServer().isSingleplayerOwner(player.getGameProfile());
    }

    /**
     * Green, underlined, click to copy the path. Servers may not send OPEN_FILE click events (newer
     * clients reject the whole message), so copying is used on every version for consistent behavior.
     */
    public static Style fileLink(Style style, String path) {
        return style.withColor(ChatFormatting.GREEN).withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, path))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Click to copy the file path")));
    }

    public static ParticleOptions dust(int rgb, float scale) {
        return new DustParticleOptions(new Vector3f(((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f), scale);
    }

    /** One particle, visible only to {@code player}, even from far away. */
    public static void particle(ServerLevel level, ServerPlayer player, ParticleOptions particle, double x, double y, double z) {
        level.sendParticles(player, particle, true, x, y, z, 1, 0, 0, 0, 0);
    }
}
