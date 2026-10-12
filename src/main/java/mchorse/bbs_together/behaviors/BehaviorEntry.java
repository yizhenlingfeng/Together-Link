package mchorse.bbs_together.behaviors;

import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One logged behavior on the server: who did what, when, to what, with which details.
 *
 * <p>The summary text is never stored — only structure ({@link #target}, {@link #items},
 * {@link #extra}) — so the client can phrase it in whatever language it runs in.</p>
 */
public class BehaviorEntry
{
    /** Monotonic ID, persisted across restarts, never reused. */
    public long id;
    public long time;
    public UUID playerId;
    /** Name snapshot taken when the entry was logged (OP view only). */
    public String playerName;
    /** {@link BehaviorType#id}. */
    public int type;
    /** One of the {@code BehaviorType.SUB_*} constants. */
    public int subType;
    /** Main subject: a model ID, a film ID, ... */
    public String target = "";
    /** Detail lines: model IDs, asset paths, ... */
    public List<String> items = new ArrayList<>();
    /** Extra key/values: model block position, clip count, ... */
    public Map<String, String> extra = new LinkedHashMap<>();
    /** Every repeat of this behavior with its own timestamp and items — the
     * entry itself carries the latest time and the pooled items. */
    public final List<Occurrence> occurrences = new ArrayList<>();

    public BehaviorEntry()
    {}

    public BehaviorEntry(long id, long time, UUID playerId, String playerName, int type, int subType, String target)
    {
        this.id = id;
        this.time = time;
        this.playerId = playerId;
        this.playerName = playerName;
        this.type = type;
        this.subType = subType;
        this.target = target == null ? "" : target;
    }

    public BehaviorType getType()
    {
        return BehaviorType.byId(this.type);
    }

    /** One timestamped repeat of a merged behavior. */
    public static class Occurrence
    {
        public long time;
        public List<String> items = new ArrayList<>();

        public Occurrence()
        {}

        public Occurrence(long time, List<String> items)
        {
            this.time = time;

            if (items != null)
            {
                this.items.addAll(items);
            }
        }
    }

    /* Serialization */

    public MapType toData(boolean withIdentity)
    {
        MapType map = new MapType();

        map.putLong("id", this.id);
        map.putLong("time", this.time);
        map.putInt("type", this.type);
        map.putInt("subType", this.subType);
        map.putString("target", this.target);

        ListType items = new ListType();

        for (String item : this.items)
        {
            items.addString(item);
        }

        map.put("items", items);

        if (!this.occurrences.isEmpty())
        {
            ListType times = new ListType();

            for (Occurrence occurrence : this.occurrences)
            {
                MapType data = new MapType();
                ListType occurrenceItems = new ListType();

                data.putLong("t", occurrence.time);

                for (String item : occurrence.items)
                {
                    occurrenceItems.addString(item);
                }

                data.put("items", occurrenceItems);
                times.add(data);
            }

            map.put("times", times);
        }

        if (!this.extra.isEmpty())
        {
            MapType extra = new MapType();

            for (Map.Entry<String, String> entry : this.extra.entrySet())
            {
                extra.putString(entry.getKey(), entry.getValue());
            }

            map.put("extra", extra);
        }

        if (withIdentity)
        {
            map.putString("pid", this.playerId == null ? "" : this.playerId.toString());
            map.putString("name", this.playerName == null ? "" : this.playerName);
        }

        return map;
    }

    public static BehaviorEntry fromData(MapType map)
    {
        BehaviorEntry entry = new BehaviorEntry();

        entry.id = map.getLong("id");
        entry.time = map.getLong("time");
        entry.type = map.getInt("type");
        entry.subType = map.getInt("subType");
        entry.target = map.getString("target");

        ListType items = map.getList("items");

        if (items != null)
        {
            for (int i = 0; i < items.size(); i++)
            {
                entry.items.add(items.getString(i));
            }
        }

        ListType times = map.getList("times");

        if (times != null)
        {
            for (int i = 0; i < times.size(); i++)
            {
                MapType data = times.getMap(i);
                ListType occurrenceItems = data.getList("items");
                Occurrence occurrence = new Occurrence(data.getLong("t"), null);

                if (occurrenceItems != null)
                {
                    for (int j = 0; j < occurrenceItems.size(); j++)
                    {
                        occurrence.items.add(occurrenceItems.getString(j));
                    }
                }

                entry.occurrences.add(occurrence);
            }
        }

        MapType extra = map.getMap("extra");

        if (extra != null)
        {
            for (String key : extra.keys())
            {
                entry.extra.put(key, extra.getString(key));
            }
        }

        String pid = map.getString("pid");

        if (pid != null && !pid.isEmpty())
        {
            try
            {
                entry.playerId = UUID.fromString(pid);
            }
            catch (IllegalArgumentException ignored)
            {}
        }

        entry.playerName = map.getString("name");

        return entry;
    }
}
