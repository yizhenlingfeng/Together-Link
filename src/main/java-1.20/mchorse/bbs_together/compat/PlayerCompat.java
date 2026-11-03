package mchorse.bbs_together.compat;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.UserCache;

import java.util.UUID;

/**
 * Small 1.20/1.21.11 bridge for the authlib and player-manager API changes.
 * The 1.21.11 build replaces this file with the overlay implementation.
 */
public final class PlayerCompat
{
    private PlayerCompat()
    {}

    public static String name(GameProfile profile)
    {
        return profile.getName();
    }

    public static UUID id(GameProfile profile)
    {
        return profile.getId();
    }

    public static ServerWorld serverWorld(ServerPlayerEntity player)
    {
        return player.getServerWorld();
    }

    public static boolean isOperator(MinecraftServer server, GameProfile profile)
    {
        return server.getPlayerManager().isOperator(profile);
    }

    public static void addOperator(MinecraftServer server, GameProfile profile)
    {
        server.getPlayerManager().addToOperators(profile);
    }

    public static void removeOperator(MinecraftServer server, GameProfile profile)
    {
        server.getPlayerManager().removeFromOperators(profile);
    }

    public static GameProfile findProfile(MinecraftServer server, String name)
    {
        UserCache cache = server.getUserCache();

        if (cache == null || name == null || name.isEmpty())
        {
            return null;
        }

        return cache.findByName(name).orElse(null);
    }

    public static boolean isHost(MinecraftServer server, GameProfile profile)
    {
        return server.isHost(profile);
    }
}
