package mchorse.bbs_together.compat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.util.Identifier;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Bridges the few client APIs that differ between Minecraft 1.20.1 and 1.20.4.
 *
 * <p>The mod ships one jar for both versions, compiled against 1.20.1, so anything that
 * changed between them is looked up reflectively here. A released instance runs the game
 * under intermediary names ({@code method_52810}), a development instance under Yarn names
 * ({@code getSkinTextures}), and reflection is not remapped — hence every name list below
 * holds the Yarn name first and the intermediary name second.</p>
 */
public final class ClientCompat
{
    /* 1.20.4: MinecraftClient#getDebugHud() */
    private static final String[] DEBUG_HUD = {"getDebugHud", "method_53526"};
    /* 1.20.4: DebugHud#shouldShowDebugHud() */
    private static final String[] DEBUG_HUD_VISIBLE = {"shouldShowDebugHud", "method_53536"};
    /* 1.20.1: GameOptions#debugEnabled */
    private static final String[] DEBUG_ENABLED = {"debugEnabled", "field_1866"};
    /* 1.20.4: PlayerListEntry#getSkinTextures() */
    private static final String[] SKIN_TEXTURES = {"getSkinTextures", "method_52810"};
    /* 1.20.4: SkinTextures#texture() */
    private static final String[] SKIN_TEXTURE = {"texture", "comp_1626"};
    /* 1.20.1: PlayerListEntry#getSkinTexture() */
    private static final String[] PLAYER_SKIN_TEXTURE = {"getSkinTexture", "method_2968"};

    /* The runtime classes never change while the game runs, so each lookup happens once. */
    private static Class<?> resolvedClientClass;
    private static Method debugHudGetter;
    private static Method debugHudVisible;
    private static Field debugEnabled;

    private static Class<?> resolvedEntryClass;
    private static Method skinTexturesGetter;
    private static Method skinTextureAccessor;
    private static Method skinTextureGetter;

    private ClientCompat()
    {}

    /** Whether the F3 debug HUD is currently visible. */
    public static boolean isDebugHudVisible(MinecraftClient client)
    {
        resolveDebugHud(client);

        try
        {
            if (debugHudGetter != null)
            {
                Object hud = debugHudGetter.invoke(client);

                return hud != null && debugHudVisible != null && (Boolean) debugHudVisible.invoke(hud);
            }

            /* 1.20.1 has no DebugHud getter; the game options carry the flag */
            return debugEnabled != null && debugEnabled.getBoolean(client.options);
        }
        catch (ReflectiveOperationException | RuntimeException ignored)
        {
            return false;
        }
    }

    /** The player's skin texture, or null when the entry has none (an offline-mode
     * profile, or one whose skin the client has not seen yet). */
    public static Identifier skinTexture(PlayerListEntry entry)
    {
        resolveSkin(entry.getClass());

        try
        {
            /* 1.20.4: SkinTextures#texture() */
            if (skinTexturesGetter != null)
            {
                Object textures = skinTexturesGetter.invoke(entry);

                if (textures == null || skinTextureAccessor == null)
                {
                    return null;
                }

                return (Identifier) skinTextureAccessor.invoke(textures);
            }

            /* 1.20.1: the identifier itself */
            if (skinTextureGetter != null)
            {
                return (Identifier) skinTextureGetter.invoke(entry);
            }
        }
        catch (ReflectiveOperationException | RuntimeException ignored)
        {}

        return null;
    }

    private static synchronized void resolveDebugHud(MinecraftClient client)
    {
        if (client.getClass() == resolvedClientClass)
        {
            return;
        }

        resolvedClientClass = client.getClass();
        debugHudGetter = method(resolvedClientClass, DEBUG_HUD);
        debugHudVisible = debugHudGetter == null ? null : method(debugHudGetter.getReturnType(), DEBUG_HUD_VISIBLE);
        debugEnabled = debugHudGetter == null ? field(client.options.getClass(), DEBUG_ENABLED) : null;
    }

    private static synchronized void resolveSkin(Class<?> entryClass)
    {
        if (entryClass == resolvedEntryClass)
        {
            return;
        }

        resolvedEntryClass = entryClass;
        skinTexturesGetter = method(entryClass, SKIN_TEXTURES);
        skinTextureAccessor = skinTexturesGetter == null ? null : method(skinTexturesGetter.getReturnType(), SKIN_TEXTURE);
        skinTextureGetter = skinTexturesGetter == null ? method(entryClass, PLAYER_SKIN_TEXTURE) : null;
    }

    /** The first name that resolves to a public method on {@code owner}, or null. */
    private static Method method(Class<?> owner, String... names)
    {
        for (String name : names)
        {
            try
            {
                return owner.getMethod(name);
            }
            catch (NoSuchMethodException ignored)
            {}
        }

        return null;
    }

    /** The first name that resolves to a public field on {@code owner}, or null. */
    private static Field field(Class<?> owner, String... names)
    {
        for (String name : names)
        {
            try
            {
                return owner.getField(name);
            }
            catch (NoSuchFieldException ignored)
            {}
        }

        return null;
    }
}
