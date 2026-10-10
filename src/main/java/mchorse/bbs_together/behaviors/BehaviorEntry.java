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
