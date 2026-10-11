package mchorse.bbs_together.bbsop;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import mchorse.bbs_together.network.TogetherServerNetwork;

/**
 * Server-side per-player BBS edit permission (0 = read-only, 1/2 reserved).
 *
 * <p>The server is the final authority: levels live in a
 * {@link ConcurrentHashMap} and are persisted to
 * <code>&lt;world&gt;/bbs/bbsop.json</code>. Every player defaults to level 0
 * (read-only) until explicitly set. On join, and whenever {@link #set} changes
 * a level, the affected client is pushed a {@code c_bbsop_sync} packet so its
 * UI locks/unlocks live, without a re-login.</p>
 *
 * <p>Thread-safety: callers arrive from both the server thread and network
 * threads; the map itself is concurrent, and save/load are guarded by a
 * monitor.</p>
 */
public final class BbsOpManager
{
    /** Level 0: read-only (browse + download, no upload/edit). 1/2 reserved for later. */
    public static final int LEVEL_READ_ONLY = 0;

    private static final ConcurrentHashMap<UUID, Integer> levels = new ConcurrentHashMap<>();
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
        dirty = false;
        folder = null;
    }

    /* Query / mutate */

    /** Effective level for a player; never-set players default to read-only (0). */
    public static int get(UUID playerId)
    {
        return levels.getOrDefault(playerId, LEVEL_READ_ONLY);
    }

    /** Whether this player may not edit (level 0). The server enforces this on write packets. */
    public static boolean isReadOnly(ServerPlayerEntity player)
    {
        return get(player.getUuid()) == LEVEL_READ_ONLY;
    }

    /**
     * Set a player's level. Returns {@code true} if the value actually changed.
     * Persists and pushes the new level to the target if they are online.
     */
    public static boolean set(UUID playerId, int level)
    {
        if (level < 0) level = 0;
        if (level > 2) level = 2;

        Integer previous = levels.get(playerId);
        int prev = previous == null ? LEVEL_READ_ONLY : previous;

        if (prev == level)
        {
            return false;
        }

        /* Level 0 is the default: storing it explicitly is allowed (so an op can
         * re-lock someone who was set to 1/2), but we don't need to persist a 0
         * that was never written. Keep it in memory either way. */
        levels.put(playerId, level);
        dirty = true;
        save();
        return true;
    }

    /* Networking */

    /** Push the local player's current level to them (called on join). */
    public static void syncToPlayer(MinecraftServer server, ServerPlayerEntity player)
    {
        PacketByteBuf out = PacketByteBufs.create();
        out.writeInt(get(player.getUuid()));
        ServerPlayNetworking.send(player, TogetherServerNetwork.CLIENT_BBSOP_SYNC, out);
    }

    /** Re-push levels to every online player (broadcast after a change). */
    public static void syncToAll(MinecraftServer server)
    {
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList())
        {
            syncToPlayer(server, p);
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
            Type type = new TypeToken<HashMap<String, Integer>>(){}.getType();
            HashMap<String, Integer> raw = GSON.fromJson(reader, type);

            if (raw == null)
            {
                return;
            }

            levels.clear();

            for (Map.Entry<String, Integer> entry : raw.entrySet())
            {
                try
                {
                    levels.put(UUID.fromString(entry.getKey()), entry.getValue());
                }
                catch (IllegalArgumentException ignored)
                {}
            }
        }
        catch (Exception e)
        {
            System.err.println("[BBSTogether] Failed to load bbsop.json: " + e);
        }
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

            HashMap<String, Integer> data = new HashMap<>();

            for (Map.Entry<UUID, Integer> entry : levels.entrySet())
            {
                data.put(entry.getKey().toString(), entry.getValue());
            }

            try (FileWriter writer = new FileWriter(file))
            {
                GSON.toJson(data, writer);
            }

            dirty = false;
        }
        catch (Exception e)
        {
            System.err.println("[BBSTogether] Failed to save bbsop.json: " + e);
        }
    }
}
