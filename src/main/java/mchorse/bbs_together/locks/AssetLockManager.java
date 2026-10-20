package mchorse.bbs_together.locks;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.io.File;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import mchorse.bbs_together.bbsop.BbsOpManager;
import mchorse.bbs_together.network.TogetherServerNetwork;

/**
 * Server-side "lock asset" store.
 *
 * <p>A BBS level-2 admin can lock a model, a film or a placed model block and pick
 * who is still allowed to edit it: nobody, a whitelist, or everybody except a
 * blacklist. Only BBS level-2 players always keep access; a lock owner who is later
 * demoted is subject to the lock like everyone else.</p>
 *
 * <p>This is the authoritative side. Every write path that can touch a locked asset
 * asks {@link #canEdit}; UI state is only a mirror pushed through
 * {@code c_asset_locks}. Locks persist to {@code <world>/bbs/asset_locks.json}.</p>
 */
public final class AssetLockManager
{
    private static final boolean CASE_INSENSITIVE = isCaseInsensitiveFileSystem();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final ConcurrentHashMap<String, AssetLock> locks = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Long> noticeAt = new ConcurrentHashMap<>();
    private static File folder;
    private static volatile boolean dirty = false;

    private AssetLockManager()
    {}

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

    public static void onServerStopped()
    {
        synchronized (AssetLockManager.class)
        {
            locks.clear();
            noticeAt.clear();
            dirty = false;
            folder = null;
        }
    }

    /* Query */

    public static AssetLock get(AssetLockType type, String assetId)
    {
        if (type == null || assetId == null)
        {
            return null;
        }

        return locks.get(key(type, assetId));
    }

    public static Collection<AssetLock> snapshot()
    {
        return new ArrayList<>(locks.values());
    }

    public static String key(AssetLockType type, String assetId)
    {
        return type.id + "\n" + canonical(type, assetId);
    }

    private static boolean isCaseInsensitiveFileSystem()
    {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);

