package dev.worldcad.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.worldcad.Compat;
import dev.worldcad.export.ExportOptions;
import dev.worldcad.export.ExportService;
import dev.worldcad.selection.SelectionBox;
import dev.worldcad.selection.SelectionManager;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.util.concurrent.CompletableFuture;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static com.mojang.brigadier.arguments.StringArgumentType.word;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * <pre>
 * /cad                                   help
 * /cad pos1 [x y z] | /cad pos2 [x y z]  set a corner (default: the block you stand on)
 * /cad select &lt;from&gt; &lt;to&gt;              set both corners
 * /cad info | /cad clear
 * /cad export &lt;filename&gt; [--format=stl|3mf] [--smooth] [--relax=N] [--no-merge]
 *                         [--scale=mm] [--origin=center|corner] [--fluids] [--all-blocks]
 * </pre>
 * Allowed for the owner of a singleplayer/LAN world (no cheats needed) and for operators.
 * Other players on a server can't use it: export writes files on the server's disk.
 */
public final class CadCommand {
    private CadCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(literal("cad")
                .requires(Compat::mayUseCad)
                .executes(CadCommand::help)
                .then(literal("help").executes(CadCommand::help))
                .then(literal("pos1")
                        .executes(ctx -> setCorner(ctx, 1, standingOn(ctx.getSource())))
                        .then(argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> setCorner(ctx, 1, BlockPosArgument.getLoadedBlockPos(ctx, "pos")))))
                .then(literal("pos2")
                        .executes(ctx -> setCorner(ctx, 2, standingOn(ctx.getSource())))
                        .then(argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> setCorner(ctx, 2, BlockPosArgument.getLoadedBlockPos(ctx, "pos")))))
                .then(literal("select")
                        .then(argument("from", BlockPosArgument.blockPos())
                                .then(argument("to", BlockPosArgument.blockPos())
                                        .executes(CadCommand::select))))
                .then(literal("info").executes(CadCommand::info))
                .then(literal("clear").executes(ctx -> {
                    SelectionManager.clear(ctx.getSource().getPlayerOrException());
                    ctx.getSource().sendSuccess(() -> Component.literal("Selection cleared."), false);
                    return 1;
                }))
                .then(literal("export")
                        .then(argument("filename", word())
                                .executes(ctx -> export(ctx, ""))
                                .then(argument("options", greedyString())
                                        .suggests(CadCommand::suggestOptions)
                                        .executes(ctx -> export(ctx, getString(ctx, "options")))))));
    }

    private static int help(CommandContext<CommandSourceStack> ctx) {
        String[][] lines = {
                {"/cad pos1", "stand on a corner block: that's corner 1"},
                {"/cad pos2", "stand on the opposite corner: corner 2"},
                {"/cad info", "show the current selection"},
                {"/cad export <name>", "save the selection as <name>.stl"},
                {"  --scale=5", "mm per block (default 10)"},
                {"  --format=3mf", "colored 3MF instead of STL"},
                {"  --smooth", "rounded instead of blocky"},
                {"/cad clear", "remove the selection"},
        };
        MutableComponent text = Component.literal("WorldCAD: export builds as 3D-printable files\n").withStyle(ChatFormatting.GOLD);
        for (String[] line : lines) {
            text.append(Component.literal(line[0]).withStyle(ChatFormatting.YELLOW))
                    .append(Component.literal(" - " + line[1] + "\n").withStyle(ChatFormatting.GRAY));
        }
        text.append(Component.literal("Files go to: " + ExportService.exportDir().toAbsolutePath().normalize()).withStyle(ChatFormatting.DARK_GRAY));
        ctx.getSource().sendSuccess(() -> text, false);
        return 1;
    }

    /**
     * The block the player is standing on. Sampling just below the feet picks the block itself
     * when it's shorter than full height (slabs, paths), and the block below otherwise.
     */
    private static BlockPos standingOn(CommandSourceStack src) throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        return BlockPos.containing(player.getX(), player.getY() - 0.01, player.getZ());
    }

    private static int setCorner(CommandContext<CommandSourceStack> ctx, int corner, BlockPos pos) throws CommandSyntaxException {
        SelectionManager.setCorner(ctx.getSource().getPlayerOrException(), corner, pos);
        return 1;
    }

    private static int select(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        SelectionManager.setCorner(player, 1, BlockPosArgument.getLoadedBlockPos(ctx, "from"));
        SelectionManager.setCorner(player, 2, BlockPosArgument.getLoadedBlockPos(ctx, "to"));
        return 1;
    }

    private static int info(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        SelectionBox box = SelectionManager.get(ctx.getSource().getPlayerOrException());
        ctx.getSource().sendSuccess(() -> Component.literal("Selection: " + box.describe()), false);
        return 1;
    }

    private static int export(CommandContext<CommandSourceStack> ctx, String flags) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        SelectionBox box = SelectionManager.get(src.getPlayerOrException());
        if (!box.isComplete()) {
            src.sendFailure(Component.literal("Set both corners first: stand on one corner and type /cad pos1, then stand on the opposite corner and type /cad pos2."));
            return 0;
        }
        if (box.volume() > ExportService.MAX_VOLUME) {
            src.sendFailure(Component.literal("Selection is " + box.volume() + " blocks; the limit is " + ExportService.MAX_VOLUME + "."));
            return 0;
        }
        try {
            ExportOptions opts = ExportOptions.parse(getString(ctx, "filename"), flags);
            ExportService.export(src, box, opts);
            return 1;
        } catch (IllegalArgumentException | IllegalStateException e) {
            src.sendFailure(Component.literal(e.getMessage()));
            return 0;
        }
    }

    /** Suggests the next --flag for the greedy options string. */
    private static CompletableFuture<Suggestions> suggestOptions(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        String remaining = builder.getRemaining();
        int lastSpace = remaining.lastIndexOf(' ');
        String partial = remaining.substring(lastSpace + 1);
        SuggestionsBuilder sub = builder.createOffset(builder.getStart() + lastSpace + 1);
        for (String flag : ExportOptions.FLAGS) {
            String key = flag.contains("=") ? flag.substring(0, flag.indexOf('=') + 1) : flag;
            if (flag.startsWith(partial) && !remaining.contains(key)) sub.suggest(flag);
        }
        return sub.buildFuture();
    }
}
