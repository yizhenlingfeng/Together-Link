package mchorse.bbs_together.mixin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.Recorder;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.film.replays.tracks.TrackId;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.ui.film.UIClips;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.graphs.IUIKeyframeGraph;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.graphs.UIKeyframeDopeSheet;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.CollectionUtils;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_together.BBSTogetherFix14;
import mchorse.bbs_together.BBSTogetherFix19;
import mchorse.bbs_together.BBSTogetherFix28Client;
import mchorse.bbs_together.BBSTogetherFix45;
import mchorse.bbs_together.ITogetherDopeSheet;
import mchorse.bbs_together.ITogetherReplaysEditor;
import mchorse.bbs_together.bbsop.BbsOpClientState;
import mchorse.bbs_together.network.TogetherClientNetwork;
import mchorse.bbs_together.network.TogetherUndoSync;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * All UIFilmPanel injections in one class: collaboration cursor/selection sync, undo/redo
 * broadcast, film appearance/join sync, recording sync, film CRUD broadcast and the
 * read-only (level 0) flight lock. Handler declaration order below mirrors the previous
 * cross-class application order (same-config mixins applied in json listing order).
 */
@Environment(value=EnvType.CLIENT)
@Mixin(value={UIFilmPanel.class}, remap=false)
public class UIFilmPanelMixin {
    @Unique
    private static final long TOGETHER_FS2_SELECTION_FLUSH_INTERVAL_MS = 250L;
    @Unique
    private int together_lastMouseX = Integer.MIN_VALUE;
    @Unique
    private int together_lastMouseY = Integer.MIN_VALUE;
    @Unique
    private long together_lastPlayheadSent = 0L;
    @Unique
    private int together_lastSentPlayhead = Integer.MIN_VALUE;
    @Unique
    private final Map<UUID, Integer> together_lastReplayCursorScreenY = new HashMap<UUID, Integer>();
    @Unique
    private List<Integer> together_fs2LastSelection = Collections.emptyList();
    @Unique
    private long together_fs2LastSelectionSent;
    @Unique
    private int together_fs2LastReplayCursor = Integer.MIN_VALUE;
    @Unique
    private long together_fs2LastReplayCursorSent;
    @Unique
    private String together_fs2LastSentReplayFilmId = "";
    @Unique
    private int together_fs2LastSentReplayIndex = Integer.MIN_VALUE;
    @Unique
    private String together_fs2LastJoinFilmId = "";

    @Inject(method={"fillData"}, at={@At(value="TAIL")}, remap=false)
    private void onFillData(CallbackInfo ci) {
        int idx;
        Replay replay;
        if (!TogetherClientNetwork.isConnected()) {
            return;
        }
        UIFilmPanel self = (UIFilmPanel)(Object)this;
        Film data = (Film)self.getData();
        TogetherClientNetwork.sendFilmSelection(data != null ? data.getId() : "");
        if (data != null && (replay = self.replayEditor.getReplay()) != null && (idx = data.replays.getList().indexOf(replay)) >= 0) {
            TogetherClientNetwork.sendReplaySelection(data.getId(), idx);
        }
    }

    @Inject(method={"fillData"}, at={@At(value="TAIL")}, remap=false)
    private void together_fix30Appear(CallbackInfo callbackInfo) {
        if (!TogetherClientNetwork.isConnected()) {
            return;
        }
        UIFilmPanel uIFilmPanel = (UIFilmPanel)(Object)this;
        if (uIFilmPanel.getData() == null) {
            return;
        }
        Film film = (Film)uIFilmPanel.getData();
        String string = film.getId();
        if (string == null || string.isEmpty()) {
            return;
        }
        TogetherClientNetwork.sendFilmSelection(string);
        this.together_fs2AnnounceReplaySelection(uIFilmPanel, film, true);
        this.together_fs2ResetCursorBroadcastCache();
        this.together_fs2LastJoinFilmId = string;
        if (!BBSTogetherFix28Client.consumeSmallReenterAppearJoinSuppressed(string)) {
            BBSTogetherFix28Client.sendFilmJoinRequest(string);
        }
    }

    @Inject(method={"fillData"}, at={@At(value="TAIL")}, remap=false)
    private void together_fix28JoinFilm(CallbackInfo callbackInfo) {
        if (!TogetherClientNetwork.isConnected()) {
            return;
        }
        UIFilmPanel uIFilmPanel = (UIFilmPanel)(Object)this;
        if (uIFilmPanel.getData() == null) {
            return;
        }
        String string = ((Film)uIFilmPanel.getData()).getId();
        if (string == null || string.isEmpty() || string.equals(this.together_fs2LastJoinFilmId)) {
            return;
        }
        this.together_fs2LastJoinFilmId = string;
        BBSTogetherFix28Client.sendFilmJoinRequest(string);
    }

