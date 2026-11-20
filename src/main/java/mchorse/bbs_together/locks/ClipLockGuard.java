package mchorse.bbs_together.locks;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_together.bbsop.BbsOpManager;
import mchorse.bbs_together.commits.FilmBranchStore;
import mchorse.bbs_together.compat.PlayerCompat;
import mchorse.bbs_together.network.TogetherServerNetwork;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server side of the per-clip lock: a player outside a clip's lock cannot edit, move or
 * remove that clip.
 *
 * <p>Camera clips reach the server over two paths, and both are checked here. A whole-film
 * snapshot (joining and leaving a film) is compared clip by clip against the branch working
 * copy, and a locked clip the player may not touch is restored from that copy instead of
 * failing the save. Live timeline edits arrive as path syncs; those name the clip by index,
 * so the index is looked up in the branch copy and the same lock decides. Locked clips are
 * matched by the lock's own UUID, since BBS renumbers clips whenever the list is reordered.</p>
 *
 * <p>Creating, changing and removing a lock is open to level 1; rewriting somebody else's
 * lock and ignoring a lock while editing stay with level 2. An automatic edit lock belongs
 * to the editor who opened the clip and only lives as long as that session.</p>
 */
public final class ClipLockGuard
{
    /** The film's camera clip list lives under this key (see {@code Film.camera}). */
    private static final String CAMERA = "camera";

    /** Throttles the "this clip is locked" notice per player. */
    private static final Map<UUID, Long> NOTICE_AT = new ConcurrentHashMap<>();

    /** Who is editing which clip right now, fed by the keyframe focus sync. */
    private static final Map<UUID, EditingClips> EDITING = new ConcurrentHashMap<>();

    private ClipLockGuard()
    {}

    /**
     * Forgets that a player is editing a clip once they leave every editor.
     *
     * <p>Which clip a player edits comes from the keyframe focus sync
     * ({@link #trackKeyframeFocus}): a clip that is merely selected in the timeline is not being
     * edited, so a selection alone never puts a player into an automatic lock's whitelist.</p>
     */
    public static void trackCursor(UUID playerId, int editorType)
    {
        if (playerId != null && editorType < 0)
        {
            EDITING.remove(playerId);
        }
    }

    /**
     * Records that a player has a clip's keyframe editor open, so that an automatic lock
     * created by another editor keeps them in its whitelist as a co-editor.
     *
     * <p>Focus sync carries the plain clip index (unlike the keyframe cursor, whose selection
     * is a keyframe encoding), so the index can be matched against the lock directly.</p>
     */
    public static void trackKeyframeFocus(UUID playerId, String filmId, String branch, int clipIndex)
    {
        if (playerId == null || filmId == null)
        {
            return;
        }

        if (clipIndex < 0)
        {
            EditingClips previous = EDITING.get(playerId);

            if (previous != null && previous.filmId.equals(filmId))
            {
                EDITING.remove(playerId);
            }

            return;
        }

        List<Integer> selected = new ArrayList<>();
        selected.add(clipIndex);

        EDITING.put(playerId, new EditingClips(filmId, branch, selected));
    }

    /**
     * Forgets a player and drops the automatic locks they still hold.
     *
     * <p>The client releases its lock when the keyframe editor closes, but a crash, a
     * disconnect or a branch switch leaves it behind. An automatic lock without an editor is
     * dead weight that would keep the clip closed for everyone, so it is removed here and the
     * editors of that branch are sent the unlocked clip.</p>
     */
    public static void forgetPlayer(MinecraftServer server, UUID playerId)
    {
        if (playerId == null)
        {
            return;
        }

        EditingClips editing = EDITING.remove(playerId);

        NOTICE_AT.remove(playerId);

        if (server == null || editing == null)
        {
            return;
        }

        for (Integer index : editing.selected)
        {
            releaseAutomaticLock(server, playerId, editing.filmId, editing.branch, index);
        }
    }

