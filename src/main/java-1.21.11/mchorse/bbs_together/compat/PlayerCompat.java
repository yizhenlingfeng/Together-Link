package mchorse.bbs_together.compat;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerConfigEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

import java.util.UUID;

/**
 * 1.21.11 overlay: GameProfile is an authlib record and operator APIs use
 * PlayerConfigEntry.
 */
public final class PlayerCompat
{
    private PlayerCompat()
    {}

    private static PlayerConfigEntry entry(GameProfile profile)
    {
        return new PlayerConfigEntry(profile);
    }

    public static String name(GameProfile profile)
    {
        return profile.name();
    }

    public static UUID id(GameProfile profile)
    {
        return profile.id();
    }

    public static ServerWorld serverWorld(ServerPlayerEntity player)
    {
        return player.getEntityWorld();
    }

    public static boolean isOperator(MinecraftServer server, GameProfile profile)
    {
        return server.getPlayerManager().isOperator(entry(profile));
    }

    public static void addOperator(MinecraftServer server, GameProfile profile)
    {
        server.getPlayerManager().addToOperators(entry(profile));
    }

    public static void removeOperator(MinecraftServer server, GameProfile profile)
    {
        server.getPlayerManager().removeFromOperators(entry(profile));
    }

    public static GameProfile findProfile(MinecraftServer server, String name)
    {
        if (name == null || name.isEmpty())
        {
            return null;
        }

        PlayerConfigEntry entry = server.getApiServices().nameToIdCache().findByName(name).orElse(null);

        return entry == null ? null : new GameProfile(entry.id(), entry.name());
    }

    public static boolean isHost(MinecraftServer server, GameProfile profile)
    {
        return server.isHost(entry(profile));
    }
}
