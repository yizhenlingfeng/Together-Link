package mchorse.bbs_together.network;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.data.DataStorageUtils;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.film.replays.tracks.TrackId;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.network.IBufferReceiver;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.settings.values.base.BaseValueGroup;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_together.BBSTogetherFix18;
import mchorse.bbs_together.BBSTogetherFix28Server;
import mchorse.bbs_together.TogetherSettings;
import mchorse.bbs_together.bbsop.BbsOpDomain;
import mchorse.bbs_together.bbsop.BbsOpManager;
import mchorse.bbs_together.behaviors.BehaviorEntry;
import mchorse.bbs_together.behaviors.BehaviorLogManager;
import mchorse.bbs_together.behaviors.BehaviorType;
import mchorse.bbs_together.commits.FilmBranchStore;
import mchorse.bbs_together.commits.FilmCommit;
import mchorse.bbs_together.commits.FilmCommitStore;
import mchorse.bbs_together.locks.AssetLock;
import mchorse.bbs_together.locks.AssetLockManager;
import mchorse.bbs_together.locks.AssetLockMode;
import mchorse.bbs_together.locks.AssetLockType;
import mchorse.bbs_together.network.AssetEgressBudget;
import mchorse.bbs_together.network.CoopSessionManager;
import mchorse.bbs_together.utils.AssetPaths;
import mchorse.bbs_together.utils.ModelPathUtils;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

