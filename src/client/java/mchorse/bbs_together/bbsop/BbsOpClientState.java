package mchorse.bbs_together.bbsop;

import mchorse.bbs_together.network.TogetherClientNetwork;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Client-side mirror of the local player's BBS edit level, pushed by the server via
 * {@code c_bbsop_sync}. This is purely the presentation layer: every greyed-out button,
 * overlay and lock icon reads {@link #isReadOnly()}; the server still re-checks on every
 * write packet, so a tampered client cannot edit even if it hides the overlay.
 *
 * <p>Read-only only applies while connected to a BBS-Together server. In singleplayer /
 * local world everything stays unlocked, matching how the other mixins gate on
 * {@link TogetherClientNetwork#isConnected()}.</p>
 */
@Environment(EnvType.CLIENT)
public final class BbsOpClientState
{
    /** Local player's level as last pushed by the server; 0 = read-only. */
    private static volatile int level = 0;

    private BbsOpClientState() {}

    public static void setLevel(int newLevel)
    {
        level = newLevel;
    }

    public static int getLevel()
    {
        return level;
    }

    /**
     * Whether the BBS UI should be locked to view-only right now: connected to a
     * BBS-Together server <em>and</em> the server says level 0. Levels 1/2 are
     * reserved and currently treated as unlocked (open for editing).
     */
    public static boolean isReadOnly()
    {
        return TogetherClientNetwork.isConnected() && level == 0;
    }

    /** Reset on disconnect, so singleplayer / local worlds never stay locked. */
    public static void reset()
    {
        level = 0;
    }
}