    @Inject(method={"render"}, at={@At(value="HEAD")}, remap=false)
    private void onRenderHead(UIContext context, CallbackInfo ci) {
        UIFilmPanel self = (UIFilmPanel)(Object)this;
        if (!TogetherClientNetwork.isConnected()) {
            return;
        }
        if (self.getData() == null) {
            return;
        }
        float frameDelta = MinecraftClient.getInstance().getLastFrameDuration();
        for (TogetherClientNetwork.CursorPosition pos : TogetherClientNetwork.playerCursors.values()) {
            pos.interpolate(frameDelta);
        }
    }

    @Inject(method={"render"}, at={@At(value="HEAD")}, remap=false)
    private void together_fs2RenderHead(UIContext uIContext, CallbackInfo callbackInfo) {
        UIFilmPanel uIFilmPanel = (UIFilmPanel)(Object)this;
        if (TogetherClientNetwork.isConnected() && uIFilmPanel.getData() != null && BBSTogetherFix14.hasCameraKeyframeEditor(uIFilmPanel)) {
            BBSTogetherFix14.setCameraKeyframeHighlights(uIFilmPanel);
            return;
        }
        UIKeyframeDopeSheet uIKeyframeDopeSheet = together_fs2GetDopeSheet(uIFilmPanel);
        if (uIKeyframeDopeSheet == null || !(uIKeyframeDopeSheet instanceof ITogetherDopeSheet)) {
            return;
        }
        ITogetherDopeSheet iTogetherDopeSheet = (ITogetherDopeSheet)uIKeyframeDopeSheet;
        iTogetherDopeSheet.together_setExternalHighlights(together_fs2BuildHighlights(uIFilmPanel, uIKeyframeDopeSheet));
    }

    @Inject(method={"render"}, at={@At(value="TAIL")}, remap=false)
    private void onRenderTail(UIContext context, CallbackInfo ci) {
        IUIKeyframeGraph iUIKeyframeGraph;
        UIFilmPanel self = (UIFilmPanel)(Object)this;
        if (!TogetherClientNetwork.isConnected()) {
            return;
        }
        if (self.getData() == null) {
            return;
        }
        this.sendCursorIfMoved(self, context);
        TogetherClientNetwork.flushPendingCursor();
        this.sendPlayheadIfChanged(self);
        this.renderRemoteCursors(self, context);
        this.renderRemotePlayheadLines(self, context);
        if (self.replayEditor.keyframeEditor != null && self.replayEditor.keyframeEditor.view != null && (iUIKeyframeGraph = self.replayEditor.keyframeEditor.view.getGraph()) instanceof ITogetherDopeSheet) {
            ITogetherDopeSheet ds = (ITogetherDopeSheet)iUIKeyframeGraph;
            ds.together_setExternalHighlights(null);
        }
    }

    @Inject(method={"render"}, at={@At(value="TAIL")}, remap=false)
    private void together_fs2RenderTail(UIContext uIContext, CallbackInfo callbackInfo) {
        boolean bl;
        UIFilmPanel uIFilmPanel = (UIFilmPanel)(Object)this;
        if (!TogetherClientNetwork.isConnected()) {
            return;
        }
        if (uIFilmPanel.getData() == null) {
            return;
        }
        this.together_fs2SendCurrentReplaySelection(uIFilmPanel);
        if (BBSTogetherFix14.hasCameraKeyframeEditor(uIFilmPanel)) {
            BBSTogetherFix14.setCameraKeyframeHighlights(uIFilmPanel);
            BBSTogetherFix14.sendCameraKeyframeCursor(uIFilmPanel, uIContext);
            BBSTogetherFix14.renderCameraKeyframeCursors(uIFilmPanel, uIContext);
            BBSTogetherFix14.renderCameraKeyframePlayheads(uIFilmPanel, uIContext);
            return;
        }
        if (uIFilmPanel.replayEditor == null || !uIFilmPanel.replayEditor.isVisible()) {
            return;
        }
        UIKeyframeDopeSheet uIKeyframeDopeSheet = together_fs2GetDopeSheet(uIFilmPanel);
        if (uIKeyframeDopeSheet == null) {
            return;
        }
        together_fs2RenderRemoteCursors(uIFilmPanel, uIContext, uIKeyframeDopeSheet);
        List<Integer> list = together_fs2EncodeSelection(uIFilmPanel, uIKeyframeDopeSheet);
        float[] fArray = together_fs2TickRowFromKeyframes(uIFilmPanel, uIFilmPanel.replayEditor.keyframeEditor.view, uIKeyframeDopeSheet, uIContext.mouseX, uIContext.mouseY);
        boolean bl2 = fArray != null;
        long l = System.currentTimeMillis();
        int n = uIFilmPanel.getCursor();
        boolean bl3 = n != this.together_fs2LastReplayCursor;
        boolean bl4 = bl = !list.equals(this.together_fs2LastSelection);
        if (!(bl2 || bl || bl3 || l - this.together_fs2LastSelectionSent >= TOGETHER_FS2_SELECTION_FLUSH_INTERVAL_MS)) {
            return;
        }
        if (!bl && !bl3 && l - this.together_fs2LastSelectionSent < 33L) {
            return;
        }
        float f = -1.0f;
        float f2 = -1.0f;
        if (bl2) {
            f = fArray[0];
            f2 = fArray[1];
        }
        String string = ((Film)uIFilmPanel.getData()).getId();
        TogetherClientNetwork.sendCursorPosition(string, BBSTogetherFix14.replayEditorType(uIFilmPanel.replayEditor), f, f2, list, n);
        this.together_fs2LastSelection = new ArrayList<Integer>(list);
        this.together_fs2LastSelectionSent = l;
        this.together_fs2LastReplayCursor = n;
        this.together_fs2LastReplayCursorSent = l;
    }

