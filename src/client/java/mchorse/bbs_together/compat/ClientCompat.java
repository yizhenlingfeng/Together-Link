package mchorse.bbs_together.compat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.util.Identifier;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Bridges the few client APIs that differ between Minecraft 1.20.1 and 1.20.4.
 *
 * <p>The mod ships one jar for both versions, compiled against 1.20.1. Anything that
 * changed between the two versions is looked up reflectively here, so the shared code
 * never links against a method or field that only exists on one side.</p>
 */
public final class ClientCompat
{
    private ClientCompat()
    {}

    /** Whether the F3 debug HUD is currently visible. */
    public static boolean isDebugHudVisible(MinecraftClient client)
    {
        /* 1.20.4: MinecraftClient#getDebugHud().shouldShowDebugHud() */
        try
        {
            Method getDebugHud = client.getClass().getMethod("getDebugHud");
            Object hud = getDebugHud.invoke(client);

            if (hud != null)
            {
                Method shouldShow = hud.getClass().getMethod("shouldShowDebugHud");

                return (Boolean) shouldShow.invoke(hud);
            }
        }
        catch (ReflectiveOperationException ignored)
        {}

        /* 1.20.1: GameOptions#debugEnabled */
        try
        {
            Field debugEnabled = client.options.getClass().getField("debugEnabled");

            return debugEnabled.getBoolean(client.options);
        }
        catch (ReflectiveOperationException ignored)
        {
            return false;
        }
    }

    /** The player's skin texture, or null when the entry has none. */
    public static Identifier skinTexture(PlayerListEntry entry)
    {
        /* 1.20.4: PlayerListEntry#getSkinTextures().texture() */
        try
        {
            Method getSkinTextures = entry.getClass().getMethod("getSkinTextures");
            Object textures = getSkinTextures.invoke(entry);

            if (textures == null)
            {
                return null;
            }

            Method texture = textures.getClass().getMethod("texture");

            return (Identifier) texture.invoke(textures);
        }
        catch (ReflectiveOperationException ignored)
        {}

        /* 1.20.1: PlayerListEntry#getSkinTexture() */
        try
        {
            Method getSkinTexture = entry.getClass().getMethod("getSkinTexture");

            return (Identifier) getSkinTexture.invoke(entry);
        }
        catch (ReflectiveOperationException ignored)
        {
            return null;
        }
    }
}
