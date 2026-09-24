package mchorse.bbs_together;

import mchorse.bbs_together.TogetherCommands;
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
            if (!"local".equals(handler.player.getIp())) {
                TogetherServerNetwork.sendAssetManifest(server, handler.player);
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> TogetherServerNetwork.clearPlayerState(handler.player, server));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> TogetherServerNetwork.reset());
    }
}

