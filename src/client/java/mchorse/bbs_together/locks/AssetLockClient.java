package mchorse.bbs_together.locks;

import mchorse.bbs_together.bbsop.BbsOpClientState;
import mchorse.bbs_together.network.TogetherClientNetwork;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side mirror of the server's asset locks. Purely presentational: every
 * greyed-out button and every "locked" message reads this, while the server
 * re-checks each write. Locks only exist while connected to a Together server;
 * single-player and local worlds stay unlocked.
 */
@Environment(EnvType.CLIENT)
public final class AssetLockClient
{
    private static final ConcurrentHashMap<String, AssetLock> locks = new ConcurrentHashMap<>();
    /** Film currently open in the editor; lets the existing per-domain read-only UI follow a film lock. */
    private static volatile String currentFilmId;

    private AssetLockClient()
    {}

    public static AssetLock get(AssetLockType type, String assetId)
    {
        if (type == null || assetId == null)
        {
            return null;
        }

        return locks.get(AssetLockManager.key(type, assetId));
    }

    /** Replace the whole mirror with a server push. Called on the client thread. */
    public static void sync(Collection<AssetLock> values)
    {
        locks.clear();

        for (AssetLock lock : values)
        {
            if (lock != null && lock.type != null && lock.assetId != null)
            {
                locks.put(AssetLockManager.key(lock.type, lock.assetId), lock);
            }
        }
    }

    public static void reset()
    {
        locks.clear();
        currentFilmId = null;
    }

    /** Called when the film editor fills / closes; null clears the current film. */
    public static void setCurrentFilm(String filmId)
    {
        currentFilmId = filmId;
    }

    /** Whether the film currently open in the editor is locked against this player. */
    public static boolean isCurrentFilmLocked()
    {
        String filmId = currentFilmId;

        return filmId != null && !canEdit(AssetLockType.FILM, filmId);
    }

    public static UUID self()
    {
        MinecraftClient client = MinecraftClient.getInstance();

        return client.player == null ? null : client.player.getUuid();
    }

    /** Level-2 admins are the only ones who see or use the lock UI. */
    public static boolean canManage()
    {
        return TogetherClientNetwork.isConnected() && BbsOpClientState.canManage();
    }

    public static boolean canEdit(AssetLockType type, String assetId)
    {
        if (!TogetherClientNetwork.isConnected())
        {
            return true;
        }

        if (BbsOpClientState.getLevel() >= 2)
        {
            return true;
        }

        AssetLock lock = get(type, assetId);

        if (lock == null)
        {
            return true;
        }

        return lock.allows(self());
    }

    /** Whether the current player may edit a model whose asset path starts with models/&lt;id&gt;. */
    public static boolean canEditModelPath(String relativePath)
    {
        String modelId = mchorse.bbs_together.utils.ModelPathUtils.extractModelId(relativePath);

        return modelId == null || canEdit(AssetLockType.MODEL, modelId);
    }

    /**
     * Optimistically apply a change made by this client so the UI reacts before the
     * server's authoritative push arrives. The server push overwrites this a moment later.
     */
    public static void applyLocal(AssetLockType type, String assetId, AssetLockMode mode, Map<UUID, String> members)
    {
        if (type == null || assetId == null || mode == null)
        {
            removeLocal(type, assetId);
            return;
        }

        AssetLock lock = new AssetLock(type, assetId);
        AssetLock previous = get(type, assetId);
        UUID self = self();

        lock.owner = self;
        lock.ownerName = self == null ? "" : MinecraftClient.getInstance().player.getGameProfile().getName();
        lock.mode = mode;

        if (previous != null)
        {
            lock.owner = previous.owner;
            lock.ownerName = previous.ownerName;
            lock.whitelist.putAll(previous.whitelist);
            lock.blacklist.putAll(previous.blacklist);
        }

        if (mode != AssetLockMode.ALL && members != null)
        {
            lock.listFor(mode).clear();
            lock.listFor(mode).putAll(members);
        }

        locks.put(AssetLockManager.key(type, assetId), lock);
    }

    public static void removeLocal(AssetLockType type, String assetId)
    {
        if (type != null && assetId != null)
        {
            locks.remove(AssetLockManager.key(type, assetId));
        }
    }
}
