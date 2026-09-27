package dev.worldcad;

import dev.worldcad.command.CadCommand;
import dev.worldcad.selection.SelectionManager;
import dev.worldcad.selection.SelectionOutline;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Commands only, server-side only: no items, blocks or packets. Worlds stay vanilla (the mod can be
 * removed at any time), and a server running it accepts unmodded clients. The selection outline is
 * drawn with vanilla particles, so every player sees it.
 * <p>
 * Everything here compiles unchanged for every supported Minecraft version; the few calls whose
 * signatures differ between versions live in the per-version {@link Compat}.
 */
public final class WorldCad implements ModInitializer {
    public static final String MOD_ID = "worldcad";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> CadCommand.register(dispatcher));
        ServerTickEvents.END_SERVER_TICK.register(SelectionOutline::tick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> SelectionManager.remove(handler.player.getUUID()));
        LOGGER.info("WorldCAD loaded");
    }
}