    /* Read-only lock: a level-0 player cannot toggle camera flight. setFlight(false) on panel
     * close is left alone (that's cleanup), only the user-initiated toggle is blocked. */
    @Inject(method={"toggleFlight"}, at={@At(value="HEAD")}, cancellable=true, remap=false)
    private void together$blockToggleFlight(CallbackInfo ci) {
        if (!BbsOpClientState.canEdit("film")) {
            ci.cancel();
        }
    }

    @Inject(method={"dupeData"}, at={@At(value="TAIL")}, remap=false)
    private void onDupeData(String name, CallbackInfo ci) {
        if (!TogetherClientNetwork.isConnected()) {
            return;
        }
        UIFilmPanel self = (UIFilmPanel)(Object)this;
        if (self.getData() == null) {
            return;
        }
        TogetherClientNetwork.sendFilmSaveSync(((Film)self.getData()).getId(), (Film)self.getData());
        TogetherClientNetwork.sendFilmListChanged();
    }

    @Inject(method={"applyRecordedKeyframes"}, at={@At(value="TAIL")}, remap=false)
    private void onApplyRecordedKeyframesFix19(Recorder recorder, Film film, CallbackInfo callbackInfo) {
        if (recorder == null || recorder.hasNotStarted()) {
            return;
        }
        if (!TogetherClientNetwork.recordingSessionActive) {
            return;
        }
        BBSTogetherFix19.sendRecordingFinished(film, recorder);
    }

    @Inject(method={"applyRecordedKeyframes"}, at={@At(value="TAIL")}, remap=false)
    private void onApplyRecordedKeyframes(Recorder recorder, Film film, CallbackInfo ci) {
        if (!TogetherClientNetwork.isConnected()) {
            return;
        }
        if (film == null) {
            return;
        }
        int replayIdx = recorder.exception;
        Replay replay = (Replay)CollectionUtils.getSafe((List)film.replays.getList(), (int)replayIdx);
        if (replay == null) {
            return;
        }
        String filmId = film.getId();
        String idxStr = String.valueOf(replayIdx);
        TogetherClientNetwork.stripRecordingCommands(replay);
        List<String> kfBase = Arrays.asList("replays", idxStr, "keyframes");
        for (KeyframeChannel ch : replay.keyframes.getChannels()) {
            if (ch.isEmpty()) continue;
            ArrayList<String> path = new ArrayList<String>(kfBase);
            path.add(ch.getId());
            TogetherClientNetwork.sendSyncDataWithPath(filmId, path, (BaseValue)ch);
        }
        if (replay.properties != null && replay.properties.tracks != null) {
            List<String> propBase = Arrays.asList("replays", idxStr, "properties");
            for (Map.Entry<TrackId, KeyframeChannel> entry : replay.properties.tracks.entrySet()) {
                KeyframeChannel ch = entry.getValue();
                if (ch.isEmpty()) continue;
                ArrayList<String> path = new ArrayList<String>(propBase);
                path.add(entry.getKey().toKey());
                TogetherClientNetwork.sendSyncDataWithPath(filmId, path, (BaseValue)ch);
            }
        }
        TogetherClientNetwork.recordingSessionActive = false;
    }

