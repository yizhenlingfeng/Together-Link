package mchorse.bbs_together.behaviors;

import mchorse.bbs_mod.data.DataStorageUtils;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_together.TogetherSettings;
import mchorse.bbs_together.bbsop.BbsOpManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.UserCache;
import net.minecraft.util.WorldSavePath;

import com.mojang.authlib.GameProfile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The server-side behavior log: who edited / downloaded / uploaded what, since
 * they joined. Entries live in memory, get persisted (periodically, and on
 * server stop) into <code>&lt;world&gt;/bbs/behaviors/behaviors.bin</code>, and the
 * oldest ones are evicted once the count passes the {@code behavior_log_limit}
 * setting. IDs only ever grow, so clients can ask for "everything after id N".
 *
 * <p>All state is guarded by a class-wide lock: callers arrive both from the
 * server thread and from network threads (crusher receive callbacks).</p>
 */
public class BehaviorLogManager
{
    /** How many detail items a single entry keeps at most. */
    private static final int MAX_ITEMS = 64;
    /** How many per-repeat timestamps a merged entry keeps at most (oldest dropped). */
    private static final int MAX_OCCURRENCES = 128;
    /** Extra key carrying the transferred size in bytes. */
    public static final String BYTES_KEY = "bytes";
    private static final int SAVE_PERIOD_SECONDS = 30;

    private static final List<BehaviorEntry> entries = new ArrayList<>();
    private static final Map<UUID, PlayerRecord> players = new HashMap<>();
    private static long nextId = 1;
    /** Bumped on every change (new/merged/removed entries, evictions); clients that
     * report an older version get a full snapshot instead of an increment. */
    private static long version = 0;
    private static boolean dirty = false;
    private static File folder;
    private static ScheduledExecutorService saver;

    /* Lifecycle */

    public static synchronized void onServerStarted(MinecraftServer server)
    {
        folder = new File(server.getSavePath(WorldSavePath.ROOT).toFile(), "bbs" + File.separator + "behaviors");

        load();

        if (saver == null)
        {
            saver = Executors.newSingleThreadScheduledExecutor((runnable) ->
            {
                Thread thread = new Thread(runnable, "BBSTogether-BehaviorSave");

                thread.setDaemon(true);

                return thread;
            });
            saver.scheduleWithFixedDelay(BehaviorLogManager::saveIfDirty, SAVE_PERIOD_SECONDS, SAVE_PERIOD_SECONDS, TimeUnit.SECONDS);
        }
    }

    public static synchronized void onServerStopping()
    {
        save();

        if (saver != null)
        {
            saver.shutdownNow();
            saver = null;
        }
    }

    public static synchronized void onServerStopped()
    {
        entries.clear();
        players.clear();
        nextId = 1;
        version = 0;
        dirty = false;
        folder = null;
    }

    /* Recording */

    public static void onPlayerJoin(ServerPlayerEntity player)
    {
        UUID id = player.getUuid();
        String name = player.getGameProfile().getName();
        long time = System.currentTimeMillis();

        synchronized (BehaviorLogManager.class)
        {
            PlayerRecord record = players.get(id);

            if (record == null)
            {
                players.put(id, new PlayerRecord(id, name, time));
            }
            else
            {
                record.name = name;
                record.lastSeen = time;
            }

            dirty = true;
        }
    }

    /**
     * Log one behavior. A record that repeats the last entry (same player,
     * type and target) merges into it instead of creating a new one: items
     * pool together and every repeat lands in the entry's timeline with its
     * own timestamp — so an hour-long editing session on one film stays a
     * single row that expands into per-save times in the details pane.
     */
    public static void record(ServerPlayerEntity player, int type, int subType, String target, List<String> items, Map<String, String> extra)
    {
        if (player == null)
        {
            return;
        }

        UUID id = player.getUuid();
        String name = player.getGameProfile().getName();
        long time = System.currentTimeMillis();

        synchronized (BehaviorLogManager.class)
        {
            touchPlayer(id, name, time);

            BehaviorEntry last = entries.isEmpty() ? null : entries.get(entries.size() - 1);

            if (last != null
                && id.equals(last.playerId)
                && last.type == type
                && last.subType == subType
                && last.target.equals(target == null ? "" : target))
            {
                appendItems(last, items);
                appendOccurrence(last, time, items);
                last.time = time;
                mergeBytes(last, extra);
            }
            else
            {
                addEntry(id, name, time, type, subType, target, items, extra);
            }

            version++;
            dirty = true;
        }
    }