    /** Removes the automatic lock {@code playerId} holds on one clip, if it is still theirs. */
    private static void releaseAutomaticLock(MinecraftServer server, UUID playerId, String filmId, String branch, int index)
    {
        Film film = FilmBranchStore.working(filmId, branch);

        if (film == null || film.camera == null)
        {
            return;
        }

        List<Clip> clips = film.camera.get();

        if (index < 0 || index >= clips.size())
        {
            return;
        }

        Clip clip = clips.get(index);
        ClipLockData lock = clip == null ? null : ClipLockData.read((MapType) clip.toData());

        if (lock == null || !lock.automatic || !playerId.equals(lock.owner))
        {
            return;
        }

        Map<String, BaseType> foreign = KeyframeLockData.foreignOrNull(clip);

        if (foreign != null)
        {
            foreign.remove(ClipLockData.KEY);
        }

        TogetherServerNetwork.broadcastClipData(server, filmId, branch, index, clip.toData());
        TogetherServerNetwork.scheduleFilmSave(server, filmId, branch);
    }

    public static void reset()
    {
        EDITING.clear();
        NOTICE_AT.clear();
    }

    public static BaseType prepareAutomaticLock(MinecraftServer server, ServerPlayerEntity player, String filmId, String branch, List<String> path, BaseType payload)
    {
        if (player == null || path == null || path.size() != 2 || !CAMERA.equals(path.get(0))
            || payload == null || !payload.isMap())
        {
            return payload;
        }

        MapType map = payload.asMap();
        ClipLockData incoming = ClipLockData.read(map);

        if (incoming == null || !incoming.automatic)
        {
            return payload;
        }

        Clip clip = clipAt(filmId, branch, path.get(1));
        BaseType data = clip == null ? null : clip.toData();
        ClipLockData stored = data != null && data.isMap() ? ClipLockData.read(data.asMap()) : null;

        int index;

        try
        {
            index = Integer.parseInt(path.get(1));
        }
        catch (NumberFormatException e)
        {
            return payload;
        }

        if (stored != null)
        {
            if (!stored.automatic || !stored.clipId.equals(incoming.clipId))
            {
                return payload;
            }

            /* An automatic lock only lives as long as its owner is editing the clip. When the
             * owner left, crashed or disconnected without releasing it, the lock is dead weight
             * that would keep the clip closed forever, so the request taking the clip now
             * becomes its new owner. */
            if (!ownerEditing(stored, filmId, branch, index))
            {
                ClipLockData taken = createAutomaticLock(server, player, filmId, branch, index, incoming);

                return replaceLock(payload, incoming.toData(), taken.toData());
            }

            /* The stored lock stays authoritative: keeping the owner and adding the players who
             * are editing this clip right now is what lets a co-editor keep working without the
             * lock ever being handed over. */
            ClipLockData merged = mergeWhitelist(server, stored, filmId, branch, index);

            return replaceLock(payload, incoming.toData(), merged.toData());
        }

        if (!validAutomaticLock(player, incoming))
        {
            return payload;
        }

        ClipLockData created = createAutomaticLock(server, player, filmId, branch, index, incoming);

        return replaceLock(payload, incoming.toData(), created.toData());
    }

    private static BaseType replaceLock(BaseType payload, MapType before, MapType after)
    {
        if (sameValue(before, after))
        {
            return payload;
        }

        MapType copy = (MapType) payload.copy();
        copy.put(ClipLockData.KEY, after);

        return copy;
    }

    /**
     * Refreshes the members of a stored automatic lock.
     *
     * <p>The owner and the mode always stay those of the stored lock, so a client can never take
     * over someone else's automatic lock, and membership is computed from the server's own focus
     * tracking rather than from what a client sends.</p>
     */
    private static ClipLockData mergeWhitelist(MinecraftServer server, ClipLockData stored, String filmId, String branch, int index)
    {
        ClipLockData merged = new ClipLockData(stored.clipId);

        merged.owner = stored.owner;
        merged.ownerName = stored.ownerName;
        merged.mode = stored.mode;
        merged.automatic = stored.automatic;
        merged.whitelist.putAll(stored.whitelist);
        merged.blacklist.putAll(stored.blacklist);

        if (stored.mode == AssetLockMode.WHITELIST)
        {
            merged.whitelist.putAll(editors(server, filmId, branch, index));
        }

        return merged;
    }