    @Inject(method={"receiveActions"}, at={@At(value="INVOKE", target="Lmchorse/bbs_mod/ui/film/UIFilmPanel;save()V", remap=false)}, remap=false)
    private void onReceiveActions(String string, int n, int n2, BaseType baseType, CallbackInfo callbackInfo) {
        UIFilmPanel uIFilmPanel = (UIFilmPanel)(Object)this;
        Film film = (Film)uIFilmPanel.getData();
        if (film == null || string == null || !string.equals(film.getId())) {
            return;
        }
        List list = film.replays.getList();
        if (n < 0 || n >= list.size()) {
            return;
        }
        Replay replay = (Replay)list.get(n);
        if (replay == null || replay.actions == null) {
            return;
        }
        MapType mapType = new MapType();
        mapType.put("actions", replay.actions.toData());
        BBSTogetherFix19.sendRecordingFinishedData(string, n, (BaseType)mapType);
    }

    @Inject(method={"undo"}, at={@At(value="TAIL")}, remap=false)
    private void onUndo(CallbackInfo ci) {
        TogetherUndoSync.broadcast((UIFilmPanel)(Object)this, false);
    }

    @Inject(method={"redo"}, at={@At(value="TAIL")}, remap=false)
    private void onRedo(CallbackInfo ci) {
        TogetherUndoSync.broadcast((UIFilmPanel)(Object)this, true);
    }

    /* ---- cursor / playhead sync helpers (legacy paths, gated by Fix14) ---------------- */

    @Unique
    private void sendCursorIfMoved(UIFilmPanel self, UIContext context) {
        float[] tr;
        boolean playheadMoved;
        int mx = context.mouseX;
        int my = context.mouseY;
        int playhead = self.getCursor();
        boolean mouseMoved = mx != this.together_lastMouseX || my != this.together_lastMouseY;
        boolean bl = playheadMoved = playhead != this.together_lastSentPlayhead;
        if (!mouseMoved && !playheadMoved) {
            return;
        }
        if (mouseMoved) {
            this.together_lastMouseX = mx;
            this.together_lastMouseY = my;
        }
        if (playheadMoved) {
            this.together_lastSentPlayhead = playhead;
            this.together_lastPlayheadSent = System.currentTimeMillis();
        }
        String filmId = ((Film)self.getData()).getId();
        if (BBSTogetherFix14.hasCameraKeyframeEditor(self)) {
            BBSTogetherFix14.sendCameraKeyframeCursor(self, context);
            return;
        }
        if (self.cameraEditor.isVisible() && self.cameraEditor.clips != null && (tr = this.together_tickRowFromClips(self.cameraEditor.clips, mx, my)) != null) {
            ArrayList<Integer> sel = new ArrayList<Integer>(self.cameraEditor.clips.getSelection());
            TogetherClientNetwork.sendCursorPosition(filmId, 0, tr[0], tr[1], sel, self.getCursor());
            return;
        }
        if (self.actionEditor.isVisible() && self.actionEditor.clips != null && (tr = this.together_tickRowFromClips(self.actionEditor.clips, mx, my)) != null) {
            ArrayList<Integer> sel = new ArrayList<Integer>(self.actionEditor.clips.getSelection());
            TogetherClientNetwork.sendCursorPosition(filmId, 2, tr[0], tr[1], sel, self.getCursor());
        }
    }

    @Unique
    private void sendPlayheadIfChanged(UIFilmPanel self) {
        if (!TogetherClientNetwork.isConnected()) {
            return;
        }
        if (self.getData() == null) {
            return;
        }
        int playhead = self.getCursor();
        long now = System.currentTimeMillis();
        if (playhead == this.together_lastSentPlayhead) {
            return;
        }
        if (now - this.together_lastPlayheadSent < 100L) {
            return;
        }
        this.together_lastSentPlayhead = playhead;
        this.together_lastPlayheadSent = now;
        String filmId = ((Film)self.getData()).getId();
        TogetherClientNetwork.sendCursorPosition(filmId, BBSTogetherFix14.currentEditorType(self), -1.0f, -1.0f, Collections.emptyList(), playhead);
    }

    @Unique
    private float[] together_tickRowFromClips(UIClips clips, int mx, int my) {
        if (!clips.area.isInside(mx, my)) {
            return null;
        }
        float tick = (float)clips.fromGraphX(mx);
        int layer = clips.fromLayerY(my);
        if (layer < 0) {
            layer = 0;
        }
        int rowTop = clips.toLayerY(layer);
        float frac = Math.max(0.0f, Math.min(0.999f, (float)(my - rowTop) / 20.0f));
        return new float[]{tick, (float)layer + frac};
    }

