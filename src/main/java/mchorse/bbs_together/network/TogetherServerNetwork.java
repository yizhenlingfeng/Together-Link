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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.data.DataStorageUtils;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.film.replays.tracks.TrackId;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.network.ServerPacketCrusher;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.utils.PermissionUtils;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_together.BBSTogetherFix18;
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
    private static final String NS = "bbs_together";
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
    private static final long ASSET_SEND_INTERVAL_MS = 50L;
    private static final ConcurrentHashMap<UUID, LinkedBlockingQueue<AssetSendTask>> assetSendQueues = new ConcurrentHashMap();
    private static final ConcurrentHashMap<UUID, Boolean> recordingPlayers = new ConcurrentHashMap();
    private static final ConcurrentHashMap<UUID, AtomicBoolean> assetDrainActive = new ConcurrentHashMap();

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
            TogetherServerNetwork.sendAssetManifest(server, player);
        });
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
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
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
        for (LinkedBlockingQueue linkedBlockingQueue : assetSendQueues.values()) {
            linkedBlockingQueue.clear();
        }
        assetSendQueues.clear();
        assetDrainActive.clear();
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
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
            playerCursors.remove(uuid);
            playerCrushers.remove(uuid);
            playerInFilmScreen.remove(uuid);
            LinkedBlockingQueue<AssetSendTask> pendingAssets = assetSendQueues.remove(uuid);
            if (pendingAssets != null) {
                pendingAssets.clear();
            }
            assetDrainActive.remove(uuid);
            if (!server.isStopping() && !server.isStopped()) {
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
        String filmId = buf.readString();
        int editorType = buf.readInt();
        float tick = buf.readFloat();
        float row = buf.readFloat();
        int selCount = buf.readInt();
        ArrayList<Integer> selected = new ArrayList<Integer>(selCount);
        for (int i = 0; i < selCount; ++i) {
            selected.add(buf.readInt());
        }
        int playheadTick = buf.readInt();
        server.execute(() -> {
            playerEditingFilm.put(player.getUuid(), filmId);
            playerCursors.put(player.getUuid(), new CursorData(filmId, editorType, tick, row, selected, playheadTick));
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                List<Map.Entry<UUID, CursorData>> entries;
                CursorData fallback;
                if (p.getUuid().equals(player.getUuid())) continue;
                String otherFilm = playerEditingFilm.get(p.getUuid());
                if (otherFilm == null && (fallback = playerCursors.get(p.getUuid())) != null) {
                    otherFilm = fallback.filmId;
                }
                if (otherFilm == null || !otherFilm.equals(filmId)) continue;
                ConcurrentHashMap<UUID, CursorData> concurrentHashMap = playerCursors;
                synchronized (concurrentHashMap) {
                    entries = playerCursors.entrySet().stream().filter(e -> ((CursorData)e.getValue()).filmId.equals(filmId) && !((UUID)e.getKey()).equals(p.getUuid())).collect(Collectors.toList());
                }
                PacketByteBuf outBuf = PacketByteBufs.create();
                outBuf.writeInt(entries.size());
                for (Map.Entry e2 : entries) {
                    outBuf.writeUuid((UUID)e2.getKey());
                    outBuf.writeInt(((CursorData)e2.getValue()).editorType);
                    outBuf.writeFloat(((CursorData)e2.getValue()).tick);
                    outBuf.writeFloat(((CursorData)e2.getValue()).row);
                    outBuf.writeInt(((CursorData)e2.getValue()).selectedIndices.size());
                    for (int idx : ((CursorData)e2.getValue()).selectedIndices) {
                        outBuf.writeInt(idx);
                    }
                    outBuf.writeInt(((CursorData)e2.getValue()).playheadTick);
                }
                ServerPlayNetworking.send((ServerPlayerEntity)p, (Identifier)CLIENT_CURSOR_POSITIONS, (PacketByteBuf)outBuf);
            }
        });
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

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
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
        File assetsFolder = BBSMod.getAssetsFolder();
        if (assetsFolder == null || !assetsFolder.exists()) {
            return;
        }
        final Path assetsPath = assetsFolder.toPath();
        Thread thread = new Thread(() -> {
            try {
                final MapType manifest = new MapType();
                Files.walkFileTree(assetsPath, (FileVisitor<? super Path>)new SimpleFileVisitor<Path>(){

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        if (!attrs.isRegularFile()) {
                            return FileVisitResult.CONTINUE;
                        }
                        String relativePath = assetsPath.relativize(file).toString().replace(File.separator, "/");
                        /* Manifest entries carry "<sha1>:<sizeBytes>" so clients can skip over-size
                         * assets before requesting them. */
                        manifest.putString(relativePath, TogetherServerNetwork.sha1OfPath(file) + ":" + file.toFile().length());
                        return FileVisitResult.CONTINUE;
                    }
                });
                server.execute(() -> TogetherServerNetwork.crusherFor(player).send((PlayerEntity)player, CLIENT_ASSET_MANIFEST, (BaseType)manifest, null));
            }
            catch (Exception e) {
                e.printStackTrace();
            }
        }, "BBSTogether-AssetManifest");
        thread.setDaemon(true);
        thread.start();
    }

    public static void sendInitialFilmSync(MinecraftServer server, ServerPlayerEntity player) {
        try {
            Collection<String> keys = BBSMod.getFilms().getKeys();
            for (String filmId : keys) {
                Film film = (Film)BBSMod.getFilms().load(filmId);
                if (film == null) continue;
                MapType filmData = (MapType)film.toData();
                TogetherServerNetwork.crusherFor(player).send((PlayerEntity)player, CLIENT_FILM_SAVED_SYNC, (BaseType)filmData, outBuf -> {
                    outBuf.writeString(filmId);
                    outBuf.writeString("[Server]");
                });
            }
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
                if (relativePath.contains("..") || relativePath.startsWith("/") || !(file = new File(assetsFolder, relativePath.replace("/", File.separator))).exists() || !file.isFile()) continue;
                if (file.length() > TogetherServerNetwork.maxAssetBytes()) continue;
                try {
                    byte[] fileBytes = Files.readAllBytes(file.toPath());
                    TogetherServerNetwork.enqueueAssetSend(server, player, new AssetSendTask(relativePath, fileBytes, false));
                }
                catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }, "BBSTogether-AssetSend");
        thread.setDaemon(true);
        thread.start();
    }

    private static void handleAssetUpload(MinecraftServer server, ServerPlayerEntity uploader, PacketByteBuf buf) {
        TogetherServerNetwork.crusherFor(uploader).receive(buf, (bytes, packetByteBuf) -> {
            String relativePath = packetByteBuf.readString();
            if (relativePath.contains("..") || relativePath.startsWith("/")) {
                return;
            }
            if (bytes.length > TogetherServerNetwork.maxAssetBytes()) {
                return;
            }
            /* Persist the uploaded asset into the server's own assets folder. Without this the file
             * is only relayed to other players and never lands on the server, so it also never shows
             * up in later manifests. */
            File assetsFolder = BBSMod.getAssetsFolder();
            boolean changed = true;
            if (assetsFolder != null) {
                try {
                    Path target = assetsFolder.toPath().resolve(relativePath.replace("/", File.separator));
                    if (target.getParent() != null) {
                        Files.createDirectories(target.getParent());
                    }
                    /* Content comparison: re-uploading identical bytes (a save that changed
                     * nothing) isn't logged */
                    byte[] existing = Files.exists(target) ? Files.readAllBytes(target) : null;
                    changed = existing == null || !java.util.Arrays.equals(existing, bytes);
                    Files.write(target, bytes);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            if (changed) {
                recordUploadBehavior(uploader, relativePath, (long)bytes.length);
            }
            server.execute(() -> {
                for (ServerPlayerEntity other : server.getPlayerManager().getPlayerList()) {
                    if (other == uploader) continue;
                    TogetherServerNetwork.enqueueAssetSend(server, other, new AssetSendTask(relativePath, bytes, true));
                }
            });
        });
    }

    private static void enqueueAssetSend(MinecraftServer server, ServerPlayerEntity player, AssetSendTask task) {
        UUID uuid = player.getUuid();
        LinkedBlockingQueue queue = assetSendQueues.computeIfAbsent(uuid, k -> new LinkedBlockingQueue());
        queue.add(task);
        AtomicBoolean draining = assetDrainActive.computeIfAbsent(uuid, k -> new AtomicBoolean(false));
        if (draining.compareAndSet(false, true)) {
            Thread drainThread = new Thread(() -> {
                try {
                    LinkedBlockingQueue<AssetSendTask> q = assetSendQueues.get(uuid);
                    while (q != null && !q.isEmpty()) {
                        AssetSendTask t = q.poll();
                        if (t == null) {
                            break;
                        }
                        AssetSendTask ft = t;
                        server.execute(() -> {
                            if (!server.getPlayerManager().getPlayerList().contains(player)) {
                                return;
                            }
                            TogetherServerNetwork.crusherFor(player).send((PlayerEntity)player, CLIENT_ASSET_FILE, ft.bytes, outBuf -> {
                                outBuf.writeString(ft.relativePath);
                                outBuf.writeBoolean(ft.isPush);
                            });
                        });
                        try {
                            Thread.sleep(50L);
                        }
                        catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
                finally {
                    assetDrainActive.remove(uuid);
                    assetSendQueues.remove(uuid);
                }
            }, "BBSTogether-AssetDrain-" + String.valueOf(uuid));
            drainThread.setDaemon(true);
            drainThread.start();
        }
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
        if (!PermissionUtils.arePanelsAllowed((MinecraftServer)server, (ServerPlayerEntity)player)) {
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
        if (!PermissionUtils.arePanelsAllowed((MinecraftServer)server, (ServerPlayerEntity)player)) {
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
                for (ServerPlayerEntity recipient : server.getPlayerManager().getPlayerList()) {
                    TogetherServerNetwork.broadcastFilmSavedToAll(server, recipient, filmId, merged);
                }
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
        for (ServerPlayerEntity recipient : server.getPlayerManager().getPlayerList()) {
            TogetherServerNetwork.broadcastFilmSavedToAll(server, recipient, filmId, filmData);
        }
    }

    public static void broadcastFilmSavedToAll(MinecraftServer server, ServerPlayerEntity recipient, String filmId, MapType filmData) {
        TogetherServerNetwork.crusherFor(recipient).send((PlayerEntity)recipient, CLIENT_FILM_SAVED_SYNC, (BaseType)filmData, outBuf -> {
            outBuf.writeString(filmId);
            outBuf.writeString("[Server]");
        });
    }

    private static void handleFilmSaveSync(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        if (!PermissionUtils.arePanelsAllowed((MinecraftServer)server, (ServerPlayerEntity)player)) {
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
                ArrayList<String> changedReplays = new ArrayList<String>();
                if (stored == null) {
                    if (deletedFilmIds.contains(filmId)) {
                        return;
                    }
                    merged = (MapType)incomingFilm.toData();
                    for (Replay replay : incomingFilm.replays.getList()) {
                        changedReplays.add(replay.getId());
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
                    changedReplays = TogetherServerNetwork.collectChangedClips(before, merged);
                }
                playerEditingFilm.put(player.getUuid(), filmId);
                BBSMod.getFilms().save(filmId, merged);
                lastSavedFilmSnapshots.put(filmId, merged);
                /* Content comparison: a save whose merge changed nothing on an existing
                 * film isn't logged; new films always are */
                if (before == null || !BaseType.equals(before, merged)) {
                    BehaviorLogManager.record(player, BehaviorType.EDIT.id, BehaviorType.SUB_EDIT_FILM, filmId, changedReplays, Map.of("replays", String.valueOf(mergedFilmReplays(merged))));
                }
                for (ServerPlayerEntity recipient : server.getPlayerManager().getPlayerList()) {
                    TogetherServerNetwork.broadcastFilmSavedToAll(server, recipient, filmId, merged);
                }
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
                MapType snapshot = BehaviorLogManager.query(server, afterId, clientVersion, isOp);
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

    /** Tell a player whether they are an OP, so the panel can show the player list. */
    public static void sendPermissions(MinecraftServer server, ServerPlayerEntity player) {
        boolean isOp = BehaviorLogManager.isOperator(server, player);
        PacketByteBuf out = PacketByteBufs.create();
        out.writeBoolean(isOp);
        ServerPlayNetworking.send((ServerPlayerEntity)player, (Identifier)CLIENT_PERMISSIONS, (PacketByteBuf)out);
    }

    public static void broadcastPermissions(MinecraftServer server) {
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            TogetherServerNetwork.sendPermissions(server, p);
        }
    }

    private static void handleFilmDeleted(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
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
        if (!PermissionUtils.arePanelsAllowed((MinecraftServer)server, (ServerPlayerEntity)player)) {
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
                    BBSMod.getFilms().save(filmId, (MapType)film.toData());
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
        final byte[] bytes;
        final boolean isPush;

        AssetSendTask(String relativePath, byte[] bytes, boolean isPush) {
            this.relativePath = relativePath;
            this.bytes = bytes;
            this.isPush = isPush;
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

