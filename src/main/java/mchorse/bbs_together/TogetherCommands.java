package mchorse.bbs_together;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import java.util.ArrayList;
import java.util.Collection;
import mchorse.bbs_together.bbsop.BbsOpManager;
import mchorse.bbs_together.network.TogetherServerNetwork;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.EntitySelector;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

public class TogetherCommands {
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess, CommandManager.RegistrationEnvironment env) {
        RequiredArgumentBuilder<ServerCommandSource, EntitySelector> target = CommandManager.argument("targets", EntityArgumentType.players());
        target.executes(ctx -> {
            Collection<ServerPlayerEntity> players = EntityArgumentType.getPlayers(ctx, "targets");
            ServerCommandSource source = ctx.getSource();
            boolean anyRecording = players.stream().anyMatch(TogetherServerNetwork::isPlayerRecording);
            if (anyRecording) {
                ArrayList<ServerPlayerEntity> stopping = new ArrayList<ServerPlayerEntity>();
                for (ServerPlayerEntity player : players) {
                    if (!TogetherServerNetwork.isPlayerRecording(player)) continue;
                    stopping.add(player);
                }
                TogetherServerNetwork.beginRecordingSession(stopping);
                for (ServerPlayerEntity player : stopping) {
                    TogetherServerNetwork.sendToggleRecording(player, false);
                }
            } else {
                for (ServerPlayerEntity player : players) {
                    TogetherServerNetwork.sendToggleRecording(player, true);
                }
            }
            return players.size();
        });
        dispatcher.register(((LiteralArgumentBuilder<ServerCommandSource>)CommandManager.literal("bbstogether").requires(src -> src.hasPermissionLevel(2))).then(CommandManager.literal("record").then(target)));

        /* /bbsop <player> [level] — set or query a player's BBS edit permission (0 = read-only). */
        RequiredArgumentBuilder<ServerCommandSource, EntitySelector> bbsopTarget = CommandManager.argument("targets", EntityArgumentType.players());
        /* No level argument -> query current level. */
        bbsopTarget.executes(ctx -> {
            Collection<ServerPlayerEntity> players = EntityArgumentType.getPlayers(ctx, "targets");
            ServerCommandSource source = ctx.getSource();
            for (ServerPlayerEntity player : players) {
                int level = BbsOpManager.get(player.getUuid());
                String msg = player.getGameProfile().getName() + ": BBS permission level " + level + " (0 = view only)";
                source.sendFeedback(() -> Text.literal(msg), false);
            }
            return players.size();
        });
        /* With a level argument -> set it. */
        bbsopTarget.then(CommandManager.argument("level", IntegerArgumentType.integer(0, 2)).executes(ctx -> {
            Collection<ServerPlayerEntity> players = EntityArgumentType.getPlayers(ctx, "targets");
            int level = IntegerArgumentType.getInteger(ctx, "level");
            ServerCommandSource source = ctx.getSource();
            for (ServerPlayerEntity player : players) {
                BbsOpManager.set(player.getUuid(), level);
                String msg = "Set " + player.getGameProfile().getName() + "'s BBS permission level to " + level;
                source.sendFeedback(() -> Text.literal(msg), false);
                System.out.println("[BBSTogether] " + source.getName() + " set " + player.getGameProfile().getName() + " BBS op level to " + level);
            }
            /* Push immediately so every online client refreshes its UI lock state. */
            BbsOpManager.syncToAll(source.getServer());
            return players.size();
        }));
        dispatcher.register(((LiteralArgumentBuilder<ServerCommandSource>)CommandManager.literal("bbsop").requires(src -> src.hasPermissionLevel(2))).then(bbsopTarget));
    }
}
