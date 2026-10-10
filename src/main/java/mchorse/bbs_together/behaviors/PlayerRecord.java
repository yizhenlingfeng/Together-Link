package mchorse.bbs_together.behaviors;

import mchorse.bbs_mod.data.types.MapType;

import java.util.UUID;

/**
 * A player the server has seen: identity snapshot for the OP-side player list,
 * plus when they last produced a behavior, so the list can show only players
 * who edited / downloaded / uploaded at least once.
 */
public class PlayerRecord
{
    public UUID id;
    public String name = "";
    public long firstSeen;
    public long lastSeen;
    public long lastAction;
    public boolean everEdited;
    /** Whether this player is an OP right now; filled in when sent to clients. */
    public boolean op;
    /** Offline-mode account (name-derived UUID v3, premium accounts are v4); filled on send. */
    public boolean offline;

    public PlayerRecord()
    {}

    public PlayerRecord(UUID id, String name, long time)
    {
        this.id = id;
        this.name = name == null ? "" : name;
        this.firstSeen = time;
        this.lastSeen = time;
    }

    public MapType toData()
    {
        MapType map = new MapType();

        map.putString("id", this.id == null ? "" : this.id.toString());
        map.putString("name", this.name);
        map.putLong("first", this.firstSeen);
        map.putLong("last", this.lastSeen);
        map.putLong("lastAction", this.lastAction);
        map.putBool("edited", this.everEdited);

        return map;
    }

    public static PlayerRecord fromData(MapType map)
    {
        PlayerRecord record = new PlayerRecord();

        String id = map.getString("id");

        if (id != null && !id.isEmpty())
        {
            try
            {
                record.id = UUID.fromString(id);
            }
            catch (IllegalArgumentException ignored)
            {}
        }

        record.name = map.getString("name");
        record.firstSeen = map.getLong("first");
        record.lastSeen = map.getLong("last");
        record.lastAction = map.getLong("lastAction");
        record.everEdited = map.getBool("edited");

        return record;
    }
}