    /**
     * Log a behavior that must never merge into the previous entry: request
     * entries carry an approval {@code status} in {@code extra}, which a
     * repeat-merge would silently keep stale. Always creates a new entry.
     */
    public static void recordUnique(ServerPlayerEntity player, int type, int subType, String target, List<String> items, Map<String, String> extra)
    {
        if (player == null)
        {
            return;
        }

        UUID id = player.getUuid();
        String name = player.getGameProfile().getName();
        long time = System.currentTimeMillis();

        synchronized (BehaviorLogManager.class)
        {
            touchPlayer(id, name, time);
            addEntry(id, name, time, type, subType, target, items, extra);

            version++;
            dirty = true;
        }
    }

    /** Refresh the player's activity snapshot (creates the record on first sight). */
    private static void touchPlayer(UUID id, String name, long time)
    {
        PlayerRecord record = players.get(id);

        if (record == null)
        {
            record = new PlayerRecord(id, name, time);

            players.put(id, record);
        }

        record.name = name;
        record.lastSeen = time;
        record.lastAction = time;
        record.everEdited = true;
    }

    /** Append a fresh entry and enforce the ring limit (caller holds the lock, bumps version). */
    private static void addEntry(UUID id, String name, long time, int type, int subType, String target, List<String> items, Map<String, String> extra)
    {
        BehaviorEntry entry = new BehaviorEntry(nextId++, time, id, name, type, subType, target);

        appendItems(entry, items);
        entry.occurrences.add(new BehaviorEntry.Occurrence(time, items));

        if (extra != null)
        {
            entry.extra.putAll(extra);
        }

        entries.add(entry);
        enforceLimit();
    }

    /** Whether a pending model-delete request for this model is already awaiting a decision. */
    public static boolean hasPendingRequest(String modelId)
    {
        synchronized (BehaviorLogManager.class)
        {
            for (int i = entries.size() - 1; i >= 0; i--)
            {
                BehaviorEntry entry = entries.get(i);

                if (entry.type == BehaviorType.OTHER.id
                    && entry.subType == BehaviorType.SUB_REQUEST_MODEL_DELETE
                    && entry.target.equals(modelId)
                    && "pending".equals(entry.extra.get("status")))
                {
                    return true;
                }
            }
        }

        return false;
    }

    /** Look up one entry by id (request workflows mutate entries in place). */
    public static BehaviorEntry find(long id)
    {
        synchronized (BehaviorLogManager.class)
        {
            for (int i = entries.size() - 1; i >= 0; i--)
            {
                BehaviorEntry entry = entries.get(i);

                if (entry.id == id)
                {
                    return entry;
                }
            }
        }

        return null;
    }

    /** Bump the data version after an in-place entry edit, so clients re-sync their lists. */
    public static void bump()
    {
        synchronized (BehaviorLogManager.class)
        {
            version++;
            dirty = true;
        }
    }

    /** Merged entries remember every repeat; the oldest timestamp drops once the cap hits. */
    private static void appendOccurrence(BehaviorEntry entry, long time, List<String> items)
    {
        if (entry.occurrences.size() >= MAX_OCCURRENCES)
        {
            entry.occurrences.remove(0);
            entry.extra.put("truncated", "1");
        }

        entry.occurrences.add(new BehaviorEntry.Occurrence(time, items));
    }

    /** Pooled entries accumulate the transferred bytes of every merged record. */
    private static void mergeBytes(BehaviorEntry last, Map<String, String> extra)
    {
        if (extra == null || !extra.containsKey(BYTES_KEY))
        {
            return;
        }

        long added;

        try
        {
            added = Long.parseLong(extra.get(BYTES_KEY));
        }
        catch (NumberFormatException e)
        {
            return;
        }

        long old = 0;

        try
        {
            old = Long.parseLong(last.extra.getOrDefault(BYTES_KEY, "0"));
        }
        catch (NumberFormatException ignored)
        {}

        last.extra.put(BYTES_KEY, String.valueOf(old + added));
    }

