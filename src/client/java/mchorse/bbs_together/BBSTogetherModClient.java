package mchorse.bbs_together;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.ui.framework.UIScreen;
import mchorse.bbs_mod.ui.framework.elements.utils.Batcher2D;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_together.network.TogetherClientNetwork;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;

@Environment(value=EnvType.CLIENT)
public class BBSTogetherModClient
implements ClientModInitializer {
    public void onInitializeClient() {
        TogetherClientNetwork.setup();
        BBSSettings.primaryColor.postCallback((value, fromFile) -> TogetherClientNetwork.sendPlayerColor((Integer)BBSSettings.primaryColor.get()));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.world != null) {
                TogetherClientNetwork.interpolateCursors(client.getLastFrameDuration());
            }
        });
        ClientPlayConnectionEvents.JOIN.register((handler, packetSender, client) -> TogetherClientNetwork.onJoin());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> TogetherClientNetwork.reset());
        HudRenderCallback.EVENT.register((drawContext, tickDelta) -> {
            int padding;
            if (!TogetherClientNetwork.isConnected()) {
                return;
            }
            if (!TogetherClientNetwork.damageControlActive) {
                return;
            }
            if (UIScreen.getCurrentMenu() != null) {
                return;
            }
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player == null) {
                return;
            }
            int x = padding = 8;
            int y = mc.getWindow().getScaledHeight() - Icons.LOCKED.h - padding;
            Batcher2D batcher = new Batcher2D(drawContext);
            batcher.box((float)(x - 3), (float)(y - 3), (float)(x + Icons.LOCKED.w + 3), (float)(y + Icons.LOCKED.h + 3), 0x55000000);
            batcher.icon(Icons.LOCKED, -1, (float)x, (float)y);
            batcher.flush();
        });
    }
}

