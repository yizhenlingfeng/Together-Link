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
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_together.TogetherSettings;
import mchorse.bbs_together.BBSTogetherFix18;
import mchorse.bbs_together.BBSTogetherFix28Server;
import mchorse.bbs_together.bbsop.BbsOpDomain;
import mchorse.bbs_together.bbsop.BbsOpManager;
import mchorse.bbs_together.behaviors.BehaviorLogManager;
import mchorse.bbs_together.behaviors.BehaviorType;
import mchorse.bbs_together.utils.ModelPathUtils;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.MinecraftServer;

public class TogetherServerNetwork {
    /** C2S: client reports whether it wants to receive pushed assets (assetSyncReceive). */
    public static final Identifier SERVER_SYNC_PREF = new Identifier("bbs_together", "s_sync_pref");
    /** C2S (crusher): client reports its local asset inventory {path: sha1} for push filtering. */
    public static final Identifier SERVER_ASSET_REPORT = new Identifier("bbs_together", "s_asset_report");
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
     * Absolute server-side safety cap on a single synced asset (2 GB). Each client independently
     * chooses its own upload/download ceiling via {@code TogetherSettings.assetMaxSizeMb}; this
     * hard cap exists only so a misbehaving client can't buffer a multi-GB file and blow up the
     * server heap.
     */
    public static long maxAssetBytes() {
        return 2048L * 1024L * 1024L;
    }

