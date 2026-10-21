package mchorse.bbs_together.compat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.util.Identifier;

/**
 * 1.20.1 implementation of the few client APIs that changed in 1.20.4.
 *
 * <p>The shared code calls this class; the 1.20.4 source set provides the same
 * class with the newer APIs. Keeping the differences here means the rest of the
 * mod is compiled from one tree for both versions.</p>
 */
public final class ClientCompat
{
    private ClientCompat()
    {}

    /** Whether the F3 debug HUD is currently visible. */
    public static boolean isDebugHudVisible(MinecraftClient client)
    {
        return client.options.debugEnabled;
    }

    /** The player's skin texture, or null when the entry has none. */
    public static Identifier skinTexture(PlayerListEntry entry)
    {
        return entry.getSkinTexture();
    }
}
