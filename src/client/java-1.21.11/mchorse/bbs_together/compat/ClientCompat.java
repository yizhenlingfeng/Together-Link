package mchorse.bbs_together.compat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.util.Identifier;

/**
 * 1.21.11 overlay: these client APIs are stable enough to call directly. The
 * 1.20 build keeps the reflective bridge in java-1.20.
 */
public final class ClientCompat
{
    private ClientCompat()
    {}

    public static boolean isDebugHudVisible(MinecraftClient client)
    {
        return client.getDebugHud().shouldShowDebugHud();
    }

    public static Identifier skinTexture(PlayerListEntry entry)
    {
        return entry.getSkinTextures().body().texturePath();
    }

    public static float frameDelta(MinecraftClient client)
    {
        return client.getRenderTickCounter().getDynamicDeltaTicks();
    }
}