    @Unique
    private void renderRemoteCursors(UIFilmPanel self, UIContext context) {
        if (BBSTogetherFix14.shouldSkipLegacyCursorRenderer(self)) {
            return;
        }
        if (TogetherClientNetwork.playerCursors.isEmpty()) {
            return;
        }
        int localEditorType = -1;
        if (self.cameraEditor.isVisible()) {
            localEditorType = 0;
        } else if (self.replayEditor.isVisible()) {
            localEditorType = BBSTogetherFix14.replayEditorType(self.replayEditor);
        } else if (self.actionEditor.isVisible()) {
            localEditorType = 2;
        }
        if (localEditorType < 0) {
            return;
        }
        for (Map.Entry<UUID, TogetherClientNetwork.CursorPosition> entry : TogetherClientNetwork.playerCursors.entrySet()) {
            int[] xy;
            Object xyObj;
            UUID uuid = entry.getKey();
            TogetherClientNetwork.CursorPosition pos = entry.getValue();
            if (!BBSTogetherFix14.legacyCursorTypesMatch(pos.editorType, localEditorType)) continue;
            Integer col = TogetherClientNetwork.playerColors.get(uuid);
            int color = (col != null ? col : 0xFFFFFF) | 0xFF000000;
            if (pos.currentTick < 0.0f && pos.currentRow < 0.0f) continue;
            int screenX = -1;
            int screenY = -1;
            boolean shouldRender = false;
            if (localEditorType == 0 && self.cameraEditor.clips != null) {
                xy = this.together_screenFromClips(self.cameraEditor.clips, pos.currentTick, pos.currentRow);
                if (xy != null && self.cameraEditor.clips.area.isInside(xy[0], xy[1])) {
                    screenX = xy[0];
                    screenY = xy[1];
                    shouldRender = true;
                }
            } else if (localEditorType == 1 && self.replayEditor.keyframeEditor != null && self.replayEditor.keyframeEditor.view != null && (xyObj = self.replayEditor.keyframeEditor.view.getGraph()) instanceof UIKeyframeDopeSheet) {
                Integer frozen;
                UIKeyframeDopeSheet ds = (UIKeyframeDopeSheet)xyObj;
                if (self.getData() != null) {
                    boolean remoteOnSameReplay;
                    Replay localReplay = self.replayEditor.getReplay();
                    int localReplayIdx = localReplay == null ? -1 : ((Film)self.getData()).replays.getList().indexOf(localReplay);
                    Map<Integer, List<UUID>> selMap = TogetherClientNetwork.replaySelections.get(((Film)self.getData()).getId());
                    boolean bl = remoteOnSameReplay = selMap != null && localReplayIdx >= 0 && selMap.containsKey(localReplayIdx) && selMap.get(localReplayIdx).contains(uuid);
                    if (!remoteOnSameReplay) continue;
                }
                screenX = self.replayEditor.keyframeEditor.view.toGraphX((double)pos.currentTick);
                List<String> allIds = ((ITogetherReplaysEditor)self.replayEditor).together_getAllSheetIds();
                int canonical = (int)pos.currentRow;
                float frac = pos.currentRow - (float)canonical;
                boolean resolved = false;
                if (canonical >= 0 && canonical < allIds.size()) {
                    String targetId = allIds.get(canonical);
                    List vis = ds.getSheets();
                    for (int vi = 0; vi < vis.size(); ++vi) {
                        if (!((UIKeyframeSheet)vis.get((int)vi)).id.equals(targetId)) continue;
                        screenY = ds.getDopeSheetY(vi) + (int)((double)frac * ds.getTrackHeight());
                        this.together_lastReplayCursorScreenY.put(uuid, screenY);
                        resolved = true;
                        break;
                    }
                }
                if (!resolved && (frozen = this.together_lastReplayCursorScreenY.get(uuid)) != null) {
                    screenY = frozen;
                }
                if (screenY >= 0 && self.replayEditor.keyframeEditor.view.area.isInside(screenX, screenY)) {
                    shouldRender = true;
                }
            } else if (localEditorType == 2 && self.actionEditor.clips != null && (xy = this.together_screenFromClips(self.actionEditor.clips, pos.currentTick, pos.currentRow)) != null && self.actionEditor.clips.area.isInside(xy[0], xy[1])) {
                screenX = xy[0];
                screenY = xy[1];
                shouldRender = true;
            }
            if (!shouldRender || screenX < 0 || screenY < 0) continue;
            context.batcher.icon(Icons.CURSOR, color, (float)screenX, (float)screenY);
        }
    }

    @Unique
    private int[] together_screenFromClips(UIClips clips, float tick, float row) {
        int screenX = clips.toGraphX((int)tick);
        int layer = (int)row;
        float frac = row - (float)layer;
        int screenY = clips.toLayerY(layer) + (int)(frac * 20.0f);
        return new int[]{screenX, screenY};
    }

