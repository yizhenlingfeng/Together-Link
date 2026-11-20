package mchorse.bbs_together.locks;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_together.TogetherSettings;
import mchorse.bbs_together.bbsop.BbsOpClientState;
import mchorse.bbs_together.compat.PlayerCompat;
import mchorse.bbs_together.mixin.ValueGroupForeignAccessor;
import mchorse.bbs_together.network.TogetherClientNetwork;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;

import java.util.HashMap;
import java.util.LinkedHashMap;
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
     * The clip whose keyframe editor this player currently has open, tracked so that the
     * automatic edit lock can be released when they leave it.
     */
    private static Clip editSessionClip = null;

    /** Last keyframe-focus (film + index) pushed to the server, so focus is only sent when it changes. */
    private static int lastSentFocusIndex = Integer.MIN_VALUE;
    private static String lastSentFocusFilm = null;

    private static boolean editLockActive()
    {
        return TogetherClientNetwork.isConnected()
            && TogetherSettings.shouldEditLockClips()
            && TogetherClientNetwork.serverProtocolVersion() >= 6;
    }

    /**
     * Keeps this player's automatic edit lock in step with the keyframe editor they have open.
     *
     * <p>While a player has a clip's keyframe editor open and the setting is on, the clip is
     * locked for everyone else; closing the editor removes that automatic lock again. Only the
     * automatic lock this session created is removed — a manual lock from a level-2 admin is
     * left untouched.</p>
     *
     * <p>Called every frame with the index of the clip whose keyframe editor is open, or a
     * negative index when none is open.</p>
     */
    public static void syncEditSession(Film film, int index)
    {
        if (film == null || film.camera == null || !editLockActive())
        {
            clearEditSession();
            sendFocusIfChanged(film, -1);
            return;
        }

        sendFocusIfChanged(film, index);

        List<Clip> clips = film.camera.get();
        Clip target = index >= 0 && index < clips.size() ? clips.get(index) : null;

        if (target == editSessionClip)
        {
            return;
        }

        Clip previous = editSessionClip;

        editSessionClip = target;

        if (previous != null)
        {
            releaseAutomatic(film, previous);
        }

        if (target != null)
        {
            acquireAutomatic(film, target);
        }
    }

    /** Forget the tracked session without touching any lock (used on disconnect/disable). */
    private static void clearEditSession()
    {
        editSessionClip = null;
    }

    /** Pushes the keyframe focus to the server only when it changed since the last frame. */
    private static void sendFocusIfChanged(Film film, int index)
    {
        if (film == null)
        {
            return;
        }

        String filmId = film.getId();

        if (index == lastSentFocusIndex && java.util.Objects.equals(filmId, lastSentFocusFilm))
        {
            return;
        }

        lastSentFocusIndex = index;
        lastSentFocusFilm = filmId;
        TogetherClientNetwork.sendKeyframeEditFocus(filmId, index);
    }

    private static void acquireAutomatic(Film film, Clip clip)
    {
        UUID self = AssetLockClient.self();

        if (self == null)
        {
            return;
        }

        ClipLockData existing = get(clip);

        /* A manual lock (or another player's lock) is respected: only create our own automatic
         * lock when the clip carries none, or carries our own automatic lock. */
        if (existing != null && !(existing.automatic && self.equals(existing.owner)))
        {
            return;
        }

        Map<UUID, String> editors = new LinkedHashMap<>();
        editors.put(self, selfName());

        int index = film.camera.getIndex(clip);

        for (Map.Entry<UUID, TogetherClientNetwork.CursorPosition> entry : TogetherClientNetwork.playerCursors.entrySet())
        {
            TogetherClientNetwork.CursorPosition cursor = entry.getValue();

            if ((cursor.editorType == 0 || cursor.editorType == 2) && cursor.selectedIndices.contains(index)
                && film.getId().equals(TogetherClientNetwork.filmSelections.get(entry.getKey())))
            {
                editors.put(entry.getKey(), "");
            }
        }

        ClipLockData lock = new ClipLockData(ClipLockData.newId());
        lock.owner = self;
        lock.ownerName = selfName();
        lock.mode = AssetLockMode.WHITELIST;
        lock.automatic = true;
        lock.whitelist.putAll(editors);

        Map<String, BaseType> foreign = ((ValueGroupForeignAccessor) (Object) clip).bbs_together$getForeign();

        if (foreign == null)
        {
            foreign = new HashMap<>();
            ((ValueGroupForeignAccessor) (Object) clip).bbs_together$setForeign(foreign);
        }

        foreign.put(ClipLockData.KEY, lock.toData());
        broadcast(film, clip);
    }

    private static void releaseAutomatic(Film film, Clip clip)
    {
        UUID self = AssetLockClient.self();
        ClipLockData existing = get(clip);

        /* Only remove our own automatic lock; a manual lock stays. */
        if (existing == null || !existing.automatic || self == null || !self.equals(existing.owner))
        {
            return;
        }

        Map<String, BaseType> foreign = ((ValueGroupForeignAccessor) (Object) clip).bbs_together$getForeign();

        if (foreign != null)
        {
            foreign.remove(ClipLockData.KEY);
        }

        broadcast(film, clip);
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
        lock.automatic = false;

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

        /* Send the clip's whole data (ValueGroup.toData() already carries the foreign lock key).
         * A lock-only map would leave a removed automatic lock in place on the other editors,
         * because ValueGroup.fromData() keeps absent keys; the full clip drops the lock too. */
        MapType payload = (MapType) clip.toData();

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
