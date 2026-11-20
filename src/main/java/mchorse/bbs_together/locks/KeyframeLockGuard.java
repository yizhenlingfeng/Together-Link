package mchorse.bbs_together.locks;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.film.replays.tracks.TrackId;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.KeyframeLoop;
import mchorse.bbs_together.bbsop.BbsOpManager;
import mchorse.bbs_together.commits.FilmBranchStore;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server side of the multi-keyframe lock. A lock record lives in the foreign map of
 * the replay (or camera clip) that owns the channels. The guard resolves that owner
 * from a sync path and compares only the locked slices of the incoming channel with
 * the branch working copy, so unrelated keyframe edits keep flowing.
 *
 * <p>Creating, changing or removing a lock is level-2 only, exactly like the clip
 * and film locks. Full film saves keep the server's whole channel when it has a lock,
 * because a serialized replacement does not preserve object identity and could move a
 * locked key out of its range while leaving a modified copy behind.</p>
 */
public final class KeyframeLockGuard
{
    private static final String REPLAYS = "replays";
    private static final String CAMERA = "camera";
    private static final String KEYFRAMES = "keyframes";
    private static final String PROPERTIES = "properties";

    private static final Map<UUID, Long> NOTICE_AT = new ConcurrentHashMap<>();

    public static final String REASON_LOCKED = "bbs_together.keyframe_lock.notify.locked";

    private KeyframeLockGuard()
    {}

    public static boolean canSync(ServerPlayerEntity player, String filmId, String branch, List<String> path, BaseType payload)
    {
        if (player == null || path == null || path.size() < 2)
        {
            return true;
        }

        Film film = FilmBranchStore.working(filmId, branch);

        if (film == null)
        {
            return true;
        }

        String root = path.get(0);

        if (REPLAYS.equals(root))
        {
            Replay replay = replay(film, path.get(1));

            if (replay == null)
            {
                return true;
            }

            if (path.size() == 2)
            {
                return ownerPayload(player, replay, payload);
            }

            if (path.size() == 3)
            {
                String group = path.get(2);

                /* Only keyframe-bearing groups can drop locked keys; forms etc. stay editable. */
                if (!KEYFRAMES.equals(group) && !PROPERTIES.equals(group))
                {
                    return true;
                }

                /* Replacing a whole replay group would drop every keyframe and loop. */
                return !KeyframeLockData.hasAny(replay) || deny(player);
            }

            String group = path.get(2);
            String channelId = path.get(3);
            KeyframeChannel<?> channel = channel(replay, group, channelId);

            if (path.size() == 4)
            {
                return channelPayload(player, replay, channel, channelId, payload);
            }

            int keyIndex = parseInt(path.get(4));

            return keyframePayload(player, replay, channel, channelId, keyIndex, payload);
        }

        if (CAMERA.equals(root))
        {
            Clip clip = clip(film, path.get(1));

            if (clip == null)
            {
                return true;
            }

            if (path.size() == 2)
            {
                return ownerPayload(player, clip, payload);
            }

            String channelId = path.get(2);
            KeyframeChannel<?> channel = clipChannel(clip, channelId);

            if (path.size() == 3)
            {
                return channelPayload(player, clip, channel, channelId, payload);
            }

            int keyIndex = parseInt(path.get(3));

            return keyframePayload(player, clip, channel, channelId, keyIndex, payload);
        }

        return true;
    }

    /**
     * A replay/clip payload carries the whole foreign map of its owner, so it both creates and
     * removes locks. Adding a lock to an owner that has none, or rewriting the locks an existing
     * owner already holds, cannot take a lock away from anybody, and it is what an admin's lock
     * broadcast looks like; refusing it left the lock only on the client that created it, which
     * showed up as "locking a clip sometimes does not stick, lock it a second time". Payloads that
     * drop or clear the lock record stay refused unless the player manages locks. Once an owner has
     * a lock, every owner snapshot must leave its child values unchanged, managers included;
     * lock-only broadcasts carry no children.
     */
    private static boolean ownerPayload(ServerPlayerEntity player, ValueGroup owner, BaseType payload)
    {
        List<KeyframeLockData> stored = KeyframeLockData.get(owner);

        /* A keyframe lock only guards its own owner. An owner that holds none takes an ordinary
         * payload as it comes: whole-clip syncs carry no keyframe lock at all, and refusing them
         * here refused every clip lock and clip edit of a level-1 editor. */
        if (!stored.isEmpty() && !keepsLocks(stored, payload) && !BbsOpManager.canManage(player))
        {
            return deny(player);
        }

        if (!KeyframeLockData.hasAny(owner) || payload == null || !payload.isMap())
        {
            return true;
        }

        for (String key : payload.asMap().keys())
        {
            BaseValue child = owner.get(key);

            if (child != null && !BaseType.equals(child.toData(), payload.asMap().get(key)))
            {
                return deny(player);
            }
        }

        return true;
    }