    public static final Identifier CLIENT_ASSET_MANIFEST = new Identifier("bbs_together", "c_asset_manifest");
    public static final Identifier CLIENT_ASSET_FILE = new Identifier("bbs_together", "c_asset_file");
    public static final Identifier CLIENT_FILM_SCREENS = new Identifier("bbs_together", "c_film_screens");
    public static final Identifier CLIENT_MERGE_REPLAYS = new Identifier("bbs_together", "c_merge_replays");
    public static final Identifier CLIENT_FILM_SAVED_SYNC = new Identifier("bbs_together", "c_film_saved_sync");
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
    public static final Identifier CLIENT_BEHAVIOR_LIST = new Identifier("bbs_together", "c_behavior_list");
    public static final Identifier CLIENT_PERMISSIONS = new Identifier("bbs_together", "c_permissions");
    /** S2C: pushes the receiving player's own BBS edit level (0 read-only / 1-2 reserved). */
    public static final Identifier CLIENT_BBSOP_SYNC = new Identifier("bbs_together", "c_bbsop_sync");
    /** C2S: a level-2/vanilla-OP player sets another player's BBS permission level. */
    public static final Identifier SERVER_BBSOP_SET = new Identifier("bbs_together", "s_bbsop_set");
    /** C2S: a level-2/vanilla-OP player allows/denies one editable domain for a level-1 player. */
    public static final Identifier SERVER_BBSOP_GRANT = new Identifier("bbs_together", "s_bbsop_grant");
    private static final ConcurrentHashMap<UUID, ServerPacketCrusher> playerCrushers = new ConcurrentHashMap();
    private static final LinkedHashMap<UUID, Integer> playerReplaySelections = new LinkedHashMap();
    private static final ConcurrentHashMap<UUID, String> playerEditingFilm = new ConcurrentHashMap();
    private static final ConcurrentHashMap<UUID, String> playerInFilmScreen = new ConcurrentHashMap();
    private static final ConcurrentHashMap<UUID, Integer> playerColors = new ConcurrentHashMap();
    private static final Set<String> deletedFilmIds = Collections.synchronizedSet(new HashSet());
    /** Last saved state per film, the baseline film saves diff against: edits reach the
     * server through FILM_DATA_SYNC before (and besides) the full FILM_SAVE_SYNC, and in
     * single player BBS's own save has already rewritten the file by the time the sync
     * arrives — so the pre-save state can only come from this snapshot. */
    private static final ConcurrentHashMap<String, MapType> lastSavedFilmSnapshots = new ConcurrentHashMap();
    private static final ConcurrentHashMap<UUID, CursorData> playerCursors = new ConcurrentHashMap();
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
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_FILM_INVENTORY, (server, player, handler, buf, resp) -> TogetherServerNetwork.handleFilmInventory(server, player, buf));
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_FILM_SCREEN, (server, player, handler, buf, resp) -> TogetherServerNetwork.handleFilmScreen(server, player, buf));
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_RECORDING_FINISHED, (server, player, handler, buf, resp) -> TogetherServerNetwork.handleRecordingFinished(server, player, buf));
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_MASTER_DONE, (server, player, handler, buf, resp) -> TogetherServerNetwork.handleMasterRecordingDone(server, player, buf));
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_FILM_DATA_SYNC, (server, player, handler, buf, resp) -> TogetherServerNetwork.handleFilmDataSync(server, player, buf));
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_FILM_SAVE_SYNC, (server, player, handler, buf, resp) -> TogetherServerNetwork.handleFilmSaveSync(server, player, buf));
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_FILM_LIST_CHANGED, (server, player, handler, buf, resp) -> TogetherServerNetwork.handleFilmListChanged(server, player, buf));
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_FILM_DELETED, (server, player, handler, buf, resp) -> TogetherServerNetwork.handleFilmDeleted(server, player, buf));
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_FILM_RENAMED, (server, player, handler, buf, resp) -> TogetherServerNetwork.handleFilmRenamed(server, player, buf));
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_BEHAVIOR_REPORT, (server, player, handler, buf, resp) -> TogetherServerNetwork.handleBehaviorReport(server, player, buf));
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_BEHAVIOR_QUERY, (server, player, handler, buf, resp) -> TogetherServerNetwork.handleBehaviorQuery(server, player, buf));
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_SET_OPERATOR, (server, player, handler, buf, resp) -> TogetherServerNetwork.handleSetOperator(server, player, buf));
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_REMOVE_PLAYER_LOGS, (server, player, handler, buf, resp) -> TogetherServerNetwork.handleRemovePlayerLogs(server, player, buf));
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_BBSOP_SET, (server, player, handler, buf, resp) -> TogetherServerNetwork.handleBbsopSet(server, player, buf));
        ServerPlayNetworking.registerGlobalReceiver((Identifier)SERVER_BBSOP_GRANT, (server, player, handler, buf, resp) -> TogetherServerNetwork.handleBbsopGrant(server, player, buf));
        BBSTogetherFix28Server.registerReceivers();
    }

    public static void reset() {
        playerCrushers.clear();
        playerEditingFilm.clear();
        playerColors.clear();
        playerCursors.clear();
        playerFormLocks.clear();
        playerInFilmScreen.clear();
        deletedFilmIds.clear();
        lastSavedFilmSnapshots.clear();
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
        BBSTogetherFix28Server.resetState();
    }

    public static void clearPlayerState(ServerPlayerEntity player, MinecraftServer server) {
        try {
            UUID uuid = player.getUuid();
            LinkedHashMap<UUID, Integer> linkedHashMap = playerReplaySelections;
            synchronized (linkedHashMap) {
                playerReplaySelections.remove(uuid);
            }
            FormLockData formLock = playerFormLocks.remove(uuid);
            recordingPlayers.remove(uuid);
            playerEditingFilm.remove(uuid);
            playerColors.remove(uuid);
            CursorData goneCursor = playerCursors.remove(uuid);
            playerCrushers.remove(uuid);
            playerInFilmScreen.remove(uuid);
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
                playerEditingFilm.remove(player.getUuid());
            } else {
                playerEditingFilm.put(player.getUuid(), filmId);
            }
            TogetherServerNetwork.broadcastFilmSelections(server);
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
            synchronized (abstractMap) {
                buf.writeInt(playerReplaySelections.size());
                for (Map.Entry<UUID, Integer> entry : playerReplaySelections.entrySet()) {
                    buf.writeUuid(entry.getKey());
                    buf.writeInt(entry.getValue().intValue());
                }
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
            outBuf.writeInt(entries.size());
            for (Map.Entry entry : entries) {
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
                Files.walkFileTree(assetsPath, (FileVisitor<? super Path>)new SimpleFileVisitor<Path>(){

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        if (!attrs.isRegularFile()) {
                            return FileVisitResult.CONTINUE;
                        }
                        String relativePath = assetsPath.relativize(file).toString().replace(File.separator, "/");
                        /* ".bbsup" files are transient multi-slice upload temps: they may be
                         * deleted or promoted at any moment, so never advertise them or
                         * clients will request a file that vanishes mid-transfer. */
                        if (relativePath.endsWith(".bbsup")) {
                            return FileVisitResult.CONTINUE;
                        }
                        /* Manifest entries carry "<sha1>:<sizeBytes>" so clients can skip over-size
                         * assets before requesting them. */
                        String sha1 = TogetherServerNetwork.sha1OfPathCached(file, file.toFile());
                        String entry = sha1 + ":" + file.toFile().length();
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
        Thread thread = new Thread(() -> {
            File assetsFolder = BBSMod.getAssetsFolder();
            for (String relativePath : paths) {
                File file;
                if (relativePath.contains("..") || relativePath.startsWith("/") || relativePath.endsWith(".bbsup") || !(file = new File(assetsFolder, relativePath.replace("/", File.separator))).exists() || !file.isFile()) continue;
                if (file.length() > TogetherServerNetwork.maxAssetBytes() || file.length() == 0) continue;
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

    private static void handleAssetUpload(MinecraftServer server, ServerPlayerEntity uploader, PacketByteBuf buf) {
        /* Read-only (level 0) players may never upload, even if the gamerule would allow panels. */
        if (!BbsOpManager.isAllowed(uploader, BbsOpDomain.UPLOAD)) {
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
            if (relativePath.contains("..") || relativePath.startsWith("/")) {
                return;
            }
            if (bytes == null || totalSize > TogetherServerNetwork.maxAssetBytes()) {
                return;
            }
            File assetsFolder = BBSMod.getAssetsFolder();
            if (assetsFolder == null) {
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

    /** Post-upload bookkeeping: cache the hash, log the behavior, then push to everyone who
     * wants pushes (B1) and does not already own the identical file (B2). */
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
        server.execute(() -> {
            for (ServerPlayerEntity other : server.getPlayerManager().getPlayerList()) {
                if (other.getUuid().equals(uploader.getUuid())) continue;
                Boolean pref = playerReceivePush.get(other.getUuid());
                if (pref != null && !pref.booleanValue()) continue;
                Map<String, String> owned = playerOwnedAssets.get(other.getUuid());
                if (!sha1.isEmpty() && owned != null && sha1.equalsIgnoreCase(owned.get(relativePath))) continue;
                TogetherServerNetwork.enqueueAssetSend(server, other, new AssetSendTask(relativePath, target.toFile(), size, sha1, true));
            }
        });
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

    private static void enqueueAssetSend(MinecraftServer server, ServerPlayerEntity player, AssetSendTask task) {
        UUID uuid = player.getUuid();
        LinkedBlockingQueue<AssetSendTask> queue = assetSendQueues.computeIfAbsent(uuid, k -> new LinkedBlockingQueue());
        queue.add(task);
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

    /** A5: stream one asset as slice batches, pacing between batches (see sendIntervalMs). */
    private static void sendTaskInBatches(MinecraftServer server, ServerPlayerEntity player, AssetSendTask task) {
        long interval = Math.max(0L, TogetherServerNetwork.sendIntervalMs());
        final long batchBytes = Math.max(30_720L, TogetherServerNetwork.sendBatchBytes());
        final long size = task.size;
        if (size <= 0) {
            return;
        }
        final int totalSlices = (int)Math.max(1L, (size + batchBytes - 1L) / batchBytes);
        try {
            for (int seq = 0; seq < totalSlices; ++seq) {
                final int fSeq = seq;
                final int fTotal = totalSlices;
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
                        });
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

    /** Mark a film dirty and arm a debounced save ~2s out; continuous editing coalesces. */
    private static void scheduleFilmSave(MinecraftServer server, String filmId) {
        AtomicBoolean flag = filmSaveDirty.computeIfAbsent(filmId, k -> new AtomicBoolean(false));
        flag.set(true);
        FILM_SAVE_SCHEDULER.schedule(() -> {
            try {
                server.execute(() -> TogetherServerNetwork.saveIfDirty(filmId));
            }
            catch (Exception ignored) {}
        }, FILM_SAVE_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    /** Runs on the server thread: saves the film if it is still marked dirty. */
    private static void saveIfDirty(String filmId) {
        AtomicBoolean flag = filmSaveDirty.get(filmId);
        if (flag == null || !flag.compareAndSet(true, false)) {
            return;
        }
        try {
            Film film = (Film)BBSMod.getFilms().load(filmId);
            if (film != null) {
                BBSMod.getFilms().save(filmId, (MapType)film.toData());
            }
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** Flush every pending debounced save (server shutdown). Must run on the server thread. */
    public static void flushPendingFilmSaves(MinecraftServer server) {
        for (String filmId : filmSaveDirty.keySet()) {
            TogetherServerNetwork.saveIfDirty(filmId);
        }
        filmSaveDirty.clear();
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
                    for (String filmId : keys) {
                        File file = BBSMod.getFilms().getFile(filmId);
                        if (file == null || !file.isFile()) continue;
                        String serverSha1 = TogetherServerNetwork.sha1OfPathCached(file.toPath(), file);
                        String clientSha1 = inventory.has(filmId) ? inventory.getString(filmId) : "";
                        if (!clientSha1.isEmpty() && clientSha1.equalsIgnoreCase(serverSha1)) continue;
                        Film film = (Film)BBSMod.getFilms().load(filmId);
                        if (film == null) continue;
                        MapType filmData = (MapType)film.toData();
                        server.execute(() -> TogetherServerNetwork.broadcastFilmSavedToAll(server, player, filmId, filmData));
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
            if (filmId.isEmpty()) {
                boolean wasLast = playerInFilmScreen.size() == 1 && playerInFilmScreen.containsKey(player.getUuid());
                playerInFilmScreen.remove(player.getUuid());
                if (wasLast) {
                    BBSMod.getActions().resetDamage(player.getServerWorld());
                }
            } else {
                boolean wasFirst = playerInFilmScreen.isEmpty();
                playerInFilmScreen.put(player.getUuid(), filmId);
                if (wasFirst) {
                    BBSMod.getActions().trackDamage(player.getServerWorld());
                }
            }
            TogetherServerNetwork.broadcastFilmScreens(server);
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
        if (!BbsOpManager.isAllowed(player, BbsOpDomain.FILM) /* level 1/2 bypass the gamerule/OP gate: the owner granted editing */) {
            return;
        }
        TogetherServerNetwork.crusherFor(player).receive(buf, (bytes, packetByteBuf) -> {
            String filmId = packetByteBuf.readString();
            int replayIndex = packetByteBuf.readInt();
            BaseType replayData = DataStorageUtils.readFromBytes((byte[])bytes);
            if (!replayData.isMap()) {
                return;
            }
            server.execute(() -> {
                BBSTogetherFix18.mergeRecordingFinished(filmId, replayIndex, replayData, server);
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
            BaseType incoming = DataStorageUtils.readFromBytes((byte[])bytes);
            if (!incoming.isMap()) {
                return;
            }
            server.execute(() -> {
                MapType merged;
                Film stored = (Film)BBSMod.getFilms().load(filmId);
                if (stored == null) {
                    merged = incoming.asMap();
                } else {
                    Film incomingFilm = new Film();
                    incomingFilm.fromData((BaseType)incoming.asMap());
                    TogetherServerNetwork.mergeFilms(stored, incomingFilm);
                    merged = (MapType)stored.toData();
                }
                BBSMod.getFilms().save(filmId, merged);
                TogetherServerNetwork.broadcastFilmSaved(server, filmId, merged);
            });
        });
    }

    private static void serverSideMerge(MinecraftServer server, String filmId) {
        Film film = (Film)BBSMod.getFilms().load(filmId);
        if (film == null) {
            pendingReplayData.clear();
            return;
        }
        List replays = film.replays.getList();
        for (Map.Entry<Integer, MapType> entry : pendingReplayData.entrySet()) {
            int idx = entry.getKey();
            if (idx < 0 || idx >= replays.size()) continue;
            MapType incomingReplayMap = entry.getValue();
            Replay storedReplay = (Replay)replays.get(idx);
            if (incomingReplayMap.has("keyframes")) {
                storedReplay.keyframes.fromData(incomingReplayMap.get("keyframes"));
            }
            if (!incomingReplayMap.has("properties")) continue;
            storedReplay.properties.fromData(incomingReplayMap.get("properties"));
        }
        pendingReplayData.clear();
        MapType filmData = (MapType)film.toData();
        BBSMod.getFilms().save(filmId, filmData);
        TogetherServerNetwork.broadcastFilmSaved(server, filmId, filmData);
    }

    public static void broadcastFilmSavedToAll(MinecraftServer server, ServerPlayerEntity recipient, String filmId, MapType filmData) {
        TogetherServerNetwork.crusherFor(recipient).send((PlayerEntity)recipient, CLIENT_FILM_SAVED_SYNC, (BaseType)filmData, outBuf -> {
            outBuf.writeString(filmId);
            outBuf.writeString("[Server]");
        });
    }

    /** B3: full data only to players looking at the film; others get a list-changed ping. */
    public static void broadcastFilmSaved(MinecraftServer server, String filmId, MapType filmData) {
        ArrayList<ServerPlayerEntity> others = new ArrayList<ServerPlayerEntity>();
        for (ServerPlayerEntity recipient : server.getPlayerManager().getPlayerList()) {
            String editing = playerEditingFilm.get(recipient.getUuid());
            String screen = playerInFilmScreen.get(recipient.getUuid());
            boolean interested = filmId.equals(editing) || filmId.equals(screen);
            if (interested) {
                TogetherServerNetwork.broadcastFilmSavedToAll(server, recipient, filmId, filmData);
            } else {
                others.add(recipient);
            }
        }
        for (ServerPlayerEntity recipient : others) {
            ServerPlayNetworking.send(recipient, (Identifier)CLIENT_FILM_LIST_CHANGED, PacketByteBufs.create());
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
            BaseType incoming = DataStorageUtils.readFromBytes((byte[])bytes);
            if (!incoming.isMap()) {
                return;
            }
            server.execute(() -> {
                Film stored = (Film)BBSMod.getFilms().load(filmId);
                Film incomingFilm = new Film();
                incomingFilm.fromData((BaseType)incoming.asMap());
                MapType before = null;
                MapType merged = null;
                ArrayList<String> changedClips = new ArrayList<String>();
                if (stored == null) {
                    if (deletedFilmIds.contains(filmId)) {
                        return;
                    }
                    merged = (MapType)incomingFilm.toData();
                    for (Replay replay : incomingFilm.replays.getList()) {
                        changedClips.add(replay.getId());
                    }
                } else {
                    /* The baseline is the last known saved state, NOT the file on disk:
                     * in single player BBS's own save has already rewritten it, and live
                     * edits arrive here through FILM_DATA_SYNC before any save */
                    before = lastSavedFilmSnapshots.containsKey(filmId)
                        ? lastSavedFilmSnapshots.get(filmId)
                        : (MapType)stored.toData();
                    TogetherServerNetwork.mergeFilms(stored, incomingFilm);
                    merged = (MapType)stored.toData();
                    /* Compare the camera clips of the merged film against the pre-merge one,
                     * so the log says which clips were touched */
                    changedClips = TogetherServerNetwork.collectChangedClips(before, merged);
                }
                playerEditingFilm.put(player.getUuid(), filmId);
                BBSMod.getFilms().save(filmId, merged);
                lastSavedFilmSnapshots.put(filmId, merged);
                AtomicBoolean pending = filmSaveDirty.get(filmId);
                if (pending != null) {
                    pending.set(false);
                }
                /* Content comparison: a save whose merge changed nothing on an existing
                 * film isn't logged; new films always are */
                if (before == null || !BaseType.equals(before, merged)) {
                    BehaviorLogManager.record(player, BehaviorType.EDIT.id, BehaviorType.SUB_EDIT_FILM, filmId, changedClips, Map.of("replays", String.valueOf(mergedFilmReplays(merged))));
                }
                TogetherServerNetwork.broadcastFilmSaved(server, filmId, merged);
                TogetherServerNetwork.broadcastFilmSelections(server);
                TogetherServerNetwork.broadcastReplaySelections(server, filmId);
            });
        });
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
            BehaviorLogManager.record(player, BehaviorType.UPLOAD.id, BehaviorType.SUB_UPLOAD_MODELS, "", Collections.singletonList(modelId), Map.of(BehaviorLogManager.BYTES_KEY, String.valueOf(bytes)));
        } catch (Exception e) {
            e.printStackTrace();
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
            lastSavedFilmSnapshots.remove(filmId);
            try {
                BBSMod.getFilms().delete(filmId);
            }
            catch (Exception exception) {
                // empty catch block
            }
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
                // empty catch block
            }
            lastSavedFilmSnapshots.remove(oldId);
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
            server.execute(() -> {
                Film film;
                playerEditingFilm.put(player.getUuid(), filmId);
                for (Map.Entry<UUID, String> entry : playerEditingFilm.entrySet()) {
                    ServerPlayerEntity other;
                    if (!entry.getValue().equals(filmId) || entry.getKey().equals(player.getUuid()) || (other = server.getPlayerManager().getPlayer(entry.getKey())) == null) continue;
                    PacketByteBuf outBuf = PacketByteBufs.create();
                    outBuf.writeString(filmId);
                    outBuf.writeInt(path.size());
                    for (String s : path) {
                        outBuf.writeString(s);
                    }
                    outBuf.writeInt(bytes.length);
                    outBuf.writeBytes(bytes);
                    ServerPlayNetworking.send((ServerPlayerEntity)other, (Identifier)CLIENT_FILM_DATA_SYNC, (PacketByteBuf)outBuf);
                }
                if (path.size() >= 4 && "replays".equals(path.get(0)) && (film = (Film)BBSMod.getFilms().load(filmId)) != null) {
                    lastSavedFilmSnapshots.computeIfAbsent(filmId, k -> (MapType)film.toData());
                    TogetherServerNetwork.applyPathToFilm(film, path, DataStorageUtils.readFromBytes((byte[])bytes));
                    /* A4: live edits no longer rewrite the whole film on the main thread per
                     * packet; saves are debounced until edits go quiet. */
                    TogetherServerNetwork.scheduleFilmSave(server, filmId);
                }
            });
        });
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
                if (!entry.getKey().equals(playerId) && filmId.equals(entry.getValue().filmId)) {
                    entries.add(entry);
                }
            }
            PacketByteBuf out = PacketByteBufs.create();
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