    /** The online players the focus tracking says are editing this clip right now. */
    private static Map<UUID, String> editors(MinecraftServer server, String filmId, String branch, int index)
    {
        Map<UUID, String> editors = new LinkedHashMap<>();

        for (Map.Entry<UUID, EditingClips> entry : EDITING.entrySet())
        {
            EditingClips editing = entry.getValue();

            if (!editing.filmId.equals(filmId) || !editing.branch.equals(branch) || !editing.selected.contains(index))
            {
                continue;
            }

            ServerPlayerEntity editor = server.getPlayerManager().getPlayer(entry.getKey());

            if (editor != null)
            {
                editors.put(entry.getKey(), PlayerCompat.name(editor.getGameProfile()));
            }
        }

        return editors;
    }

    /** Whether an automatic lock's owner is still editing that very clip. */
    private static boolean ownerEditing(ClipLockData lock, String filmId, String branch, int index)
    {
        if (lock == null || lock.owner == null)
        {
            return false;
        }

        EditingClips editing = EDITING.get(lock.owner);

        return editing != null && editing.filmId.equals(filmId) && editing.branch.equals(branch) && editing.selected.contains(index);
    }

    private static ClipLockData createAutomaticLock(MinecraftServer server, ServerPlayerEntity player, String filmId, String branch, int index, ClipLockData source)
    {
        ClipLockData lock = new ClipLockData(source.clipId);
        lock.owner = player.getUuid();
        lock.ownerName = PlayerCompat.name(player.getGameProfile());
        lock.mode = AssetLockMode.WHITELIST;
        lock.automatic = true;
        lock.whitelist.put(lock.owner, lock.ownerName);
        lock.whitelist.putAll(editors(server, filmId, branch, index));

        return lock;
    }

    private static boolean validAutomaticLock(ServerPlayerEntity player, ClipLockData lock)
    {
        return lock != null && lock.automatic && lock.mode == AssetLockMode.WHITELIST && player.getUuid().equals(lock.owner);
    }

    private static boolean validAutomaticLock(ServerPlayerEntity player, boolean wholeClip, BaseType payload)
    {
        return wholeClip && payload != null && payload.isMap() && validAutomaticLock(player, ClipLockData.read(payload.asMap()));
    }

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
        List<MapType> storedClips = stored == null ? new ArrayList<>() : clips(stored);
        List<MapType> incomingClips = clips(incoming);

        for (int i = 0; i < incomingClips.size(); i++)
        {
            MapType newClip = incomingClips.get(i);
            ClipLockData lock = ClipLockData.read(newClip);

            if (lock == null)
            {
                continue;
            }

            ClipLockData previous = storedLock(storedClips, i, lock.clipId);

            if (previous == null && validAutomaticLock(player, lock))
            {
                MinecraftServer server = player.getEntityWorld() == null ? null : player.getEntityWorld().getServer();

                if (server != null)
                {
                    newClip.put(ClipLockData.KEY, createAutomaticLock(server, player, filmId, branch, i, lock).toData());
                    continue;
                }
            }

            if (previous == null)
            {
                /* The branch copy holds no lock on this clip yet, so a manual lock introduced here
                 * takes nothing away from anyone: creating locks is open to level 1. */
                if (BbsOpManager.canOperateLocks(player))
                {
                    continue;
                }

                return deny(player);
            }

            /* A stale automatic lock (its owner stopped editing, or left the server without
             * releasing it) may be taken over by the editor who opens the clip now. */
            if (previous.automatic && validAutomaticLock(player, lock) && !ownerEditing(previous, filmId, branch, i))
            {
                MinecraftServer server = player.getEntityWorld() == null ? null : player.getEntityWorld().getServer();

                if (server != null)
                {
                    newClip.put(ClipLockData.KEY, createAutomaticLock(server, player, filmId, branch, i, lock).toData());
                    continue;
                }
            }

            if (!sameValue(previous.toData(), lock.toData()))
            {
                return deny(player);
            }
        }

        if (stored != null)
        {
            restoreLockedClips(player, stored, incoming);
        }

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

            if (lock == null || allows(lock, player.getUuid()))
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
     * Whether a lock admits this player.
     *
     * <p>An automatic lock belongs to one editing session and is shared with the players who
     * were editing the clip when it was created; it admits them and nobody else, exactly like a
     * whitelist lock. The owner's own release removes it again.</p>
     */
    private static boolean allows(ClipLockData lock, UUID playerId)
    {
        return lock == null || lock.allows(playerId);
    }