    /**
     * Drop every entry of this player and mark their record as never-edited.
     * Bumps the version, so every client re-syncs their whole list.
     */
    public static synchronized int removePlayerLogs(UUID playerId)
    {
        int removed;

        synchronized (BehaviorLogManager.class)
        {
            removed = 0;

            for (int i = entries.size() - 1; i >= 0; i--)
            {
                if (playerId.equals(entries.get(i).playerId))
                {
                    entries.remove(i);
                    removed++;
                }
            }

            PlayerRecord record = players.get(playerId);

            if (record != null)
            {
                record.lastAction = 0;
                record.everEdited = false;
            }

            version++;
            dirty = true;
        }

        return removed;
    }

    private static void appendItems(BehaviorEntry entry, List<String> items)
    {
        if (items == null)
        {
            return;
        }

        for (String item : items)
        {
            if (entry.items.size() >= MAX_ITEMS)
            {
                entry.extra.put("truncated", "1");

                break;
            }

            if (!entry.items.contains(item))
            {
                entry.items.add(item);
            }
        }
    }

    private static void enforceLimit()
    {
        int limit = TogetherSettings.getBehaviorLogLimit();

        while (entries.size() > limit)
        {
            entries.remove(0);
        }
    }

    /* Querying */

    /**
     * Build a snapshot for a client: every entry newer than {@code afterId}
     * (identity fields only when the requester is an OP), plus the player
     * records of players currently on the server (managers only — a manager is
     * a vanilla OP or a BBS level-2 admin). A client whose reported version
     * lags behind the server's (entries were removed, merged or evicted
     * elsewhere) gets the full list instead of an increment.
     */
    public static synchronized MapType query(MinecraftServer server, UUID requesterId, long afterId, long clientVersion, boolean requesterIsOp)
    {
        MapType map = new MapType();

        boolean manager = requesterIsOp || BbsOpManager.get(requesterId) >= BbsOpManager.LEVEL_ADMIN;

        map.putLong("version", version);
        map.putBool("op", requesterIsOp);
        map.putBool("manager", manager);

        boolean full = clientVersion != version;
        long cutoff = full ? 0L : afterId;
        ListType behaviors = new ListType();

        for (BehaviorEntry entry : entries)
        {
            if (entry.id > cutoff)
            {
                /* Managers (vanilla OP or BBS level 2) get identities, so the level group
                 * filter can match entries to players; everyone else stays anonymous. */
                behaviors.add(entry.toData(manager));
            }
        }

        map.put("behaviors", behaviors);

        if (manager)
        {
            ListType playerList = new ListType();

            for (PlayerRecord record : players.values())
            {
                /* Only players currently on the server show up in the list */
                if (server.getPlayerManager().getPlayer(record.id) == null)
                {
                    continue;
                }

                MapType playerData = record.toData();

                playerData.putBool("isOp", isOperator(server, record.id));
                /* Offline-mode accounts have name-derived UUIDs (version 3), premium ones v4 */
                playerData.putBool("offline", record.id.version() != 4);
                /* BBS permission level and (when restricted) the level-1 domain whitelist,
                 * so the player list can group players and managers can edit the grants. */
                playerData.putInt("bbsop", BbsOpManager.get(record.id));

                java.util.Set<String> whitelist = BbsOpManager.getGrants(record.id);

                if (whitelist != null)
                {
                    playerData.putString("bbsopGrants", String.join(",", whitelist));
                }

                playerList.add(playerData);
            }

            map.put("players", playerList);
        }

        return map;
    }

    /* OP management */

    public static boolean isOperator(MinecraftServer server, ServerPlayerEntity player)
    {
        return server.getPlayerManager().isOperator(player.getGameProfile());
    }

    public static boolean isOperator(MinecraftServer server, UUID playerId)
    {
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);

        if (player != null)
        {
            return server.getPlayerManager().isOperator(player.getGameProfile());
        }