    @Unique
    private void renderRemotePlayheadLines(UIFilmPanel self, UIContext context) {
        if (BBSTogetherFix14.shouldSkipLegacyPlayheadRenderer(self)) {
            return;
        }
        if (TogetherClientNetwork.playerCursors.isEmpty()) {
            return;
        }
        if (self.getData() == null) {
            return;
        }
        UIClips activeClips = null;
        int activeEditorType = -1;
        if (self.cameraEditor.isVisible() && self.cameraEditor.clips != null) {
            activeClips = self.cameraEditor.clips;
            activeEditorType = 0;
        } else if (self.actionEditor.isVisible() && self.actionEditor.clips != null) {
            activeClips = self.actionEditor.clips;
            activeEditorType = 2;
        } else if (self.replayEditor.isVisible() && self.replayEditor.keyframeEditor != null && self.replayEditor.keyframeEditor.view != null) {
            for (Map.Entry<UUID, TogetherClientNetwork.CursorPosition> entry : TogetherClientNetwork.playerCursors.entrySet()) {
                UUID uuid = entry.getKey();
                if (MinecraftClient.getInstance().player != null && uuid.equals(MinecraftClient.getInstance().player.getUuid())) continue;
                TogetherClientNetwork.CursorPosition pos = entry.getValue();
                Integer col = TogetherClientNetwork.playerColors.get(uuid);
                if (col == null) continue;
                int lineColor = Colors.setA((int)(col | 0xFF000000), (float)0.25f);
                if (!BBSTogetherFix14.editorTypesMatch(pos.editorType, BBSTogetherFix14.replayEditorType(self.replayEditor))) continue;
                int screenX = self.replayEditor.keyframeEditor.view.toGraphX((double)pos.playheadTick);
                Area viewArea = self.replayEditor.keyframeEditor.view.area;
                if (screenX < viewArea.x || screenX > viewArea.ex()) continue;
                context.batcher.box((float)screenX, (float)viewArea.y, (float)(screenX + 2), (float)viewArea.ey(), lineColor);
            }
            return;
        }
        if (activeClips == null) {
            return;
        }
        for (Map.Entry<UUID, TogetherClientNetwork.CursorPosition> entry : TogetherClientNetwork.playerCursors.entrySet()) {
            Integer col;
            UUID uuid = entry.getKey();
            if (MinecraftClient.getInstance().player != null && uuid.equals(MinecraftClient.getInstance().player.getUuid())) continue;
            TogetherClientNetwork.CursorPosition pos = entry.getValue();
            if (!BBSTogetherFix14.shouldUseClipRenderer(pos.editorType, activeEditorType) || (col = TogetherClientNetwork.playerColors.get(uuid)) == null) continue;
            int lineColor = Colors.setA((int)(col | 0xFF000000), (float)0.25f);
            int screenX = activeClips.toGraphX(pos.playheadTick);
            Area clipsArea = activeClips.area;
            if (screenX < clipsArea.x || screenX > clipsArea.ex()) continue;
            context.batcher.box((float)screenX, (float)clipsArea.y, (float)(screenX + 2), (float)clipsArea.ey(), lineColor);
        }
    }

    /* ---- FS2 keyframe collaboration helpers ------------------------------------------- */

    @Unique
    private void together_fs2SendCurrentReplaySelection(UIFilmPanel uIFilmPanel) {
        if (uIFilmPanel.replayEditor == null || uIFilmPanel.getData() == null) {
            return;
        }
        Film film = (Film)uIFilmPanel.getData();
        this.together_fs2AnnounceReplaySelection(uIFilmPanel, film, false);
    }

    @Unique
    private void together_fs2AnnounceReplaySelection(UIFilmPanel uIFilmPanel, Film film, boolean bl) {
        Replay replay = uIFilmPanel.replayEditor.getReplay();
        int n = BBSTogetherFix45.identityReplayIndex(film.replays.getList(), replay);
        String string = film.getId();
        if (n < 0 || !bl && string.equals(this.together_fs2LastSentReplayFilmId) && n == this.together_fs2LastSentReplayIndex) {
            return;
        }
        TogetherClientNetwork.sendReplaySelection(string, n);
        this.together_fs2LastSentReplayFilmId = string;
        this.together_fs2LastSentReplayIndex = n;
    }

    @Unique
    private void together_fs2ResetCursorBroadcastCache() {
        this.together_fs2LastSelection = Collections.emptyList();
        this.together_fs2LastSelectionSent = 0L;
        this.together_fs2LastReplayCursor = Integer.MIN_VALUE;
        this.together_fs2LastReplayCursorSent = 0L;
    }

