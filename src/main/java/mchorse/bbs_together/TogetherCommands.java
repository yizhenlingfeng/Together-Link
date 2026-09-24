package mchorse.bbs_together;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.util.ArrayList;
import java.util.Collection;
import mchorse.bbs_together.network.TogetherServerNetwork;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.EntitySelector;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;

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
    }
}
