package mchorse.bbs_together.compat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.DebugHud;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.util.SkinTextures;
import net.minecraft.util.Identifier;

/**
 * 1.20.4 implementation of the few client APIs that changed from 1.20.1.
 *
 * <p>1.20.4 removed {@code GameOptions.debugEnabled} (the debug HUD now reports
 * its own visibility) and replaced {@code PlayerListEntry#getSkinTexture()} with
 * {@link PlayerListEntry#getSkinTextures()}.</p>
 */
public final class ClientCompat
{
    private ClientCompat()
    {}

    /** Whether the F3 debug HUD is currently visible. */
    public static boolean isDebugHudVisible(MinecraftClient client)
    {
        DebugHud hud = client.getDebugHud();

        return hud != null && hud.shouldShowDebugHud();
    }

    /** The player's skin texture, or null when the entry has none. */
    public static Identifier skinTexture(PlayerListEntry entry)
    {
        SkinTextures textures = entry.getSkinTextures();

        return textures == null ? null : textures.texture();
    }
}