        synchronized (BehaviorLogManager.class)
        {
            PlayerRecord record = players.get(playerId);

            if (record == null || server.getUserCache() == null)
            {
                return false;
            }

            GameProfile profile = findProfile(server, record.name);

            return profile != null && server.getPlayerManager().isOperator(profile);
        }
    }

    /**
     * Set or unset OP on a player, looked up online first, then through the
     * user cache. Returns whether the change was applied (a no-op counts as
     * applied — the requested state was already in place).
     */
    public static boolean setOperator(MinecraftServer server, UUID targetId, boolean op)
    {
        ServerPlayerEntity online = server.getPlayerManager().getPlayer(targetId);
        GameProfile profile = online != null ? online.getGameProfile() : null;

        if (profile == null)
        {
            String name;

            synchronized (BehaviorLogManager.class)
            {
                PlayerRecord record = players.get(targetId);

                name = record == null ? null : record.name;
            }

            profile = name == null ? null : findProfile(server, name);
        }

        if (profile == null)
        {
            System.out.println("[BBSTogether] Couldn't resolve a profile for " + targetId + ", op change ignored");

            return false;
        }

        boolean isOp = server.getPlayerManager().isOperator(profile);

        if (op == isOp)
        {
            return true;
        }

        if (op)
        {
            server.getPlayerManager().addToOperators(profile);
        }
        else
        {
            server.getPlayerManager().removeFromOperators(profile);
        }

        /* Push the new command tree right away, so the change bites immediately */
        ServerPlayerEntity target = server.getPlayerManager().getPlayer(profile.getId());

        if (target != null)
        {
            server.getCommandManager().sendCommandTree(target);
        }

        System.out.println("[BBSTogether] " + profile.getName() + (op ? " is now an operator" : " is no longer an operator"));

        return true;
    }

    private static GameProfile findProfile(MinecraftServer server, String name)
    {
        UserCache cache = server.getUserCache();

        if (cache == null || name == null || name.isEmpty())
        {
            return null;
        }

        return cache.findByName(name).orElse(null);
    }

    /* Persistence */

    private static File getFile()
    {
        return folder == null ? null : new File(folder, "behaviors.bin");
    }

    private static void load()
    {
        File file = getFile();

        if (file == null || !file.isFile())
        {
            return;
        }

        try
        {
            BaseType data = DataStorageUtils.readFromBytes(Files.readAllBytes(file.toPath()));

            if (data == null || !data.isMap())
            {
                return;
            }

            MapType map = data.asMap();

            nextId = Math.max(1L, map.getLong("nextId"));
            version = map.getLong("version");

            ListType entryList = map.getList("entries");

            for (int i = 0; i < entryList.size(); i++)
            {
                BehaviorEntry entry = BehaviorEntry.fromData(entryList.getMap(i));

                if (entry.playerId != null || entry.id > 0)
                {
                    entries.add(entry);
                }
            }

            ListType playerList = map.getList("players");

            for (int i = 0; i < playerList.size(); i++)
            {
                PlayerRecord record = PlayerRecord.fromData(playerList.getMap(i));

                if (record.id != null)
                {
                    players.put(record.id, record);
                }
            }

            enforceLimit();

            for (BehaviorEntry entry : entries)
            {
                nextId = Math.max(nextId, entry.id + 1);
            }
        }
        catch (Exception e)
        {
            System.err.println("Failed to load BBS Together behavior log: " + e);
        }
    }

    private static void saveIfDirty()
    {
        synchronized (BehaviorLogManager.class)
        {
            if (!dirty)
            {
                return;
            }
        }

        save();
    }

    private static synchronized void save()
    {
        if (folder == null)
        {
            return;
        }

        File file = getFile();

        try
        {
            folder.mkdirs();

            MapType map = new MapType();

            map.putLong("nextId", nextId);
            map.putLong("version", version);

            ListType entryList = new ListType();

            for (BehaviorEntry entry : entries)
            {
                entryList.add(entry.toData(true));
            }

            map.put("entries", entryList);

            ListType playerList = new ListType();

            for (PlayerRecord record : players.values())
            {
                playerList.add(record.toData());
            }

            map.put("players", playerList);

            java.nio.file.Path target = file.toPath();
            java.nio.file.Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.write(tmp, DataStorageUtils.writeToBytes(map));

            try {
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            }
            catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            dirty = false;
        }
        catch (Throwable e)
        {
            System.err.println("Failed to save BBS Together behavior log: " + e);
        }
    }
}
