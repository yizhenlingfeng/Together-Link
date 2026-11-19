package mchorse.bbs_together.locks;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_together.bbsop.BbsOpClientState;
import mchorse.bbs_together.compat.PlayerCompat;
import mchorse.bbs_together.mixin.ValueGroupForeignAccessor;
import mchorse.bbs_together.network.TogetherClientNetwork;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Client side of the per-clip lock. The lock lives inside the clip's own data
 * (see {@link ClipLockData}), so this class only reads and writes that one
 * foreign entry; the server re-checks every write through
 * {@link ClipLockGuard}.
 */
@Environment(EnvType.CLIENT)
public final class ClipLockClient
{
    private ClipLockClient()
    {}

    public static boolean canManage()
    {
        return AssetLockClient.canManage();
    }

    public static ClipLockData get(Clip clip)
    {
        if (clip == null)
        {
            return null;
        }

        Map<String, BaseType> foreign = ((ValueGroupForeignAccessor) (Object) clip).bbs_together$getForeign();

        return foreign == null ? null : ClipLockData.fromData(foreign.get(ClipLockData.KEY));
    }

    /** Whether the clip carries a lock at all, regardless of whether it applies to this player. */
    public static boolean isLocked(Clip clip)
    {
        return get(clip) != null;
    }

    /**
     * Whether the clip's lock actually keeps this player out. A clip locked for others but
     * open to this player reads as unlocked here, which is how its icon is picked.
     */
    public static boolean isLockedForSelf(Clip clip)
    {
        return !canEdit(clip);
    }

    public static boolean canEdit(Clip clip)
    {
        if (!TogetherClientNetwork.isConnected())
        {
            return true;
        }

        if (BbsOpClientState.getLevel() >= 2)
        {
            return true;
        }

        ClipLockData lock = get(clip);

        return lock == null || lock.allows(AssetLockClient.self());
    }

    /**
     * Create, update or remove ({@code mode == null}) the lock of a clip.
     * The caller still has to save the film and refresh the panel.
     */
    public static void apply(Film film, Clip clip, AssetLockMode mode, Map<UUID, String> members)
    {
        if (clip == null)
        {
            return;
        }

        Map<String, BaseType> foreign = ((ValueGroupForeignAccessor) (Object) clip).bbs_together$getForeign();

        if (mode == null)
        {
            if (foreign != null)
            {
                foreign.remove(ClipLockData.KEY);
            }

            broadcast(film, clip);

            return;
        }

        if (foreign == null)
        {
            foreign = new HashMap<>();
            ((ValueGroupForeignAccessor) (Object) clip).bbs_together$setForeign(foreign);
        }

        ClipLockData lock = get(clip);

        if (lock == null)
        {
            lock = new ClipLockData(ClipLockData.newId());
        }

        UUID self = AssetLockClient.self();

        if (self != null && lock.owner == null)
        {
            lock.owner = self;
            lock.ownerName = selfName();
        }

        lock.mode = mode;

        if (mode != AssetLockMode.ALL && members != null)
        {
            lock.listFor(mode).clear();
            lock.listFor(mode).putAll(members);
        }

        foreign.put(ClipLockData.KEY, lock.toData());

        broadcast(film, clip);
    }

    /**
     * Pushes the clip's lock to the other editors and to the server's branch copy.
     *
     * <p>Camera clips are not part of the full-film save (the server only merges replay
     * data there), so the lock has to travel over the same path sync channel the timeline
     * itself uses. The payload is lock-only: the receivers apply it onto their own clip
     * only because {@code ValueGroup.fromData} keeps values that are absent from the map,
     * so a partial map that carries just this key updates the lock and nothing else.</p>
     */
    public static void broadcast(Film film, Clip clip)
    {
        if (film == null || clip == null || film.camera == null || !TogetherClientNetwork.isConnected())
        {
            return;
        }

        int index = film.camera.getIndex(clip);

        if (index < 0)
        {
            return;
        }

        MapType payload = new MapType();
        Map<String, BaseType> foreign = ((ValueGroupForeignAccessor) (Object) clip).bbs_together$getForeign();

        for (Map.Entry<String, BaseType> entry : foreign.entrySet())
        {
            payload.put(entry.getKey(), entry.getValue().copy());
        }

        TogetherClientNetwork.sendSyncDataPayload(film.getId(), List.of("camera", String.valueOf(index)), payload);
    }

    private static String selfName()
    {
        MinecraftClient client = MinecraftClient.getInstance();

        try
        {
            return client.player == null ? "" : PlayerCompat.name(client.player.getGameProfile());
        }
        catch (Exception e)
        {
            return "";
        }
    }
}