    /** Whether {@code payload} still carries a lock for every range the owner holds right now. */
    private static boolean keepsLocks(List<KeyframeLockData> stored, BaseType payload)
    {
        List<KeyframeLockData> locks = KeyframeLockData.fromPayload(payload);

        if (locks == null)
        {
            return false;
        }

        for (KeyframeLockData lock : stored)
        {
            for (KeyframeLockData.Range range : lock.ranges())
            {
                if (!requested(locks, range))
                {
                    return false;
                }
            }
        }

        return true;
    }

    private static boolean requested(List<KeyframeLockData> locks, KeyframeLockData.Range range)
    {
        for (KeyframeLockData lock : locks)
        {
            for (KeyframeLockData.Range candidate : lock.ranges())
            {
                if (candidate.channel().equals(range.channel()) && candidate.start() <= range.start() && candidate.end() >= range.end())
                {
                    return true;
                }
            }
        }

        return false;
    }

    private static boolean channelPayload(ServerPlayerEntity player, ValueGroup owner, KeyframeChannel<?> stored, String channelId, BaseType payload)
    {
        List<KeyframeLockData.Range> ranges = KeyframeLockData.rangesFor(owner, channelId);

        if (ranges.isEmpty())
        {
            return true;
        }

        if (stored == null)
        {
            return deny(player);
        }

        if (payload == null || !payload.isMap())
        {
            return deny(player);
        }

        MapType map = payload.asMap();

        if (!map.has(KEYFRAMES))
        {
            return deny(player);
        }

        if (!sameKeys(stored, map.getList(KEYFRAMES), ranges))
        {
            return deny(player);
        }

        return sameLoops(stored, map.getList("loops"), ranges) || deny(player);
    }

    private static boolean keyframePayload(ServerPlayerEntity player, ValueGroup owner, KeyframeChannel<?> stored, String channelId, int keyIndex, BaseType payload)
    {
        List<KeyframeLockData.Range> ranges = KeyframeLockData.rangesFor(owner, channelId);

        if (ranges.isEmpty())
        {
            return true;
        }

        if (stored == null)
        {
            return deny(player);
        }

        if (keyIndex < 0 || keyIndex >= stored.getKeyframes().size())
        {
            return deny(player);
        }

        Keyframe<?> key = stored.getKeyframes().get(keyIndex);
        float tick = key.getTick();
        float newTick = tick;

        if (payload != null && payload.isMap())
        {
            MapType map = payload.asMap();

            if (map.has("tick"))
            {
                newTick = map.getFloat("tick");
            }
        }

        return !inRange(ranges, tick) && !inRange(ranges, newTick) || deny(player);
    }

    private static boolean sameKeys(KeyframeChannel<?> stored, ListType incoming, List<KeyframeLockData.Range> ranges)
    {
        if (incoming == null)
        {
            return storedSizeInRanges(stored, ranges) == 0;
        }

        List<MapType> storedKeys = new ArrayList<>();
        List<MapType> incomingKeys = new ArrayList<>();

        for (Keyframe<?> key : stored.getKeyframes())
        {
            if (inRange(ranges, key.getTick()))
            {
                storedKeys.add((MapType) key.toData());
            }
        }

        for (BaseType entry : incoming)
        {
            if (!entry.isMap())
            {
                return false;
            }

            MapType map = entry.asMap();

            if (inRange(ranges, map.getFloat("tick")))
            {
                incomingKeys.add(map);
            }
        }

        if (storedKeys.size() != incomingKeys.size())
        {
            return false;
        }

        storedKeys.sort((a, b) -> Float.compare(a.getFloat("tick"), b.getFloat("tick")));
        incomingKeys.sort((a, b) -> Float.compare(a.getFloat("tick"), b.getFloat("tick")));

        for (int i = 0; i < storedKeys.size(); i++)
        {
            if (!BaseType.equals(storedKeys.get(i), incomingKeys.get(i)))
            {
                return false;
            }
        }

        return true;
    }

    private static int storedSizeInRanges(KeyframeChannel<?> stored, List<KeyframeLockData.Range> ranges)
    {
        int size = 0;

        for (Keyframe<?> key : stored.getKeyframes())
        {
            if (inRange(ranges, key.getTick()))
            {
                size++;
            }
        }

        return size;
    }