        return os.contains("win") || os.contains("mac") || os.contains("darwin");
    }

    /**
     * Windows (and usually macOS) resolves asset paths case-insensitively. Lock ids come
     * from the UI and from upload packets, so {@code Foo/Bar} and {@code foo/bar} must map
     * to the same lock on those filesystems or a case-changed upload bypasses the lock.
     */
    private static String canonical(AssetLockType type, String assetId)
    {
        if (assetId == null || !CASE_INSENSITIVE || type == AssetLockType.MODEL_BLOCK)
        {
            return assetId;
        }

        return assetId.toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Whether this player may edit the asset right now. No lock and level-2 admins
     * always pass; everyone else is decided by the lock mode. Lock ownership does not
     * bypass the lock by itself.
     */
    public static boolean canEdit(UUID playerId, AssetLockType type, String assetId)
    {
        if (type == null || assetId == null || assetId.isEmpty())
        {
            return true;
        }

        AssetLock lock = get(type, assetId);

        if (lock == null)
        {
            return true;
        }

        if (playerId != null && BbsOpManager.get(playerId) >= BbsOpManager.LEVEL_ADMIN)
        {
            return true;
        }

        return lock.allows(playerId);
    }

    public static boolean canEdit(ServerPlayerEntity player, AssetLockType type, String assetId)
    {
        return canEdit(player == null ? null : player.getUuid(), type, assetId);
    }

    /** Canonical model-block id: dimension + block coordinates. */
    public static String modelBlockId(World world, BlockPos pos)
    {
        String dimension = world == null ? "" : world.getRegistryKey().getValue().toString();

        return dimension + "|" + pos.getX() + "|" + pos.getY() + "|" + pos.getZ();
    }

    public static String modelBlockId(ServerPlayerEntity player, BlockPos pos)
    {
        return modelBlockId(player == null ? null : player.getWorld(), pos);
    }

    /** Whether {@code folder} contains at least one film the player may not edit. */
    public static boolean hasLockedFilmUnder(String folder, UUID playerId)
    {
        if (folder == null)
        {
            return false;
        }

        String prefix = folder.endsWith("/") ? folder : folder + "/";

        for (AssetLock lock : locks.values())
        {
            if (lock.type == AssetLockType.FILM
                && (lock.assetId.equals(folder) || lock.assetId.startsWith(prefix))
                && !canEdit(playerId, AssetLockType.FILM, lock.assetId))
            {
                return true;
            }
        }

        return false;
    }

    /* Mutation */

    /**
     * Create, update or remove a lock. {@code mode == null} removes it.
     *
     * @param members the whitelist or blacklist to store for {@code mode}; ignored for
     *                {@link AssetLockMode#ALL}. The other list is preserved.
     * @return whether anything changed
     */
    public static synchronized boolean set(ServerPlayerEntity actor, AssetLockType type, String assetId,
        AssetLockMode mode, Map<UUID, String> members)
    {
        if (actor == null || type == null || !BbsOpManager.canManage(actor))
        {
            return false;
        }

        String id = normalizeId(type, assetId);

        if (id == null)
        {
            return false;
        }

        String key = key(type, id);
        AssetLock existing = locks.get(key);

        if (mode == null)
        {
            if (existing == null)
            {
                return false;
            }

            locks.remove(key);
            dirty = true;
            save();
            return true;
        }

        AssetLock lock = existing;

        if (lock == null)
        {
            lock = new AssetLock(type, id);
            lock.owner = actor.getUuid();
            lock.ownerName = actor.getGameProfile().getName();
        }

        lock.mode = mode;

        if (mode != AssetLockMode.ALL)
        {
            Map<UUID, String> target = lock.listFor(mode);

            target.clear();

            if (members != null)
            {
                int count = 0;

                for (Map.Entry<UUID, String> entry : members.entrySet())
                {
                    if (entry.getKey() == null || count >= 256)
                    {
                        continue;
                    }

                    target.put(entry.getKey(), cleanName(entry.getValue()));
                    count += 1;
                }
            }
        }

        locks.put(key, lock);
        dirty = true;
        save();

        return true;
    }

    public static synchronized void removeForAsset(AssetLockType type, String assetId)
    {
        if (type == null || assetId == null)
        {
            return;
        }

        if (locks.remove(key(type, assetId)) != null)
        {
            dirty = true;
            save();
        }
    }

    /** Move a lock to a renamed asset (a film rename, for example). */
    public static synchronized void rename(AssetLockType type, String oldId, String newId)
    {
        if (type == null || oldId == null || newId == null || oldId.equals(newId))
        {
            return;
        }

        AssetLock old = locks.remove(key(type, oldId));

        if (old == null)
        {
            return;
        }

        AssetLock moved = copyFor(old, newId);

        locks.put(key(type, newId), moved);
        dirty = true;
        save();
    }

    /** Move every lock whose id starts with {@code oldPrefix} (folder renames). */
    public static synchronized void renamePrefix(AssetLockType type, String oldPrefix, String newPrefix)
    {
        if (type == null || oldPrefix == null || newPrefix == null)
        {
            return;
        }

        String oldBase = oldPrefix.endsWith("/") ? oldPrefix.substring(0, oldPrefix.length() - 1) : oldPrefix;
        String newBase = newPrefix.endsWith("/") ? newPrefix.substring(0, newPrefix.length() - 1) : newPrefix;
        String from = oldBase + "/";
        List<AssetLock> moved = new ArrayList<>();

        for (AssetLock lock : new ArrayList<>(locks.values()))
        {
            if (lock.type != type)
            {
                continue;
            }

            if (lock.assetId.equals(oldBase) || lock.assetId.startsWith(from))
            {
                locks.remove(key(type, lock.assetId));
                moved.add(lock);
            }
        }

        if (moved.isEmpty())
        {
            return;
        }

        for (AssetLock lock : moved)
        {
            String suffix = lock.assetId.equals(oldBase) ? "" : lock.assetId.substring(oldBase.length());

            locks.put(key(type, newBase + suffix), copyFor(lock, newBase + suffix));
        }

        dirty = true;
        save();
    }

    private static AssetLock copyFor(AssetLock lock, String assetId)
    {
        AssetLock copy = new AssetLock(lock.type, assetId);

        copy.owner = lock.owner;
        copy.ownerName = lock.ownerName;
        copy.mode = lock.mode;
        copy.whitelist.putAll(lock.whitelist);
        copy.blacklist.putAll(lock.blacklist);

        return copy;
    }

    /* Notification */

    /** Tell a player why their edit was refused, at most once every few seconds. */
    public static void notifyLocked(MinecraftServer server, ServerPlayerEntity player, AssetLockType type)
    {
        if (server == null || player == null)
        {
            return;
        }

        UUID id = player.getUuid();
        long now = System.currentTimeMillis();
        Long last = noticeAt.get(id);

        if (last != null && now - last < 5000L)
        {
            return;
        }

        noticeAt.put(id, now);

        String what = type == AssetLockType.FILM ? "影片" : (type == AssetLockType.MODEL ? "模型" : "模型方块");
        String message = "\u00a7e[BBS Together]\u00a7f 该" + what + "已被锁定，无法编辑";

        server.execute(() -> player.sendMessage(Text.literal(message), false));
    }

    /* Validation */

    public static String normalizeId(AssetLockType type, String assetId)
    {
        if (type == null || assetId == null)
        {
            return null;
        }

        String id = assetId.trim();

        while (id.endsWith("/"))
        {
            id = id.substring(0, id.length() - 1);
        }

        if (id.isEmpty() || id.length() > 512 || id.contains("..") || id.startsWith("/") || id.indexOf('\n') >= 0)
        {
            return null;
        }

        if (type == AssetLockType.MODEL_BLOCK)
        {
            String[] parts = id.split("\\|");

            if (parts.length != 4 || parts[0].isEmpty())
            {
                return null;
            }

            try
            {
                int x = Integer.parseInt(parts[1]);
                int y = Integer.parseInt(parts[2]);
                int z = Integer.parseInt(parts[3]);

                return parts[0] + "|" + x + "|" + y + "|" + z;
            }
            catch (NumberFormatException e)
            {
                return null;
            }
        }

        return id;
    }

    private static String cleanName(String name)
    {
        if (name == null)
        {
            return "";
        }

        String cleaned = name.replaceAll("[\\r\\n\\t]", " ").trim();

        return cleaned.length() > 64 ? cleaned.substring(0, 64) : cleaned;
    }

    /* Networking */

    public static void syncToPlayer(ServerPlayerEntity player)
    {
        TogetherServerNetwork.sendAssetLocks(player);
    }

    /* Persistence */

    private static File getFile()
    {
        return folder == null ? null : new File(folder, "asset_locks.json");
    }

    private static synchronized void load()
    {
        locks.clear();

        File file = getFile();

        if (file == null || !file.isFile())
        {
            return;
        }

        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8))
        {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);

            if (root == null || !root.has("locks") || !root.get("locks").isJsonArray())
            {
                return;
            }

            for (JsonElement element : root.getAsJsonArray("locks"))
            {
                if (!element.isJsonObject())
                {
                    continue;
                }

                JsonObject data = element.getAsJsonObject();
                AssetLockType type = AssetLockType.byId(getString(data, "type"));
                String id = normalizeId(type, getString(data, "id"));

                if (type == null || id == null)
                {
                    continue;
                }

                AssetLock lock = new AssetLock(type, id);
                lock.owner = parseUuid(getString(data, "owner"));
                lock.ownerName = cleanName(getString(data, "ownerName"));
                AssetLockMode mode = AssetLockMode.byOrdinal(data.has("mode") ? data.get("mode").getAsInt() : 0);

                lock.mode = mode == null ? AssetLockMode.ALL : mode;

                readMembers(data, "whitelist", lock.whitelist);
                readMembers(data, "blacklist", lock.blacklist);

                locks.put(key(type, id), lock);
            }
        }
        catch (Exception e)
        {
            System.err.println("[BBSTogether] Failed to load asset_locks.json: " + e);
        }
    }

    private static void readMembers(JsonObject data, String name, Map<UUID, String> target)
    {
        if (!data.has(name) || !data.get(name).isJsonArray())
        {
            return;
        }

        for (JsonElement element : data.getAsJsonArray(name))
        {
            if (!element.isJsonObject())
            {
                continue;
            }

            JsonObject member = element.getAsJsonObject();
            UUID id = parseUuid(getString(member, "id"));

            if (id != null)
            {
                target.put(id, cleanName(getString(member, "name")));
            }
        }
    }

    private static String getString(JsonObject data, String name)
    {
        try
        {
            return data.has(name) && !data.get(name).isJsonNull() ? data.get(name).getAsString() : "";
        }
        catch (Exception e)
        {
            return "";
        }
    }

    private static UUID parseUuid(String value)
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
            JsonArray array = new JsonArray();

            for (AssetLock lock : locks.values())
            {
                JsonObject data = new JsonObject();

                data.addProperty("type", lock.type.id);
                data.addProperty("id", lock.assetId);
                data.addProperty("owner", lock.owner == null ? "" : lock.owner.toString());
                data.addProperty("ownerName", lock.ownerName);
                data.addProperty("mode", lock.mode.ordinal());
                data.add("whitelist", writeMembers(lock.whitelist));
                data.add("blacklist", writeMembers(lock.blacklist));

                array.add(data);
            }

            root.add("locks", array);

            Path target = file.toPath();
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");

            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8))
            {
                GSON.toJson(root, writer);
            }

            try
            {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            catch (java.nio.file.AtomicMoveNotSupportedException e)
            {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }

            dirty = false;
        }
        catch (Exception e)
        {
            System.err.println("[BBSTogether] Failed to save asset_locks.json: " + e);
        }
    }

    private static JsonArray writeMembers(Map<UUID, String> members)
    {
        JsonArray array = new JsonArray();

        for (Map.Entry<UUID, String> entry : members.entrySet())
        {
            JsonObject member = new JsonObject();

            member.addProperty("id", entry.getKey().toString());
            member.addProperty("name", entry.getValue());

            array.add(member);
        }

        return array;
    }
}
