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
import net.minecraft.server.permissions.Permissions;

/**
 * Minecraft 26.3: the calls whose signatures differ between supported versions.
 * (Permission sets replaced numeric levels; click/hover events are records; dust takes an RGB int.)
 */
public final class Compat {
    private Compat() {
    }

    public static ServerLevel level(ServerPlayer player) {
        return player.level();
    }

    /** Operators (gamemaster level), or the owner of a singleplayer / LAN world (no cheats needed). */
    public static boolean mayUseCad(CommandSourceStack source) {
        if (source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) return true;
        ServerPlayer player = source.getPlayer();
        return player != null && source.getServer().isSingleplayerOwner(player.nameAndId());
    }

    /**
     * Green, underlined, click to copy the path. Since 1.21.5 servers may not send OPEN_FILE click
     * events (the client rejects the whole message), so copying is the best a server can offer.
     */
    public static Style fileLink(Style style, String path) {
        return style.withColor(ChatFormatting.GREEN).withUnderlined(true)
                .withClickEvent(new ClickEvent.CopyToClipboard(path))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to copy the file path")));
    }

    public static ParticleOptions dust(int rgb, float scale) {
        return new DustParticleOptions(rgb, scale);
    }

    /** One particle, visible only to {@code player}, even from far away. */
    public static void particle(ServerLevel level, ServerPlayer player, ParticleOptions particle, double x, double y, double z) {
        level.sendParticles(player, particle, true, true, x, y, z, 1, 0, 0, 0, 0);
    }
}
