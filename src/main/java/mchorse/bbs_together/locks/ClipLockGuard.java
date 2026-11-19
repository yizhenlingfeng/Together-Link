package mchorse.bbs_together.locks;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_together.bbsop.BbsOpManager;
import mchorse.bbs_together.commits.FilmBranchStore;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server side of the per-clip lock: a player outside a clip's lock cannot edit,
 * move or remove that clip.
 *
 * <p>Camera clips reach the server over two paths, and both are checked here. A
 * whole-film snapshot (joining and leaving a film) is compared clip by clip against
 * the branch working copy, and a locked clip the player may not touch is restored
 * from that copy instead of failing the save. Live timeline edits arrive as path
 * syncs; those name the clip by index, so the index is looked up in the branch copy
 * and the same lock decides. Locked clips are matched by the lock's own UUID, since
 * BBS renumbers clips by index whenever the list is reordered.</p>
 *
 * <p>Creating, changing and removing a lock is reserved to level 2, exactly like the
 * film and model lock menus.</p>
 */
public final class ClipLockGuard
{
    /** The film's camera clip list lives under this key (see {@code Film.camera}). */
    private static final String CAMERA = "camera";

    private static final Map<UUID, Long> NOTICE_AT = new ConcurrentHashMap<>();

    private ClipLockGuard()
    {}

    /**
     * Checks the camera clips of a snapshot about to be stored.
     *
     * <p>Only the lock itself is refused: a clip locked for this player is restored from
     * the branch copy instead of failing the whole save, because dropping a snapshot would
     * also drop every other, legitimate edit it carries. The restored copy keeps the lock
     * and the locked content exactly as the branch holds them.</p>
     *
     * @param incoming the film map the client is about to store, edited in place
     * @return whether this snapshot may be written
     */
    public static boolean canWrite(ServerPlayerEntity player, String filmId, String branch, MapType incoming)
    {
        if (player == null || incoming == null)
        {
            return true;
        }

        /* Level 2 owns every clip, exactly like the film and model locks. */
        if (BbsOpManager.canManage(player))
        {
            return true;
        }

        MapType stored = FilmBranchStore.workingData(filmId, branch);

        /* Nothing to protect on the first save of a branch. */
        if (stored == null)
        {
            return true;
        }

        Map<String, MapType> before = index(stored);

        /* Re-locking, changing and unlocking are level-2 operations as well. */
        for (MapType newClip : clips(incoming))
        {
            ClipLockData lock = ClipLockData.read(newClip);

            if (lock == null)
            {
                continue;
            }

            ClipLockData previous = ClipLockData.read(before.get(lock.clipId));

            if (previous == null || !sameValue(previous.toData(), lock.toData()))
            {
                return deny(player);
            }
        }

        restoreLockedClips(player, stored, incoming);

        return true;
    }

    /**
     * Rewrites every locked clip this player may not edit to the branch's version: deleting,
     * moving, resizing or keyframing it in the incoming snapshot has no effect, while the
     * clip stays where it is for everyone else.
     */
    private static void restoreLockedClips(ServerPlayerEntity player, MapType stored, MapType incoming)
    {
        BaseType camera = incoming.get(CAMERA);

        if (camera == null || !camera.isList())
        {
            return;
        }

        ListType incomingClips = camera.asList();
        ListType storedClips = clipsList(stored);
        boolean restored = false;

        for (int i = 0; i < storedClips.size(); i++)
        {
            BaseType type = storedClips.get(i);

            if (!type.isMap())
            {
                continue;
            }

            MapType oldClip = type.asMap();
            ClipLockData lock = ClipLockData.read(oldClip);

            if (lock == null || lock.allows(player.getUuid()))
            {
                continue;
            }

            int at = indexOfLock(incomingClips, lock.clipId);
            BaseType current = at < 0 ? null : incomingClips.get(at);

            if (current != null && current.isMap() && sameClipData(oldClip, current.asMap()))
            {
                continue;
            }

            if (at < 0)
            {
                incomingClips.add(Math.min(i, incomingClips.size()), oldClip.copy());
            }
            else
            {
                incomingClips.remove(at);
                incomingClips.add(at, oldClip.copy());
            }

            restored = true;
        }

        if (restored)
        {
            notifyLocked(player);
        }
    }

    private static int indexOfLock(ListType clips, String clipId)
    {
        for (int i = 0; i < clips.size(); i++)
        {
            BaseType type = clips.get(i);

            if (!type.isMap())
            {
                continue;
            }

            ClipLockData lock = ClipLockData.read(type.asMap());

            if (lock != null && lock.clipId.equals(clipId))
            {
                return i;
            }
        }

        return -1;
    }

