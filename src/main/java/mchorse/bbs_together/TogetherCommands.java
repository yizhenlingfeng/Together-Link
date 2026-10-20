package mchorse.bbs_together;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import java.util.ArrayList;
import java.util.Collection;
import mchorse.bbs_together.bbsop.BbsOpDomain;
import mchorse.bbs_together.bbsop.BbsOpManager;
import mchorse.bbs_together.locks.AssetTombstones;
import mchorse.bbs_together.network.CoopSessionManager;
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
            /* Coop recording sessions own their members' recording flow; the command must
             * not remote-toggle someone a coop session is driving (double bookkeeping). */
            players = players.stream()
                .filter(p -> !CoopSessionManager.isPlayerInSession(p.getUuid()))
                .collect(java.util.stream.Collectors.toList());
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

        /* /bbstogether restore <model> — clear a persistent delete tombstone so a model
         * may be uploaded again. The deleted files themselves are not recreated. */
        dispatcher.register(((LiteralArgumentBuilder<ServerCommandSource>)CommandManager.literal("bbstogether")
            .requires(src -> src.hasPermissionLevel(2) || (src.getPlayer() != null && BbsOpManager.canManage(src.getPlayer()))))
            .then(CommandManager.literal("restore").then(CommandManager.argument("model", StringArgumentType.greedyString()).executes(ctx -> {
                String modelId = StringArgumentType.getString(ctx, "model");
                ServerCommandSource source = ctx.getSource();

                if (!AssetTombstones.restore(modelId)) {
                    source.sendError(Text.literal("No delete tombstone for model \"" + modelId + "\""));
                    return 0;
                }

                TogetherServerNetwork.broadcastAssetsChanged(source.getServer());
                source.sendFeedback(() -> Text.literal("Tombstone cleared for \"" + modelId + "\". Deleted files are not restored; clients may upload the model again."), true);
                return 1;
            }))));

        /* E1: /bbstogether diag — where the asset bytes are going right now. */
        dispatcher.register(((LiteralArgumentBuilder<ServerCommandSource>)CommandManager.literal("bbstogether").requires(src -> src.hasPermissionLevel(2))).then(CommandManager.literal("diag").executes(ctx -> {
            for (String line : TogetherServerNetwork.diagLines(ctx.getSource().getServer())) {
                ctx.getSource().sendFeedback(() -> Text.literal(line), false);
            }
            return 1;
        })));

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
            TogetherServerNetwork.broadcastPermissions(source.getServer());
            return players.size();
        }));
        /* /bbsop <player> grant <domain> <allow|deny> — control one editable domain of a level-1 player. */
        bbsopTarget.then(CommandManager.literal("grant").then(CommandManager.argument("domain", StringArgumentType.word()).then(CommandManager.argument("mode", StringArgumentType.word()).executes(ctx -> {
            Collection<ServerPlayerEntity> players = EntityArgumentType.getPlayers(ctx, "targets");
            String domainId = StringArgumentType.getString(ctx, "domain");
            String mode = StringArgumentType.getString(ctx, "mode");
            ServerCommandSource source = ctx.getSource();
            mchorse.bbs_together.bbsop.BbsOpDomain domain = BbsOpDomain.byId(domainId);
            if (domain == null) {
                source.sendError(Text.literal("Unknown domain: " + domainId + " (valid: morph, film, model_blocks, particles, model_editor, textures, audio, upload)"));
                return 0;
            }
            boolean allowed;
            if (mode.equalsIgnoreCase("allow") || mode.equalsIgnoreCase("on") || mode.equalsIgnoreCase("true")) {
                allowed = true;
            } else if (mode.equalsIgnoreCase("deny") || mode.equalsIgnoreCase("off") || mode.equalsIgnoreCase("false")) {
                allowed = false;
            } else {
                source.sendError(Text.literal("Mode must be allow or deny"));
                return 0;
            }
            for (ServerPlayerEntity player : players) {
                BbsOpManager.setGrant(player.getUuid(), domain, allowed);
                String msg = (allowed ? "Allowed " : "Denied ") + domain.id + " for " + player.getGameProfile().getName();
                source.sendFeedback(() -> Text.literal(msg), false);
            }
            BbsOpManager.syncToAll(source.getServer());
            return players.size();
        })).then(CommandManager.literal("reset").executes(ctx -> {
            Collection<ServerPlayerEntity> players = EntityArgumentType.getPlayers(ctx, "targets");
            ServerCommandSource source = ctx.getSource();
            for (ServerPlayerEntity player : players) {
                BbsOpManager.resetGrants(player.getUuid());
                String msg = "Reset domain grants for " + player.getGameProfile().getName() + " (unrestricted again)";
                source.sendFeedback(() -> Text.literal(msg), false);
            }
            BbsOpManager.syncToAll(source.getServer());
            return players.size();
        }))));
        dispatcher.register(((LiteralArgumentBuilder<ServerCommandSource>)CommandManager.literal("bbsop").requires(TogetherCommands::canUseBbsOp)).then(bbsopTarget));
    }

    /**
     * /bbsop gate: server console (or command blocks), vanilla OP level 2+ (the server
     * owner), or a player whose BBS op level is 2. Everyone else — including plain
     * vanilla OPs of lower level and BBS level-1 editors — is rejected.
     */
    private static boolean canUseBbsOp(ServerCommandSource source) {
        if (!source.isExecutedByPlayer()) {
            return true;
        }
        if (source.hasPermissionLevel(2)) {
            return true;
        }
        ServerPlayerEntity player = source.getPlayer();
        return BbsOpManager.get(player.getUuid()) == BbsOpManager.LEVEL_ADMIN;
    }
}
