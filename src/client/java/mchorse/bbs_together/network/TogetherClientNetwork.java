package mchorse.bbs_together.network;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
import mchorse.bbs_mod.utils.CollectionUtils;
import mchorse.bbs_mod.utils.DataPath;
import mchorse.bbs_mod.utils.clips.Clip;
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
    private static final int MAX_SELECTION_PACKET = 512;
    /** When true, the next manifest response forces re-download of every models/ entry regardless of local hash. */
    private static volatile boolean forceDownloadModels = false;

    public static void setup() {
        ClientPlayNetworking.registerGlobalReceiver((Identifier)TogetherServerNetwork.CLIENT_PLAYER_COLORS, (client, handler, buf, resp) -> TogetherClientNetwork.handlePlayerColorsPacket(client, buf));
        ClientPlayNetworking.registerGlobalReceiver((Identifier)TogetherServerNetwork.CLIENT_CURSOR_POSITIONS, (client, handler, buf, resp) -> TogetherClientNetwork.handleCursorPositionsPacket(client, buf));
        ClientPlayNetworking.registerGlobalReceiver((Identifier)TogetherServerNetwork.CLIENT_FILM_SELECTIONS, (client, handler, buf, resp) -> TogetherClientNetwork.handleFilmSelectionsPacket(client, buf));
        ClientPlayNetworking.registerGlobalReceiver((Identifier)TogetherServerNetwork.CLIENT_REPLAY_SELECTIONS, (client, handler, buf, resp) -> TogetherClientNetwork.handleReplaySelectionsPacket(client, buf));
        ClientPlayNetworking.registerGlobalReceiver((Identifier)TogetherServerNetwork.CLIENT_REPLAY_FORM_LOCKS, (client, handler, buf, resp) -> TogetherClientNetwork.handleReplayFormLocksPacket(client, buf));
        ClientPlayNetworking.registerGlobalReceiver((Identifier)TogetherServerNetwork.CLIENT_REPLAY_SWAP, (client, handler, buf, resp) -> TogetherClientNetwork.handleReplaySwapPacket(client, buf));
        ClientPlayNetworking.registerGlobalReceiver((Identifier)TogetherServerNetwork.CLIENT_TOGGLE_RECORDING, (client, handler, buf, resp) -> TogetherClientNetwork.handleToggleRecordingPacket(client, buf));
        ClientPlayNetworking.registerGlobalReceiver((Identifier)TogetherServerNetwork.CLIENT_ASSET_MANIFEST, (client, handler, buf, resp) -> TogetherClientNetwork.handleAssetManifestPacket(client, buf));
        ClientPlayNetworking.registerGlobalReceiver((Identifier)TogetherServerNetwork.CLIENT_ASSET_FILE, (client, handler, buf, resp) -> TogetherClientNetwork.handleAssetFilePacket(client, buf));
        ClientPlayNetworking.registerGlobalReceiver((Identifier)TogetherServerNetwork.CLIENT_FILM_SCREENS, (client, handler, buf, resp) -> TogetherClientNetwork.handleFilmScreensPacket(client, buf));
        ClientPlayNetworking.registerGlobalReceiver((Identifier)TogetherServerNetwork.CLIENT_FILM_SAVED_SYNC, (client, handler, buf, resp) -> TogetherClientNetwork.handleFilmSavedSyncPacket(client, buf));
        ClientPlayNetworking.registerGlobalReceiver((Identifier)TogetherServerNetwork.CLIENT_FILM_DATA_SYNC, (client, handler, buf, resp) -> TogetherClientNetwork.handleFilmDataSyncPacket(client, buf));
        ClientPlayNetworking.registerGlobalReceiver((Identifier)TogetherServerNetwork.CLIENT_FILM_LIST_CHANGED, (client, handler, buf, resp) -> TogetherClientNetwork.handleFilmListChangedPacket(client));
        ClientPlayNetworking.registerGlobalReceiver((Identifier)TogetherServerNetwork.CLIENT_FILM_DELETED, (client, handler, buf, resp) -> TogetherClientNetwork.handleFilmDeletedPacket(client, buf.readString()));
        ClientPlayNetworking.registerGlobalReceiver((Identifier)TogetherServerNetwork.CLIENT_FILM_RENAMED, (client, handler, buf, resp) -> TogetherClientNetwork.handleFilmRenamedPacket(client, buf.readString(), buf.readString()));
        ClientPlayNetworking.registerGlobalReceiver((Identifier)TogetherServerNetwork.CLIENT_MERGE_REPLAYS, (client, handler, buf, resp) -> TogetherClientNetwork.handleMergeReplaysPacket(client, buf));
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
        damageControlActive = false;
        pendingSwapFilmId = null;
        pendingSwapFrom = -1;
        pendingSwapTo = -1;
        isBBSTogetherOnServer = false;
    }

    public static void onJoin() {
        isBBSTogetherOnServer = true;
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
                CursorPosition existing = playerCursors.get(entry.getKey());
                if (existing != null) {
                    CursorPosition n = (CursorPosition)entry.getValue();
                    existing.updateTarget(n.editorType, n.targetTick, n.targetRow, n.selectedIndices, n.playheadTick);
                    continue;
                }
                playerCursors.put((UUID)entry.getKey(), (CursorPosition)entry.getValue());
            }
            playerCursors.keySet().retainAll(incoming.keySet());
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
            boolean doDownload = (Boolean)TogetherSettings.assetSyncDownload.get();
            boolean bl = (Boolean)TogetherSettings.assetSyncUpload.get();
            boolean doUpload = true;
            if (!doDownload && !doUpload) {
                return;
            }
            BaseType data = DataStorageUtils.readFromBytes((byte[])bytes);
            if (!(data instanceof MapType)) {
                return;
            }
            MapType manifest = (MapType)data;
            Thread thread = new Thread(() -> {
                try {
                    File assetsFolder = BBSMod.getAssetsFolder();
                    Path assetsPath = assetsFolder.toPath();
                    HashMap<String, String> serverSha1Map = new HashMap<String, String>();
                    HashMap<String, Long> serverSizeMap = new HashMap<String, Long>();
                    for (String key : manifest.keys()) {
                        String raw = manifest.get(key).asString();
                        int colon = raw.indexOf(':');
                        if (colon > 0) {
                            serverSha1Map.put(key, raw.substring(0, colon));
                            try { serverSizeMap.put(key, Long.parseLong(raw.substring(colon + 1))); }
                            catch (NumberFormatException ignored) {}
                        } else {
                            serverSha1Map.put(key, raw);
                        }
                    }
                    long maxBytes = TogetherClientNetwork.clientMaxAssetBytes();
                    TogetherClientNetwork.tooLargeModels.clear();
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
                    boolean forceDownload = forceDownloadModels;
                    forceDownloadModels = false;
                    if (doDownload) {
                        ArrayList<String> needed = new ArrayList<String>();
                        for (Map.Entry entry : serverSha1Map.entrySet()) {
                            String relPath = (String) entry.getKey();
                            if (forceDownload && !relPath.startsWith("models/")) continue;
                            /* Skip any single file larger than the per-client size limit, regardless of
                             * which directory it lives in (models/ aggregation already handles model
                             * folders, but videos/audio/etc. outside models/ need this guard). */
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
                            if (!forceDownload && localFile.exists() && localFile.isFile()) {
                                needsDownload = !((String)entry.getValue()).equalsIgnoreCase(TogetherClientNetwork.sha1OfLocalFile(localFile.toPath()));
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
                                String localSha1 = TogetherClientNetwork.sha1OfLocalFile(localPath);
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
                                for (String relPath : toUpload) {
                                    if (!isBBSTogetherOnServer) break;
                                    try {
                                        File f = new File(assetsFolder, relPath.replace("/", File.separator));
                                        if (!f.exists() || !f.isFile()) continue;
                                        if (f.length() > upMaxBytes) continue;
                                        byte[] fileData = Files.readAllBytes(f.toPath());
                                        AssetSyncTracker.pending(relPath, AssetSyncTracker.Dir.UPLOAD);
                                        MinecraftClient.getInstance().execute(() -> TogetherClientNetwork.sendAssetUpload(relPath, fileData));
                                        Thread.sleep(200L);
                                    } catch (Exception ignored) {}
                                }
                            }, "BBSTogether-AssetUpload").start();
                        }
                    }
                    if (!hadTasks) {
                        AssetSyncTracker.abort();
                    }
                }
                catch (Exception e) {
                    e.printStackTrace();
                }
            }, "BBSTogether-AssetManifestCheck");
            thread.setDaemon(true);
            thread.start();
        });
    }

    private static void handleAssetFilePacket(MinecraftClient client, PacketByteBuf buf) {
        crusher.receive(buf, (bytes, packetByteBuf) -> {
            boolean isPush;
            String relativePath = packetByteBuf.readString();
            boolean bl = isPush = packetByteBuf.readableBytes() > 0 && packetByteBuf.readBoolean();
            if (isPush && !((Boolean)TogetherSettings.assetSyncReceive.get()).booleanValue()) {
                return;
            }
            if (!isPush && !((Boolean)TogetherSettings.assetSyncDownload.get()).booleanValue()) {
                return;
            }
            if (relativePath.contains("..") || relativePath.startsWith("/")) {
                return;
            }
            client.execute(() -> {
                Thread thread = new Thread(() -> {
                    block3: {
                        try {
                            AssetSyncTracker.active(relativePath, isPush ? AssetSyncTracker.Dir.PUSH : AssetSyncTracker.Dir.DOWNLOAD);
                            if (bytes.length > TogetherClientNetwork.clientMaxAssetBytes()) {
                                String modelId = together$extractModelId(relativePath);
                                if (modelId != null) TogetherClientNetwork.tooLargeModels.add(modelId);
                                AssetSyncTracker.done(relativePath);
                                if (!isPush && ++assetDownloadDone >= assetDownloadTotal && assetDownloadTotal > 0) {
                                    assetDownloadFinishedAt = System.currentTimeMillis();
                                }
                                break block3;
                            }
                            File dest = new File(BBSMod.getAssetsFolder(), relativePath.replace("/", File.separator));
                            dest.getParentFile().mkdirs();
                            Files.write(dest.toPath(), bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                            System.out.println("[BBS Assets] " + (isPush ? "Received push" : "Downloaded") + ": " + relativePath);
                            AssetSyncTracker.done(relativePath);
                            if (!isPush && ++assetDownloadDone >= assetDownloadTotal && assetDownloadTotal > 0) {
                                assetDownloadFinishedAt = System.currentTimeMillis();
                            }
                        }
                        catch (Exception e) {
                            e.printStackTrace();
                            if (isPush) break block3;
                            ++assetDownloadErrors;
                            AssetSyncTracker.error(relativePath);
                        }
                    }
                }, "BBSTogether-AssetWrite");
                thread.setDaemon(true);
                thread.start();
            });
        });
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
        if (relativePath == null || !relativePath.startsWith("models/")) {
            return null;
        }
        String after = relativePath.substring("models/".length());
        int slash = after.lastIndexOf('/');
        return slash > 0 ? after.substring(0, slash) : null;
    }

    public static void sendAssetUpload(String relativePath, byte[] fileBytes) {
        if (!isBBSTogetherOnServer) {
            return;
        }
        try {
            AssetSyncTracker.active(relativePath, AssetSyncTracker.Dir.UPLOAD);
            crusher.send((PlayerEntity)MinecraftClient.getInstance().player, TogetherServerNetwork.SERVER_ASSET_UPLOAD, fileBytes, buf -> buf.writeString(relativePath));
            AssetSyncTracker.done(relativePath);
            if (++assetUploadDone >= assetUploadTotal && assetUploadTotal > 0) {
                assetUploadFinishedAt = System.currentTimeMillis();
            }
        }
        catch (Exception e) {
            ++assetUploadErrors;
            AssetSyncTracker.error(relativePath);
        }
    }

    /** Ask the server to resend the asset manifest; the response will force re-download of every models/ entry. */
    public static void requestModelResync() {
        if (!isBBSTogetherOnServer) return;
        forceDownloadModels = true;
        AssetSyncTracker.beginScan();
        PacketByteBuf buf = PacketByteBufs.create();
        ClientPlayNetworking.send(TogetherServerNetwork.SERVER_ASSET_RESYNC, buf);
    }

    /** Walk the local models/ folder and upload every file, regardless of server-side hash. */
    public static void forceUploadModels() {
        if (!isBBSTogetherOnServer) return;
        File assetsFolder = BBSMod.getAssetsFolder();
        if (assetsFolder == null || !assetsFolder.exists()) return;
        File modelsFolder = new File(assetsFolder, "models");
        if (!modelsFolder.exists() || !modelsFolder.isDirectory()) return;
        AssetSyncTracker.beginScan();
        long maxBytes = clientMaxAssetBytes();
        new Thread(() -> {
            try {
                ArrayList<Path> paths = new ArrayList<>();
                Files.walk(modelsFolder.toPath()).filter(Files::isRegularFile).forEach(paths::add);
                int count = paths.size();
                MinecraftClient.getInstance().execute(() -> {
                    assetUploadTotal = count;
                    assetUploadDone = 0;
                    assetUploadErrors = 0;
                    assetUploadFinishedAt = 0L;
                });
                for (Path p : paths) {
                    if (!isBBSTogetherOnServer) break;
                    try {
                        if (Files.size(p) > maxBytes) continue;
                        byte[] data = Files.readAllBytes(p);
                        String rel = "models/" + modelsFolder.toPath().relativize(p).toString().replace(File.separator, "/");
                        AssetSyncTracker.pending(rel, AssetSyncTracker.Dir.UPLOAD);
                        final byte[] fdata = data;
                        MinecraftClient.getInstance().execute(() -> sendAssetUpload(rel, fdata));
                        Thread.sleep(200L);
                    } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, "BBSTogether-ForceUploadModels").start();
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
        crusher.send((PlayerEntity)MinecraftClient.getInstance().player, TogetherServerNetwork.SERVER_RECORDING_FINISHED, replay.toData(), buf -> {
            buf.writeString(filmId);
            buf.writeInt(replayIndex);
        });
    }

    public static void sendFilmSaveSync(String filmId, Film film) {
        if (!isBBSTogetherOnServer || film == null || filmId == null) {
            return;
        }
        crusher.send((PlayerEntity)MinecraftClient.getInstance().player, TogetherServerNetwork.SERVER_FILM_SAVE_SYNC, film.toData(), buf -> buf.writeString(filmId));
    }

    public static void sendMasterRecordingDone(String filmId, Film film) {
        if (!isBBSTogetherOnServer || film == null || filmId == null) {
            return;
        }
        crusher.send((PlayerEntity)MinecraftClient.getInstance().player, TogetherServerNetwork.SERVER_MASTER_DONE, film.toData(), buf -> buf.writeString(filmId));
    }

    public static void sendSyncData(String filmId, BaseValue value) {
        if (!isBBSTogetherOnServer) {
            return;
        }
        DataPath path = value.getPath();
        crusher.send((PlayerEntity)MinecraftClient.getInstance().player, TogetherServerNetwork.SERVER_FILM_DATA_SYNC, value.toData(), buf -> {
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
        crusher.send((PlayerEntity)MinecraftClient.getInstance().player, TogetherServerNetwork.SERVER_FILM_DATA_SYNC, value.toData(), buf -> {
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
    }

    public static void sendFilmListChanged() {
        if (!isBBSTogetherOnServer) {
            return;
        }
        ClientPlayNetworking.send((Identifier)TogetherServerNetwork.SERVER_FILM_LIST_CHANGED, (PacketByteBuf)PacketByteBufs.create());
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