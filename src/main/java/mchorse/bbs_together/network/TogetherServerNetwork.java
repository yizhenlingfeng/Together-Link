package mchorse.bbs_together.network;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.data.DataStorageUtils;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.film.replays.tracks.TrackId;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.network.ServerPacketCrusher;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.settings.values.base.BaseValueGroup;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_together.TogetherSettings;
import mchorse.bbs_together.BBSTogetherFix18;
import mchorse.bbs_together.BBSTogetherFix28Server;
import mchorse.bbs_together.bbsop.BbsOpDomain;
import mchorse.bbs_together.bbsop.BbsOpManager;
import mchorse.bbs_together.behaviors.BehaviorEntry;
import mchorse.bbs_together.behaviors.BehaviorLogManager;
import mchorse.bbs_together.behaviors.BehaviorType;
import mchorse.bbs_together.commits.FilmCommit;
import mchorse.bbs_together.commits.FilmBranchStore;
import mchorse.bbs_together.commits.FilmCommitStore;
import mchorse.bbs_together.utils.ModelPathUtils;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.MinecraftServer;

public class TogetherServerNetwork {
    /** C2S: client reports whether it wants to receive pushed assets (assetSyncReceive). */
    public static final Identifier SERVER_SYNC_PREF = new Identifier("bbs_together", "s_sync_pref");
    /** C2S (crusher): client reports its local asset inventory {path: sha1} for push filtering. */
    public static final Identifier SERVER_ASSET_REPORT = new Identifier("bbs_together", "s_asset_report");
    /** A1/A2: incremental variant of {@link #SERVER_ASSET_REPORT} — only the paths that changed,
     * an empty sha1 meaning "I no longer own this". Absent on old clients (they keep the full map). */
    public static final Identifier SERVER_ASSET_REPORT_DELTA = new Identifier("bbs_together", "s_asset_report_delta");
    /** C2S (crusher): client reports its local film inventory {filmId: sha1} for on-demand film sync. */
    public static final Identifier SERVER_FILM_INVENTORY = new Identifier("bbs_together", "s_film_inventory");
    public static final Identifier CLIENT_PLAYER_COLORS = new Identifier("bbs_together", "c_player_colors");
    public static final Identifier CLIENT_CURSOR_POSITIONS = new Identifier("bbs_together", "c_cursor_positions");
    public static final Identifier CLIENT_FILM_SELECTIONS = new Identifier("bbs_together", "c_film_selections");
    public static final Identifier CLIENT_REPLAY_SELECTIONS = new Identifier("bbs_together", "c_replay_selections");
    public static final Identifier CLIENT_REPLAY_FORM_LOCKS = new Identifier("bbs_together", "c_replay_form_locks");
    public static final Identifier CLIENT_REPLAY_SWAP = new Identifier("bbs_together", "c_replay_swap");
    public static final Identifier CLIENT_TOGGLE_RECORDING = new Identifier("bbs_together", "c_toggle_recording");
    /**
     * Absolute server-side safety cap on a single synced asset (2 GB). Normal size limits come
     * from {@code TogetherSettings.getAssetSyncMaxMb()} (the shared sync ceiling); this hard cap
     * exists only so a misbehaving client can't buffer a multi-GB file and blow up the server
     * heap.
     */
    public static long maxAssetBytes() {
        return 2048L * 1024L * 1024L;
    }

    /** Largest file the manifest advertises, in bytes; 0 means "advertise everything". */
    public static long syncMaxBytes() {
        int mb = TogetherSettings.getAssetSyncMaxMb();
        return mb <= 0 ? 0L : (long)mb * 1024L * 1024L;
    }

