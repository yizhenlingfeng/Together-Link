package mchorse.bbs_together.mixin;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.Films;
import mchorse.bbs_mod.film.Recorder;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.settings.values.base.BaseValueGroup;
import mchorse.bbs_mod.ui.ContentType;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.film.UIClips;
import mchorse.bbs_mod.ui.film.UIClipsPanel;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeEditor;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.factories.UIKeyframeFactory;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.factories.UIPoseKeyframeFactory;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.graphs.IUIKeyframeGraph;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.graphs.UIKeyframeDopeSheet;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.clips.Clips;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.pose.Pose;
import mchorse.bbs_together.BBSTogetherFix14;
import mchorse.bbs_together.BBSTogetherFix22;
import mchorse.bbs_together.BBSTogetherFix28Client;
import mchorse.bbs_together.BBSTogetherFix33Client;
import mchorse.bbs_together.BBSTogetherFix45;
import mchorse.bbs_together.network.TogetherClientNetwork;
import mchorse.bbs_together.network.TogetherServerNetwork;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value={TogetherClientNetwork.class}, remap=false)
public abstract class TogetherClientNetworkMixin {
    private static final int TOGETHER_ASSET_REQUEST_MAX_BYTES = 28000;
    @Shadow
    public static ConcurrentHashMap<String, Map<Integer, List<UUID>>> replaySelections;
    @Shadow
    public static ConcurrentHashMap<UUID, String> filmSelections;
    @Shadow
    public static ConcurrentHashMap<UUID, Integer> playerColors;
    @Shadow
    public static int lastLocalReplayIdx;
    @Shadow
    private static boolean isBBSTogetherOnServer;

    @Shadow
    private static UIDashboard getDashboardIfOpen() {
        throw new AssertionError();
    }

    @Shadow
    private static UIDashboard getDashboardIfExists() {
        throw new AssertionError();
    }

    @Shadow
    private static boolean applyAtPath(BaseValueGroup baseValueGroup, List<String> list, int n, BaseType baseType) {
        throw new AssertionError();
    }

    @Overwrite
    private static void applyFilmDataUpdate(UIFilmPanel uIFilmPanel, MapType mapType) {
        TogetherClientNetworkMixin.together_applyFullFilmDataUpdate(uIFilmPanel, mapType, true);
    }

    @Overwrite
    public static void sendReplaySelection(String string, int n) {
        if (!isBBSTogetherOnServer || BBSTogetherFix22.isApplyingRemoteSync()) {
            return;
        }
        if (n >= 0) {
            lastLocalReplayIdx = n;
        }
        TogetherClientNetworkMixin.together_setLocalReplaySelection(string, n);
        PacketByteBuf class_25402 = PacketByteBufs.create();
        class_25402.writeString(string);
        class_25402.writeInt(n);
        ClientPlayNetworking.send((Identifier)TogetherServerNetwork.SERVER_REPLAY_SELECTION, (PacketByteBuf)class_25402);
    }