    /**
     * Whether a path sync from {@code player} may be applied to the camera timeline.
     *
     * <p>Camera edits are relayed to the other editors rather than merged into the branch
     * working copy the way replays are, so this compares the synced clip index against the
     * server's copy: a clip carrying a lock the player may not touch cannot be written, and
     * only level 2 may add, change or remove a lock. An index the server has never seen is
     * let through, because its copy lags behind a live camera session and there is nothing
     * authoritative to compare against.</p>
     *
     * @param path    the data path of the synced value, {@code ["camera", "<index>", ...]}
     * @param payload the value being written
     */
    public static boolean canSyncCamera(ServerPlayerEntity player, String filmId, String branch, List<String> path, BaseType payload)
    {
        if (player == null || path == null || path.size() < 2 || !CAMERA.equals(path.get(0)))
        {
            return true;
        }

        boolean wholeClip = path.size() == 2;
        boolean introducesLock = payload != null && payload.isMap() && payload.asMap().has(ClipLockData.KEY);
        Clip clip = clipAt(filmId, branch, path.get(1));

        /* The server's copy does not have this clip yet: only the lock payload needs a check. */
        if (clip == null)
        {
            return !(wholeClip && introducesLock) || BbsOpManager.canManage(player);
        }

        BaseType data = clip.toData();
        ClipLockData stored = data != null && data.isMap() ? ClipLockData.read(data.asMap()) : null;

        /* A clip nobody has locked yet may be edited, but a lock may only be created by level 2. */
        if (stored == null)
        {
            return !introducesLock || BbsOpManager.canManage(player);
        }

        if (BbsOpManager.canManage(player) || stored.allows(player.getUuid()))
        {
            return true;
        }

        return deny(player);
    }

    private static Clip clipAt(String filmId, String branch, String index)
    {
        Film film = FilmBranchStore.working(filmId, branch);

        if (film == null || film.camera == null)
        {
            return null;
        }

        int i;

        try
        {
            i = Integer.parseInt(index);
        }
        catch (NumberFormatException e)
        {
            return null;
        }

        List<Clip> clips = film.camera.get();

        return i < 0 || i >= clips.size() ? null : clips.get(i);
    }

    private static Map<String, MapType> index(MapType film)
    {
        Map<String, MapType> output = new HashMap<>();

        for (MapType clip : clips(film))
        {
            ClipLockData lock = ClipLockData.read(clip);

            if (lock != null)
            {
                output.put(lock.clipId, clip);
            }
        }

        return output;
    }

    private static ListType clipsList(MapType film)
    {
        BaseType camera = film.get(CAMERA);

        return camera != null && camera.isList() ? camera.asList() : new ListType();
    }

    private static List<MapType> clips(MapType film)
    {
        List<MapType> output = new ArrayList<>();

        for (BaseType type : clipsList(film))
        {
            if (type.isMap())
            {
                output.add(type.asMap());
            }
        }

        return output;
    }

    /** Compare two clip maps, skipping the lock key itself (checked separately). */
    private static boolean sameClipData(MapType a, MapType b)
    {
        int extraA = (a.has(ClipLockData.KEY) ? 1 : 0) + (a.has(KeyframeLockData.KEY) ? 1 : 0);
        int extraB = (b.has(ClipLockData.KEY) ? 1 : 0) + (b.has(KeyframeLockData.KEY) ? 1 : 0);

        if (a.size() - extraA != b.size() - extraB)
        {
            return false;
        }

        for (String key : a.keys())
        {
            if (key.equals(ClipLockData.KEY) || key.equals(KeyframeLockData.KEY))
            {
                continue;
            }

            if (!b.has(key) || !sameValue(a.get(key), b.get(key)))
            {
                return false;
            }
        }

        return true;
    }

    /**
     * Structural comparison that treats every numeric type as a double: data
     * round-tripped through a client can come back as a different numeric width
     * without the value having changed.
     */
    private static boolean sameValue(BaseType a, BaseType b)
    {
        if (a == null || b == null)
        {
            return a == b;
        }

        if (a.isMap() && b.isMap())
        {
            MapType mapA = a.asMap();
            MapType mapB = b.asMap();

            if (mapA.size() != mapB.size())
            {
                return false;
            }

            for (String key : mapA.keys())
            {
                if (!mapB.has(key) || !sameValue(mapA.get(key), mapB.get(key)))
                {
                    return false;
                }
            }

            return true;
        }

        if (a.isList() && b.isList())
        {
            ListType listA = a.asList();
            ListType listB = b.asList();

            if (listA.size() != listB.size())
            {
                return false;
            }

            for (int i = 0; i < listA.size(); i++)
            {
                if (!sameValue(listA.get(i), listB.get(i)))
                {
                    return false;
                }
            }

            return true;
        }

        if (a.isNumeric() && b.isNumeric())
        {
            return a.asNumeric().doubleValue() == b.asNumeric().doubleValue();
        }

        if (a.isString() && b.isString())
        {
            return a.asString().equals(b.asString());
        }

        String sa = a.toString();
        String sb = b.toString();

        return sa != null && sa.equals(sb);
    }

    private static boolean deny(ServerPlayerEntity player)
    {
        notifyLocked(player);

        return false;
    }

    /** Tell the player why the save was refused, at most once every few seconds. */
    private static void notifyLocked(ServerPlayerEntity player)
    {
        UUID id = player.getUuid();
        long now = System.currentTimeMillis();
        Long last = NOTICE_AT.get(id);

        if (last != null && now - last < 5000L)
        {
            return;
        }

        NOTICE_AT.put(id, now);

        MinecraftServer server = player.getEntityWorld() == null ? null : player.getEntityWorld().getServer();

        if (server == null)
        {
            return;
        }

        Text message = Text.translatable("bbs_together.clip_lock.notify.locked").formatted(Formatting.YELLOW);

        server.execute(() -> player.sendMessage(message, false));
    }
}
