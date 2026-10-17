package mchorse.bbs_together.bbsop;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.WorldSavePath;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import mchorse.bbs_together.network.TogetherServerNetwork;

/**
 * Server-side per-player BBS edit permission.
 *
 * <p>Three levels: 0 = read-only (browse + download), 1 = editor (can edit every
 * {@link BbsOpDomain} unless restricted, see below), 2 = admin (can edit everything and
 * manage other players' levels / domains).</p>
 *
 * <p>The server is the final authority: levels live in a {@link ConcurrentHashMap} and are
 * persisted to <code>&lt;world&gt;/bbs/bbsop.json</code>. Every player defaults to level 0
 * (read-only) until explicitly set; the world host is defaulted to level 2 on their first
 * join (see {@link #onPlayerJoin(MinecraftServer, ServerPlayerEntity)}), and a stored level
 * always wins over that default. Level-1 players may additionally be restricted by a
 * <b>domain whitelist</b>: the {@code grants} map keyed by player holds the set of domain
 * ids they may control. Absent key = unrestricted (level 1 defaults to full editing);
 * present key = only those domains (an empty set means nothing is editable). Level 2
 * always bypasses the whitelist.</p>
 *
 * <p>On join, and whenever {@link #set} / {@link #setGrant} change something, the affected
 * client is pushed a {@code c_bbsop_sync} packet so its UI locks/unlocks live, without a
 * re-login. Levels persist across sessions, so a player lands back in their group every
 * time they join.</p>
 *
 * <p>Thread-safety: callers arrive from both the server thread and network threads; the
 * maps themselves are concurrent, and save/load are guarded by a monitor.</p>
 */
public final class BbsOpManager
{
    /** Level 0: read-only (browse + download, no upload/edit). */
    public static final int LEVEL_READ_ONLY = 0;
    /** Level 1: editor — full editing by default, restricted by the domain whitelist when set. */
    public static final int LEVEL_EDITOR = 1;
    /** Level 2: admin — edits everything and manages other players' BBS permissions. */
    public static final int LEVEL_ADMIN = 2;

    private static final ConcurrentHashMap<UUID, Integer> levels = new ConcurrentHashMap<>();
    /** Explicit per-player domain whitelists for level-1 editors; absent = unrestricted. */
    private static final ConcurrentHashMap<UUID, Set<String>> grants = new ConcurrentHashMap<>();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static File folder;
    private static boolean dirty = false;

    private BbsOpManager() {}

    /* Lifecycle */

    public static void onServerStarted(MinecraftServer server)
    {
        folder = new File(server.getSavePath(WorldSavePath.ROOT).toFile(), "bbs");
        load();
    }

    public static synchronized void onServerStopping()
    {
        save();
    }

    public static synchronized void onServerStopped()
    {
        levels.clear();
        grants.clear();
        dirty = false;
        folder = null;
    }

    /* Query / mutate */

    /**
     * Default the world host to level 2 on their first join, unless a level is already stored
     * for them (so an admin can still demote the host).
     *
     * <p>On a dedicated server there is no player identity for "the person who started it", so
     * the first player to join a world with no stored levels becomes the admin, once.</p>
     *
     * @return whether a level was granted on this join
     */
    public static boolean onPlayerJoin(MinecraftServer server, ServerPlayerEntity player)
    {
        UUID playerId = player.getUuid();

        if (levels.containsKey(playerId))
        {
            return false;
        }

        if (!isHost(server, player))
        {
            return false;
        }

        levels.put(playerId, LEVEL_ADMIN);
        dirty = true;
        save();

        System.out.println("[BBSTogether] " + player.getGameProfile().getName() + " joined as the world host and defaulted to BBS level 2");

        return true;
    }

    /** The integrated server's own player, or the first player of a dedicated world with no levels yet. */
    private static boolean isHost(MinecraftServer server, ServerPlayerEntity player)
    {
        if (server.isHost(player.getGameProfile()))
        {
            return true;
        }

        return server.isDedicated() && levels.isEmpty();
    }

