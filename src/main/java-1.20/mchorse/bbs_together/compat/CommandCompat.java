package mchorse.bbs_together.compat;

import net.minecraft.server.command.ServerCommandSource;

/**
 * 1.20 permission check. The 1.21.11 overlay uses PermissionCheck instead.
 */
public final class CommandCompat
{
    private CommandCompat()
    {}

    public static boolean hasPermission(ServerCommandSource source, int level)
    {
        return source.hasPermissionLevel(level);
    }
}
