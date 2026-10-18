package mchorse.bbs_together.network;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.BBSResources;
import mchorse.bbs_mod.data.DataStorageUtils;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.Films;
import mchorse.bbs_mod.film.Recorder;
import mchorse.bbs_mod.film.replays.FormProperties;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.network.ClientPacketCrusher;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.settings.values.base.BaseValueGroup;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.ContentType;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.film.UIClips;
import mchorse.bbs_mod.ui.film.UIClipsPanel;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.framework.UIBaseMenu;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.UIScreen;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeEditor;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.graphs.IUIKeyframeGraph;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.graphs.UIKeyframeDopeSheet;
import mchorse.bbs_mod.utils.DataPath;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.clips.Clips;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.film.replays.tracks.TrackId;
import mchorse.bbs_together.BBSTogetherFix14;
import mchorse.bbs_together.BBSTogetherFix22;
import mchorse.bbs_together.BBSTogetherFix28Client;
import mchorse.bbs_together.BBSTogetherFix33Client;
import mchorse.bbs_together.BBSTogetherFix45;
import mchorse.bbs_together.BBSTogetherFixReplayStructure;
import mchorse.bbs_together.TogetherSettings;
import mchorse.bbs_together.commits.FilmCommit;
import mchorse.bbs_together.network.TogetherServerNetwork;
import mchorse.bbs_together.sync.AssetAutoSyncWatcher;
import mchorse.bbs_together.sync.AssetSyncTracker;
import mchorse.bbs_together.ui.commits.CommitColors;
import mchorse.bbs_together.ui.commits.CommitUi;
import mchorse.bbs_together.ui.commits.FilmCommitClient;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.client.MinecraftClient;

@Environment(value=EnvType.CLIENT)
public class TogetherClientNetwork {
    private static final ClientPacketCrusher crusher = new CompressedClientPacketCrusher();
    private static boolean isBBSTogetherOnServer = false;
    /** A1/A2: what the server currently believes we own ({path: sha1}), so inventory reports can be
     * deltas instead of a full 100+ KB map every time. Cleared with the session. */
    private static final HashMap<String, String> lastReportedSha1 = new HashMap<>();
    /** Coalesces overlapping inventory passes: a walk in flight means at most one more afterwards. */
    private static final java.util.concurrent.atomic.AtomicBoolean inventoryInFlight = new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final java.util.concurrent.atomic.AtomicBoolean inventoryDirty = new java.util.concurrent.atomic.AtomicBoolean(false);
    public static final ConcurrentHashMap<UUID, Integer> playerColors = new ConcurrentHashMap();
    public static final ConcurrentHashMap<UUID, CursorPosition> playerCursors = new ConcurrentHashMap();
    public static final ConcurrentHashMap<UUID, String> filmSelections = new ConcurrentHashMap();
    public static final ConcurrentHashMap<String, Map<Integer, List<UUID>>> replaySelections = new ConcurrentHashMap();
    public static final ConcurrentHashMap<String, Map<Integer, UUID>> replayFormLocks = new ConcurrentHashMap();
    public static final ConcurrentHashMap<UUID, String> playersInFilmScreen = new ConcurrentHashMap();
    /** Film id -> branch this client currently holds for it (server-driven). */
    private static final ConcurrentHashMap<String, String> filmBranches = new ConcurrentHashMap<>();
    /** Film id -> player -> commit id currently selected in that film's graph. */
    private static final ConcurrentHashMap<String, ConcurrentHashMap<UUID, String>> commitSelections = new ConcurrentHashMap<>();
    /** Film id -> player -> commit id where that player's branch currently sits. */
    private static final ConcurrentHashMap<String, ConcurrentHashMap<UUID, String>> playerCommitPositions = new ConcurrentHashMap<>();
    /** Film id -> "branch\trequestTime" while a personal checkout waits for its full film. */
    private static final ConcurrentHashMap<String, String> pendingBranchCheckouts = new ConcurrentHashMap<>();
    private static final long BRANCH_CHECKOUT_TIMEOUT_MS = 5000L;
    public static volatile boolean damageControlActive = false;
    public static volatile boolean recordingSessionActive = false;
    public static volatile int assetDownloadTotal = 0;
    public static volatile int assetDownloadDone = 0;
    public static volatile int assetDownloadErrors = 0;
    public static volatile int assetUploadTotal = 0;
    public static volatile int assetUploadDone = 0;
    public static volatile int assetUploadErrors = 0;
    public static volatile long assetDownloadFinishedAt = 0L;
    public static volatile long assetUploadFinishedAt = 0L;
    public static volatile String lastLocalFilmId = "";
    public static volatile int lastLocalReplayIdx = -1;
    public static volatile String pendingSwapFilmId = null;
    public static volatile int pendingSwapFrom = -1;
    public static volatile int pendingSwapTo = -1;
    /** Model IDs whose folder contained at least one file too large to download; shown red in the picker. */
    public static final ConcurrentHashMap.KeySetView<String, Boolean> tooLargeModels = ConcurrentHashMap.newKeySet();
    /** Model IDs that exist locally but are NOT present in the server's asset manifest (never uploaded). */
    public static final ConcurrentHashMap.KeySetView<String, Boolean> localOnlyModels = ConcurrentHashMap.newKeySet();
    /* Server behavior log (panel data), filled by CLIENT_BEHAVIOR_LIST */
    public static volatile boolean isOperator = false;
    /** Vanilla OP <em>or</em> BBS level-2 admin: may see the player list and manage BBS levels. */
    public static volatile boolean isManager = false;
    public static final java.util.NavigableMap<Long, mchorse.bbs_together.behaviors.BehaviorEntry> behaviorEntries = new java.util.TreeMap<>();
    public static final Map<UUID, mchorse.bbs_together.behaviors.PlayerRecord> behaviorPlayers = new java.util.concurrent.ConcurrentHashMap<>();
    public static volatile long behaviorVersion = 0;
    /** Bumped every time panel data changes, so the panel knows when to rebuild. */
    public static volatile long behaviorDataStamp = 0;
    /** 0 = normal (follow settings), 1 = download-only resync, 2 = upload-only resync. */
    private static volatile int resyncMode = 0;

    /* Commit colours are server-side now; these only smooth the trip there. */
    /** Last colour picked per commit, waiting for the picker to go quiet. */
    private static final Map<String, PendingCommitColor> pendingCommitColors = new LinkedHashMap<>();
    private static volatile long pendingCommitColorAt = 0L;
    private static final long COMMIT_COLOR_DEBOUNCE_MS = 250L;
    /** Legacy local colours already pushed once, so a graph refresh does not resend them. */
    private static final java.util.Set<String> migratedCommitColors = ConcurrentHashMap.newKeySet();

    /* ---- A2: local SHA-1 cache (mtime+size keyed) ------------------------------------- */

    private static final ConcurrentHashMap<String, HashEntry> localHashCache = new ConcurrentHashMap();

    private static final class HashEntry {
        final long mtime;
        final long size;
        final String sha1;

        HashEntry(long mtime, long size, String sha1) {
            this.mtime = mtime;
            this.size = size;
            this.sha1 = sha1;
        }
    }

    private static String sha1OfLocalFileCached(Path path) throws IOException {
        ensureHashCacheLoaded();
        long mtime = Files.getLastModifiedTime(path).toMillis();
        long size = path.toFile().length();
        HashEntry e = localHashCache.get(path.toString());
        if (e != null && e.mtime == mtime && e.size == size) {
            return e.sha1;
        }
        /* Single-flight: the inventory pass and the manifest diff may ask for the same file
         * at the same time on join. Hashing it twice means reading it twice. */
        synchronized (HASH_WALK_LOCK) {
            HashEntry again = localHashCache.get(path.toString());
            if (again != null && again.mtime == mtime && again.size == size) {
                return again.sha1;
            }
            String sha1 = sha1OfLocalFile(path);
            localHashCache.put(path.toString(), new HashEntry(mtime, size, sha1));
            hashCacheDirty = true;
            return sha1;
        }
    }

    private static void cacheLocalSha1(Path path, String sha1) {
        if (sha1 == null || sha1.isEmpty()) return;
        try {
            localHashCache.put(path.toString(), new HashEntry(Files.getLastModifiedTime(path).toMillis(), path.toFile().length(), sha1));
            hashCacheDirty = true;
        }
        catch (Exception ignored) {}
    }

    /* ---- Persisted hash cache -------------------------------------------------------
     * Hashing every asset on join means reading the whole assets folder (gigabytes) at the
     * exact moment the client is also downloading: the disk thrashes, the client stalls and
     * the server times it out. Persisting {mtime,size -> sha1} turns that into a stat-only
     * walk, both for the inventory delta and for the upload diff. The cache lives outside
     * config/bbs/assets so it is never itself synced. */
    private static final Object HASH_CACHE_LOCK = new Object();
    private static final Object HASH_WALK_LOCK = new Object();
    private static volatile boolean hashCacheLoaded = false;
    private static volatile boolean hashCacheDirty = false;

    private static File hashCacheFile() {
        return new File(FabricLoader.getInstance().getConfigDir().toFile(), "bbs_together_hashcache.txt");
    }