    /**
     * Whether {@code player} is taking over an automatic lock whose owner stopped editing it.
     *
     * <p>Automatic locks are released when their owner closes the keyframe editor, so one that
     * survived means the owner left, crashed or was disconnected. Leaving it in place would
     * close the clip for good, therefore any level-1 editor may take it over. A lock whose
     * owner is still editing stays theirs.</p>
     */
    private static boolean staleAutomatic(ServerPlayerEntity player, ClipLockData stored, List<String> path, BaseType payload, String filmId, String branch)
    {
        if (stored == null || !stored.automatic || !validAutomaticLock(player, path.size() == 2, payload))
        {
            return false;
        }

        if (!BbsOpManager.canOperateLocks(player))
        {
            return false;
        }

        int index;

        try
        {
            index = Integer.parseInt(path.get(1));
        }
        catch (NumberFormatException e)
        {
            return false;
        }

        return !ownerEditing(stored, filmId, branch, index);
    }

    /**
     * Whether a path sync from {@code player} may be applied to the camera timeline.
     *
     * <p>Camera edits are relayed to the other editors rather than merged into the branch
     * working copy the way replays are, so this compares the synced clip index against the
     * server's copy: a clip carrying a lock the player may not touch cannot be written, and a
     * lock may be added by any editor, while changing or removing an existing one needs its
     * owner or a level-2 manager. An index
     * the server has never seen is let through, because its copy lags behind a live camera
     * session and there is nothing authoritative to compare against.</p>
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

        /* The server's copy does not have this clip yet: there is no lock to compare against, so
         * only the payload carrying a lock is looked at. A free clip carries no lock, so adding
         * one takes nothing away from another player and any editor may do it. */
        if (clip == null)
        {
            return validAutomaticLock(player, wholeClip, payload) || !(wholeClip && introducesLock)
                || BbsOpManager.canOperateLocks(player);
        }

        BaseType data = clip.toData();
        ClipLockData stored = data != null && data.isMap() ? ClipLockData.read(data.asMap()) : null;

        /* A clip nobody has locked yet may be edited by anyone, and any editor may lock it: the
         * lock only restricts whoever comes next. Rewriting an existing lock stays a level-2
         * action and is checked below. */
        if (stored == null)
        {
            return validAutomaticLock(player, wholeClip, payload) || !introducesLock
                || BbsOpManager.canOperateLocks(player);
        }

        if (staleAutomatic(player, stored, path, payload, filmId, branch))
        {
            return true;
        }

        if (wholeClip && introducesLock && !BbsOpManager.canManage(player))
        {
            ClipLockData incoming = ClipLockData.read(payload.asMap());

            /* Only the lock itself is compared here, never the clip content around it: a player
             * re-broadcasting a clip they legitimately edit (the automatic lock rides along with
             * every clip sync) must not be refused for the edit that ships in the same payload. */
            boolean shares = incoming != null && stored.automatic && stored.owner != null
                && stored.owner.equals(incoming.owner) && incoming.automatic;

            if (!shares && (incoming == null || !sameValue(stored.toData(), incoming.toData())))
            {
                return deny(player);
            }
        }

        if (BbsOpManager.canManage(player) || allows(stored, player.getUuid()))
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

    /**
     * Finds the branch copy's lock for one incoming clip: by the lock's own id first, then by the
     * clip's position.
     *
     * <p>The id match survives a reorder (BBS renumbers clips by index on every {@code sync()},
     * which also happens on load), while the index fallback covers a clip whose lock reached the
     * client but not the branch copy yet, so a player is not refused for a difference that is
     * only about how the two copies number the same clip.</p>
     */
    private static ClipLockData storedLock(List<MapType> storedClips, int index, String clipId)
    {
        for (MapType clip : storedClips)
        {
            ClipLockData lock = ClipLockData.read(clip);

            if (lock != null && lock.clipId.equals(clipId))
            {
                return lock;
            }
        }

        if (index >= 0 && index < storedClips.size())
        {
            return ClipLockData.read(storedClips.get(index));
        }

        return null;
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

    public static boolean sameClipContent(BaseType a, BaseType b)
    {
        return a != null && b != null && a.isMap() && b.isMap() && sameClipData(a.asMap(), b.asMap());
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

    private static final class EditingClips
    {
        private final String filmId;
        private final String branch;
        private final List<Integer> selected;

        private EditingClips(String filmId, String branch, List<Integer> selected)
        {
            this.filmId = filmId;
            this.branch = branch;
            this.selected = selected;
        }
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
