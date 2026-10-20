package mchorse.bbs_together.locks;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

import java.io.File;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Durable "this model was deliberately deleted" markers.
 *
 * <p>The old in-memory 30-minute map could not survive a restart and the client upload
 * pass treats a missing manifest entry as "the server does not have it yet", so offline
 * clients resurrected deleted models. Tombstones now live in
 * {@code <world>/bbs/asset_tombstones.json}; they are removed only by an explicit restore
 * command (or by re-creating the model through an admin action).</p>
 */
public final class AssetTombstones {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, Long> DELETED = new ConcurrentHashMap<>();
    private static File file;
    private static boolean dirty;

    private AssetTombstones() {}

    public static void onServerStarted(MinecraftServer server) {
        DELETED.clear();
        Path root = server.getSavePath(WorldSavePath.ROOT).resolve("bbs");
        file = root.resolve("asset_tombstones.json").toFile();
        load();
    }

    public static void onServerStopping() {
        save();
        DELETED.clear();
        file = null;
    }

    public static boolean isTombstoned(String modelId) {
        return modelId != null && DELETED.containsKey(modelId);
    }

    public static void tombstone(String modelId, String actor) {
        if (modelId == null || modelId.isEmpty()) {
            return;
        }

        DELETED.put(modelId, System.currentTimeMillis());
        dirty = true;
        save();
    }

    public static boolean restore(String modelId) {
        if (modelId == null || DELETED.remove(modelId) == null) {
            return false;
        }

        dirty = true;
        save();
        return true;
    }

    public static Map<String, Long> snapshot() {
        return Map.copyOf(DELETED);
    }

    private static void load() {
        if (file == null || !file.isFile()) {
            return;
        }

        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);

            if (root == null || !root.has("models") || !root.get("models").isJsonObject()) {
                return;
            }

            for (Map.Entry<String, com.google.gson.JsonElement> entry : root.getAsJsonObject("models").entrySet()) {
                if (entry.getValue().isJsonPrimitive()) {
                    DELETED.put(entry.getKey(), entry.getValue().getAsLong());
                }
            }
        }
        catch (Exception e) {
            System.err.println("[BBSTogether] Failed to load asset tombstones: " + e);
        }
    }

    private static void save() {
        if (file == null || !dirty) {
            return;
        }

        try {
            Path target = file.toPath();
            Files.createDirectories(target.getParent());

            JsonObject root = new JsonObject();
            JsonObject models = new JsonObject();

            for (Map.Entry<String, Long> entry : DELETED.entrySet()) {
                models.addProperty(entry.getKey(), entry.getValue());
            }

            root.add("models", models);

            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }

            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }

            dirty = false;
        }
        catch (Exception e) {
            System.err.println("[BBSTogether] Failed to save asset tombstones: " + e);
        }
    }
}
