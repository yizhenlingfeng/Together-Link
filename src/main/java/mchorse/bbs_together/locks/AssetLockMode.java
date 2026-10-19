package mchorse.bbs_together.locks;

/**
 * Who may edit a locked asset.
 *
 * <p>The lock owner and any BBS level-2 admin always keep edit access; these
 * modes only describe what happens to everybody else.</p>
 */
public enum AssetLockMode
{
    /** Nobody else may edit. */
    ALL,
    /** Only the players in the whitelist may edit. */
    WHITELIST,
    /** Everyone except the players in the blacklist may edit. */
    BLACKLIST;

    public static AssetLockMode byOrdinal(int ordinal)
    {
        AssetLockMode[] values = values();

        return ordinal < 0 || ordinal >= values.length ? null : values[ordinal];
    }
}