    @Unique
    private static UIKeyframeDopeSheet together_fs2GetDopeSheet(UIFilmPanel uIFilmPanel) {
        UIKeyframeDopeSheet uIKeyframeDopeSheet;
        if (!TogetherClientNetwork.isConnected() || uIFilmPanel.getData() == null || uIFilmPanel.replayEditor == null || uIFilmPanel.replayEditor.keyframeEditor == null || uIFilmPanel.replayEditor.keyframeEditor.view == null) {
            return null;
        }
        IUIKeyframeGraph iUIKeyframeGraph = uIFilmPanel.replayEditor.keyframeEditor.view.getGraph();
        return iUIKeyframeGraph instanceof UIKeyframeDopeSheet ? (uIKeyframeDopeSheet = (UIKeyframeDopeSheet)iUIKeyframeGraph) : null;
    }

    @Unique
    private static List<Integer> together_fs2EncodeSelection(UIFilmPanel uIFilmPanel, UIKeyframeDopeSheet uIKeyframeDopeSheet) {
        ArrayList<Integer> arrayList = new ArrayList<Integer>();
        List list = uIKeyframeDopeSheet.getSheets();
        for (int i = 0; i < list.size(); ++i) {
            UIKeyframeSheet uIKeyframeSheet = (UIKeyframeSheet)list.get(i);
            if (uIKeyframeSheet.selection == null || uIKeyframeSheet.selection.getIndices().isEmpty()) continue;
            for (Integer n : uIKeyframeSheet.selection.getIndices()) {
                int n2 = together_fs2AllSheetIndex(uIFilmPanel, list, i);
                if (n2 < 0) continue;
                arrayList.add(n2 << 16 | n & 0xFFFF);
            }
        }
        return arrayList;
    }

    @Unique
    private static Map<Integer, Integer> together_fs2BuildHighlights(UIFilmPanel uIFilmPanel, UIKeyframeDopeSheet uIKeyframeDopeSheet) {
        HashMap<Integer, Integer> hashMap = new HashMap<Integer, Integer>();
        if (TogetherClientNetwork.playerCursors.isEmpty() || uIFilmPanel.getData() == null) {
            return hashMap;
        }
        Replay replay = uIFilmPanel.replayEditor.getReplay();
        int n3 = BBSTogetherFix45.identityReplayIndex(((Film)uIFilmPanel.getData()).replays.getList(), replay);
        if (n3 < 0) {
            return hashMap;
        }
        String string = ((Film)uIFilmPanel.getData()).getId();
        Map<Integer, List<UUID>> map = TogetherClientNetwork.replaySelections.get(string);
        List list = uIKeyframeDopeSheet.getSheets();
        for (Map.Entry<UUID, TogetherClientNetwork.CursorPosition> entry : TogetherClientNetwork.playerCursors.entrySet()) {
            Integer n4;
            TogetherClientNetwork.CursorPosition cursorPosition = entry.getValue();
            if (!BBSTogetherFix14.editorTypesMatch(BBSTogetherFix14.replayEditorType(uIFilmPanel.replayEditor), cursorPosition.editorType) || cursorPosition.selectedIndices.isEmpty() || !BBSTogetherFix14.shouldUseReplaySelectionFilter(map, n3, entry.getKey()) || (n4 = TogetherClientNetwork.playerColors.get(entry.getKey())) == null) continue;
            int n5 = Colors.setA((int)(n4 | 0xFF000000), (float)0.55f);
            for (Integer n6 : cursorPosition.selectedIndices) {
                int n7 = together_fs2VisibleSheetIndex(uIFilmPanel, list, n6 >> 16 & 0xFFFF);
                int n8 = n6 & 0xFFFF;
                if (n7 < 0 || n7 >= list.size()) continue;
                hashMap.merge(n7 << 16 | n8, n5, (n, n2) -> n2);
            }
        }
        return hashMap;
    }

    @Unique
    private static float[] together_fs2TickRowFromKeyframes(UIFilmPanel uIFilmPanel, UIKeyframes uIKeyframes, UIKeyframeDopeSheet uIKeyframeDopeSheet, int n, int n2) {
        float[] fArray;
        if (uIKeyframes == null || uIKeyframeDopeSheet == null || uIKeyframes.graphArea != null && !uIKeyframes.graphArea.isInside(n, n2) || uIKeyframes.graphArea == null && !uIKeyframes.area.isInside(n, n2)) {
            return null;
        }
        UIKeyframeSheet uIKeyframeSheet = uIKeyframeDopeSheet.getSheet(n2);
        if (uIKeyframeSheet == null) {
            return null;
        }
        List list = uIKeyframeDopeSheet.getSheets();
        int n3 = list.indexOf(uIKeyframeSheet);
        if (n3 < 0) {
            return null;
        }
        int n4 = uIKeyframeDopeSheet.getDopeSheetY(n3);
        float f = Math.max(0.0f, Math.min(0.999f, (float)(n2 - n4) / (float)uIKeyframeDopeSheet.getTrackHeight()));
        int n5 = together_fs2AllSheetIndex(uIFilmPanel, list, n3);
        if (n5 < 0) {
            fArray = null;
        } else {
            float[] fArray2 = new float[2];
            fArray2[0] = (float)uIKeyframes.fromGraphX(n);
            fArray = fArray2;
            fArray2[1] = (float)n5 + f;
        }
        return fArray;
    }

