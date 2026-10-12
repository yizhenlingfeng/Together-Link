package mchorse.bbs_together;

import mchorse.bbs_together.TogetherCommands;
import mchorse.bbs_together.bbsop.BbsOpManager;
import mchorse.bbs_together.behaviors.BehaviorLogManager;
import mchorse.bbs_together.network.TogetherServerNetwork;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

public class BBSTogetherMod
implements ModInitializer {
    public static final String MOD_ID = "bbs_together";

    public void onInitialize() {
        TogetherServerNetwork.setup();
        CommandRegistrationCallback.EVENT.register(TogetherCommands::register);
        ServerPlayConnectionEvents.JOIN.register((handler, packetSender, server) -> {
            server.execute(() -> {
                /* B4: the asset manifest is client-pulled now — the client sends its cached
                 * manifest fingerprint right after joining, and identical manifests cost a
                 * round trip instead of a full re-send. */
                TogetherServerNetwork.sendInitialFilmSync(server, handler.player);
                BehaviorLogManager.onPlayerJoin(handler.player);
                TogetherServerNetwork.sendPermissions(server, handler.player);
                BbsOpManager.syncToPlayer(handler.player);
            });
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> TogetherServerNetwork.clearPlayerState(handler.player, server));
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            BehaviorLogManager.onServerStarted(server);
            BbsOpManager.onServerStarted(server);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            BehaviorLogManager.onServerStopping();
            BbsOpManager.onServerStopping();
            TogetherServerNetwork.flushPendingFilmSaves(server);
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            BehaviorLogManager.onServerStopped();
            BbsOpManager.onServerStopped();
            TogetherServerNetwork.reset();
        });
    }
}
