package mchorse.bbs_together.locks;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One locked asset: who locked it, how it is locked, and the two player lists
 * that back the whitelist / blacklist modes.
 *
 * <p>Both lists are kept even while the other mode is active, so switching a
 * lock from "only these players" to "everybody but these players" does not throw
 * away the list the owner already picked.</p>
 */
public class AssetLock
{
    public final AssetLockType type;
    public final String assetId;
    public UUID owner;
    public String ownerName = "";
    public AssetLockMode mode = AssetLockMode.ALL;

    /** Players explicitly allowed when {@link #mode} is {@link AssetLockMode#WHITELIST}. */
    public final Map<UUID, String> whitelist = new ConcurrentHashMap<>();
    /** Players explicitly denied when {@link #mode} is {@link AssetLockMode#BLACKLIST}. */
    public final Map<UUID, String> blacklist = new ConcurrentHashMap<>();

    public AssetLock(AssetLockType type, String assetId)
    {
        this.type = type;
        this.assetId = assetId;
    }

    public Map<UUID, String> listFor(AssetLockMode mode)
    {
        return mode == AssetLockMode.WHITELIST ? this.whitelist : this.blacklist;
    }

    public boolean allows(UUID playerId)
    {
        if (playerId == null)
        {
            return false;
        }

        /* The owner is not a special case: only the level-2 bypass in the managers
         * applies. If an owner is demoted, the lock applies to them like to everyone
         * else, which is what "nobody except level 2" means. */
        switch (this.mode)
        {
            case WHITELIST:
                return this.whitelist.containsKey(playerId);
            case BLACKLIST:
                return !this.blacklist.containsKey(playerId);
            case ALL:
            default:
                return false;
        }
    }
}
