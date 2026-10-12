package mchorse.bbs_together.network;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.data.DataStorageUtils;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.Films;
import mchorse.bbs_mod.film.Recorder;
import mchorse.bbs_mod.film.replays.FormProperties;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.network.ClientPacketCrusher;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.settings.values.base.BaseValueGroup;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.framework.UIBaseMenu;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.UIScreen;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.factories.UIPoseKeyframeFactory;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.graphs.UIKeyframeDopeSheet;
import mchorse.bbs_mod.utils.DataPath;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.clips.Clips;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.film.replays.tracks.TrackId;
import mchorse.bbs_mod.utils.pose.Pose;
import mchorse.bbs_together.TogetherSettings;
import mchorse.bbs_together.network.TogetherServerNetwork;
import mchorse.bbs_together.sync.AssetSyncTracker;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;
import net.minecraft.client.MinecraftClient;

@Environment(value=EnvType.CLIENT)
public class TogetherClientNetwork {
    private static final ClientPacketCrusher crusher = new ClientPacketCrusher();
    private static boolean isBBSTogetherOnServer = false;
    public static final ConcurrentHashMap<UUID, Integer> playerColors = new ConcurrentHashMap();
    public static final ConcurrentHashMap<UUID, CursorPosition> playerCursors = new ConcurrentHashMap();
    public static final ConcurrentHashMap<UUID, String> filmSelections = new ConcurrentHashMap();
    public static final ConcurrentHashMap<String, Map<Integer, List<UUID>>> replaySelections = new ConcurrentHashMap();
    public static final ConcurrentHashMap<String, Map<Integer, UUID>> replayFormLocks = new ConcurrentHashMap();
    public static final ConcurrentHashMap<UUID, String> playersInFilmScreen = new ConcurrentHashMap();
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
    private static final int MAX_SELECTION_PACKET = 512;
    /** 0 = normal (follow settings), 1 = download-only resync, 2 = upload-only resync. */
    private static volatile int resyncMode = 0;

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
        long mtime = Files.getLastModifiedTime(path).toMillis();
        long size = path.toFile().length();
        HashEntry e = localHashCache.get(path.toString());
        if (e != null && e.mtime == mtime && e.size == size) {
            return e.sha1;
        }
        String sha1 = sha1OfLocalFile(path);
        localHashCache.put(path.toString(), new HashEntry(mtime, size, sha1));
        return sha1;
    }

    private static void cacheLocalSha1(Path path, String sha1) {
        if (sha1 == null || sha1.isEmpty()) return;
        try {
            localHashCache.put(path.toString(), new HashEntry(Files.getLastModifiedTime(path).toMillis(), path.toFile().length(), sha1));
        }
        catch (Exception ignored) {}
    }

    /* ---- B4: cached last server manifest + fingerprint -------------------------------- */

    /** Last received server manifest, kept across joins so unchanged manifests cost nothing. */
    private static final HashMap<String, String> lastServerSha1Map = new HashMap();
    private static final HashMap<String, Long> lastServerSizeMap = new HashMap();
    private static volatile long lastManifestVersion = 0L;

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
        ClientPlayNetworking.registerGlobalReceiver((Identifier)TogetherServerNetwork.CLIENT_FILM_SCREENS, (client, handler, buf, resp) -> TogetherClientNetwork.handleFilmScreensPacket(client, buf));
        ClientPlayNetworking.registerGlobalReceiver((Identifier)TogetherServerNetwork.CLIENT_FILM_SAVED_SYNC, (client, handler, buf, resp) -> TogetherClientNetwork.handleFilmSavedSyncPacket(client, buf));
        ClientPlayNetworking.registerGlobalReceiver((Identifier)TogetherServerNetwork.CLIENT_FILM_DATA_SYNC, (client, handler, buf, resp) -> TogetherClientNetwork.handleFilmDataSyncPacket(client, buf));
        ClientPlayNetworking.registerGlobalReceiver((Identifier)TogetherServerNetwork.CLIENT_FILM_LIST_CHANGED, (client, handler, buf, resp) -> TogetherClientNetwork.handleFilmListChangedPacket(client));
        ClientPlayNetworking.registerGlobalReceiver((Identifier)TogetherServerNetwork.CLIENT_FILM_DELETED, (client, handler, buf, resp) -> TogetherClientNetwork.handleFilmDeletedPacket(client, buf.readString()));
        ClientPlayNetworking.registerGlobalReceiver((Identifier)TogetherServerNetwork.CLIENT_FILM_RENAMED, (client, handler, buf, resp) -> TogetherClientNetwork.handleFilmRenamedPacket(client, buf.readString(), buf.readString()));
        ClientPlayNetworking.registerGlobalReceiver((Identifier)TogetherServerNetwork.CLIENT_MERGE_REPLAYS, (client, handler, buf, resp) -> TogetherClientNetwork.handleMergeReplaysPacket(client, buf));
        ClientPlayNetworking.registerGlobalReceiver((Identifier)TogetherServerNetwork.CLIENT_BEHAVIOR_LIST, (client, handler, buf, resp) -> TogetherClientNetwork.handleBehaviorListPacket(client, buf));
        ClientPlayNetworking.registerGlobalReceiver((Identifier)TogetherServerNetwork.CLIENT_PERMISSIONS, (client, handler, buf, resp) -> TogetherClientNetwork.handlePermissionsPacket(client, buf));
        ClientPlayNetworking.registerGlobalReceiver((Identifier)TogetherServerNetwork.CLIENT_BBSOP_SYNC, (client, handler, buf, resp) -> TogetherClientNetwork.handleBbsopSyncPacket(client, buf));
        isBBSTogetherOnServer = true;
    }

    public static void reset() {
        crusher.reset();
        AssetSyncTracker.reset();
        playerColors.clear();
        playerCursors.clear();
        filmSelections.clear();
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
        damageControlActive = false;
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
        ignoredTransfers.clear();
        pendingCursor = null;
        autoSyncInFlight.set(false);
        autoSyncScheduled.set(false);
        lastAutoSyncMs = 0L;
        mchorse.bbs_together.bbsop.BbsOpClientState.reset();
        isBBSTogetherOnServer = false;
    }

    public static void onJoin() {
        isBBSTogetherOnServer = true;
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
        /* Auto sync: watch the local assets folder so local edits upload by themselves. */
        mchorse.bbs_together.sync.AssetAutoSyncWatcher.ensureStarted();
    }

    public static boolean isConnected() {
        return isBBSTogetherOnServer;
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
        UIDashboard dashboard = TogetherClientNetwork.getDashboardIfExists();
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
            int recordedIdx = recorder.exception;
            if (panel == null || panel.getData() == null) {
                return;
            }
            Film film = (Film)panel.getData();
            panel.applyRecordedKeyframes(recorder, film);
            if (panel.getData() != null && ((Film)panel.getData()).getId().equals(filmId)) {
                panel.replayEditor.replaysList.replays.update();
                panel.getController().createEntities();
            }
        } else {
            if (recorder != null) {
                return;
            }
            int idx = replayIndex >= 0 ? replayIndex : lastLocalReplayIdx;
            Film film = panel != null && panel.getData() != null && ((Film)panel.getData()).getId().equals(filmId) ? (Film)panel.getData() : (Film)BBSMod.getFilms().load(filmId);
            if (film == null) {
                return;
            }
            if (idx < 0 || idx >= film.replays.getList().size()) {
                return;
            }
            films.startRecording(film, idx, 0);
            recordingSessionActive = true;
        }
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
        UIDashboard dashboard = TogetherClientNetwork.getDashboardIfOpen();
        UIFilmPanel panel = dashboard == null ? null : (UIFilmPanel)dashboard.getPanel(UIFilmPanel.class);
        Film film = panel != null && panel.getData() != null && ((Film)panel.getData()).getId().equals(filmId) ? (Film)panel.getData() : (Film)BBSMod.getFilms().load(filmId);
        if (film == null) {
            return;
        }
        List replays = film.replays.getList();
        for (String key : batchMap.keys()) {
            try {
                int idx = Integer.parseInt(key);
                BaseType rd = batchMap.get(key);
                if (idx < 0 || idx >= replays.size() || rd == null || !rd.isMap()) continue;
                ((Replay)replays.get(idx)).fromData((BaseType)rd.asMap());
            }
            catch (NumberFormatException numberFormatException) {}
        }
        TogetherClientNetwork.sendMasterRecordingDone(filmId, film);
        if (panel != null && panel.getData() != null && ((Film)panel.getData()).getId().equals(filmId)) {
            panel.fillData();
            panel.replayEditor.replaysList.replays.update();
            panel.getController().createEntities();
            Replay r = panel.replayEditor.getReplay();
            int idx = r == null ? -1 : ((Film)panel.getData()).replays.getList().indexOf(r);
            if (idx < 0 && !((Film)panel.getData()).replays.getList().isEmpty()) {
                panel.replayEditor.setReplay((Replay)((Film)panel.getData()).replays.getList().get(0));
                idx = 0;
            }
            TogetherClientNetwork.sendReplaySelection(filmId, idx);
        }
    }
    private static void handleAssetManifestPacket(MinecraftClient client, PacketByteBuf buf) {
        crusher.receive(buf, (bytes, packetByteBuf) -> {
            /* An auto-triggered diff is settled once its answer lands (also settles on
             * manual resync answers — those are rarer and never overlap for long). */
            autoSyncInFlight.set(false);
            boolean dl = (Boolean)TogetherSettings.assetSyncDownload.get();
            boolean ul = ((Boolean)TogetherSettings.assetSyncUpload.get()).booleanValue();
            if (resyncMode == 1) { dl = true; ul = false; }
            else if (resyncMode == 2) { dl = false; ul = true; }
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
                    if (reuseCache) {
                        synchronized (lastServerSha1Map) {
                            serverSha1Map.putAll(lastServerSha1Map);
                            serverSizeMap.putAll(lastServerSizeMap);
                        }
                    } else {
                        for (String key : manifest.keys()) {
                            String raw = manifest.get(key).asString();
                            int colon = raw.indexOf(':');
                            if (colon > 0) {
                                serverSha1Map.put(key, raw.substring(0, colon));
                                try { serverSizeMap.put(key, Long.parseLong(raw.substring(colon + 1))); }
                                catch (NumberFormatException ignored2) {}
                            } else {
                                serverSha1Map.put(key, raw);
                            }
                        }
                        synchronized (lastServerSha1Map) {
                            lastServerSha1Map.clear();
                            lastServerSha1Map.putAll(serverSha1Map);
                            lastServerSizeMap.clear();
                            lastServerSizeMap.putAll(serverSizeMap);
                        }
                        lastManifestVersion = manifestVersion;
                    }
                    long maxBytes = TogetherClientNetwork.clientMaxAssetBytes();
                    TogetherClientNetwork.tooLargeModels.clear();
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
                    HashMap<String, Long> modelTotalSize = new HashMap<String, Long>();
                    for (Map.Entry<String, Long> e : serverSizeMap.entrySet()) {
                        String modelId = together$extractModelId(e.getKey());
                        if (modelId != null) modelTotalSize.merge(modelId, e.getValue(), Long::sum);
                    }
                    HashSet<String> oversizedModels = new HashSet<String>();
                    for (Map.Entry<String, Long> e : modelTotalSize.entrySet()) {
                        if (e.getValue() <= maxBytes) continue;
                        String modelId = e.getKey();
                        File localConfig = new File(assetsFolder, "models/" + modelId + "/config.json");
                        if (localConfig.exists() && localConfig.isFile()) continue;
                        oversizedModels.add(modelId);
                        TogetherClientNetwork.tooLargeModels.add(modelId);
                    }
                    AssetSyncTracker.beginScan();
                    boolean hadTasks = false;
                    if (doDownload) {
                        ArrayList<String> needed = new ArrayList<String>();
                        for (Map.Entry entry : serverSha1Map.entrySet()) {
                            String relPath = (String) entry.getKey();
                            Long fileSize = serverSizeMap.get(relPath);
                            if (fileSize != null && fileSize > maxBytes) {
                                continue;
                            }
                            String modelId = together$extractModelId(relPath);
                            if (modelId != null && oversizedModels.contains(modelId)) {
                                if (!relPath.endsWith("/config.json")) continue;
                                new File(assetsFolder, "models/" + modelId).mkdirs();
                            }
                            File localFile = new File(assetsFolder, relPath.replace("/", File.separator));
                            boolean needsDownload = true;
                            if (localFile.exists() && localFile.isFile()) {
                                needsDownload = !((String)entry.getValue()).equalsIgnoreCase(TogetherClientNetwork.sha1OfLocalFileCached(localFile.toPath()));
                            }
                            if (!needsDownload) continue;
                            needed.add(relPath);
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
                                String serverSha1 = (String)serverSha1Map.get(relativePath);
                                String localSha1 = TogetherClientNetwork.sha1OfLocalFileCached(localPath);
                                if ((serverSha1 == null || !serverSha1.equalsIgnoreCase(localSha1))) {
                                    toUpload.add(relativePath);
                                }
                            }
                            catch (Exception e) {
                                e.printStackTrace();
                            }
                        });
                        if (!toUpload.isEmpty()) {
                            int uploadCount = toUpload.size();
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
                                    try {
                                        Thread.sleep(upInterval);
                                    }
                                    catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                        break;
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
                AssetSyncTracker.done(relativePath);
                if (!isPush) {
                    TogetherClientNetwork.finishDownloadTask();
                }
                return;
            }
            client.execute(() -> {
                Thread thread = new Thread(() -> {
                    try {
                        AssetSyncTracker.active(relativePath, isPush ? AssetSyncTracker.Dir.PUSH : AssetSyncTracker.Dir.DOWNLOAD);
                        File dest = new File(BBSMod.getAssetsFolder(), relativePath.replace("/", File.separator));
                        dest.getParentFile().mkdirs();
                        Path part = dest.toPath().resolveSibling(dest.getName() + ".bbspart");
                        if (seq == 0) {
                            Files.write(part, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                        } else {
                            Files.write(part, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                        }
                        if (seq < seqTotal - 1) {
                            return;
                        }
                        /* Final slice: verify the combined content, then promote atomically. */
                        if (!sha1.isEmpty() && !sha1OfLocalFile(part).equalsIgnoreCase(sha1)) {
                            throw new IOException("sha1 mismatch");
                        }
                        Files.move(part, dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        if (!sha1.isEmpty()) {
                            TogetherClientNetwork.cacheLocalSha1(dest.toPath(), sha1);
                        }
                        System.out.println("[BBS Assets] " + (isPush ? "Received push" : "Downloaded") + ": " + relativePath);
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
                        AssetSyncTracker.error(relativePath);
                        TogetherClientNetwork.finishDownloadTask();
                    }
                }, "BBSTogether-AssetWrite");
                thread.setDaemon(true);
                thread.start();
            });
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
        if (!isBBSTogetherOnServer) {
            return;
        }
        if (replayIndex >= 0) {
            lastLocalReplayIdx = replayIndex;
        }
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

    public static long clientMaxAssetBytes() {
        int mb = TogetherSettings.assetMaxSizeMb == null
            ? 1024
            : ((Number) TogetherSettings.assetMaxSizeMb.get()).intValue();
        if (mb < 16) mb = 16;
        if (mb > 2048) mb = 2048;
        return (long) mb * 1024L * 1024L;
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

    /** A5: stream one asset to the server as slice batches; files never sit in the heap in full. */
    public static void sendAssetUploadSliced(File f, String relPath, long batchBytes, long interval) throws Exception {
        long size = f.length();
        int totalSlices = (int)Math.max(1L, (size + batchBytes - 1L) / batchBytes);
        String sha1 = TogetherClientNetwork.sha1OfLocalFileCached(f.toPath());
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
                AssetSyncTracker.active(relPath, AssetSyncTracker.Dir.UPLOAD);
                sendCrusher(MinecraftClient.getInstance().player, TogetherServerNetwork.SERVER_ASSET_UPLOAD, slice, pbuf -> {
                    pbuf.writeString(relPath);
                    pbuf.writeInt(fSeq);
                    pbuf.writeInt(fTotal);
                    pbuf.writeLong(fSize);
                    pbuf.writeString(fSha);
                });
                if (seq < totalSlices - 1 && interval > 0L) {
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
        sendManifestVersionQuery();
    }

    /** B4: request the manifest, riding our cached manifest fingerprint along — an unchanged
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
     * pushes of files we already own. Debounced; the server-side filter is best-effort. */
    private static void sendAssetInventory() {
        if (!isBBSTogetherOnServer || MinecraftClient.getInstance().player == null) {
            return;
        }
        File assetsFolder = BBSMod.getAssetsFolder();
        if (assetsFolder == null || !assetsFolder.exists()) {
            return;
        }
        Thread thread = new Thread(() -> {
            try {
                Path assetsPath = assetsFolder.toPath();
                MapType report = new MapType();
                Files.walk(assetsPath, new FileVisitOption[0]).filter(x$0 -> Files.isRegularFile(x$0, new LinkOption[0])).forEach(p -> {
                    try {
                        String rel = assetsPath.relativize((Path)p).toString().replace(File.separator, "/");
                        report.putString(rel, TogetherClientNetwork.sha1OfLocalFileCached((Path)p));
                    }
                    catch (Exception ignored) {}
                });
                MinecraftClient.getInstance().execute(() -> {
                    if (!isBBSTogetherOnServer || MinecraftClient.getInstance().player == null) {
                        return;
                    }
                    sendCrusher(MinecraftClient.getInstance().player, TogetherServerNetwork.SERVER_ASSET_REPORT, (BaseType)report, null);
                });
            }
            catch (Exception e) {
                e.printStackTrace();
            }
        }, "BBSTogether-AssetInventory");
        thread.setDaemon(true);
        thread.start();
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
            buf.writeInt(replayIndex);
        });
    }

    public static void sendFilmSaveSync(String filmId, Film film) {
        if (!isBBSTogetherOnServer || film == null || filmId == null) {
            return;
        }
        sendCrusher((PlayerEntity)MinecraftClient.getInstance().player, TogetherServerNetwork.SERVER_FILM_SAVE_SYNC, film.toData(), buf -> buf.writeString(filmId));
    }

    public static void sendMasterRecordingDone(String filmId, Film film) {
        if (!isBBSTogetherOnServer || film == null || filmId == null) {
            return;
        }
        sendCrusher((PlayerEntity)MinecraftClient.getInstance().player, TogetherServerNetwork.SERVER_MASTER_DONE, film.toData(), buf -> buf.writeString(filmId));
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

    private static void handleFilmSavedSyncPacket(MinecraftClient client, PacketByteBuf buf) {
        crusher.receive(buf, (bytes, packetByteBuf) -> {
            String filmId = packetByteBuf.readString();
            String saverName = packetByteBuf.readString();
            BaseType data = DataStorageUtils.readFromBytes((byte[])bytes);
            if (!data.isMap()) {
                return;
            }
            client.execute(() -> lambda$handleFilmSavedSyncPacket$42(filmId, data, saverName));
        });
    }


    private static void lambda$handleFilmSavedSyncPacket$42(String filmId, BaseType data, String saverName) {
        AssetSyncTracker.active("film:" + filmId, AssetSyncTracker.Dir.DOWNLOAD);
        AssetSyncTracker.done("film:" + filmId);
        UIDashboard dashboard = TogetherClientNetwork.getDashboardIfOpen();
        UIFilmPanel panel = dashboard == null ? null : (UIFilmPanel)dashboard.getPanel(UIFilmPanel.class);
        boolean filmIsOpen = panel != null && panel.getData() != null && ((Film)panel.getData()).getId().equals(filmId);
        if (filmIsOpen) {
            TogetherClientNetwork.applyFilmDataUpdate(panel, data.asMap());
            UIContext context = panel.getContext();
            if (context != null) {
                if (MinecraftClient.getInstance().isIntegratedServerRunning()) {
                    context.notifySuccess(UIKeys.PANELS_SAVED_NOTIFICATION.format(new Object[]{filmId}));
                } else {
                    String syncMsg = L10n.lang((String)"bbs_together.notification.synced").get() + " " + saverName + ": " + filmId;
                    context.notifyInfo(IKey.raw((String)syncMsg));
                }
            }
        } else {
            BBSMod.getFilms().save(filmId, data.asMap());
            /* B3: this film arrived on demand (join pull / save ping); refresh open lists. */
            UIDashboard pulled = TogetherClientNetwork.getDashboardIfOpen();
            UIFilmPanel pulledPanel = pulled == null ? null : (UIFilmPanel)pulled.getPanel(UIFilmPanel.class);
            if (pulledPanel != null) {
                pulledPanel.requestNames();
            }
        }
    }
    private static void applyFilmDataUpdate(UIFilmPanel panel, MapType data) {
        Replay reconfirmed;
        int idx03;
        Object object;
        int savedIdx = -1;
        Replay cur = panel.replayEditor.getReplay();
        if (cur != null && panel.getData() != null) {
            savedIdx = ((Film)panel.getData()).replays.getList().indexOf(cur);
        }
        ArrayList savedCameraClipSelection = new ArrayList(panel.cameraEditor.clips != null ? panel.cameraEditor.clips.getSelection() : Collections.emptyList());
        ArrayList savedActionClipSelection = new ArrayList(panel.actionEditor.clips != null ? panel.actionEditor.clips.getSelection() : Collections.emptyList());
        HashMap savedSelection = new HashMap();
        if (panel.replayEditor.keyframeEditor != null && panel.replayEditor.keyframeEditor.view != null && (object = panel.replayEditor.keyframeEditor.view.getGraph()) instanceof UIKeyframeDopeSheet) {
            UIKeyframeDopeSheet ds = (UIKeyframeDopeSheet)object;
            for (UIKeyframeSheet sheet : ds.getSheets()) {
                Collection indices = sheet.selection.getIndices();
                if (indices.isEmpty()) continue;
                savedSelection.put(sheet.id, new ArrayList(indices));
            }
        }
        ((Film)panel.getData()).fromData((BaseType)data);
        panel.fillData();
        panel.replayEditor.replaysList.replays.update();
        panel.getController().createEntities();
        List replays = ((Film)panel.getData()).replays.getList();
        if (savedIdx >= 0 && savedIdx < replays.size()) {
            panel.replayEditor.setReplay((Replay)replays.get(savedIdx));
        } else if (!replays.isEmpty()) {
            panel.replayEditor.setReplay((Replay)replays.get(0));
        }
        if (panel.cameraEditor.clips != null && !savedCameraClipSelection.isEmpty()) {
            panel.cameraEditor.clips.setSelection(savedCameraClipSelection);
            if (!savedCameraClipSelection.isEmpty()) {
                List cameraClips = panel.cameraEditor.clips.getClips() != null ? panel.cameraEditor.clips.getClips().get() : Collections.emptyList();
                int idx02 = (Integer)savedCameraClipSelection.get(0);
                if (idx02 >= 0 && idx02 < cameraClips.size()) {
                    panel.cameraEditor.clips.pickClip((Clip)cameraClips.get(idx02));
                }
            }
        }
        if (panel.actionEditor.clips != null && !savedActionClipSelection.isEmpty()) {
            panel.actionEditor.clips.setSelection(savedActionClipSelection);
            if (!savedActionClipSelection.isEmpty()) {
                List actionClips = panel.actionEditor.clips.getClips() != null ? panel.actionEditor.clips.getClips().get() : Collections.emptyList();
                idx03 = (Integer)savedActionClipSelection.get(0);
                if (idx03 >= 0 && idx03 < actionClips.size()) {
                    panel.actionEditor.clips.pickClip((Clip)actionClips.get(idx03));
                }
            }
        }
        if (!savedSelection.isEmpty() && panel.replayEditor.keyframeEditor != null && panel.replayEditor.keyframeEditor.view != null && panel.replayEditor.keyframeEditor.view.getGraph() instanceof UIKeyframeDopeSheet) {
            UIKeyframeDopeSheet ds2 = (UIKeyframeDopeSheet)panel.replayEditor.keyframeEditor.view.getGraph();
            for (UIKeyframeSheet sheet : ds2.getSheets()) {
                List indices = (List)savedSelection.get(sheet.id);
                if (indices == null) continue;
                Iterator iterator = indices.iterator();
                while (iterator.hasNext()) {
                    int i = (Integer)iterator.next();
                    if (sheet.selection == null) continue;
                    sheet.selection.add(i);
                }
            }
        }
        if (panel.replayEditor.keyframeEditor != null && panel.replayEditor.keyframeEditor.view != null) {
            panel.replayEditor.keyframeEditor.view.getGraph().pickSelected();
        }
        int reconfirmedIdx = (reconfirmed = panel.replayEditor.getReplay()) == null ? -1 : ((Film)panel.getData()).replays.getList().indexOf(reconfirmed);
        TogetherClientNetwork.sendReplaySelection(((Film)panel.getData()).getId(), reconfirmedIdx);
    }

    private static void handleFilmDeletedPacket(MinecraftClient client, String filmId) {
        client.execute(() -> {
            UIFilmPanel panel;
            try {
                BBSMod.getFilms().delete(filmId);
            }
            catch (Exception exception) {
                // empty catch block
            }
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
                // empty catch block
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
        /* B3: someone else saved/renamed/created a film — pull the changed ones on demand,
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
                 * our last pull — replace everything, don't merge increments on top */
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

    private static UIDashboard getDashboardIfOpen() {
        UIBaseMenu menu = UIScreen.getCurrentMenu();
        return menu instanceof UIDashboard ? (UIDashboard)menu : null;
    }

    private static UIDashboard getDashboardIfExists() {
        return BBSModClient.getDashboard();
    }

    private static void handleFilmDataSyncPacket(MinecraftClient client, PacketByteBuf buf) {
        String filmId = buf.readString();
        int pathSize = buf.readInt();
        ArrayList<String> path = new ArrayList<String>();
        for (int i = 0; i < pathSize; ++i) {
            path.add(buf.readString());
        }
        int dataLen = buf.readInt();
        byte[] bytes = new byte[dataLen];
        buf.readBytes(bytes);
        BaseType data = DataStorageUtils.readFromBytes((byte[])bytes);
        client.execute(() -> TogetherClientNetwork.lambda$handleFilmDataSyncPacket$47(filmId, path, data));
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
                 * type 的完整 clip MapType 才用工厂造一个，否则丢弃补丁，避免 MapType 回灌 CCE。 */
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

    private static void lambda$handleFilmDataSyncPacket$47(String filmId, List<String> path, BaseType data) {
        UIDashboard dashboard = TogetherClientNetwork.getDashboardIfOpen();
        UIFilmPanel panel = dashboard == null ? null : (UIFilmPanel)dashboard.getPanel(UIFilmPanel.class);
        if (panel == null || panel.getData() == null || !((Film)panel.getData()).getId().equals(filmId)) {
            return;
        }
        if (!TogetherClientNetwork.applyAtPath((BaseValueGroup)panel.getData(), path, 1, data)) {
            return;
        }
        boolean isKeyframeEdit = false;
        if (path.size() > 1 && "replays".equals(path.get(1))) {
            if (path.size() >= 5) {
                isKeyframeEdit = true;
            } else if (path.size() == 4) {
                isKeyframeEdit = "keyframes".equals(path.get(3));
            }
        }
        panel.fillData();
        if (!isKeyframeEdit) {
            panel.replayEditor.replaysList.replays.update();
            panel.getController().createEntities();
        }
        Replay currentReplay = panel.replayEditor.getReplay();
        if (path.size() >= 3 && "replays".equals(path.get(1)) && currentReplay != null) {
            int replayIdx = ((Film)panel.getData()).replays.getList().indexOf(currentReplay);
            if (replayIdx >= 0 && String.valueOf(replayIdx).equals(path.get(2))) {
                boolean needsChannelRebuild = path.size() == 3;
                if (!needsChannelRebuild && path.size() == 4) {
                    String key4 = (String)path.get(3);
                    needsChannelRebuild = "form".equals(key4) || "properties".equals(key4);
                }
                if (needsChannelRebuild) {
                    panel.replayEditor.updateChannelsList();
                }
            }
        }
        Replay openReplay = panel.replayEditor.getReplay();
        if (path.size() >= 3 && "replays".equals(path.get(1)) && openReplay != null) {
            panel.replayEditor.setReplay(openReplay);
        }
        if (isKeyframeEdit && panel.replayEditor.keyframeEditor != null) {
            Object kff = panel.replayEditor.keyframeEditor.editor;
            if (kff instanceof UIPoseKeyframeFactory) {
                UIPoseKeyframeFactory poseFactory = (UIPoseKeyframeFactory)kff;
                if (poseFactory.poseEditor != null) {
                    String currentBone = poseFactory.poseEditor.getGroup();
                    Keyframe kf = poseFactory.getKeyframe();
                    if (kf != null) {
                        poseFactory.poseEditor.setPose((Pose)kf.getValue(), poseFactory.poseEditor.getGroup());
                    }
                    if (currentBone != null && !currentBone.isEmpty()) {
                        poseFactory.poseEditor.selectBone(currentBone);
                    }
                }
            } else if (panel.replayEditor.keyframeEditor.view != null) {
                panel.replayEditor.keyframeEditor.view.getGraph().pickSelected();
            }
        }
        if (panel.getData() != null) {
            Replay r = panel.replayEditor.getReplay();
            int idx = r == null ? -1 : ((Film)panel.getData()).replays.getList().indexOf(r);
            TogetherClientNetwork.sendReplaySelection(((Film)panel.getData()).getId(), idx);
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