public class TogetherServerNetwork {
    public static final Identifier SERVER_SYNC_PREF = new Identifier("bbs_together", "s_sync_pref");
    public static final Identifier SERVER_ASSET_REPORT = new Identifier("bbs_together", "s_asset_report");
    public static final Identifier SERVER_ASSET_REPORT_DELTA = new Identifier("bbs_together", "s_asset_report_delta");
    public static final Identifier SERVER_FILM_INVENTORY = new Identifier("bbs_together", "s_film_inventory");
    public static final Identifier CLIENT_PLAYER_COLORS = new Identifier("bbs_together", "c_player_colors");
    public static final Identifier CLIENT_CURSOR_POSITIONS = new Identifier("bbs_together", "c_cursor_positions");
    public static final Identifier CLIENT_FILM_SELECTIONS = new Identifier("bbs_together", "c_film_selections");
    public static final Identifier CLIENT_REPLAY_SELECTIONS = new Identifier("bbs_together", "c_replay_selections");
    public static final Identifier CLIENT_REPLAY_FORM_LOCKS = new Identifier("bbs_together", "c_replay_form_locks");
    public static final Identifier CLIENT_REPLAY_SWAP = new Identifier("bbs_together", "c_replay_swap");
    public static final Identifier CLIENT_TOGGLE_RECORDING = new Identifier("bbs_together", "c_toggle_recording");
    public static final Identifier CLIENT_ASSET_MANIFEST = new Identifier("bbs_together", "c_asset_manifest");
    public static final Identifier CLIENT_ASSET_HINT = new Identifier("bbs_together", "c_asset_hint");
    public static final Identifier CLIENT_ASSET_FILE = new Identifier("bbs_together", "c_asset_file");
    public static final Identifier CLIENT_FILM_SCREENS = new Identifier("bbs_together", "c_film_screens");
    public static final Identifier CLIENT_MERGE_REPLAYS = new Identifier("bbs_together", "c_merge_replays");
    public static final Identifier CLIENT_FILM_BRANCH_STATE = new Identifier("bbs_together", "c_film_branch_state");
    /** Tiny packet that only names the branch, used when the full branch data was already sent. */
    public static final Identifier CLIENT_FILM_BRANCH = new Identifier("bbs_together", "c_film_branch");
    public static final Identifier CLIENT_FILM_SAVED_SYNC = new Identifier("bbs_together", "c_film_saved_sync");
    public static final Identifier SERVER_UNDO_SYNC = new Identifier("bbs_together", "s_undo_sync");
    public static final Identifier CLIENT_UNDO_REJECTED = new Identifier("bbs_together", "c_undo_rejected");
    public static final Identifier CLIENT_FILM_DATA_SYNC = new Identifier("bbs_together", "c_film_data_sync");
    public static final Identifier CLIENT_FILM_LIST_CHANGED = new Identifier("bbs_together", "c_film_list_changed");
    public static final Identifier CLIENT_ASSETS_CHANGED = new Identifier("bbs_together", "c_assets_changed");
    public static final Identifier SERVER_FILM_LIST_CHANGED = new Identifier("bbs_together", "s_film_list_changed");
    public static final Identifier CLIENT_FILM_DELETED = new Identifier("bbs_together", "c_film_deleted");
    public static final Identifier SERVER_FILM_DELETED = new Identifier("bbs_together", "s_film_deleted");
    public static final Identifier CLIENT_FILM_RENAMED = new Identifier("bbs_together", "c_film_renamed");
    public static final Identifier SERVER_FILM_RENAMED = new Identifier("bbs_together", "s_film_renamed");
    public static final Identifier SERVER_PLAYER_COLOR = new Identifier("bbs_together", "s_player_color");
    public static final Identifier SERVER_CURSOR_POSITION = new Identifier("bbs_together", "s_cursor_position");
    public static final Identifier SERVER_FILM_SELECTION = new Identifier("bbs_together", "s_film_selection");
    public static final Identifier SERVER_REPLAY_SELECTION = new Identifier("bbs_together", "s_replay_selection");
    public static final Identifier SERVER_REPLAY_FORM_LOCK = new Identifier("bbs_together", "s_replay_form_lock");
    public static final Identifier SERVER_REPLAY_SWAP = new Identifier("bbs_together", "s_replay_swap");
    public static final Identifier SERVER_ASSET_REQUEST = new Identifier("bbs_together", "s_asset_request");
    public static final Identifier SERVER_ASSET_UPLOAD = new Identifier("bbs_together", "s_asset_upload");
    public static final Identifier SERVER_ASSET_RESYNC = new Identifier("bbs_together", "s_asset_resync");
    public static final Identifier SERVER_FILM_SCREEN = new Identifier("bbs_together", "s_film_screen");
    public static final Identifier SERVER_RECORDING_FINISHED = new Identifier("bbs_together", "s_recording_finished");
    public static final Identifier SERVER_MASTER_DONE = new Identifier("bbs_together", "s_master_done");
    public static final Identifier SERVER_FILM_DATA_SYNC = new Identifier("bbs_together", "s_film_data_sync");
    public static final Identifier SERVER_FILM_SAVE_SYNC = new Identifier("bbs_together", "s_film_save_sync");
    public static final Identifier SERVER_BEHAVIOR_REPORT = new Identifier("bbs_together", "s_behavior_report");
    public static final Identifier SERVER_BEHAVIOR_QUERY = new Identifier("bbs_together", "s_behavior_query");
    public static final Identifier SERVER_SET_OPERATOR = new Identifier("bbs_together", "s_set_operator");
    public static final Identifier SERVER_REMOVE_PLAYER_LOGS = new Identifier("bbs_together", "s_remove_player_logs");
    public static final Identifier SERVER_MODEL_DELETE_REQUEST = new Identifier("bbs_together", "s_model_delete_request");
    public static final Identifier SERVER_MODEL_DELETE_DECISION = new Identifier("bbs_together", "s_model_delete_decision");
    public static final Identifier CLIENT_MODEL_DELETED = new Identifier("bbs_together", "c_model_deleted");
    public static final Identifier CLIENT_BEHAVIOR_LIST = new Identifier("bbs_together", "c_behavior_list");
    public static final Identifier CLIENT_PERMISSIONS = new Identifier("bbs_together", "c_permissions");
    public static final Identifier CLIENT_BBSOP_SYNC = new Identifier("bbs_together", "c_bbsop_sync");
    public static final Identifier SERVER_BBSOP_SET = new Identifier("bbs_together", "s_bbsop_set");
    public static final Identifier SERVER_BBSOP_GRANT = new Identifier("bbs_together", "s_bbsop_grant");
    public static final Identifier CLIENT_ASSET_LOCKS = new Identifier("bbs_together", "c_asset_locks");
    public static final Identifier SERVER_ASSET_LOCK = new Identifier("bbs_together", "s_asset_lock");
    public static final Identifier SERVER_COOP_INVITE = new Identifier("bbs_together", "s_coop_invite");
    public static final Identifier SERVER_COOP_RESPOND = new Identifier("bbs_together", "s_coop_respond");
    public static final Identifier SERVER_COOP_PICK = new Identifier("bbs_together", "s_coop_pick");
    public static final Identifier SERVER_COOP_LEAVE = new Identifier("bbs_together", "s_coop_leave");
    public static final Identifier SERVER_COOP_START = new Identifier("bbs_together", "s_coop_start");
    public static final Identifier SERVER_COOP_CANCEL = new Identifier("bbs_together", "s_coop_cancel");
    public static final Identifier SERVER_COOP_MOUSE = new Identifier("bbs_together", "s_coop_mouse");
    public static final Identifier CLIENT_COOP_REQUEST = new Identifier("bbs_together", "c_coop_request");
    public static final Identifier CLIENT_COOP_REQUEST_CLOSED = new Identifier("bbs_together", "c_coop_request_closed");
    public static final Identifier CLIENT_COOP_ROOM = new Identifier("bbs_together", "c_coop_room");
    public static final Identifier CLIENT_COOP_END = new Identifier("bbs_together", "c_coop_end");
    public static final Identifier CLIENT_COOP_BEGIN = new Identifier("bbs_together", "c_coop_begin");
    public static final Identifier CLIENT_COOP_MOUSE = new Identifier("bbs_together", "c_coop_mouse");
    public static final Identifier SERVER_COMMITS_QUERY = new Identifier("bbs_together", "s_commits_query");
    public static final Identifier SERVER_COMMIT_INDEX = new Identifier("bbs_together", "s_commit_index");
    public static final Identifier SERVER_COMMIT_DIFF = new Identifier("bbs_together", "s_commit_diff");
    public static final Identifier SERVER_COMMIT_RENAME = new Identifier("bbs_together", "s_commit_rename");
    public static final Identifier SERVER_COMMIT_SELECTION = new Identifier("bbs_together", "s_commit_selection");
    public static final Identifier SERVER_COMMIT_COLOR = new Identifier("bbs_together", "s_commit_color");
    public static final Identifier SERVER_COMMIT_REVERT = new Identifier("bbs_together", "s_commit_revert");
    public static final Identifier SERVER_COMMIT_SQUASH = new Identifier("bbs_together", "s_commit_squash");
    public static final Identifier SERVER_COMMIT_CREATE = new Identifier("bbs_together", "s_commit_create");
    public static final Identifier SERVER_COMMIT_HEAD = new Identifier("bbs_together", "s_commit_head");
    public static final Identifier SERVER_BRANCH_CREATE = new Identifier("bbs_together", "s_branch_create");
    public static final Identifier SERVER_BRANCH_CHECKOUT = new Identifier("bbs_together", "s_branch_checkout");
    public static final Identifier CLIENT_COMMITS = new Identifier("bbs_together", "c_commits");
    public static final Identifier CLIENT_COMMIT_INDEX = new Identifier("bbs_together", "c_commit_index");
    public static final Identifier CLIENT_COMMIT_ADDED = new Identifier("bbs_together", "c_commit_added");
    public static final Identifier CLIENT_COMMIT_DIFF = new Identifier("bbs_together", "c_commit_diff");
    public static final Identifier CLIENT_COMMIT_RENAMED = new Identifier("bbs_together", "c_commit_renamed");
    public static final Identifier CLIENT_COMMIT_COLOR = new Identifier("bbs_together", "c_commit_color");
    public static final Identifier CLIENT_COMMIT_SELECTIONS = new Identifier("bbs_together", "c_commit_selections");
    public static final Identifier CLIENT_PLAYER_COMMIT_POSITIONS = new Identifier("bbs_together", "c_player_commit_positions");
    public static final Identifier CLIENT_COMMIT_REJECTED = new Identifier("bbs_together", "c_commit_rejected");
    public static final Identifier CLIENT_COMMIT_HEAD = new Identifier("bbs_together", "c_commit_head");
    private static final ConcurrentHashMap<UUID, CompressedServerPacketCrusher> playerCrushers = new ConcurrentHashMap();
    private static final LinkedHashMap<UUID, Integer> playerReplaySelections = new LinkedHashMap();
    private static final ConcurrentHashMap<UUID, String> playerEditingFilm = new ConcurrentHashMap();
    private static final ConcurrentHashMap<UUID, String> lastBranchStateSent = new ConcurrentHashMap();
    private static final ConcurrentHashMap<UUID, Long> lastBranchStateAt = new ConcurrentHashMap();
    private static final ConcurrentHashMap<UUID, String> playerInFilmScreen = new ConcurrentHashMap();
    private static final ConcurrentHashMap<UUID, Integer> playerColors = new ConcurrentHashMap();
    private static final Set<String> deletedFilmIds = Collections.synchronizedSet(new HashSet());
    private static final ConcurrentHashMap<String, MapType> lastSavedFilmSnapshots = new ConcurrentHashMap();
    private static final ConcurrentHashMap<UUID, CursorData> playerCursors = new ConcurrentHashMap();
    private static final ConcurrentHashMap<UUID, CommitSelection> playerCommitSelections = new ConcurrentHashMap();
    private static final ConcurrentHashMap<UUID, FormLockData> playerFormLocks = new ConcurrentHashMap();
    private static final ConcurrentHashMap<UUID, Object> pendingStopPlayers = new ConcurrentHashMap();
    private static final ConcurrentHashMap<Integer, MapType> pendingReplayData = new ConcurrentHashMap();
    private static volatile String pendingFilmId = null;
    private static volatile UUID masterPlayerUUID = null;
    private static final ConcurrentHashMap<UUID, LinkedBlockingQueue<AssetSendTask>> assetSendQueues = new ConcurrentHashMap();
    private static final ConcurrentHashMap<UUID, Boolean> recordingPlayers = new ConcurrentHashMap();
    private static final ConcurrentHashMap<UUID, AtomicBoolean> assetDrainActive = new ConcurrentHashMap();
    private static final ConcurrentHashMap<UUID, Boolean> playerReceivePush = new ConcurrentHashMap();
    private static final ConcurrentHashMap<UUID, Map<String, String>> playerOwnedAssets = new ConcurrentHashMap();
    private static final ConcurrentHashMap<String, AtomicBoolean> filmSaveDirty = new ConcurrentHashMap();
    private static final ConcurrentHashMap<String, ScheduledFuture<?>> filmSaveTasks = new ConcurrentHashMap();
    private static final ConcurrentHashMap<String, Long> filmSaveFirstDirty = new ConcurrentHashMap();
    private static final ScheduledExecutorService FILM_SAVE_SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "BBSTogether-FilmSaver");
        t.setDaemon(true);
        return t;
    });
    private static final long FILM_SAVE_DELAY_MS = 5000L;
    /** Continuous editing must not delay persisting forever. */
    private static final long FILM_SAVE_MAX_MS = 15000L;
    /**
     * How long a full branch state counts as already delivered. Opening a film does three
     * requests in quick succession (BBS' manager load, the film selection and the join request);
     * only the first one needs to carry the whole film. The window is short enough that a real
     * re-enter later still resynchronizes from disk.
     */
    private static final long BRANCH_DATA_FRESH_MS = 3000L;
    private static final ConcurrentHashMap<String, HashCacheEntry> assetHashCache = new ConcurrentHashMap();
    private static final AtomicLong assetBytesSent = new AtomicLong();
    private static final AtomicLong assetTasksSent = new AtomicLong();
    private static final AtomicLong assetRequestsSeen = new AtomicLong();
    private static final AtomicLong assetPendingDropped = new AtomicLong();
    private static final ConcurrentHashMap<String, String> pendingAssetHints = new ConcurrentHashMap();
    private static final AtomicBoolean assetHintPending = new AtomicBoolean(false);
    private static final ConcurrentHashMap<String, Long> recentlyDeletedModels = new ConcurrentHashMap();
    private static final long MODEL_TOMBSTONE_TTL_MS = 1800000L;
    private static final ScheduledExecutorService ASSET_CHANGE_SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "BBSTogether-AssetChangePing");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicBoolean assetChangePingPending = new AtomicBoolean(false);
    private static final ConcurrentHashMap<UUID, Long> uploadRejectNoticeAt = new ConcurrentHashMap();
    private static final int ASSET_QUEUE_MAX = 4096;
    public static final int COMMIT_REJECTED_NO_CHANGE = 0;
    public static final int COMMIT_REJECTED_NO_FILM = 1;
    private static final Map<UUID, Map<String, PendingUpload>> pendingUploadAggregates = new ConcurrentHashMap<UUID, Map<String, PendingUpload>>();
    private static final long UPLOAD_AGGREGATE_WINDOW_MS = 3000L;

    public static long maxAssetBytes() {
        return 0x80000000L;
    }

    public static long syncMaxBytes() {
        int mb = TogetherSettings.getAssetSyncMaxMb();
        return mb <= 0 ? 0L : (long)mb * 1024L * 1024L;
    }

    private static String branchKey(String filmId, String branch) {
        return filmId + "\t" + branch;
    }

    private static String playerBranch(String filmId, ServerPlayerEntity player) {
        return FilmBranchStore.branchOf(filmId, player.getUuid());
    }

    private static String playerBranch(String filmId, UUID playerId) {
        return FilmBranchStore.branchOf(filmId, playerId);
    }

    private static boolean sameBranch(String filmId, ServerPlayerEntity a, ServerPlayerEntity b) {
        return TogetherServerNetwork.playerBranch(filmId, a).equals(TogetherServerNetwork.playerBranch(filmId, b));
    }

    private static boolean sameBranch(String filmId, UUID a, UUID b) {
        return TogetherServerNetwork.playerBranch(filmId, a).equals(TogetherServerNetwork.playerBranch(filmId, b));
    }

    private static void saveBranchNow(String filmId, String branch, MapType data) {
        FilmBranchStore.setWorking(filmId, branch, data);
        FilmBranchStore.save(filmId, branch);
    }

    public static void sendFilmBranchState(MinecraftServer server, ServerPlayerEntity player, String filmId) {
        if (filmId == null || filmId.isEmpty()) {
            return;
        }
        String branch = FilmBranchStore.branchOf(filmId, player.getUuid());
        MapType data = FilmBranchStore.workingData(filmId, branch);
        TogetherServerNetwork.sendFilmBranchStateTo(server, player, filmId, branch, data);
        TogetherServerNetwork.markBranchStateDelivered(player, filmId, branch);
        TogetherServerNetwork.sendFilmBranchMeta(server, player, filmId, branch);
    }

    /**
     * Send the current branch state unless the full film was already delivered moments ago.
     * In that case only the branch name and the commit graph are refreshed, which avoids
     * re-sending a large film two more times every time the editor opens.
     */
    public static void syncFilmBranchState(MinecraftServer server, ServerPlayerEntity player, String filmId) {
        if (filmId == null || filmId.isEmpty()) {
            return;
        }
        String branch = TogetherServerNetwork.playerBranch(filmId, player);
        String key = TogetherServerNetwork.branchKey(filmId, branch);
        Long at = lastBranchStateAt.get(player.getUuid());
        boolean fresh = key.equals(lastBranchStateSent.get(player.getUuid()))
            && at != null
            && System.currentTimeMillis() - at <= BRANCH_DATA_FRESH_MS;
        if (fresh) {
            TogetherServerNetwork.sendFilmBranchMeta(server, player, filmId, branch);
        } else {
            TogetherServerNetwork.sendFilmBranchState(server, player, filmId);
        }
    }

    /** Record that the full film for this player/film/branch is already on the wire. */
    public static void markBranchStateDelivered(ServerPlayerEntity player, String filmId, String branch) {
        if (player == null || filmId == null || filmId.isEmpty()) {
            return;
        }
        lastBranchStateSent.put(player.getUuid(), TogetherServerNetwork.branchKey(filmId, branch));
        lastBranchStateAt.put(player.getUuid(), System.currentTimeMillis());
    }

    private static void sendFilmBranchLabel(ServerPlayerEntity player, String filmId, String branch) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeString(filmId);
        buf.writeString(branch == null ? "" : branch);
        ServerPlayNetworking.send(player, CLIENT_FILM_BRANCH, buf);
    }

    private static void sendFilmBranchMeta(MinecraftServer server, ServerPlayerEntity player, String filmId, String branch) {
        TogetherServerNetwork.sendFilmBranchLabel(player, filmId, branch);
        TogetherServerNetwork.sendCommits(player, FilmCommitStore.list(filmId), filmId, branch);
        TogetherServerNetwork.sendCommitSelectionsTo(player, filmId);
        TogetherServerNetwork.sendPlayerCommitPositionsTo(server, player, filmId);
    }

    private static void sendFilmBranchStateTo(MinecraftServer server, ServerPlayerEntity player, String filmId, String branch, MapType data) {
        TogetherServerNetwork.crusherFor(player).send((PlayerEntity)player, CLIENT_FILM_BRANCH_STATE, (BaseType)data, outBuf -> {
            outBuf.writeString(filmId);
            outBuf.writeString(branch == null ? "" : branch);
        });
    }

    private static String sha1OfPathCached(Path path, File file) throws IOException {
        long mtime = Files.getLastModifiedTime(path, new LinkOption[0]).toMillis();
        long size = file.length();
        HashCacheEntry e = assetHashCache.get(path.toString());
        if (e != null && e.mtime == mtime && e.size == size) {
            return e.sha1;
        }
        String sha1 = TogetherServerNetwork.sha1OfPath(path);
        assetHashCache.put(path.toString(), new HashCacheEntry(mtime, size, sha1));
        return sha1;
    }

    private static void cacheAssetHash(Path path, long mtime, long size, String sha1) {
        if (sha1 == null || sha1.isEmpty()) {
            return;
        }
        assetHashCache.put(path.toString(), new HashCacheEntry(mtime, size, sha1));
    }

    private static long sendIntervalMs() {
        try {
            return ((Number)TogetherSettings.assetSendIntervalMs.get()).longValue();
        }
        catch (Exception e) {
            return 40L;
        }
    }

    private static long sendBatchBytes() {
        try {
            return Math.max(30720L, ((Number)TogetherSettings.assetSendBatchKb.get()).longValue() * 1024L);
        }
        catch (Exception e) {
            return 524288L;
        }
    }

    private static CompressedServerPacketCrusher crusherFor(ServerPlayerEntity player) {
        return playerCrushers.computeIfAbsent(player.getUuid(), k -> new CompressedServerPacketCrusher());
    }

    public static void receiveCrusher(ServerPlayerEntity player, PacketByteBuf buf, IBufferReceiver receiver) {
        TogetherServerNetwork.crusherFor(player).receive(buf, receiver);
    }

    public static void setup() {
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_PLAYER_COLOR, (server, player, handler, buf, resp) -> TogetherServerNetwork.handlePlayerColor(server, player, buf));
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_CURSOR_POSITION, (server, player, handler, buf, resp) -> TogetherServerNetwork.handleCursorPosition(server, player, buf));
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_FILM_SELECTION, (server, player, handler, buf, resp) -> TogetherServerNetwork.handleFilmSelection(server, player, buf));
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_REPLAY_SELECTION, (server, player, handler, buf, resp) -> TogetherServerNetwork.handleReplaySelection(server, player, buf));
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_REPLAY_FORM_LOCK, (server, player, handler, buf, resp) -> TogetherServerNetwork.handleReplayFormLock(server, player, buf));
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_REPLAY_SWAP, (server, player, handler, buf, resp) -> TogetherServerNetwork.handleReplaySwap(server, player, buf));
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_ASSET_REQUEST, (server, player, handler, buf, resp) -> TogetherServerNetwork.handleAssetRequest(server, player, buf));
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_ASSET_UPLOAD, (server, player, handler, buf, resp) -> TogetherServerNetwork.handleAssetUpload(server, player, buf));
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_ASSET_RESYNC, (server, player, handler, buf, resp) -> {
            long clientVersion = buf.readableBytes() >= 8 ? buf.readLong() : 0L;
            TogetherServerNetwork.sendAssetManifest(server, player, clientVersion);
        });
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_SYNC_PREF, (server, player, handler, buf, resp) -> TogetherServerNetwork.handleSyncPref(server, player, buf));
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_ASSET_REPORT, (server, player, handler, buf, resp) -> TogetherServerNetwork.handleAssetReport(server, player, buf));
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_ASSET_REPORT_DELTA, (server, player, handler, buf, resp) -> TogetherServerNetwork.handleAssetReportDelta(server, player, buf));
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_FILM_INVENTORY, (server, player, handler, buf, resp) -> TogetherServerNetwork.handleFilmInventory(server, player, buf));
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_FILM_SCREEN, (server, player, handler, buf, resp) -> TogetherServerNetwork.handleFilmScreen(server, player, buf));
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_RECORDING_FINISHED, (server, player, handler, buf, resp) -> TogetherServerNetwork.handleRecordingFinished(server, player, buf));
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_MASTER_DONE, (server, player, handler, buf, resp) -> TogetherServerNetwork.handleMasterRecordingDone(server, player, buf));
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_FILM_DATA_SYNC, (server, player, handler, buf, resp) -> TogetherServerNetwork.handleFilmDataSync(server, player, buf));
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_UNDO_SYNC, (server, player, handler, buf, resp) -> TogetherServerNetwork.handleUndoSync(server, player, buf));
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_FILM_SAVE_SYNC, (server, player, handler, buf, resp) -> TogetherServerNetwork.handleFilmSaveSync(server, player, buf));
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_FILM_LIST_CHANGED, (server, player, handler, buf, resp) -> TogetherServerNetwork.handleFilmListChanged(server, player, buf));
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_FILM_DELETED, (server, player, handler, buf, resp) -> TogetherServerNetwork.handleFilmDeleted(server, player, buf));
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_FILM_RENAMED, (server, player, handler, buf, resp) -> TogetherServerNetwork.handleFilmRenamed(server, player, buf));
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_BEHAVIOR_REPORT, (server, player, handler, buf, resp) -> TogetherServerNetwork.handleBehaviorReport(server, player, buf));
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_BEHAVIOR_QUERY, (server, player, handler, buf, resp) -> TogetherServerNetwork.handleBehaviorQuery(server, player, buf));
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_SET_OPERATOR, (server, player, handler, buf, resp) -> TogetherServerNetwork.handleSetOperator(server, player, buf));
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_REMOVE_PLAYER_LOGS, (server, player, handler, buf, resp) -> TogetherServerNetwork.handleRemovePlayerLogs(server, player, buf));
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_MODEL_DELETE_REQUEST, (server, player, handler, buf, resp) -> TogetherServerNetwork.handleModelDeleteRequest(server, player, buf));
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_MODEL_DELETE_DECISION, (server, player, handler, buf, resp) -> TogetherServerNetwork.handleModelDeleteDecision(server, player, buf));
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_BBSOP_SET, (server, player, handler, buf, resp) -> TogetherServerNetwork.handleBbsopSet(server, player, buf));
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_BBSOP_GRANT, (server, player, handler, buf, resp) -> TogetherServerNetwork.handleBbsopGrant(server, player, buf));
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_ASSET_LOCK, (server, player, handler, buf, resp) -> TogetherServerNetwork.handleAssetLock(server, player, buf));
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_COOP_INVITE, (server, player, handler, buf, resp) -> CoopSessionManager.handleInvite(server, player, buf));
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_COOP_RESPOND, (server, player, handler, buf, resp) -> CoopSessionManager.handleRespond(server, player, buf));
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_COOP_PICK, (server, player, handler, buf, resp) -> CoopSessionManager.handlePick(server, player, buf));
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_COOP_LEAVE, (server, player, handler, buf, resp) -> CoopSessionManager.handleLeave(server, player, buf));
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_COOP_START, (server, player, handler, buf, resp) -> CoopSessionManager.handleStart(server, player, buf));
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_COOP_CANCEL, (server, player, handler, buf, resp) -> CoopSessionManager.handleCancel(server, player, buf));
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_COOP_MOUSE, (server, player, handler, buf, resp) -> CoopSessionManager.handleMouse(server, player, buf));
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_COMMITS_QUERY, (server, player, handler, buf, resp) -> TogetherServerNetwork.handleCommitsQuery(server, player, buf));
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_COMMIT_INDEX, (server, player, handler, buf, resp) -> TogetherServerNetwork.handleCommitIndex(server, player, buf));
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_COMMIT_DIFF, (server, player, handler, buf, resp) -> TogetherServerNetwork.handleCommitDiff(server, player, buf));
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_COMMIT_HEAD, (server, player, handler, buf, resp) -> TogetherServerNetwork.handleCommitHead(server, player, buf));
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_COMMIT_RENAME, (server, player, handler, buf, resp) -> TogetherServerNetwork.handleCommitRename(server, player, buf));
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_COMMIT_SELECTION, (server, player, handler, buf, resp) -> TogetherServerNetwork.handleCommitSelection(server, player, buf));
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_COMMIT_COLOR, (server, player, handler, buf, resp) -> TogetherServerNetwork.handleCommitColor(server, player, buf));
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_COMMIT_REVERT, (server, player, handler, buf, resp) -> TogetherServerNetwork.handleCommitRevert(server, player, buf));
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_COMMIT_SQUASH, (server, player, handler, buf, resp) -> TogetherServerNetwork.handleCommitSquash(server, player, buf));
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_COMMIT_CREATE, (server, player, handler, buf, resp) -> TogetherServerNetwork.handleCommitCreate(server, player, buf));
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_BRANCH_CREATE, (server, player, handler, buf, resp) -> TogetherServerNetwork.handleBranchCreate(server, player, buf));
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_BRANCH_CHECKOUT, (server, player, handler, buf, resp) -> TogetherServerNetwork.handleBranchCheckout(server, player, buf));
        BBSTogetherFix28Server.registerReceivers();
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            TogetherServerNetwork.sweepUploadAggregates(server);
            CoopSessionManager.sweep(server);
        });
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public static void reset() {
        playerCrushers.clear();
        pendingUploadAggregates.clear();
        playerEditingFilm.clear();
        lastBranchStateSent.clear();
        lastBranchStateAt.clear();
        playerColors.clear();
        playerCursors.clear();
        playerCommitSelections.clear();
        playerFormLocks.clear();
        playerInFilmScreen.clear();
        deletedFilmIds.clear();
        lastSavedFilmSnapshots.clear();
        FilmCommitStore.unload();
        FilmBranchStore.unload();
        LinkedHashMap<UUID, Integer> linkedHashMap = playerReplaySelections;
        LinkedHashMap<UUID, Integer> linkedHashMap2 = linkedHashMap;
        synchronized (linkedHashMap2) {
            playerReplaySelections.clear();
        }
        recordingPlayers.clear();
        pendingStopPlayers.clear();
        pendingReplayData.clear();
        pendingFilmId = null;
        masterPlayerUUID = null;
        playerReceivePush.clear();
        playerOwnedAssets.clear();
        for (ScheduledFuture<?> task : filmSaveTasks.values()) {
            task.cancel(false);
        }
        filmSaveTasks.clear();
        filmSaveFirstDirty.clear();
        filmSaveDirty.clear();
        assetChangePingPending.set(false);
        for (LinkedBlockingQueue linkedBlockingQueue : assetSendQueues.values()) {
            linkedBlockingQueue.clear();
        }
        assetSendQueues.clear();
        assetDrainActive.clear();
        pendingAssetHints.clear();
        assetHintPending.set(false);
        AssetEgressBudget.reset();
        assetBytesSent.set(0L);
        assetTasksSent.set(0L);
        assetRequestsSeen.set(0L);
        assetPendingDropped.set(0L);
        CoopSessionManager.reset();
        BBSTogetherFix28Server.resetState();
    }

    public static List<String> diagLines(MinecraftServer server) {
        ArrayList<String> lines = new ArrayList<String>();
        long rate = AssetEgressBudget.rateBytesPerSec();
        lines.add("\u00a76[BBS Together] \u8d44\u4ea7\u51fa\u53e3\u8bca\u65ad");
        lines.add("\u00a77\u9884\u7b97: \u00a7f" + (String)(rate <= 0L ? "\u4e0d\u9650\u901f\uff080\uff09" : rate / 1024L + " KiB/s (" + (double)(rate / 1024L) / 1024.0 + " MiB/s)"));
        lines.add("\u00a77\u5f53\u524d\u53ef\u7528: \u00a7f" + AssetEgressBudget.availableBytes() / 1024L + " KiB");
        int queued = 0;
        boolean anyQueue = false;
        if (server != null) {
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                int n;
                LinkedBlockingQueue<AssetSendTask> q = assetSendQueues.get(p.getUuid());
                if (q == null || (n = q.size()) <= 0) continue;
                anyQueue = true;
                queued += n;
                lines.add("\u00a77  " + p.getGameProfile().getName() + ": \u00a7f" + n + " \u4e2a\u6587\u4ef6\u6392\u961f" + (assetDrainActive.containsKey(p.getUuid()) ? "\uff08\u6b63\u5728\u53d1\u9001\uff09" : "\uff08\u7a7a\u95f2\uff0c\u7b49\u5f85\u6392\u7a0b\uff09"));
            }
        }
        if (!anyQueue) {
            lines.add("\u00a77\u6392\u961f: \u00a7f\u65e0\u5f85\u53d1\u8d44\u4ea7");
        } else {
            lines.add("\u00a77\u6392\u961f\u5408\u8ba1: \u00a7f" + queued + " \u4e2a\u6587\u4ef6");
        }
        lines.add("\u00a77\u672c\u6b21\u8fd0\u884c\u5df2\u53d1: \u00a7f" + assetBytesSent.get() / 1024L / 1024L + " MiB / " + assetTasksSent.get() + " \u4e2a\u6587\u4ef6");
        lines.add("\u00a77\u5df2\u5904\u7406\u4e0b\u8f7d\u8bf7\u6c42: \u00a7f" + assetRequestsSeen.get() + " \u4e2a\u8def\u5f84");
        lines.add("\u00a77\u8282\u6d41\u53c2\u6570: \u00a7f\u95f4\u9694 " + TogetherServerNetwork.sendIntervalMs() + " ms / \u5206\u6279 " + TogetherServerNetwork.sendBatchBytes() / 1024L + " KiB\uff08\u6bcf\u73a9\u5bb6\uff09");
        long syncMax = TogetherServerNetwork.syncMaxBytes();
        lines.add("\u00a77\u6e05\u5355\u4f53\u79ef\u4e0a\u9650: \u00a7f" + (String)(syncMax <= 0L ? "\u4e0d\u9650\uff08\u6240\u6709\u6587\u4ef6\u90fd\u4f1a\u540c\u6b65\uff09" : syncMax / 1024L / 1024L + " MiB\uff08\u66f4\u5927\u7684\u6587\u4ef6\u4e0d\u540c\u6b65\uff0c\u7559\u5728\u670d\u52a1\u5668\u4e0a\uff09"));
        lines.add("\u00a77\u4e22\u5305: \u00a7f" + assetPendingDropped.get() + " \u4e2a\u6587\u4ef6\uff08\u961f\u5217\u8d85\u957f\u88ab\u4e22\u5f03\uff0c\u89c1 asset.queue");
        lines.add("\u00a77\u63d0\u793a: \u00a7f\u9884\u7b97 0 \u8868\u793a\u5173\u95ed\u9650\u901f\uff1b2048 KiB/s \u7ea6\u7b49\u4e8e\u4e3a\u5fc3\u8df3\u7559\u51fa\u4f59\u91cf\u7684\u5bb6\u7528\u4e0a\u884c\uff1b\u5355\u73a9\u5bb6\u4e0a\u9650\u4ecd\u7531\u4e0a\u9762\u7684\u8282\u6d41\u53c2\u6570\u51b3\u5b9a");
        return lines;
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public static void clearPlayerState(ServerPlayerEntity player, MinecraftServer server) {
        try {
            LinkedHashMap<UUID, Integer> linkedHashMap;
            TogetherServerNetwork.flushUploadAggregates(player, true);
            UUID uuid = player.getUuid();
            CoopSessionManager.onDisconnect(uuid, server);
            LinkedHashMap<UUID, Integer> linkedHashMap2 = linkedHashMap = playerReplaySelections;
            synchronized (linkedHashMap2) {
                playerReplaySelections.remove(uuid);
            }
            FormLockData formLock = playerFormLocks.remove(uuid);
            recordingPlayers.remove(uuid);
            String goneEditingFilm = playerEditingFilm.remove(uuid);
            playerColors.remove(uuid);
            CursorData goneCursor = playerCursors.remove(uuid);
            CommitSelection goneSelection = playerCommitSelections.remove(uuid);
            playerCrushers.remove(uuid);
            String goneScreenFilm = playerInFilmScreen.remove(uuid);
            playerReceivePush.remove(uuid);
            playerOwnedAssets.remove(uuid);
            LinkedBlockingQueue<AssetSendTask> pendingAssets = assetSendQueues.remove(uuid);
            if (pendingAssets != null) {
                pendingAssets.clear();
            }
            assetDrainActive.remove(uuid);
            if (!server.isStopping() && !server.isStopped()) {
                if (goneCursor != null) {
                    TogetherServerNetwork.broadcastCursorRemoval(server, goneCursor.filmId, uuid);
                }
                TogetherServerNetwork.broadcastReplaySelections(server, "");
                if (goneSelection != null) {
                    TogetherServerNetwork.broadcastCommitSelections(server, goneSelection.filmId);
                }
                if (goneEditingFilm != null) {
                    TogetherServerNetwork.broadcastPlayerCommitPositions(server, goneEditingFilm);
                }
                if (goneScreenFilm != null && !goneScreenFilm.equals(goneEditingFilm)) {
                    TogetherServerNetwork.broadcastPlayerCommitPositions(server, goneScreenFilm);
                }
                if (formLock != null) {
                    TogetherServerNetwork.broadcastReplayFormLocks(server, formLock.filmId);
                }
            }
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void handlePlayerColor(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        int color = buf.readInt();
        server.execute(() -> {
            playerColors.put(player.getUuid(), color);
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                ConcurrentHashMap<UUID, Integer> concurrentHashMap;
                PacketByteBuf outBuf = PacketByteBufs.create();
                ConcurrentHashMap<UUID, Integer> concurrentHashMap2 = concurrentHashMap = playerColors;
                synchronized (concurrentHashMap2) {
                    outBuf.writeInt(playerColors.size());
                    for (Map.Entry<UUID, Integer> entry : playerColors.entrySet()) {
                        outBuf.writeUuid(entry.getKey());
                        outBuf.writeInt(entry.getValue().intValue());
                    }
                }
                ServerPlayNetworking.send((ServerPlayerEntity)p, (Identifier)CLIENT_PLAYER_COLORS, (PacketByteBuf)outBuf);
            }
        });
    }

    private static void handleCursorPosition(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        int mark = buf.readerIndex();
        try {
            String clearFilmId = buf.readString();
            int clearEditorType = buf.readInt();
            float clearTick = buf.readFloat();
            float clearRow = buf.readFloat();
            int clearSelCount = buf.readInt();
            for (int i = 0; i < clearSelCount; ++i) {
                buf.readInt();
            }
            buf.readInt();
            if (!(clearFilmId == null || clearFilmId.isEmpty() || clearEditorType >= 0 || clearTick >= 0.0f || clearRow >= 0.0f || clearSelCount > 0)) {
                UUID leaver = player.getUuid();
                server.execute(() -> {
                    playerEditingFilm.remove(leaver, clearFilmId);
                    playerCursors.remove(leaver);
                    TogetherServerNetwork.broadcastFilmSelections(server);
                    TogetherServerNetwork.together_broadcastCursorSnapshot(server, clearFilmId);
                });
                return;
            }
            buf.readerIndex(mark);
        }
        catch (RuntimeException runtimeException) {
            buf.readerIndex(mark);
        }
        String filmId = buf.readString();
        int editorType = buf.readInt();
        float tick = buf.readFloat();
        float row = buf.readFloat();
        int selCount = buf.readInt();
        ArrayList<Integer> selected = new ArrayList<Integer>(Math.min(selCount, 512));
        for (int i = 0; i < selCount; ++i) {
            selected.add(buf.readInt());
        }
        int playheadTick = buf.readInt();
        boolean leaving = editorType < 0;
        server.execute(() -> {
            playerEditingFilm.put(player.getUuid(), filmId);
            if (leaving) {
                playerCursors.remove(player.getUuid());
            } else {
                playerCursors.put(player.getUuid(), new CursorData(filmId, editorType, tick, row, selected, playheadTick));
            }
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                CursorData fallback;
                if (p.getUuid().equals(player.getUuid())) continue;
                String otherFilm = playerEditingFilm.get(p.getUuid());
                if (otherFilm == null && (fallback = playerCursors.get(p.getUuid())) != null) {
                    otherFilm = fallback.filmId;
                }
                if (otherFilm == null || !otherFilm.equals(filmId) || !TogetherServerNetwork.sameBranch(filmId, player, p)) continue;
                PacketByteBuf outBuf = PacketByteBufs.create();
                outBuf.writeBoolean(false);
                outBuf.writeInt(1);
                outBuf.writeUuid(player.getUuid());
                outBuf.writeInt(editorType);
                outBuf.writeFloat(tick);
                outBuf.writeFloat(row);
                outBuf.writeInt(selected.size());
                Iterator iterator = selected.iterator();
                while (iterator.hasNext()) {
                    int idx = (Integer)iterator.next();
                    outBuf.writeInt(idx);
                }
                outBuf.writeInt(playheadTick);
                ServerPlayNetworking.send((ServerPlayerEntity)p, (Identifier)CLIENT_CURSOR_POSITIONS, (PacketByteBuf)outBuf);
            }
        });
    }

    private static void broadcastCursorRemoval(MinecraftServer server, String filmId, UUID gone) {
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            String otherFilm;
            if (p.getUuid().equals(gone) || (otherFilm = playerEditingFilm.get(p.getUuid())) == null || !otherFilm.equals(filmId) || !TogetherServerNetwork.sameBranch(filmId, gone, p.getUuid())) continue;
            PacketByteBuf outBuf = PacketByteBufs.create();
            outBuf.writeBoolean(false);
            outBuf.writeInt(1);
            outBuf.writeUuid(gone);
            outBuf.writeInt(-1);
            outBuf.writeFloat(0.0f);
            outBuf.writeFloat(0.0f);
            outBuf.writeInt(0);
            outBuf.writeInt(0);
            ServerPlayNetworking.send((ServerPlayerEntity)p, (Identifier)CLIENT_CURSOR_POSITIONS, (PacketByteBuf)outBuf);
        }
    }

    private static void handleFilmSelection(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        String filmId = buf.readString();
        server.execute(() -> {
            if (filmId.isEmpty()) {
                String goneFilm = playerEditingFilm.remove(player.getUuid());
                lastBranchStateSent.remove(player.getUuid());
                lastBranchStateAt.remove(player.getUuid());
                CommitSelection gone = playerCommitSelections.remove(player.getUuid());
                if (gone != null) {
                    TogetherServerNetwork.broadcastCommitSelections(server, gone.filmId);
                }
                if (goneFilm != null) {
                    TogetherServerNetwork.broadcastPlayerCommitPositions(server, goneFilm);
                }
            } else {
                playerEditingFilm.put(player.getUuid(), filmId);
                /* The manager load that just opened this film may already have delivered the
                 * branch content; only refresh the branch label and commit graph then. */
                TogetherServerNetwork.syncFilmBranchState(server, player, filmId);
            }
            TogetherServerNetwork.broadcastFilmSelections(server);
            if (!filmId.isEmpty()) {
                TogetherServerNetwork.broadcastPlayerCommitPositions(server, filmId);
            }
        });
    }

    private static void broadcastFilmSelections(MinecraftServer server) {
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeInt(playerEditingFilm.size());
            for (Map.Entry<UUID, String> entry : playerEditingFilm.entrySet()) {
                buf.writeUuid(entry.getKey());
                buf.writeString(entry.getValue());
            }
            ServerPlayNetworking.send((ServerPlayerEntity)p, (Identifier)CLIENT_FILM_SELECTIONS, (PacketByteBuf)buf);
        }
    }

    private static void handleReplaySelection(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        int mark = buf.readerIndex();
        try {
            String clearFilmId = buf.readString();
            int clearIndex = buf.readInt();
            if (clearFilmId != null && !clearFilmId.isEmpty() && clearIndex < 0) {
                UUID leaver = player.getUuid();
                server.execute(() -> {
                    LinkedHashMap<UUID, Integer> selections;
                    LinkedHashMap<UUID, Integer> linkedHashMap = selections = playerReplaySelections;
                    synchronized (linkedHashMap) {
                        selections.remove(leaver);
                    }
                    TogetherServerNetwork.broadcastReplaySelections(server, clearFilmId);
                });
                return;
            }
            buf.readerIndex(mark);
        }
        catch (RuntimeException runtimeException) {
            buf.readerIndex(mark);
        }
        String filmId = buf.readString();
        int replayIndex = buf.readInt();
        server.execute(() -> {
            LinkedHashMap<UUID, Integer> linkedHashMap;
            if (!filmId.isEmpty()) {
                playerEditingFilm.put(player.getUuid(), filmId);
            }
            LinkedHashMap<UUID, Integer> linkedHashMap2 = linkedHashMap = playerReplaySelections;
            synchronized (linkedHashMap2) {
                playerReplaySelections.remove(player.getUuid());
                if (replayIndex >= 0) {
                    playerReplaySelections.put(player.getUuid(), replayIndex);
                }
            }
            TogetherServerNetwork.broadcastReplaySelections(server, filmId);
        });
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public static void broadcastReplaySelections(MinecraftServer server, String filmId) {
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeBoolean(false);
            buf.writeString(filmId);
            AbstractMap abstractMap = playerReplaySelections;
            ArrayList<Map.Entry<UUID, Integer>> replayEntries = new ArrayList<Map.Entry<UUID, Integer>>();
            AbstractMap abstractMap2 = abstractMap;
            synchronized (abstractMap2) {
                for (Map.Entry<UUID, Integer> entry : playerReplaySelections.entrySet()) {
                    if (!filmId.isEmpty() && !TogetherServerNetwork.sameBranch(filmId, p.getUuid(), entry.getKey())) continue;
                    replayEntries.add(entry);
                }
            }
            buf.writeInt(replayEntries.size());
            for (Map.Entry entry : replayEntries) {
                buf.writeUuid((UUID)entry.getKey());
                buf.writeInt(((Integer)entry.getValue()).intValue());
            }
            abstractMap2 = abstractMap = playerColors;
            synchronized (abstractMap2) {
                buf.writeInt(playerColors.size());
                for (Map.Entry<UUID, Integer> entry : playerColors.entrySet()) {
                    buf.writeUuid(entry.getKey());
                    buf.writeInt(entry.getValue().intValue());
                }
            }
            ServerPlayNetworking.send((ServerPlayerEntity)p, (Identifier)CLIENT_REPLAY_SELECTIONS, (PacketByteBuf)buf);
        }
    }

    private static void handleReplayFormLock(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        String filmId = buf.readString();
        int replayIndex = buf.readInt();
        boolean locked = buf.readBoolean();
        server.execute(() -> {
            if (locked && replayIndex >= 0) {
                playerFormLocks.put(player.getUuid(), new FormLockData(filmId, replayIndex));
            } else {
                playerFormLocks.remove(player.getUuid());
            }
            TogetherServerNetwork.broadcastReplayFormLocks(server, filmId);
        });
    }

    public static void broadcastReplayFormLocks(MinecraftServer server, String filmId) {
        ArrayList<Map.Entry<UUID, FormLockData>> entries = new ArrayList<Map.Entry<UUID, FormLockData>>();
        for (Map.Entry<UUID, FormLockData> entry : playerFormLocks.entrySet()) {
            if (!entry.getValue().filmId.equals(filmId)) continue;
            entries.add(entry);
        }
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            PacketByteBuf outBuf = PacketByteBufs.create();
            outBuf.writeString(filmId);
            ArrayList<Map.Entry> visible = new ArrayList<Map.Entry>();
            for (Map.Entry entry : entries) {
                if (!TogetherServerNetwork.sameBranch(filmId, p.getUuid(), (UUID)entry.getKey())) continue;
                visible.add(entry);
            }
            outBuf.writeInt(visible.size());
            for (Map.Entry entry : visible) {
                outBuf.writeUuid((UUID)entry.getKey());
                outBuf.writeInt(((FormLockData)entry.getValue()).replayIndex);
            }
            ServerPlayNetworking.send((ServerPlayerEntity)p, (Identifier)CLIENT_REPLAY_FORM_LOCKS, (PacketByteBuf)outBuf);
        }
    }

    private static void handleReplaySwap(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        String filmId = buf.readString();
        int from = buf.readInt();
        int to = buf.readInt();
        server.execute(() -> {
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                if (p == player || !TogetherServerNetwork.sameBranch(filmId, player, p)) continue;
                PacketByteBuf outBuf = PacketByteBufs.create();
                outBuf.writeString(filmId);
                outBuf.writeInt(from);
                outBuf.writeInt(to);
                ServerPlayNetworking.send((ServerPlayerEntity)p, (Identifier)CLIENT_REPLAY_SWAP, (PacketByteBuf)outBuf);
            }
        });
    }

    public static void sendAssetManifest(MinecraftServer server, ServerPlayerEntity player) {
        TogetherServerNetwork.sendAssetManifest(server, player, 0L);
    }

    public static void sendAssetManifest(MinecraftServer server, ServerPlayerEntity player, long clientVersion) {
        File assetsFolder = BBSMod.getAssetsFolder();
        if (assetsFolder == null) {
            return;
        }
        if (!assetsFolder.exists()) {
            assetsFolder.mkdirs();
        }
        File assetsRoot = assetsFolder;
        final Path assetsPath = assetsFolder.toPath();
        Thread thread = new Thread(() -> {
            try {
                final MapType manifest = new MapType();
                final StringBuilder fingerprint = new StringBuilder();
                long syncMaxBytes = TogetherServerNetwork.syncMaxBytes();
                if (assetsRoot.exists()) {
                    Files.walkFileTree(assetsPath, (FileVisitor<? super Path>)new SimpleFileVisitor<Path>(){

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                            if (!attrs.isRegularFile()) {
                                return FileVisitResult.CONTINUE;
                            }
                            String relativePath = assetsPath.relativize(file).toString().replace(File.separator, "/");
                            if (AssetPaths.isTransferTemp(relativePath)) {
                                return FileVisitResult.CONTINUE;
                            }
                            String sha1 = TogetherServerNetwork.sha1OfPathCached(file, file.toFile());
                            String entry = sha1 + ":" + file.toFile().length() + ":" + attrs.lastModifiedTime().toMillis();
                            manifest.putString(relativePath, entry);
                            fingerprint.append(relativePath).append('=').append(entry).append('\n');
                            return FileVisitResult.CONTINUE;
                        }
                    });
                }
                long version = TogetherServerNetwork.fingerprintVersion(fingerprint.toString());
                boolean unchanged = clientVersion != 0L && clientVersion == version;
                server.execute(() -> {
                    MapType payload = unchanged ? new MapType() : manifest;
                    TogetherServerNetwork.crusherFor(player).send((PlayerEntity)player, CLIENT_ASSET_MANIFEST, (BaseType)payload, outBuf -> {
                        outBuf.writeBoolean(unchanged);
                        outBuf.writeLong(version);
                    });
                });
            }
            catch (Exception e) {
                e.printStackTrace();
            }
        }, "BBSTogether-AssetManifest");
        thread.setDaemon(true);
        thread.start();
    }

    private static long fingerprintVersion(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            long v = 0L;
            for (int i = 0; i < 8; ++i) {
                v = v << 8 | (long)(hash[i] & 0xFF);
            }
            return v;
        }
        catch (NoSuchAlgorithmException e) {
            return content.hashCode();
        }
    }

    public static void sendInitialFilmSync(MinecraftServer server, ServerPlayerEntity player) {
        try {
            TogetherServerNetwork.broadcastFilmSelections(server);
            TogetherServerNetwork.broadcastReplaySelections(server, "");
            TogetherServerNetwork.broadcastFilmScreens(server);
            TogetherServerNetwork.sendAllCommitSelections(server, player);
            TogetherServerNetwork.sendAllPlayerCommitPositions(server, player);
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void handleAssetRequest(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        int count = buf.readInt();
        ArrayList<String> paths = new ArrayList<String>();
        for (int i = 0; i < count; ++i) {
            paths.add(buf.readString());
        }
        TogetherServerNetwork.recordDownloadBehavior(player, paths);
        assetRequestsSeen.addAndGet(paths.size());
        Thread thread = new Thread(() -> {
            File assetsFolder = BBSMod.getAssetsFolder();
            for (String relativePath : paths) {
                long syncMax;
                File file;
                if (!AssetPaths.isPublishable(relativePath) || !(file = new File(assetsFolder, relativePath.replace("/", File.separator))).exists() || !file.isFile() || file.length() > TogetherServerNetwork.maxAssetBytes() || file.length() == 0L || (syncMax = TogetherServerNetwork.syncMaxBytes()) > 0L && file.length() > syncMax) continue;
                String sha1 = "";
                try {
                    sha1 = TogetherServerNetwork.sha1OfPathCached(file.toPath(), file);
                }
                catch (Exception exception) {
                    // empty catch block
                }
                TogetherServerNetwork.enqueueAssetSend(server, player, new AssetSendTask(relativePath, file, file.length(), sha1, false));
            }
        }, "BBSTogether-AssetSend");
        thread.setDaemon(true);
        thread.start();
    }

    private static void noticeUploadRejected(MinecraftServer server, ServerPlayerEntity player) {
        long now = System.currentTimeMillis();
        Long last = uploadRejectNoticeAt.get(player.getUuid());
        if (last != null && now - last < 30000L) {
            return;
        }
        uploadRejectNoticeAt.put(player.getUuid(), now);
        String message = "[BBS Together] \u4e0a\u4f20\u88ab\u62d2\u7edd\uff1a\u4f60\u7684 BBS \u6743\u9650\u662f\u53ea\u8bfb\uff08\u7b49\u7ea7 0\uff09\uff0c\u521a\u4e0a\u4f20\u7684\u8d44\u4ea7\u6ca1\u6709\u5199\u5165\u670d\u52a1\u5668\u3002\u8bf7\u7ba1\u7406\u5458\u7528 /bbsop \u7ed9\u4f60\u6388\u6743\u540e\u518d\u4e0a\u4f20\u3002";
        server.execute(() -> player.sendMessage((Text)Text.literal((String)message), false));
    }

    private static void handleAssetUpload(MinecraftServer server, ServerPlayerEntity uploader, PacketByteBuf buf) {
        if (!BbsOpManager.isAllowed(uploader, BbsOpDomain.UPLOAD)) {
            TogetherServerNetwork.noticeUploadRejected(server, uploader);
            return;
        }
        TogetherServerNetwork.crusherFor(uploader).receive(buf, (bytes, packetByteBuf) -> {
            block19: {
                int seqTotal;
                String relativePath = packetByteBuf.readString();
                int seq = packetByteBuf.readableBytes() >= 4 ? packetByteBuf.readInt() : 0;
                int n = seqTotal = packetByteBuf.readableBytes() >= 4 ? packetByteBuf.readInt() : 1;
                long totalSize = packetByteBuf.readableBytes() >= 8 ? packetByteBuf.readLong() : (bytes == null ? 0L : (long)bytes.length);
                String sha1 = packetByteBuf.readableBytes() > 0 ? packetByteBuf.readString() : "";
                long uploadMtime = packetByteBuf.readableBytes() >= 8 ? packetByteBuf.readLong() : 0L;
                long receivedAt = System.currentTimeMillis();
                if (uploadMtime <= 0L || uploadMtime > receivedAt + 60000L) {
                    uploadMtime = receivedAt;
                }
                if (relativePath.contains("..") || relativePath.startsWith("/")) {
                    return;
                }
                if (bytes == null || totalSize > TogetherServerNetwork.maxAssetBytes()) {
                    return;
                }
                long uploadSyncMax = TogetherServerNetwork.syncMaxBytes();
                if (uploadSyncMax > 0L && totalSize > uploadSyncMax) {
                    return;
                }
                File assetsFolder = BBSMod.getAssetsFolder();
                if (assetsFolder == null) {
                    return;
                }
                String uploadModelId = ModelPathUtils.extractModelId(relativePath);
                if (uploadModelId != null && TogetherServerNetwork.isModelTombstoned(uploadModelId)) {
                    System.out.println("[BBSTogether] Ignored upload of recently deleted model " + uploadModelId + " from " + uploader.getGameProfile().getName());
                    return;
                }
                if (uploadModelId != null && !AssetLockManager.canEdit(uploader.getUuid(), AssetLockType.MODEL, uploadModelId)) {
                    AssetLockManager.notifyLocked(server, uploader, AssetLockType.MODEL);
                    return;
                }
                Path target = assetsFolder.toPath().resolve(relativePath.replace("/", File.separator));
                if (target.getParent() == null) {
                    return;
                }
                try {
                    boolean changed;
                    String existingSha1;
                    Files.createDirectories(target.getParent(), new FileAttribute[0]);
                    if (seqTotal > 1) {
                        Path tmp = target.resolveSibling(target.getFileName().toString() + ".bbsup");
                        if (seq == 0) {
                            Files.write(tmp, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                        } else {
                            Files.write(tmp, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                        }
                        if (seq < seqTotal - 1) {
                            return;
                        }
                        if (!sha1.isEmpty() && !TogetherServerNetwork.sha1OfPath(tmp).equalsIgnoreCase(sha1)) {
                            Files.deleteIfExists(tmp);
                            System.out.println("[BBS Assets] Upload rejected (sha1 mismatch): " + relativePath);
                            return;
                        }
                        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                        try {
                            FileTime stamp = FileTime.fromMillis(uploadMtime);
                            Files.setLastModifiedTime(target, stamp);
                        }
                        catch (Exception stamp) {
                            // empty catch block
                        }
                        TogetherServerNetwork.finalizeUpload(server, uploader, relativePath, target, totalSize, sha1, true);
                        break block19;
                    }
                    String string = existingSha1 = Files.exists(target, new LinkOption[0]) ? TogetherServerNetwork.sha1OfPathCached(target, target.toFile()) : null;
                    changed = existingSha1 == null || (sha1.isEmpty() ? !Arrays.equals(Files.readAllBytes(target), bytes) : !existingSha1.equalsIgnoreCase(sha1));
                    if (changed) {
                        Files.write(target, bytes, new OpenOption[0]);
                        try {
                            FileTime stamp = FileTime.fromMillis(uploadMtime);
                            Files.setLastModifiedTime(target, stamp);
                        }
                        catch (Exception exception) {
                            // empty catch block
                        }
                        TogetherServerNetwork.finalizeUpload(server, uploader, relativePath, target, totalSize, sha1.isEmpty() ? TogetherServerNetwork.sha1OfPathCached(target, target.toFile()) : sha1, true);
                        break block19;
                    }
                    TogetherServerNetwork.finalizeUpload(server, uploader, relativePath, target, totalSize, sha1, false);
                }
                catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    private static void finalizeUpload(MinecraftServer server, ServerPlayerEntity uploader, String relativePath, Path target, long size, String sha1, boolean changed) {
        try {
            TogetherServerNetwork.cacheAssetHash(target, Files.getLastModifiedTime(target, new LinkOption[0]).toMillis(), target.toFile().length(), sha1);
        }
        catch (Exception exception) {
            // empty catch block
        }
        if (changed) {
            TogetherServerNetwork.recordUploadBehavior(uploader, relativePath, size);
        }
        if (!changed) {
            return;
        }
        TogetherServerNetwork.scheduleAssetsChangedPing(server, uploader);
        TogetherServerNetwork.queueAssetHint(server, uploader, relativePath, sha1, size);
    }

    private static void queueAssetHint(MinecraftServer server, ServerPlayerEntity uploader, String relativePath, String sha1, long size) {
        if (sha1 == null || sha1.isEmpty() || relativePath == null) {
            return;
        }
        pendingAssetHints.put(relativePath, sha1 + ":" + size);
        if (!assetHintPending.compareAndSet(false, true)) {
            return;
        }
        UUID uploaderId = uploader.getUuid();
        ASSET_CHANGE_SCHEDULER.schedule(() -> {
            assetHintPending.set(false);
            HashMap<String, String> hints = new HashMap<String, String>(pendingAssetHints);
            pendingAssetHints.clear();
            if (hints.isEmpty()) {
                return;
            }
            try {
                server.execute(() -> {
                    File assetsFolder = BBSMod.getAssetsFolder();
                    for (ServerPlayerEntity other : server.getPlayerManager().getPlayerList()) {
                        Boolean pref;
                        if (other.getUuid().equals(uploaderId) || (pref = playerReceivePush.get(other.getUuid())) != null && !pref.booleanValue()) continue;
                        boolean hintCapable = ServerPlayNetworking.canSend((ServerPlayerEntity)other, (Identifier)CLIENT_ASSET_HINT);
                        Map<String, String> owned = playerOwnedAssets.get(other.getUuid());
                        MapType payload = new MapType();
                        for (Map.Entry e : hints.entrySet()) {
                            String entry = (String)e.getValue();
                            String hintSha1 = entry.substring(0, entry.indexOf(58));
                            if (owned != null && hintSha1.equalsIgnoreCase(owned.get(e.getKey()))) continue;
                            if (hintCapable) {
                                payload.putString((String)e.getKey(), entry);
                                continue;
                            }
                            File legacy = new File(assetsFolder, ((String)e.getKey()).replace("/", File.separator));
                            if (!legacy.isFile() || legacy.length() <= 0L) continue;
                            TogetherServerNetwork.enqueueAssetSend(server, other, new AssetSendTask((String)e.getKey(), legacy, legacy.length(), hintSha1, true));
                        }
                        if (!hintCapable || payload.keys().isEmpty()) continue;
                        TogetherServerNetwork.crusherFor(other).send((PlayerEntity)other, CLIENT_ASSET_HINT, (BaseType)payload, null);
                    }
                });
            }
            catch (Exception exception) {
                // empty catch block
            }
        }, 2000L, TimeUnit.MILLISECONDS);
    }

    private static void scheduleAssetsChangedPing(MinecraftServer server, ServerPlayerEntity uploader) {
        if (!assetChangePingPending.compareAndSet(false, true)) {
            return;
        }
        UUID uploaderId = uploader.getUuid();
        ASSET_CHANGE_SCHEDULER.schedule(() -> {
            assetChangePingPending.set(false);
            try {
                server.execute(() -> {
                    for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                        if (p.getUuid().equals(uploaderId)) continue;
                        ServerPlayNetworking.send((ServerPlayerEntity)p, (Identifier)CLIENT_ASSETS_CHANGED, (PacketByteBuf)PacketByteBufs.create());
                    }
                });
            }
            catch (Exception exception) {
                // empty catch block
            }
        }, 2000L, TimeUnit.MILLISECONDS);
    }

    private static void enqueueAssetSend(MinecraftServer server, ServerPlayerEntity player, AssetSendTask task) {
        UUID uuid = player.getUuid();
        LinkedBlockingQueue queue = assetSendQueues.computeIfAbsent(uuid, k -> new LinkedBlockingQueue());
        if (queue.size() >= 4096) {
            assetPendingDropped.incrementAndGet();
            return;
        }
        queue.add(task);
        assetTasksSent.incrementAndGet();
        AtomicBoolean draining = assetDrainActive.computeIfAbsent(uuid, k -> new AtomicBoolean(false));
        if (draining.compareAndSet(false, true)) {
            Thread drainThread = new Thread(() -> {
                try {
                    while (true) {
                        LinkedBlockingQueue<AssetSendTask> q;
                        AssetSendTask t;
                        AssetSendTask assetSendTask = t = (q = assetSendQueues.get(uuid)) == null ? null : q.poll();
                        if (t == null) {
                            assetDrainActive.remove(uuid);
                            q = assetSendQueues.get(uuid);
                            if (q != null) {
                                if (q.isEmpty()) {
                                } else if (assetDrainActive.computeIfAbsent(uuid, k -> new AtomicBoolean(false)).compareAndSet(false, true)) continue;
                            }
                            break;
                        }
                        TogetherServerNetwork.sendTaskInBatches(server, player, t);
                    }
                }
                finally {
                    assetDrainActive.remove(uuid);
                }
            }, "BBSTogether-AssetDrain-" + String.valueOf(uuid));
            drainThread.setDaemon(true);
            drainThread.start();
        }
    }

    private static void sendTaskInBatches(MinecraftServer server, ServerPlayerEntity player, AssetSendTask task) {
        long interval = Math.max(0L, TogetherServerNetwork.sendIntervalMs());
        long batchBytes = Math.max(30720L, TogetherServerNetwork.sendBatchBytes());
        long size = task.size;
        if (size <= 0L) {
            return;
        }
        AssetEgressBudget.sync();
        int totalSlices = (int)Math.max(1L, (size + batchBytes - 1L) / batchBytes);
        try {
            for (int seq = 0; seq < totalSlices; ++seq) {
                int fSeq = seq;
                int fTotal = totalSlices;
                long budgetWaitNs = AssetEgressBudget.reserve((int)Math.min(batchBytes, size - (long)seq * batchBytes));
                if (budgetWaitNs > 0L) {
                    Thread.sleep(budgetWaitNs / 1000000L);
                }
                server.execute(() -> {
                    try {
                        if (!server.getPlayerManager().getPlayerList().contains(player)) {
                            return;
                        }
                        byte[] slice = TogetherServerNetwork.readSlice(task.file, (long)fSeq * batchBytes, (int)Math.min(batchBytes, size - (long)fSeq * batchBytes));
                        if (slice == null) {
                            return;
                        }
                        TogetherServerNetwork.crusherFor(player).send((PlayerEntity)player, CLIENT_ASSET_FILE, slice, outBuf -> {
                            outBuf.writeString(task.relativePath);
                            outBuf.writeBoolean(task.isPush);
                            outBuf.writeInt(fSeq);
                            outBuf.writeInt(fTotal);
                            outBuf.writeLong(task.size);
                            outBuf.writeString(task.sha1 == null ? "" : task.sha1);
                            outBuf.writeLong(task.file.lastModified());
                        });
                        assetBytesSent.addAndGet(slice.length);
                    }
                    catch (Exception e) {
                        e.printStackTrace();
                    }
                });
                if (seq >= totalSlices - 1 || interval <= 0L) continue;
                Thread.sleep(interval);
            }
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /*
     * Enabled aggressive block sorting
     * Enabled unnecessary exception pruning
     * Enabled aggressive exception aggregation
     */
    private static byte[] readSlice(File file, long offset, int length) {
        try (FileChannel ch = FileChannel.open(file.toPath(), StandardOpenOption.READ);){
            int read;
            ByteBuffer buffer = ByteBuffer.allocate(length);
            while (buffer.hasRemaining() && (read = ch.read(buffer, offset + (long)buffer.position())) >= 0) {
            }
            if (buffer.hasRemaining()) {
                byte[] byArray2 = null;
                return byArray2;
            }
            byte[] byArray = buffer.array();
            return byArray;
        }
        catch (Exception e) {
            return null;
        }
    }

    private static void scheduleFilmSave(MinecraftServer server, String filmId, String branch) {
        String key = TogetherServerNetwork.branchKey(filmId, branch);
        AtomicBoolean flag = filmSaveDirty.computeIfAbsent(key, k -> new AtomicBoolean(false));
        flag.set(true);
        long now = System.currentTimeMillis();
        long first = filmSaveFirstDirty.computeIfAbsent(key, k -> now);
        long delay = Math.min(FILM_SAVE_DELAY_MS, Math.max(0L, first + FILM_SAVE_MAX_MS - now));
        ScheduledFuture<?> previous = filmSaveTasks.remove(key);
        if (previous != null) {
            previous.cancel(false);
        }
        filmSaveTasks.put(key, FILM_SAVE_SCHEDULER.schedule(() -> {
            try {
                server.execute(() -> TogetherServerNetwork.saveIfDirty(key));
            }
            catch (Exception exception) {
                // empty catch block
            }
        }, delay, TimeUnit.MILLISECONDS));
    }

    private static void saveIfDirty(String key) {
        filmSaveTasks.remove(key);
        filmSaveFirstDirty.remove(key);
        AtomicBoolean flag = filmSaveDirty.get(key);
        if (flag == null || !flag.compareAndSet(true, false)) {
            return;
        }
        int tab = key.lastIndexOf(9);
        if (tab < 0) {
            return;
        }
        String filmId = key.substring(0, tab);
        String branch = key.substring(tab + 1);
        try {
            FilmBranchStore.save(filmId, branch);
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void flushPendingFilmSaves(MinecraftServer server) {
        for (ScheduledFuture<?> task : filmSaveTasks.values()) {
            task.cancel(false);
        }
        filmSaveTasks.clear();
        filmSaveFirstDirty.clear();
        for (String key : filmSaveDirty.keySet()) {
            TogetherServerNetwork.saveIfDirty(key);
        }
        filmSaveDirty.clear();
        FilmBranchStore.saveAll();
    }

    private static void handleSyncPref(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        boolean receive = buf.readBoolean();
        server.execute(() -> playerReceivePush.put(player.getUuid(), receive));
    }

    private static void handleAssetReport(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        TogetherServerNetwork.crusherFor(player).receive(buf, (bytes, packetByteBuf) -> {
            if (bytes == null) {
                return;
            }
            try {
                BaseType data = DataStorageUtils.readFromBytes((byte[])bytes);
                if (!data.isMap()) {
                    return;
                }
                MapType report = data.asMap();
                HashMap<String, String> owned = new HashMap<String, String>();
                for (String key : report.keys()) {
                    owned.put(key, report.getString(key));
                }
                server.execute(() -> playerOwnedAssets.put(player.getUuid(), owned));
            }
            catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private static void handleAssetReportDelta(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        TogetherServerNetwork.crusherFor(player).receive(buf, (bytes, packetByteBuf) -> {
            if (bytes == null) {
                return;
            }
            try {
                BaseType data = DataStorageUtils.readFromBytes((byte[])bytes);
                if (!data.isMap()) {
                    return;
                }
                MapType report = data.asMap();
                HashMap<String, String> updates = new HashMap<String, String>();
                ArrayList<String> removals = new ArrayList<String>();
                for (String key : report.keys()) {
                    String sha1 = report.getString(key);
                    if (sha1 == null || sha1.isEmpty()) {
                        removals.add(key);
                        continue;
                    }
                    updates.put(key, sha1);
                }
                server.execute(() -> {
                    Map owned = playerOwnedAssets.computeIfAbsent(player.getUuid(), k -> new HashMap());
                    owned.putAll(updates);
                    for (String key : removals) {
                        owned.remove(key);
                    }
                });
            }
            catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private static void handleFilmInventory(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        TogetherServerNetwork.crusherFor(player).receive(buf, (bytes, packetByteBuf) -> {
            if (bytes == null) {
                return;
            }
            Thread thread = new Thread(() -> {
                try {
                    BaseType data = DataStorageUtils.readFromBytes((byte[])bytes);
                    if (!data.isMap()) {
                        return;
                    }
                    MapType inventory = data.asMap();
                    Collection keys = BBSMod.getFilms().getKeys();
                    for (String filmId : new ArrayList<String>(keys)) {
                        server.execute(() -> {
                            String clientSha1;
                            String serverSha1;
                            String branch = FilmBranchStore.branchOf(filmId, player.getUuid());
                            if (!FilmBranchStore.isDefault(filmId, branch)) {
                                return;
                            }
                            File file = BBSMod.getFilms().getFile(filmId);
                            if (file == null || !file.isFile()) {
                                return;
                            }
                            try {
                                serverSha1 = TogetherServerNetwork.sha1OfPathCached(file.toPath(), file);
                            }
                            catch (IOException e) {
                                return;
                            }
                            String string = clientSha1 = inventory.has(filmId) ? inventory.getString(filmId) : "";
                            if (!clientSha1.isEmpty() && clientSha1.equalsIgnoreCase(serverSha1)) {
                                return;
                            }
                            MapType filmData = FilmBranchStore.workingData(filmId, branch);
                            if (filmData == null) {
                                return;
                            }
                            TogetherServerNetwork.broadcastFilmSavedToAll(server, player, filmId, branch, filmData);
                        });
                    }
                }
                catch (Exception e) {
                    e.printStackTrace();
                }
            }, "BBSTogether-FilmInventory");
            thread.setDaemon(true);
            thread.start();
        });
    }

    private static String sha1OfPath(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            try (InputStream in = Files.newInputStream(path, new OpenOption[0]);){
                int read;
                byte[] buffer = new byte[8192];
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            byte[] bytes = digest.digest();
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                int v = b & 0xFF;
                if (v < 16) {
                    sb.append('0');
                }
                sb.append(Integer.toHexString(v));
            }
            return sb.toString();
        }
        catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-1 not available", e);
        }
    }

    private static void handleFilmScreen(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        String filmId = buf.readString();
        server.execute(() -> {
            String goneFilm = null;
            if (filmId.isEmpty()) {
                boolean wasLast = playerInFilmScreen.size() == 1 && playerInFilmScreen.containsKey(player.getUuid());
                goneFilm = playerInFilmScreen.remove(player.getUuid());
                if (wasLast) {
                    BBSMod.getActions().resetDamage(player.getServerWorld());
                }
            } else {
                String branch;
                String key;
                boolean wasFirst = playerInFilmScreen.isEmpty();
                playerInFilmScreen.put(player.getUuid(), filmId);
                if (wasFirst) {
                    BBSMod.getActions().trackDamage(player.getServerWorld());
                }
                if (!(key = filmId + "\t" + (branch = TogetherServerNetwork.playerBranch(filmId, player))).equals(lastBranchStateSent.get(player.getUuid()))) {
                    TogetherServerNetwork.sendFilmBranchState(server, player, filmId);
                }
            }
            TogetherServerNetwork.broadcastFilmScreens(server);
            TogetherServerNetwork.broadcastPlayerCommitPositions(server, goneFilm != null ? goneFilm : filmId);
        });
    }

    private static void broadcastFilmScreens(MinecraftServer server) {
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeInt(playerInFilmScreen.size());
            for (Map.Entry<UUID, String> entry : playerInFilmScreen.entrySet()) {
                buf.writeUuid(entry.getKey());
                buf.writeString(entry.getValue());
            }
            ServerPlayNetworking.send((ServerPlayerEntity)p, (Identifier)CLIENT_FILM_SCREENS, (PacketByteBuf)buf);
        }
    }

    public static boolean isPlayerRecording(ServerPlayerEntity player) {
        return recordingPlayers.containsKey(player.getUuid());
    }

    public static void sendToggleRecording(ServerPlayerEntity player, boolean start) {
        if (start && !BbsOpManager.isAllowed(player, BbsOpDomain.FILM)) {
            return;
        }
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBoolean(start);
        buf.writeInt(-1);
        ServerPlayNetworking.send((ServerPlayerEntity)player, (Identifier)CLIENT_TOGGLE_RECORDING, (PacketByteBuf)buf);
        if (start) {
            recordingPlayers.put(player.getUuid(), Boolean.TRUE);
        } else {
            recordingPlayers.remove(player.getUuid());
        }
    }

    public static void beginRecordingSession(Collection<ServerPlayerEntity> players) {
        pendingStopPlayers.clear();
        pendingReplayData.clear();
        pendingFilmId = null;
        masterPlayerUUID = null;
        for (ServerPlayerEntity p : players) {
            pendingStopPlayers.put(p.getUuid(), Boolean.TRUE);
        }
        if (!players.isEmpty()) {
            masterPlayerUUID = players.iterator().next().getUuid();
        }
    }

    /** Refuse a film write for a player the film's lock does not allow; says why once. */
    public static boolean denyLockedFilm(MinecraftServer server, ServerPlayerEntity player, String filmId) {
        if (AssetLockManager.canEdit(player.getUuid(), AssetLockType.FILM, filmId)) {
            return false;
        }
        AssetLockManager.notifyLocked(server, player, AssetLockType.FILM);
        return true;
    }

    private static void handleRecordingFinished(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        if (!BbsOpManager.isAllowed(player, BbsOpDomain.FILM) || !BbsOpManager.isAllowed(player, BbsOpDomain.RECORD)) {
            return;
        }
        TogetherServerNetwork.crusherFor(player).receive(buf, (bytes, packetByteBuf) -> {
            String filmId = packetByteBuf.readString();
            String sentBranch = packetByteBuf.isReadable() ? packetByteBuf.readString(80) : "";
            int replayIndex = packetByteBuf.readInt();
            BaseType replayData = DataStorageUtils.readFromBytes((byte[])bytes);
            if (!replayData.isMap()) {
                return;
            }
            server.execute(() -> {
                String branch = TogetherServerNetwork.playerBranch(filmId, player);
                if (!sentBranch.isEmpty() && !sentBranch.equals(branch)) {
                    return;
                }
                if (TogetherServerNetwork.denyLockedFilm(server, player, filmId)) {
                    return;
                }
                if (CoopSessionManager.onRecordingFinished(server, player, filmId, replayIndex, replayData.asMap())) {
                    return;
                }
                BBSTogetherFix18.mergeRecordingFinished(filmId, replayIndex, replayData, server, branch);
                recordingPlayers.remove(player.getUuid());
                pendingStopPlayers.remove(player.getUuid());
                if (pendingStopPlayers.isEmpty()) {
                    pendingReplayData.clear();
                    pendingFilmId = null;
                    masterPlayerUUID = null;
                }
            });
        });
    }

    private static void handleMasterRecordingDone(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        if (!BbsOpManager.isAllowed(player, BbsOpDomain.FILM)) {
            return;
        }
        TogetherServerNetwork.crusherFor(player).receive(buf, (bytes, packetByteBuf) -> {
            String filmId = packetByteBuf.readString();
            String sentBranch = packetByteBuf.isReadable() ? packetByteBuf.readString(80) : "";
            BaseType incoming = DataStorageUtils.readFromBytes((byte[])bytes);
            if (!incoming.isMap()) {
                return;
            }
            server.execute(() -> {
                String branch = TogetherServerNetwork.playerBranch(filmId, player);
                if (!sentBranch.isEmpty() && !sentBranch.equals(branch)) {
                    return;
                }
                if (TogetherServerNetwork.denyLockedFilm(server, player, filmId)) {
                    return;
                }
                Film stored = FilmBranchStore.working(filmId, branch);
                Film incomingFilm = new Film();
                incomingFilm.fromData((BaseType)incoming.asMap());
                TogetherServerNetwork.mergeFilms(stored, incomingFilm);
                MapType merged = (MapType)stored.toData();
                TogetherServerNetwork.saveBranchNow(filmId, branch, merged);
                lastSavedFilmSnapshots.put(TogetherServerNetwork.branchKey(filmId, branch), merged);
                TogetherServerNetwork.broadcastFilmSaved(server, filmId, branch, merged);
            });
        });
    }

    public static void broadcastFilmSavedToAll(MinecraftServer server, ServerPlayerEntity recipient, String filmId, String branch, MapType filmData) {
        TogetherServerNetwork.crusherFor(recipient).send((PlayerEntity)recipient, CLIENT_FILM_SAVED_SYNC, (BaseType)filmData, outBuf -> {
            outBuf.writeString(filmId);
            outBuf.writeString("[Server]");
            outBuf.writeString(branch == null ? "" : branch);
        });
    }

    public static void broadcastFilmSaved(MinecraftServer server, String filmId, String branch, MapType filmData) {
        boolean defaultBranch = FilmBranchStore.isDefault(filmId, branch);
        ArrayList<ServerPlayerEntity> others = new ArrayList<ServerPlayerEntity>();
        for (ServerPlayerEntity recipient : server.getPlayerManager().getPlayerList()) {
            boolean interested;
            String editing = playerEditingFilm.get(recipient.getUuid());
            String screen = playerInFilmScreen.get(recipient.getUuid());
            boolean bl = interested = filmId.equals(editing) || filmId.equals(screen);
            if (interested && TogetherServerNetwork.playerBranch(filmId, recipient).equals(branch)) {
                TogetherServerNetwork.broadcastFilmSavedToAll(server, recipient, filmId, branch, filmData);
                continue;
            }
            if (interested) continue;
            others.add(recipient);
        }
        if (defaultBranch) {
            for (ServerPlayerEntity recipient : others) {
                ServerPlayNetworking.send((ServerPlayerEntity)recipient, (Identifier)CLIENT_FILM_LIST_CHANGED, (PacketByteBuf)PacketByteBufs.create());
            }
        }
    }

    private static void handleFilmSaveSync(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        if (!BbsOpManager.isAllowed(player, BbsOpDomain.FILM)) {
            return;
        }
        TogetherServerNetwork.crusherFor(player).receive(buf, (bytes, packetByteBuf) -> {
            if (bytes == null) {
                return;
            }
            String filmId = packetByteBuf.readString();
            String sentBranch = packetByteBuf.isReadable() ? packetByteBuf.readString(80) : "";
            BaseType incoming = DataStorageUtils.readFromBytes((byte[])bytes);
            if (!incoming.isMap()) {
                return;
            }
            server.execute(() -> {
                if (deletedFilmIds.contains(filmId)) {
                    return;
                }
                String branch = TogetherServerNetwork.playerBranch(filmId, player);
                if (!sentBranch.isEmpty() && !sentBranch.equals(branch)) {
                    System.out.println("[BBSTogether] Drop save from stale branch player=" + player.getGameProfile().getName() + " film=" + filmId + " sent=" + sentBranch + " current=" + branch);
                    return;
                }
                if (CoopSessionManager.isFilmLockedForEditing(filmId, branch)) {
                    return;
                }
                if (TogetherServerNetwork.denyLockedFilm(server, player, filmId)) {
                    return;
                }
                String key = TogetherServerNetwork.branchKey(filmId, branch);
                Film stored = FilmBranchStore.working(filmId, branch);
                Film incomingFilm = new Film();
                incomingFilm.fromData((BaseType)incoming.asMap());
                MapType before = lastSavedFilmSnapshots.containsKey(key) ? lastSavedFilmSnapshots.get(key) : (MapType)stored.toData();
                TogetherServerNetwork.mergeFilms(stored, incomingFilm);
                MapType merged = (MapType)stored.toData();
                ArrayList<String> changedClips = TogetherServerNetwork.collectChangedClips(before, merged);
                playerEditingFilm.put(player.getUuid(), filmId);
                TogetherServerNetwork.saveBranchNow(filmId, branch, merged);
                lastSavedFilmSnapshots.put(key, merged);
                AtomicBoolean pending = filmSaveDirty.get(key);
                if (pending != null) {
                    pending.set(false);
                }
                ScheduledFuture<?> pendingTask = filmSaveTasks.remove(key);
                if (pendingTask != null) {
                    pendingTask.cancel(false);
                }
                filmSaveFirstDirty.remove(key);
                if (!BaseType.equals((BaseType)before, (BaseType)merged)) {
                    BehaviorLogManager.record(player, BehaviorType.EDIT.id, 1, filmId, changedClips, Map.of("replays", String.valueOf(TogetherServerNetwork.mergedFilmReplays(merged)), "branch", branch));
                }
                TogetherServerNetwork.broadcastFilmSaved(server, filmId, branch, merged);
                TogetherServerNetwork.broadcastFilmSelections(server);
                TogetherServerNetwork.broadcastReplaySelections(server, filmId);
            });
        });
    }

    private static void handleCommitIndex(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        server.execute(() -> TogetherServerNetwork.crusherFor(player).send((PlayerEntity)player, CLIENT_COMMIT_INDEX, (BaseType)FilmCommitStore.index(), outBuf -> {}));
    }

    private static void handleCommitsQuery(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        String filmId = buf.readString();
        server.execute(() -> {
            MapType graph = FilmCommitStore.list(filmId);
            if (graph == null) {
                graph = new MapType();
                graph.putString("film", filmId);
                graph.putString("head", "");
                graph.put("commits", (BaseType)new ListType());
                graph.put("branches", (BaseType)new MapType());
            }
            TogetherServerNetwork.sendCommits(player, graph, filmId, TogetherServerNetwork.playerBranch(filmId, player));
            TogetherServerNetwork.sendCommitSelectionsTo(player, filmId);
            TogetherServerNetwork.sendPlayerCommitPositionsTo(server, player, filmId);
        });
    }

    private static void handleCommitDiff(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        String filmId = buf.readString();
        String commitId = buf.readString();
        server.execute(() -> {
            MapType diff = FilmCommitStore.diff(filmId, commitId);
            if (diff == null) {
                return;
            }
            TogetherServerNetwork.crusherFor(player).send((PlayerEntity)player, CLIENT_COMMIT_DIFF, (BaseType)diff, outBuf -> {
                outBuf.writeString(filmId);
                outBuf.writeString(commitId);
            });
        });
    }

    private static void handleCommitHead(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        String filmId = buf.readString();
        server.execute(() -> {
            MapType data;
            FilmCommitStore.Store store = FilmCommitStore.get(filmId);
            String branch = TogetherServerNetwork.playerBranch(filmId, player);
            String head = store == null ? "" : store.branchHead(branch);
            String headValue = head == null ? "" : head;
            MapType mapType = data = store == null || headValue.isEmpty() ? new MapType() : FilmCommitStore.snapshot(store, headValue);
            if (data == null) {
                data = new MapType();
            }
            TogetherServerNetwork.crusherFor(player).send((PlayerEntity)player, CLIENT_COMMIT_HEAD, (BaseType)data, outBuf -> {
                outBuf.writeString(filmId);
                outBuf.writeString(headValue);
            });
        });
    }

    private static void handleCommitSelection(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        String filmId = buf.readString(512);
        String commitId = buf.readString(64);
        server.execute(() -> {
            UUID uuid = player.getUuid();
            if (commitId.isEmpty() || filmId.isEmpty()) {
                CommitSelection previous = playerCommitSelections.remove(uuid);
                if (previous != null) {
                    TogetherServerNetwork.broadcastCommitSelections(server, previous.filmId);
                }
                return;
            }
            if (FilmCommitStore.branchOfCommit(filmId, commitId) == null) {
                CommitSelection gone = playerCommitSelections.remove(uuid);
                if (gone != null) {
                    TogetherServerNetwork.broadcastCommitSelections(server, gone.filmId);
                }
                return;
            }
            CommitSelection previous = playerCommitSelections.put(uuid, new CommitSelection(filmId, commitId));
            if (previous != null && !previous.filmId.equals(filmId)) {
                TogetherServerNetwork.broadcastCommitSelections(server, previous.filmId);
            }
            TogetherServerNetwork.broadcastCommitSelections(server, filmId);
        });
    }

    private static void broadcastCommitSelections(MinecraftServer server, String filmId) {
        if (filmId == null || filmId.isEmpty()) {
            return;
        }
        for (ServerPlayerEntity recipient : server.getPlayerManager().getPlayerList()) {
            TogetherServerNetwork.sendCommitSelectionsTo(recipient, filmId);
        }
    }

    private static void sendCommitSelectionsTo(ServerPlayerEntity recipient, String filmId) {
        ArrayList<Map.Entry<UUID, CommitSelection>> entries = new ArrayList<Map.Entry<UUID, CommitSelection>>();
        for (Map.Entry<UUID, CommitSelection> entry : playerCommitSelections.entrySet()) {
            if (!filmId.equals(entry.getValue().filmId)) continue;
            entries.add(entry);
        }
        PacketByteBuf outBuf = PacketByteBufs.create();
        outBuf.writeString(filmId);
        outBuf.writeInt(entries.size());
        for (Map.Entry entry : entries) {
            outBuf.writeUuid((UUID)entry.getKey());
            outBuf.writeString(((CommitSelection)entry.getValue()).commitId);
        }
        ServerPlayNetworking.send((ServerPlayerEntity)recipient, (Identifier)CLIENT_COMMIT_SELECTIONS, (PacketByteBuf)outBuf);
    }

    private static void sendAllCommitSelections(MinecraftServer server, ServerPlayerEntity player) {
        HashSet<String> films = new HashSet<String>();
        for (CommitSelection selection : playerCommitSelections.values()) {
            films.add(selection.filmId);
        }
        for (String filmId : films) {
            TogetherServerNetwork.sendCommitSelectionsTo(player, filmId);
        }
    }

    private static void broadcastPlayerCommitPositions(MinecraftServer server, String filmId) {
        if (filmId == null || filmId.isEmpty()) {
            return;
        }
        for (ServerPlayerEntity recipient : server.getPlayerManager().getPlayerList()) {
            TogetherServerNetwork.sendPlayerCommitPositionsTo(server, recipient, filmId);
        }
    }

    private static void sendPlayerCommitPositionsTo(MinecraftServer server, ServerPlayerEntity recipient, String filmId) {
        LinkedHashMap<UUID, String> positions = new LinkedHashMap<UUID, String>();
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            String branch;
            String head;
            UUID uuid = p.getUuid();
            if (!filmId.equals(playerEditingFilm.get(uuid)) && !filmId.equals(playerInFilmScreen.get(uuid)) || (head = FilmCommitStore.branchHead(filmId, branch = TogetherServerNetwork.playerBranch(filmId, p))) == null || head.isEmpty()) continue;
            positions.put(uuid, head);
        }
        PacketByteBuf outBuf = PacketByteBufs.create();
        outBuf.writeString(filmId);
        outBuf.writeInt(positions.size());
        for (Map.Entry entry : positions.entrySet()) {
            outBuf.writeUuid((UUID)entry.getKey());
            outBuf.writeString((String)entry.getValue());
        }
        ServerPlayNetworking.send((ServerPlayerEntity)recipient, (Identifier)CLIENT_PLAYER_COMMIT_POSITIONS, (PacketByteBuf)outBuf);
    }

    private static void sendAllPlayerCommitPositions(MinecraftServer server, ServerPlayerEntity recipient) {
        HashSet<String> films = new HashSet<String>();
        films.addAll(playerEditingFilm.values());
        films.addAll(playerInFilmScreen.values());
        for (String filmId : films) {
            TogetherServerNetwork.sendPlayerCommitPositionsTo(server, recipient, filmId);
        }
    }

    private static void handleCommitRename(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        if (!BbsOpManager.isAllowed(player, BbsOpDomain.FILM)) {
            return;
        }
        String filmId = buf.readString();
        String commitId = buf.readString();
        String message = buf.readString(1024);
        server.execute(() -> {
            if (TogetherServerNetwork.denyLockedFilm(server, player, filmId)) {
                return;
            }
            String author = FilmCommitStore.authorOf(filmId, commitId);
            if (author == null) {
                return;
            }
            if (!author.equals(player.getUuid().toString()) && !BbsOpManager.canManage(player)) {
                return;
            }
            if (!FilmCommitStore.rename(filmId, commitId, message)) {
                return;
            }
            TogetherServerNetwork.broadcastCommitRenamed(server, filmId, commitId, message.trim());
        });
    }

    private static void handleCommitColor(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        if (!BbsOpManager.isAllowed(player, BbsOpDomain.FILM)) {
            return;
        }
        String filmId = buf.readString(512);
        String commitId = buf.readString(64);
        int color = Colors.opaque((int)buf.readInt());
        server.execute(() -> {
            if (TogetherServerNetwork.denyLockedFilm(server, player, filmId)) {
                return;
            }
            if (!FilmCommitStore.setColor(filmId, commitId, color)) {
                return;
            }
            TogetherServerNetwork.broadcastCommitColor(server, filmId, commitId, color);
        });
    }

    private static void handleCommitRevert(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        if (!BbsOpManager.isAllowed(player, BbsOpDomain.FILM)) {
            return;
        }
        String filmId = buf.readString();
        String commitId = buf.readString();
        byte mode = buf.readByte();
        server.execute(() -> {
            if (mode < 0 || mode > 3) {
                return;
            }
            String targetBranch = FilmCommitStore.branchOfCommit(filmId, commitId);
            if (targetBranch == null || targetBranch.isEmpty()) {
                return;
            }
            /* A commit from another branch is not part of this checkout; refusing here keeps a
             * stale/hand-crafted packet from moving the wrong branch. */
            if (!targetBranch.equals(TogetherServerNetwork.playerBranch(filmId, player))) {
                return;
            }
            if (CoopSessionManager.isFilmLockedForEditing(filmId, targetBranch)) {
                return;
            }
            if (TogetherServerNetwork.denyLockedFilm(server, player, filmId)) {
                return;
            }
            MapType snapshot = FilmCommitStore.rollback(filmId, commitId, mode);
            if (snapshot == null) {
                return;
            }
            Film film = new Film();
            film.fromData((BaseType)snapshot);
            MapType reverted = (MapType)film.toData();
            playerEditingFilm.put(player.getUuid(), filmId);
            TogetherServerNetwork.saveBranchNow(filmId, targetBranch, reverted);
            lastSavedFilmSnapshots.put(TogetherServerNetwork.branchKey(filmId, targetBranch), reverted);
            BehaviorLogManager.record(player, BehaviorType.EDIT.id, 1, filmId, new ArrayList<String>(), Map.of("commit", commitId, "rollback", String.valueOf(mode), "branch", targetBranch));
            TogetherServerNetwork.broadcastCommits(server, filmId, FilmCommitStore.list(filmId));
            TogetherServerNetwork.broadcastFilmSaved(server, filmId, targetBranch, reverted);
            TogetherServerNetwork.broadcastFilmSelections(server);
            TogetherServerNetwork.broadcastReplaySelections(server, filmId);
            TogetherServerNetwork.broadcastPlayerCommitPositions(server, filmId);
        });
    }

    /** Squash the current branch from its head down to the picked commit. */
    private static void handleCommitSquash(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        if (!BbsOpManager.isAllowed(player, BbsOpDomain.FILM)) {
            return;
        }
        String filmId = buf.readString();
        String commitId = buf.readString();
        server.execute(() -> {
            String targetBranch = FilmCommitStore.branchOfCommit(filmId, commitId);
            if (targetBranch == null || targetBranch.isEmpty()) {
                return;
            }
            if (!targetBranch.equals(TogetherServerNetwork.playerBranch(filmId, player))) {
                return;
            }
            if (CoopSessionManager.isFilmLockedForEditing(filmId, targetBranch)) {
                return;
            }
            if (TogetherServerNetwork.denyLockedFilm(server, player, filmId)) {
                return;
            }
            FilmCommit squashed = FilmCommitStore.squash(filmId, commitId, player.getUuid().toString(), player.getGameProfile().getName(), null);
            if (squashed == null) {
                player.sendMessage(Text.literal("§e[BBS Together] 无法合并到此提交：该历史区间被其他分支引用，或提交已不在当前分支上"), false);
                return;
            }
            playerEditingFilm.put(player.getUuid(), filmId);
            BehaviorLogManager.record(player, BehaviorType.EDIT.id, 1, filmId, new ArrayList<String>(), Map.of("commit", squashed.id, "squashed", commitId, "branch", targetBranch));
            TogetherServerNetwork.broadcastCommits(server, filmId, FilmCommitStore.list(filmId));
            TogetherServerNetwork.broadcastPlayerCommitPositions(server, filmId);
        });
    }

    private static void handleCommitCreate(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        if (!BbsOpManager.isAllowed(player, BbsOpDomain.FILM)) {
            return;
        }
        TogetherServerNetwork.crusherFor(player).receive(buf, (bytes, packetByteBuf) -> {
            String filmId = packetByteBuf.readString();
            String message = packetByteBuf.readString(240);
            String sentBranch = packetByteBuf.isReadable() ? packetByteBuf.readString(80) : "";
            MapType payload = TogetherServerNetwork.readFilmPayload(bytes);
            server.execute(() -> {
                MapType data;
                if (deletedFilmIds.contains(filmId)) {
                    TogetherServerNetwork.rejectCommit(player, 1);
                    return;
                }
                String branch = TogetherServerNetwork.playerBranch(filmId, player);
                if (!sentBranch.isEmpty() && !sentBranch.equals(branch)) {
                    return;
                }
                if (CoopSessionManager.isFilmLockedForEditing(filmId, branch)) {
                    return;
                }
                if (TogetherServerNetwork.denyLockedFilm(server, player, filmId)) {
                    return;
                }
                MapType mapType = data = payload != null ? payload : FilmBranchStore.workingData(filmId, branch);
                if (data == null) {
                    TogetherServerNetwork.rejectCommit(player, 1);
                    return;
                }
                FilmCommit commit = FilmCommitStore.commit(filmId, data, player.getUuid().toString(), player.getGameProfile().getName(), System.currentTimeMillis(), message, branch);
                if (commit == null) {
                    TogetherServerNetwork.rejectCommit(player, 0);
                    return;
                }
                TogetherServerNetwork.saveBranchNow(filmId, branch, data);
                lastSavedFilmSnapshots.put(TogetherServerNetwork.branchKey(filmId, branch), data);
                BehaviorLogManager.record(player, BehaviorType.EDIT.id, 1, filmId, new ArrayList<String>(), Map.of("commit", commit.id, "branch", branch));
                TogetherServerNetwork.broadcastCommitAdded(server, filmId, commit);
                TogetherServerNetwork.broadcastPlayerCommitPositions(server, filmId);
            });
        });
    }

    public static MapType readFilmPayload(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        BaseType data = DataStorageUtils.readFromBytes((byte[])bytes);
        return data != null && data.isMap() ? data.asMap() : null;
    }

    private static void branchCheckoutMessage(ServerPlayerEntity player, String branch, String reason, boolean success) {
        String message = success ? "\u00a7a[BBS Together] \u5df2\u5207\u6362\u5230\u5206\u652f \u00a7f" + branch : "\u00a7e[BBS Together] \u65e0\u6cd5\u5207\u6362\u5230\u5206\u652f \u00a7f" + branch + "\u00a7e\uff1a" + reason;
        player.sendMessage((Text)Text.literal((String)message), success);
        System.out.println("[BBSTogether] Branch checkout " + (success ? "ok" : "failed") + " player=" + player.getGameProfile().getName() + " film=" + playerEditingFilm.get(player.getUuid()) + " branch=" + branch + (String)(success ? "" : " reason=" + reason));
    }

    private static void handleBranchCreate(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        if (!BbsOpManager.isAllowed(player, BbsOpDomain.FILM)) {
            return;
        }
        String filmId = buf.readString();
        String name = buf.readString(80);
        String commitId = buf.readString();
        server.execute(() -> {
            if (deletedFilmIds.contains(filmId)) {
                return;
            }
            if (TogetherServerNetwork.denyLockedFilm(server, player, filmId)) {
                return;
            }
            if (!FilmCommitStore.createBranch(filmId, name, commitId)) {
                return;
            }
            TogetherServerNetwork.broadcastCommits(server, filmId, FilmCommitStore.list(filmId));
        });
    }

    private static void handleBranchCheckout(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        if (!BbsOpManager.isAllowed(player, BbsOpDomain.FILM)) {
            return;
        }
        String filmId = buf.readString();
        String branch = buf.readString(80);
        server.execute(() -> {
            System.out.println("[BBSTogether] Branch checkout request player=" + player.getGameProfile().getName() + " film=" + filmId + " branch=" + branch);
            if (CoopSessionManager.isPlayerInSession(player.getUuid())) {
                TogetherServerNetwork.branchCheckoutMessage(player, branch, "\u5408\u4f5c\u5f55\u5236\u6216\u9080\u8bf7\u4e2d\u4e0d\u80fd\u5207\u6362\u5206\u652f", false);
                return;
            }
            FilmCommitStore.Store store = FilmCommitStore.get(filmId);
            if (store == null || !store.branches.containsKey(branch) && !branch.equals(FilmBranchStore.defaultBranch(filmId))) {
                TogetherServerNetwork.branchCheckoutMessage(player, branch, "\u5206\u652f\u4e0d\u5b58\u5728", false);
                return;
            }
            if (CoopSessionManager.isFilmLockedForEditing(filmId, branch)) {
                TogetherServerNetwork.branchCheckoutMessage(player, branch, "\u8be5\u5206\u652f\u6b63\u5728\u88ab\u5408\u4f5c\u5f55\u5236\u5360\u7528", false);
                return;
            }
            if (TogetherServerNetwork.denyLockedFilm(server, player, filmId)) {
                TogetherServerNetwork.branchCheckoutMessage(player, branch, "\u8be5\u5f71\u7247\u5df2\u88ab\u9501\u5b9a", false);
                return;
            }
            if (!FilmBranchStore.setBranch(filmId, player.getUuid(), branch)) {
                TogetherServerNetwork.branchCheckoutMessage(player, branch, "\u65e0\u6cd5\u4fdd\u5b58\u5206\u652f\u9009\u62e9", false);
                return;
            }
            String actual = FilmBranchStore.branchOf(filmId, player.getUuid());
            if (!branch.equals(actual)) {
                TogetherServerNetwork.branchCheckoutMessage(player, branch, "\u5206\u652f\u9009\u62e9\u672a\u751f\u6548\uff08\u5f53\u524d\u4e3a " + actual + "\uff09", false);
                return;
            }
            playerEditingFilm.put(player.getUuid(), filmId);
            TogetherServerNetwork.branchCheckoutMessage(player, branch, "", true);
            LinkedHashMap<UUID, Integer> linkedHashMap = playerReplaySelections;
            synchronized (linkedHashMap) {
                playerReplaySelections.remove(player.getUuid());
            }
            playerFormLocks.remove(player.getUuid());
            playerCursors.remove(player.getUuid());
            BehaviorLogManager.record(player, BehaviorType.EDIT.id, 1, filmId, new ArrayList<String>(), Map.of("branch", branch));
            TogetherServerNetwork.sendFilmBranchState(server, player, filmId);
            TogetherServerNetwork.broadcastFilmSelections(server);
            TogetherServerNetwork.broadcastReplaySelections(server, filmId);
            TogetherServerNetwork.broadcastReplayFormLocks(server, filmId);
            TogetherServerNetwork.together_broadcastCursorSnapshot(server, filmId);
            TogetherServerNetwork.broadcastPlayerCommitPositions(server, filmId);
        });
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public static boolean prepareCoopBranchSwitch(MinecraftServer server, ServerPlayerEntity player, String filmId, String targetBranch, MapType incomingData, String sentBranch) {
        MapType data;
        if (filmId == null || filmId.isEmpty() || targetBranch == null || targetBranch.isEmpty()) {
            return false;
        }
        String current = TogetherServerNetwork.playerBranch(filmId, player);
        if (incomingData != null && sentBranch != null && !sentBranch.isEmpty() && !sentBranch.equals(current)) {
            System.out.println("[BBSTogether] Ignore coop accept film from stale branch player=" + player.getGameProfile().getName() + " film=" + filmId + " sent=" + sentBranch + " current=" + current);
            incomingData = null;
        }
        if (targetBranch.equals(current)) {
            return true;
        }
        if (CoopSessionManager.isFilmLockedForEditing(filmId, current)) {
            System.out.println("[BBSTogether] Coop branch switch refused (locked) player=" + player.getGameProfile().getName() + " film=" + filmId + " branch=" + current);
            return false;
        }
        MapType mapType = data = incomingData != null ? incomingData : FilmBranchStore.workingData(filmId, current);
        if (data == null) {
            System.out.println("[BBSTogether] Coop branch switch refused (no working data) player=" + player.getGameProfile().getName() + " film=" + filmId + " branch=" + current);
            return false;
        }
        FilmCommit commit = FilmCommitStore.commit(filmId, data, player.getUuid().toString(), player.getGameProfile().getName(), System.currentTimeMillis(), "\u5408\u4f5c\u5f55\u5236\u524d\u81ea\u52a8\u63d0\u4ea4\uff08\u5207\u6362\u5230 " + targetBranch + "\uff09", current);
        if (commit != null || incomingData != null) {
            TogetherServerNetwork.saveBranchNow(filmId, current, data);
            lastSavedFilmSnapshots.put(TogetherServerNetwork.branchKey(filmId, current), data);
        }
        if (commit != null) {
            TogetherServerNetwork.broadcastCommitAdded(server, filmId, commit);
            BehaviorLogManager.record(player, BehaviorType.EDIT.id, 1, filmId, new ArrayList<String>(), Map.of("commit", commit.id, "branch", current, "coop", "accept"));
        }
        if (!FilmBranchStore.setBranch(filmId, player.getUuid(), targetBranch) || !targetBranch.equals(FilmBranchStore.branchOf(filmId, player.getUuid()))) {
            System.out.println("[BBSTogether] Coop branch switch failed player=" + player.getGameProfile().getName() + " film=" + filmId + " from=" + current + " to=" + targetBranch);
            return false;
        }
        playerEditingFilm.put(player.getUuid(), filmId);
        LinkedHashMap<UUID, Integer> linkedHashMap = playerReplaySelections;
        synchronized (linkedHashMap) {
            playerReplaySelections.remove(player.getUuid());
        }
        playerFormLocks.remove(player.getUuid());
        playerCursors.remove(player.getUuid());
        TogetherServerNetwork.sendFilmBranchState(server, player, filmId);
        TogetherServerNetwork.broadcastFilmSelections(server);
        TogetherServerNetwork.broadcastReplaySelections(server, filmId);
        TogetherServerNetwork.broadcastReplayFormLocks(server, filmId);
        TogetherServerNetwork.together_broadcastCursorSnapshot(server, filmId);
        TogetherServerNetwork.broadcastPlayerCommitPositions(server, filmId);
        player.sendMessage((Text)Text.literal((String)("\u00a7a[BBS Together] \u5df2\u63d0\u4ea4\u5f53\u524d\u5206\u652f \u00a7f" + current + "\u00a7a\uff0c\u5e76\u5207\u6362\u5230 \u00a7f" + targetBranch + "\u00a7a \u4ee5\u52a0\u5165\u5408\u4f5c\u5f55\u5236")), false);
        System.out.println("[BBSTogether] Coop branch switch player=" + player.getGameProfile().getName() + " film=" + filmId + " from=" + current + " to=" + targetBranch + (String)(commit == null ? " (nothing to commit)" : " commit=" + commit.id));
        return true;
    }

    private static void broadcastCommits(MinecraftServer server, String filmId, MapType graph) {
        for (ServerPlayerEntity recipient : server.getPlayerManager().getPlayerList()) {
            TogetherServerNetwork.sendCommits(recipient, graph, filmId, TogetherServerNetwork.playerBranch(filmId, recipient));
        }
    }

    private static void sendCommits(ServerPlayerEntity recipient, MapType graph, String filmId, String branch) {
        if (graph == null) {
            graph = new MapType();
            graph.putString("film", filmId);
            graph.putString("head", "");
            graph.putString("current", branch == null ? "" : branch);
            graph.put("commits", (BaseType)new ListType());
            graph.put("branches", (BaseType)new MapType());
        }
        MapType perPlayer = new MapType();
        perPlayer.combine(graph);
        perPlayer.putString("current", branch == null ? "" : branch);
        String branchHead = FilmCommitStore.branchHead(filmId, branch);
        perPlayer.putString("head", branchHead == null ? "" : branchHead);
        TogetherServerNetwork.crusherFor(recipient).send((PlayerEntity)recipient, CLIENT_COMMITS, (BaseType)perPlayer, outBuf -> {
            outBuf.writeString(filmId);
            outBuf.writeString(branch == null ? "" : branch);
        });
    }

    private static void broadcastCommitAdded(MinecraftServer server, String filmId, FilmCommit commit) {
        for (ServerPlayerEntity recipient : server.getPlayerManager().getPlayerList()) {
            TogetherServerNetwork.crusherFor(recipient).send((PlayerEntity)recipient, CLIENT_COMMIT_ADDED, (BaseType)commit.toData(), outBuf -> outBuf.writeString(filmId));
        }
    }

    private static void broadcastCommitRenamed(MinecraftServer server, String filmId, String commitId, String message) {
        for (ServerPlayerEntity recipient : server.getPlayerManager().getPlayerList()) {
            PacketByteBuf outBuf = PacketByteBufs.create();
            outBuf.writeString(filmId);
            outBuf.writeString(commitId);
            outBuf.writeString(message);
            ServerPlayNetworking.send((ServerPlayerEntity)recipient, (Identifier)CLIENT_COMMIT_RENAMED, (PacketByteBuf)outBuf);
        }
    }

    private static void broadcastCommitColor(MinecraftServer server, String filmId, String commitId, int color) {
        for (ServerPlayerEntity recipient : server.getPlayerManager().getPlayerList()) {
            PacketByteBuf outBuf = PacketByteBufs.create();
            outBuf.writeString(filmId);
            outBuf.writeString(commitId);
            outBuf.writeInt(color);
            ServerPlayNetworking.send((ServerPlayerEntity)recipient, (Identifier)CLIENT_COMMIT_COLOR, (PacketByteBuf)outBuf);
        }
    }

    private static void rejectCommit(ServerPlayerEntity player, int reason) {
        PacketByteBuf outBuf = PacketByteBufs.create();
        outBuf.writeByte(reason);
        ServerPlayNetworking.send((ServerPlayerEntity)player, (Identifier)CLIENT_COMMIT_REJECTED, (PacketByteBuf)outBuf);
    }

    private static int mergedFilmReplays(MapType filmData) {
        try {
            return filmData.getList("replays").size();
        }
        catch (Exception e) {
            return 0;
        }
    }

    private static ArrayList<String> collectChangedClips(MapType before, MapType merged) {
        int i;
        ArrayList<String> changed = new ArrayList<String>();
        ListType oldClips = before.getList("camera");
        ListType newClips = merged.getList("camera");
        int shared = Math.min(oldClips.size(), newClips.size());
        for (i = 0; i < shared; ++i) {
            if (BaseType.equals((BaseType)oldClips.getMap(i), (BaseType)newClips.getMap(i))) continue;
            changed.add(TogetherServerNetwork.clipLabel(newClips.getMap(i), i));
        }
        for (i = shared; i < newClips.size(); ++i) {
            changed.add(TogetherServerNetwork.clipLabel(newClips.getMap(i), i));
        }
        for (i = shared; i < oldClips.size(); ++i) {
            changed.add(TogetherServerNetwork.clipLabel(oldClips.getMap(i), i));
        }
        return changed;
    }

    private static String clipLabel(MapType clip, int index) {
        String title = clip.getString("title");
        return title == null || title.isEmpty() ? "#" + index : title;
    }

    private static void recordDownloadBehavior(ServerPlayerEntity player, List<String> paths) {
        try {
            ArrayList<String> modelIds = new ArrayList<String>();
            long totalBytes = 0L;
            File assetsFolder = BBSMod.getAssetsFolder();
            for (String path : paths) {
                if (ModelPathUtils.isSkinPath(path)) continue;
                String modelId = ModelPathUtils.extractModelId(path);
                if (modelId != null && !ModelPathUtils.isSkinPath("/" + modelId) && !modelIds.contains(modelId)) {
                    modelIds.add(modelId);
                }
                if (assetsFolder == null) continue;
                try {
                    File asset = new File(assetsFolder, path.replace("/", File.separator));
                    if (!asset.isFile()) continue;
                    totalBytes += asset.length();
                }
                catch (Exception exception) {}
            }
            if (modelIds.isEmpty()) {
                return;
            }
            BehaviorLogManager.record(player, BehaviorType.DOWNLOAD.id, 3, (String)modelIds.get(0), modelIds, Map.of("bytes", String.valueOf(totalBytes)));
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void recordUploadBehavior(ServerPlayerEntity player, String relativePath, long bytes) {
        try {
            if (ModelPathUtils.isSkinPath(relativePath)) {
                return;
            }
            String lower = relativePath.toLowerCase();
            if (lower.endsWith(".zip") || lower.endsWith(".rar") || lower.endsWith(".7z")) {
                return;
            }
            String modelId = ModelPathUtils.extractModelId(relativePath);
            if (modelId == null || modelId.isEmpty()) {
                return;
            }
            TogetherServerNetwork.queueUploadBehavior(player, modelId, bytes);
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private static void queueUploadBehavior(ServerPlayerEntity player, String modelId, long bytes) {
        Map<UUID, Map<String, PendingUpload>> map = pendingUploadAggregates;
        synchronized (map) {
            Map<String, PendingUpload> byModel = pendingUploadAggregates.computeIfAbsent(player.getUuid(), k -> new HashMap<>());
            PendingUpload pending = byModel.computeIfAbsent(modelId, k -> new PendingUpload());
            ++pending.files;
            pending.bytes += bytes;
            pending.lastTime = System.currentTimeMillis();
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private static void flushUploadAggregates(ServerPlayerEntity player, boolean forceAll) {
        UUID id = player.getUuid();
        long now = System.currentTimeMillis();
        ArrayList<String> flushedModels = new ArrayList<String>();
        ArrayList<PendingUpload> flushedData = new ArrayList<PendingUpload>();
        Map<UUID, Map<String, PendingUpload>> map = pendingUploadAggregates;
        synchronized (map) {
            Map<String, PendingUpload> models = pendingUploadAggregates.get(id);
            if (models == null) {
                return;
            }
            for (Map.Entry<String, PendingUpload> entry : models.entrySet()) {
                if (!forceAll && now - entry.getValue().lastTime < 3000L) continue;
                flushedModels.add(entry.getKey());
                flushedData.add(entry.getValue());
            }
            for (String modelId : flushedModels) {
                models.remove(modelId);
            }
            if (models.isEmpty()) {
                pendingUploadAggregates.remove(id);
            }
        }
        for (int i = 0; i < flushedModels.size(); ++i) {
            try {
                PendingUpload pending = (PendingUpload)flushedData.get(i);
                HashMap<String, String> extra = new HashMap<String, String>();
                extra.put("bytes", String.valueOf(pending.bytes));
                extra.put("files", String.valueOf(pending.files));
                BehaviorLogManager.record(player, BehaviorType.UPLOAD.id, 4, "", Collections.singletonList((String)flushedModels.get(i)), extra);
                continue;
            }
            catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public static void sweepUploadAggregates(MinecraftServer server) {
        if (pendingUploadAggregates.isEmpty()) {
            return;
        }
        for (UUID id : new ArrayList<UUID>(pendingUploadAggregates.keySet())) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(id);
            if (player != null) {
                TogetherServerNetwork.flushUploadAggregates(player, false);
                continue;
            }
            pendingUploadAggregates.remove(id);
        }
    }

    private static void handleBehaviorReport(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        int subType = buf.readInt();
        String target = buf.readString();
        int itemCount = buf.readInt();
        ArrayList<String> items = new ArrayList<String>();
        for (int i = 0; i < Math.min(itemCount, 64); ++i) {
            items.add(buf.readString());
        }
        ArrayList<String> finalItems = items;
        server.execute(() -> {
            try {
                BehaviorLogManager.record(player, BehaviorType.EDIT.id, subType, target, finalItems, null);
            }
            catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private static void handleBehaviorQuery(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        long afterId = buf.readLong();
        long clientVersion = buf.readLong();
        server.execute(() -> {
            try {
                boolean isOp = BehaviorLogManager.isOperator(server, player);
                MapType snapshot = BehaviorLogManager.query(server, player.getUuid(), afterId, clientVersion, isOp);
                TogetherServerNetwork.crusherFor(player).send((PlayerEntity)player, CLIENT_BEHAVIOR_LIST, (BaseType)snapshot, null);
            }
            catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private static void handleSetOperator(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        UUID targetId = buf.readUuid();
        boolean op = buf.readBoolean();
        server.execute(() -> {
            try {
                if (!BehaviorLogManager.isOperator(server, player)) {
                    System.out.println("[BBSTogether] " + player.getGameProfile().getName() + " tried to " + (op ? "op" : "deop") + " someone without being an op");
                    return;
                }
                boolean done = BehaviorLogManager.setOperator(server, targetId, op);
                System.out.println("[BBSTogether] " + player.getGameProfile().getName() + (done ? (op ? " op'ed " : " deop'ed ") : " FAILED to change op status of ") + String.valueOf(targetId));
                TogetherServerNetwork.broadcastPermissions(server);
            }
            catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private static void handleBbsopSet(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        UUID targetId = buf.readUuid();
        int level = Math.max(0, Math.min(2, buf.readInt()));
        server.execute(() -> {
            try {
                if (!BbsOpManager.canManage(player)) {
                    System.out.println("[BBSTogether] " + player.getGameProfile().getName() + " tried to set a BBS op level without permission");
                    return;
                }
                boolean changed = BbsOpManager.set(targetId, level);
                ServerPlayerEntity target = server.getPlayerManager().getPlayer(targetId);
                String targetName = target == null ? String.valueOf(targetId) : target.getGameProfile().getName();
                System.out.println("[BBSTogether] " + player.getGameProfile().getName() + (changed ? " set " : " kept ") + targetName + " BBS op level at " + level);
                BbsOpManager.syncToAll(server);
                TogetherServerNetwork.broadcastPermissions(server);
            }
            catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private static void handleBbsopGrant(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        UUID targetId = buf.readUuid();
        String domainId = buf.readString();
        boolean allowed = buf.readBoolean();
        server.execute(() -> {
            try {
                if (!BbsOpManager.canManage(player)) {
                    System.out.println("[BBSTogether] " + player.getGameProfile().getName() + " tried to change a BBS domain grant without permission");
                    return;
                }
                BbsOpDomain domain = BbsOpDomain.byId(domainId);
                if (domain == null) {
                    return;
                }
                boolean changed = BbsOpManager.setGrant(targetId, domain, allowed);
                if (changed) {
                    System.out.println("[BBSTogether] " + player.getGameProfile().getName() + (allowed ? " allowed " : " denied ") + domain.id + " for " + String.valueOf(targetId));
                    BbsOpManager.syncToAll(server);
                }
            }
            catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private static void handleAssetLock(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        String typeId = buf.readString(32);
        String assetId = buf.readString(512);
        int modeOrdinal = buf.readInt();
        int count = Math.min(buf.readInt(), 256);
        LinkedHashMap<UUID, String> members = new LinkedHashMap<>();
        for (int i = 0; i < count; ++i) {
            try {
                UUID id = buf.readUuid();
                String name = buf.readString(64);
                members.put(id, name);
            }
            catch (Exception e) {
                break;
            }
        }
        server.execute(() -> {
            try {
                if (!BbsOpManager.canManage(player)) {
                    System.out.println("[BBSTogether] " + player.getGameProfile().getName() + " tried to change an asset lock without permission");
                    return;
                }
                AssetLockType type = AssetLockType.byId(typeId);
                if (type == null) {
                    return;
                }
                String id = AssetLockManager.normalizeId(type, assetId);
                if (id == null) {
                    return;
                }
                AssetLockMode mode = modeOrdinal < 0 ? null : AssetLockMode.byOrdinal(modeOrdinal);
                if (modeOrdinal >= 0 && mode == null) {
                    return;
                }
                boolean changed = AssetLockManager.set(player, type, id, mode, members);
                if (changed) {
                    System.out.println("[BBSTogether] " + player.getGameProfile().getName()
                        + (mode == null ? " unlocked " : " locked ") + type.id + " " + id);
                    TogetherServerNetwork.broadcastAssetLocks(server);
                }
            }
            catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    /** Push every lock to one client; called on join and after every change. */
    public static void sendAssetLocks(ServerPlayerEntity player) {
        if (player == null) {
            return;
        }
        ArrayList<AssetLock> locks = new ArrayList<>(AssetLockManager.snapshot());
        PacketByteBuf out = PacketByteBufs.create();
        out.writeInt(locks.size());
        for (AssetLock lock : locks) {
            out.writeString(lock.type.id);
            out.writeString(lock.assetId);
            out.writeString(lock.owner == null ? "" : lock.owner.toString());
            out.writeString(lock.ownerName == null ? "" : lock.ownerName);
            out.writeInt(lock.mode.ordinal());
            TogetherServerNetwork.writeLockMembers(out, lock.whitelist);
            TogetherServerNetwork.writeLockMembers(out, lock.blacklist);
        }
        ServerPlayNetworking.send(player, CLIENT_ASSET_LOCKS, out);
    }

    private static void writeLockMembers(PacketByteBuf out, Map<UUID, String> members) {
        out.writeInt(members.size());
        for (Map.Entry<UUID, String> entry : members.entrySet()) {
            out.writeUuid(entry.getKey());
            out.writeString(entry.getValue() == null ? "" : entry.getValue());
        }
    }

    public static void broadcastAssetLocks(MinecraftServer server) {
        if (server == null) {
            return;
        }
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            TogetherServerNetwork.sendAssetLocks(p);
        }
    }

    private static void handleRemovePlayerLogs(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        UUID targetId = buf.readUuid();
        server.execute(() -> {
            try {
                if (!BehaviorLogManager.isOperator(server, player)) {
                    return;
                }
                int removed = BehaviorLogManager.removePlayerLogs(targetId);
                System.out.println("[BBSTogether] " + player.getGameProfile().getName() + " removed " + removed + " log entries of " + String.valueOf(targetId));
            }
            catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private static boolean isModelTombstoned(String modelId) {
        Long expiry = recentlyDeletedModels.get(modelId);
        if (expiry == null) {
            return false;
        }
        if (expiry < System.currentTimeMillis()) {
            recentlyDeletedModels.remove(modelId);
            return false;
        }
        return true;
    }

    private static void tombstoneModel(String modelId) {
        long now = System.currentTimeMillis();
        recentlyDeletedModels.put(modelId, now + 1800000L);
        recentlyDeletedModels.values().removeIf(expiry -> expiry < now);
    }

    private static boolean modelFolderHasFiles(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return false;
        }

        try (Stream<Path> walk = Files.walk(dir.toPath())) {
            return walk.anyMatch(Files::isRegularFile);
        }
        catch (Exception e) {
            File[] files = dir.listFiles();
            return files != null && files.length > 0;
        }
    }

    private static void handleModelDeleteRequest(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        String modelId = buf.readString();
        server.execute(() -> {
            try {
                if (modelId.isEmpty() || modelId.contains("..") || modelId.startsWith("/") || modelId.endsWith("/")) {
                    return;
                }
                if (!AssetLockManager.canEdit(player.getUuid(), AssetLockType.MODEL, modelId)) {
                    AssetLockManager.notifyLocked(server, player, AssetLockType.MODEL);
                    return;
                }
                File assetsFolder = BBSMod.getAssetsFolder();
                File modelDir = new File(assetsFolder, "models/" + modelId.replace('/', File.separatorChar));
                if (!TogetherServerNetwork.modelFolderHasFiles(modelDir)) {
                    System.out.println("[BBSTogether] " + player.getGameProfile().getName() + " requested deletion of model " + modelId + " which does not exist on the server");
                    return;
                }
                if (BehaviorLogManager.hasPendingRequest(modelId)) {
                    return;
                }
                BehaviorLogManager.recordUnique(player, BehaviorType.OTHER.id, 6, modelId, Collections.singletonList(modelId), Map.of("status", "pending"));
                System.out.println("[BBSTogether] " + player.getGameProfile().getName() + " requested server deletion of model " + modelId);
            }
            catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private static void handleModelDeleteDecision(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        long entryId = buf.readLong();
        boolean approve = buf.readBoolean();
        server.execute(() -> {
            try {
                boolean isManager;
                boolean bl = isManager = BehaviorLogManager.isOperator(server, player) || BbsOpManager.get(player.getUuid()) >= 2;
                if (!isManager) {
                    System.out.println("[BBSTogether] " + player.getGameProfile().getName() + " tried to decide a model delete request without being a manager");
                    return;
                }
                BehaviorEntry entry = BehaviorLogManager.find(entryId);
                if (entry == null || entry.type != BehaviorType.OTHER.id || entry.subType != 6 || !"pending".equals(entry.extra.get("status"))) {
                    return;
                }
                entry.extra.put("status", approve ? "approved" : "denied");
                entry.extra.put("by", player.getGameProfile().getName());
                entry.extra.put("byId", player.getUuid().toString());
                entry.extra.put("decidedAt", String.valueOf(System.currentTimeMillis()));
                BehaviorLogManager.bump();
                if (approve) {
                    TogetherServerNetwork.deleteServerModel(server, entry.target);
                }
                System.out.println("[BBSTogether] " + player.getGameProfile().getName() + (approve ? " approved" : " denied") + " the deletion of model " + entry.target);
            }
            catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private static void deleteServerModel(MinecraftServer server, String modelId) {
        if (modelId == null || modelId.isEmpty() || modelId.contains("..")) {
            return;
        }
        File modelDir = new File(BBSMod.getAssetsFolder(), "models/" + modelId.replace('/', File.separatorChar));
        boolean removed = TogetherServerNetwork.deleteModelFolder(modelDir);
        if (!removed) {
            System.out.println("[BBSTogether] Model " + modelId + " was already gone from the server assets");
        }
        TogetherServerNetwork.tombstoneModel(modelId);
        AssetLockManager.removeForAsset(AssetLockType.MODEL, modelId);
        for (ServerPlayerEntity other : server.getPlayerManager().getPlayerList()) {
            PacketByteBuf out = PacketByteBufs.create();
            out.writeString(modelId);
            ServerPlayNetworking.send((ServerPlayerEntity)other, (Identifier)CLIENT_MODEL_DELETED, (PacketByteBuf)out);
            ServerPlayNetworking.send((ServerPlayerEntity)other, (Identifier)CLIENT_ASSETS_CHANGED, (PacketByteBuf)PacketByteBufs.create());
        }
    }

    private static boolean deleteModelFolder(File dir) {
        if (dir == null || !dir.exists()) {
            return false;
        }
        try {
            Files.walkFileTree(dir.toPath(), (FileVisitor<? super Path>)new SimpleFileVisitor<Path>(){

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                    Files.deleteIfExists(d);
                    return FileVisitResult.CONTINUE;
                }
            });
            return !dir.exists();
        }
        catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public static void sendPermissions(MinecraftServer server, ServerPlayerEntity player) {
        boolean isOp = BehaviorLogManager.isOperator(server, player);
        boolean isManager = isOp || BbsOpManager.get(player.getUuid()) >= 2;
        PacketByteBuf out = PacketByteBufs.create();
        out.writeBoolean(isOp);
        out.writeBoolean(isManager);
        ServerPlayNetworking.send((ServerPlayerEntity)player, (Identifier)CLIENT_PERMISSIONS, (PacketByteBuf)out);
    }

    public static void broadcastPermissions(MinecraftServer server) {
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            TogetherServerNetwork.sendPermissions(server, p);
        }
    }

    private static void handleFilmDeleted(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        if (!BbsOpManager.isAllowed(player, BbsOpDomain.FILM)) {
            return;
        }
        String filmId = buf.readString();
        server.execute(() -> {
            if (TogetherServerNetwork.denyLockedFilm(server, player, filmId)) {
                return;
            }
            deletedFilmIds.add(filmId);
            AssetLockManager.removeForAsset(AssetLockType.FILM, filmId);
            lastSavedFilmSnapshots.keySet().removeIf(key -> key.startsWith(filmId + "\t"));
            filmSaveDirty.keySet().removeIf(key -> key.startsWith(filmId + "\t"));
            try {
                BBSMod.getFilms().delete(filmId);
            }
            catch (Exception exception) {
                // empty catch block
            }
            FilmCommitStore.deleteFilm(filmId);
            FilmBranchStore.forgetFilm(filmId);
            playerCommitSelections.entrySet().removeIf(entry -> filmId.equals(((CommitSelection)entry.getValue()).filmId));
            playerEditingFilm.entrySet().removeIf(entry -> filmId.equals(entry.getValue()));
            playerInFilmScreen.entrySet().removeIf(entry -> filmId.equals(entry.getValue()));
            playerCursors.entrySet().removeIf(entry -> filmId.equals(((CursorData)entry.getValue()).filmId));
            playerFormLocks.entrySet().removeIf(entry -> filmId.equals(((FormLockData)entry.getValue()).filmId));
            for (ServerPlayerEntity other : server.getPlayerManager().getPlayerList()) {
                PacketByteBuf out = PacketByteBufs.create();
                out.writeString(filmId);
                ServerPlayNetworking.send((ServerPlayerEntity)other, (Identifier)CLIENT_FILM_DELETED, (PacketByteBuf)out);
            }
        });
    }

    private static void handleFilmRenamed(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        if (!BbsOpManager.isAllowed(player, BbsOpDomain.FILM)) {
            return;
        }
        String oldId = buf.readString();
        String newId = buf.readString();
        server.execute(() -> {
            if (TogetherServerNetwork.denyLockedFilm(server, player, oldId)) {
                return;
            }
            if (TogetherServerNetwork.denyLockedFilm(server, player, newId)) {
                return;
            }
            try {
                BBSMod.getFilms().rename(oldId, newId);
            }
            catch (Exception exception) {
                // empty catch block
            }
            AssetLockManager.rename(AssetLockType.FILM, oldId, newId);
            FilmCommitStore.renameFilm(oldId, newId);
            FilmBranchStore.renameFilm(oldId, newId);
            for (Map.Entry<UUID, CommitSelection> entry : playerCommitSelections.entrySet()) {
                if (!oldId.equals(entry.getValue().filmId)) continue;
                playerCommitSelections.put(entry.getKey(), new CommitSelection(newId, entry.getValue().commitId));
            }
            TogetherServerNetwork.broadcastCommitSelections(server, oldId);
            TogetherServerNetwork.broadcastCommitSelections(server, newId);
            TogetherServerNetwork.renameBranchKeys(lastSavedFilmSnapshots, oldId, newId);
            TogetherServerNetwork.renameBranchKeys(filmSaveDirty, oldId, newId);
            for (Map.Entry<String, AtomicBoolean> entry : filmSaveDirty.entrySet()) {
                int tab;
                if (!((String)entry.getKey()).startsWith(newId + "\t") || !((AtomicBoolean)entry.getValue()).get() || (tab = ((String)entry.getKey()).lastIndexOf(9)) < 0) continue;
                TogetherServerNetwork.scheduleFilmSave(server, newId, ((String)entry.getKey()).substring(tab + 1));
            }
            for (Map.Entry<UUID, String> entry : playerEditingFilm.entrySet()) {
                if (!oldId.equals(entry.getValue())) continue;
                playerEditingFilm.put((UUID)entry.getKey(), newId);
            }
            for (Map.Entry<UUID, String> entry : playerInFilmScreen.entrySet()) {
                if (!oldId.equals(entry.getValue())) continue;
                playerInFilmScreen.put((UUID)entry.getKey(), newId);
            }
            TogetherServerNetwork.broadcastPlayerCommitPositions(server, oldId);
            TogetherServerNetwork.broadcastPlayerCommitPositions(server, newId);
            for (CursorData cursorData : playerCursors.values()) {
                if (!oldId.equals(cursorData.filmId)) continue;
                cursorData.filmId = newId;
            }
            for (Map.Entry entry : playerFormLocks.entrySet()) {
                if (!oldId.equals(((FormLockData)entry.getValue()).filmId)) continue;
                playerFormLocks.put((UUID)entry.getKey(), new FormLockData(newId, ((FormLockData)entry.getValue()).replayIndex));
            }
            for (ServerPlayerEntity serverPlayerEntity : server.getPlayerManager().getPlayerList()) {
                if (serverPlayerEntity.getUuid().equals(player.getUuid())) continue;
                PacketByteBuf renameOut = PacketByteBufs.create();
                renameOut.writeString(oldId);
                renameOut.writeString(newId);
                ServerPlayNetworking.send((ServerPlayerEntity)serverPlayerEntity, (Identifier)CLIENT_FILM_RENAMED, (PacketByteBuf)renameOut);
            }
            for (ServerPlayerEntity serverPlayerEntity : server.getPlayerManager().getPlayerList()) {
                ServerPlayNetworking.send((ServerPlayerEntity)serverPlayerEntity, (Identifier)CLIENT_FILM_LIST_CHANGED, (PacketByteBuf)PacketByteBufs.create());
            }
        });
    }

    private static <T> void renameBranchKeys(Map<String, T> map, String oldId, String newId) {
        String prefix = oldId + "\t";
        ArrayList<String> keys = new ArrayList<String>();
        for (String key : map.keySet()) {
            if (!key.startsWith(prefix)) continue;
            keys.add(key);
        }
        for (String key : keys) {
            T value = map.remove(key);
            if (value == null) continue;
            map.put(newId + key.substring(oldId.length()), value);
        }
    }

    private static void handleFilmListChanged(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        server.execute(() -> {
            for (ServerPlayerEntity other : server.getPlayerManager().getPlayerList()) {
                if (other.getUuid().equals(player.getUuid())) continue;
                ServerPlayNetworking.send((ServerPlayerEntity)other, (Identifier)CLIENT_FILM_LIST_CHANGED, (PacketByteBuf)PacketByteBufs.create());
            }
        });
    }

    private static void mergeFilms(Film stored, Film incoming) {
        List storedReplays = stored.replays.getList();
        List incomingReplays = incoming.replays.getList();
        for (int i = 0; i < incomingReplays.size(); ++i) {
            KeyframeChannel storedCh;
            Replay incomingReplay = (Replay)incomingReplays.get(i);
            if (i >= storedReplays.size()) {
                stored.replays.add(incomingReplay);
                continue;
            }
            Replay storedReplay = (Replay)storedReplays.get(i);
            for (KeyframeChannel keyframeChannel : incomingReplay.keyframes.getChannels()) {
                BaseValue storedVal = storedReplay.keyframes.get(keyframeChannel.getId());
                if (!(storedVal instanceof KeyframeChannel)) continue;
                storedCh = (KeyframeChannel)storedVal;
                if (keyframeChannel.getKeyframes().size() <= storedCh.getKeyframes().size()) continue;
                storedCh.fromData(keyframeChannel.toData());
            }
            for (Map.Entry entry : incomingReplay.properties.tracks.entrySet()) {
                KeyframeChannel inCh = (KeyframeChannel)entry.getValue();
                storedCh = storedReplay.properties.get((TrackId)entry.getKey());
                if (storedCh == null) {
                    storedReplay.properties.put((TrackId)entry.getKey(), inCh);
                    continue;
                }
                if (inCh.getKeyframes().size() <= storedCh.getKeyframes().size()) continue;
                storedCh.fromData(inCh.toData());
            }
            if (incomingReplay.form.get() == null) continue;
            storedReplay.form.set(FormUtils.copy(incomingReplay.form.get()));
        }
    }

    private static void applyPathToFilm(Film film, List<String> path, BaseType data) {
        try {
            KeyframeChannel ch;
            TrackId trackId;
            if (path.size() < 4 || data == null) {
                return;
            }
            int replayIdx = Integer.parseInt(path.get(1));
            String group = path.get(2);
            String chanId = path.get(3);
            List replays = film.replays.getList();
            if (replayIdx < 0 || replayIdx >= replays.size()) {
                return;
            }
            Replay replay = (Replay)replays.get(replayIdx);
            if ("keyframes".equals(group)) {
                BaseValue ch2 = replay.keyframes.get(chanId);
                if (ch2 != null) {
                    ch2.fromData(data);
                }
            } else if ("properties".equals(group) && (trackId = TrackId.parse((String)chanId)) != null && (ch = replay.properties.get(trackId)) != null) {
                ch.fromData(data);
            }
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void handleFilmDataSync(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        if (!BbsOpManager.isAllowed(player, BbsOpDomain.FILM)) {
            return;
        }
        TogetherServerNetwork.crusherFor(player).receive(buf, (bytes, packetByteBuf) -> {
            if (bytes == null) {
                return;
            }
            String filmId = packetByteBuf.readString();
            int pathSize = packetByteBuf.readInt();
            ArrayList<String> path = new ArrayList<String>();
            for (int i = 0; i < pathSize; ++i) {
                path.add(packetByteBuf.readString());
            }
            String sentBranch = packetByteBuf.isReadable() ? packetByteBuf.readString(80) : "";
            server.execute(() -> {
                Film film;
                if (deletedFilmIds.contains(filmId)) {
                    return;
                }
                String branch = TogetherServerNetwork.playerBranch(filmId, player);
                if (!sentBranch.isEmpty() && !sentBranch.equals(branch)) {
                    System.out.println("[BBSTogether] Drop delta from stale branch player=" + player.getGameProfile().getName() + " film=" + filmId + " sent=" + sentBranch + " current=" + branch);
                    return;
                }
                if (CoopSessionManager.isFilmLockedForEditing(filmId, branch)) {
                    return;
                }
                if (TogetherServerNetwork.denyLockedFilm(server, player, filmId)) {
                    return;
                }
                playerEditingFilm.put(player.getUuid(), filmId);
                for (Map.Entry<UUID, String> entry : playerEditingFilm.entrySet()) {
                    ServerPlayerEntity other;
                    if (!entry.getValue().equals(filmId) || entry.getKey().equals(player.getUuid()) || (other = server.getPlayerManager().getPlayer(entry.getKey())) == null || !TogetherServerNetwork.playerBranch(filmId, other).equals(branch)) continue;
                    TogetherServerNetwork.crusherFor(other).send((PlayerEntity)other, CLIENT_FILM_DATA_SYNC, bytes, outBuf -> {
                        outBuf.writeString(filmId);
                        outBuf.writeString(branch);
                        outBuf.writeInt(path.size());
                        for (String s : path) {
                            outBuf.writeString(s);
                        }
                    });
                }
                if (path.size() >= 4 && "replays".equals(path.get(0)) && (film = FilmBranchStore.working(filmId, branch)) != null) {
                    lastSavedFilmSnapshots.computeIfAbsent(TogetherServerNetwork.branchKey(filmId, branch), k -> (MapType)film.toData());
                    TogetherServerNetwork.applyPathToFilm(film, path, DataStorageUtils.readFromBytes((byte[])bytes));
                    TogetherServerNetwork.scheduleFilmSave(server, filmId, branch);
                }
            });
        });
    }

    private static void handleUndoSync(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        if (!BbsOpManager.isAllowed(player, BbsOpDomain.FILM)) {
            return;
        }
        TogetherServerNetwork.crusherFor(player).receive(buf, (bytes, packetByteBuf) -> {
            if (bytes == null) {
                return;
            }
            String filmId = packetByteBuf.readString();
            int count = packetByteBuf.readInt();
            ArrayList<UndoChange> changes = new ArrayList<UndoChange>();
            for (int i = 0; i < count && i < 64; ++i) {
                int pathSize = packetByteBuf.readInt();
                ArrayList<String> path = new ArrayList<String>();
                for (int j = 0; j < pathSize; ++j) {
                    path.add(packetByteBuf.readString());
                }
                int dataLen = packetByteBuf.readInt();
                byte[] data = new byte[dataLen];
                packetByteBuf.readBytes(data);
                int expectedLen = packetByteBuf.readInt();
                byte[] expected = new byte[expectedLen];
                packetByteBuf.readBytes(expected);
                boolean checkable = packetByteBuf.readBoolean();
                changes.add(new UndoChange(path, data, expected, checkable));
            }
            String sentBranch = packetByteBuf.isReadable() ? packetByteBuf.readString(80) : "";
            server.execute(() -> {
                String branch = TogetherServerNetwork.playerBranch(filmId, player);
                if (!sentBranch.isEmpty() && !sentBranch.equals(branch)) {
                    return;
                }
                if (TogetherServerNetwork.denyLockedFilm(server, player, filmId)) {
                    return;
                }
                TogetherServerNetwork.applyUndoSync(server, player, filmId, changes);
            });
        });
    }

    private static void applyUndoSync(MinecraftServer server, ServerPlayerEntity player, String filmId, ArrayList<UndoChange> changes) {
        if (deletedFilmIds.contains(filmId)) {
            return;
        }
        String branch = TogetherServerNetwork.playerBranch(filmId, player);
        playerEditingFilm.put(player.getUuid(), filmId);
        Film film = null;
        for (UndoChange c : changes) {
            if (!c.checkable) continue;
            film = FilmBranchStore.working(filmId, branch);
            break;
        }
        if (film != null) {
            for (UndoChange c : changes) {
                byte[] actual;
                BaseValue current;
                if (!c.checkable || (current = TogetherServerNetwork.resolveServerValue(film, c.path)) == null || Arrays.equals(actual = DataStorageUtils.writeToBytes((BaseType)current.toData()), c.expected)) continue;
                TogetherServerNetwork.rejectUndo(server, player, film, changes);
                return;
            }
        }
        boolean dirty = false;
        Film filmCopy = film;
        for (UndoChange c : changes) {
            for (Map.Entry<UUID, String> entry : playerEditingFilm.entrySet()) {
                ServerPlayerEntity other;
                if (!entry.getValue().equals(filmId) || entry.getKey().equals(player.getUuid()) || (other = server.getPlayerManager().getPlayer(entry.getKey())) == null || !TogetherServerNetwork.playerBranch(filmId, other).equals(branch)) continue;
                TogetherServerNetwork.crusherFor(other).send((PlayerEntity)other, CLIENT_FILM_DATA_SYNC, c.data, outBuf -> {
                    outBuf.writeString(filmId);
                    outBuf.writeString(branch);
                    outBuf.writeInt(c.path.size());
                    for (String s : c.path) {
                        outBuf.writeString(s);
                    }
                });
            }
            if (!c.checkable || filmCopy == null) continue;
            lastSavedFilmSnapshots.computeIfAbsent(TogetherServerNetwork.branchKey(filmId, branch), k -> (MapType)filmCopy.toData());
            TogetherServerNetwork.applyPathToFilm(filmCopy, c.path, DataStorageUtils.readFromBytes((byte[])c.data));
            dirty = true;
        }
        if (dirty) {
            TogetherServerNetwork.scheduleFilmSave(server, filmId, branch);
        }
    }

    private static void rejectUndo(MinecraftServer server, ServerPlayerEntity player, Film film, ArrayList<UndoChange> changes) {
        ArrayList<byte[]> current = new ArrayList<byte[]>();
        int count = 0;
        for (UndoChange c : changes) {
            if (!c.checkable) {
                current.add(null);
                continue;
            }
            BaseValue value = TogetherServerNetwork.resolveServerValue(film, c.path);
            byte[] data = value == null ? null : DataStorageUtils.writeToBytes((BaseType)value.toData());
            current.add(data);
            if (data == null) continue;
            ++count;
        }
        PacketByteBuf out = PacketByteBufs.create();
        out.writeString(film.getId());
        out.writeInt(count);
        for (int i = 0; i < changes.size(); ++i) {
            byte[] data = (byte[])current.get(i);
            if (data == null) continue;
            UndoChange c = changes.get(i);
            out.writeInt(c.path.size());
            for (String s : c.path) {
                out.writeString(s);
            }
            out.writeInt(data.length);
            out.writeBytes(data);
        }
        byte[] payload = new byte[out.readableBytes()];
        out.readBytes(payload);
        TogetherServerNetwork.crusherFor(player).send((PlayerEntity)player, CLIENT_UNDO_REJECTED, payload, null);
        player.sendMessage((Text)Text.literal((String)"\u00a7e[BBS Together]\u00a7f \u64a4\u9500\u672a\u751f\u6548\uff1a\u8be5\u533a\u57df\u521a\u88ab\u5176\u4ed6\u73a9\u5bb6\u4fee\u6539\uff0c\u5df2\u4e3a\u4f60\u6062\u590d\u6700\u65b0\u5185\u5bb9"), false);
    }

    private static BaseValue resolveServerValue(Film film, List<String> path) {
        try {
            BaseValue current = film;
            for (String segment : path) {
                if (!(current instanceof BaseValueGroup)) {
                    return null;
                }
                if ((current = ((BaseValueGroup)current).get(segment)) != null) continue;
                return null;
            }
            return current;
        }
        catch (Exception e) {
            return null;
        }
    }

    private static void together_broadcastCursorSnapshot(MinecraftServer server, String filmId) {
        for (ServerPlayerEntity recipient : server.getPlayerManager().getPlayerList()) {
            UUID playerId = recipient.getUuid();
            String recipientFilm = playerEditingFilm.get(playerId);
            if (recipientFilm == null) {
                CursorData ownCursor = playerCursors.get(playerId);
                String string = recipientFilm = ownCursor == null ? null : ownCursor.filmId;
            }
            if (!filmId.equals(recipientFilm)) continue;
            ArrayList<Map.Entry<UUID, CursorData>> entries = new ArrayList<Map.Entry<UUID, CursorData>>();
            for (Map.Entry<UUID, CursorData> entry : playerCursors.entrySet()) {
                if (entry.getKey().equals(playerId) || !filmId.equals(entry.getValue().filmId) || !TogetherServerNetwork.sameBranch(filmId, playerId, entry.getKey())) continue;
                entries.add(entry);
            }
            PacketByteBuf out = PacketByteBufs.create();
            out.writeBoolean(true);
            out.writeInt(entries.size());
            for (Map.Entry entry : entries) {
                CursorData cursor = (CursorData)entry.getValue();
                List<Integer> selected = cursor.selectedIndices == null ? List.<Integer>of() : cursor.selectedIndices;
                out.writeUuid((UUID)entry.getKey());
                out.writeInt(cursor.editorType);
                out.writeFloat(cursor.tick);
                out.writeFloat(cursor.row);
                out.writeInt(selected.size());
                for (Integer n : selected) {
                    out.writeInt(n == null ? -1 : n);
                }
                out.writeInt(cursor.playheadTick);
            }
            ServerPlayNetworking.send((ServerPlayerEntity)recipient, (Identifier)CLIENT_CURSOR_POSITIONS, (PacketByteBuf)out);
        }
    }

    private static final class HashCacheEntry {
        final long mtime;
        final long size;
        final String sha1;

        HashCacheEntry(long mtime, long size, String sha1) {
            this.mtime = mtime;
            this.size = size;
            this.sha1 = sha1;
        }
    }

    private static class FormLockData {
        final String filmId;
        final int replayIndex;

        FormLockData(String filmId, int replayIndex) {
            this.filmId = filmId;
            this.replayIndex = replayIndex;
        }
    }

    private static class CursorData {
        String filmId;
        int editorType;
        float tick;
        float row;
        List<Integer> selectedIndices;
        int playheadTick;

        CursorData(String filmId, int editorType, float tick, float row, List<Integer> selected, int playheadTick) {
            this.filmId = filmId;
            this.editorType = editorType;
            this.tick = tick;
            this.row = row;
            this.selectedIndices = selected;
            this.playheadTick = playheadTick;
        }
    }

    private static class CommitSelection {
        final String filmId;
        final String commitId;

        CommitSelection(String filmId, String commitId) {
            this.filmId = filmId;
            this.commitId = commitId;
        }
    }

    private static class AssetSendTask {
        final String relativePath;
        final File file;
        final long size;
        final String sha1;
        final boolean isPush;

        AssetSendTask(String relativePath, File file, long size, String sha1, boolean isPush) {
            this.relativePath = relativePath;
            this.file = file;
            this.size = size;
            this.sha1 = sha1;
            this.isPush = isPush;
        }
    }

    private static class PendingUpload {
        int files;
        long bytes;
        long lastTime;

        private PendingUpload() {
        }
    }

    private static class UndoChange {
        final ArrayList<String> path;
        final byte[] data;
        final byte[] expected;
        final boolean checkable;

        UndoChange(ArrayList<String> path, byte[] data, byte[] expected, boolean checkable) {
            this.path = path;
            this.data = data;
            this.expected = expected;
            this.checkable = checkable;
        }
    }
}
