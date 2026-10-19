package mchorse.bbs_together.bbsop;

import mchorse.bbs_together.network.TogetherClientNetwork;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Client-side mirror of the local player's BBS edit level, pushed by the server via
 * {@code c_bbsop_sync}. This is purely the presentation layer: every greyed-out button,
 * overlay and lock icon reads {@link #canEdit(String)}; the server
 * still re-checks on every write packet, so a tampered client cannot edit even if it hides
 * the overlay.
 *
 * <p>Restrictions only apply while connected to a BBS-Together server. In singleplayer /
 * local world everything stays unlocked, matching how the other mixins gate on
 * {@link TogetherClientNetwork#isConnected()}.</p>
 *
 * <p>Levels: 0 = read-only, 1 = editor (every {@link BbsOpDomain} unless the server pushed
 * an explicit domain whitelist), 2 = admin (everything, plus managing other players).</p>
 */
@Environment(EnvType.CLIENT)
public final class BbsOpClientState
{
    /** Local player's level as last pushed by the server; 0 = read-only. */
    private static volatile int level = 0;
    /** Explicit domain whitelist for a restricted level-1 editor; null = unrestricted. */
    private static volatile Set<String> whitelist = null;

    private BbsOpClientState() {}

    /** Legacy single-value setter (kept for compatibility); clears any whitelist. */
    public static void setLevel(int newLevel)
    {
        level = newLevel;
        whitelist = null;
    }

    /** Applies a full sync from the server: level plus (optionally) the domain whitelist. */
    public static void sync(int newLevel, List<String> whitelistIds)
    {
        level = newLevel;
        whitelist = whitelistIds == null ? null : new HashSet<>(whitelistIds);
    }

    public static int getLevel()
    {
        return level;
    }

    /**
     * Whether the local player may currently control the given {@link BbsOpDomain} id
     * (use {@code BbsOpDomain.X.id}). Level 2 edits everything; level 1 edits everything
     * except whitelisted-away domains; level 0 edits nothing. Unconnected (singleplayer)
     * everything is allowed.
     */
    public static boolean canEdit(String domainId)
    {
        if (!TogetherClientNetwork.isConnected())
        {
            return true;
        }

        if (level >= 2)
        {
            return true;
        }

        if (level <= 0)
        {
            return false;
        }

        /* A per-asset lock narrows the domain further: a level-1 editor looking at a
         * locked film gets the same read-only UI a level-0 player sees. Admins bypass
         * locks above, and the server re-checks every write anyway. */
        if (BbsOpDomain.FILM.id.equals(domainId) && mchorse.bbs_together.locks.AssetLockClient.isCurrentFilmLocked())
        {
            return false;
        }

        Set<String> current = whitelist;

        return current == null || current.contains(domainId);
    }

    /**
     * Whether the local player may start a coop-recording invitation. Both level 1 and level 2
     * are allowed; domain whitelists only restrict what may be edited, not who may invite.
     */
    public static boolean canInviteCoop()
    {
        if (!TogetherClientNetwork.isConnected())
        {
            return true;
        }

        return level >= 1;
    }

    /**
     * Whether the local player may manage other players' BBS permissions: BBS level 2 only.
     * Vanilla OPs are deliberately excluded — they manage through the {@code /bbsop}
     * command instead. The server re-checks on every packet.
     */
    public static boolean canManage()
    {
        return level >= 2;
    }

    /** Reset on disconnect, so singleplayer / local worlds never stay locked. */
    public static void reset()
    {
        level = 0;
        whitelist = null;
    }
}