    private static boolean sameLoops(KeyframeChannel<?> stored, ListType incoming, List<KeyframeLockData.Range> ranges)
    {
        Map<String, MapType> storedLoops = new HashMap<>();

        for (KeyframeLoop loop : stored.getLoops())
        {
            if (intersects(ranges, loop.start(), stored.getLoopEnd(loop)))
            {
                storedLoops.put(loop.id(), (MapType) loop.toData());
            }
        }

        Map<String, MapType> incomingLoops = new HashMap<>();

        if (incoming != null)
        {
            for (BaseType entry : incoming)
            {
                if (!entry.isMap())
                {
                    return false;
                }

                MapType map = entry.asMap();
                float start = map.getFloat("start");
                float end = map.getFloat("end");

                if (!intersects(ranges, start, end))
                {
                    continue;
                }

                String id = map.getString("id");

                if (id != null)
                {
                    incomingLoops.put(id, map);
                }
            }
        }

        if (storedLoops.size() != incomingLoops.size())
        {
            return false;
        }

        for (Map.Entry<String, MapType> entry : storedLoops.entrySet())
        {
            MapType in = incomingLoops.get(entry.getKey());

            if (in == null || !BaseType.equals(entry.getValue(), in))
            {
                return false;
            }
        }

        return true;
    }

    /**
     * Strict variant of {@link #canSync} used to vet Ctrl+Z rollbacks. A rollback restores the
     * state from before an edit, which a locked range may have legitimately moved on from, so
     * "the locked slice already changed" is not proof — the rollback itself must not touch any
     * locked keyframe or loop at all.
     */
    public static String undoReason(ServerPlayerEntity player, String filmId, String branch, Film film, List<String> path, BaseType payload)
    {
        if (player == null || film == null || path == null || path.size() < 2)
        {
            return null;
        }

        String root = path.get(0);

        if (REPLAYS.equals(root))
        {
            Replay replay = replay(film, path.get(1));

            if (replay == null)
            {
                return null;
            }

            if (path.size() == 2)
            {
                return undoOwner(replay, payload);
            }

            if (path.size() == 3)
            {
                String group = path.get(2);

                if (!KEYFRAMES.equals(group) && !PROPERTIES.equals(group))
                {
                    return null;
                }

                return undoOwner(replay, payload);
            }

            String channelId = path.get(3);
            KeyframeChannel<?> stored = channel(replay, path.get(2), channelId);

            if (path.size() == 4)
            {
                return undoChannel(replay, stored, channelId, payload);
            }

            return undoKeyframe(replay, stored, channelId, parseInt(path.get(4)), payload);
        }

        if (CAMERA.equals(root))
        {
            Clip clip = clip(film, path.get(1));

            if (clip == null)
            {
                return null;
            }

            if (path.size() == 2)
            {
                return undoOwner(clip, payload);
            }

            String channelId = path.get(2);
            KeyframeChannel<?> stored = clipChannel(clip, channelId);

            if (path.size() == 3)
            {
                return undoChannel(clip, stored, channelId, payload);
            }

            return undoKeyframe(clip, stored, channelId, parseInt(path.get(3)), payload);
        }

        return null;
    }

    private static String undoOwner(ValueGroup owner, BaseType payload)
    {
        if (!KeyframeLockData.hasAny(owner))
        {
            return null;
        }

        if (payload == null || !payload.isMap())
        {
            return REASON_LOCKED;
        }

        for (KeyframeLockData lock : KeyframeLockData.get(owner))
        {
            for (KeyframeLockData.Range range : lock.ranges())
            {
                KeyframeChannel<?> stored = ownerChannel(owner, range.channel());
                BaseType incoming = ownerChannelData(payload.asMap(), range.channel());

                if (stored == null || incoming == null || !incoming.isMap())
                {
                    return REASON_LOCKED;
                }

                List<KeyframeLockData.Range> ranges = List.of(range);

                if (!sameKeys(stored, incoming.asMap().getList(KEYFRAMES), ranges)
                    || !sameLoops(stored, incoming.asMap().getList("loops"), ranges))
                {
                    return REASON_LOCKED;
                }
            }
        }

        return null;
    }

    private static KeyframeChannel<?> ownerChannel(ValueGroup owner, String channelId)
    {
        if (owner instanceof Replay replay)
        {
            KeyframeChannel<?> curated = channel(replay, KEYFRAMES, channelId);

            return curated != null ? curated : channel(replay, PROPERTIES, channelId);
        }

        BaseValue direct = owner.get(channelId);

        if (direct instanceof KeyframeChannel<?> channel)
        {
            return channel;
        }

        BaseValue channels = owner.get("channels");
        BaseValue nested = channels instanceof ValueGroup group ? group.get(channelId) : null;

        return nested instanceof KeyframeChannel<?> channel ? channel : null;
    }

    private static BaseType ownerChannelData(MapType payload, String channelId)
    {
        BaseType direct = payload.get(channelId);

        if (direct != null)
        {
            return direct;
        }

        for (String group : new String[] {KEYFRAMES, PROPERTIES, "channels"})
        {
            BaseType value = payload.get(group);

            if (value != null && value.isMap() && value.asMap().has(channelId))
            {
                return value.asMap().get(channelId);
            }
        }

        return null;
    }

