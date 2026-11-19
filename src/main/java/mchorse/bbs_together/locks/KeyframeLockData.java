package mchorse.bbs_together.locks;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.keyframes.KeyframeLoop;
import mchorse.bbs_together.mixin.ValueGroupForeignAccessor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A multi-keyframe lock: one record owns one range per channel, all created by the
 * same selection. The record lives in the foreign map of the nearest {@link Replay}
 * or camera {@link Clip}, next to the clip lock. Storing it there keeps it out of
 * keyframe data (which has no stable identity and drops unknown keys) and lets the
 * server resolve the owner from a normal data path.
 */
public final class KeyframeLockData
{
    public static final String KEY = "bbs_together:keyframe_lock";

    public record Range(String channel, float start, float end)
    {
        public boolean contains(float tick)
        {
            return tick >= this.start - 0.0001F && tick <= this.end + 0.0001F;
        }

        public boolean intersects(float a, float b)
        {
            return a <= this.end + 0.0001F && b >= this.start - 0.0001F;
        }

        public BaseType toData()
        {
            MapType data = new MapType();
            data.putString("channel", this.channel);
            data.putFloat("start", this.start);
            data.putFloat("end", this.end);
            return data;
        }

        public static Range fromData(BaseType data)
        {
            if (data == null || !data.isMap())
            {
                return null;
            }

            MapType map = data.asMap();
            String channel = map.getString("channel");

            if (channel == null || channel.isEmpty())
            {
                return null;
            }

            float start = map.getFloat("start");
            float end = map.getFloat("end");

            return end > start ? new Range(channel, start, end) : null;
        }
    }

    private final String id;
    private final List<Range> ranges;

    public KeyframeLockData(String id, List<Range> ranges)
    {
        this.id = id == null || id.isEmpty() ? UUID.randomUUID().toString() : id;
        this.ranges = new ArrayList<>(ranges);
    }

    public String id()
    {
        return this.id;
    }

    public List<Range> ranges()
    {
        return Collections.unmodifiableList(this.ranges);
    }

    public boolean locksChannel(String channel)
    {
        for (Range range : this.ranges)
        {
            if (range.channel().equals(channel))
            {
                return true;
            }
        }

        return false;
    }

    public BaseType toData()
    {
        MapType data = new MapType();
        data.putString("id", this.id);
        ListType list = new ListType();

        for (Range range : this.ranges)
        {
            list.add(range.toData());
        }

        data.put("ranges", list);
        return data;
    }

    public static KeyframeLockData fromData(BaseType data)
    {
        if (data == null || !data.isMap())
        {
            return null;
        }

        MapType map = data.asMap();
        ListType list = map.getList("ranges");
        ArrayList<Range> ranges = new ArrayList<>();

        if (list != null)
        {
            for (BaseType entry : list)
            {
                Range range = Range.fromData(entry);

                if (range != null)
                {
                    ranges.add(range);
                }
            }
        }

        return ranges.isEmpty() ? null : new KeyframeLockData(map.getString("id"), ranges);
    }

    /* Storage */

    public static List<KeyframeLockData> get(ValueGroup owner)
    {
        if (owner == null)
        {
            return Collections.emptyList();
        }

        Map<String, BaseType> foreign = foreignOrNull(owner);

        if (foreign == null)
        {
            return Collections.emptyList();
        }

        BaseType data = foreign.get(KEY);

        if (data == null || !data.isList())
        {
            return Collections.emptyList();
        }

        ArrayList<KeyframeLockData> locks = new ArrayList<>();

        for (BaseType entry : data.asList())
        {
            KeyframeLockData lock = fromData(entry);

            if (lock != null)
            {
                locks.add(lock);
            }
        }

        return locks;
    }

    public static void set(ValueGroup owner, List<KeyframeLockData> locks)
    {
        if (owner == null)
        {
            return;
        }

        if (locks == null || locks.isEmpty())
        {
            Map<String, BaseType> foreign = foreignOrNull(owner);

            if (foreign != null)
            {
                foreign.remove(KEY);
            }

            return;
        }

        ListType list = new ListType();

        for (KeyframeLockData lock : locks)
        {
            list.add(lock.toData());
        }

        foreign(owner).put(KEY, list);
    }

    public static boolean hasAny(ValueGroup owner)
    {
        return !get(owner).isEmpty();
    }

    /** The nearest replay or camera clip that owns a channel, or null for unsupported editors. */
    public static ValueGroup ownerOf(BaseValue value)
    {
        for (BaseValue parent = value; parent != null; parent = parent.getParent())
        {
            if (parent instanceof Replay || parent instanceof Clip)
            {
                return (ValueGroup) parent;
            }
        }

        return null;
    }

    /** Lock ranges that apply to one channel of an owner. */
    public static List<Range> rangesFor(ValueGroup owner, String channel)
    {
        ArrayList<Range> ranges = new ArrayList<>();

        for (KeyframeLockData lock : get(owner))
        {
            for (Range range : lock.ranges())
            {
                if (range.channel().equals(channel))
                {
                    ranges.add(range);
                }
            }
        }

        return ranges;
    }

    /** Whether any lock record covers the given channel at all. */
    public static boolean locksChannel(ValueGroup owner, String channel)
    {
        return !rangesFor(owner, channel).isEmpty();
    }

    /** Whether a channel's loop block is covered by a lock on that channel. */
    public static boolean loopLocked(ValueGroup owner, String channel, KeyframeLoop loop, float loopEnd)
    {
        for (Range range : rangesFor(owner, channel))
        {
            if (range.intersects(loop.start(), loopEnd))
            {
                return true;
            }
        }

        return false;
    }

    static Map<String, BaseType> foreignOrNull(ValueGroup owner)
    {
        return owner == null ? null : ((ValueGroupForeignAccessor) owner).bbs_together$getForeign();
    }

    static Map<String, BaseType> foreign(ValueGroup owner)
    {
        Map<String, BaseType> foreign = foreignOrNull(owner);

        if (foreign == null)
        {
            foreign = new LinkedHashMap<>();
            ((ValueGroupForeignAccessor) owner).bbs_together$setForeign(foreign);
        }

        return foreign;
    }
}