    /** Effective level for a player; never-set players default to read-only (0). */
    public static int get(UUID playerId)
    {
        return levels.getOrDefault(playerId, LEVEL_READ_ONLY);
    }

    /** The player's explicit domain whitelist, or {@code null} when unrestricted. */
    public static Set<String> getGrants(UUID playerId)
    {
        return grants.get(playerId);
    }

    /**
     * Whether the player may control the given domain right now: level 2 always,
     * level 1 unless whitelisted away, level 0 never.
     */
    public static boolean isAllowed(UUID playerId, BbsOpDomain domain)
    {
        int level = get(playerId);

        if (level >= LEVEL_ADMIN)
        {
            return true;
        }

        if (level <= LEVEL_READ_ONLY)
        {
            return false;
        }

        Set<String> whitelist = grants.get(playerId);

        return whitelist == null || whitelist.contains(domain.id);
    }

    /** {@link #isAllowed(UUID, BbsOpDomain)} for an online player. */
    public static boolean isAllowed(ServerPlayerEntity player, BbsOpDomain domain)
    {
        return isAllowed(player.getUuid(), domain);
    }

    /**
     * Whether this player may manage other players' BBS permissions: BBS level 2 only.
     * Vanilla OPs are deliberately excluded from the in-game management UI — they use the
     * {@code /bbsop} command (console included) to grant level 2 in the first place.
     */
    public static boolean canManage(ServerPlayerEntity player)
    {
        return get(player.getUuid()) >= LEVEL_ADMIN;
    }

    /**
     * Set a player's level. Returns {@code true} if the value actually changed.
     * Persists; the caller is responsible for pushing sync packets.
     */
    public static boolean set(UUID playerId, int level)
    {
        if (level < LEVEL_READ_ONLY) level = LEVEL_READ_ONLY;
        if (level > LEVEL_ADMIN) level = LEVEL_ADMIN;

        Integer previous = levels.get(playerId);
        int prev = previous == null ? LEVEL_READ_ONLY : previous;

        if (prev == level)
        {
            return false;
        }

        levels.put(playerId, level);
        dirty = true;
        save();
        return true;
    }

    /**
     * Allow or deny one domain for a (level-1) player. The whitelist holds the <b>allowed</b>
     * domains, so "allow" adds to it and "deny" removes from it. The first explicit change
     * flips the player into whitelist mode: everything was allowed before, so "deny" starts
     * the whitelist with every other domain, and "allow" on an unrestricted player is a
     * no-op. Returns {@code true} if anything changed.
     */
    public static boolean setGrant(UUID playerId, BbsOpDomain domain, boolean allowed)
    {
        if (get(playerId) != LEVEL_EDITOR)
        {
            return false;
        }

        Set<String> whitelist = grants.get(playerId);

        if (allowed)
        {
            if (whitelist == null)
            {
                /* Unrestricted: already allowed. */
                return false;
            }

            boolean changed = whitelist.add(domain.id);

            if (changed)
            {
                dirty = true;
                save();
            }

            return changed;
        }

        if (whitelist == null)
        {
            /* Enter whitelist mode: everything except the denied domain stays allowed. */
            Set<String> created = ConcurrentHashMap.newKeySet();

            for (BbsOpDomain other : BbsOpDomain.values())
            {
                if (other != domain)
                {
                    created.add(other.id);
                }
            }

            grants.put(playerId, created);
            dirty = true;
            save();

            return true;
        }

        boolean changed = whitelist.remove(domain.id);

        if (changed)
        {
            dirty = true;
            save();
        }

        return changed;
    }

    /** Drop a player's domain whitelist, returning a level-1 editor to full editing. */
    public static boolean resetGrants(UUID playerId)
    {
        boolean existed = grants.remove(playerId) != null;

        if (existed)
        {
            dirty = true;
            save();
        }

        return existed;
    }

    /* Networking */