    private static String undoChannel(ValueGroup owner, KeyframeChannel<?> stored, String channelId, BaseType payload)
    {
        List<KeyframeLockData.Range> ranges = KeyframeLockData.rangesFor(owner, channelId);

        if (ranges.isEmpty())
        {
            return null;
        }

        if (stored == null)
        {
            return REASON_LOCKED;
        }

        if (payload == null || !payload.isMap() || !sameKeys(stored, payload.asMap().getList(KEYFRAMES), ranges))
        {
            return REASON_LOCKED;
        }

        return sameLoops(stored, payload.asMap().getList("loops"), ranges) ? null : REASON_LOCKED;
    }

    private static String undoKeyframe(ValueGroup owner, KeyframeChannel<?> stored, String channelId, int keyIndex, BaseType payload)
    {
        List<KeyframeLockData.Range> ranges = KeyframeLockData.rangesFor(owner, channelId);

        if (ranges.isEmpty())
        {
            return null;
        }

        if (stored == null)
        {
            return REASON_LOCKED;
        }

        /* An index that no longer exists means the rollback would recreate a key the lock
         * may cover; there is nothing to compare against, so treat it as locked. */
        if (keyIndex < 0 || keyIndex >= stored.getKeyframes().size())
        {
            return REASON_LOCKED;
        }

        float currentTick = stored.getKeyframes().get(keyIndex).getTick();
        float restoredTick = payload != null && payload.isMap() && payload.asMap().has("tick") ? payload.asMap().getFloat("tick") : currentTick;

        return inRange(ranges, currentTick) || inRange(ranges, restoredTick) ? REASON_LOCKED : null;
    }

    /* Resolution */

    private static Replay replay(Film film, String id)
    {
        if (film == null || id == null)
        {
            return null;
        }

        for (Replay replay : film.replays.getList())
        {
            if (id.equals(replay.getId()))
            {
                return replay;
            }
        }

        /* Legacy index paths resolve through the same list; never let them bypass the guard. */
        int index = parseInt(id);

        if (index >= 0 && index < film.replays.getList().size())
        {
            return film.replays.getList().get(index);
        }

        return null;
    }

    private static Clip clip(Film film, String index)
    {
        int i = parseInt(index);

        if (film == null || i < 0 || i >= film.camera.get().size())
        {
            return null;
        }

        return film.camera.get().get(i);
    }

    private static KeyframeChannel<?> channel(Replay replay, String group, String id)
    {
        if (replay == null || group == null || id == null)
        {
            return null;
        }

        BaseValue value;

        if (KEYFRAMES.equals(group))
        {
            value = replay.keyframes.get(id);
        }
        else if (PROPERTIES.equals(group))
        {
            TrackId track = TrackId.parse(id);

            value = track == null ? null : replay.properties.get(track);
        }
        else
        {
            return null;
        }

        return value instanceof KeyframeChannel ? (KeyframeChannel<?>) value : null;
    }

    private static KeyframeChannel<?> clipChannel(Clip clip, String id)
    {
        if (clip == null || id == null)
        {
            return null;
        }

        BaseValue value = clip.get(id);

        return value instanceof KeyframeChannel ? (KeyframeChannel<?>) value : null;
    }

    /* Helpers */

    /** Drops lock records from an owner that a non-manager tried to smuggle in a full save. */
    public static void stripLocks(ValueGroup owner)
    {
        Map<String, BaseType> foreign = KeyframeLockData.foreignOrNull(owner);

        if (foreign != null)
        {
            foreign.remove(KeyframeLockData.KEY);
        }
    }

    private static boolean inRange(List<KeyframeLockData.Range> ranges, float tick)
    {
        for (KeyframeLockData.Range range : ranges)
        {
            if (range.contains(tick))
            {
                return true;
            }
        }

        return false;
    }

    private static boolean intersects(List<KeyframeLockData.Range> ranges, float start, float end)
    {
        for (KeyframeLockData.Range range : ranges)
        {
            if (range.intersects(start, end))
            {
                return true;
            }
        }

        return false;
    }

    private static int parseInt(String value)
    {
        try
        {
            return Integer.parseInt(value);
        }
        catch (NumberFormatException e)
        {
            return -1;
        }
    }

    private static boolean deny(ServerPlayerEntity player)
    {
        long now = System.currentTimeMillis();
        Long last = NOTICE_AT.get(player.getUuid());

        if (last == null || now - last > 5000L)
        {
            NOTICE_AT.put(player.getUuid(), now);
            player.sendMessage(Text.translatable(REASON_LOCKED).formatted(Formatting.YELLOW), false);
        }

        return false;
    }
}