    private static void ensureHashCacheLoaded() {
        if (hashCacheLoaded) {
            return;
        }
        synchronized (HASH_CACHE_LOCK) {
            if (hashCacheLoaded) {
                return;
            }
            hashCacheLoaded = true;
            File file = hashCacheFile();
            if (!file.isFile()) {
                return;
            }
            try {
                int loaded = 0;
                for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                    if (line.isEmpty() || line.charAt(0) == '#') {
                        continue;
                    }
                    String[] parts = line.split("\t", 4);
                    if (parts.length != 4) {
                        continue;
                    }
                    localHashCache.put(parts[3], new HashEntry(Long.parseLong(parts[2]), Long.parseLong(parts[1]), parts[0]));
                    if (++loaded > 200_000) {
                        break;
                    }
                }
            }
            catch (Exception ignored) {}
        }
    }

    /** Writes the cache when something changed. Cheap: one line per asset. */
    private static void saveHashCache() {
        if (!hashCacheDirty) {
            return;
        }
        synchronized (HASH_CACHE_LOCK) {
            if (!hashCacheDirty) {
                return;
            }
            hashCacheDirty = false;
            try {
                StringBuilder builder = new StringBuilder(localHashCache.size() * 80);
                builder.append("# bbs-together local sha1 cache: sha1\\tsize\\tmtime\\tabsolute path\n");
                for (java.util.Map.Entry<String, HashEntry> entry : localHashCache.entrySet()) {
                    HashEntry value = entry.getValue();
                    builder.append(value.sha1).append('\t').append(value.size).append('\t').append(value.mtime).append('\t').append(entry.getKey()).append('\n');
                }
                File file = hashCacheFile();
                File parent = file.getParentFile();
                if (parent != null && !parent.isDirectory()) {
                    parent.mkdirs();
                }
                Path temporary = new File(file.getPath() + ".tmp").toPath();
                Files.write(temporary, builder.toString().getBytes(StandardCharsets.UTF_8));
                Files.move(temporary, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            catch (Exception ignored) {}
        }
    }

    /* ---- B4: cached last server manifest + fingerprint -------------------------------- */

    /** Last received server manifest, kept across joins so unchanged manifests cost nothing. */
    private static final HashMap<String, String> lastServerSha1Map = new HashMap();
    private static final HashMap<String, Long> lastServerSizeMap = new HashMap();
    private static final HashMap<String, Long> lastServerMtimeMap = new HashMap();
    private static volatile long lastManifestVersion = 0L;

    /**
     * Conflict arbitration slack for manifest mtimes, in millis. Copies whose edit times are
     * closer than this are treated as "server wins" so two clients editing near-simultaneously
     * converge deterministically on the server copy instead of ping-ponging uploads.
     */
    private static final long MTIME_TOLERANCE_MS = 3000L;

    /* ---- Auto-sync flip-flop guard ----------------------------------------------------- */

    /** A file whose auto-sync direction alternates between upload and download within this
     * window is considered caught in a rewrite loop. */
    private static final long AUTO_FLIP_WINDOW_MS = 120_000L;
    /** How long a loop-caught file is excluded from auto sync (manual resync is not affected). */
    private static final long AUTO_COOLDOWN_MS = 300_000L;
    /** Alternations within the window before the cooldown kicks in. */
    private static final int AUTO_FLIP_LIMIT = 2;

    private static final Map<String, Character> autoLastDir = new ConcurrentHashMap();
    private static final Map<String, Long> autoLastDirMs = new ConcurrentHashMap();
    private static final Map<String, Integer> autoFlips = new ConcurrentHashMap();
    private static final Map<String, Long> autoCooldown = new ConcurrentHashMap();

    /**
     * Flip-flop guard for auto sync. Some generators rewrite files inside a model folder
     * on every client without anyone editing; each rewrite uploads, the other side
     * downloads, its generator rewrites again — an endless upload/download loop. When a
     * file's auto direction keeps alternating, cool it down and say which file it is.
     *
     * @param relPath the asset-relative path
     * @param dir     the direction the diff is about to take ('U'pload or 'D'ownload)
     * @param manual  false for automatic sync passes; manual resync is never throttled
     * @return true when the file is currently cooled down and must be skipped
     */
    private static boolean together$isAutoFlip(String relPath, char dir, boolean manual) {
        if (manual) {
            autoCooldown.remove(relPath);
            autoFlips.remove(relPath);
            autoLastDir.put(relPath, dir);
            autoLastDirMs.put(relPath, System.currentTimeMillis());
            return false;
        }

        long now = System.currentTimeMillis();
        Long cooldown = autoCooldown.get(relPath);

        if (cooldown != null) {
            if (cooldown.longValue() > now) {
                return true;
            }
            autoCooldown.remove(relPath);
            autoFlips.remove(relPath);
        }

        Character last = autoLastDir.get(relPath);
        Long lastMs = autoLastDirMs.get(relPath);

        if (last != null && last.charValue() != dir && lastMs != null && now - lastMs.longValue() < AUTO_FLIP_WINDOW_MS) {
            int flips = autoFlips.merge(relPath, 1, Integer::sum);

            if (flips >= AUTO_FLIP_LIMIT) {
                autoCooldown.put(relPath, now + AUTO_COOLDOWN_MS);
                autoFlips.remove(relPath);
                System.out.println("[BBSTogether] Auto sync of \"" + relPath + "\" paused for 5 minutes — it keeps"
                    + " flip-flopping between upload and download (something is rewriting this file without you editing it)");
                return true;
            }
        } else if (last == null || last.charValue() == dir) {
            autoFlips.remove(relPath);
        }

        autoLastDir.put(relPath, dir);
        autoLastDirMs.put(relPath, now);

        return false;
    }

    /* ---- A6: download retry bookkeeping ----------------------------------------------- */

    private static final Map<String, Integer> downloadRetries = new ConcurrentHashMap();
    /** Transfers dropped for exceeding the local size ceiling; later batches are ignored. */
    private static final java.util.Set<String> ignoredTransfers = ConcurrentHashMap.newKeySet();

    /* ---- A3: cursor send throttle ------------------------------------------------------ */

    private static final long CURSOR_SEND_INTERVAL_MS = 50L;
    private static volatile long lastCursorSentMs = 0L;
    /** Latest cursor update skipped by the throttle; flushed on the next render tick. */
    private static volatile Object[] pendingCursor = null;

    /* ---- B3: film inventory pull debounce ---------------------------------------------- */

    private static volatile long lastFilmInventorySent = 0L;

    /* ---- Auto sync: react to server/local asset changes without manual resync ---------- */

    /** Guards one auto-triggered manifest diff at a time (cleared when a manifest arrives). */
    private static final AtomicBoolean autoSyncInFlight = new AtomicBoolean(false);
    /** Collapses pending auto-sync re-checks into a single scheduled one. */
    private static final AtomicBoolean autoSyncScheduled = new AtomicBoolean(false);
    private static volatile long lastAutoSyncMs = 0L;
    private static final ScheduledExecutorService AUTO_SYNC_SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "BBSTogether-AutoSync");
        t.setDaemon(true);
        return t;
    });

    public static void setup() {
        ClientPlayNetworking.registerGlobalReceiver((Identifier)TogetherServerNetwork.CLIENT_PLAYER_COLORS, (client, handler, buf, resp) -> TogetherClientNetwork.handlePlayerColorsPacket(client, buf));
        ClientPlayNetworking.registerGlobalReceiver((Identifier)TogetherServerNetwork.CLIENT_CURSOR_POSITIONS, (client, handler, buf, resp) -> TogetherClientNetwork.handleCursorPositionsPacket(client, buf));
        ClientPlayNetworking.registerGlobalReceiver((Identifier)TogetherServerNetwork.CLIENT_FILM_SELECTIONS, (client, handler, buf, resp) -> TogetherClientNetwork.handleFilmSelectionsPacket(client, buf));
        ClientPlayNetworking.registerGlobalReceiver((Identifier)TogetherServerNetwork.CLIENT_REPLAY_SELECTIONS, (client, handler, buf, resp) -> TogetherClientNetwork.handleReplaySelectionsPacket(client, buf));
        ClientPlayNetworking.registerGlobalReceiver((Identifier)TogetherServerNetwork.CLIENT_REPLAY_FORM_LOCKS, (client, handler, buf, resp) -> TogetherClientNetwork.handleReplayFormLocksPacket(client, buf));
        ClientPlayNetworking.registerGlobalReceiver((Identifier)TogetherServerNetwork.CLIENT_REPLAY_SWAP, (client, handler, buf, resp) -> TogetherClientNetwork.handleReplaySwapPacket(client, buf));
        ClientPlayNetworking.registerGlobalReceiver((Identifier)TogetherServerNetwork.CLIENT_TOGGLE_RECORDING, (client, handler, buf, resp) -> TogetherClientNetwork.handleToggleRecordingPacket(client, buf));
        ClientPlayNetworking.registerGlobalReceiver((Identifier)TogetherServerNetwork.CLIENT_ASSET_MANIFEST, (client, handler, buf, resp) -> TogetherClientNetwork.handleAssetManifestPacket(client, buf));
        ClientPlayNetworking.registerGlobalReceiver((Identifier)TogetherServerNetwork.CLIENT_ASSETS_CHANGED, (client, handler, buf, resp) -> TogetherClientNetwork.handleAssetsChangedPacket(client, buf));
        ClientPlayNetworking.registerGlobalReceiver((Identifier)TogetherServerNetwork.CLIENT_ASSET_FILE, (client, handler, buf, resp) -> TogetherClientNetwork.handleAssetFilePacket(client, buf));
        ClientPlayNetworking.registerGlobalReceiver((Identifier)TogetherServerNetwork.CLIENT_ASSET_HINT, (client, handler, buf, resp) -> TogetherClientNetwork.handleAssetHintPacket(client, buf));
        ClientPlayNetworking.registerGlobalReceiver((Identifier)TogetherServerNetwork.CLIENT_FILM_SCREENS, (client, handler, buf, resp) -> TogetherClientNetwork.handleFilmScreensPacket(client, buf));
        ClientPlayNetworking.registerGlobalReceiver((Identifier)TogetherServerNetwork.CLIENT_FILM_BRANCH_STATE, (client, handler, buf, resp) -> TogetherClientNetwork.handleFilmBranchStatePacket(client, buf));
        ClientPlayNetworking.registerGlobalReceiver((Identifier)TogetherServerNetwork.CLIENT_FILM_SAVED_SYNC, (client, handler, buf, resp) -> TogetherClientNetwork.handleFilmSavedSyncPacket(client, buf));
        ClientPlayNetworking.registerGlobalReceiver((Identifier)TogetherServerNetwork.CLIENT_FILM_DATA_SYNC, (client, handler, buf, resp) -> TogetherClientNetwork.crusher.receive(buf, (bytes, packetByteBuf) -> TogetherClientNetwork.handleFilmDataSyncPacket(client, (byte[])bytes, packetByteBuf)));
        ClientPlayNetworking.registerGlobalReceiver((Identifier)TogetherServerNetwork.CLIENT_UNDO_REJECTED, (client, handler, buf, resp) -> TogetherClientNetwork.crusher.receive(buf, (bytes, packetByteBuf) -> TogetherClientNetwork.handleUndoRejectedPacket(client, (byte[])bytes)));
        ClientPlayNetworking.registerGlobalReceiver((Identifier)TogetherServerNetwork.CLIENT_FILM_LIST_CHANGED, (client, handler, buf, resp) -> TogetherClientNetwork.handleFilmListChangedPacket(client));
        ClientPlayNetworking.registerGlobalReceiver((Identifier)TogetherServerNetwork.CLIENT_FILM_DELETED, (client, handler, buf, resp) -> TogetherClientNetwork.handleFilmDeletedPacket(client, buf.readString()));
        ClientPlayNetworking.registerGlobalReceiver((Identifier)TogetherServerNetwork.CLIENT_FILM_RENAMED, (client, handler, buf, resp) -> TogetherClientNetwork.handleFilmRenamedPacket(client, buf.readString(), buf.readString()));
        ClientPlayNetworking.registerGlobalReceiver((Identifier)TogetherServerNetwork.CLIENT_MERGE_REPLAYS, (client, handler, buf, resp) -> TogetherClientNetwork.handleMergeReplaysPacket(client, buf));
        ClientPlayNetworking.registerGlobalReceiver((Identifier)TogetherServerNetwork.CLIENT_BEHAVIOR_LIST, (client, handler, buf, resp) -> TogetherClientNetwork.handleBehaviorListPacket(client, buf));
        ClientPlayNetworking.registerGlobalReceiver((Identifier)TogetherServerNetwork.CLIENT_PERMISSIONS, (client, handler, buf, resp) -> TogetherClientNetwork.handlePermissionsPacket(client, buf));
        ClientPlayNetworking.registerGlobalReceiver((Identifier)TogetherServerNetwork.CLIENT_MODEL_DELETED, (client, handler, buf, resp) -> TogetherClientNetwork.handleModelDeletedPacket(client, buf.readString()));
        ClientPlayNetworking.registerGlobalReceiver((Identifier)TogetherServerNetwork.CLIENT_BBSOP_SYNC, (client, handler, buf, resp) -> TogetherClientNetwork.handleBbsopSyncPacket(client, buf));
        ClientPlayNetworking.registerGlobalReceiver((Identifier)TogetherServerNetwork.CLIENT_COOP_REQUEST, (client, handler, buf, resp) -> CoopClient.handleRequest(client, buf));
        ClientPlayNetworking.registerGlobalReceiver((Identifier)TogetherServerNetwork.CLIENT_COOP_REQUEST_CLOSED, (client, handler, buf, resp) -> CoopClient.handleRequestClosed(client, buf));
        ClientPlayNetworking.registerGlobalReceiver((Identifier)TogetherServerNetwork.CLIENT_COOP_ROOM, (client, handler, buf, resp) -> CoopClient.handleRoom(client, buf));
        ClientPlayNetworking.registerGlobalReceiver((Identifier)TogetherServerNetwork.CLIENT_COOP_END, (client, handler, buf, resp) -> CoopClient.handleEnd(client, buf));
        ClientPlayNetworking.registerGlobalReceiver((Identifier)TogetherServerNetwork.CLIENT_COOP_MOUSE, (client, handler, buf, resp) -> CoopClient.handleMouse(client, buf));
        ClientPlayNetworking.registerGlobalReceiver((Identifier)TogetherServerNetwork.CLIENT_COOP_BEGIN, (client, handler, buf, resp) -> CoopClient.handleBegin(client, buf));
        ClientPlayNetworking.registerGlobalReceiver((Identifier)TogetherServerNetwork.CLIENT_COMMIT_INDEX, (client, handler, buf, resp) -> TogetherClientNetwork.handleCommitIndexPacket(client, buf));
        ClientPlayNetworking.registerGlobalReceiver((Identifier)TogetherServerNetwork.CLIENT_COMMITS, (client, handler, buf, resp) -> TogetherClientNetwork.handleCommitsPacket(client, buf));
        ClientPlayNetworking.registerGlobalReceiver((Identifier)TogetherServerNetwork.CLIENT_COMMIT_ADDED, (client, handler, buf, resp) -> TogetherClientNetwork.handleCommitAddedPacket(client, buf));
        ClientPlayNetworking.registerGlobalReceiver((Identifier)TogetherServerNetwork.CLIENT_COMMIT_DIFF, (client, handler, buf, resp) -> TogetherClientNetwork.handleCommitDiffPacket(client, buf));
        ClientPlayNetworking.registerGlobalReceiver((Identifier)TogetherServerNetwork.CLIENT_COMMIT_HEAD, (client, handler, buf, resp) -> TogetherClientNetwork.handleCommitHeadPacket(client, buf));
        ClientPlayNetworking.registerGlobalReceiver((Identifier)TogetherServerNetwork.CLIENT_COMMIT_RENAMED, (client, handler, buf, resp) -> TogetherClientNetwork.handleCommitRenamedPacket(client, buf));
        ClientPlayNetworking.registerGlobalReceiver((Identifier)TogetherServerNetwork.CLIENT_COMMIT_SELECTIONS, (client, handler, buf, resp) -> TogetherClientNetwork.handleCommitSelectionsPacket(client, buf));
        ClientPlayNetworking.registerGlobalReceiver((Identifier)TogetherServerNetwork.CLIENT_PLAYER_COMMIT_POSITIONS, (client, handler, buf, resp) -> TogetherClientNetwork.handlePlayerCommitPositionsPacket(client, buf));
        ClientPlayNetworking.registerGlobalReceiver((Identifier)TogetherServerNetwork.CLIENT_COMMIT_COLOR, (client, handler, buf, resp) -> TogetherClientNetwork.handleCommitColorPacket(client, buf));
        ClientPlayNetworking.registerGlobalReceiver((Identifier)TogetherServerNetwork.CLIENT_COMMIT_REJECTED, (client, handler, buf, resp) -> TogetherClientNetwork.handleCommitRejectedPacket(client, buf));
        isBBSTogetherOnServer = true;
        BBSTogetherFix28Client.registerReceivers();
        BBSTogetherFixReplayStructure.registerClientReceiver();
    }

    public static void reset() {
        crusher.reset();
        AssetSyncTracker.reset();
        playerColors.clear();
        playerCursors.clear();
        filmSelections.clear();
        filmBranches.clear();
        commitSelections.clear();
        playerCommitPositions.clear();
        pendingBranchCheckouts.clear();
        replaySelections.clear();
        replayFormLocks.clear();
        playersInFilmScreen.clear();
        damageControlActive = false;
        assetDownloadErrors = 0;
        assetDownloadDone = 0;
        assetDownloadTotal = 0;
        assetUploadErrors = 0;
        assetUploadDone = 0;
        assetUploadTotal = 0;
        assetUploadFinishedAt = 0L;
        assetDownloadFinishedAt = 0L;
        tooLargeModels.clear();
        localOnlyModels.clear();
        mchorse.bbs_together.sync.OversizedAssets.clear();
        pendingSwapFilmId = null;
        pendingSwapFrom = -1;
        pendingSwapTo = -1;
        isOperator = false;
        isManager = false;
        behaviorEntries.clear();
        behaviorPlayers.clear();
        behaviorVersion = 0;
        behaviorDataStamp = 0;
        downloadRetries.clear();
        PENDING_DOWNLOADS.clear();
        ignoredTransfers.clear();
        pendingCommitColors.clear();
        pendingCommitColorAt = 0L;
        migratedCommitColors.clear();
        synchronized (lastReportedSha1) {
            lastReportedSha1.clear();
        }
        inventoryDirty.set(false);
        TogetherClientNetwork.closeAllSinks();
        TogetherClientNetwork.saveHashCache();
        mchorse.bbs_together.sync.SelfWriteGuard.reset();
        uploadBudgetKbps = -1;
        UPLOAD_BUDGET.reset();
        pendingCursor = null;
        autoSyncInFlight.set(false);
        autoSyncScheduled.set(false);
        lastAutoSyncMs = 0L;
        mchorse.bbs_together.bbsop.BbsOpClientState.reset();
        CoopClient.reset();
        isBBSTogetherOnServer = false;
        BBSTogetherFix28Client.resetState();
        BBSTogetherFixReplayStructure.resetClient();
    }

    public static void onJoin() {
        isBBSTogetherOnServer = true;
        /* Send the local player's color once on join (it used to be re-sent every client tick
         * by a mixin that targeted the screen-tick helper instead of joinWorld). */
        MinecraftClient joinClient = MinecraftClient.getInstance();
        if (joinClient != null) {
            joinClient.execute(() -> {
                if (joinClient.player != null) {
                    TogetherClientNetwork.sendPlayerColor((Integer) BBSSettings.primaryColor.get());
                }
            });
        }
        synchronized (lastReportedSha1) {
            /* New session, new server-side map: the first report has to be complete. */
            lastReportedSha1.clear();
        }
        TogetherClientNetwork.closeAllSinks();
        inventoryDirty.set(false);
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.isIntegratedServerRunning()) {
            /* Single player: client and server share the same process (and film/assets
             * folders), so there is nothing to pull or report. */
            return;
        }
        client.execute(() -> {
            TogetherClientNetwork.sendSyncPref();
            TogetherClientNetwork.sendManifestVersionQuery();
            TogetherClientNetwork.sendFilmInventory();
        });
        TogetherClientNetwork.purgeStaleTransferTemps();
        /* Auto sync: watch the local assets folder so local edits upload by themselves. */
        AssetAutoSyncWatcher.ensureStarted();
    }

    public static boolean isConnected() {
        return isBBSTogetherOnServer;
    }

    /** Branch this client currently holds for a film, or empty when unknown yet. */
    public static String filmBranch(String filmId) {
        String branch = filmId == null ? null : filmBranches.get(filmId);
        return branch == null ? "" : branch;
    }

    private static void setFilmBranch(String filmId, String branch) {
        if (filmId == null || filmId.isEmpty()) {
            return;
        }
        String previous;
        if (branch == null || branch.isEmpty()) {
            previous = filmBranches.remove(filmId);
        } else {
            previous = filmBranches.put(filmId, branch);
            /* Keep the commit graph's current branch in sync even if the following
             * c_commits packet is delayed or lost: commits must target this branch. */
            FilmCommitClient.setCurrentBranch(filmId, branch);
        }
        /* Replay indices and form locks are branch-specific; keep the previous branch's
         * markers out of the newly loaded editor. */
        if (previous != null && !previous.equals(branch)) {
            replaySelections.remove(filmId);
            replayFormLocks.remove(filmId);
        }
    }

    /** Branch a checkout is waiting for, or null when none is pending (or it expired). */
    private static String pendingCheckoutBranch(String filmId) {
        String pending = filmId == null ? null : pendingBranchCheckouts.get(filmId);
        if (pending == null) {
            return null;
        }
        int tab = pending.indexOf('\t');
        long at;
        try {
            at = Long.parseLong(tab < 0 ? pending : pending.substring(tab + 1));
        } catch (NumberFormatException e) {
            pendingBranchCheckouts.remove(filmId);
            return null;
        }
        if (System.currentTimeMillis() - at > BRANCH_CHECKOUT_TIMEOUT_MS) {
            pendingBranchCheckouts.remove(filmId);
            return null;
        }
        return tab < 0 ? pending : pending.substring(0, tab);
    }

    public static void interpolateCursors(float delta) {
        for (CursorPosition pos : playerCursors.values()) {
            pos.interpolate(delta);
        }
    }

    private static void handlePlayerColorsPacket(MinecraftClient client, PacketByteBuf buf) {
        int count = buf.readInt();
        HashMap<UUID, Integer> colors = new HashMap<UUID, Integer>();
        for (int i = 0; i < count; ++i) {
            colors.put(buf.readUuid(), buf.readInt());
        }
        client.execute(() -> {
            playerColors.clear();
            playerColors.putAll(colors);
        });
    }

    private static void handleCursorPositionsPacket(MinecraftClient client, PacketByteBuf buf) {
        boolean full = buf.readBoolean();
        int count = buf.readInt();
        HashMap<UUID, CursorPosition> incoming = new HashMap<UUID, CursorPosition>();
        for (int i = 0; i < count; ++i) {
            UUID uuid = buf.readUuid();
            int editorType = buf.readInt();
            float tick = buf.readFloat();
            float row = buf.readFloat();
            int selCount = buf.readInt();
            ArrayList<Integer> sel = new ArrayList<Integer>(selCount);
            for (int s = 0; s < selCount; ++s) {
                sel.add(buf.readInt());
            }
            CursorPosition cp = new CursorPosition(editorType, tick, row, sel);
            if (buf.isReadable(4)) {
                cp.playheadTick = buf.readInt();
            }
            incoming.put(uuid, cp);
        }
        client.execute(() -> {
            for (Map.Entry entry : incoming.entrySet()) {
                CursorPosition n = (CursorPosition)entry.getValue();
                if (n.editorType < 0) {
                    /* Delta removal: the player left the film or disconnected. */
                    playerCursors.remove(entry.getKey());
                    continue;
                }
                CursorPosition existing = playerCursors.get(entry.getKey());
                if (existing != null) {
                    existing.updateTarget(n.editorType, n.targetTick, n.targetRow, n.selectedIndices, n.playheadTick);
                    continue;
                }
                playerCursors.put((UUID)entry.getKey(), (CursorPosition)entry.getValue());
            }
            /* Only full snapshots replace the whole set; deltas must not clobber
             * cursors they didn't carry. */
            if (full) {
                playerCursors.keySet().retainAll(incoming.keySet());
            }
        });
    }

    private static void handleFilmSelectionsPacket(MinecraftClient client, PacketByteBuf buf) {
        int count = buf.readInt();
        HashMap<UUID, String> selections = new HashMap<UUID, String>();
        for (int i = 0; i < count; ++i) {
            selections.put(buf.readUuid(), buf.readString());
        }
        client.execute(() -> {
            filmSelections.clear();
            filmSelections.putAll(selections);
        });
    }

    private static void handleReplaySelectionsPacket(MinecraftClient client, PacketByteBuf buf) {
        boolean lockingEnabled = buf.readBoolean();
        String filmId = buf.readString();
        int count = buf.readInt();
        LinkedHashMap<Integer, List<UUID>> map = new LinkedHashMap<Integer, List<UUID>>();
        for (int i = 0; i < count; ++i) {
            UUID uuid = buf.readUuid();
            int replayIndex = buf.readInt();
            map.computeIfAbsent(replayIndex, k -> new ArrayList<UUID>()).add(uuid);
        }
        int colorCount = buf.readInt();
        HashMap<UUID, Integer> colors = new HashMap<UUID, Integer>();
        for (int i = 0; i < colorCount; ++i) {
            colors.put(buf.readUuid(), buf.readInt());
        }
        client.execute(() -> {
            if (!filmId.isEmpty()) {
                Map<Integer, List<UUID>> existing = replaySelections.get(filmId);
                if (existing == null) {
                    replaySelections.put(filmId, copyReplaySelections(map));
                } else {
                    mergeReplaySelections(existing, map);
                }
            }
            playerColors.clear();
            playerColors.putAll(colors);
        });
    }

    private static Map<Integer, List<UUID>> copyReplaySelections(Map<Integer, List<UUID>> map) {
        HashMap<Integer, List<UUID>> hashMap = new HashMap<Integer, List<UUID>>();
        for (Map.Entry<Integer, List<UUID>> entry : map.entrySet()) {
            hashMap.put(entry.getKey(), new ArrayList<UUID>(entry.getValue()));
        }
        return hashMap;
    }

    private static void mergeReplaySelections(Map<Integer, List<UUID>> map, Map<Integer, List<UUID>> map2) {
        HashSet<UUID> hashSet = new HashSet<UUID>();
        for (List<UUID> object : map2.values()) {
            hashSet.addAll(object);
        }
        if (!hashSet.isEmpty()) {
            for (List<UUID> list : map.values()) {
                list.removeIf(hashSet::contains);
            }
            map.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        }
        for (Map.Entry<Integer, List<UUID>> entry2 : map2.entrySet()) {
            List<UUID> list = map.computeIfAbsent(entry2.getKey(), n -> new ArrayList<UUID>());
            for (UUID uUID : entry2.getValue()) {
                if (list.contains(uUID)) continue;
                list.add(uUID);
            }
        }
    }

    private static void handleReplayFormLocksPacket(MinecraftClient client, PacketByteBuf buf) {
        String filmId = buf.readString();
        int count = buf.readInt();
        HashMap<Integer, UUID> locks = new HashMap<Integer, UUID>();
        for (int i = 0; i < count; ++i) {
            UUID u = buf.readUuid();
            int idx = buf.readInt();
            locks.putIfAbsent(idx, u);
        }
        client.execute(() -> {
            if (locks.isEmpty()) {
                replayFormLocks.remove(filmId);
            } else {
                replayFormLocks.put(filmId, locks);
            }
        });
    }

    private static void handleReplaySwapPacket(MinecraftClient client, PacketByteBuf buf) {
        String filmId = buf.readString();
        int from = buf.readInt();
        int to = buf.readInt();
        client.execute(() -> {
            pendingSwapFilmId = filmId;
            pendingSwapFrom = from;
            pendingSwapTo = to;
        });
    }

    private static void handleFilmScreensPacket(MinecraftClient client, PacketByteBuf buf) {
        int count = buf.readInt();
        HashMap<UUID, String> screens = new HashMap<UUID, String>();
        for (int i = 0; i < count; ++i) {
            screens.put(buf.readUuid(), buf.readString());
        }
        client.execute(() -> {
            playersInFilmScreen.clear();
            playersInFilmScreen.putAll(screens);
            damageControlActive = !screens.isEmpty();
        });
    }

    private static void handleToggleRecordingPacket(MinecraftClient client, PacketByteBuf buf) {
        boolean start = buf.readBoolean();
        int replayIndex = buf.readInt();
        client.execute(() -> lambda$handleToggleRecordingPacket$24(start, replayIndex));
    }

    private static void lambda$handleToggleRecordingPacket$24(boolean start, int replayIndex) {
        Films films = BBSModClient.getFilms();
        Recorder recorder = films.getRecorder();
        UIDashboard dashboard = getDashboardIfExists();
        UIFilmPanel panel = dashboard == null ? null : (UIFilmPanel)dashboard.getPanel(UIFilmPanel.class);
        String filmId = panel != null && panel.getData() != null ? ((Film)panel.getData()).getId() : lastLocalFilmId;
        if (filmId == null || filmId.isEmpty()) {
            return;
        }
        if (!start) {
            if (recorder == null || recorder.hasNotStarted()) {
                return;
            }
            recorder = BBSModClient.getFilms().stopRecording();
            if (recorder == null || recorder.hasNotStarted()) {
                return;
            }
            if (panel == null || panel.getData() == null) {
                return;
            }
            Film film = (Film)panel.getData();
            panel.applyRecordedKeyframes(recorder, film);
            if (panel.getData() != null && ((Film)panel.getData()).getId().equals(filmId)) {
                together_updateReplayList(panel);
                panel.getController().createEntities();
            }
            return;
        }
        if (recorder != null) {
            return;
        }
        int idx = replayIndex >= 0 ? replayIndex : lastLocalReplayIdx;
        Film film = together_getOpenOrStoredFilm(panel, filmId);
        if (film == null || idx < 0 || idx >= film.replays.getList().size()) {
            return;
        }
        BBSTogetherFix28Client.sendRecordingBaselineSnapshot(filmId, film);
        films.startRecording(film, idx, 0);
        recordingSessionActive = true;
    }
    private static void handleMergeReplaysPacket(MinecraftClient client, PacketByteBuf buf) {
        crusher.receive(buf, (bytes, packetByteBuf) -> {
            String filmId = packetByteBuf.readString();
            BaseType batch = DataStorageUtils.readFromBytes((byte[])bytes);
            if (!batch.isMap()) {
                return;
            }
            MapType batchMap = batch.asMap();
            client.execute(() -> lambda$handleMergeReplaysPacket$25(filmId, batchMap));
        });
    }

    private static void lambda$handleMergeReplaysPacket$25(String filmId, MapType batchMap) {
        UIDashboard dashboard = getDashboardIfOpen();
        UIFilmPanel panel = dashboard == null ? null : (UIFilmPanel)dashboard.getPanel(UIFilmPanel.class);
        Film film = together_getOpenOrStoredFilm(panel, filmId);
        if (film == null) {
            return;
        }
        List<Replay> replays = film.replays.getList();
        for (String key : batchMap.keys()) {
            try {
                int idx = Integer.parseInt(key);
                BaseType rd = batchMap.get(key);
                if (idx < 0 || idx >= replays.size() || rd == null || !rd.isMap()) continue;
                BBSTogetherFix22.beginRemoteSyncApply();
                try {
                    ((Replay)replays.get(idx)).fromData(rd.asMap());
                }
                finally {
                    BBSTogetherFix22.endRemoteSyncApply();
                }
            }
            catch (NumberFormatException numberFormatException) {}
        }
        sendMasterRecordingDone(filmId, film);
        if (panel != null && panel.getData() != null && ((Film)panel.getData()).getId().equals(filmId)) {
            panel.fillData();
            together_updateReplayList(panel);
            panel.getController().createEntities();
            Replay r = panel.replayEditor.getReplay();
            int idx = BBSTogetherFix45.identityReplayIndex(((Film)panel.getData()).replays.getList(), r);
            if (idx < 0 && !((Film)panel.getData()).replays.getList().isEmpty()) {
                panel.replayEditor.setReplay((Replay)((Film)panel.getData()).replays.getList().get(0));
                idx = 0;
            }
            sendReplaySelection(filmId, idx);
        }
    }
    private static void handleAssetManifestPacket(MinecraftClient client, PacketByteBuf buf) {
        crusher.receive(buf, (bytes, packetByteBuf) -> {
            /* An auto-triggered diff is settled once its answer lands (also settles on
             * manual resync answers —those are rarer and never overlap for long). */
            autoSyncInFlight.set(false);
            boolean dl = (Boolean)TogetherSettings.assetSyncDownload.get();
            boolean ul = ((Boolean)TogetherSettings.assetSyncUpload.get()).booleanValue();
            if (resyncMode == 1) { dl = true; ul = false; }
            else if (resyncMode == 2) { dl = false; ul = true; }
            final boolean manual = resyncMode != 0;
            resyncMode = 0;
            if (!dl && !ul) {
                return;
            }
            final boolean doDownload = dl;
            final boolean doUpload = ul;
            /* B4: the server answers "unchanged" with an empty payload when our reported
             * manifest fingerprint still matches; reuse the cached copy in that case. */
            boolean unchanged = packetByteBuf.readableBytes() >= 9 && packetByteBuf.readBoolean();
            long manifestVersion = packetByteBuf.readableBytes() >= 8 ? packetByteBuf.readLong() : 0L;
            BaseType data = bytes == null ? (BaseType)new MapType() : DataStorageUtils.readFromBytes((byte[])bytes);
            if (!(data instanceof MapType)) {
                return;
            }
            MapType manifest = (MapType)data;
            boolean reuseCache = unchanged && !lastServerSha1Map.isEmpty();
            downloadRetries.clear();
            ignoredTransfers.clear();
            Thread thread = new Thread(() -> {
                try {
                    File assetsFolder = BBSMod.getAssetsFolder();
                    Path assetsPath = assetsFolder.toPath();
                    HashMap<String, String> serverSha1Map = new HashMap<String, String>();
                    HashMap<String, Long> serverSizeMap = new HashMap<String, Long>();
                    HashMap<String, Long> serverMtimeMap = new HashMap<String, Long>();
                    if (reuseCache) {
                        synchronized (lastServerSha1Map) {
                            serverSha1Map.putAll(lastServerSha1Map);
                            serverSizeMap.putAll(lastServerSizeMap);
                            serverMtimeMap.putAll(lastServerMtimeMap);
                        }
                    } else {
                        for (String key : manifest.keys()) {
                            /* Entries are "<sha1>:<size>:<mtime>" (mtime optional, old servers). */
                            String raw = manifest.get(key).asString();
                            String[] parts = raw.split(":", 3);
                            if (parts.length > 0 && !parts[0].isEmpty()) {
                                serverSha1Map.put(key, parts[0]);
                            }
                            if (parts.length > 1) {
                                try { serverSizeMap.put(key, Long.parseLong(parts[1])); }
                                catch (NumberFormatException ignored2) {}
                            }
                            if (parts.length > 2) {
                                try { serverMtimeMap.put(key, Long.parseLong(parts[2])); }
                                catch (NumberFormatException ignored2) {}
                            }
                        }
                        synchronized (lastServerSha1Map) {
                            lastServerSha1Map.clear();
                            lastServerSha1Map.putAll(serverSha1Map);
                            lastServerSizeMap.clear();
                            lastServerSizeMap.putAll(serverSizeMap);
                            lastServerMtimeMap.clear();
                            lastServerMtimeMap.putAll(serverMtimeMap);
                        }
                        lastManifestVersion = manifestVersion;
                    }
                    long maxBytes = TogetherClientNetwork.clientMaxAssetBytes();
                    TogetherClientNetwork.tooLargeModels.clear();
                    /* Over-size assets are advertised but never transferred (a 700 MB video would
                     * hold the link for minutes and get us dropped). Remembering them lets every
                     * picker, grid and list paint the entry as "too large" instead of showing a
                     * hole where a file should be —and the stubs below keep the name present. */
                    mchorse.bbs_together.sync.OversizedAssets.clear();
                    for (Map.Entry<String, Long> oversized : serverSizeMap.entrySet()) {
                        Long oversizedSize = oversized.getValue();
                        if (oversizedSize != null && oversizedSize.longValue() > maxBytes) {
                            mchorse.bbs_together.sync.OversizedAssets.mark(oversized.getKey(), oversizedSize.longValue());
                        }
                    }
                    /* Models present locally but absent from the server manifest were never uploaded.
                       Only compute this when a non-empty manifest was received, or an un-synced server
                       would wrongly mark every local model as local-only. */
                    localOnlyModels.clear();
                    if (!serverSha1Map.isEmpty())
                    {
                        java.util.Set<String> serverModelIds = new java.util.HashSet<>();
                        for (String key : serverSha1Map.keySet())
                        {
                            String mid = together$extractModelId(key);
                            if (mid != null)
                            {
                                serverModelIds.add(mid);
                            }
                        }
                        File modelsDir = new File(assetsFolder, "models");
                        File[] children = modelsDir.listFiles();
                        if (children != null)
                        {
                            for (File child : children)
                            {
                                if (child.isDirectory() && !serverModelIds.contains(child.getName()))
                                {
                                    localOnlyModels.add(child.getName());
                                }
                            }
                        }
                    }
                    /* Per-model size aggregation is only used to *label* big models for the
                     * "models too large" list. It must never gate the download: skipping a
                     * whole model's files but keeping its config.json left folders with a model
                     * entry and no texture/geometry at all (the "只同步了模型没有材质" report).
                     * Over-size protection happens per file, below. */
                    HashMap<String, Long> modelTotalSize = new HashMap<String, Long>();
                    for (Map.Entry<String, Long> e : serverSizeMap.entrySet()) {
                        String modelId = together$extractModelId(e.getKey());
                        if (modelId != null) modelTotalSize.merge(modelId, e.getValue(), Long::sum);
                    }
                    for (Map.Entry<String, Long> e : modelTotalSize.entrySet()) {
                        if (e.getValue() > maxBytes) {
                            TogetherClientNetwork.tooLargeModels.add(e.getKey());
                        }
                    }
                    AssetSyncTracker.beginScan();
                    boolean hadTasks = false;
                    if (doDownload) {
                        ArrayList<String> needed = new ArrayList<String>();
                        for (Map.Entry entry : serverSha1Map.entrySet()) {
                            String relPath = (String) entry.getKey();
                            Long fileSize = serverSizeMap.get(relPath);
                            if (fileSize != null && fileSize > maxBytes) {
                                /* Too large to ever arrive: write the stub so BBS can still list
                                 * the entry (and the UI can label it), instead of leaving a hole.
                                 * Models are the exception: a model folder is discovered through
                                 * its config.json, so a stub for any *other* model file would
                                 * resurrect a half-model (mesh without textures) —the exact
                                 * bug behind "模型同步了但没有材质". For models only config.json
                                 * is stubbed, and the rest is left to the "模型过大" cell. */
                                if (relPath.startsWith("models/") && !relPath.endsWith("/config.json")) {
                                    continue;
                                }
                                TogetherClientNetwork.ensureOversizedPlaceholder(assetsPath, relPath);
                                continue;
                            }
                            File localFile = new File(assetsFolder, relPath.replace("/", File.separator));
                            boolean needsDownload = true;
                            if (localFile.exists() && localFile.isFile()) {
                                needsDownload = !((String)entry.getValue()).equalsIgnoreCase(TogetherClientNetwork.sha1OfLocalFileCached(localFile.toPath()));
                                /* mtime arbitration: a strictly newer local copy must NOT be
                                 * clobbered by the server's stale one —skip it here; the upload
                                 * pass sends it up instead. One direction per file per diff pass,
                                 * otherwise download+upload race forever and never converge. */
                                if (needsDownload) {
                                    Long serverMtime = serverMtimeMap.get(relPath);
                                    if (serverMtime != null && serverMtime.longValue() > 0L) {
                                        try {
                                            long localMtime = Files.getLastModifiedTime(localFile.toPath()).toMillis();
                                            if (localMtime > serverMtime.longValue() + MTIME_TOLERANCE_MS) {
                                                needsDownload = false;
                                            }
                                        }
                                        catch (Exception ignored2) {}
                                    }
                                }
                            }
                            if (!needsDownload) continue;
                            /* A file we do not have at all cannot be flip-flopping: there is no
                             * local copy to rewrite. Skipping it for the cooldown is what leaves
                             * a model's textures behind while its .json arrives, so missing files
                             * always bypass the guard. */
                            boolean locallyMissing = !localFile.exists();
                            if (locallyMissing || !TogetherClientNetwork.together$isAutoFlip(relPath, 'D', manual)) {
                                needed.add(relPath);
                            }
                        }
                        if (!needed.isEmpty()) {
                            int downloadCount = needed.size();
                            hadTasks = true;
                            client.execute(() -> {
                                assetDownloadTotal = downloadCount;
                                assetDownloadDone = 0;
                                assetDownloadErrors = 0;
                                assetDownloadFinishedAt = 0L;
                                for (String n : needed) {
                                    AssetSyncTracker.pending(n, AssetSyncTracker.Dir.DOWNLOAD);
                                }
                                sendAssetRequestChunks(needed);
                            });
                        }
                    }
                    if (doUpload && assetsFolder.exists()) {
                        long upMaxBytes = clientMaxAssetBytes();
                        ArrayList<String> toUpload = new ArrayList<>();
                        Files.walk(assetsPath, new FileVisitOption[0]).filter(x$0 -> Files.isRegularFile(x$0, new LinkOption[0])).forEach(localPath -> {
                            try {
                                String relativePath = assetsPath.relativize((Path)localPath).toString().replace(File.separator, "/");
                                if (TogetherClientNetwork.isLocalTransferTemp(relativePath)) {
                                    return;
                                }
                                /* An over-size server asset was replaced locally by a stub (see
                                 * ensureOversizedPlaceholder). Pushing that stub back would
                                 * overwrite the real file with zero bytes, so skip it here; the
                                 * server keeps the only full copy and the client keeps the label. */
                                Long serverSize = (Long)serverSizeMap.get(relativePath);
                                if (serverSize != null && serverSize.longValue() > upMaxBytes) {
                                    return;
                                }
                                String serverSha1 = (String)serverSha1Map.get(relativePath);
                                String localSha1 = TogetherClientNetwork.sha1OfLocalFileCached(localPath);
                                if ((serverSha1 == null || !serverSha1.equalsIgnoreCase(localSha1))) {
                                    /* mtime arbitration (see the download pass): when the server
                                     * copy is newer than ours (or their edit times are within
                                     * tolerance), let the download fetch it instead of pushing a
                                     * stale local copy over it. */
                                    boolean upload = true;
                                    Long serverMtime = serverMtimeMap.get(relativePath);
                                    if (serverSha1 != null && serverMtime != null && serverMtime.longValue() > 0L) {
                                        long localMtime = Files.getLastModifiedTime(localPath).toMillis();
                                        if (localMtime <= serverMtime.longValue() + MTIME_TOLERANCE_MS) {
                                            upload = false;
                                        }
                                    }
                                    /* A brand new local file (server has no copy) can never be
                                     * flip-flopping either —always push it, otherwise freshly
                                     * authored models never reach the server at all. */
                                    boolean serverMissing = serverSha1 == null;
                                    if (upload && (serverMissing || !TogetherClientNetwork.together$isAutoFlip(relativePath, 'U', manual))) {
                                        toUpload.add(relativePath);
                                    }
                                }
                            }
                            catch (Exception e) {
                                e.printStackTrace();
                            }
                        });
                        if (!toUpload.isEmpty()) {
                            int uploadCount = toUpload.size();
                            System.out.println("[BBS Assets] " + (manual ? "Manual" : "Auto") + " upload: "
                                + uploadCount + " file(s) differ from or are missing on the server");
                            hadTasks = true;
                            client.execute(() -> {
                                assetUploadTotal = uploadCount;
                                assetUploadDone = 0;
                                assetUploadErrors = 0;
                                assetUploadFinishedAt = 0L;
                            });
                            new Thread(() -> {
                                /* A5: uploads stream out in slice batches so a multi-GB model
                                 * neither bursts the connection nor gets buffered whole. */
                                long upInterval = TogetherClientNetwork.uploadIntervalMs();
                                long upBatch = TogetherClientNetwork.uploadBatchBytes();
                                for (String relPath : toUpload) {
                                    if (!isBBSTogetherOnServer) break;
                                    try {
                                        File f = new File(assetsFolder, relPath.replace("/", File.separator));
                                        if (!f.exists() || !f.isFile() || f.length() == 0) continue;
                                        if (f.length() > upMaxBytes) continue;
                                        AssetSyncTracker.pending(relPath, AssetSyncTracker.Dir.UPLOAD);
                                        TogetherClientNetwork.sendAssetUploadSliced(f, relPath, upBatch, upInterval);
                                    }
                                    catch (Exception e) {
                                        ++assetUploadErrors;
                                        AssetSyncTracker.error(relPath);
                                    }
                                }
                                MinecraftClient.getInstance().execute(() -> TogetherClientNetwork.sendAssetInventory());
                            }, "BBSTogether-AssetUpload").start();
                        }
                    }
                    if (!hadTasks) {
                        AssetSyncTracker.abort();
                    }
                    /* B2: publish our local inventory either way, so the server can skip
                     * us for pushes of files we already own. */
                    MinecraftClient.getInstance().execute(() -> TogetherClientNetwork.sendAssetInventory());
                }
                catch (Exception e) {
                    e.printStackTrace();
                }
            }, "BBSTogether-AssetManifestCheck");
            thread.setDaemon(true);
            thread.start();
        });
    }

    /* ---- Auto sync: trigger machinery --------------------------------------------------- */

    private static boolean autoSyncEnabled() {
        try {
            return TogetherSettings.assetAutoSync != null && ((Boolean)TogetherSettings.assetAutoSync.get()).booleanValue();
        }
        catch (Exception e) {
            return false;
        }
    }

    private static long autoSyncCooldownMs() {
        try {
            return Math.max(5L, ((Number)TogetherSettings.assetAutoSyncCooldownSec.get()).longValue()) * 1000L;
        }
        catch (Exception e) {
            return 20000L;
        }
    }

    /** S2C: the server says its assets changed; re-diff so missing files download on their own. */
    private static void handleAssetsChangedPacket(MinecraftClient client, PacketByteBuf buf) {
        client.execute(() -> TogetherClientNetwork.triggerAutoSync("server assets changed"));
    }

    /** Called by the local assets watcher after edits went quiet. */
    public static void onLocalAssetsChanged() {
        if (!isBBSTogetherOnServer) {
            return;
        }
        /* Local changes can only ever result in an upload; don't wake the machinery
         * when uploading is turned off. */
        try {
            if (!((Boolean)TogetherSettings.assetSyncUpload.get()).booleanValue()) {
                return;
            }
        }
        catch (Exception e) {
            return;
        }
        triggerAutoSync("local assets changed");
    }

    /**
     * Re-diff the manifest without touching resyncMode (mode 0 follows the download/upload
     * settings, which is exactly the auto semantics). Rate limited by the cooldown setting;
     * triggers landing inside the cooldown are collapsed into one deferred re-check.
     */
    private static void triggerAutoSync(String reason) {
        if (!isBBSTogetherOnServer || !autoSyncEnabled()) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.isIntegratedServerRunning()) {
            return;
        }
        long now = System.currentTimeMillis();
        long since = now - lastAutoSyncMs;
        if (since < autoSyncCooldownMs()) {
            scheduleAutoSync(autoSyncCooldownMs() - since + 250L, reason);
            return;
        }
        if (!autoSyncInFlight.compareAndSet(false, true)) {
            return;
        }
        lastAutoSyncMs = now;
        System.out.println("[BBS Assets] Auto sync triggered (" + reason + ")");
        try {
            sendManifestVersionQuery();
        }
        catch (Exception e) {
            /* Disconnected (or left the play phase) between the trigger and the send. */
            autoSyncInFlight.set(false);
        }
    }

    private static void scheduleAutoSync(long delayMs, String reason) {
        if (!autoSyncScheduled.compareAndSet(false, true)) {
            return;
        }
        AUTO_SYNC_SCHEDULER.schedule(() -> {
            autoSyncScheduled.set(false);
            try {
                MinecraftClient.getInstance().execute(() -> TogetherClientNetwork.triggerAutoSync(reason));
            }
            catch (Exception ignored) {}
        }, Math.max(250L, delayMs), TimeUnit.MILLISECONDS);
    }

    private static void handleAssetFilePacket(MinecraftClient client, PacketByteBuf buf) {
        crusher.receive(buf, (bytes, packetByteBuf) -> {
            String relativePath = packetByteBuf.readString();
            boolean isPush = packetByteBuf.readBoolean();
            /* A5: each slice batch is a self-contained crusher transfer; the metadata says
             * which slice of the file it carries. */
            int seq = packetByteBuf.readableBytes() >= 4 ? packetByteBuf.readInt() : 0;
            int seqTotal = packetByteBuf.readableBytes() >= 4 ? packetByteBuf.readInt() : 1;
            long totalSize = packetByteBuf.readableBytes() >= 8 ? packetByteBuf.readLong() : (bytes == null ? 0L : (long)bytes.length);
            String sha1 = packetByteBuf.readableBytes() > 0 ? packetByteBuf.readString() : "";
            /* Optional mtime (absent with old servers): restored on disk so later manifest
             * diffs arbitrate by edit time instead of "when we downloaded it". */
            long fileMtime = packetByteBuf.readableBytes() >= 8 ? packetByteBuf.readLong() : 0L;
            if (relativePath.contains("..") || relativePath.startsWith("/")) {
                return;
            }
            if (isPush && !((Boolean)TogetherSettings.assetSyncReceive.get()).booleanValue()) {
                return;
            }
            if (!isPush && !((Boolean)TogetherSettings.assetSyncDownload.get()).booleanValue()) {
                return;
            }
            if (bytes == null) {
                return;
            }
            final String transferKey = relativePath + "#" + seqTotal + "#" + totalSize;
            if (ignoredTransfers.contains(transferKey)) {
                return;
            }
            if (totalSize > TogetherClientNetwork.clientMaxAssetBytes()) {
                ignoredTransfers.add(transferKey);
                String modelId = together$extractModelId(relativePath);
                if (modelId != null) TogetherClientNetwork.tooLargeModels.add(modelId);
                TogetherClientNetwork.forgetDownload(relativePath);
                AssetSyncTracker.done(relativePath);
                if (!isPush) {
                    TogetherClientNetwork.finishDownloadTask();
                }
                return;
            }
            /* One writer thread for the whole session: a 1 GB asset arrives as ~35k slices and
             * spawning a thread per slice (let alone a main-thread hop per slice) cost seconds
             * of pure scheduling —and stalled the game while it happened. */
            TogetherClientNetwork.ASSET_WRITER.execute(() -> {
                    try {
                        AssetSyncTracker.active(relativePath, isPush ? AssetSyncTracker.Dir.PUSH : AssetSyncTracker.Dir.DOWNLOAD);
                        File dest = new File(BBSMod.getAssetsFolder(), relativePath.replace("/", File.separator));
                        dest.getParentFile().mkdirs();
                        Path part = dest.toPath().resolveSibling(dest.getName() + ".bbspart");
                        /* Keep the temp file open for the duration of the transfer: opening,
                         * appending and closing per 30 KB slice was ~35k syscall triplets. */
                        FileChannel sink = TogetherClientNetwork.OPEN_SINKS.get(part.toString());
                        try {
                            if (seq == 0) {
                                if (sink != null) {
                                    sink.close();
                                }
                                sink = FileChannel.open(part, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                                TogetherClientNetwork.OPEN_SINKS.put(part.toString(), sink);
                            } else if (sink == null) {
                                sink = FileChannel.open(part, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                                TogetherClientNetwork.OPEN_SINKS.put(part.toString(), sink);
                            }
                            ByteBuffer bb = ByteBuffer.wrap(bytes);
                            while (bb.hasRemaining()) {
                                sink.write(bb);
                            }
                        }
                        catch (Exception e) {
                            TogetherClientNetwork.closeSink(part);
                            throw e;
                        }
                        if (seq < seqTotal - 1) {
                            TogetherClientNetwork.touchDownload(relativePath);
                            return;
                        }
                        TogetherClientNetwork.closeSink(part);
                        /* Final slice: verify the combined content, then promote atomically. */
                        if (!sha1.isEmpty() && !sha1OfLocalFile(part).equalsIgnoreCase(sha1)) {
                            throw new IOException("sha1 mismatch");
                        }
                        Files.move(part, dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        /* D1: our own write, not a local edit —keep the watcher quiet. */
                        mchorse.bbs_together.sync.SelfWriteGuard.record(dest.toPath());
                        if (fileMtime > 0L) {
                            try {
                                Files.setLastModifiedTime(dest.toPath(), java.nio.file.attribute.FileTime.fromMillis(fileMtime));
                            }
                            catch (Exception ignored2) {}
                        }
                        if (!sha1.isEmpty()) {
                            TogetherClientNetwork.cacheLocalSha1(dest.toPath(), sha1);
                        }
                        System.out.println("[BBS Assets] " + (isPush ? "Received push" : "Downloaded") + ": " + relativePath);
                        TogetherClientNetwork.forgetDownload(relativePath);
                        AssetSyncTracker.done(relativePath);
                        if (isPush) {
                            TogetherClientNetwork.sendAssetInventory();
                        } else {
                            TogetherClientNetwork.finishDownloadTask();
                        }
                    }
                    catch (Exception e) {
                        e.printStackTrace();
                        if (isPush) {
                            AssetSyncTracker.error(relativePath);
                            return;
                        }
                        /* A6: a failed/short/corrupted download is retried a couple of times
                         * before it counts as settled. */
                        int tries = downloadRetries.merge(relativePath, 1, Integer::sum);
                        if (tries <= 2) {
                            client.execute(() -> TogetherClientNetwork.sendAssetRequestChunks(Collections.singletonList(relativePath)));
                            return;
                        }
                        ++assetDownloadErrors;
                        TogetherClientNetwork.forgetDownload(relativePath);
                        AssetSyncTracker.error(relativePath);
                        TogetherClientNetwork.finishDownloadTask();
                    }
            });
        });
    }

    /**
     * Single daemon writer: slice order is preserved (they arrive in order on one connection).
     *
     * The queue is BOUNDED on purpose. The link can hand us slices faster than the disk can
     * absorb them; with an unbounded queue that difference turns into gigabytes of pending
     * byte[] and a VM that spends its life in GC —the client stops answering keepalives and
     * the server times it out ("连接超时"). CallerRunsPolicy instead lets the network thread
     * perform the write, which slows down reads and pushes back on the peer via TCP.
     */
    private static final int ASSET_WRITER_QUEUE = 32;
    private static final ExecutorService ASSET_WRITER = new java.util.concurrent.ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        new java.util.concurrent.ArrayBlockingQueue<>(ASSET_WRITER_QUEUE),
        r -> {
            Thread thread = new Thread(r, "BBSTogether-AssetWrite");
            thread.setDaemon(true);
            return thread;
        },
        new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());

    /** Open {@code .bbspart} sinks keyed by absolute path, so a transfer costs one open. */
    private static final java.util.concurrent.ConcurrentHashMap<String, FileChannel> OPEN_SINKS = new java.util.concurrent.ConcurrentHashMap<>();

    /* ---- Download completeness watchdog ------------------------------------
     * A requested file is only written once its last slice arrives. If a slice is
     * lost (link hiccup, client stall, a peer's upload replacing the file mid-flight)
     * nothing ever completes and nothing retries: the .bbspart just sits there and the
     * model stays half-present (geometry or skin missing) until the next join. The
     * watchdog below re-requests files that stopped making progress. */
    private static final long DOWNLOAD_STALL_MS = 20_000L;
    /** A file that never even started: it may just be queued behind a big pack, so allow more. */
    private static final long DOWNLOAD_QUEUE_GRACE_MS = 180_000L;
    private static final int DOWNLOAD_STALL_RETRIES = 3;
    /** relative path -> {firstRequestMs, lastProgressMs (0 = not started), stalledRetries} */
    private static final java.util.concurrent.ConcurrentHashMap<String, long[]> PENDING_DOWNLOADS = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile boolean downloadWatchdogStarted = false;

    private static void watchDownload(String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) {
            return;
        }
        synchronized (TogetherClientNetwork.class) {
            if (!downloadWatchdogStarted) {
                downloadWatchdogStarted = true;
                DOWNLOAD_WATCHDOG.scheduleWithFixedDelay(TogetherClientNetwork::checkStalledDownloads, 10L, 5L, TimeUnit.SECONDS);
            }
        }
        long now = System.currentTimeMillis();
        PENDING_DOWNLOADS.computeIfAbsent(relativePath, k -> new long[]{now, 0L, 0L});
    }

    /** Progress heartbeat: a slice of this file just arrived, so stop counting it as stalled. */
    private static void touchDownload(String relativePath) {
        long[] state = PENDING_DOWNLOADS.get(relativePath);
        if (state != null) {
            state[1] = System.currentTimeMillis();
        }
    }

    private static void forgetDownload(String relativePath) {
        PENDING_DOWNLOADS.remove(relativePath);
    }

    private static void checkStalledDownloads() {
        if (PENDING_DOWNLOADS.isEmpty() || !isBBSTogetherOnServer) {
            return;
        }
        long now = System.currentTimeMillis();
        ArrayList<String> retry = new ArrayList<>();
        for (java.util.Map.Entry<String, long[]> entry : PENDING_DOWNLOADS.entrySet()) {
            long[] state = entry.getValue();
            boolean started = state[1] > 0L;
            long since = started ? now - state[1] : now - state[0];
            long limit = started ? DOWNLOAD_STALL_MS : DOWNLOAD_QUEUE_GRACE_MS;
            if (since < limit) {
                continue;
            }
            if (state[2] >= DOWNLOAD_STALL_RETRIES) {
                PENDING_DOWNLOADS.remove(entry.getKey());
                AssetSyncTracker.error(entry.getKey());
                ++assetDownloadErrors;
                System.out.println("[BBS Assets] Giving up on stalled file: " + entry.getKey());
                finishDownloadTask();
                continue;
            }
            state[2] += 1L;
            state[1] = now;
            retry.add(entry.getKey());
            System.out.println("[BBS Assets] Re-requesting stalled file (" + state[2] + "/" + DOWNLOAD_STALL_RETRIES + "): " + entry.getKey());
        }
        if (retry.isEmpty()) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            return;
        }
        client.execute(() -> {
            try {
                sendAssetRequestChunks(retry);
            }
            catch (Exception ignored) {}
        });
    }

    private static final java.util.concurrent.ScheduledExecutorService DOWNLOAD_WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "BBSTogether-DownloadWatchdog");
        thread.setDaemon(true);
        return thread;
    });

    private static void closeSink(Path part) {
        FileChannel channel = TogetherClientNetwork.OPEN_SINKS.remove(part.toString());
        if (channel != null) {
            try {
                channel.close();
            }
            catch (Exception ignored) {}
        }
    }

    /** Drops every open sink (session reset, or a transfer that will never finish). */
    private static void closeAllSinks() {
        for (String key : new java.util.ArrayList<>(TogetherClientNetwork.OPEN_SINKS.keySet())) {
            FileChannel channel = TogetherClientNetwork.OPEN_SINKS.remove(key);
            if (channel != null) {
                try {
                    channel.close();
                }
                catch (Exception ignored) {}
            }
        }
    }

    /**
     * B4: the server told us "these files changed" ({@code path -> sha1:size}) instead of
     * pushing the files themselves. We pull only the ones we do not already have, so a peer's
     * upload no longer costs every other client a full copy they may never open.
     */
    private static void handleAssetHintPacket(MinecraftClient client, PacketByteBuf buf) {
        crusher.receive(buf, (bytes, packetByteBuf) -> {
            if (bytes == null) {
                return;
            }
            /* Pushes only happen when we asked for them. */
            if (!((Boolean)TogetherSettings.assetSyncReceive.get()).booleanValue()) {
                return;
            }
            if (!((Boolean)TogetherSettings.assetSyncDownload.get()).booleanValue()) {
                return;
            }
            Thread thread = new Thread(() -> {
                try {
                    BaseType data = DataStorageUtils.readFromBytes(bytes);
                    if (!data.isMap()) {
                        return;
                    }
                    MapType hints = data.asMap();
                    long maxBytes = TogetherClientNetwork.clientMaxAssetBytes();
                    ArrayList<String> needed = new ArrayList<>();
                    File assetsFolder = BBSMod.getAssetsFolder();
                    for (String relativePath : hints.keys()) {
                        String entry = hints.getString(relativePath);
                        if (entry == null || !TogetherClientNetwork.isPublishableAsset(relativePath)) {
                            continue;
                        }
                        int sep = entry.lastIndexOf(':');
                        String sha1 = sep > 0 ? entry.substring(0, sep) : entry;
                        long size = 0L;
                        if (sep > 0) {
                            try {
                                size = Long.parseLong(entry.substring(sep + 1));
                            }
                            catch (NumberFormatException ignored) {}
                        }
                        if (size > maxBytes) {
                            String modelId = together$extractModelId(relativePath);
                            if (modelId != null) TogetherClientNetwork.tooLargeModels.add(modelId);
                            mchorse.bbs_together.sync.OversizedAssets.mark(relativePath, size);
                            /* Same rule as the manifest pass: never stub a non-config model file,
                             * or a half-model (mesh without textures) shows up. */
                            if (!(relativePath.startsWith("models/") && !relativePath.endsWith("/config.json"))) {
                                TogetherClientNetwork.ensureOversizedPlaceholder(assetsFolder.toPath(), relativePath);
                            }
                            continue;
                        }
                        File dest = new File(assetsFolder, relativePath.replace("/", File.separator));
                        if (dest.isFile() && sha1.equalsIgnoreCase(sha1OfLocalFileCached(dest.toPath()))) {
                            continue;
                        }
                        needed.add(relativePath);
                    }
                    if (needed.isEmpty()) {
                        return;
                    }
                    client.execute(() -> {
                        if (!isBBSTogetherOnServer || MinecraftClient.getInstance().player == null) {
                            return;
                        }
                        assetDownloadTotal += needed.size();
                        for (String n : needed) {
                            AssetSyncTracker.pending(n, AssetSyncTracker.Dir.DOWNLOAD);
                        }
                        sendAssetRequestChunks(needed);
                    });
                }
                catch (Exception e) {
                    e.printStackTrace();
                }
            }, "BBSTogether-AssetHint");
            thread.setDaemon(true);
            thread.start();
        });
    }

    /** One settled (or failed-for-good) download task; bumps the finished marker and,
     * once everything is in, publishes our inventory so future pushes get filtered. */
    private static void finishDownloadTask() {
        if (++assetDownloadDone >= assetDownloadTotal && assetDownloadTotal > 0) {
            assetDownloadFinishedAt = System.currentTimeMillis();
            TogetherClientNetwork.sendAssetInventory();
        }
    }

    private static String sha1OfLocalFile(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            try (InputStream in = Files.newInputStream(path, new OpenOption[0]);){
                int read;
                byte[] buffer = new byte[8192];
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            byte[] b = digest.digest();
            StringBuilder sb = new StringBuilder(b.length * 2);
            for (byte v : b) {
                if ((v & 0xFF) < 16) {
                    sb.append('0');
                }
                sb.append(Integer.toHexString(v & 0xFF));
            }
            return sb.toString();
        }
        catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-1 not available", e);
        }
    }

    public static void sendPlayerColor(int color) {
        if (!isBBSTogetherOnServer) {
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) {
            return;
        }
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeInt(color);
        ClientPlayNetworking.send((Identifier)TogetherServerNetwork.SERVER_PLAYER_COLOR, (PacketByteBuf)buf);
    }

    public static void sendCursorPosition(String filmId, int editorType, float tick, float row, List<Integer> selectedIndices, int playheadTick) {
        if (!isBBSTogetherOnServer) {
            return;
        }
        if (MinecraftClient.getInstance().player == null) {
            return;
        }
        /* A3: throttle cursor spam to ~20 Hz. "Leave" packets (editorType < 0) always go
         * through so a departing editor's ghost is removed immediately. */
        long now = System.currentTimeMillis();
        if (editorType >= 0 && now - lastCursorSentMs < CURSOR_SEND_INTERVAL_MS) {
            pendingCursor = new Object[]{filmId, editorType, tick, row, selectedIndices, playheadTick};
            return;
        }
        lastCursorSentMs = now;
        pendingCursor = null;
        int selCount = Math.min(selectedIndices.size(), 512);
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeString(filmId);
        buf.writeInt(editorType);
        buf.writeFloat(tick);
        buf.writeFloat(row);
        buf.writeInt(selCount);
        for (int i = 0; i < selCount; ++i) {
            buf.writeInt(selectedIndices.get(i).intValue());
        }
        buf.writeInt(playheadTick);
        ClientPlayNetworking.send((Identifier)TogetherServerNetwork.SERVER_CURSOR_POSITION, (PacketByteBuf)buf);
    }

    /** Send the latest throttled cursor state, called once per render tick so the final
     * cursor position/selection before a pause still lands. */
    public static void flushPendingCursor() {
        Object[] pending = pendingCursor;
        if (pending == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastCursorSentMs < CURSOR_SEND_INTERVAL_MS) {
            return;
        }
        pendingCursor = null;
        lastCursorSentMs = now;
        try {
            TogetherClientNetwork.sendCursorPosition((String)pending[0], (Integer)pending[1], (Float)pending[2], (Float)pending[3], (List)pending[4], (Integer)pending[5]);
        }
        catch (Exception ignored) {}
    }

    public static void sendFilmSelection(String filmId) {
        if (!isBBSTogetherOnServer) {
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) {
            return;
        }
        if (filmId != null && !filmId.isEmpty()) {
            lastLocalFilmId = filmId;
        }
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeString(filmId == null ? "" : filmId);
        ClientPlayNetworking.send((Identifier)TogetherServerNetwork.SERVER_FILM_SELECTION, (PacketByteBuf)buf);
    }

    public static void sendFilmScreen(String filmId) {
        if (!isBBSTogetherOnServer) {
            return;
        }
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeString(filmId == null ? "" : filmId);
        ClientPlayNetworking.send((Identifier)TogetherServerNetwork.SERVER_FILM_SCREEN, (PacketByteBuf)buf);
    }

    public static void sendReplaySelection(String filmId, int replayIndex) {
        if (!isBBSTogetherOnServer || BBSTogetherFix22.isApplyingRemoteSync()) {
            return;
        }
        if (replayIndex >= 0) {
            lastLocalReplayIdx = replayIndex;
        }
        together_setLocalReplaySelection(filmId, replayIndex);
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeString(filmId);
        buf.writeInt(replayIndex);
        ClientPlayNetworking.send((Identifier)TogetherServerNetwork.SERVER_REPLAY_SELECTION, (PacketByteBuf)buf);
    }

    public static void sendReplayFormLock(String filmId, int replayIndex, boolean locked) {
        if (!isBBSTogetherOnServer) {
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) {
            return;
        }
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeString(filmId);
        buf.writeInt(replayIndex);
        buf.writeBoolean(locked);
        ClientPlayNetworking.send((Identifier)TogetherServerNetwork.SERVER_REPLAY_FORM_LOCK, (PacketByteBuf)buf);
    }

    public static void sendReplaySwap(String filmId, int from, int to) {
        if (!isBBSTogetherOnServer) {
            return;
        }
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeString(filmId);
        buf.writeInt(from);
        buf.writeInt(to);
        ClientPlayNetworking.send((Identifier)TogetherServerNetwork.SERVER_REPLAY_SWAP, (PacketByteBuf)buf);
    }

    /** The one asset size ceiling shared by both directions: a file above it is neither
     * downloaded nor uploaded. The server still advertises such files (so the client can show
     * them as "too large" instead of hiding them), but no bytes ever travel for them. 0 means
     * "no ceiling". */
    public static long clientMaxAssetBytes() {
        int mb = TogetherSettings.getAssetSyncMaxMb();
        if (mb <= 0) {
            return Long.MAX_VALUE;
        }
        if (mb > 2048) mb = 2048;
        return (long) mb * 1024L * 1024L;
    }

    /** 1x1 transparent PNG: a stub image that still decodes, so the texture browser lists it
     * (and labels it) instead of failing to load a zero-byte file. */
    private static final byte[] PLACEHOLDER_PNG = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=");

    /**
     * Creates a stub for an over-size asset so the entry keeps existing on disk.
     *
     * <p>No bytes are transferred for such a file (that is what the limit is for), but a missing
     * file is also missing from every picker: the user just sees a hole and cannot tell "not
     * synced yet" from "too big to sync at all". A stub with the right name and extension lets
     * BBS list the entry, while {@code OversizedAssets} makes the UI paint it dimmed with
     * {@code 文件尺寸过大 · 700 MB}. An existing real file is never touched.</p>
     */
    public static void ensureOversizedPlaceholder(Path assetsRoot, String relativePath) {
        if (assetsRoot == null || relativePath == null || relativePath.isEmpty()) {
            return;
        }
        try {
            Path dest = assetsRoot.resolve(relativePath.replace("/", File.separator));
            if (Files.exists(dest)) {
                return;
            }
            Path parent = dest.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String name = dest.getFileName().toString().toLowerCase(Locale.ROOT);
            byte[] content = name.endsWith(".json") ? new byte[]{ '{', '}' } : (name.endsWith(".png") ? PLACEHOLDER_PNG : new byte[0]);
            Files.write(dest, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            /* Our own write must not look like an edit, or the watcher would resync, find the
             * stub "changed" and start a diff loop on a file that can never converge. */
            mchorse.bbs_together.sync.SelfWriteGuard.record(dest);
        }
        catch (Exception e) {
            /* A stub is cosmetic —never let it break the sync diff. */
        }
    }

    /** "models/foo/bar/textures/x.png" -> "foo/bar". Returns null for non-model paths. */
    private static String together$extractModelId(String relativePath) {
        return mchorse.bbs_together.utils.ModelPathUtils.extractModelId(relativePath);
    }

    /** crusher.send isn't thread-safe; every send goes through here so background upload
     * threads can't interleave chunk streams with main-thread sends. */
    private static void sendCrusher(PlayerEntity player, Identifier id, byte[] payload, java.util.function.Consumer<PacketByteBuf> consumer) {
        synchronized (crusher) {
            crusher.send(player, id, payload, consumer);
        }
    }

    private static void sendCrusher(PlayerEntity player, Identifier id, BaseType data, java.util.function.Consumer<PacketByteBuf> consumer) {
        synchronized (crusher) {
            crusher.send(player, id, data, consumer);
        }
    }

    /**
     * Coop accept/decline. When the invitee is on another branch, its full editor film rides
     * along so the server can commit everything (camera clips included) before switching.
     */
    public static void sendCoopRespond(long sessionId, boolean accept, Film film, String branch) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!isConnected() || mc.player == null) {
            return;
        }
        byte[] payload;

        try {
            payload = film == null ? new byte[0] : DataStorageUtils.writeToBytes(film.toData());
        } catch (Exception e) {
            /* Never lose the accept just because the snapshot could not be serialized: the
             * server then commits the branch working copy it already has. */
            e.printStackTrace();
            payload = new byte[0];
        }

        sendCrusher((PlayerEntity) mc.player, TogetherServerNetwork.SERVER_COOP_RESPOND, payload, buf -> {
            buf.writeLong(sessionId);
            buf.writeBoolean(accept);
            buf.writeString(branch == null ? "" : branch, 256);
        });
    }

    private static long uploadIntervalMs() {
        try {
            return ((Number)TogetherSettings.assetUploadIntervalMs.get()).longValue();
        }
        catch (Exception e) {
            return 200L;
        }
    }

    private static long uploadBatchBytes() {
        try {
            return Math.max(30_720L, ((Number)TogetherSettings.assetUploadBatchKb.get()).longValue() * 1024L);
        }
        catch (Exception e) {
            return 512L * 1024L;
        }
    }

    /** B1: this client's share of its own uplink, metered in bytes rather than "sleep between
     * batches" (which let every upload run at line speed right after each sleep). */
    private static final mchorse.bbs_together.utils.TokenBucket UPLOAD_BUDGET = new mchorse.bbs_together.utils.TokenBucket(0L);
    private static int uploadBudgetKbps = -1;

    private static mchorse.bbs_together.utils.TokenBucket uploadBudget() {
        int kbps;
        try {
            kbps = TogetherSettings.getAssetUploadKbps();
        }
        catch (Exception e) {
            kbps = TogetherSettings.DEFAULT_ASSET_UPLOAD_KBPS;
        }
        if (kbps != uploadBudgetKbps) {
            uploadBudgetKbps = kbps;
            UPLOAD_BUDGET.setRate((long)kbps * 1024L);
        }
        return UPLOAD_BUDGET;
    }

    /** A5: stream one asset to the server as slice batches; files never sit in the heap in full. */
    public static void sendAssetUploadSliced(File f, String relPath, long batchBytes, long interval) throws Exception {
        long size = f.length();
        int totalSlices = (int)Math.max(1L, (size + batchBytes - 1L) / batchBytes);
        String sha1 = TogetherClientNetwork.sha1OfLocalFileCached(f.toPath());
        /* The edit time travels with the content; the server stamps it onto its stored copy so
         * later manifest diffs compare edit times, not receipt times. */
        long mtime = Files.getLastModifiedTime(f.toPath()).toMillis();
        try (FileChannel ch = FileChannel.open(f.toPath(), StandardOpenOption.READ)){
            for (int seq = 0; seq < totalSlices; ++seq) {
                long offset = (long)seq * batchBytes;
                int len = (int)Math.min(batchBytes, size - offset);
                ByteBuffer bb = ByteBuffer.allocate(len);
                while (bb.hasRemaining()) {
                    int r = ch.read(bb, offset + (long)bb.position());
                    if (r < 0) break;
                }
                if (bb.hasRemaining()) {
                    throw new IOException("short read while slicing " + relPath);
                }
                final byte[] slice = bb.array();
                final int fSeq = seq;
                final int fTotal = totalSlices;
                final long fSize = size;
                final String fSha = sha1;
                final long fMtime = mtime;
                AssetSyncTracker.active(relPath, AssetSyncTracker.Dir.UPLOAD);
                /* B1: wait for budget before the bytes leave, so a 50 MB model cannot fill the
                 * uplink in one go and starve our own keepalives. */
                long budgetWaitNs = uploadBudget().reserve(slice.length);
                if (budgetWaitNs > 0L) {
                    Thread.sleep(budgetWaitNs / 1_000_000L);
                }
                sendCrusher(MinecraftClient.getInstance().player, TogetherServerNetwork.SERVER_ASSET_UPLOAD, slice, pbuf -> {
                    pbuf.writeString(relPath);
                    pbuf.writeInt(fSeq);
                    pbuf.writeInt(fTotal);
                    pbuf.writeLong(fSize);
                    pbuf.writeString(fSha);
                    pbuf.writeLong(fMtime);
                });
                /* The fixed sleep below is the pre-B1 pacing. When a byte budget is configured the
                 * bucket above already meters the slice, so sleeping again only halves the speed. */
                if (seq < totalSlices - 1 && interval > 0L && uploadBudgetKbps <= 0) {
                    Thread.sleep(Math.min(interval, 50L));
                }
            }
        }
        AssetSyncTracker.done(relPath);
        if (++assetUploadDone >= assetUploadTotal && assetUploadTotal > 0) {
            assetUploadFinishedAt = System.currentTimeMillis();
        }
    }

    /** Ask the server to resend the manifest, then download only files that differ from local. */
    public static void requestModelResync() {
        if (!isBBSTogetherOnServer) return;
        resyncMode = 1;
        AssetSyncTracker.beginScan();
        sendManifestVersionQuery();
    }

    /** Ask the server to resend the manifest, then upload only local files missing from the server. */
    public static void forceUploadModels() {
        if (!isBBSTogetherOnServer) return;
        resyncMode = 2;
        AssetSyncTracker.beginScan();
        System.out.println("[BBS Assets] Manual upload requested — waiting for the server's asset manifest");
        sendManifestVersionQuery();
    }

    /** B4: request the manifest, riding our cached manifest fingerprint along —an unchanged
     * server answers with a cheap stub instead of the full manifest. */
    private static void sendManifestVersionQuery() {
        if (!isBBSTogetherOnServer) return;
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeLong(lastManifestVersion);
        ClientPlayNetworking.send((Identifier)TogetherServerNetwork.SERVER_ASSET_RESYNC, (PacketByteBuf)buf);
    }

    /** B1: tell the server whether we want pushed assets at all (assetSyncReceive). */
    private static void sendSyncPref() {
        if (!isBBSTogetherOnServer || MinecraftClient.getInstance().player == null) {
            return;
        }
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBoolean(((Boolean)TogetherSettings.assetSyncReceive.get()).booleanValue());
        ClientPlayNetworking.send((Identifier)TogetherServerNetwork.SERVER_SYNC_PREF, (PacketByteBuf)buf);
    }

    /** B2: publish {relativePath: sha1} of our local assets so the server can skip us for
     * pushes of files we already own. Debounced; the server-side filter is best-effort.
     *
     * <p>A1/A2: this only sends what changed since the last report (and nothing at all when
     * nothing changed), because a full inventory of a few thousand assets is 100+ KB of upload
     * per call and the diff pass used to call it unconditionally —twice.</p> */
    private static boolean isLocalTransferTemp(String relativePath) {
        return TogetherClientNetwork.isTransferTemp(relativePath);
    }

    private static boolean isPublishableAsset(String relativePath) {
        return TogetherClientNetwork.isPublishable(relativePath);
    }

    private static boolean isTransferTemp(String relativePath) {
        return mchorse.bbs_together.utils.AssetPaths.isTransferTemp(relativePath);
    }

    private static boolean isPublishable(String relativePath) {
        return mchorse.bbs_together.utils.AssetPaths.isPublishable(relativePath);
    }

    /**
     * Deletes slice temps left behind by an interrupted transfer. They are useless without resume
     * support and they are big: a handful of aborted 300 MB model pulls leaves ~1 GB of
     * {@code .bbspart} junk that every subsequent startup would otherwise keep walking over.
     */
    public static void purgeStaleTransferTemps() {
        File assetsFolder = BBSMod.getAssetsFolder();
        if (assetsFolder == null || !assetsFolder.exists()) {
            return;
        }
        Thread thread = new Thread(() -> {
            long removed = 0L;
            int count = 0;
            try (java.util.stream.Stream<Path> stream = Files.walk(assetsFolder.toPath(), new FileVisitOption[0])) {
                java.util.List<Path> temps = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> TogetherClientNetwork.isTransferTemp(p.getFileName().toString()))
                    .collect(java.util.stream.Collectors.toList());
                for (Path p : temps) {
                    try {
                        long size = Files.size(p);
                        if (Files.deleteIfExists(p)) {
                            removed += size;
                            ++count;
                        }
                    }
                    catch (Exception ignored) {}
                }
            }
            catch (Exception ignored) {}
            if (count > 0) {
                System.out.println("[BBS Together] 清理残留传输临时文件: " + count + " 个 / " + (removed / 1024L / 1024L) + " MiB");
            }
        }, "BBSTogether-TempCleanup");
        thread.setDaemon(true);
        thread.start();
    }

    private static void sendAssetInventory() {
        if (!isBBSTogetherOnServer || MinecraftClient.getInstance().player == null) {
            return;
        }
        File assetsFolder = BBSMod.getAssetsFolder();
        if (assetsFolder == null || !assetsFolder.exists()) {
            return;
        }
        final boolean deltaSupported = ClientPlayNetworking.canSend(TogetherServerNetwork.SERVER_ASSET_REPORT_DELTA);
        if (!inventoryInFlight.compareAndSet(false, true)) {
            /* A walk is already running; its loop picks this request up on the next round. */
            inventoryDirty.set(true);
            return;
        }
        Thread thread = new Thread(() -> {
            try {
                do {
                    inventoryDirty.set(false);
                    sendAssetInventoryNow(assetsFolder, deltaSupported);
                }
                while (inventoryDirty.get());
            }
            finally {
                inventoryInFlight.set(false);
            }
            if (inventoryDirty.get()) {
                sendAssetInventory();
            }
        }, "BBSTogether-AssetInventory");
        thread.setDaemon(true);
        thread.start();
    }

    private static void sendAssetInventoryNow(File assetsFolder, boolean deltaSupported) {
        try {
            Path assetsPath = assetsFolder.toPath();
            HashMap<String, String> local = new HashMap<>();
            Files.walk(assetsPath, new FileVisitOption[0]).filter(x$0 -> Files.isRegularFile(x$0, new LinkOption[0])).forEach(p -> {
                try {
                    String rel = assetsPath.relativize((Path)p).toString().replace(File.separator, "/");
                    /* Half-written slice temps are not assets: reporting them made every client
                     * hash its own leftovers (hundreds of MB after a few aborted downloads) and
                     * advertise paths that the server must not know about. */
                    if (TogetherClientNetwork.isLocalTransferTemp(rel)) {
                        return;
                    }
                    local.put(rel, TogetherClientNetwork.sha1OfLocalFileCached((Path)p));
                }
                catch (Exception ignored) {}
            });
            MapType changed = new MapType();
            int changes = 0;
            synchronized (lastReportedSha1) {
                for (Map.Entry<String, String> e : local.entrySet()) {
                    String was = lastReportedSha1.get(e.getKey());
                    if (was != null && was.equalsIgnoreCase(e.getValue())) {
                        continue;
                    }
                    changed.putString(e.getKey(), e.getValue());
                    ++changes;
                }
                for (String old : lastReportedSha1.keySet()) {
                    if (!local.containsKey(old)) {
                        /* An empty sha1 tells the server we no longer own this path. */
                        changed.putString(old, "");
                        ++changes;
                    }
                }
                if (changes == 0) {
                    return;
                }
                if (deltaSupported) {
                    lastReportedSha1.clear();
                    lastReportedSha1.putAll(local);
                }
            }
            MapType payload = changed;
            if (!deltaSupported) {
                /* Old server: its handler replaces the whole map, so it needs the full picture. */
                payload = new MapType();
                for (Map.Entry<String, String> e : local.entrySet()) {
                    payload.putString(e.getKey(), e.getValue());
                }
            }
            final MapType report = payload;
            final Identifier channel = deltaSupported ? TogetherServerNetwork.SERVER_ASSET_REPORT_DELTA : TogetherServerNetwork.SERVER_ASSET_REPORT;
            MinecraftClient.getInstance().execute(() -> {
                PlayerEntity player = MinecraftClient.getInstance().player;
                if (!isBBSTogetherOnServer || player == null) {
                    return;
                }
                sendCrusher(player, channel, (BaseType)report, null);
            });
        }
        catch (Exception e) {
            e.printStackTrace();
        }
        finally {
            /* Persist whatever was hashed so the next join does not re-read gigabytes. */
            TogetherClientNetwork.saveHashCache();
        }
    }

    /** B3: publish {filmId: sha1} of our local films; the server sends only films that are
     * missing or differ. Called on join and (debounced) whenever the film list changes. */
    public static void sendFilmInventory() {
        if (!isBBSTogetherOnServer || MinecraftClient.getInstance().player == null) {
            return;
        }
        lastFilmInventorySent = System.currentTimeMillis();
        Thread thread = new Thread(() -> {
            try {
                MapType report = new MapType();
                for (String filmId : BBSMod.getFilms().getKeys()) {
                    try {
                        File f = BBSMod.getFilms().getFile(filmId);
                        if (f == null || !f.isFile()) continue;
                        report.putString(filmId, TogetherClientNetwork.sha1OfLocalFile(f.toPath()));
                    }
                    catch (Exception ignored) {}
                }
                MinecraftClient.getInstance().execute(() -> {
                    if (!isBBSTogetherOnServer || MinecraftClient.getInstance().player == null) {
                        return;
                    }
                    sendCrusher(MinecraftClient.getInstance().player, TogetherServerNetwork.SERVER_FILM_INVENTORY, (BaseType)report, null);
                });
            }
            catch (Exception e) {
                e.printStackTrace();
            }
        }, "BBSTogether-FilmInventory");
        thread.setDaemon(true);
        thread.start();
    }

    public static void stripRecordingCommands(Replay replay) {
        try {
            if (replay.actions == null) {
                return;
            }
            List allClips = replay.actions.getClips(Clip.class);
            if (allClips == null || allClips.isEmpty()) {
                return;
            }
            int maxTick = 0;
            for (Object clipObj : allClips) {
                Clip clip = (Clip)clipObj;
                maxTick = Math.max(maxTick, (Integer)clip.tick.get() + (Integer)clip.duration.get());
            }
            int trimThreshold = maxTick - 5;
            ArrayList<Clip> toRemove = new ArrayList<Clip>();
            for (Object clipObj : allClips) {
                Clip clip = (Clip)clipObj;
                if ((Integer)clip.tick.get() >= trimThreshold) {
                    toRemove.add(clip);
                    continue;
                }
                try {
                    String serialized = clip.toData().toString();
                    if (!serialized.contains("bbstogether")) continue;
                    toRemove.add(clip);
                }
                catch (Exception exception) {}
            }
            for (Clip clip : toRemove) {
                replay.actions.remove(clip);
            }
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void sendRecordingFinished(String filmId, int replayIndex, Replay replay) {
        if (!isBBSTogetherOnServer) {
            return;
        }
        sendCrusher((PlayerEntity)MinecraftClient.getInstance().player, TogetherServerNetwork.SERVER_RECORDING_FINISHED, replay.toData(), buf -> {
            buf.writeString(filmId);
            buf.writeString(filmBranch(filmId));
            buf.writeInt(replayIndex);
        });
    }

    public static void sendFilmSaveSync(String filmId, Film film) {
        if (!isBBSTogetherOnServer || film == null || filmId == null) {
            return;
        }
        sendCrusher((PlayerEntity)MinecraftClient.getInstance().player, TogetherServerNetwork.SERVER_FILM_SAVE_SYNC, film.toData(), buf -> {
            buf.writeString(filmId);
            buf.writeString(filmBranch(filmId));
        });
    }

    public static void sendMasterRecordingDone(String filmId, Film film) {
        if (!isBBSTogetherOnServer || film == null || filmId == null) {
            return;
        }
        sendCrusher((PlayerEntity)MinecraftClient.getInstance().player, TogetherServerNetwork.SERVER_MASTER_DONE, film.toData(), buf -> {
            buf.writeString(filmId);
            buf.writeString(filmBranch(filmId));
        });
    }

    public static void sendSyncData(String filmId, BaseValue value) {
        if (!isBBSTogetherOnServer) {
            return;
        }
        DataPath path = value.getPath();
        sendCrusher((PlayerEntity)MinecraftClient.getInstance().player, TogetherServerNetwork.SERVER_FILM_DATA_SYNC, value.toData(), buf -> {
            buf.writeString(filmId);
            buf.writeInt(path.strings.size());
            for (String s : path.strings) {
                buf.writeString(s);
            }
            buf.writeString(filmBranch(filmId));
        });
    }

    public static void sendSyncDataWithPath(String filmId, List<String> pathStrings, BaseValue value) {
        if (!isBBSTogetherOnServer) {
            return;
        }
        if (MinecraftClient.getInstance().player == null) {
            return;
        }
        sendCrusher((PlayerEntity)MinecraftClient.getInstance().player, TogetherServerNetwork.SERVER_FILM_DATA_SYNC, value.toData(), buf -> {
            buf.writeString(filmId);
            buf.writeInt(pathStrings.size());
            for (String s : pathStrings) {
                buf.writeString(s);
            }
            buf.writeString(filmBranch(filmId));
        });
    }

    /** Path sync with an arbitrary payload —used by the undo guard to flush a still-queued
     * edit's recorded post-edit state (the value's current data is already rolled back at
     * that point, so it cannot be re-serialized). */
    public static void sendSyncDataPayload(String filmId, List<String> pathStrings, BaseType payload) {
        if (!isBBSTogetherOnServer) {
            return;
        }
        if (MinecraftClient.getInstance().player == null || payload == null) {
            return;
        }
        sendCrusher((PlayerEntity)MinecraftClient.getInstance().player, TogetherServerNetwork.SERVER_FILM_DATA_SYNC, payload, buf -> {
            buf.writeString(filmId);
            buf.writeInt(pathStrings.size());
            for (String s : pathStrings) {
                buf.writeString(s);
            }
            buf.writeString(filmBranch(filmId));
        });
    }

    /** One subtree of an undo/redo entry, as sent to the server for guarded replay. */
    public static class UndoChangePayload {
        public final List<String> path;
        public final BaseType data;
        public final BaseType expected;
        public final boolean checkable;

        public UndoChangePayload(List<String> path, BaseType data, BaseType expected, boolean checkable) {
            this.path = path;
            this.data = data;
            this.expected = expected;
            this.checkable = checkable;
        }
    }

    /** Broadcast an undo/redo with the entry's expected baseline attached, so the server
     * can refuse a rollback that would wipe another player's newer edit (s_undo_sync). */
    public static void sendUndoSync(String filmId, List<UndoChangePayload> changes) {
        if (!isBBSTogetherOnServer || changes.isEmpty()) {
            return;
        }
        if (MinecraftClient.getInstance().player == null) {
            return;
        }
        sendCrusher((PlayerEntity)MinecraftClient.getInstance().player, TogetherServerNetwork.SERVER_UNDO_SYNC, new byte[0], buf -> {
            buf.writeString(filmId);
            buf.writeInt(changes.size());
            for (UndoChangePayload c : changes) {
                buf.writeInt(c.path.size());
                for (String s : c.path) {
                    buf.writeString(s);
                }
                byte[] data = DataStorageUtils.writeToBytes(c.data);
                buf.writeInt(data.length);
                buf.writeBytes(data);
                byte[] expected = DataStorageUtils.writeToBytes(c.expected);
                buf.writeInt(expected.length);
                buf.writeBytes(expected);
                buf.writeBoolean(c.checkable);
            }
            buf.writeString(filmBranch(filmId));
        });
    }

    public static boolean isReplayFormLocked(String filmId, int replayIndex) {
        if (!isBBSTogetherOnServer) {
            return false;
        }
        Map<Integer, UUID> locks = replayFormLocks.get(filmId);
        if (locks == null) {
            return false;
        }
        UUID owner = locks.get(replayIndex);
        if (owner == null) {
            return false;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.player != null && !owner.equals(mc.player.getUuid());
    }

    private static void handleFilmBranchStatePacket(MinecraftClient client, PacketByteBuf buf) {
        crusher.receive(buf, (bytes, packetByteBuf) -> {
            String filmId = packetByteBuf.readString();
            String branch = packetByteBuf.readString(FilmCommit.MAX_BRANCH * 2);
            BaseType data = DataStorageUtils.readFromBytes((byte[])bytes);

            if (data == null || !data.isMap()) {
                return;
            }

            client.execute(() -> TogetherClientNetwork.applyFilmBranchState(filmId, branch, data));
        });
    }

    /** A branch state is authoritative: always replace the local film and branch, no matter
     * how long ago the checkout was clicked or what the pending timer says. */
    private static void applyFilmBranchState(String filmId, String branch, BaseType data) {
        pendingBranchCheckouts.remove(filmId);

        if (branch != null && !branch.isEmpty()) {
            setFilmBranch(filmId, branch);
        }

        applyFullFilmPacket(filmId, data, true);
    }

    private static void handleFilmSavedSyncPacket(MinecraftClient client, PacketByteBuf buf) {
        crusher.receive(buf, (bytes, packetByteBuf) -> {
            String filmId = packetByteBuf.readString();
            String saverName = packetByteBuf.readString();
            String branch = packetByteBuf.isReadable() ? packetByteBuf.readString(FilmCommit.MAX_BRANCH * 2) : "";
            BaseType data = DataStorageUtils.readFromBytes((byte[])bytes);
            if (!data.isMap()) {
                return;
            }
            client.execute(() -> lambda$handleFilmSavedSyncPacket$42(filmId, data, saverName, branch));
        });
    }

    private static void lambda$handleFilmSavedSyncPacket$42(String filmId, BaseType data, String saverName, String branch) {
        String pending = TogetherClientNetwork.pendingCheckoutBranch(filmId);
        String previous = TogetherClientNetwork.filmBranch(filmId);
        if (branch != null && !branch.isEmpty()) {
            /* Accept the new branch the checkout is waiting for, or the branch we already
             * hold (a normal save / a failed checkout). Anything else is an in-flight packet
             * from the branch we just left. */
            if (!previous.isEmpty() && !branch.equals(previous) && !branch.equals(pending)) {
                return;
            }
            if (pending != null && branch.equals(pending)) {
                pendingBranchCheckouts.remove(filmId);
            }
        } else if (pending != null) {
            return;
        }
        boolean branchChange = branch != null && !branch.isEmpty()
            && (previous.isEmpty() || !previous.equals(branch));
        if (branch != null && !branch.isEmpty()) {
            /* The server only sends a branch's data to players on that branch; a checkout
             * arrives through the same packet, so accepting it also moves our local branch. */
            setFilmBranch(filmId, branch);
        }

        applyFullFilmPacket(filmId, data, branchChange);
    }

    /** Apply one complete film copy to the open editor, or just the local/hidden copy. */
    private static void applyFullFilmPacket(String filmId, BaseType data, boolean force) {
        UIDashboard dashboard = getDashboardIfOpen();
        UIFilmPanel panel = dashboard == null ? null : (UIFilmPanel)dashboard.getPanel(UIFilmPanel.class);
        boolean visible = panel != null && panel.getData() != null && ((Film)panel.getData()).getId().equals(filmId) && panel.isVisible();

        if (visible) {
            /* A branch switch/initial load is authoritative even when the incoming branch has
             * no camera clips; the old guards only make sense for same-branch live updates. */
            applyFilmDataUpdate(panel, data.asMap(), force);
            together_notifyFilmSyncApplied(panel, filmId);
            return;
        }

        UIDashboard created = BBSModClient.getDashboardIfCreated();
        UIFilmPanel hiddenPanel = created == null ? null : (UIFilmPanel)created.getPanel(UIFilmPanel.class);

        if (hiddenPanel != null && hiddenPanel.getData() != null && ((Film)hiddenPanel.getData()).getId().equals(filmId)) {
            together_applyHiddenFilmDataUpdate(hiddenPanel, data.asMap());
        }

        together_saveReceivedFilm(filmId, data.asMap());
    }

    private static void applyFilmDataUpdate(UIFilmPanel panel, MapType data) {
        applyFilmDataUpdate(panel, data, false);
    }

    private static void applyFilmDataUpdate(UIFilmPanel panel, MapType data, boolean force) {
        together_applyFullFilmDataUpdate(panel, data, true, force);
    }

    private static void together_applyFullFilmDataUpdate(UIFilmPanel panel, MapType data, boolean announce) {
        together_applyFullFilmDataUpdate(panel, data, announce, false);
    }

    private static void together_applyFullFilmDataUpdate(UIFilmPanel panel, MapType data, boolean announce, boolean force) {
        List<Integer> cameraSel = together_copyClipSelection(panel.cameraEditor);
        List<Integer> actionSel = together_copyClipSelection(panel.actionEditor);
        Map<String, List<Integer>> keyframeSel = together_copyKeyframeSelection(panel);
        Film film = (Film)panel.getData();
        int currentIdx = together_getCurrentReplayIndex(panel, film);
        if (!force && together_wouldClearLocalCamera(film, data)) {
            return;
        }
        Film incoming = new Film();
        incoming.fromData((BaseType)data);
        if (!force && together_clipCount(film.camera) > 0 && together_clipCount(incoming.camera) == 0) {
            return;
        }
        int remappedIdx = together_remapReplayIndex(film.replays.getList(), incoming.replays.getList(), currentIdx);
        together_remapClientReplaySelections(film.getId(), film, incoming);
        BBSTogetherFix22.beginRemoteSyncApply();
        try {
            film.fromData(incoming.toData());
            if (!BBSTogetherFix14.hasCameraKeyframeEditor(panel)) {
                panel.fillData();
            }
            if (panel.replayEditor.replaysList != null && panel.replayEditor.replaysList.replays != null) {
                panel.replayEditor.replaysList.replays.refreshReplayList();
                panel.replayEditor.replaysList.replays.update();
            }
            panel.getController().createEntities();
            List<Replay> replays = ((Film)panel.getData()).replays.getList();
            together_restoreReplaySelection(panel, replays, remappedIdx);
        }
        finally {
            BBSTogetherFix22.endRemoteSyncApply();
        }
        together_restoreClipSelection(panel.cameraEditor, cameraSel);
        together_restoreClipSelection(panel.actionEditor, actionSel);
        together_restoreKeyframeSelection(panel, keyframeSel);
        UIKeyframeEditor keyframeEditor = panel.replayEditor.keyframeEditor;
        if (keyframeEditor != null && keyframeEditor.view != null) {
            keyframeEditor.view.getGraph().pickSelected();
        }
        int reconfirmedIdx = together_getCurrentReplayIndex(panel, (Film)panel.getData());
        together_setLocalReplaySelection(((Film)panel.getData()).getId(), reconfirmedIdx);
        sendReplaySelection(((Film)panel.getData()).getId(), reconfirmedIdx);
        BBSTogetherFix33Client.reannounceReplaySelectionAfterFullSync(panel);
    }

    private static void handleFilmDeletedPacket(MinecraftClient client, String filmId) {
        client.execute(() -> {
            UIFilmPanel panel;
            try {
                BBSMod.getFilms().delete(filmId);
            }
            catch (Exception exception) {
            }
            filmBranches.remove(filmId);
            commitSelections.remove(filmId);
            playerCommitPositions.remove(filmId);
            UIDashboard dashboard = TogetherClientNetwork.getDashboardIfOpen();
            UIFilmPanel uIFilmPanel = panel = dashboard == null ? null : (UIFilmPanel)dashboard.getPanel(UIFilmPanel.class);
            if (panel != null) {
                if (panel.getData() != null && ((Film)panel.getData()).getId().equals(filmId)) {
                    panel.fill(null);
                }
                panel.requestNames();
            }
        });
    }

    private static void handleFilmRenamedPacket(MinecraftClient client, String oldId, String newId) {
        client.execute(() -> {
            UIFilmPanel panel;
            try {
                BBSMod.getFilms().rename(oldId, newId);
            }
            catch (Exception exception) {
            }
            String branch = filmBranches.remove(oldId);
            if (branch != null) {
                filmBranches.put(newId, branch);
            }
            ConcurrentHashMap<UUID, String> oldSelections = commitSelections.remove(oldId);
            if (oldSelections != null) {
                commitSelections.put(newId, oldSelections);
            }
            ConcurrentHashMap<UUID, String> oldPositions = playerCommitPositions.remove(oldId);
            if (oldPositions != null) {
                playerCommitPositions.put(newId, oldPositions);
            }
            UIDashboard dashboard = TogetherClientNetwork.getDashboardIfOpen();
            UIFilmPanel uIFilmPanel = panel = dashboard == null ? null : (UIFilmPanel)dashboard.getPanel(UIFilmPanel.class);
            if (panel != null) {
                if (panel.getData() != null && ((Film)panel.getData()).getId().equals(oldId)) {
                    ((Film)panel.getData()).setId(newId);
                }
                panel.requestNames();
            }
        });
    }

    public static void sendFilmDeleted(String filmId) {
        if (!isBBSTogetherOnServer) {
            return;
        }
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeString(filmId);
        ClientPlayNetworking.send((Identifier)TogetherServerNetwork.SERVER_FILM_DELETED, (PacketByteBuf)buf);
    }

    public static void sendFilmRenamed(String oldId, String newId) {
        if (!isBBSTogetherOnServer) {
            return;
        }
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeString(oldId);
        buf.writeString(newId);
        ClientPlayNetworking.send((Identifier)TogetherServerNetwork.SERVER_FILM_RENAMED, (PacketByteBuf)buf);
    }

    private static void handleFilmListChangedPacket(MinecraftClient client) {
        client.execute(() -> {
            UIFilmPanel panel;
            UIDashboard dashboard = TogetherClientNetwork.getDashboardIfOpen();
            UIFilmPanel uIFilmPanel = panel = dashboard == null ? null : (UIFilmPanel)dashboard.getPanel(UIFilmPanel.class);
            if (panel != null) {
                panel.requestNames();
            }
        });
        /* B3: someone else saved/renamed/created a film —pull the changed ones on demand,
         * debounced so a burst of saves during editing doesn't trigger a pull per save. */
        long now = System.currentTimeMillis();
        if (now - lastFilmInventorySent > 3000L) {
            TogetherClientNetwork.sendFilmInventory();
        }
    }

    public static void sendFilmListChanged() {
        if (!isBBSTogetherOnServer) {
            return;
        }
        ClientPlayNetworking.send((Identifier)TogetherServerNetwork.SERVER_FILM_LIST_CHANGED, (PacketByteBuf)PacketByteBufs.create());
    }

    /* Server behavior log */

    /** Ask the server for behavior entries newer than {@code afterId} (0 = full snapshot).
     * The client's data version rides along: a lagging version gets a full snapshot back. */
    public static void sendBehaviorQuery(long afterId) {
        if (!isBBSTogetherOnServer || MinecraftClient.getInstance().player == null) {
            return;
        }
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeLong(afterId);
        buf.writeLong(behaviorVersion);
        ClientPlayNetworking.send((Identifier)TogetherServerNetwork.SERVER_BEHAVIOR_QUERY, (PacketByteBuf)buf);
    }

    /** Report a client-side action (model editor save) to the server log. */
    public static void sendBehaviorReport(int subType, String target, List<String> items) {
        if (!isBBSTogetherOnServer || MinecraftClient.getInstance().player == null) {
            return;
        }
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeInt(subType);
        buf.writeString(target == null ? "" : target);
        buf.writeInt(items == null ? 0 : Math.min(items.size(), 64));
        if (items != null) {
            for (int i = 0; i < Math.min(items.size(), 64); ++i) {
                buf.writeString(items.get(i));
            }
        }
        ClientPlayNetworking.send((Identifier)TogetherServerNetwork.SERVER_BEHAVIOR_REPORT, (PacketByteBuf)buf);
    }

    /** Ask the server to (un)op a player; the server double checks the requester. */
    public static void sendSetOperator(UUID targetId, boolean op) {
        if (!isBBSTogetherOnServer || MinecraftClient.getInstance().player == null) {
            return;
        }
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeUuid(targetId);
        buf.writeBoolean(op);
        ClientPlayNetworking.send((Identifier)TogetherServerNetwork.SERVER_SET_OPERATOR, (PacketByteBuf)buf);
    }

    /** Ask the server to wipe every log entry of a player (OP only, server re-checks). */
    public static void sendRemovePlayerLogs(UUID targetId) {
        if (!isBBSTogetherOnServer || MinecraftClient.getInstance().player == null) {
            return;
        }
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeUuid(targetId);
        ClientPlayNetworking.send((Identifier)TogetherServerNetwork.SERVER_REMOVE_PLAYER_LOGS, (PacketByteBuf)buf);
    }

    /** Ask the server to set a player's BBS permission level (manager only, server re-checks). */
    public static void sendSetBbsOp(UUID targetId, int level) {
        if (!isBBSTogetherOnServer || MinecraftClient.getInstance().player == null) {
            return;
        }
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeUuid(targetId);
        buf.writeInt(level);
        ClientPlayNetworking.send((Identifier)TogetherServerNetwork.SERVER_BBSOP_SET, (PacketByteBuf)buf);
    }

    /** Allow or deny one editable domain for a (level-1) player (manager only, server re-checks). */
    public static void sendSetBbsGrant(UUID targetId, String domainId, boolean allowed) {
        if (!isBBSTogetherOnServer || MinecraftClient.getInstance().player == null) {
            return;
        }
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeUuid(targetId);
        buf.writeString(domainId);
        buf.writeBoolean(allowed);
        ClientPlayNetworking.send((Identifier)TogetherServerNetwork.SERVER_BBSOP_GRANT, (PacketByteBuf)buf);
    }

    /** Ask the server (as a logged request behavior) to delete a model; managers approve/deny it. */
    public static void sendModelDeleteRequest(String modelId) {
        if (!isBBSTogetherOnServer || MinecraftClient.getInstance().player == null) {
            return;
        }
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeString(modelId == null ? "" : modelId);
        ClientPlayNetworking.send((Identifier)TogetherServerNetwork.SERVER_MODEL_DELETE_REQUEST, (PacketByteBuf)buf);
    }

    /** Approve or deny a pending model-delete request (manager only, server re-checks). */
    public static void sendModelDeleteDecision(long entryId, boolean approve) {
        if (!isBBSTogetherOnServer || MinecraftClient.getInstance().player == null) {
            return;
        }
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeLong(entryId);
        buf.writeBoolean(approve);
        ClientPlayNetworking.send((Identifier)TogetherServerNetwork.SERVER_MODEL_DELETE_DECISION, (PacketByteBuf)buf);
    }

    /** An approved server-side deletion: drop this client's local copy of the model. */
    private static void handleModelDeletedPacket(MinecraftClient client, String modelId) {
        client.execute(() -> {
            boolean removed = deleteLocalModelFolder(modelId);
            System.out.println("[BBSTogether] Server deleted model " + modelId + (removed ? "; removed the local copy" : "; no local copy found"));
        });
    }

    /**
     * Recursively delete assets/models/<modelId>/ and let the asset watchdog refresh the
     * model palette (ModelManager unloads, ModelFormSection rescans). Also used by the
     * model list's local-delete menu action. Returns whether anything was deleted.
     */
    public static boolean deleteLocalModelFolder(String modelId) {
        if (modelId == null || modelId.isEmpty() || modelId.contains("..")) {
            return false;
        }
        File modelDir = new File(BBSMod.getAssetsFolder(), "models/" + modelId.replace('/', File.separatorChar));
        if (!modelDir.isDirectory()) {
            return false;
        }
        boolean ok = true;
        try {
            Files.walkFileTree(modelDir.toPath(), new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        catch (Exception e) {
            e.printStackTrace();
            ok = false;
        }
        localOnlyModels.remove(modelId);
        BBSResources.markAssetsChanged();
        return ok;
    }

    private static void handlePermissionsPacket(MinecraftClient client, PacketByteBuf buf) {
        boolean op = buf.readBoolean();
        boolean manager = buf.readBoolean();
        client.execute(() -> {
            isOperator = op;
            isManager = manager;
        });
    }

    private static void handleBbsopSyncPacket(MinecraftClient client, PacketByteBuf buf) {
        int newLevel = buf.readInt();
        /* Level + optional domain whitelist (restricted level-1 editors). */
        java.util.List<String> whitelist = null;
        if (buf.readBoolean()) {
            int count = buf.readInt();
            whitelist = new java.util.ArrayList<>(count);
            for (int i = 0; i < count; ++i) {
                whitelist.add(buf.readString());
            }
        }
        java.util.List<String> finalWhitelist = whitelist;
        client.execute(() -> mchorse.bbs_together.bbsop.BbsOpClientState.sync(newLevel, finalWhitelist));
    }

    private static void handleBehaviorListPacket(MinecraftClient client, PacketByteBuf buf) {
        crusher.receive(buf, (bytes, packetByteBuf) -> {
            BaseType data = DataStorageUtils.readFromBytes((byte[])bytes);
            if (!data.isMap()) {
                return;
            }
            MapType map = data.asMap();
            client.execute(() -> {
                isOperator = map.getBool("op");
                isManager = map.getBool("manager");
                long version = map.getLong("version");
                /* A version mismatch means entries were removed, merged or evicted since
                 * our last pull —replace everything, don't merge increments on top */
                boolean full = version != behaviorVersion;
                behaviorVersion = version;
                if (full) {
                    behaviorEntries.clear();
                }
                mchorse.bbs_mod.data.types.ListType behaviors = map.getList("behaviors");
                for (int i = 0; i < behaviors.size(); ++i) {
                    mchorse.bbs_together.behaviors.BehaviorEntry entry = mchorse.bbs_together.behaviors.BehaviorEntry.fromData(behaviors.getMap(i));
                    behaviorEntries.put(entry.id, entry);
                }
                if (map.has("players")) {
                    behaviorPlayers.clear();
                    mchorse.bbs_mod.data.types.ListType players = map.getList("players");
                    for (int i = 0; i < players.size(); ++i) {
                        mchorse.bbs_mod.data.types.MapType playerData = players.getMap(i);
                        mchorse.bbs_together.behaviors.PlayerRecord record = mchorse.bbs_together.behaviors.PlayerRecord.fromData(playerData);
                        if (record.id != null) {
                            record.op = playerData.getBool("isOp");
                            record.offline = playerData.getBool("offline");
                            record.bbsLevel = playerData.getInt("bbsop");
                            /* Key present (even empty string = every domain denied) means the
                             * level-1 editor is whitelist-restricted; key absent = unrestricted. */
                            record.bbsGrants = playerData.has("bbsopGrants") ? playerData.getString("bbsopGrants") : null;
                            behaviorPlayers.put(record.id, record);
                        }
                    }
                }
                behaviorDataStamp++;
            });
        });
    }

    /* Film commits */

    public static void sendCommitIndex() {
        if (!isBBSTogetherOnServer || MinecraftClient.getInstance().player == null) {
            return;
        }
        ClientPlayNetworking.send((Identifier)TogetherServerNetwork.SERVER_COMMIT_INDEX, PacketByteBufs.create());
    }

    public static void sendCommitsQuery(String filmId) {
        if (!isBBSTogetherOnServer || filmId == null || MinecraftClient.getInstance().player == null) {
            return;
        }
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeString(filmId);
        ClientPlayNetworking.send((Identifier)TogetherServerNetwork.SERVER_COMMITS_QUERY, (PacketByteBuf)buf);
    }

    public static void sendCommitDiff(String filmId, String commitId) {
        if (!isBBSTogetherOnServer || filmId == null || commitId == null || MinecraftClient.getInstance().player == null) {
            return;
        }
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeString(filmId);
        buf.writeString(commitId);
        ClientPlayNetworking.send((Identifier)TogetherServerNetwork.SERVER_COMMIT_DIFF, (PacketByteBuf)buf);
    }

    public static void sendCommitHead(String filmId) {
        if (!isBBSTogetherOnServer || filmId == null || MinecraftClient.getInstance().player == null) {
            return;
        }
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeString(filmId);
        ClientPlayNetworking.send((Identifier)TogetherServerNetwork.SERVER_COMMIT_HEAD, (PacketByteBuf)buf);
    }

    public static void sendCommitRename(String filmId, String commitId, String message) {
        if (!isBBSTogetherOnServer || filmId == null || commitId == null || MinecraftClient.getInstance().player == null) {
            return;
        }
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeString(filmId);
        buf.writeString(commitId);
        buf.writeString(message == null ? "" : message);
        ClientPlayNetworking.send((Identifier)TogetherServerNetwork.SERVER_COMMIT_RENAME, (PacketByteBuf)buf);
    }

    /** Tell the server which commit this player is looking at (empty commit clears it). */
    public static void sendCommitSelection(String filmId, String commitId) {
        if (!isBBSTogetherOnServer || filmId == null || MinecraftClient.getInstance().player == null) {
            return;
        }
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeString(filmId);
        buf.writeString(commitId == null ? "" : commitId);
        ClientPlayNetworking.send((Identifier)TogetherServerNetwork.SERVER_COMMIT_SELECTION, (PacketByteBuf)buf);
    }

    /** Players (other than the local one, filtered by the caller) looking at a commit. */
    public static List<UUID> commitWatchers(String filmId, String commitId) {
        Map<UUID, String> selections = filmId == null ? null : commitSelections.get(filmId);

        if (selections == null || commitId == null || commitId.isEmpty()) {
            return Collections.emptyList();
        }

        ArrayList<UUID> watchers = new ArrayList<>();

        for (Map.Entry<UUID, String> entry : selections.entrySet()) {
            if (commitId.equals(entry.getValue())) {
                watchers.add(entry.getKey());
            }
        }

        return watchers;
    }

    /** Players whose current branch head is this commit. */
    public static List<UUID> commitPositions(String filmId, String commitId) {
        Map<UUID, String> positions = filmId == null ? null : playerCommitPositions.get(filmId);

        if (positions == null || commitId == null || commitId.isEmpty()) {
            return Collections.emptyList();
        }

        ArrayList<UUID> players = new ArrayList<>();

        for (Map.Entry<UUID, String> entry : positions.entrySet()) {
            if (commitId.equals(entry.getValue())) {
                players.add(entry.getKey());
            }
        }

        return players;
    }

    /** Player name for the face renderer's offline fallback; empty when unknown. */
    public static String playerName(UUID uuid) {
        try {
            MinecraftClient mc = MinecraftClient.getInstance();

            if (uuid == null || mc.getNetworkHandler() == null) {
                return "";
            }

            net.minecraft.client.network.PlayerListEntry entry = mc.getNetworkHandler().getPlayerListEntry(uuid);

            return entry == null ? "" : entry.getProfile().getName();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Queue a colour picked in the graph. The colour picker fires its callback on every drag
     * step, and each send costs the server an index write plus a broadcast, so the actual
     * packet leaves in {@link #tickCommitColors()} once the user has been quiet for a moment.
     */
    public static void queueCommitColor(String filmId, String commitId, int color) {
        if (!isBBSTogetherOnServer || filmId == null || commitId == null || MinecraftClient.getInstance().player == null) {
            return;
        }
        pendingCommitColors.put(filmId + '\t' + commitId, new PendingCommitColor(filmId, commitId, Colors.opaque(color)));
        pendingCommitColorAt = System.currentTimeMillis();
    }

    /** Flush the last queued colour of each commit after the picker has been quiet. */
    public static void tickCommitColors() {
        if (pendingCommitColors.isEmpty() || System.currentTimeMillis() - pendingCommitColorAt < COMMIT_COLOR_DEBOUNCE_MS) {
            return;
        }

        ArrayList<PendingCommitColor> batch = new ArrayList<>(pendingCommitColors.values());

        pendingCommitColors.clear();

        for (PendingCommitColor pending : batch) {
            sendCommitColor(pending.filmId, pending.commitId, pending.color);
        }
    }

    public static void sendCommitColor(String filmId, String commitId, int color) {
        if (!isBBSTogetherOnServer || filmId == null || commitId == null || MinecraftClient.getInstance().player == null) {
            return;
        }
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeString(filmId);
        buf.writeString(commitId);
        buf.writeInt(color);
        ClientPlayNetworking.send((Identifier)TogetherServerNetwork.SERVER_COMMIT_COLOR, (PacketByteBuf)buf);
    }

    /** One colour waiting for the debounce in {@link #tickCommitColors()}. */
    private static final class PendingCommitColor {
        final String filmId;
        final String commitId;
        final int color;

        PendingCommitColor(String filmId, String commitId, int color) {
            this.filmId = filmId;
            this.commitId = commitId;
            this.color = color;
        }
    }

    public static void sendCommitRevert(String filmId, String commitId, int mode) {
        if (!isBBSTogetherOnServer || filmId == null || commitId == null || MinecraftClient.getInstance().player == null) {
            return;
        }
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeString(filmId);
        buf.writeString(commitId);
        buf.writeByte(mode);
        ClientPlayNetworking.send((Identifier)TogetherServerNetwork.SERVER_COMMIT_REVERT, (PacketByteBuf)buf);
    }

    public static void sendCommitCreate(String filmId, String message, String branch, Film film) {
        if (!isBBSTogetherOnServer || filmId == null || MinecraftClient.getInstance().player == null) {
            return;
        }
        /* The editor's live state travels with the commit. BBS writes films to disk on its own
         * schedule, so the copy the server would load can still be the previous commit's, and
         * committing that either fails the equality check or records what the author never saw. */
        boolean live = film != null && filmId.equals(film.getId());
        byte[] payload = live ? DataStorageUtils.writeToBytes(film.toData()) : new byte[0];
        sendCrusher((PlayerEntity)MinecraftClient.getInstance().player, TogetherServerNetwork.SERVER_COMMIT_CREATE, payload, buf -> {
            buf.writeString(filmId);
            buf.writeString(message == null ? "" : message);
            buf.writeString(branch == null ? "" : branch);
        });
    }

    public static void sendBranchCreate(String filmId, String name, String commitId) {
        if (!isBBSTogetherOnServer || filmId == null || name == null || MinecraftClient.getInstance().player == null) {
            return;
        }
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeString(filmId);
        buf.writeString(name);
        buf.writeString(commitId == null ? "" : commitId);
        ClientPlayNetworking.send((Identifier)TogetherServerNetwork.SERVER_BRANCH_CREATE, (PacketByteBuf)buf);
    }

    public static void sendBranchCheckout(String filmId, String branch) {
        if (!isBBSTogetherOnServer || filmId == null || branch == null || MinecraftClient.getInstance().player == null) {
            return;
        }
        pendingBranchCheckouts.put(filmId, branch + '\t' + System.currentTimeMillis());
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeString(filmId);
        buf.writeString(branch);
        ClientPlayNetworking.send((Identifier)TogetherServerNetwork.SERVER_BRANCH_CHECKOUT, (PacketByteBuf)buf);
    }

    private static void handleCommitIndexPacket(MinecraftClient client, PacketByteBuf buf) {
        crusher.receive(buf, (bytes, packetByteBuf) -> {
            BaseType data = DataStorageUtils.readFromBytes((byte[])bytes);
            if (!data.isMap()) {
                return;
            }
            client.execute(() -> FilmCommitClient.setIndex(data.asMap()));
        });
    }

    private static void handleCommitsPacket(MinecraftClient client, PacketByteBuf buf) {
        crusher.receive(buf, (bytes, packetByteBuf) -> {
            String filmId = packetByteBuf.readString();
            String branch = packetByteBuf.isReadable() ? packetByteBuf.readString(FilmCommit.MAX_BRANCH * 2) : "";
            BaseType data = DataStorageUtils.readFromBytes((byte[])bytes);
            if (!data.isMap()) {
                return;
            }
            client.execute(() -> {
                FilmCommitClient.setGraph(filmId, data.asMap());
                if (branch != null && !branch.isEmpty()) {
                    FilmCommitClient.setCurrentBranch(filmId, branch);
                    TogetherClientNetwork.setFilmBranch(filmId, branch);
                }
                TogetherClientNetwork.migrateLegacyCommitColors(filmId);
            });
        });
    }

    private static void handleCommitAddedPacket(MinecraftClient client, PacketByteBuf buf) {
        crusher.receive(buf, (bytes, packetByteBuf) -> {
            String filmId = packetByteBuf.readString();
            BaseType data = DataStorageUtils.readFromBytes((byte[])bytes);
            if (!data.isMap()) {
                return;
            }
            client.execute(() -> FilmCommitClient.addCommit(filmId, data.asMap()));
        });
    }

    private static void handleCommitDiffPacket(MinecraftClient client, PacketByteBuf buf) {
        crusher.receive(buf, (bytes, packetByteBuf) -> {
            String filmId = packetByteBuf.readString();
            String commitId = packetByteBuf.readString();
            BaseType data = DataStorageUtils.readFromBytes((byte[])bytes);
            if (!data.isMap()) {
                return;
            }
            client.execute(() -> FilmCommitClient.setDiff(filmId, commitId, data.asMap()));
        });
    }

    private static void handleCommitHeadPacket(MinecraftClient client, PacketByteBuf buf) {
        crusher.receive(buf, (bytes, packetByteBuf) -> {
            String filmId = packetByteBuf.readString();
            String head = packetByteBuf.readString();
            BaseType data = DataStorageUtils.readFromBytes((byte[])bytes);
            if (!data.isMap()) {
                return;
            }
            client.execute(() -> FilmCommitClient.setHead(filmId, head, data.asMap()));
        });
    }

    private static void handleCommitRenamedPacket(MinecraftClient client, PacketByteBuf buf) {
        String filmId = buf.readString();
        String commitId = buf.readString();
        String message = buf.readString(1024);
        client.execute(() -> FilmCommitClient.renameCommit(filmId, commitId, message));
    }

    private static void handleCommitColorPacket(MinecraftClient client, PacketByteBuf buf) {
        String filmId = buf.readString(512);
        String commitId = buf.readString(64);
        int color = buf.readInt();

        client.execute(() -> {
            FilmCommitClient.setColor(filmId, commitId, color);
            /* Mirror it into the legacy local file: it is the fallback shown while an older
             * server (or a commit that predates server-side colours) has no colour. */
            CommitColors.set(filmId, commitId, color);
        });
    }

    private static void handleCommitSelectionsPacket(MinecraftClient client, PacketByteBuf buf) {
        String filmId = buf.readString(512);
        int count = buf.readInt();
        HashMap<UUID, String> selections = new HashMap<>();

        for (int i = 0; i < count && i < 256; ++i) {
            selections.put(buf.readUuid(), buf.readString(64));
        }

        client.execute(() -> {
            if (selections.isEmpty()) {
                commitSelections.remove(filmId);
            } else {
                commitSelections.put(filmId, new ConcurrentHashMap<>(selections));
            }
        });
    }

    private static void handlePlayerCommitPositionsPacket(MinecraftClient client, PacketByteBuf buf) {
        String filmId = buf.readString(512);
        int count = buf.readInt();
        HashMap<UUID, String> positions = new HashMap<>();

        for (int i = 0; i < count && i < 256; ++i) {
            positions.put(buf.readUuid(), buf.readString(64));
        }

        client.execute(() -> {
            if (positions.isEmpty()) {
                playerCommitPositions.remove(filmId);
            } else {
                playerCommitPositions.put(filmId, new ConcurrentHashMap<>(positions));
            }
        });
    }

    /**
     * Colours picked before they moved server-side still sit in
     * {@code config/bbs_together_commit_colors.json}. Push each one once, but only while the
     * server still shows the commit as uncoloured: a colour that another player already set
     * is the shared truth and must not be overwritten by an old local file.
     */
    private static void migrateLegacyCommitColors(String filmId) {
        FilmCommitClient.Graph graph = FilmCommitClient.graph(filmId);

        if (graph == null) {
            return;
        }

        for (FilmCommit commit : graph.commits) {
            if (commit.color != 0) {
                continue;
            }

            int local = CommitColors.get(filmId, commit.id);

            if (local == CommitColors.DEFAULT) {
                continue;
            }

            if (migratedCommitColors.add(filmId + '\t' + commit.id)) {
                sendCommitColor(filmId, commit.id, local);
            }
        }
    }

    private static void handleCommitRejectedPacket(MinecraftClient client, PacketByteBuf buf) {
        int reason = buf.readByte();
        client.execute(() -> CommitUi.showRejected(reason));
    }

    private static UIDashboard getDashboardIfOpen() {
        UIBaseMenu menu = UIScreen.getCurrentMenu();
        return menu instanceof UIDashboard ? (UIDashboard)menu : null;
    }

    private static UIDashboard getDashboardIfExists() {
        return BBSModClient.getDashboard();
    }

    /** The server refused our undo/redo (someone edited the same subtree after us): apply
     * the server's current data for every touched path —which reverts the local rollback —     * and tell the player why. */
    private static void handleUndoRejectedPacket(MinecraftClient client, byte[] payload) {
        if (payload == null) {
            return;
        }
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBytes(payload);
        String filmId = buf.readString();
        int count = buf.readInt();
        ArrayList<List<String>> paths = new ArrayList<List<String>>();
        ArrayList<BaseType> datas = new ArrayList<BaseType>();
        for (int i = 0; i < count; ++i) {
            int pathSize = buf.readInt();
            ArrayList<String> path = new ArrayList<String>();
            for (int j = 0; j < pathSize; ++j) {
                path.add(buf.readString());
            }
            int len = buf.readInt();
            byte[] bytes = new byte[len];
            buf.readBytes(bytes);
            BaseType data = DataStorageUtils.readFromBytes(bytes);
            if (data == null) {
                continue;
            }
            paths.add(path);
            datas.add(data);
        }
        client.execute(() -> {
            for (int i = 0; i < paths.size(); ++i) {
                together_applyIncomingFilmDataSync(filmId, paths.get(i), datas.get(i));
            }
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc != null && mc.inGameHud != null) {
                mc.inGameHud.setOverlayMessage(Text.literal("§e撤销未生效：该区域刚被其他玩家修改，已同步为最新内容"), false);
            }
        });
    }

    private static void handleFilmDataSyncPacket(MinecraftClient client, byte[] payload, PacketByteBuf buf) {
        if (payload == null) {
            return;
        }
        String filmId = buf.readString();
        String branch = buf.readString(FilmCommit.MAX_BRANCH * 2);
        int pathSize = buf.readInt();
        ArrayList<String> path = new ArrayList<String>();
        for (int i = 0; i < pathSize; ++i) {
            path.add(buf.readString());
        }
        BaseType data = DataStorageUtils.readFromBytes(payload);
        if (data == null) {
            return;
        }
        client.execute(() -> {
            String known = TogetherClientNetwork.filmBranch(filmId);
            if (known.isEmpty()) {
                /* Wait for the branch's full film: a delta before it would be applied to
                 * whatever film happens to be loaded locally. */
                return;
            }
            if (!known.equals(branch)) {
                /* In-flight delta from the branch we just left (or the new one arriving
                 * before its full film). */
                return;
            }
            TogetherClientNetwork.together_applyIncomingFilmDataSync(filmId, path, data);
        });
    }

    private static boolean applyAtPath(BaseValueGroup current, List<String> path, int depth, BaseType data) {
        if (depth >= path.size()) {
            return false;
        }
        String segment = path.get(depth);
        BaseValue child = current.get(segment);
        if (child == null) {
            if (current instanceof FormProperties) {
                FormProperties formProps = (FormProperties)current;
                KeyframeChannel channel = new KeyframeChannel(segment, null);
                channel.fromData(data);
                if (channel.getFactory() != null) {
                    formProps.tracks.put(TrackId.parse(segment), channel);
                    formProps.add((BaseValue)channel);
                    return true;
                }
            } else if (current instanceof Clips clips) {
                /* Clips.fromData 只接受 ListType；本地还没有对应 clip 时，仅当 data 是带
                 * type 的完整 clip MapType 才用工厂造一个，否则丢弃补丁，避免 MapType 回灌 CCE。*/
                if (data.isMap()) {
                    MapType map = data.asMap();
                    if (!map.getString("type").isEmpty()) {
                        try {
                            Clip clip = clips.getFactory().fromData(map);
                            if (clip != null) {
                                clips.addClip(clip);
                                return true;
                            }
                        } catch (Exception ignored) {}
                    }
                }
                return false;
            } else {
                BaseType wrapped = data;
                for (int j = path.size() - 1; j >= depth; --j) {
                    MapType wrapper = new MapType();
                    wrapper.put(path.get(j), wrapped);
                    wrapped = wrapper;
                }
                current.fromData(wrapped);
                return true;
            }
            return false;
        }
        if (depth == path.size() - 1) {
            child.fromData(data);
            return true;
        }
        if (child instanceof BaseValueGroup) {
            BaseValueGroup group = (BaseValueGroup)child;
            return TogetherClientNetwork.applyAtPath(group, path, depth + 1, data);
        }
        return false;
    }

    private static void together_applyIncomingFilmDataSync(String filmId, List<String> path, BaseType data) {
        UIDashboard dashboard = getDashboardIfOpen();
        UIFilmPanel panel = dashboard == null ? null : (UIFilmPanel)dashboard.getPanel(UIFilmPanel.class);
        if (panel == null || panel.getData() == null || !((Film)panel.getData()).getId().equals(filmId)) {
            return;
        }
        if (together_wouldClearLocalCamera((Film)panel.getData(), path, data)) {
            return;
        }
        Film film = (Film)panel.getData();
        int currentIdx = together_getCurrentReplayIndex(panel, film);
        List<Replay> snapshot = new ArrayList<Replay>(film.replays.getList());
        boolean structureSync = together_isReplayStructurePath(path);
        int offset = together_applyPathOffset(path);
        boolean applied;
        BBSTogetherFix22.beginRemoteSyncApply();
        try {
            applied = applyAtPath((BaseValueGroup)panel.getData(), path, offset, data);
        }
        finally {
            BBSTogetherFix22.endRemoteSyncApply();
        }
        if (!applied) {
            return;
        }
        if (structureSync) {
            together_restoreReplayAfterStructureSync(panel, snapshot, currentIdx);
            return;
        }
        if (together_isKeyframeOnlySyncPath(path)) {
            together_refreshEmbeddedClipKeyframeEditor(panel, path);
            return;
        }
        panel.fillData();
        together_updateReplayList(panel);
        panel.getController().createEntities();
        Replay currentReplay = panel.replayEditor.getReplay();
        if (together_isCurrentReplayPath(panel, path, currentReplay) && together_shouldUpdateChannels(path)) {
            panel.replayEditor.updateChannelsList();
        }
        if (together_isReplayPath(path) && currentReplay != null) {
            panel.replayEditor.setReplay(currentReplay);
        }
        Replay openReplay = panel.replayEditor.getReplay();
        int newIdx = BBSTogetherFix45.identityReplayIndex(((Film)panel.getData()).replays.getList(), openReplay);
        sendReplaySelection(((Film)panel.getData()).getId(), newIdx);
    }

    private static void together_setLocalReplaySelection(String filmId, int replayIndex) {
        UUID playerId = together_localPlayerUuid();
        if (playerId == null) {
            return;
        }
        if (filmId == null || filmId.isEmpty()) {
            for (Map<Integer, List<UUID>> selections : replaySelections.values()) {
                together_removePlayerFromReplaySelections(selections, playerId);
            }
            return;
        }
        Map<Integer, List<UUID>> selections = replaySelections.computeIfAbsent(filmId, key -> new HashMap<Integer, List<UUID>>());
        together_removePlayerFromReplaySelections(selections, playerId);
        if (replayIndex >= 0) {
            List<UUID> players = selections.computeIfAbsent(replayIndex, idx -> new ArrayList<UUID>());
            if (!players.contains(playerId)) {
                players.add(playerId);
            }
            filmSelections.put(playerId, filmId);
        }
    }

    private static void together_removePlayerFromReplaySelections(Map<Integer, List<UUID>> selections, UUID playerId) {
        if (selections == null || playerId == null) {
            return;
        }
        for (List<UUID> players : selections.values()) {
            players.removeIf(playerId::equals);
        }
        selections.entrySet().removeIf(entry -> ((List)entry.getValue()).isEmpty());
    }

    private static UUID together_localPlayerUuid() {
        return MinecraftClient.getInstance().player == null ? null : MinecraftClient.getInstance().player.getUuid();
    }

    private static boolean together_isReplayPath(List<String> path) {
        return together_replayPathIndex(path) >= 0 && path.size() > together_replayPathIndex(path) + 1;
    }

    private static boolean together_isReplayStructurePath(List<String> path) {
        int idx = together_replayPathIndex(path);
        return idx >= 0 && path.size() <= idx + 2;
    }

    private static int together_applyPathOffset(List<String> path) {
        return path != null && !path.isEmpty() && "replays".equals(path.get(0)) ? 0 : 1;
    }

    private static int together_replayPathIndex(List<String> path) {
        if (path == null) {
            return -1;
        }
        for (int i = 0; i < path.size(); ++i) {
            if (!"replays".equals(path.get(i))) continue;
            return i;
        }
        return -1;
    }

    private static void together_restoreReplayAfterStructureSync(UIFilmPanel panel, List<Replay> snapshot, int previousIdx) {
        if (panel == null || panel.getData() == null || panel.replayEditor == null) {
            return;
        }
        Film film = (Film)panel.getData();
        List<Replay> replays = film.replays.getList();
        int remappedIdx = together_remapReplayIndex(snapshot, replays, previousIdx);
        together_remapClientReplaySelections(film.getId(), snapshot, replays);
        if (remappedIdx < 0 && !replays.isEmpty()) {
            remappedIdx = Math.min(Math.max(previousIdx, 0), replays.size() - 1);
        }
        panel.fillData();
        if (panel.replayEditor.replaysList != null && panel.replayEditor.replaysList.replays != null) {
            panel.replayEditor.replaysList.replays.refreshReplayList();
            panel.replayEditor.replaysList.replays.update();
        }
        panel.getController().createEntities();
        if (replays.isEmpty()) {
            panel.replayEditor.setReplay(null);
            together_setLocalReplaySelection(film.getId(), -1);
            sendReplaySelection(film.getId(), -1);
            return;
        }
        Replay replay = (Replay)replays.get(remappedIdx);
        panel.replayEditor.setReplay(replay);
        if (panel.replayEditor.replaysList != null && panel.replayEditor.replaysList.replays != null) {
            panel.replayEditor.replaysList.replays.scrollToReplay(replay);
        }
        together_setLocalReplaySelection(film.getId(), remappedIdx);
        sendReplaySelection(film.getId(), remappedIdx);
        BBSTogetherFix33Client.reannounceReplaySelectionAfterFullSync(panel);
    }

    private static Film together_getOpenOrStoredFilm(UIFilmPanel panel, String filmId) {
        if (panel != null && panel.getData() != null && ((Film)panel.getData()).getId().equals(filmId)) {
            return (Film)panel.getData();
        }
        return (Film)BBSMod.getFilms().load(filmId);
    }

    private static void together_saveReceivedFilm(String filmId, MapType data) {
        BBSMod.getFilms().save(filmId, data);
        try {
            ContentType.FILMS.getRepository().save(filmId, data);
        }
        catch (Exception exception) {
            exception.printStackTrace();
        }
    }

    private static void together_applyHiddenFilmDataUpdate(UIFilmPanel panel, MapType data) {
        Film film = (Film)panel.getData();
        int currentIdx = together_getCurrentReplayIndex(panel, film);
        Film incoming = new Film();
        incoming.fromData((BaseType)data);
        int remappedIdx = together_remapReplayIndex(film.replays.getList(), incoming.replays.getList(), currentIdx);
        BBSTogetherFix22.beginRemoteSyncApply();
        try {
            film.fromData(incoming.toData());
            if (panel.replayEditor != null && remappedIdx >= 0 && remappedIdx < film.replays.getList().size()) {
                panel.replayEditor.setReplay((Replay)film.replays.getList().get(remappedIdx));
            }
            panel.getController().createEntities();
        }
        finally {
            BBSTogetherFix22.endRemoteSyncApply();
        }
    }

    private static void together_notifyFilmSyncApplied(UIFilmPanel panel, String filmId) {
        UIContext context = panel.getContext();
        if (context == null) {
            return;
        }
        context.notifySuccess(UIKeys.PANELS_SAVED_NOTIFICATION.format(new Object[]{filmId}));
    }

    private static boolean together_isKeyframeOnlySyncPath(List<String> path) {
        if (path.size() <= 1) {
            return false;
        }
        String section = path.get(1);
        if ("replays".equals(section)) {
            if (path.size() >= 5) {
                return true;
            }
            return path.size() == 4 && "keyframes".equals(path.get(3));
        }
        if ("camera".equals(section) || "action".equals(section) || "actions".equals(section)) {
            return together_isClipKeyframeOnlyPath(path);
        }
        return false;
    }

    private static boolean together_isCurrentReplayPath(UIFilmPanel panel, List<String> path, Replay replay) {
        if (!together_isReplayPath(path) || replay == null) {
            return false;
        }
        int idx = BBSTogetherFix45.identityReplayIndex(((Film)panel.getData()).replays.getList(), replay);
        int pathIdx = together_replayPathIndex(path);
        return idx >= 0 && pathIdx >= 0 && path.size() > pathIdx + 1 && String.valueOf(idx).equals(path.get(pathIdx + 1));
    }

    private static boolean together_shouldUpdateChannels(List<String> path) {
        if (path.size() == 3) {
            return true;
        }
        if (path.size() != 4) {
            return false;
        }
        String segment = path.get(3);
        return "form".equals(segment) || "properties".equals(segment);
    }

    private static void together_updateReplayList(UIFilmPanel panel) {
        if (panel.replayEditor.replaysList != null && panel.replayEditor.replaysList.replays != null) {
            panel.replayEditor.replaysList.replays.update();
        }
    }

    private static int together_getCurrentReplayIndex(UIFilmPanel panel, Film film) {
        if (panel == null || panel.replayEditor == null || film == null) {
            return -1;
        }
        Replay replay = panel.replayEditor.getReplay();
        int idx = BBSTogetherFix45.identityReplayIndex(film.replays.getList(), replay);
        if (idx >= 0) {
            return idx;
        }
        if (panel.replayEditor.replaysList != null && panel.replayEditor.replaysList.replays != null) {
            Replay current = panel.replayEditor.replaysList.replays.getSelectedReplayFirst();
            return current == null ? -1 : ((Film)panel.getData()).replays.getList().indexOf(current);
        }
        return -1;
    }

    private static void together_restoreReplaySelection(UIFilmPanel panel, List<Replay> replays, int idx) {
        if (panel == null || panel.replayEditor == null || replays == null || replays.isEmpty()) {
            return;
        }
        int target = idx >= 0 && idx < replays.size() ? idx : 0;
        Replay replay = replays.get(target);
        panel.replayEditor.setReplay(replay);
        if (panel.replayEditor.replaysList != null && panel.replayEditor.replaysList.replays != null) {
            panel.replayEditor.replaysList.replays.scrollToReplay(replay);
        }
    }

    private static boolean together_wouldClearLocalCamera(Film film, MapType data) {
        BaseType camera = data.get("camera");
        if (camera == null || !camera.isList()) {
            return false;
        }
        return together_wouldClearLocalCamera(film, camera);
    }

    private static boolean together_wouldClearLocalCamera(Film film, List<String> path, BaseType data) {
        if (path.size() != 2 || !"camera".equals(path.get(1))) {
            return false;
        }
        return together_wouldClearLocalCamera(film, data);
    }

    private static boolean together_wouldClearLocalCamera(Film film, BaseType data) {
        return film != null && data != null && data.isList() && together_clipCount(film.camera) > 0 && data.asList().size() == 0;
    }

    private static int together_clipCount(Clips clips) {
        return clips == null || clips.get() == null ? 0 : clips.get().size();
    }

    private static boolean together_isClipKeyframeOnlyPath(List<String> path) {
        int idx = path.indexOf("channels");
        if (idx >= 0 && path.size() > idx + 1) {
            return true;
        }
        for (String segment : path) {
            if (!"keyframes".equals(segment) && !"channel".equals(segment) && !"x".equals(segment) && !"y".equals(segment) && !"z".equals(segment) && !"yaw".equals(segment) && !"pitch".equals(segment) && !"roll".equals(segment) && !"fov".equals(segment) && !"distance".equals(segment)) continue;
            return true;
        }
        return false;
    }

    private static void together_refreshEmbeddedClipKeyframeEditor(UIFilmPanel panel, List<String> path) {
        if (path.size() <= 1) {
            return;
        }
        String section = path.get(1);
        if (!("camera".equals(section) || "action".equals(section) || "actions".equals(section))) {
            return;
        }
        UIKeyframeEditor keyframeEditor = "camera".equals(section) ? BBSTogetherFix14.getCameraKeyframeEditor(panel) : null;
        if (keyframeEditor != null && keyframeEditor.editor != null) {
            keyframeEditor.editor.update();
        }
    }

    private static void together_remapClientReplaySelections(String filmId, Film oldFilm, Film newFilm) {
        if (filmId == null || filmId.isEmpty() || oldFilm == null || newFilm == null) {
            return;
        }
        together_remapClientReplaySelections(filmId, oldFilm.replays.getList(), newFilm.replays.getList());
    }

    private static void together_remapClientReplaySelections(String filmId, List<Replay> oldReplays, List<Replay> newReplays) {
        if (filmId == null || filmId.isEmpty() || oldReplays == null || newReplays == null) {
            return;
        }
        if (oldReplays.size() == newReplays.size()) {
            return;
        }
        Map<Integer, List<UUID>> selections = replaySelections.get(filmId);
        if (selections == null || selections.isEmpty()) {
            return;
        }
        HashMap<Integer, List<UUID>> remapped = new HashMap<Integer, List<UUID>>();
        for (Map.Entry<Integer, List<UUID>> entry : selections.entrySet()) {
            int idx = together_remapReplayIndex(oldReplays, newReplays, entry.getKey());
            if (idx < 0) {
                if (newReplays.isEmpty()) continue;
                idx = Math.min(Math.max(entry.getKey(), 0), newReplays.size() - 1);
            }
            remapped.computeIfAbsent(idx, key -> new ArrayList<UUID>()).addAll((Collection<UUID>)entry.getValue());
        }
        replaySelections.put(filmId, remapped);
    }

    private static int together_remapReplayIndex(List<Replay> oldReplays, List<Replay> newReplays, int idx) {
        if (idx < 0 || newReplays == null || newReplays.isEmpty()) {
            return -1;
        }
        if (oldReplays == null || oldReplays.isEmpty()) {
            return Math.min(idx, newReplays.size() - 1);
        }
        int oldCount = oldReplays.size();
        int newCount = newReplays.size();
        if (newCount == oldCount + 1) {
            int changed = together_findChangedReplayIndex(oldReplays, newReplays);
            return idx >= changed ? idx + 1 : idx;
        }
        if (newCount == oldCount - 1) {
            int changed = together_findChangedReplayIndex(newReplays, oldReplays);
            if (idx > changed) {
                return idx - 1;
            }
            if (idx == changed) {
                return Math.min(changed, newCount - 1);
            }
            return Math.min(idx, newCount - 1);
        }
        return Math.min(idx, newCount - 1);
    }

    private static int together_findChangedReplayIndex(List<Replay> oldReplays, List<Replay> newReplays) {
        int count = Math.min(oldReplays.size(), newReplays.size());
        for (int i = 0; i < count; ++i) {
            if (together_replaySignature(oldReplays.get(i)).equals(together_replaySignature(newReplays.get(i)))) continue;
            return i;
        }
        return count;
    }

    private static String together_replaySignature(Replay replay) {
        return replay == null ? "" : replay.toData().toString();
    }

    private static List<Integer> together_copyClipSelection(UIClipsPanel clipsPanel) {
        if (clipsPanel == null || clipsPanel.clips == null) {
            return new ArrayList<Integer>();
        }
        return new ArrayList<Integer>(clipsPanel.clips.getSelection());
    }

    private static void together_restoreClipSelection(UIClipsPanel clipsPanel, List<Integer> selection) {
        if (clipsPanel == null || clipsPanel.clips == null || selection.isEmpty()) {
            return;
        }
        UIClips clips = clipsPanel.clips;
        clips.setSelection(selection);
        List clipList = clips.getClips() == null ? Collections.emptyList() : clips.getClips().get();
        int first = selection.get(0);
        if (first >= 0 && first < clipList.size()) {
            clips.pickClip((Clip)clipList.get(first));
        }
    }

    private static Map<String, List<Integer>> together_copyKeyframeSelection(UIFilmPanel panel) {
        HashMap<String, List<Integer>> saved = new HashMap<String, List<Integer>>();
        UIKeyframeEditor keyframeEditor = panel.replayEditor.keyframeEditor;
        if (keyframeEditor == null || keyframeEditor.view == null) {
            return saved;
        }
        IUIKeyframeGraph graph = keyframeEditor.view.getGraph();
        if (!(graph instanceof UIKeyframeDopeSheet)) {
            return saved;
        }
        UIKeyframeDopeSheet dopeSheet = (UIKeyframeDopeSheet)graph;
        for (UIKeyframeSheet sheet : dopeSheet.getSheets()) {
            if (sheet.selection == null || sheet.selection.getIndices().isEmpty()) continue;
            saved.put(sheet.id, new ArrayList(sheet.selection.getIndices()));
        }
        return saved;
    }

    private static void together_restoreKeyframeSelection(UIFilmPanel panel, Map<String, List<Integer>> saved) {
        if (saved.isEmpty()) {
            return;
        }
        UIKeyframeEditor keyframeEditor = panel.replayEditor.keyframeEditor;
        if (keyframeEditor == null || keyframeEditor.view == null) {
            return;
        }
        IUIKeyframeGraph graph = keyframeEditor.view.getGraph();
        if (!(graph instanceof UIKeyframeDopeSheet)) {
            return;
        }
        UIKeyframeDopeSheet dopeSheet = (UIKeyframeDopeSheet)graph;
        for (UIKeyframeSheet sheet : dopeSheet.getSheets()) {
            List<Integer> indices = saved.get(sheet.id);
            if (indices == null || sheet.selection == null) continue;
            for (Integer index : indices) {
                sheet.selection.add(index.intValue());
            }
        }
    }

    @Environment(value=EnvType.CLIENT)
    public static class CursorPosition {
        public int editorType;
        public float targetTick;
        public float currentTick;
        public float targetRow;
        public float currentRow;
        public List<Integer> selectedIndices = new ArrayList<Integer>();
        public int playheadTick;

        public CursorPosition(int editorType, float tick, float row, List<Integer> selected) {
            this.editorType = editorType;
            this.targetTick = tick;
            this.currentTick = tick;
            this.targetRow = row;
            this.currentRow = row;
            this.selectedIndices = new ArrayList<Integer>(selected);
        }

        public void updateTarget(int editorType, float tick, float row, List<Integer> selected, int playheadTick) {
            this.editorType = editorType;
            this.targetTick = tick;
            this.targetRow = row;
            this.selectedIndices = new ArrayList<Integer>(selected);
            this.playheadTick = playheadTick;
        }

        public void interpolate(float delta) {
            float factor = Math.min(1.0f, 8.0f * delta);
            this.currentTick += (this.targetTick - this.currentTick) * factor;
            this.currentRow += (this.targetRow - this.currentRow) * factor;
        }
    }

    private static void sendAssetRequestChunks(List<String> list) {
        ArrayList<String> arrayList = new ArrayList<String>();
        int n = 4;
        for (String string : list) {
            TogetherClientNetwork.watchDownload(string);
            int n2 = stringPacketBytes(string);
            if (!arrayList.isEmpty() && n + n2 > 28000) {
                sendAssetRequestBatch(arrayList);
                arrayList.clear();
                n = 4;
            }
            arrayList.add(string);
            n += n2;
        }
        if (!arrayList.isEmpty()) {
            sendAssetRequestBatch(arrayList);
        }
    }

    private static void sendAssetRequestBatch(List<String> list) {
        PacketByteBuf requestBuf = PacketByteBufs.create();
        requestBuf.writeInt(list.size());
        for (String string : list) {
            requestBuf.writeString(string);
        }
        ClientPlayNetworking.send((Identifier)TogetherServerNetwork.SERVER_ASSET_REQUEST, (PacketByteBuf)requestBuf);
    }

    private static int stringPacketBytes(String string) {
        int n = string.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        return varIntBytes(n) + n;
    }

    private static int varIntBytes(int n) {
        int n2 = 1;
        while ((n & 0xFFFFFF80) != 0) {
            n >>>= 7;
            ++n2;
        }
        return n2;
    }
}