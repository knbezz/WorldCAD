package dev.worldcad.selection;

import dev.worldcad.Compat;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Server-authoritative per-player selections. Only touched on the server thread. */
public final class SelectionManager {
    private static final Map<UUID, SelectionBox> SELECTIONS = new HashMap<>();

    private SelectionManager() {
    }

    public static SelectionBox get(ServerPlayer player) {
        return SELECTIONS.computeIfAbsent(player.getUUID(), id -> new SelectionBox());
    }

    /** Selection of a player if they have started one, without creating it. */
    public static SelectionBox peek(UUID playerId) {
        return SELECTIONS.get(playerId);
    }

    public static void setCorner(ServerPlayer player, int corner, BlockPos pos) {
        SelectionBox box = get(player);
        box.setCorner(corner, Compat.level(player).dimension(), pos);
        // (Component, true) = action bar; this overload exists unchanged from 1.19 to 26.x
        player.sendSystemMessage(Component.literal("Pos" + corner + " set. ").withStyle(corner == 1 ? ChatFormatting.RED : ChatFormatting.AQUA)
                .append(Component.literal(box.describe()).withStyle(ChatFormatting.GRAY)), true);
    }

    public static void clear(ServerPlayer player) {
        get(player).clear();
    }

    public static void remove(UUID playerId) {
        SELECTIONS.remove(playerId);
    }
}
