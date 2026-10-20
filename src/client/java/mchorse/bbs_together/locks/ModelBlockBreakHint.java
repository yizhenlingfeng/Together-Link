package mchorse.bbs_together.locks;

import mchorse.bbs_mod.l10n.L10n;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

/**
 * Local action-bar hint for the client-side break guard. The guard stops the break
 * before a packet is sent, so the server never sees the attempt and cannot answer with
 * its usual chat notice; this keeps the player informed without spamming.
 */
@Environment(EnvType.CLIENT)
public final class ModelBlockBreakHint
{
    private static long lastNotice;

    private ModelBlockBreakHint()
    {}

    public static void showLocked()
    {
        long now = System.currentTimeMillis();

        if (now - lastNotice < 1000L)
        {
            return;
        }

        lastNotice = now;

        MinecraftClient client = MinecraftClient.getInstance();

        if (client.inGameHud != null)
        {
            String label = L10n.lang("bbs_together.locks.notify.model_block_locked").get();

            client.inGameHud.setOverlayMessage(Text.literal(label), false);
        }
    }
}