    /**
     * Push the local player's current level and (if restricted) their domain whitelist to
     * them. Wire format: {@code int level, boolean whitelistMode, [int n, n * string id]}.
     */
    public static void syncToPlayer(ServerPlayerEntity player)
    {
        UUID id = player.getUuid();
        PacketByteBuf out = PacketByteBufs.create();

        out.writeInt(get(id));

        Set<String> whitelist = grants.get(id);

        out.writeBoolean(whitelist != null);

        if (whitelist != null)
        {
            out.writeInt(whitelist.size());

            for (String domainId : whitelist)
            {
                out.writeString(domainId);
            }
        }

        ServerPlayNetworking.send(player, TogetherServerNetwork.CLIENT_BBSOP_SYNC, out);
    }

    /** Re-push levels to every online player (broadcast after a change). */
    public static void syncToAll(MinecraftServer server)
    {
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList())
        {
            syncToPlayer(p);
        }
    }

    /* Persistence */

    private static File getFile()
    {
        return folder == null ? null : new File(folder, "bbsop.json");
    }

    private static void load()
    {
        File file = getFile();

        if (file == null || !file.isFile())
        {
            return;
        }

        try (FileReader reader = new FileReader(file))
        {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);

            if (root == null)
            {
                return;
            }

            levels.clear();
            grants.clear();

            if (root.has("levels") && root.get("levels").isJsonObject())
            {
                /* New format: {"levels": {uuid: level}, "grants": {uuid: [domain, ...]}} */
                for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("levels").entrySet())
                {
                    parseUuid(entry.getKey(), id -> levels.put(id, entry.getValue().getAsInt()));
                }

                if (root.has("grants") && root.get("grants").isJsonObject())
                {
                    for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("grants").entrySet())
                    {
                        Set<String> set = ConcurrentHashMap.newKeySet();

                        for (JsonElement item : entry.getValue().getAsJsonArray())
                        {
                            if (BbsOpDomain.byId(item.getAsString()) != null)
                            {
                                set.add(item.getAsString());
                            }
                        }

                        parseUuid(entry.getKey(), id -> grants.put(id, set));
                    }
                }
            }
            else
            {
                /* Legacy format: a flat {uuid: level} map from before the 1/2-level split. */
                Type type = new TypeToken<HashMap<String, Integer>>(){}.getType();
                HashMap<String, Integer> raw = GSON.fromJson(root, type);

                if (raw != null)
                {
                    for (Map.Entry<String, Integer> entry : raw.entrySet())
                    {
                        parseUuid(entry.getKey(), id -> levels.put(id, entry.getValue()));
                    }
                }
            }
        }
        catch (Exception e)
        {
            System.err.println("[BBSTogether] Failed to load bbsop.json: " + e);
        }
    }

    private static void parseUuid(String value, java.util.function.Consumer<UUID> consumer)
    {
        try
        {
            consumer.accept(UUID.fromString(value));
        }
        catch (IllegalArgumentException ignored)
        {}
    }

    private static synchronized void save()
    {
        if (folder == null || !dirty)
        {
            return;
        }

        File file = getFile();

        try
        {
            folder.mkdirs();

            JsonObject root = new JsonObject();
            JsonObject levelData = new JsonObject();
            JsonObject grantData = new JsonObject();

            for (Map.Entry<UUID, Integer> entry : levels.entrySet())
            {
                levelData.addProperty(entry.getKey().toString(), entry.getValue());
            }

            for (Map.Entry<UUID, Set<String>> entry : grants.entrySet())
            {
                JsonArray array = new JsonArray();

                for (String domainId : entry.getValue())
                {
                    array.add(domainId);
                }

                grantData.add(entry.getKey().toString(), array);
            }

            root.add("levels", levelData);
            root.add("grants", grantData);

            try (FileWriter writer = new FileWriter(file))
            {
                GSON.toJson(root, writer);
            }

            dirty = false;
        }
        catch (Exception e)
        {
            System.err.println("[BBSTogether] Failed to save bbsop.json: " + e);
        }
    }
}