    @Unique
    private static void together_fs2RenderRemoteCursors(UIFilmPanel uIFilmPanel, UIContext uIContext, UIKeyframeDopeSheet uIKeyframeDopeSheet) {
        if (TogetherClientNetwork.playerCursors.isEmpty() || uIFilmPanel.getData() == null || uIFilmPanel.replayEditor == null || uIFilmPanel.replayEditor.keyframeEditor == null) {
            return;
        }
        Replay replay = uIFilmPanel.replayEditor.getReplay();
        int n = BBSTogetherFix45.identityReplayIndex(((Film)uIFilmPanel.getData()).replays.getList(), replay);
        if (n < 0) {
            return;
        }
        String string = ((Film)uIFilmPanel.getData()).getId();
        Map<Integer, List<UUID>> map = TogetherClientNetwork.replaySelections.get(string);
        int n2 = BBSTogetherFix14.replayEditorType(uIFilmPanel.replayEditor);
        for (Map.Entry<UUID, TogetherClientNetwork.CursorPosition> entry : TogetherClientNetwork.playerCursors.entrySet()) {
            float f;
            int[] nArray;
            int n3;
            TogetherClientNetwork.CursorPosition cursorPosition = entry.getValue();
            if (!BBSTogetherFix14.editorTypesMatch(n2, cursorPosition.editorType) || cursorPosition.currentTick < 0.0f || cursorPosition.currentRow < 0.0f || !BBSTogetherFix14.shouldUseReplaySelectionFilter(map, n, entry.getKey()) || (n3 = together_fs2VisibleSheetIndex(uIFilmPanel, uIKeyframeDopeSheet.getSheets(), (int)cursorPosition.currentRow)) < 0 || (nArray = BBSTogetherFix14.screenFromKeyframes(uIFilmPanel.replayEditor.keyframeEditor.view, uIKeyframeDopeSheet, cursorPosition.currentTick, f = (float)n3 + (cursorPosition.currentRow - (float)((int)cursorPosition.currentRow)))) == null) continue;
            Integer n4 = TogetherClientNetwork.playerColors.get(entry.getKey());
            int n5 = (n4 == null ? 0xFFFFFF : n4) | 0xFF000000;
            uIContext.batcher.icon(Icons.CURSOR, n5, (float)nArray[0], (float)nArray[1]);
        }
    }

    @Unique
    private static int together_fs2VisibleSheetIndex(UIFilmPanel uIFilmPanel, List<UIKeyframeSheet> list, int n) {
        if (n < 0) {
            return -1;
        }
        Object object = uIFilmPanel.replayEditor;
        if (!(object instanceof ITogetherReplaysEditor)) {
            return n;
        }
        ITogetherReplaysEditor iTogetherReplaysEditor = (ITogetherReplaysEditor)object;
        List<String> allIds = iTogetherReplaysEditor.together_getAllSheetIds();
        if (allIds == null || n >= allIds.size()) {
            return n;
        }
        String string = allIds.get(n);
        for (int i = 0; i < list.size(); ++i) {
            if (!string.equals(together_fs2SheetKey(list.get(i)))) continue;
            return i;
        }
        return -1;
    }

    @Unique
    private static int together_fs2AllSheetIndex(UIFilmPanel uIFilmPanel, List<UIKeyframeSheet> list, int n) {
        if (n < 0 || n >= list.size()) {
            return -1;
        }
        Object object = uIFilmPanel.replayEditor;
        if (!(object instanceof ITogetherReplaysEditor)) {
            return n;
        }
        ITogetherReplaysEditor iTogetherReplaysEditor = (ITogetherReplaysEditor)object;
        List<String> allIds2 = iTogetherReplaysEditor.together_getAllSheetIds();
        if (allIds2 == null) {
            return n;
        }
        int n2 = allIds2.indexOf(together_fs2SheetKey(list.get(n)));
        return n2 < 0 ? n : n2;
    }

    @Unique
    private static String together_fs2SheetKey(UIKeyframeSheet uIKeyframeSheet) {
        if (uIKeyframeSheet == null) {
            return "";
        }
        String string = "";
        try {
            string = uIKeyframeSheet.title == null ? "" : uIKeyframeSheet.title.get();
        }
        catch (Exception exception) {

        }
        return uIKeyframeSheet.id + "\u0002" + string + "\u0002" + uIKeyframeSheet.isBoneTrack;
    }
}