    @Unique
    private static void together_applyPendingFullSync(UIFilmPanel uIFilmPanel, MapType mapType) {
        TogetherClientNetworkMixin.together_applyFullFilmDataUpdate(uIFilmPanel, mapType, false);
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private static void together_applyFullFilmDataUpdate(UIFilmPanel uIFilmPanel, MapType mapType, boolean bl) {
        List list;
        List<Integer> list2 = TogetherClientNetworkMixin.together_copyClipSelection(uIFilmPanel.cameraEditor);
        List<Integer> list3 = TogetherClientNetworkMixin.together_copyClipSelection(uIFilmPanel.actionEditor);
        Map<String, List<Integer>> map = TogetherClientNetworkMixin.together_copyKeyframeSelection(uIFilmPanel);
        Film film = (Film)uIFilmPanel.getData();
        int n = TogetherClientNetworkMixin.together_getCurrentReplayIndex(uIFilmPanel, film);
        if (TogetherClientNetworkMixin.together_wouldClearLocalCamera(film, mapType)) {
            return;
        }
        Film film2 = new Film();
        film2.fromData((BaseType)mapType);
        if (TogetherClientNetworkMixin.together_clipCount(film.camera) > 0 && TogetherClientNetworkMixin.together_clipCount(film2.camera) == 0) {
            return;
        }
        int n2 = TogetherClientNetworkMixin.together_remapReplayIndex(film.replays.getList(), film2.replays.getList(), n);
        TogetherClientNetworkMixin.together_remapClientReplaySelections(film.getId(), film, film2);
        BBSTogetherFix22.beginRemoteSyncApply();
        try {
            film.fromData(film2.toData());
            if (!BBSTogetherFix14.hasCameraKeyframeEditor(uIFilmPanel)) {
                uIFilmPanel.fillData();
            }
            if (uIFilmPanel.replayEditor.replaysList != null && uIFilmPanel.replayEditor.replaysList.replays != null) {
                uIFilmPanel.replayEditor.replaysList.replays.refreshReplayList();
                uIFilmPanel.replayEditor.replaysList.replays.update();
            }
            uIFilmPanel.getController().createEntities();
            list = ((Film)uIFilmPanel.getData()).replays.getList();
            TogetherClientNetworkMixin.together_restoreReplaySelection(uIFilmPanel, list, n2);
        }
        finally {
            BBSTogetherFix22.endRemoteSyncApply();
        }
        TogetherClientNetworkMixin.together_restoreClipSelection(uIFilmPanel.cameraEditor, list2);
        TogetherClientNetworkMixin.together_restoreClipSelection(uIFilmPanel.actionEditor, list3);
        TogetherClientNetworkMixin.together_restoreKeyframeSelection(uIFilmPanel, map);
        UIKeyframeEditor keyframeEditor = uIFilmPanel.replayEditor.keyframeEditor;
        if (keyframeEditor != null && keyframeEditor.view != null) {
            keyframeEditor.view.getGraph().pickSelected();
        }
        int n3 = TogetherClientNetworkMixin.together_getCurrentReplayIndex(uIFilmPanel, (Film)uIFilmPanel.getData());
        TogetherClientNetworkMixin.together_setLocalReplaySelection(((Film)uIFilmPanel.getData()).getId(), n3);
        TogetherClientNetwork.sendReplaySelection(((Film)uIFilmPanel.getData()).getId(), n3);
        BBSTogetherFix33Client.reannounceReplaySelectionAfterFullSync(uIFilmPanel);
    }



    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    @Overwrite
    private static void lambda$handleFilmDataSyncPacket$47(String string, List<String> list, BaseType baseType) {
        boolean bl;
        UIFilmPanel uIFilmPanel;
        UIDashboard uIDashboard = TogetherClientNetworkMixin.getDashboardIfOpen();
        UIFilmPanel uIFilmPanel2 = uIFilmPanel = uIDashboard == null ? null : (UIFilmPanel)uIDashboard.getPanel(UIFilmPanel.class);
        if (uIFilmPanel == null || uIFilmPanel.getData() == null || !((Film)uIFilmPanel.getData()).getId().equals(string)) {
            return;
        }
        if (TogetherClientNetworkMixin.together_wouldClearLocalCamera((Film)uIFilmPanel.getData(), list, baseType)) {
            return;
        }
        Film film = (Film)uIFilmPanel.getData();
        int n = TogetherClientNetworkMixin.together_getCurrentReplayIndex(uIFilmPanel, film);
        ArrayList<Replay> arrayList = new ArrayList<Replay>(film.replays.getList());
        boolean bl2 = TogetherClientNetworkMixin.together_isReplayStructurePath(list);
        int n2 = TogetherClientNetworkMixin.together_applyPathOffset(list);
        BBSTogetherFix22.beginRemoteSyncApply();
        try {
            bl = TogetherClientNetworkMixin.applyAtPath((BaseValueGroup)uIFilmPanel.getData(), list, n2, baseType);
        }
        finally {
            BBSTogetherFix22.endRemoteSyncApply();
        }
        if (!bl) {
            return;
        }
        if (bl2) {
            TogetherClientNetworkMixin.together_restoreReplayAfterStructureSync(uIFilmPanel, arrayList, n);
            return;
        }
        boolean bl3 = TogetherClientNetworkMixin.together_isKeyframeOnlySyncPath(list);
        if (bl3) {
            TogetherClientNetworkMixin.together_refreshEmbeddedClipKeyframeEditor(uIFilmPanel, list);
            return;
        }
        uIFilmPanel.fillData();
        TogetherClientNetworkMixin.together_updateReplayList(uIFilmPanel);
        uIFilmPanel.getController().createEntities();
        Replay replay = uIFilmPanel.replayEditor.getReplay();
        if (TogetherClientNetworkMixin.together_isCurrentReplayPath(uIFilmPanel, list, replay) && TogetherClientNetworkMixin.together_shouldUpdateChannels(list)) {
            uIFilmPanel.replayEditor.updateChannelsList();
        }
        if (TogetherClientNetworkMixin.together_isReplayPath(list) && replay != null) {
            uIFilmPanel.replayEditor.setReplay(replay);
        }
        Replay replay2 = uIFilmPanel.replayEditor.getReplay();
        int n3 = BBSTogetherFix45.identityReplayIndex(((Film)uIFilmPanel.getData()).replays.getList(), replay2);
        TogetherClientNetwork.sendReplaySelection(((Film)uIFilmPanel.getData()).getId(), n3);
    }

    @Overwrite
    private static void lambda$handleFilmSavedSyncPacket$42(String string, BaseType baseType, String string2) {
        UIFilmPanel uIFilmPanel;
        boolean bl;
        UIDashboard uIDashboard = TogetherClientNetworkMixin.getDashboardIfOpen();
        UIFilmPanel uIFilmPanel2 = uIDashboard == null ? null : (UIFilmPanel)uIDashboard.getPanel(UIFilmPanel.class);
        boolean bl2 = bl = uIFilmPanel2 != null && uIFilmPanel2.getData() != null && ((Film)uIFilmPanel2.getData()).getId().equals(string) && uIFilmPanel2.isVisible();
        if (bl) {
            TogetherClientNetworkMixin.applyFilmDataUpdate(uIFilmPanel2, baseType.asMap());
            TogetherClientNetworkMixin.together_notifyFilmSyncApplied(uIFilmPanel2, string, string2);
            return;
        }
        UIDashboard uIDashboard2 = BBSModClient.getDashboardIfCreated();
        UIFilmPanel uIFilmPanel3 = uIFilmPanel = uIDashboard2 == null ? null : (UIFilmPanel)uIDashboard2.getPanel(UIFilmPanel.class);
        if (uIFilmPanel != null && uIFilmPanel.getData() != null && ((Film)uIFilmPanel.getData()).getId().equals(string)) {
            TogetherClientNetworkMixin.together_applyHiddenFilmDataUpdate(uIFilmPanel, baseType.asMap());
        }
        TogetherClientNetworkMixin.together_saveReceivedFilm(string, baseType.asMap());
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    @Overwrite
    private static void lambda$handleMergeReplaysPacket$25(String string, MapType mapType) {
        UIDashboard uIDashboard = TogetherClientNetworkMixin.getDashboardIfOpen();
        UIFilmPanel uIFilmPanel = uIDashboard == null ? null : (UIFilmPanel)uIDashboard.getPanel(UIFilmPanel.class);
        Film film = TogetherClientNetworkMixin.together_getOpenOrStoredFilm(uIFilmPanel, string);
        if (film == null) {
            return;
        }
        List list = film.replays.getList();
        for (String string2 : mapType.keys()) {
            try {
                BaseType baseType;
                int n = Integer.parseInt(string2);
                if (n < 0 || n >= list.size() || (baseType = mapType.get(string2)) == null || !baseType.isMap()) continue;
                BBSTogetherFix22.beginRemoteSyncApply();
                try {
                    ((Replay)list.get(n)).fromData((BaseType)baseType.asMap());
                }
                finally {
                    BBSTogetherFix22.endRemoteSyncApply();
                }
            }
            catch (NumberFormatException numberFormatException) {}
        }
        TogetherClientNetwork.sendMasterRecordingDone(string, film);
        if (uIFilmPanel != null && uIFilmPanel.getData() != null && ((Film)uIFilmPanel.getData()).getId().equals(string)) {
            List list2;
            uIFilmPanel.fillData();
            TogetherClientNetworkMixin.together_updateReplayList(uIFilmPanel);
            uIFilmPanel.getController().createEntities();
            Replay replay = uIFilmPanel.replayEditor.getReplay();
            int n = BBSTogetherFix45.identityReplayIndex(((Film)uIFilmPanel.getData()).replays.getList(), replay);
            if (n < 0 && !(list2 = ((Film)uIFilmPanel.getData()).replays.getList()).isEmpty()) {
                uIFilmPanel.replayEditor.setReplay((Replay)list2.get(0));
                n = 0;
            }
            TogetherClientNetwork.sendReplaySelection(string, n);
        }
    }

    @Overwrite
    private static void lambda$handleToggleRecordingPacket$24(boolean bl, int n) {
        String string;
        Films films = BBSModClient.getFilms();
        Recorder recorder = films.getRecorder();
        UIDashboard uIDashboard = TogetherClientNetworkMixin.getDashboardIfExists();
        UIFilmPanel uIFilmPanel = uIDashboard == null ? null : (UIFilmPanel)uIDashboard.getPanel(UIFilmPanel.class);
        String string2 = string = uIFilmPanel != null && uIFilmPanel.getData() != null ? ((Film)uIFilmPanel.getData()).getId() : TogetherClientNetwork.lastLocalFilmId;
        if (string == null || string.isEmpty()) {
            return;
        }
        if (!bl) {
            if (recorder == null || recorder.hasNotStarted()) {
                return;
            }
            recorder = BBSModClient.getFilms().stopRecording();
            if (recorder == null || recorder.hasNotStarted()) {
                return;
            }
            int n2 = recorder.exception;
            if (uIFilmPanel == null || uIFilmPanel.getData() == null) {
                return;
            }
            Film film = (Film)uIFilmPanel.getData();
            uIFilmPanel.applyRecordedKeyframes(recorder, film);
            if (uIFilmPanel.getData() != null && ((Film)uIFilmPanel.getData()).getId().equals(string)) {
                TogetherClientNetworkMixin.together_updateReplayList(uIFilmPanel);
                uIFilmPanel.getController().createEntities();
            }
            return;
        }
        if (recorder != null) {
            return;
        }
        int n3 = n >= 0 ? n : TogetherClientNetwork.lastLocalReplayIdx;
        Film film = TogetherClientNetworkMixin.together_getOpenOrStoredFilm(uIFilmPanel, string);
        if (film == null || n3 < 0 || n3 >= film.replays.getList().size()) {
            return;
        }
        BBSTogetherFix28Client.sendRecordingBaselineSnapshot(string, film);
        films.startRecording(film, n3, 0);
        TogetherClientNetwork.recordingSessionActive = true;
    }

    @Unique
    private static void together_setLocalReplaySelection(String string2, int n2) {
        UUID uUID = TogetherClientNetworkMixin.together_localPlayerUuid();
        if (uUID == null) {
            return;
        }
        if (string2 == null || string2.isEmpty()) {
            for (Map<Integer, List<UUID>> map : replaySelections.values()) {
                TogetherClientNetworkMixin.together_removePlayerFromReplaySelections(map, uUID);
            }
            return;
        }
        Map<Integer, List<UUID>> map = replaySelections.computeIfAbsent(string2, string -> new HashMap<Integer, List<UUID>>());
        TogetherClientNetworkMixin.together_removePlayerFromReplaySelections(map, uUID);
        if (n2 >= 0) {
            List<UUID> list = map.computeIfAbsent(n2, n -> new ArrayList<UUID>());
            if (!list.contains(uUID)) {
                list.add(uUID);
            }
            filmSelections.put(uUID, string2);
        }
    }

    @Unique
    private static void together_removePlayerFromReplaySelections(Map<Integer, List<UUID>> map, UUID uUID) {
        if (map == null || uUID == null) {
            return;
        }
        for (List<UUID> list : map.values()) {
            list.removeIf(uUID::equals);
        }
        map.entrySet().removeIf(entry -> ((List)entry.getValue()).isEmpty());
    }

    @Unique
    private static UUID together_localPlayerUuid() {
        return MinecraftClient.getInstance().player == null ? null : MinecraftClient.getInstance().player.getUuid();
    }

    private static boolean together_isReplayPath(List<String> list) {
        return TogetherClientNetworkMixin.together_replayPathIndex(list) >= 0 && list.size() > TogetherClientNetworkMixin.together_replayPathIndex(list) + 1;
    }

    @Unique
    private static boolean together_isReplayStructurePath(List<String> list) {
        int n = TogetherClientNetworkMixin.together_replayPathIndex(list);
        return n >= 0 && list.size() <= n + 2;
    }

    @Unique
    private static int together_applyPathOffset(List<String> list) {
        return list != null && !list.isEmpty() && "replays".equals(list.get(0)) ? 0 : 1;
    }

    @Unique
    private static int together_replayPathIndex(List<String> list) {
        if (list == null) {
            return -1;
        }
        for (int i = 0; i < list.size(); ++i) {
            if (!"replays".equals(list.get(i))) continue;
            return i;
        }
        return -1;
    }

    @Unique
    private static void together_restoreReplayAfterStructureSync(UIFilmPanel uIFilmPanel, List<Replay> list, int n) {
        if (uIFilmPanel == null || uIFilmPanel.getData() == null || uIFilmPanel.replayEditor == null) {
            return;
        }
        Film film = (Film)uIFilmPanel.getData();
        List list2 = film.replays.getList();
        int n2 = TogetherClientNetworkMixin.together_remapReplayIndex(list, list2, n);
        TogetherClientNetworkMixin.together_remapClientReplaySelections(film.getId(), list, list2);
        if (n2 < 0 && !list2.isEmpty()) {
            n2 = Math.min(Math.max(n, 0), list2.size() - 1);
        }
        uIFilmPanel.fillData();
        if (uIFilmPanel.replayEditor.replaysList != null && uIFilmPanel.replayEditor.replaysList.replays != null) {
            uIFilmPanel.replayEditor.replaysList.replays.refreshReplayList();
            uIFilmPanel.replayEditor.replaysList.replays.update();
        }
        uIFilmPanel.getController().createEntities();
        if (list2.isEmpty()) {
            uIFilmPanel.replayEditor.setReplay(null);
            TogetherClientNetworkMixin.together_setLocalReplaySelection(film.getId(), -1);
            TogetherClientNetwork.sendReplaySelection(film.getId(), -1);
            return;
        }
        Replay replay = (Replay)list2.get(n2);
        uIFilmPanel.replayEditor.setReplay(replay);
        if (uIFilmPanel.replayEditor.replaysList != null && uIFilmPanel.replayEditor.replaysList.replays != null) {
            uIFilmPanel.replayEditor.replaysList.replays.scrollToReplay(replay);
        }
        TogetherClientNetworkMixin.together_setLocalReplaySelection(film.getId(), n2);
        TogetherClientNetwork.sendReplaySelection(film.getId(), n2);
        BBSTogetherFix33Client.reannounceReplaySelectionAfterFullSync(uIFilmPanel);
    }

    private static Film together_getOpenOrStoredFilm(UIFilmPanel uIFilmPanel, String string) {
        if (uIFilmPanel != null && uIFilmPanel.getData() != null && ((Film)uIFilmPanel.getData()).getId().equals(string)) {
            return (Film)uIFilmPanel.getData();
        }
        return (Film)BBSMod.getFilms().load(string);
    }

    private static void together_saveReceivedFilm(String string, MapType mapType) {
        BBSMod.getFilms().save(string, mapType);
        try {
            ContentType.FILMS.getRepository().save(string, mapType);
        }
        catch (Exception exception) {
            exception.printStackTrace();
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    @Unique
    private static void together_applyHiddenFilmDataUpdate(UIFilmPanel uIFilmPanel, MapType mapType) {
        Film film = (Film)uIFilmPanel.getData();
        int n = TogetherClientNetworkMixin.together_getCurrentReplayIndex(uIFilmPanel, film);
        Film film2 = new Film();
        film2.fromData((BaseType)mapType);
        int n2 = TogetherClientNetworkMixin.together_remapReplayIndex(film.replays.getList(), film2.replays.getList(), n);
        BBSTogetherFix22.beginRemoteSyncApply();
        try {
            film.fromData(film2.toData());
            if (uIFilmPanel.replayEditor != null && n2 >= 0 && n2 < film.replays.getList().size()) {
                uIFilmPanel.replayEditor.setReplay((Replay)film.replays.getList().get(n2));
            }
            uIFilmPanel.getController().createEntities();
        }
        finally {
            BBSTogetherFix22.endRemoteSyncApply();
        }
    }

    private static void together_notifyFilmSyncApplied(UIFilmPanel uIFilmPanel, String string, String string2) {
        UIContext uIContext = uIFilmPanel.getContext();
        if (uIContext == null) {
            return;
        }
        uIContext.notifySuccess(UIKeys.PANELS_SAVED_NOTIFICATION.format(new Object[]{string}));
    }

    private static boolean together_isKeyframeOnlySyncPath(List<String> list) {
        if (list.size() <= 1) {
            return false;
        }
        String string = list.get(1);
        if ("replays".equals(string)) {
            if (list.size() >= 5) {
                return true;
            }
            return list.size() == 4 && "keyframes".equals(list.get(3));
        }
        if ("camera".equals(string) || "action".equals(string) || "actions".equals(string)) {
            return TogetherClientNetworkMixin.together_isClipKeyframeOnlyPath(list);
        }
        return false;
    }

    private static boolean together_isCurrentReplayPath(UIFilmPanel uIFilmPanel, List<String> list, Replay replay) {
        if (!TogetherClientNetworkMixin.together_isReplayPath(list) || replay == null) {
            return false;
        }
        int n = BBSTogetherFix45.identityReplayIndex(((Film)uIFilmPanel.getData()).replays.getList(), replay);
        int n2 = TogetherClientNetworkMixin.together_replayPathIndex(list);
        return n >= 0 && n2 >= 0 && list.size() > n2 + 1 && String.valueOf(n).equals(list.get(n2 + 1));
    }

    private static boolean together_shouldUpdateChannels(List<String> list) {
        if (list.size() == 3) {
            return true;
        }
        if (list.size() != 4) {
            return false;
        }
        String string = list.get(3);
        return "form".equals(string) || "properties".equals(string);
    }

    private static void together_updateReplayList(UIFilmPanel uIFilmPanel) {
        if (uIFilmPanel.replayEditor.replaysList != null && uIFilmPanel.replayEditor.replaysList.replays != null) {
            uIFilmPanel.replayEditor.replaysList.replays.update();
        }
    }

    @Unique
    private static int together_getCurrentReplayIndex(UIFilmPanel uIFilmPanel, Film film) {
        if (uIFilmPanel == null || uIFilmPanel.replayEditor == null || film == null) {
            return -1;
        }
        Replay replay = uIFilmPanel.replayEditor.getReplay();
        int n = BBSTogetherFix45.identityReplayIndex(film.replays.getList(), replay);
        if (n >= 0) {
            return n;
        }
        if (uIFilmPanel.replayEditor.replaysList != null && uIFilmPanel.replayEditor.replaysList.replays != null) {
            Replay current = uIFilmPanel.replayEditor.replaysList.replays.getSelectedReplayFirst();
            return current == null ? -1 : ((Film)uIFilmPanel.getData()).replays.getList().indexOf(current);
        }
        return -1;
    }

    @Unique
    private static void together_restoreReplaySelection(UIFilmPanel uIFilmPanel, List<Replay> list, int n) {
        if (uIFilmPanel == null || uIFilmPanel.replayEditor == null || list == null || list.isEmpty()) {
            return;
        }
        int n2 = n >= 0 && n < list.size() ? n : 0;
        Replay replay = list.get(n2);
        uIFilmPanel.replayEditor.setReplay(replay);
        if (uIFilmPanel.replayEditor.replaysList != null && uIFilmPanel.replayEditor.replaysList.replays != null) {
            uIFilmPanel.replayEditor.replaysList.replays.scrollToReplay(replay);
        }
    }

    private static boolean together_wouldClearLocalCamera(Film film, MapType mapType) {
        BaseType baseType = mapType.get("camera");
        if (baseType == null || !baseType.isList()) {
            return false;
        }
        return TogetherClientNetworkMixin.together_wouldClearLocalCamera(film, baseType);
    }

    private static boolean together_wouldClearLocalCamera(Film film, List<String> list, BaseType baseType) {
        if (list.size() != 2 || !"camera".equals(list.get(1))) {
            return false;
        }
        return TogetherClientNetworkMixin.together_wouldClearLocalCamera(film, baseType);
    }

    private static boolean together_wouldClearLocalCamera(Film film, BaseType baseType) {
        return film != null && baseType != null && baseType.isList() && TogetherClientNetworkMixin.together_clipCount(film.camera) > 0 && baseType.asList().size() == 0;
    }

    private static int together_clipCount(Clips clips) {
        return clips == null || clips.get() == null ? 0 : clips.get().size();
    }

    private static void together_refreshKeyframeEditor(UIFilmPanel uIFilmPanel) {
        UIKeyframeEditor uIKeyframeEditor = uIFilmPanel.replayEditor.keyframeEditor;
        if (uIKeyframeEditor == null) {
            return;
        }
        UIKeyframeFactory uIKeyframeFactory = uIKeyframeEditor.editor;
        if (uIKeyframeFactory instanceof UIPoseKeyframeFactory) {
            UIPoseKeyframeFactory uIPoseKeyframeFactory = (UIPoseKeyframeFactory)uIKeyframeFactory;
            if (uIPoseKeyframeFactory.poseEditor != null) {
                Object object;
                String string = uIPoseKeyframeFactory.poseEditor.getGroup();
                Keyframe keyframe = uIPoseKeyframeFactory.getKeyframe();
                if (keyframe != null && (object = keyframe.getValue()) instanceof Pose) {
                    Pose pose = (Pose)object;
                    uIPoseKeyframeFactory.poseEditor.setPose(pose, uIPoseKeyframeFactory.poseEditor.getGroup());
                }
                if (string != null && !string.isEmpty()) {
                    uIPoseKeyframeFactory.poseEditor.selectBone(string);
                }
                return;
            }
        }
        if (uIKeyframeEditor.view != null) {
            uIKeyframeEditor.view.getGraph().pickSelected();
        }
    }

    private static boolean together_isClipKeyframeOnlyPath(List<String> list) {
        int n = list.indexOf("channels");
        if (n >= 0 && list.size() > n + 1) {
            return true;
        }
        for (String string : list) {
            if (!"keyframes".equals(string) && !"channel".equals(string) && !"x".equals(string) && !"y".equals(string) && !"z".equals(string) && !"yaw".equals(string) && !"pitch".equals(string) && !"roll".equals(string) && !"fov".equals(string) && !"distance".equals(string)) continue;
            return true;
        }
        return false;
    }

    private static void together_refreshEmbeddedClipKeyframeEditor(UIFilmPanel uIFilmPanel, List<String> list) {
        UIKeyframeEditor uIKeyframeEditor;
        if (list.size() <= 1) {
            return;
        }
        String string = list.get(1);
        if (!("camera".equals(string) || "action".equals(string) || "actions".equals(string))) {
            return;
        }
        UIKeyframeEditor uIKeyframeEditor2 = uIKeyframeEditor = "camera".equals(string) ? BBSTogetherFix14.getCameraKeyframeEditor(uIFilmPanel) : null;
        if (uIKeyframeEditor != null && uIKeyframeEditor.editor != null) {
            uIKeyframeEditor.editor.update();
        }
    }



    @Unique
    private static void together_remapClientReplaySelections(String string, Film film, Film film2) {
        if (string == null || string.isEmpty() || film == null || film2 == null) {
            return;
        }
        TogetherClientNetworkMixin.together_remapClientReplaySelections(string, film.replays.getList(), film2.replays.getList());
    }

    @Unique
    private static void together_remapClientReplaySelections(String string, List<Replay> list, List<Replay> list2) {
        if (string == null || string.isEmpty() || list == null || list2 == null) {
            return;
        }
        if (list.size() == list2.size()) {
            return;
        }
        Map<Integer, List<UUID>> map = replaySelections.get(string);
        if (map == null || map.isEmpty()) {
            return;
        }
        HashMap<Integer, List<UUID>> hashMap = new HashMap<Integer, List<UUID>>();
        for (Map.Entry<Integer, List<UUID>> entry : map.entrySet()) {
            int n2 = TogetherClientNetworkMixin.together_remapReplayIndex(list, list2, entry.getKey());
            if (n2 < 0) {
                if (list2.isEmpty()) continue;
                n2 = Math.min(Math.max(entry.getKey(), 0), list2.size() - 1);
            }
            hashMap.computeIfAbsent(n2, n -> new ArrayList<UUID>()).addAll((Collection<UUID>)entry.getValue());
        }
        replaySelections.put(string, hashMap);
    }

    @Unique
    private static int together_remapReplayIndex(List<Replay> list, List<Replay> list2, int n) {
        if (n < 0 || list2 == null || list2.isEmpty()) {
            return -1;
        }
        if (list == null || list.isEmpty()) {
            return Math.min(n, list2.size() - 1);
        }
        int n2 = list.size();
        int n3 = list2.size();
        if (n3 == n2 + 1) {
            int n4 = TogetherClientNetworkMixin.together_findChangedReplayIndex(list, list2);
            return n >= n4 ? n + 1 : n;
        }
        if (n3 == n2 - 1) {
            int n5 = TogetherClientNetworkMixin.together_findChangedReplayIndex(list2, list);
            if (n > n5) {
                return n - 1;
            }
            if (n == n5) {
                return Math.min(n5, n3 - 1);
            }
            return Math.min(n, n3 - 1);
        }
        return Math.min(n, n3 - 1);
    }

    @Unique
    private static int together_findChangedReplayIndex(List<Replay> list, List<Replay> list2) {
        int n = Math.min(list.size(), list2.size());
        for (int i = 0; i < n; ++i) {
            if (TogetherClientNetworkMixin.together_replaySignature(list.get(i)).equals(TogetherClientNetworkMixin.together_replaySignature(list2.get(i)))) continue;
            return i;
        }
        return n;
    }

    @Unique
    private static String together_replaySignature(Replay replay) {
        return replay == null ? "" : replay.toData().toString();
    }

    private static List<Integer> together_copyClipSelection(UIClipsPanel uIClipsPanel) {
        if (uIClipsPanel == null || uIClipsPanel.clips == null) {
            return new ArrayList<Integer>();
        }
        return new ArrayList<Integer>(uIClipsPanel.clips.getSelection());
    }

    private static void together_restoreClipSelection(UIClipsPanel uIClipsPanel, List<Integer> list) {
        if (uIClipsPanel == null || uIClipsPanel.clips == null || list.isEmpty()) {
            return;
        }
        UIClips uIClips = uIClipsPanel.clips;
        uIClips.setSelection(list);
        List list2 = uIClips.getClips() == null ? Collections.emptyList() : uIClips.getClips().get();
        int n = list.get(0);
        if (n >= 0 && n < list2.size()) {
            uIClips.pickClip((Clip)list2.get(n));
        }
    }

    private static Map<String, List<Integer>> together_copyKeyframeSelection(UIFilmPanel uIFilmPanel) {
        HashMap<String, List<Integer>> hashMap = new HashMap<String, List<Integer>>();
        UIKeyframeEditor uIKeyframeEditor = uIFilmPanel.replayEditor.keyframeEditor;
        if (uIKeyframeEditor == null || uIKeyframeEditor.view == null) {
            return hashMap;
        }
        IUIKeyframeGraph iUIKeyframeGraph = uIKeyframeEditor.view.getGraph();
        if (!(iUIKeyframeGraph instanceof UIKeyframeDopeSheet)) {
            return hashMap;
        }
        UIKeyframeDopeSheet uIKeyframeDopeSheet = (UIKeyframeDopeSheet)iUIKeyframeGraph;
        for (UIKeyframeSheet uIKeyframeSheet : uIKeyframeDopeSheet.getSheets()) {
            if (uIKeyframeSheet.selection == null || uIKeyframeSheet.selection.getIndices().isEmpty()) continue;
            hashMap.put(uIKeyframeSheet.id, new ArrayList(uIKeyframeSheet.selection.getIndices()));
        }
        return hashMap;
    }

    private static void together_restoreKeyframeSelection(UIFilmPanel uIFilmPanel, Map<String, List<Integer>> map) {
        if (map.isEmpty()) {
            return;
        }
        UIKeyframeEditor uIKeyframeEditor = uIFilmPanel.replayEditor.keyframeEditor;
        if (uIKeyframeEditor == null || uIKeyframeEditor.view == null) {
            return;
        }
        IUIKeyframeGraph iUIKeyframeGraph = uIKeyframeEditor.view.getGraph();
        if (!(iUIKeyframeGraph instanceof UIKeyframeDopeSheet)) {
            return;
        }
        UIKeyframeDopeSheet uIKeyframeDopeSheet = (UIKeyframeDopeSheet)iUIKeyframeGraph;
        for (UIKeyframeSheet uIKeyframeSheet : uIKeyframeDopeSheet.getSheets()) {
            List<Integer> list = map.get(uIKeyframeSheet.id);
            if (list == null || uIKeyframeSheet.selection == null) continue;
            for (Integer n : list) {
                uIKeyframeSheet.selection.add(n.intValue());
            }
        }
    }
}

