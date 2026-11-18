package mchorse.bbs_together.locks;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The lock of a single camera clip, stored <b>inside the clip itself</b>.
 *
 * <p>BBS keeps unknown foreign keys of a {@code ValueGroup} when their name is
 * namespaced, so a key like {@link #KEY} is written back on every save and comes
 * back on every load — that gives the lock the same lifetime as the clip: it
 * survives copying/pasting the clip, undo (in the common case) and a branch
 * round trip on the server.</p>
 *
 * <p>The clip id is a random UUID because BBS renumbers clips by their index in
 * the list ({@code Clips.sync()}), which is not stable across a reorder. The
 * server matches a locked clip before and after a save by this id.</p>
 */
public class ClipLockData
{
    /** Foreign key inside the clip's data map. Must contain ':' to survive BBS' save. */
    public static final String KEY = "bbs_together:clip_lock";

    private static final String ID = "id";
    private static final String OWNER = "owner";
    private static final String OWNER_NAME = "owner_name";
    private static final String MODE = "mode";
    private static final String WHITELIST = "whitelist";
    private static final String BLACKLIST = "blacklist";

    public final String clipId;
    public UUID owner;
    public String ownerName = "";
    public AssetLockMode mode = AssetLockMode.ALL;

    public final Map<UUID, String> whitelist = new LinkedHashMap<>();
    public final Map<UUID, String> blacklist = new LinkedHashMap<>();

    public ClipLockData(String clipId)
    {
        this.clipId = clipId;
    }

    public static String newId()
    {
        return UUID.randomUUID().toString();
    }

    public Map<UUID, String> listFor(AssetLockMode mode)
    {
        return mode == AssetLockMode.WHITELIST ? this.whitelist : this.blacklist;
    }

    /**
     * Same rules as {@link AssetLock#allows(UUID)}: the owner is not a special
     * case, only the level-2 bypass of the callers applies.
     */
    public boolean allows(UUID playerId)
    {
        if (playerId == null)
        {
            return false;
        }

        switch (this.mode)
        {
            case WHITELIST:
                return this.whitelist.containsKey(playerId);
            case BLACKLIST:
                return !this.blacklist.containsKey(playerId);
            case ALL:
            default:
                return false;
        }
    }

    public MapType toData()
    {
        MapType map = new MapType();

        map.putString(ID, this.clipId);
        map.putString(OWNER, this.owner == null ? "" : this.owner.toString());
        map.putString(OWNER_NAME, this.ownerName == null ? "" : this.ownerName);
        map.putString(MODE, this.mode.name());
        map.put(WHITELIST, list(this.whitelist));
        map.put(BLACKLIST, list(this.blacklist));

        return map;
    }

    /** Read the lock out of one clip's serialized map; {@code null} when the clip is not locked. */
    public static ClipLockData read(MapType clipData)
    {
        return clipData == null ? null : fromData(clipData.get(KEY));
    }

    public static ClipLockData fromData(BaseType data)
    {
        if (data == null || !data.isMap())
        {
            return null;
        }

        MapType map = data.asMap();
        String id = map.getString(ID, "");

        if (id.isEmpty())
        {
            return null;
        }

        ClipLockData lock = new ClipLockData(id);

        lock.owner = parseId(map.getString(OWNER, ""));
        lock.ownerName = map.getString(OWNER_NAME, "");
        lock.mode = mode(map.getString(MODE, ""));
        readList(map.getMap(WHITELIST), lock.whitelist);
        readList(map.getMap(BLACKLIST), lock.blacklist);

        return lock;
    }

    private static AssetLockMode mode(String name)
    {
        for (AssetLockMode mode : AssetLockMode.values())
        {
            if (mode.name().equals(name))
            {
                return mode;
            }
        }

        return AssetLockMode.ALL;
    }

    private static MapType list(Map<UUID, String> members)
    {
        MapType map = new MapType();

        for (Map.Entry<UUID, String> entry : members.entrySet())
        {
            if (entry.getKey() != null)
            {
                map.putString(entry.getKey().toString(), entry.getValue() == null ? "" : entry.getValue());
            }
        }

        return map;
    }

    private static void readList(MapType map, Map<UUID, String> output)
    {
        if (map == null)
        {
            return;
        }

        for (Map.Entry<String, BaseType> entry : map)
        {
            UUID id = parseId(entry.getKey());

            if (id != null)
            {
                output.put(id, entry.getValue().isString() ? entry.getValue().asString() : "");
            }
        }
    }

    private static UUID parseId(String value)
    {
        if (value == null || value.isEmpty())
        {
            return null;
        }

        try
        {
            return UUID.fromString(value);
        }
        catch (IllegalArgumentException e)
        {
            return null;
        }
    }
}