    public static final Identifier CLIENT_ASSET_MANIFEST = new Identifier("bbs_together", "c_asset_manifest");
    /** B4: "these assets changed" hint, {@code path -> sha1:size}; replaces pushing whole
     * files to everyone, so an upload costs the server a few dozen bytes per peer instead of
     * one full copy per peer (a 50 MB model uploaded to a 4 player server used to be sent 3
     * more times, all of it inside the player's game connection). */
    public static final Identifier CLIENT_ASSET_HINT = new Identifier("bbs_together", "c_asset_hint");
    public static final Identifier CLIENT_ASSET_FILE = new Identifier("bbs_together", "c_asset_file");
    public static final Identifier CLIENT_FILM_SCREENS = new Identifier("bbs_together", "c_film_screens");
    public static final Identifier CLIENT_MERGE_REPLAYS = new Identifier("bbs_together", "c_merge_replays");
    /** S2C (crusher): authoritative working copy of one branch (selection/join/checkout). */
    public static final Identifier CLIENT_FILM_BRANCH_STATE = new Identifier("bbs_together", "c_film_branch_state");
    public static final Identifier CLIENT_FILM_SAVED_SYNC = new Identifier("bbs_together", "c_film_saved_sync");
    /** C2S (crusher): a client undid/redid an entry — payload carries, per touched subtree,
     * the rolled-back data PLUS the entry's other side (the state the server should still
     * hold). The server refuses the rollback when its authoritative copy has moved on,
     * so one player's Ctrl+Z can no longer wipe a peer's newer edit on everyone. */
    public static final Identifier SERVER_UNDO_SYNC = new Identifier("bbs_together", "s_undo_sync");
    /** S2C: the undo/redo was refused; carries the server's current data for each touched path. */
    public static final Identifier CLIENT_UNDO_REJECTED = new Identifier("bbs_together", "c_undo_rejected");
    public static final Identifier CLIENT_FILM_DATA_SYNC = new Identifier("bbs_together", "c_film_data_sync");
    public static final Identifier CLIENT_FILM_LIST_CHANGED = new Identifier("bbs_together", "c_film_list_changed");
    /** S2C: ping that server assets changed; auto-sync clients re-diff the manifest (B4 makes that cheap). */
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
    /** C2S: a player asks the server to delete a model (logged as a pending request behavior). */
    public static final Identifier SERVER_MODEL_DELETE_REQUEST = new Identifier("bbs_together", "s_model_delete_request");
    /** C2S (manager only): approve or deny a pending model-delete request. */
    public static final Identifier SERVER_MODEL_DELETE_DECISION = new Identifier("bbs_together", "s_model_delete_decision");
    /** S2C: an approved model deletion — clients drop their local copy of the model. */
    public static final Identifier CLIENT_MODEL_DELETED = new Identifier("bbs_together", "c_model_deleted");
    public static final Identifier CLIENT_BEHAVIOR_LIST = new Identifier("bbs_together", "c_behavior_list");
    public static final Identifier CLIENT_PERMISSIONS = new Identifier("bbs_together", "c_permissions");
    /** S2C: pushes the receiving player's own BBS edit level (0 read-only / 1-2 reserved). */
    public static final Identifier CLIENT_BBSOP_SYNC = new Identifier("bbs_together", "c_bbsop_sync");
    /** C2S: a level-2/vanilla-OP player sets another player's BBS permission level. */
    public static final Identifier SERVER_BBSOP_SET = new Identifier("bbs_together", "s_bbsop_set");
    /** C2S: a level-2/vanilla-OP player allows/denies one editable domain for a level-1 player. */
    public static final Identifier SERVER_BBSOP_GRANT = new Identifier("bbs_together", "s_bbsop_grant");
    /* ---- Cooperative recording ("request coop recording"); logic in CoopSessionManager ---- */
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
    /* ---- Film commits ("影片编辑分支"); the graph itself lives in commits/FilmCommitStore ---- */
    /** C2S: ask for the whole commit graph of one film. */
    public static final Identifier SERVER_COMMITS_QUERY = new Identifier("bbs_together", "s_commits_query");
    /** C2S: ask which films have commits at all. */
    public static final Identifier SERVER_COMMIT_INDEX = new Identifier("bbs_together", "s_commit_index");
    /** C2S: ask for one commit's diff. */
    public static final Identifier SERVER_COMMIT_DIFF = new Identifier("bbs_together", "s_commit_diff");
    /** C2S: rename one commit's message (the graph shape does not change). */
    public static final Identifier SERVER_COMMIT_RENAME = new Identifier("bbs_together", "s_commit_rename");
    /** C2S: which commit the player is looking at in the graph (empty commit = none). */
    public static final Identifier SERVER_COMMIT_SELECTION = new Identifier("bbs_together", "s_commit_selection");
    /** C2S: recolour one commit; the server stores it so every client shows the same graph. */
    public static final Identifier SERVER_COMMIT_COLOR = new Identifier("bbs_together", "s_commit_color");
    /** C2S: put the film back to the state of one commit. */
    public static final Identifier SERVER_COMMIT_REVERT = new Identifier("bbs_together", "s_commit_revert");
    /** C2S: commit the film as it is now. */
    public static final Identifier SERVER_COMMIT_CREATE = new Identifier("bbs_together", "s_commit_create");
    /** C2S: ask for the snapshot of the current branch's head, the baseline the pending changes are compared against. */
    public static final Identifier SERVER_COMMIT_HEAD = new Identifier("bbs_together", "s_commit_head");
    /** C2S: start a branch at one commit. */
    public static final Identifier SERVER_BRANCH_CREATE = new Identifier("bbs_together", "s_branch_create");
    /** C2S: check a branch out, i.e. put the film back to that branch's head. */
    public static final Identifier SERVER_BRANCH_CHECKOUT = new Identifier("bbs_together", "s_branch_checkout");
    /** S2C: the whole graph of one film. */
    public static final Identifier CLIENT_COMMITS = new Identifier("bbs_together", "c_commits");
    /** S2C: which films have commits at all. */
    public static final Identifier CLIENT_COMMIT_INDEX = new Identifier("bbs_together", "c_commit_index");
    /** S2C: one commit was added. */
    public static final Identifier CLIENT_COMMIT_ADDED = new Identifier("bbs_together", "c_commit_added");
    /** S2C: one commit's diff. */
    public static final Identifier CLIENT_COMMIT_DIFF = new Identifier("bbs_together", "c_commit_diff");
    /** S2C: one commit was renamed. */
    public static final Identifier CLIENT_COMMIT_RENAMED = new Identifier("bbs_together", "c_commit_renamed");
    /** S2C: one commit's colour changed. */
    public static final Identifier CLIENT_COMMIT_COLOR = new Identifier("bbs_together", "c_commit_color");
    /** S2C: the commit selections of one film (player -> commit). */
    public static final Identifier CLIENT_COMMIT_SELECTIONS = new Identifier("bbs_together", "c_commit_selections");
    /** S2C: where each player on a film currently sits in the graph (branch head). */
    public static final Identifier CLIENT_PLAYER_COMMIT_POSITIONS = new Identifier("bbs_together", "c_player_commit_positions");
    /** S2C: a commit request didn't produce a commit; the reason tells the player why. */
    public static final Identifier CLIENT_COMMIT_REJECTED = new Identifier("bbs_together", "c_commit_rejected");
    /** S2C: the snapshot of the branch head a film currently sits at. */
    public static final Identifier CLIENT_COMMIT_HEAD = new Identifier("bbs_together", "c_commit_head");
    private static final ConcurrentHashMap<UUID, ServerPacketCrusher> playerCrushers = new ConcurrentHashMap();
    private static final LinkedHashMap<UUID, Integer> playerReplaySelections = new LinkedHashMap();
    private static final ConcurrentHashMap<UUID, String> playerEditingFilm = new ConcurrentHashMap();
    /** Player -> "filmId<TAB>branch" last pushed in full, so selecting a film does not resend it every tick. */
    private static final ConcurrentHashMap<UUID, String> lastBranchStateSent = new ConcurrentHashMap();
    private static final ConcurrentHashMap<UUID, String> playerInFilmScreen = new ConcurrentHashMap();
    private static final ConcurrentHashMap<UUID, Integer> playerColors = new ConcurrentHashMap();
    private static final Set<String> deletedFilmIds = Collections.synchronizedSet(new HashSet());
    /** Last saved state per film branch, the baseline film saves diff against: edits reach the
     * server through FILM_DATA_SYNC before (and besides) the full FILM_SAVE_SYNC, and in
     * single player BBS's own save has already rewritten the file by the time the sync
     * arrives — so the pre-save state can only come from this snapshot. Keyed by
     * {@link #branchKey(String, String)}. */
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
    /** B1: whether each client wants pushed assets at all (assetSyncReceive); default true when unreported. */
    private static final ConcurrentHashMap<UUID, Boolean> playerReceivePush = new ConcurrentHashMap();
    /** B2: per-player inventory {relativePath: sha1} reported after asset sync; used to skip redundant pushes. */
    private static final ConcurrentHashMap<UUID, Map<String, String>> playerOwnedAssets = new ConcurrentHashMap();
    /** A4: films with live edits awaiting a debounced save; edited continuously -> saved once quiet. */
    private static final ConcurrentHashMap<String, AtomicBoolean> filmSaveDirty = new ConcurrentHashMap();
    private static final ScheduledExecutorService FILM_SAVE_SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "BBSTogether-FilmSaver");
        t.setDaemon(true);
        return t;
    });
    private static final long FILM_SAVE_DELAY_MS = 2000L;
    /** A1: (absolute path -> mtime/size/sha1) cache so manifest walks only hash changed files. */
    private static final ConcurrentHashMap<String, HashCacheEntry> assetHashCache = new ConcurrentHashMap();

    /* ---- per-branch film workspaces ------------------------------------ */

    /** Key of a film+branch in the per-branch maps; film ids don't contain tabs. */
    private static String branchKey(String filmId, String branch)
    {
        return filmId + '\t' + branch;
    }

    private static String playerBranch(String filmId, ServerPlayerEntity player)
    {
        return FilmBranchStore.branchOf(filmId, player.getUuid());
    }

    private static String playerBranch(String filmId, UUID playerId)
    {
        return FilmBranchStore.branchOf(filmId, playerId);
    }

    private static boolean sameBranch(String filmId, ServerPlayerEntity a, ServerPlayerEntity b)
    {
        return playerBranch(filmId, a).equals(playerBranch(filmId, b));
    }

    private static boolean sameBranch(String filmId, UUID a, UUID b)
    {
        return playerBranch(filmId, a).equals(playerBranch(filmId, b));
    }

    /** Write a full branch snapshot to its workspace; the default branch also updates the main file. */
    private static void saveBranchNow(String filmId, String branch, MapType data)
    {
        FilmBranchStore.setWorking(filmId, branch, data);
        FilmBranchStore.save(filmId, branch);
    }

    /**
     * Send a player the working copy and graph of the branch they are on. Used when a film is
     * opened (the client's local file may belong to another branch) and when a branch is checked
     * out. Only the recipient is affected — other players stay on their own branches.
     */
    public static void sendFilmBranchState(MinecraftServer server, ServerPlayerEntity player, String filmId)
    {
        if (filmId == null || filmId.isEmpty())
        {
            return;
        }

        String branch = FilmBranchStore.branchOf(filmId, player.getUuid());
        MapType data = FilmBranchStore.workingData(filmId, branch);

        TogetherServerNetwork.sendFilmBranchStateTo(server, player, filmId, branch, data);
        TogetherServerNetwork.sendCommits(player, FilmCommitStore.list(filmId), filmId, branch);
        TogetherServerNetwork.sendCommitSelectionsTo(player, filmId);
        TogetherServerNetwork.sendPlayerCommitPositionsTo(server, player, filmId);
        lastBranchStateSent.put(player.getUuid(), filmId + '\t' + branch);
    }

    /** Authoritative branch load: unlike a normal save, the client must apply this even when
     * its pending-checkout timer already expired or the editor still shows another branch. */
    private static void sendFilmBranchStateTo(MinecraftServer server, ServerPlayerEntity player, String filmId, String branch, MapType data)
    {
        TogetherServerNetwork.crusherFor(player).send((PlayerEntity)player, CLIENT_FILM_BRANCH_STATE, (BaseType)data, outBuf -> {
            outBuf.writeString(filmId);
            outBuf.writeString(branch == null ? "" : branch);
        });
    }

    /* ---- E1: asset transfer counters, for /bbstogether diag ---------------------------- */
    private static final java.util.concurrent.atomic.AtomicLong assetBytesSent = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong assetTasksSent = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong assetRequestsSeen = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong assetPendingDropped = new java.util.concurrent.atomic.AtomicLong();

    /** B4: changed assets waiting to be announced (relativePath -> "sha1:size"), coalesced. */
    private static final ConcurrentHashMap<String, String> pendingAssetHints = new ConcurrentHashMap<>();
    private static final AtomicBoolean assetHintPending = new AtomicBoolean(false);

    /**
     * Tombstones for approved model deletions: model id -> expiry (epoch ms). Auto-sync
     * clients diff by sha1, so without this a model deleted from the server would be
     * re-uploaded by the next client that still holds it locally. Expires after
     * {@link #MODEL_TOMBSTONE_TTL_MS} so the id can be legitimately reused later.
     */
    private static final ConcurrentHashMap<String, Long> recentlyDeletedModels = new ConcurrentHashMap();
    private static final long MODEL_TOMBSTONE_TTL_MS = 30L * 60L * 1000L;

    /** Auto sync: coalesced "assets changed" pings; a burst of uploads sends one ping. */
    private static final ScheduledExecutorService ASSET_CHANGE_SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "BBSTogether-AssetChangePing");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicBoolean assetChangePingPending = new AtomicBoolean(false);

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

    /** SHA-1 of a file, cached while mtime+size are unchanged (manifest walks + upload compares). */
    private static String sha1OfPathCached(Path path, File file) throws IOException {
        long mtime = Files.getLastModifiedTime(path).toMillis();
        long size = file.length();
        HashCacheEntry e = assetHashCache.get(path.toString());
        if (e != null && e.mtime == mtime && e.size == size) {
            return e.sha1;
        }
        String sha1 = sha1OfPath(path);
        assetHashCache.put(path.toString(), new HashCacheEntry(mtime, size, sha1));
        return sha1;
    }

    private static void cacheAssetHash(Path path, long mtime, long size, String sha1) {
        if (sha1 == null || sha1.isEmpty()) return;
        assetHashCache.put(path.toString(), new HashCacheEntry(mtime, size, sha1));
    }

    private static long sendIntervalMs() {
        try {
            return ((Number) TogetherSettings.assetSendIntervalMs.get()).longValue();
        } catch (Exception e) {
            return 40L;
        }
    }

    private static long sendBatchBytes() {
        try {
            return Math.max(30_720L, ((Number) TogetherSettings.assetSendBatchKb.get()).longValue() * 1024L);
        } catch (Exception e) {
            return 512L * 1024L;
        }
    }

    private static ServerPacketCrusher crusherFor(ServerPlayerEntity player) {
        return playerCrushers.computeIfAbsent(player.getUuid(), k -> new ServerPacketCrusher());
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
            /* B4: the client rides its last manifest fingerprint along; a match lets the server
             * answer "unchanged" instead of re-sending the whole manifest. 0 = unknown. */
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
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_COMMIT_CREATE, (server, player, handler, buf, resp) -> TogetherServerNetwork.handleCommitCreate(server, player, buf));
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_BRANCH_CREATE, (server, player, handler, buf, resp) -> TogetherServerNetwork.handleBranchCreate(server, player, buf));
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_BRANCH_CHECKOUT, (server, player, handler, buf, resp) -> TogetherServerNetwork.handleBranchCheckout(server, player, buf));
        BBSTogetherFix28Server.registerReceivers();
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            TogetherServerNetwork.sweepUploadAggregates(server);
            CoopSessionManager.sweep(server);
        });
    }

    public static void reset() {
        playerCrushers.clear();
        pendingUploadAggregates.clear();
        playerEditingFilm.clear();
        lastBranchStateSent.clear();
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
        synchronized (linkedHashMap) {
            playerReplaySelections.clear();
        }
        recordingPlayers.clear();
        pendingStopPlayers.clear();
        pendingReplayData.clear();
        pendingFilmId = null;
        masterPlayerUUID = null;
        playerReceivePush.clear();
        playerOwnedAssets.clear();
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

    /**
     * E1: one-line-per-fact asset transfer report for {@code /bbstogether diag}.
     *
     * <p>Everything here answers "is the link being saturated by assets right now, and who is
     * waiting": the shared budget, how much it actually used, how deep the per-player queues
     * are, and whether anyone asked for pushes at all.</p>
     */
    public static java.util.List<String> diagLines(MinecraftServer server) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        long rate = AssetEgressBudget.rateBytesPerSec();
        lines.add("§6[BBS Together] 资产出口诊断");
        lines.add("§7预算: §f" + (rate <= 0L ? "不限速（0）" : (rate / 1024L) + " KiB/s (" + (rate / 1024L / 1024.0D) + " MiB/s)"));
        lines.add("§7当前可用: §f" + (AssetEgressBudget.availableBytes() / 1024L) + " KiB");
        int queued = 0;
        boolean anyQueue = false;
        if (server != null) {
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                LinkedBlockingQueue<AssetSendTask> q = assetSendQueues.get(p.getUuid());
                if (q == null) {
                    continue;
                }
                int n = q.size();
                if (n <= 0) {
                    continue;
                }
                anyQueue = true;
                queued += n;
                lines.add("§7  " + p.getGameProfile().getName() + ": §f" + n + " 个文件排队" + (assetDrainActive.containsKey(p.getUuid()) ? "（正在发送）" : "（空闲，等待排程）"));
            }
        }
        if (!anyQueue) {
            lines.add("§7排队: §f无待发资产");
        }
        else {
            lines.add("§7排队合计: §f" + queued + " 个文件");
        }
        lines.add("§7本次运行已发: §f" + (assetBytesSent.get() / 1024L / 1024L) + " MiB / " + assetTasksSent.get() + " 个文件");
        lines.add("§7已处理下载请求: §f" + assetRequestsSeen.get() + " 个路径");
        lines.add("§7节流参数: §f间隔 " + sendIntervalMs() + " ms / 分批 " + (sendBatchBytes() / 1024L) + " KiB（每玩家）");
        long syncMax = syncMaxBytes();
        lines.add("§7清单体积上限: §f" + (syncMax <= 0L ? "不限（所有文件都会同步）" : (syncMax / 1024L / 1024L) + " MiB（更大的文件不同步，留在服务器上）"));
        lines.add("§7丢包: §f" + assetPendingDropped.get() + " 个文件（队列超长被丢弃，见 asset.queue");
        lines.add("§7提示: §f预算 0 表示关闭限速；2048 KiB/s 约等于为心跳留出余量的家用上行；单玩家上限仍由上面的节流参数决定");
        return lines;
    }

    public static void clearPlayerState(ServerPlayerEntity player, MinecraftServer server) {
        try {
            /* Log this player's pending upload aggregates while the entity is still valid. */
            flushUploadAggregates(player, true);

            UUID uuid = player.getUuid();

            CoopSessionManager.onDisconnect(uuid, server);
            LinkedHashMap<UUID, Integer> linkedHashMap = playerReplaySelections;
            synchronized (linkedHashMap) {
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
                PacketByteBuf outBuf = PacketByteBufs.create();
                ConcurrentHashMap<UUID, Integer> concurrentHashMap = playerColors;
                synchronized (concurrentHashMap) {
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
        /* Fix 15.5: a "leave" cursor packet (empty filmId + all-negative markers) clears the
         * player's editing/cursor state and broadcasts a snapshot of the remaining cursors
         * instead of updating them. */
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
            if (clearFilmId == null || clearFilmId.isEmpty() || clearEditorType >= 0 || clearTick >= 0.0f || clearRow >= 0.0f || clearSelCount > 0) {
                buf.readerIndex(mark);
            } else {
                UUID leaver = player.getUuid();
                server.execute(() -> {
                    playerEditingFilm.remove(leaver, clearFilmId);
                    playerCursors.remove(leaver);
                    TogetherServerNetwork.broadcastFilmSelections(server);
                    TogetherServerNetwork.together_broadcastCursorSnapshot(server, clearFilmId);
                });
                return;
            }
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
            /* A3: broadcast only this player's cursor as a single-entry delta instead of
             * rebuilding and resending the whole snapshot to everyone (O(N) instead of O(N^2)). */
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                if (p.getUuid().equals(player.getUuid())) continue;
                String otherFilm = playerEditingFilm.get(p.getUuid());
                if (otherFilm == null) {
                    CursorData fallback = playerCursors.get(p.getUuid());
                    if (fallback != null) {
                        otherFilm = fallback.filmId;
                    }
                }
                if (otherFilm == null || !otherFilm.equals(filmId)) continue;
                /* Cursor indices belong to the branch's replay/clip list. */
                if (!TogetherServerNetwork.sameBranch(filmId, player, p)) continue;
                PacketByteBuf outBuf = PacketByteBufs.create();
                outBuf.writeBoolean(false);
                outBuf.writeInt(1);
                outBuf.writeUuid(player.getUuid());
                outBuf.writeInt(editorType);
                outBuf.writeFloat(tick);
                outBuf.writeFloat(row);
                outBuf.writeInt(selected.size());
                for (int idx : selected) {
                    outBuf.writeInt(idx);
                }
                outBuf.writeInt(playheadTick);
                ServerPlayNetworking.send((ServerPlayerEntity)p, (Identifier)CLIENT_CURSOR_POSITIONS, (PacketByteBuf)outBuf);
            }
        });
    }

    /** Tell everyone editing {@code filmId} that a player's cursor is gone (disconnect / wipe). */
    private static void broadcastCursorRemoval(MinecraftServer server, String filmId, UUID gone) {
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (p.getUuid().equals(gone)) continue;
            String otherFilm = playerEditingFilm.get(p.getUuid());
            if (otherFilm == null || !otherFilm.equals(filmId)) continue;
            if (!TogetherServerNetwork.sameBranch(filmId, gone, p.getUuid())) continue;
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
                CommitSelection gone = playerCommitSelections.remove(player.getUuid());

                if (gone != null) {
                    TogetherServerNetwork.broadcastCommitSelections(server, gone.filmId);
                }
                if (goneFilm != null) {
                    TogetherServerNetwork.broadcastPlayerCommitPositions(server, goneFilm);
                }
            } else {
                playerEditingFilm.put(player.getUuid(), filmId);
                String branch = TogetherServerNetwork.playerBranch(filmId, player);
                String key = filmId + '\t' + branch;
                /* The client may still hold the main file / another branch: give it this
                 * player's branch on selection, once. */
                if (!key.equals(lastBranchStateSent.get(player.getUuid()))) {
                    TogetherServerNetwork.sendFilmBranchState(server, player, filmId);
                }
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
        /* Fix 15.6: a "leave" replay selection packet (empty filmId + negative index) clears
         * the player's replay selection instead of updating it. */
        int mark = buf.readerIndex();
        try {
            String clearFilmId = buf.readString();
            int clearIndex = buf.readInt();
            if (clearFilmId == null || clearFilmId.isEmpty() || clearIndex >= 0) {
                buf.readerIndex(mark);
            } else {
                UUID leaver = player.getUuid();
                server.execute(() -> {
                    LinkedHashMap<UUID, Integer> selections = playerReplaySelections;
                    synchronized (selections) {
                        selections.remove(leaver);
                    }
                    TogetherServerNetwork.broadcastReplaySelections(server, clearFilmId);
                });
                return;
            }
        }
        catch (RuntimeException runtimeException) {
            buf.readerIndex(mark);
        }
        String filmId = buf.readString();
        int replayIndex = buf.readInt();
        server.execute(() -> {
            if (!filmId.isEmpty()) {
                playerEditingFilm.put(player.getUuid(), filmId);
            }
            LinkedHashMap<UUID, Integer> linkedHashMap = playerReplaySelections;
            synchronized (linkedHashMap) {
                playerReplaySelections.remove(player.getUuid());
                if (replayIndex >= 0) {
                    playerReplaySelections.put(player.getUuid(), replayIndex);
                }
            }
            TogetherServerNetwork.broadcastReplaySelections(server, filmId);
        });
    }

    public static void broadcastReplaySelections(MinecraftServer server, String filmId) {
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeBoolean(false);
            buf.writeString(filmId);
            AbstractMap abstractMap = playerReplaySelections;
            ArrayList<Map.Entry<UUID, Integer>> replayEntries = new ArrayList<>();
            synchronized (abstractMap) {
                for (Map.Entry<UUID, Integer> entry : playerReplaySelections.entrySet()) {
                    /* Replay indices are branch-specific: another branch's selection would
                     * point at a different replay here. */
                    if (filmId.isEmpty() || TogetherServerNetwork.sameBranch(filmId, p.getUuid(), entry.getKey())) {
                        replayEntries.add(entry);
                    }
                }
            }
            buf.writeInt(replayEntries.size());
            for (Map.Entry<UUID, Integer> entry : replayEntries) {
                buf.writeUuid(entry.getKey());
                buf.writeInt(entry.getValue().intValue());
            }
            abstractMap = playerColors;
            synchronized (abstractMap) {
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
            ArrayList<Map.Entry<UUID, FormLockData>> visible = new ArrayList<>();
            for (Map.Entry<UUID, FormLockData> entry : entries) {
                if (TogetherServerNetwork.sameBranch(filmId, p.getUuid(), entry.getKey())) {
                    visible.add(entry);
                }
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
                if (p == player) continue;
                if (!TogetherServerNetwork.sameBranch(filmId, player, p)) continue;
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

    /**
     * Sends the asset manifest. A matching {@code clientVersion} fingerprint (B4) answers
     * with an "unchanged" stub; the SHA-1 walk is cached per (mtime, size) (A1).
     */
    public static void sendAssetManifest(MinecraftServer server, ServerPlayerEntity player, long clientVersion) {
        File assetsFolder = BBSMod.getAssetsFolder();
        if (assetsFolder == null || !assetsFolder.exists()) {
            return;
        }
        final Path assetsPath = assetsFolder.toPath();
        Thread thread = new Thread(() -> {
            try {
                final MapType manifest = new MapType();
                StringBuilder fingerprint = new StringBuilder();
                final long syncMaxBytes = TogetherServerNetwork.syncMaxBytes();
                Files.walkFileTree(assetsPath, (FileVisitor<? super Path>)new SimpleFileVisitor<Path>(){

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        if (!attrs.isRegularFile()) {
                            return FileVisitResult.CONTINUE;
                        }
                        /* Over-size assets (attrs.size() > syncMaxBytes) stay in the manifest on
                         * purpose. Hiding them made a too-big video/model indistinguishable from a
                         * file that simply failed to sync: it was absent everywhere with no
                         * explanation. Clients see the size, skip the transfer and write a named
                         * stub, so the entry shows up dimmed as "文件尺寸过大" instead of vanishing.
                         * No bytes travel for these files unless the limit is raised. */
                        String relativePath = assetsPath.relativize(file).toString().replace(File.separator, "/");
                        /* ".bbsup" / ".bbspart" are transient multi-slice temps: they may be
                         * deleted or promoted at any moment, so never advertise them or
                         * clients will request a file that vanishes mid-transfer (and the
                         * half-written bytes would be shipped to every peer). */
                        if (mchorse.bbs_together.utils.AssetPaths.isTransferTemp(relativePath)) {
                            return FileVisitResult.CONTINUE;
                        }
                        /* Manifest entries carry "<sha1>:<sizeBytes>:<mtimeMillis>" so clients can
                         * skip over-size assets before requesting them and arbitrate conflicting
                         * copies by newness (the mtime travels with the content: uploads inherit
                         * the uploader's edit time, see handleAssetUpload). */
                        String sha1 = TogetherServerNetwork.sha1OfPathCached(file, file.toFile());
                        String entry = sha1 + ":" + file.toFile().length() + ":" + attrs.lastModifiedTime().toMillis();
                        manifest.putString(relativePath, entry);
                        fingerprint.append(relativePath).append('=').append(entry).append('\n');
                        return FileVisitResult.CONTINUE;
                    }
                });
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

    /** Stable 64-bit fingerprint of manifest content, used as its version for delta syncs. */
    private static long fingerprintVersion(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
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

    /** B3: films are pulled on demand via the client's film inventory, not pushed on join. */
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
        recordDownloadBehavior(player, paths);
        assetRequestsSeen.addAndGet(paths.size());
        Thread thread = new Thread(() -> {
            File assetsFolder = BBSMod.getAssetsFolder();
            for (String relativePath : paths) {
                File file;
                if (!mchorse.bbs_together.utils.AssetPaths.isPublishable(relativePath) || !(file = new File(assetsFolder, relativePath.replace("/", File.separator))).exists() || !file.isFile()) continue;
                if (file.length() > TogetherServerNetwork.maxAssetBytes() || file.length() == 0) continue;
                /* The manifest no longer advertises these, but an old client (or a hand written
                 * request) can still ask for them: keep the shared ceiling enforced here too. */
                long syncMax = TogetherServerNetwork.syncMaxBytes();
                if (syncMax > 0L && file.length() > syncMax) continue;
                String sha1 = "";
                try {
                    sha1 = TogetherServerNetwork.sha1OfPathCached(file.toPath(), file);
                }
                catch (Exception ignored) {}
                TogetherServerNetwork.enqueueAssetSend(server, player, new AssetSendTask(relativePath, file, file.length(), sha1, false));
            }
        }, "BBSTogether-AssetSend");
        thread.setDaemon(true);
        thread.start();
    }

    /** Last time we told a player their upload was rejected (throttles the chat spam). */
    private static final java.util.concurrent.ConcurrentHashMap<UUID, Long> uploadRejectNoticeAt = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * A level-0 player's upload is dropped right here, silently — the client still shows a
     * completed upload, so half a model can end up "synced" in their eyes while the server
     * never received a byte. Say it out loud (throttled) instead of failing mute.
     */
    private static void noticeUploadRejected(MinecraftServer server, ServerPlayerEntity player) {
        long now = System.currentTimeMillis();
        Long last = uploadRejectNoticeAt.get(player.getUuid());
        if (last != null && now - last.longValue() < 30_000L) {
            return;
        }
        uploadRejectNoticeAt.put(player.getUuid(), now);
        String message = "[BBS Together] 上传被拒绝：你的 BBS 权限是只读（等级 0），刚上传的资产没有写入服务器。请管理员用 /bbsop 给你授权后再上传。";
        server.execute(() -> player.sendMessage(Text.literal(message), false));
    }

    private static void handleAssetUpload(MinecraftServer server, ServerPlayerEntity uploader, PacketByteBuf buf) {
        /* Read-only (level 0) players may never upload, even if the gamerule would allow panels. */
        if (!BbsOpManager.isAllowed(uploader, BbsOpDomain.UPLOAD)) {
            TogetherServerNetwork.noticeUploadRejected(server, uploader);
            return;
        }
        TogetherServerNetwork.crusherFor(uploader).receive(buf, (bytes, packetByteBuf) -> {
            String relativePath = packetByteBuf.readString();
            /* A5: uploads arrive in slice batches; each batch is a complete crusher transfer
             * whose metadata says which slice of the file it is. */
            int seq = packetByteBuf.readableBytes() >= 4 ? packetByteBuf.readInt() : 0;
            int seqTotal = packetByteBuf.readableBytes() >= 4 ? packetByteBuf.readInt() : 1;
            long totalSize = packetByteBuf.readableBytes() >= 8 ? packetByteBuf.readLong() : (bytes == null ? 0L : (long)bytes.length);
            String sha1 = packetByteBuf.readableBytes() > 0 ? packetByteBuf.readString() : "";
            /* mtime travels with the content so manifest arbitration compares edit times, not
             * receipt times. Absent (old client) or implausible values fall back to "now". */
            long uploadMtime = packetByteBuf.readableBytes() >= 8 ? packetByteBuf.readLong() : 0L;
            long receivedAt = System.currentTimeMillis();
            if (uploadMtime <= 0L || uploadMtime > receivedAt + 60_000L) {
                uploadMtime = receivedAt;
            }
            if (relativePath.contains("..") || relativePath.startsWith("/")) {
                return;
            }
            if (bytes == null || totalSize > TogetherServerNetwork.maxAssetBytes()) {
                return;
            }
            /* Same shared ceiling on the way in: a file we would never advertise back to
             * clients is not worth storing either. */
            long uploadSyncMax = TogetherServerNetwork.syncMaxBytes();
            if (uploadSyncMax > 0L && totalSize > uploadSyncMax) {
                return;
            }
            File assetsFolder = BBSMod.getAssetsFolder();
            if (assetsFolder == null) {
                return;
            }
            /* Tombstone: drop uploads of a model that was recently deleted from the server,
             * otherwise auto-sync clients would re-upload it wholesale by sha1 diff. */
            String uploadModelId = ModelPathUtils.extractModelId(relativePath);
            if (uploadModelId != null && TogetherServerNetwork.isModelTombstoned(uploadModelId)) {
                System.out.println("[BBSTogether] Ignored upload of recently deleted model " + uploadModelId + " from " + uploader.getGameProfile().getName());
                return;
            }
            Path target = assetsFolder.toPath().resolve(relativePath.replace("/", File.separator));
            if (target.getParent() == null) {
                return;
            }
            try {
                Files.createDirectories(target.getParent());
                if (seqTotal > 1) {
                    /* Multi-slice upload: accumulate into a sibling temp file, verify the
                     * combined hash, then promote atomically. */
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
                        java.nio.file.attribute.FileTime stamp = java.nio.file.attribute.FileTime.fromMillis(uploadMtime);
                        Files.setLastModifiedTime(target, stamp);
                    } catch (Exception ignored2) {}
                    TogetherServerNetwork.finalizeUpload(server, uploader, relativePath, target, totalSize, sha1, true);
                } else {
                    /* A6: compare by cached/streamed SHA-1 instead of reading the whole
                     * existing file into the heap. Unchanged re-uploads are not stored,
                     * logged or pushed. */
                    String existingSha1 = Files.exists(target) ? TogetherServerNetwork.sha1OfPathCached(target, target.toFile()) : null;
                    boolean changed = existingSha1 == null || (sha1.isEmpty()
                        ? !java.util.Arrays.equals(Files.readAllBytes(target), bytes)
                        : !existingSha1.equalsIgnoreCase(sha1));
                    if (changed) {
                        Files.write(target, bytes);
                        try {
                            java.nio.file.attribute.FileTime stamp = java.nio.file.attribute.FileTime.fromMillis(uploadMtime);
                            Files.setLastModifiedTime(target, stamp);
                        } catch (Exception ignored2) {}
                        TogetherServerNetwork.finalizeUpload(server, uploader, relativePath, target, totalSize, sha1.isEmpty() ? TogetherServerNetwork.sha1OfPathCached(target, target.toFile()) : sha1, true);
                    } else {
                        TogetherServerNetwork.finalizeUpload(server, uploader, relativePath, target, totalSize, sha1, false);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    /** Post-upload bookkeeping: cache the hash, log the behavior, then announce the change to
     * everyone who wants pushes (B4) instead of sending them a full copy unasked. */
    private static void finalizeUpload(MinecraftServer server, ServerPlayerEntity uploader, String relativePath, Path target, long size, String sha1, boolean changed) {
        try {
            TogetherServerNetwork.cacheAssetHash(target, Files.getLastModifiedTime(target).toMillis(), target.toFile().length(), sha1);
        }
        catch (Exception ignored) {}
        if (changed) {
            recordUploadBehavior(uploader, relativePath, size);
        }
        if (!changed) {
            return;
        }
        scheduleAssetsChangedPing(server, uploader);
        queueAssetHint(server, uploader, relativePath, sha1, size);
    }

    /**
     * B4: announce that {@code relativePath} changed, coalesced over ~2s.
     *
     * <p>The old behaviour was to stream the whole file to every other player. That is the
     * single most expensive thing the mod did: one upload became one download per peer, on
     * links that already cannot separate asset bytes from keepalives. A hint is ~40 bytes and
     * the peers pull only what they lack, when they can absorb it (their request then goes
     * through the same budget as everything else).</p>
     */
    private static void queueAssetHint(MinecraftServer server, ServerPlayerEntity uploader, String relativePath, String sha1, long size) {
        if (sha1 == null || sha1.isEmpty() || relativePath == null) {
            return;
        }
        pendingAssetHints.put(relativePath, sha1 + ":" + size);
        if (!assetHintPending.compareAndSet(false, true)) {
            return;
        }
        final UUID uploaderId = uploader.getUuid();
        ASSET_CHANGE_SCHEDULER.schedule(() -> {
            assetHintPending.set(false);
            HashMap<String, String> hints = new HashMap<>(pendingAssetHints);
            pendingAssetHints.clear();
            if (hints.isEmpty()) {
                return;
            }
            try {
                server.execute(() -> {
                    File assetsFolder = BBSMod.getAssetsFolder();
                    for (ServerPlayerEntity other : server.getPlayerManager().getPlayerList()) {
                        if (other.getUuid().equals(uploaderId)) continue;
                        Boolean pref = playerReceivePush.get(other.getUuid());
                        if (pref != null && !pref.booleanValue()) continue;
                        /* Clients from before B4 cannot parse a hint; they keep the old behaviour
                         * of being sent the file, so mixed-version servers still work. */
                        boolean hintCapable = ServerPlayNetworking.canSend(other, CLIENT_ASSET_HINT);
                        Map<String, String> owned = playerOwnedAssets.get(other.getUuid());
                        MapType payload = new MapType();
                        for (Map.Entry<String, String> e : hints.entrySet()) {
                            String entry = e.getValue();
                            String hintSha1 = entry.substring(0, entry.indexOf(':'));
                            if (owned != null && hintSha1.equalsIgnoreCase(owned.get(e.getKey()))) continue;
                            if (hintCapable) {
                                payload.putString(e.getKey(), entry);
                                continue;
                            }
                            File legacy = new File(assetsFolder, e.getKey().replace("/", File.separator));
                            if (!legacy.isFile() || legacy.length() <= 0L) continue;
                            TogetherServerNetwork.enqueueAssetSend(server, other, new AssetSendTask(e.getKey(), legacy, legacy.length(), hintSha1, true));
                        }
                        if (!hintCapable || payload.keys().isEmpty()) continue;
                        TogetherServerNetwork.crusherFor(other).send((PlayerEntity)other, CLIENT_ASSET_HINT, (BaseType)payload, null);
                    }
                });
            }
            catch (Exception ignored) {}
        }, 2000L, TimeUnit.MILLISECONDS);
    }

    /** Auto sync: ~2s after an accepted upload changes server assets, ping everyone but the
     * uploader so their auto-sync re-diffs the manifest. Coalesced via the pending flag. */
    private static void scheduleAssetsChangedPing(MinecraftServer server, ServerPlayerEntity uploader) {
        if (!assetChangePingPending.compareAndSet(false, true)) {
            return;
        }
        final UUID uploaderId = uploader.getUuid();
        ASSET_CHANGE_SCHEDULER.schedule(() -> {
            assetChangePingPending.set(false);
            try {
                server.execute(() -> {
                    for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                        if (p.getUuid().equals(uploaderId)) continue;
                        ServerPlayNetworking.send(p, CLIENT_ASSETS_CHANGED, PacketByteBufs.create());
                    }
                });
            }
            catch (Exception ignored) {}
        }, 2000L, TimeUnit.MILLISECONDS);
    }

    /** Generous: a full "download everything" on a big repo is ~1500 files, so this only ever
     * trips on a runaway loop, never on a legitimate bulk pull. */
    private static final int ASSET_QUEUE_MAX = 4096;

    private static void enqueueAssetSend(MinecraftServer server, ServerPlayerEntity player, AssetSendTask task) {
        UUID uuid = player.getUuid();
        LinkedBlockingQueue<AssetSendTask> queue = assetSendQueues.computeIfAbsent(uuid, k -> new LinkedBlockingQueue());
        /* E1: a client that asks for thousands of files must not grow this queue without
         * bound — each entry holds a File and the drain thread is rate limited anyway. The
         * client retries through the manifest diff, so dropping the excess is safe. */
        if (queue.size() >= ASSET_QUEUE_MAX) {
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
                        LinkedBlockingQueue<AssetSendTask> q = assetSendQueues.get(uuid);
                        AssetSendTask t = q == null ? null : (AssetSendTask)q.poll();
                        if (t == null) {
                            /* Hand the drain role back before giving up, so a task racing in
                             * right now can't be stranded in a queue nobody drains. */
                            assetDrainActive.remove(uuid);
                            q = assetSendQueues.get(uuid);
                            if (q == null || q.isEmpty()) {
                                break;
                            }
                            if (!assetDrainActive.computeIfAbsent(uuid, k -> new AtomicBoolean(false)).compareAndSet(false, true)) {
                                break;
                            }
                            continue;
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

    /** A5: stream one asset as slice batches, pacing between batches (see sendIntervalMs).
     * B1: every batch first reserves its bytes from the server-wide egress budget, so several
     * players pulling at once share one ceiling instead of multiplying the per-player pacing. */
    private static void sendTaskInBatches(MinecraftServer server, ServerPlayerEntity player, AssetSendTask task) {
        long interval = Math.max(0L, TogetherServerNetwork.sendIntervalMs());
        final long batchBytes = Math.max(30_720L, TogetherServerNetwork.sendBatchBytes());
        final long size = task.size;
        if (size <= 0) {
            return;
        }
        AssetEgressBudget.sync();
        final int totalSlices = (int)Math.max(1L, (size + batchBytes - 1L) / batchBytes);
        try {
            for (int seq = 0; seq < totalSlices; ++seq) {
                final int fSeq = seq;
                final int fTotal = totalSlices;
                long budgetWaitNs = AssetEgressBudget.reserve((int)Math.min(batchBytes, size - (long)seq * batchBytes));
                if (budgetWaitNs > 0L) {
                    Thread.sleep(budgetWaitNs / 1_000_000L);
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
                            /* mtime rides along so the client restores it on disk and later
                             * manifest diffs arbitrate by edit time. Read at send time — for
                             * uploads this is the uploader's edit time (just stamped). */
                            outBuf.writeLong(task.file.lastModified());
                        });
                        assetBytesSent.addAndGet(slice.length);
                    }
                    catch (Exception e) {
                        e.printStackTrace();
                    }
                });
                if (seq < totalSlices - 1 && interval > 0L) {
                    Thread.sleep(interval);
                }
            }
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static byte[] readSlice(File file, long offset, int length) {
        try (FileChannel ch = FileChannel.open(file.toPath(), StandardOpenOption.READ)){
            ByteBuffer buffer = ByteBuffer.allocate(length);
            while (buffer.hasRemaining()) {
                int read = ch.read(buffer, offset + (long)buffer.position());
                if (read < 0) break;
            }
            if (buffer.hasRemaining()) {
                return null;
            }
            return buffer.array();
        }
        catch (Exception e) {
            /* Quietly skip: the file can legitimately vanish between enqueue and read
             * (upload temp deleted/promoted, asset removed). Callers handle null. */
            return null;
        }
    }

    /* ---- A4: debounced film saves ------------------------------------- */

    /** Mark one film branch dirty and arm a debounced save ~2s out; continuous editing coalesces. */
    private static void scheduleFilmSave(MinecraftServer server, String filmId, String branch) {
        String key = TogetherServerNetwork.branchKey(filmId, branch);
        AtomicBoolean flag = filmSaveDirty.computeIfAbsent(key, k -> new AtomicBoolean(false));
        flag.set(true);
        FILM_SAVE_SCHEDULER.schedule(() -> {
            try {
                server.execute(() -> TogetherServerNetwork.saveIfDirty(key));
            }
            catch (Exception ignored) {}
        }, FILM_SAVE_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    /** Runs on the server thread: saves the branch if it is still marked dirty.
     * Retries briefly on failure — BBS saves films from the netty thread while
     * we save from the server thread, and on Windows the loser gets a file lock. */
    private static void saveIfDirty(String key) {
        AtomicBoolean flag = filmSaveDirty.get(key);
        if (flag == null || !flag.compareAndSet(true, false)) {
            return;
        }
        int tab = key.lastIndexOf('\t');
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

    /** Flush every pending debounced save (server shutdown). Must run on the server thread. */
    public static void flushPendingFilmSaves(MinecraftServer server) {
        for (String key : filmSaveDirty.keySet()) {
            TogetherServerNetwork.saveIfDirty(key);
        }
        filmSaveDirty.clear();
        FilmBranchStore.saveAll();
    }

    /* ---- B1/B2/B3: client reports -------------------------------------- */

    private static void handleSyncPref(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        boolean receive = buf.readBoolean();
        server.execute(() -> playerReceivePush.put(player.getUuid(), receive));
    }

    /** B2: client posted its local asset inventory {path: sha1}; used to skip redundant pushes. */
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
                Map<String, String> owned = new HashMap<String, String>();
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

    /** A1/A2: incremental inventory. Same payload shape as {@link #handleAssetReport}, but an entry
     * with an empty sha1 means the client dropped that path, and everything else is merged in. */
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
                Map<String, String> updates = new HashMap<String, String>();
                ArrayList<String> removals = new ArrayList<String>();
                for (String key : report.keys()) {
                    String sha1 = report.getString(key);
                    if (sha1 == null || sha1.isEmpty()) {
                        removals.add(key);
                    } else {
                        updates.put(key, sha1);
                    }
                }
                server.execute(() -> {
                    Map<String, String> owned = playerOwnedAssets.computeIfAbsent(player.getUuid(), k -> new HashMap<String, String>());
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

    /** B3: client posted its local film inventory {filmId: sha1}; send only missing/changed films. */
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
                    Collection<String> keys = BBSMod.getFilms().getKeys();
                    for (String filmId : new ArrayList<String>(keys)) {
                        /* The branch working copy is mutable server state; do the hashing and
                         * the send on the server thread, not on this inventory thread. */
                        server.execute(() -> {
                            String branch = FilmBranchStore.branchOf(filmId, player.getUuid());
                            /* Film inventory is the main-file path. Other branches are sent
                             * when the player opens the film (join request/checkout), so a
                             * main-file hash can never overwrite their working copy. */
                            if (!FilmBranchStore.isDefault(filmId, branch)) {
                                return;
                            }
                            File file = BBSMod.getFilms().getFile(filmId);
                            if (file == null || !file.isFile()) {
                                return;
                            }
                            String serverSha1;
                            try {
                                serverSha1 = TogetherServerNetwork.sha1OfPathCached(file.toPath(), file);
                            } catch (IOException e) {
                                return;
                            }
                            String clientSha1 = inventory.has(filmId) ? inventory.getString(filmId) : "";
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
                boolean wasFirst = playerInFilmScreen.isEmpty();
                playerInFilmScreen.put(player.getUuid(), filmId);
                if (wasFirst) {
                    BBSMod.getActions().trackDamage(player.getServerWorld());
                }
                String branch = TogetherServerNetwork.playerBranch(filmId, player);
                String key = filmId + '\t' + branch;
                if (!key.equals(lastBranchStateSent.get(player.getUuid()))) {
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
        /* Read-only (level 0) players can never be remotely started into a recording
         * session — this packet bypasses every client-side UI lock, so the server is
         * the only place the rule can be enforced (see /bbsop plan, B-7). */
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

    private static void handleRecordingFinished(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        /* Recording needs both the film domain and its record sub-permission. */
        if (!BbsOpManager.isAllowed(player, BbsOpDomain.FILM) || !BbsOpManager.isAllowed(player, BbsOpDomain.RECORD) /* level 1/2 bypass the gamerule/OP gate: the owner granted editing */) {
            return;
        }
        TogetherServerNetwork.crusherFor(player).receive(buf, (bytes, packetByteBuf) -> {
            String filmId = packetByteBuf.readString();
            String sentBranch = packetByteBuf.isReadable() ? packetByteBuf.readString(FilmCommit.MAX_BRANCH * 2) : "";
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
                /* Coop takes are collected and merged once everyone handed in; the legacy
                 * per-player merge must not also run (double merge + master bookkeeping). */
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
        if (!BbsOpManager.isAllowed(player, BbsOpDomain.FILM) /* level 1/2 bypass the gamerule/OP gate: the owner granted editing */) {
            return;
        }
        TogetherServerNetwork.crusherFor(player).receive(buf, (bytes, packetByteBuf) -> {
            String filmId = packetByteBuf.readString();
            String sentBranch = packetByteBuf.isReadable() ? packetByteBuf.readString(FilmCommit.MAX_BRANCH * 2) : "";
            BaseType incoming = DataStorageUtils.readFromBytes((byte[])bytes);
            if (!incoming.isMap()) {
                return;
            }
            server.execute(() -> {
                String branch = TogetherServerNetwork.playerBranch(filmId, player);
                if (!sentBranch.isEmpty() && !sentBranch.equals(branch)) {
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

    /** Send a full film copy to one player, tagged with the branch it belongs to. */
    public static void broadcastFilmSavedToAll(MinecraftServer server, ServerPlayerEntity recipient, String filmId, String branch, MapType filmData) {
        TogetherServerNetwork.crusherFor(recipient).send((PlayerEntity)recipient, CLIENT_FILM_SAVED_SYNC, (BaseType)filmData, outBuf -> {
            outBuf.writeString(filmId);
            outBuf.writeString("[Server]");
            outBuf.writeString(branch == null ? "" : branch);
        });
    }

    /** B3: full data only to players looking at the same branch of the film; others get a
     * list-changed ping only when the default branch (the main film file) changed. */
    public static void broadcastFilmSaved(MinecraftServer server, String filmId, String branch, MapType filmData) {
        boolean defaultBranch = FilmBranchStore.isDefault(filmId, branch);
        ArrayList<ServerPlayerEntity> others = new ArrayList<ServerPlayerEntity>();
        for (ServerPlayerEntity recipient : server.getPlayerManager().getPlayerList()) {
            String editing = playerEditingFilm.get(recipient.getUuid());
            String screen = playerInFilmScreen.get(recipient.getUuid());
            boolean interested = filmId.equals(editing) || filmId.equals(screen);
            /* A player on another branch must not receive this branch's full film: it would
             * replace their working copy and their next save would wipe this branch. */
            if (interested && TogetherServerNetwork.playerBranch(filmId, recipient).equals(branch)) {
                TogetherServerNetwork.broadcastFilmSavedToAll(server, recipient, filmId, branch, filmData);
            } else if (!interested) {
                others.add(recipient);
            }
        }
        if (defaultBranch) {
            for (ServerPlayerEntity recipient : others) {
                ServerPlayNetworking.send(recipient, (Identifier)CLIENT_FILM_LIST_CHANGED, PacketByteBufs.create());
            }
        }
    }

    private static void handleFilmSaveSync(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        if (!BbsOpManager.isAllowed(player, BbsOpDomain.FILM) /* level 1/2 bypass the gamerule/OP gate: the owner granted editing */) {
            return;
        }
        TogetherServerNetwork.crusherFor(player).receive(buf, (bytes, packetByteBuf) -> {
            if (bytes == null) {
                return;
            }
            String filmId = packetByteBuf.readString();
            String sentBranch = packetByteBuf.isReadable() ? packetByteBuf.readString(FilmCommit.MAX_BRANCH * 2) : "";
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
                    /* A save that was already in flight while the player switched branches. */
                    System.out.println("[BBSTogether] Drop save from stale branch player=" + player.getGameProfile().getName() + " film=" + filmId + " sent=" + sentBranch + " current=" + branch);
                    return;
                }
                /* A coop session owns only its own branch: other branches keep editing. */
                if (CoopSessionManager.isFilmLockedForEditing(filmId, branch)) {
                    return;
                }
                String key = TogetherServerNetwork.branchKey(filmId, branch);
                Film stored = FilmBranchStore.working(filmId, branch);
                Film incomingFilm = new Film();
                incomingFilm.fromData((BaseType)incoming.asMap());
                MapType before = lastSavedFilmSnapshots.containsKey(key)
                    ? lastSavedFilmSnapshots.get(key)
                    : (MapType)stored.toData();
                TogetherServerNetwork.mergeFilms(stored, incomingFilm);
                MapType merged = (MapType)stored.toData();
                /* Compare the camera clips of the merged film against the pre-merge one,
                 * so the log says which clips were touched */
                ArrayList<String> changedClips = TogetherServerNetwork.collectChangedClips(before, merged);
                playerEditingFilm.put(player.getUuid(), filmId);
                TogetherServerNetwork.saveBranchNow(filmId, branch, merged);
                lastSavedFilmSnapshots.put(key, merged);
                /* Saving writes the film, it does not commit it: a commit is the author's
                 * decision, made with the commit button in the film editor */
                AtomicBoolean pending = filmSaveDirty.get(key);
                if (pending != null) {
                    pending.set(false);
                }
                /* Content comparison: a save whose merge changed nothing on an existing
                 * film isn't logged; new films always are */
                if (!BaseType.equals(before, merged)) {
                    BehaviorLogManager.record(player, BehaviorType.EDIT.id, BehaviorType.SUB_EDIT_FILM, filmId, changedClips, Map.of("replays", String.valueOf(mergedFilmReplays(merged)), "branch", branch));
                }
                TogetherServerNetwork.broadcastFilmSaved(server, filmId, branch, merged);
                TogetherServerNetwork.broadcastFilmSelections(server);
                TogetherServerNetwork.broadcastReplaySelections(server, filmId);
            });
        });
    }

    /* Film commits */

    private static void handleCommitIndex(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        /* Browsing history is read-only, so it needs no film permission: the films themselves
         * are readable by everyone on the server too */
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
                graph.put("commits", new ListType());
                graph.put("branches", new MapType());
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
            FilmCommitStore.Store store = FilmCommitStore.get(filmId);
            String branch = TogetherServerNetwork.playerBranch(filmId, player);
            String head = store == null ? "" : store.branchHead(branch);
            String headValue = head == null ? "" : head;
            MapType data = store == null || headValue.isEmpty() ? new MapType() : FilmCommitStore.snapshot(store, headValue);

            if (data == null) {
                data = new MapType();
            }

            TogetherServerNetwork.crusherFor(player).send((PlayerEntity)player, CLIENT_COMMIT_HEAD, (BaseType)data, outBuf -> {
                outBuf.writeString(filmId);
                outBuf.writeString(headValue);
            });
        });
    }

    /**
     * Remember which commit a player is looking at. Read-only players may select too; the
     * selection is only a cursor for the avatars drawn on the graph.
     */
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
            if (filmId.equals(entry.getValue().filmId)) {
                entries.add(entry);
            }
        }

        PacketByteBuf outBuf = PacketByteBufs.create();

        outBuf.writeString(filmId);
        outBuf.writeInt(entries.size());

        for (Map.Entry<UUID, CommitSelection> entry : entries) {
            outBuf.writeUuid(entry.getKey());
            outBuf.writeString(entry.getValue().commitId);
        }

        ServerPlayNetworking.send(recipient, (Identifier)CLIENT_COMMIT_SELECTIONS, outBuf);
    }

    private static void sendAllCommitSelections(MinecraftServer server, ServerPlayerEntity player) {
        Set<String> films = new HashSet<>();

        for (CommitSelection selection : playerCommitSelections.values()) {
            films.add(selection.filmId);
        }

        for (String filmId : films) {
            TogetherServerNetwork.sendCommitSelectionsTo(player, filmId);
        }
    }

    /** Broadcast where each player on this film currently is (their branch head). */
    private static void broadcastPlayerCommitPositions(MinecraftServer server, String filmId) {
        if (filmId == null || filmId.isEmpty()) {
            return;
        }

        for (ServerPlayerEntity recipient : server.getPlayerManager().getPlayerList()) {
            TogetherServerNetwork.sendPlayerCommitPositionsTo(server, recipient, filmId);
        }
    }

    private static void sendPlayerCommitPositionsTo(MinecraftServer server, ServerPlayerEntity recipient, String filmId) {
        LinkedHashMap<UUID, String> positions = new LinkedHashMap<>();

        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            UUID uuid = p.getUuid();

            if (!filmId.equals(playerEditingFilm.get(uuid)) && !filmId.equals(playerInFilmScreen.get(uuid))) {
                continue;
            }

            String branch = TogetherServerNetwork.playerBranch(filmId, p);
            String head = FilmCommitStore.branchHead(filmId, branch);

            if (head != null && !head.isEmpty()) {
                positions.put(uuid, head);
            }
        }

        PacketByteBuf outBuf = PacketByteBufs.create();

        outBuf.writeString(filmId);
        outBuf.writeInt(positions.size());

        for (Map.Entry<UUID, String> entry : positions.entrySet()) {
            outBuf.writeUuid(entry.getKey());
            outBuf.writeString(entry.getValue());
        }

        ServerPlayNetworking.send(recipient, (Identifier)CLIENT_PLAYER_COMMIT_POSITIONS, outBuf);
    }

    private static void sendAllPlayerCommitPositions(MinecraftServer server, ServerPlayerEntity recipient) {
        Set<String> films = new HashSet<>();

        films.addAll(playerEditingFilm.values());
        films.addAll(playerInFilmScreen.values());

        for (String filmId : films) {
            TogetherServerNetwork.sendPlayerCommitPositionsTo(server, recipient, filmId);
        }
    }

    private static void handleCommitRename(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        if (!BbsOpManager.isAllowed(player, BbsOpDomain.FILM) /* level 1/2 bypass the gamerule/OP gate: the owner granted editing */) {
            return;
        }

        String filmId = buf.readString();
        String commitId = buf.readString();
        String message = buf.readString(1024);

        server.execute(() -> {
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

    /**
     * Recolour one commit. Unlike renaming, this is not author-only: the colour is a shared
     * reading aid of the graph, so any editor may set it. The server stores it in the commit
     * index and broadcasts the resulting colour to everyone, which makes it survive restarts
     * and be identical on every client.
     */
    private static void handleCommitColor(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        if (!BbsOpManager.isAllowed(player, BbsOpDomain.FILM) /* level 1/2 bypass the gamerule/OP gate: the owner granted editing */) {
            return;
        }

        String filmId = buf.readString(512);
        String commitId = buf.readString(64);
        int color = Colors.opaque(buf.readInt());

        server.execute(() -> {
            if (!FilmCommitStore.setColor(filmId, commitId, color)) {
                return;
            }

            TogetherServerNetwork.broadcastCommitColor(server, filmId, commitId, color);
        });
    }

    private static void handleCommitRevert(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        if (!BbsOpManager.isAllowed(player, BbsOpDomain.FILM) /* level 1/2 bypass the gamerule/OP gate: the owner granted editing */) {
            return;
        }

        String filmId = buf.readString();
        String commitId = buf.readString();
        int mode = buf.readByte();

        server.execute(() -> {
            if (mode < FilmCommitStore.ROLLBACK_KEEP || mode > FilmCommitStore.RESET_DELETE) {
                return;
            }

            String targetBranch = FilmCommitStore.branchOfCommit(filmId, commitId);

            if (targetBranch == null || targetBranch.isEmpty()) {
                return;
            }

            /* Reverting writes the old snapshot over the target branch. */
            if (CoopSessionManager.isFilmLockedForEditing(filmId, targetBranch)) {
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

            BehaviorLogManager.record(player, BehaviorType.EDIT.id, BehaviorType.SUB_EDIT_FILM, filmId, new ArrayList<String>(), Map.of("commit", commitId, "rollback", String.valueOf(mode), "branch", targetBranch));

            /* The dropped commits must disappear from everyone's graph, so the whole list goes out */
            TogetherServerNetwork.broadcastCommits(server, filmId, FilmCommitStore.list(filmId));
            TogetherServerNetwork.broadcastFilmSaved(server, filmId, targetBranch, reverted);
            TogetherServerNetwork.broadcastFilmSelections(server);
            TogetherServerNetwork.broadcastReplaySelections(server, filmId);
            TogetherServerNetwork.broadcastPlayerCommitPositions(server, filmId);
        });
    }

    /**
     * Commit the film the author has open. The editor sends its current film along, so unsaved
     * edits are committed instead of whatever BBS last managed to write to disk.
     */
    private static void handleCommitCreate(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        if (!BbsOpManager.isAllowed(player, BbsOpDomain.FILM)) {
            return;
        }

        TogetherServerNetwork.crusherFor(player).receive(buf, (bytes, packetByteBuf) -> {
            String filmId = packetByteBuf.readString();
            String message = packetByteBuf.readString(FilmCommit.MAX_MESSAGE * 2);
            String sentBranch = packetByteBuf.isReadable() ? packetByteBuf.readString(FilmCommit.MAX_BRANCH * 2) : "";
            MapType payload = TogetherServerNetwork.readFilmPayload(bytes);

            server.execute(() -> {
                if (deletedFilmIds.contains(filmId)) {
                    TogetherServerNetwork.rejectCommit(player, COMMIT_REJECTED_NO_FILM);

                    return;
                }
                String branch = TogetherServerNetwork.playerBranch(filmId, player);
                if (!sentBranch.isEmpty() && !sentBranch.equals(branch)) {
                    /* The editor's graph still points at another branch: committing this payload
                     * would write one branch's content onto another. */
                    return;
                }
                if (CoopSessionManager.isFilmLockedForEditing(filmId, branch)) {
                    return;
                }
                MapType data = payload != null ? payload : FilmBranchStore.workingData(filmId, branch);

                if (data == null) {
                    TogetherServerNetwork.rejectCommit(player, COMMIT_REJECTED_NO_FILM);

                    return;
                }

                FilmCommit commit = FilmCommitStore.commit(filmId, data, player.getUuid().toString(), player.getGameProfile().getName(), System.currentTimeMillis(), message, branch);

                if (commit == null) {
                    TogetherServerNetwork.rejectCommit(player, COMMIT_REJECTED_NO_CHANGE);

                    return;
                }

                /* A commit is also the branch's working state: everything the author saw is
                 * what the branch now holds, and the main file follows only the default branch. */
                TogetherServerNetwork.saveBranchNow(filmId, branch, data);
                lastSavedFilmSnapshots.put(TogetherServerNetwork.branchKey(filmId, branch), data);

                BehaviorLogManager.record(player, BehaviorType.EDIT.id, BehaviorType.SUB_EDIT_FILM, filmId, new ArrayList<String>(), Map.of("commit", commit.id, "branch", branch));

                TogetherServerNetwork.broadcastCommitAdded(server, filmId, commit);
                TogetherServerNetwork.broadcastPlayerCommitPositions(server, filmId);
            });
        });
    }

    /** The film snapshot a client attached to a request, or null when it sent none. */
    private static MapType readFilmPayload(byte[] bytes) {
        if (bytes == null) {
            return null;
        }

        BaseType data = DataStorageUtils.readFromBytes(bytes);

        return data.isMap() ? (MapType) data.asMap() : null;
    }

    /** Chat/actionbar + server-log feedback for a personal branch checkout. */
    private static void branchCheckoutMessage(ServerPlayerEntity player, String branch, String reason, boolean success) {
        String message = success
            ? "§a[BBS Together] 已切换到分支 §f" + branch
            : "§e[BBS Together] 无法切换到分支 §f" + branch + "§e：" + reason;

        player.sendMessage(Text.literal(message), success);
        System.out.println("[BBSTogether] Branch checkout " + (success ? "ok" : "failed") + " player=" + player.getGameProfile().getName() + " film=" + playerEditingFilm.get(player.getUuid()) + " branch=" + branch + (success ? "" : " reason=" + reason));
    }

    private static void handleBranchCreate(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        if (!BbsOpManager.isAllowed(player, BbsOpDomain.FILM)) {
            return;
        }

        String filmId = buf.readString();
        String name = buf.readString(FilmCommit.MAX_BRANCH * 2);
        String commitId = buf.readString();

        server.execute(() -> {
            if (deletedFilmIds.contains(filmId)) {
                return;
            }
            if (!FilmCommitStore.createBranch(filmId, name, commitId)) {
                return;
            }

            TogetherServerNetwork.broadcastCommits(server, filmId, FilmCommitStore.list(filmId));
        });
    }

    /** Check a branch out: the film goes back to that branch's head, and commits follow it. */
    private static void handleBranchCheckout(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        if (!BbsOpManager.isAllowed(player, BbsOpDomain.FILM)) {
            return;
        }

        String filmId = buf.readString();
        String branch = buf.readString(FilmCommit.MAX_BRANCH * 2);

        server.execute(() -> {
            System.out.println("[BBSTogether] Branch checkout request player=" + player.getGameProfile().getName() + " film=" + filmId + " branch=" + branch);

            /* A coop session fixes its participants to one branch. */
            if (CoopSessionManager.isPlayerInSession(player.getUuid())) {
                TogetherServerNetwork.branchCheckoutMessage(player, branch, "合作录制或邀请中不能切换分支", false);

                return;
            }

            FilmCommitStore.Store store = FilmCommitStore.get(filmId);

            if (store == null || (!store.branches.containsKey(branch) && !branch.equals(FilmBranchStore.defaultBranch(filmId)))) {
                TogetherServerNetwork.branchCheckoutMessage(player, branch, "分支不存在", false);

                return;
            }

            /* Checking out a branch another coop session is recording must not clobber it. */
            if (CoopSessionManager.isFilmLockedForEditing(filmId, branch)) {
                TogetherServerNetwork.branchCheckoutMessage(player, branch, "该分支正在被合作录制占用", false);

                return;
            }

            if (!FilmBranchStore.setBranch(filmId, player.getUuid(), branch)) {
                TogetherServerNetwork.branchCheckoutMessage(player, branch, "无法保存分支选择", false);

                return;
            }

            String actual = FilmBranchStore.branchOf(filmId, player.getUuid());

            if (!branch.equals(actual)) {
                TogetherServerNetwork.branchCheckoutMessage(player, branch, "分支选择未生效（当前为 " + actual + "）", false);

                return;
            }

            playerEditingFilm.put(player.getUuid(), filmId);
            TogetherServerNetwork.branchCheckoutMessage(player, branch, "", true);

            /* Replay indices and form locks belong to the branch that is being left. */
            synchronized (playerReplaySelections) {
                playerReplaySelections.remove(player.getUuid());
            }
            playerFormLocks.remove(player.getUuid());
            playerCursors.remove(player.getUuid());

            BehaviorLogManager.record(player, BehaviorType.EDIT.id, BehaviorType.SUB_EDIT_FILM, filmId, new ArrayList<String>(), Map.of("branch", branch));

            /* Personal checkout: only this player's local film is replaced. Other players keep
             * editing their own branches, and the main film file keeps the default branch. */
            TogetherServerNetwork.sendFilmBranchState(server, player, filmId);
            TogetherServerNetwork.broadcastFilmSelections(server);
            TogetherServerNetwork.broadcastReplaySelections(server, filmId);
            TogetherServerNetwork.broadcastReplayFormLocks(server, filmId);
            TogetherServerNetwork.together_broadcastCursorSnapshot(server, filmId);
            TogetherServerNetwork.broadcastPlayerCommitPositions(server, filmId);
        });
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
            graph.put("commits", new ListType());
            graph.put("branches", new MapType());
        }

        /* FilmCommitStore.list() is global (the main/default branch); each client must get
         * the graph with ITS current branch and head, otherwise the header and diffs speak
         * about another player's branch. */
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
            ServerPlayNetworking.send(recipient, (Identifier)CLIENT_COMMIT_RENAMED, outBuf);
        }
    }

    private static void broadcastCommitColor(MinecraftServer server, String filmId, String commitId, int color) {
        for (ServerPlayerEntity recipient : server.getPlayerManager().getPlayerList()) {
            PacketByteBuf outBuf = PacketByteBufs.create();
            outBuf.writeString(filmId);
            outBuf.writeString(commitId);
            outBuf.writeInt(color);
            ServerPlayNetworking.send(recipient, (Identifier)CLIENT_COMMIT_COLOR, outBuf);
        }
    }

    /** Reasons sent with {@code c_commit_rejected}. */
    public static final int COMMIT_REJECTED_NO_CHANGE = 0;
    public static final int COMMIT_REJECTED_NO_FILM = 1;

    private static void rejectCommit(ServerPlayerEntity player, int reason) {
        PacketByteBuf outBuf = PacketByteBufs.create();
        outBuf.writeByte(reason);
        ServerPlayNetworking.send(player, (Identifier)CLIENT_COMMIT_REJECTED, outBuf);
    }

    /* Behavior log */

    /** How many replays a serialized film carries, for the film edit summary. */
    private static int mergedFilmReplays(MapType filmData) {
        try {
            return filmData.getList("replays").size();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Clip titles/indexes that differ between the pre-merge and post-merge
     * film's camera timeline: added, removed, or with any content change.
     * Clips carry no stable id, so they pair by position; a titled clip is
     * logged by its title, an untitled one as {@code #index} for the client
     * to jump back by position.
     */
    private static ArrayList<String> collectChangedClips(MapType before, MapType merged) {
        ArrayList<String> changed = new ArrayList<String>();
        ListType oldClips = before.getList("camera");
        ListType newClips = merged.getList("camera");
        int shared = Math.min(oldClips.size(), newClips.size());
        for (int i = 0; i < shared; ++i) {
            if (!BaseType.equals(oldClips.getMap(i), newClips.getMap(i))) {
                changed.add(clipLabel(newClips.getMap(i), i));
            }
        }
        for (int i = shared; i < newClips.size(); ++i) {
            changed.add(clipLabel(newClips.getMap(i), i));
        }
        for (int i = shared; i < oldClips.size(); ++i) {
            changed.add(clipLabel(oldClips.getMap(i), i));
        }
        return changed;
    }

    private static String clipLabel(MapType clip, int index) {
        String title = clip.getString("title");
        return title == null || title.isEmpty() ? "#" + index : title;
    }

    /** Aggregate the requested paths into model IDs and log one download entry.
     * Skins folders are filtered out, same as for uploads; the entry carries the
     * total size of the requested (non-skin) files. */
    private static void recordDownloadBehavior(ServerPlayerEntity player, List<String> paths) {
        try {
            ArrayList<String> modelIds = new ArrayList<String>();
            long totalBytes = 0;
            File assetsFolder = BBSMod.getAssetsFolder();
            for (String path : paths) {
                if (ModelPathUtils.isSkinPath(path)) {
                    continue;
                }
                String modelId = ModelPathUtils.extractModelId(path);
                if (modelId != null && !ModelPathUtils.isSkinPath("/" + modelId) && !modelIds.contains(modelId)) {
                    modelIds.add(modelId);
                }
                if (assetsFolder != null) {
                    try {
                        File asset = new File(assetsFolder, path.replace("/", File.separator));
                        if (asset.isFile()) {
                            totalBytes += asset.length();
                        }
                    } catch (Exception ignored) {}
                }
            }
            if (modelIds.isEmpty()) {
                return;
            }
            BehaviorLogManager.record(player, BehaviorType.DOWNLOAD.id, BehaviorType.SUB_DOWNLOAD_MODELS, modelIds.get(0), modelIds, Map.of(BehaviorLogManager.BYTES_KEY, String.valueOf(totalBytes)));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Log one upload entry, aggregated into model IDs only — paths that don't
     * belong to a model (skins, fonts, archives, ...) aren't logged, and the
     * pooled entry lists just the models, which the client can jump to. The
     * pooled entry also accumulates the uploaded byte count.
     *
     * Per-file reports are pooled into their model's pending aggregate first
     * (see {@link #queueUploadBehavior}), so a burst of N files of one model
     * lands as a single timeline occurrence instead of N identical rows.
     */
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
            queueUploadBehavior(player, modelId, bytes);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** One model's pooled upload reports: file count, summed bytes, last report time. */
    private static class PendingUpload {
        int files;
        long bytes;
        long lastTime;
    }

    /** Player UUID -> modelId -> pending upload aggregate. Touched on the server
     * thread (upload receivers and the tick sweep); synchronized for safety. */
    private static final Map<UUID, Map<String, PendingUpload>> pendingUploadAggregates = new ConcurrentHashMap<>();

    /** How long a model's aggregate waits for more files after the last one before being logged. */
    private static final long UPLOAD_AGGREGATE_WINDOW_MS = 3000L;

    /** Pool a per-file upload report into its model's pending aggregate; the tick
     * sweep logs it once that model has been quiet for {@link #UPLOAD_AGGREGATE_WINDOW_MS}. */
    private static void queueUploadBehavior(ServerPlayerEntity player, String modelId, long bytes) {
        synchronized (pendingUploadAggregates) {
            PendingUpload pending = pendingUploadAggregates
                .computeIfAbsent(player.getUuid(), k -> new HashMap<>())
                .computeIfAbsent(modelId, k -> new PendingUpload());

            pending.files++;
            pending.bytes += bytes;
            pending.lastTime = System.currentTimeMillis();
        }
    }

    /**
     * Log pending upload aggregates of this player. {@code forceAll} logs every
     * model now (player quit / server stop); otherwise only models quiet for
     * the whole window are flushed. Logging happens outside the lock —
     * BehaviorLogManager.record takes its own lock.
     */
    private static void flushUploadAggregates(ServerPlayerEntity player, boolean forceAll) {
        UUID id = player.getUuid();
        long now = System.currentTimeMillis();
        ArrayList<String> flushedModels = new ArrayList<String>();
        ArrayList<PendingUpload> flushedData = new ArrayList<PendingUpload>();

        synchronized (pendingUploadAggregates) {
            Map<String, PendingUpload> models = pendingUploadAggregates.get(id);

            if (models == null) {
                return;
            }

            for (Map.Entry<String, PendingUpload> entry : models.entrySet()) {
                if (!forceAll && now - entry.getValue().lastTime < UPLOAD_AGGREGATE_WINDOW_MS) {
                    continue;
                }

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

        for (int i = 0; i < flushedModels.size(); i++) {
            try {
                PendingUpload pending = flushedData.get(i);
                Map<String, String> extra = new HashMap<String, String>();

                extra.put(BehaviorLogManager.BYTES_KEY, String.valueOf(pending.bytes));
                extra.put("files", String.valueOf(pending.files));

                BehaviorLogManager.record(player, BehaviorType.UPLOAD.id, BehaviorType.SUB_UPLOAD_MODELS, "", Collections.singletonList(flushedModels.get(i)), extra);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * End-of-tick sweep: log every model whose file stream has been quiet for
     * the aggregate window, so the tail of an upload burst isn't stuck pending
     * until the player quits. Pending aggregates of players gone from the
     * player list are dropped (the disconnect hook flushes them first).
     */
    public static void sweepUploadAggregates(MinecraftServer server) {
        if (pendingUploadAggregates.isEmpty()) {
            return;
        }

        for (UUID id : new ArrayList<UUID>(pendingUploadAggregates.keySet())) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(id);

            if (player != null) {
                flushUploadAggregates(player, false);
            } else {
                pendingUploadAggregates.remove(id);
            }
        }
    }

    /**
     * A client reports its own action (currently: model editor saves). The client
     * can forge this — it's an audit hint, never a security input.
     */
    private static void handleBehaviorReport(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        final int subType = buf.readInt();
        final String target = buf.readString();
        int itemCount = buf.readInt();
        ArrayList<String> items = new ArrayList<String>();
        for (int i = 0; i < Math.min(itemCount, 64); ++i) {
            items.add(buf.readString());
        }
        final ArrayList<String> finalItems = items;
        server.execute(() -> {
            try {
                BehaviorLogManager.record(player, BehaviorType.EDIT.id, subType, target, finalItems, null);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private static void handleBehaviorQuery(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        final long afterId = buf.readLong();
        final long clientVersion = buf.readLong();
        server.execute(() -> {
            try {
                boolean isOp = BehaviorLogManager.isOperator(server, player);
                MapType snapshot = BehaviorLogManager.query(server, player.getUuid(), afterId, clientVersion, isOp);
                TogetherServerNetwork.crusherFor(player).send((PlayerEntity)player, CLIENT_BEHAVIOR_LIST, (BaseType)snapshot, null);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private static void handleSetOperator(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        final UUID targetId = buf.readUuid();
        final boolean op = buf.readBoolean();
        server.execute(() -> {
            try {
                if (!BehaviorLogManager.isOperator(server, player)) {
                    System.out.println("[BBSTogether] " + player.getGameProfile().getName() + " tried to " + (op ? "op" : "deop") + " someone without being an op");
                    return;
                }
                boolean done = BehaviorLogManager.setOperator(server, targetId, op);
                System.out.println("[BBSTogether] " + player.getGameProfile().getName() + (done ? (op ? " op'ed " : " deop'ed ") : " FAILED to change op status of ") + targetId);
                TogetherServerNetwork.broadcastPermissions(server);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    /**
     * A manager (vanilla OP or BBS level 2) sets another player's BBS permission level.
     * Persists on the spot; every client is re-synced so both the target's UI and every
     * player list reflect the change immediately.
     */
    private static void handleBbsopSet(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        final UUID targetId = buf.readUuid();
        final int level = Math.max(BbsOpManager.LEVEL_READ_ONLY, Math.min(BbsOpManager.LEVEL_ADMIN, buf.readInt()));
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
                broadcastPermissions(server);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    /** Allow/deny one {@link BbsOpDomain} for a level-1 player; manager-gated like {@link #handleBbsopSet}. */
    private static void handleBbsopGrant(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        final UUID targetId = buf.readUuid();
        final String domainId = buf.readString();
        final boolean allowed = buf.readBoolean();
        server.execute(() -> {
            try {
                if (!BbsOpManager.canManage(player)) {
                    System.out.println("[BBSTogether] " + player.getGameProfile().getName() + " tried to change a BBS domain grant without permission");
                    return;
                }
                mchorse.bbs_together.bbsop.BbsOpDomain domain = BbsOpDomain.byId(domainId);
                if (domain == null) {
                    return;
                }
                boolean changed = BbsOpManager.setGrant(targetId, domain, allowed);
                if (changed) {
                    System.out.println("[BBSTogether] " + player.getGameProfile().getName() + (allowed ? " allowed " : " denied ") + domain.id + " for " + targetId);
                    BbsOpManager.syncToAll(server);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private static void handleRemovePlayerLogs(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        final UUID targetId = buf.readUuid();
        server.execute(() -> {
            try {
                if (!BehaviorLogManager.isOperator(server, player)) {
                    return;
                }
                int removed = BehaviorLogManager.removePlayerLogs(targetId);
                System.out.println("[BBSTogether] " + player.getGameProfile().getName() + " removed " + removed + " log entries of " + targetId);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    /* ---- Model delete requests (model list context menu -> behavior list approval) ---- */

    private static boolean isModelTombstoned(String modelId) {
        Long expiry = recentlyDeletedModels.get(modelId);
        if (expiry == null) {
            return false;
        }
        if (expiry.longValue() < System.currentTimeMillis()) {
            recentlyDeletedModels.remove(modelId);
            return false;
        }
        return true;
    }

    private static void tombstoneModel(String modelId) {
        long now = System.currentTimeMillis();
        recentlyDeletedModels.put(modelId, now + MODEL_TOMBSTONE_TTL_MS);
        recentlyDeletedModels.values().removeIf(expiry -> expiry.longValue() < now);
    }

    /** Whether the folder still holds at least one file (a model is its folder). */
    private static boolean modelFolderHasFiles(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return false;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(dir.toPath())) {
            return walk.anyMatch(Files::isRegularFile);
        }
        catch (Exception e) {
            return dir.listFiles() != null && dir.listFiles().length > 0;
        }
    }

    private static void handleModelDeleteRequest(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        final String modelId = buf.readString();
        server.execute(() -> {
            try {
                if (modelId.isEmpty() || modelId.contains("..") || modelId.startsWith("/") || modelId.endsWith("/")) {
                    return;
                }
                File assetsFolder = BBSMod.getAssetsFolder();
                File modelDir = new File(assetsFolder, "models/" + modelId.replace('/', File.separatorChar));
                if (!modelFolderHasFiles(modelDir)) {
                    System.out.println("[BBSTogether] " + player.getGameProfile().getName() + " requested deletion of model " + modelId + " which does not exist on the server");
                    return;
                }
                if (BehaviorLogManager.hasPendingRequest(modelId)) {
                    return;
                }
                BehaviorLogManager.recordUnique(player, BehaviorType.OTHER.id, BehaviorType.SUB_REQUEST_MODEL_DELETE, modelId, Collections.singletonList(modelId), Map.of("status", "pending"));
                System.out.println("[BBSTogether] " + player.getGameProfile().getName() + " requested server deletion of model " + modelId);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private static void handleModelDeleteDecision(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        final long entryId = buf.readLong();
        final boolean approve = buf.readBoolean();
        server.execute(() -> {
            try {
                boolean isManager = BehaviorLogManager.isOperator(server, player) || BbsOpManager.get(player.getUuid()) >= BbsOpManager.LEVEL_ADMIN;
                if (!isManager) {
                    System.out.println("[BBSTogether] " + player.getGameProfile().getName() + " tried to decide a model delete request without being a manager");
                    return;
                }
                BehaviorEntry entry = BehaviorLogManager.find(entryId);
                if (entry == null || entry.type != BehaviorType.OTHER.id || entry.subType != BehaviorType.SUB_REQUEST_MODEL_DELETE
                    || !"pending".equals(entry.extra.get("status"))) {
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
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    /** Delete the model folder from the server assets, tombstone the id, and tell every
     * client to drop its local copy (the assets-changed ping re-diffs the manifest). */
    private static void deleteServerModel(MinecraftServer server, String modelId) {
        if (modelId == null || modelId.isEmpty() || modelId.contains("..")) {
            return;
        }
        File modelDir = new File(BBSMod.getAssetsFolder(), "models/" + modelId.replace('/', File.separatorChar));
        boolean removed = TogetherServerNetwork.deleteModelFolder(modelDir);
        if (!removed) {
            System.out.println("[BBSTogether] Model " + modelId + " was already gone from the server assets");
        }
        tombstoneModel(modelId);
        for (ServerPlayerEntity other : server.getPlayerManager().getPlayerList()) {
            PacketByteBuf out = PacketByteBufs.create();
            out.writeString(modelId);
            ServerPlayNetworking.send((ServerPlayerEntity)other, (Identifier)CLIENT_MODEL_DELETED, (PacketByteBuf)out);
            ServerPlayNetworking.send((ServerPlayerEntity)other, (Identifier)CLIENT_ASSETS_CHANGED, PacketByteBufs.create());
        }
    }

    private static boolean deleteModelFolder(File dir) {
        if (dir == null || !dir.exists()) {
            return false;
        }
        try {
            Files.walkFileTree(dir.toPath(), new SimpleFileVisitor<Path>() {
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

    /** Tell a player whether they are an OP and/or a BBS manager, so the panel knows which columns to show. */
    public static void sendPermissions(MinecraftServer server, ServerPlayerEntity player) {
        boolean isOp = BehaviorLogManager.isOperator(server, player);
        boolean isManager = isOp || BbsOpManager.get(player.getUuid()) >= BbsOpManager.LEVEL_ADMIN;
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
            deletedFilmIds.add(filmId);
            lastSavedFilmSnapshots.keySet().removeIf(key -> key.startsWith(filmId + '\t'));
            filmSaveDirty.keySet().removeIf(key -> key.startsWith(filmId + '\t'));
            try {
                BBSMod.getFilms().delete(filmId);
            }
            catch (Exception exception) {
            }
            FilmCommitStore.deleteFilm(filmId);
            FilmBranchStore.forgetFilm(filmId);
            playerCommitSelections.entrySet().removeIf(entry -> filmId.equals(entry.getValue().filmId));
            playerEditingFilm.entrySet().removeIf(entry -> filmId.equals(entry.getValue()));
            playerInFilmScreen.entrySet().removeIf(entry -> filmId.equals(entry.getValue()));
            playerCursors.entrySet().removeIf(entry -> filmId.equals(entry.getValue().filmId));
            playerFormLocks.entrySet().removeIf(entry -> filmId.equals(entry.getValue().filmId));
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
            try {
                BBSMod.getFilms().rename(oldId, newId);
            }
            catch (Exception exception) {
            }
            FilmCommitStore.renameFilm(oldId, newId);
            FilmBranchStore.renameFilm(oldId, newId);
            for (Map.Entry<UUID, CommitSelection> entry : playerCommitSelections.entrySet()) {
                if (oldId.equals(entry.getValue().filmId)) {
                    playerCommitSelections.put(entry.getKey(), new CommitSelection(newId, entry.getValue().commitId));
                }
            }
            TogetherServerNetwork.broadcastCommitSelections(server, oldId);
            TogetherServerNetwork.broadcastCommitSelections(server, newId);
            TogetherServerNetwork.renameBranchKeys(lastSavedFilmSnapshots, oldId, newId);
            TogetherServerNetwork.renameBranchKeys(filmSaveDirty, oldId, newId);
            /* Re-arm saves whose debounce task still holds the old key; that task will find
             * no flag under the old key and return without writing. */
            for (Map.Entry<String, AtomicBoolean> entry : filmSaveDirty.entrySet()) {
                if (!entry.getKey().startsWith(newId + '\t') || !entry.getValue().get()) {
                    continue;
                }
                int tab = entry.getKey().lastIndexOf('\t');
                if (tab >= 0) {
                    TogetherServerNetwork.scheduleFilmSave(server, newId, entry.getKey().substring(tab + 1));
                }
            }
            for (Map.Entry<UUID, String> entry : playerEditingFilm.entrySet()) {
                if (oldId.equals(entry.getValue())) {
                    playerEditingFilm.put(entry.getKey(), newId);
                }
            }
            for (Map.Entry<UUID, String> entry : playerInFilmScreen.entrySet()) {
                if (oldId.equals(entry.getValue())) {
                    playerInFilmScreen.put(entry.getKey(), newId);
                }
            }
            TogetherServerNetwork.broadcastPlayerCommitPositions(server, oldId);
            TogetherServerNetwork.broadcastPlayerCommitPositions(server, newId);
            for (CursorData cursor : playerCursors.values()) {
                if (oldId.equals(cursor.filmId)) {
                    cursor.filmId = newId;
                }
            }
            for (Map.Entry<UUID, FormLockData> entry : playerFormLocks.entrySet()) {
                if (oldId.equals(entry.getValue().filmId)) {
                    playerFormLocks.put(entry.getKey(), new FormLockData(newId, entry.getValue().replayIndex));
                }
            }
            for (ServerPlayerEntity other : server.getPlayerManager().getPlayerList()) {
                if (other.getUuid().equals(player.getUuid())) continue;
                PacketByteBuf renameOut = PacketByteBufs.create();
                renameOut.writeString(oldId);
                renameOut.writeString(newId);
                ServerPlayNetworking.send((ServerPlayerEntity)other, (Identifier)CLIENT_FILM_RENAMED, (PacketByteBuf)renameOut);
            }
            for (ServerPlayerEntity other : server.getPlayerManager().getPlayerList()) {
                ServerPlayNetworking.send((ServerPlayerEntity)other, (Identifier)CLIENT_FILM_LIST_CHANGED, (PacketByteBuf)PacketByteBufs.create());
            }
        });
    }

    /** Move every {@code oldId<TAB>branch} key of a per-branch map to the renamed film. */
    private static <T> void renameBranchKeys(Map<String, T> map, String oldId, String newId) {
        String prefix = oldId + '\t';
        ArrayList<String> keys = new ArrayList<String>();
        for (String key : map.keySet()) {
            if (key.startsWith(prefix)) {
                keys.add(key);
            }
        }
        for (String key : keys) {
            T value = map.remove(key);
            if (value != null) {
                map.put(newId + key.substring(oldId.length()), value);
            }
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
        List<Replay> storedReplays = stored.replays.getList();
        List<Replay> incomingReplays = incoming.replays.getList();
        for (int i = 0; i < incomingReplays.size(); ++i) {
            KeyframeChannel storedCh;
            Replay incomingReplay = incomingReplays.get(i);
            if (i >= storedReplays.size()) {
                stored.replays.add(incomingReplay);
                continue;
            }
            Replay storedReplay = storedReplays.get(i);
            for (KeyframeChannel keyframeChannel : incomingReplay.keyframes.getChannels()) {
                BaseValue storedVal = storedReplay.keyframes.get(keyframeChannel.getId());
                if (!(storedVal instanceof KeyframeChannel)) continue;
                storedCh = (KeyframeChannel)storedVal;
                if (keyframeChannel.getKeyframes().size() <= storedCh.getKeyframes().size()) continue;
                storedCh.fromData(keyframeChannel.toData());
            }
            for (Map.Entry<TrackId, KeyframeChannel> entry : incomingReplay.properties.tracks.entrySet()) {
                KeyframeChannel inCh = entry.getValue();
                storedCh = storedReplay.properties.get(entry.getKey());
                if (storedCh == null) {
                    storedReplay.properties.put(entry.getKey(), inCh);
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
            } else if ("properties".equals(group)) {
                TrackId trackId = TrackId.parse(chanId);
                if (trackId != null && (ch = replay.properties.get(trackId)) != null) {
                    ch.fromData(data);
                }
            }
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void handleFilmDataSync(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        if (!BbsOpManager.isAllowed(player, BbsOpDomain.FILM) /* level 1/2 bypass the gamerule/OP gate: the owner granted editing */) {
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
            String sentBranch = packetByteBuf.isReadable() ? packetByteBuf.readString(FilmCommit.MAX_BRANCH * 2) : "";
            server.execute(() -> {
                if (deletedFilmIds.contains(filmId)) {
                    return;
                }
                String branch = TogetherServerNetwork.playerBranch(filmId, player);
                if (!sentBranch.isEmpty() && !sentBranch.equals(branch)) {
                    /* An edit flushed just before/after a branch switch. */
                    System.out.println("[BBSTogether] Drop delta from stale branch player=" + player.getGameProfile().getName() + " film=" + filmId + " sent=" + sentBranch + " current=" + branch);
                    return;
                }
                /* Only the branch owned by a coop session is locked; don't relay a write the
                 * server itself will refuse. */
                if (CoopSessionManager.isFilmLockedForEditing(filmId, branch)) {
                    return;
                }
                Film film;
                playerEditingFilm.put(player.getUuid(), filmId);
                for (Map.Entry<UUID, String> entry : playerEditingFilm.entrySet()) {
                    ServerPlayerEntity other;
                    if (!entry.getValue().equals(filmId) || entry.getKey().equals(player.getUuid()) || (other = server.getPlayerManager().getPlayer(entry.getKey())) == null) continue;
                    /* Players on other branches have a different replay list and clip set:
                     * relaying by index/path would corrupt their editor. */
                    if (!TogetherServerNetwork.playerBranch(filmId, other).equals(branch)) continue;
                    PacketByteBuf outBuf = PacketByteBufs.create();
                    outBuf.writeString(filmId);
                    outBuf.writeString(branch);
                    outBuf.writeInt(path.size());
                    for (String s : path) {
                        outBuf.writeString(s);
                    }
                    outBuf.writeInt(bytes.length);
                    outBuf.writeBytes(bytes);
                    ServerPlayNetworking.send((ServerPlayerEntity)other, (Identifier)CLIENT_FILM_DATA_SYNC, (PacketByteBuf)outBuf);
                }
                if (path.size() >= 4 && "replays".equals(path.get(0)) && (film = FilmBranchStore.working(filmId, branch)) != null) {
                    lastSavedFilmSnapshots.computeIfAbsent(TogetherServerNetwork.branchKey(filmId, branch), k -> (MapType)film.toData());
                    TogetherServerNetwork.applyPathToFilm(film, path, DataStorageUtils.readFromBytes((byte[])bytes));
                    /* A4: live edits no longer rewrite the whole film on the main thread per
                     * packet; saves are debounced until edits go quiet. */
                    TogetherServerNetwork.scheduleFilmSave(server, filmId, branch);
                }
            });
        });
    }

    /** One subtree touched by a client's undo/redo. */
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

    /**
     * Undo/redo broadcast with a server-side optimistic lock.
     *
     * <p>Only "replays" channel paths (size == 4) are checked: those are live-applied on
     * every ordinary sync, so the server's copy is authoritative there. Everything else
     * (camera clips, deeper keyframe paths, structure) is relayed unchecked, exactly like
     * an ordinary data sync.</p>
     */
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
            String sentBranch = packetByteBuf.isReadable() ? packetByteBuf.readString(FilmCommit.MAX_BRANCH * 2) : "";
            server.execute(() -> {
                String branch = TogetherServerNetwork.playerBranch(filmId, player);
                if (!sentBranch.isEmpty() && !sentBranch.equals(branch)) {
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
            if (c.checkable) {
                film = FilmBranchStore.working(filmId, branch);
                break;
            }
        }
        if (film != null) {
            for (UndoChange c : changes) {
                if (!c.checkable) {
                    continue;
                }
                BaseValue current = TogetherServerNetwork.resolveServerValue(film, c.path);
                if (current == null) {
                    /* Nothing to compare against (e.g. the channel does not exist on the
                     * server yet) — let it through; a resync heals any fallout. */
                    continue;
                }
                byte[] actual = DataStorageUtils.writeToBytes(current.toData());
                if (!java.util.Arrays.equals(actual, c.expected)) {
                    TogetherServerNetwork.rejectUndo(server, player, film, changes);
                    return;
                }
            }
        }
        /* Accepted: same treatment as an ordinary sync — relay to co-editors on the same
         * branch, live-apply tracked paths, debounce the save. */
        boolean dirty = false;
        final Film filmCopy = film;
        for (UndoChange c : changes) {
            for (Map.Entry<UUID, String> entry : playerEditingFilm.entrySet()) {
                ServerPlayerEntity other;
                if (!entry.getValue().equals(filmId) || entry.getKey().equals(player.getUuid()) || (other = server.getPlayerManager().getPlayer(entry.getKey())) == null) continue;
                if (!TogetherServerNetwork.playerBranch(filmId, other).equals(branch)) continue;
                PacketByteBuf outBuf = PacketByteBufs.create();
                outBuf.writeString(filmId);
                outBuf.writeString(branch);
                outBuf.writeInt(c.path.size());
                for (String s : c.path) {
                    outBuf.writeString(s);
                }
                outBuf.writeInt(c.data.length);
                outBuf.writeBytes(c.data);
                ServerPlayNetworking.send((ServerPlayerEntity)other, (Identifier)CLIENT_FILM_DATA_SYNC, (PacketByteBuf)outBuf);
            }
            if (c.checkable && filmCopy != null) {
                lastSavedFilmSnapshots.computeIfAbsent(TogetherServerNetwork.branchKey(filmId, branch), k -> (MapType)filmCopy.toData());
                TogetherServerNetwork.applyPathToFilm(filmCopy, c.path, DataStorageUtils.readFromBytes(c.data));
                dirty = true;
            }
        }
        if (dirty) {
            TogetherServerNetwork.scheduleFilmSave(server, filmId, branch);
        }
    }

    /** Refuse a stale rollback: push the server's current data for every touched path back
     * to the undoer (their local copy follows), and tell them why. */
    private static void rejectUndo(MinecraftServer server, ServerPlayerEntity player, Film film, ArrayList<UndoChange> changes) {
        ArrayList<byte[]> current = new ArrayList<byte[]>();
        int count = 0;
        for (UndoChange c : changes) {
            if (!c.checkable) {
                current.add(null);
                continue;
            }
            BaseValue value = TogetherServerNetwork.resolveServerValue(film, c.path);
            byte[] data = value == null ? null : DataStorageUtils.writeToBytes(value.toData());
            current.add(data);
            if (data != null) {
                ++count;
            }
        }
        PacketByteBuf out = PacketByteBufs.create();
        out.writeString(film.getId());
        out.writeInt(count);
        for (int i = 0; i < changes.size(); ++i) {
            byte[] data = current.get(i);
            if (data == null) {
                continue;
            }
            UndoChange c = changes.get(i);
            out.writeInt(c.path.size());
            for (String s : c.path) {
                out.writeString(s);
            }
            out.writeInt(data.length);
            out.writeBytes(data);
        }
        ServerPlayNetworking.send(player, (Identifier)CLIENT_UNDO_REJECTED, (PacketByteBuf)out);
        player.sendMessage(Text.literal("§e[BBS Together]§f 撤销未生效：该区域刚被其他玩家修改，已为你恢复最新内容"), false);
    }

    /** Navigate the server's film copy along a client-side value path. */
    private static BaseValue resolveServerValue(Film film, List<String> path) {
        try {
            BaseValue current = film;
            for (String segment : path) {
                if (!(current instanceof BaseValueGroup)) {
                    return null;
                }
                current = ((BaseValueGroup)current).get(segment);
                if (current == null) {
                    return null;
                }
            }
            return current;
        }
        catch (Exception e) {
            return null;
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

    /** Fix 15.5: send every remaining cursor of the given film to all its current viewers. */
    private static void together_broadcastCursorSnapshot(MinecraftServer server, String filmId) {
        for (ServerPlayerEntity recipient : server.getPlayerManager().getPlayerList()) {
            UUID playerId = recipient.getUuid();
            String recipientFilm = playerEditingFilm.get(playerId);
            if (recipientFilm == null) {
                CursorData ownCursor = playerCursors.get(playerId);
                recipientFilm = ownCursor == null ? null : ownCursor.filmId;
            }
            if (!filmId.equals(recipientFilm)) continue;
            ArrayList<Map.Entry<UUID, CursorData>> entries = new ArrayList<Map.Entry<UUID, CursorData>>();
            for (Map.Entry<UUID, CursorData> entry : playerCursors.entrySet()) {
                if (!entry.getKey().equals(playerId) && filmId.equals(entry.getValue().filmId)
                    && TogetherServerNetwork.sameBranch(filmId, playerId, entry.getKey())) {
                    entries.add(entry);
                }
            }
            PacketByteBuf out = PacketByteBufs.create();
            /* Full snapshot of this film's remaining cursors: the client replaces its
             * whole set, which also drops the cursor of whoever just left. */
            out.writeBoolean(true);
            out.writeInt(entries.size());
            for (Map.Entry<UUID, CursorData> entry : entries) {
                CursorData cursor = entry.getValue();
                List<Integer> selected = cursor.selectedIndices == null ? List.of() : cursor.selectedIndices;
                out.writeUuid(entry.getKey());
                out.writeInt(cursor.editorType);
                out.writeFloat(cursor.tick);
                out.writeFloat(cursor.row);
                out.writeInt(selected.size());
                for (Integer index : selected) {
                    out.writeInt(index == null ? -1 : index);
                }
                out.writeInt(cursor.playheadTick);
            }
            ServerPlayNetworking.send(recipient, CLIENT_CURSOR_POSITIONS, out);
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
}

