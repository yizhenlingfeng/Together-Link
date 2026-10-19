package mchorse.bbs_together.network;

import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Carries the requesting player through BBS' manager-data LOAD path.
 *
 * <p>BBS resolves the {@code FilmManager.load(id)} call inside a lambda of
 * {@code ServerNetwork.handleManagerDataPacket}, where the player is not a parameter. The
 * packet handler is synchronous, so a thread local set at its head is still available when the
 * lambda runs, and it lets the film load be branch-aware without reimplementing the packet.</p>
 */
public final class FilmLoadContext
{
    private static final ThreadLocal<ServerPlayerEntity> PLAYER = new ThreadLocal<ServerPlayerEntity>();
    /**
     * Guards the nested {@code FilmManager.load} that {@code FilmBranchStore} performs while it
     * is resolving a branch; without it the lazy branch load would call itself forever.
     */
    private static final ThreadLocal<Boolean> RESOLVING = new ThreadLocal<Boolean>();
    /** Same guard for the main-file write that a branch save performs as its last step. */
    private static final ThreadLocal<Boolean> SAVING = new ThreadLocal<Boolean>();

    private FilmLoadContext()
    {}

    public static void set(ServerPlayerEntity player)
    {
        PLAYER.set(player);
    }

    public static ServerPlayerEntity get()
    {
        return PLAYER.get();
    }

    public static void clear()
    {
        PLAYER.remove();
        RESOLVING.remove();
        SAVING.remove();
    }

    public static boolean beginBranchLoad()
    {
        if (Boolean.TRUE.equals(RESOLVING.get()))
        {
            return false;
        }

        RESOLVING.set(Boolean.TRUE);

        return true;
    }

    public static void endBranchLoad()
    {
        RESOLVING.remove();
    }

    public static boolean beginBranchSave()
    {
        if (Boolean.TRUE.equals(SAVING.get()))
        {
            return false;
        }

        SAVING.set(Boolean.TRUE);

        return true;
    }

    public static void endBranchSave()
    {
        SAVING.remove();
    }
}
