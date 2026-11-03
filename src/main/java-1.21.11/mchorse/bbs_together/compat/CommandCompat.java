package mchorse.bbs_together.compat;

import net.minecraft.command.permission.PermissionCheck;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;

/**
 * 1.21.11 overlay: command permissions are checks over the source predicate.
 */
public final class CommandCompat
{
    private CommandCompat()
    {}

    public static boolean hasPermission(ServerCommandSource source, int level)
    {
        PermissionCheck check = switch (level)
        {
            case 4 -> CommandManager.OWNERS_CHECK;
            case 3 -> CommandManager.ADMINS_CHECK;
            case 2 -> CommandManager.GAMEMASTERS_CHECK;
            case 1 -> CommandManager.MODERATORS_CHECK;
            default -> CommandManager.ALWAYS_PASS_CHECK;
        };

        return check.allows(source.getPermissions());
    }
}
