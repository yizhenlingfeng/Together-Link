package mchorse.bbs_together.mixin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.ui.film.UIClips;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.film.replays.UIReplaysEditor;
import mchorse.bbs_mod.ui.film.utils.undo.ValueChangeUndo;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.graphs.IUIKeyframeGraph;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.graphs.UIKeyframeDopeSheet;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.utils.undo.CompoundUndo;
import mchorse.bbs_mod.utils.undo.IUndo;
import mchorse.bbs_together.BBSTogetherFix14;
import mchorse.bbs_together.ITogetherDopeSheet;
import mchorse.bbs_together.ITogetherReplaysEditor;
import mchorse.bbs_together.network.TogetherClientNetwork;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.util.Identifier;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.render.Tessellator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(value=EnvType.CLIENT)
@Mixin(value={UIFilmPanel.class}, remap=false)
public class UIFilmPanelMixin {
    private static final int EDITOR_CAMERA = 0;
    private static final int EDITOR_REPLAY = 1;
    private static final int EDITOR_ACTION = 2;
    private static final int LAYER_HEIGHT = 20;
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

    @Inject(method={"render"}, at={@At(value="HEAD")}, remap=false)
    private void onRenderHead(UIContext context, CallbackInfo ci) {
        UIKeyframeDopeSheet ds;
        IUIKeyframeGraph iUIKeyframeGraph;
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
        self.replayEditor.isVisible();
        if (false && self.replayEditor.keyframeEditor != null && self.replayEditor.keyframeEditor.view != null && (iUIKeyframeGraph = self.replayEditor.keyframeEditor.view.getGraph()) instanceof UIKeyframeDopeSheet && (ds = (UIKeyframeDopeSheet)iUIKeyframeGraph) instanceof ITogetherDopeSheet) {
            ITogetherDopeSheet togetherDs = (ITogetherDopeSheet)ds;
            togetherDs.together_setExternalHighlights(this.buildKeyframeHighlights(self, ds));
        }
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
        this.sendPlayheadIfChanged(self);
        this.renderRemoteCursors(self, context);
        this.renderRemotePlayheadLines(self, context);
        if (self.replayEditor.keyframeEditor != null && self.replayEditor.keyframeEditor.view != null && (iUIKeyframeGraph = self.replayEditor.keyframeEditor.view.getGraph()) instanceof ITogetherDopeSheet) {
            ITogetherDopeSheet ds = (ITogetherDopeSheet)iUIKeyframeGraph;
            ds.together_setExternalHighlights(null);
        }
    }

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
        self.replayEditor.isVisible();
        if (false && self.replayEditor.keyframeEditor != null && self.replayEditor.keyframeEditor.view != null && (tr = this.together_tickRowFromKeyframes(self.replayEditor, mx, my)) != null) {
            List<Integer> sel = this.together_encodeKeyframeSelection(self.replayEditor);
            TogetherClientNetwork.sendCursorPosition(filmId, 1, tr[0], tr[1], sel, self.getCursor());
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
        int editorType = 0;
        self.replayEditor.isVisible();
        if (false) {
            editorType = 1;
        } else if (self.actionEditor.isVisible()) {
            editorType = 2;
        }
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
    private float[] together_tickRowFromKeyframes(UIReplaysEditor replayEditor, int mx, int my) {
        UIKeyframes view = replayEditor.keyframeEditor.view;
        if (!view.area.isInside(mx, my)) {
            return null;
        }
        IUIKeyframeGraph iUIKeyframeGraph = view.getGraph();
        if (!(iUIKeyframeGraph instanceof UIKeyframeDopeSheet)) {
            return null;
        }
        UIKeyframeDopeSheet ds = (UIKeyframeDopeSheet)iUIKeyframeGraph;
        UIKeyframeSheet hovered = ds.getSheet(my);
        if (hovered == null) {
            return null;
        }
        float tick = (float)view.fromGraphX(mx);
        List<String> allIds = ((ITogetherReplaysEditor)replayEditor).together_getAllSheetIds();
        int canonicalIndex = allIds.indexOf(hovered.id);
        if (canonicalIndex < 0) {
            canonicalIndex = ds.getSheets().indexOf(hovered);
        }
        int visibleIndex = ds.getSheets().indexOf(hovered);
        int trackTop = ds.getDopeSheetY(visibleIndex);
        float frac = Math.max(0.0f, Math.min(0.999f, (float)(my - trackTop) / (float)ds.getTrackHeight()));
        return new float[]{tick, (float)canonicalIndex + frac};
    }

    @Unique
    private List<Integer> together_encodeKeyframeSelection(UIReplaysEditor replayEditor) {
        ArrayList<Integer> result = new ArrayList<Integer>();
        if (replayEditor.keyframeEditor == null || replayEditor.keyframeEditor.view == null) {
            return result;
        }
        IUIKeyframeGraph iUIKeyframeGraph = replayEditor.keyframeEditor.view.getGraph();
        if (!(iUIKeyframeGraph instanceof UIKeyframeDopeSheet)) {
            return result;
        }
        UIKeyframeDopeSheet ds = (UIKeyframeDopeSheet)iUIKeyframeGraph;
        List<String> allIds = ((ITogetherReplaysEditor)replayEditor).together_getAllSheetIds();
        List<UIKeyframeSheet> sheets = ds.getSheets();
        for (UIKeyframeSheet sheet : sheets) {
            Collection indices = sheet.selection.getIndices();
            if (indices.isEmpty()) continue;
            int canonicalIndex = allIds.indexOf(sheet.id);
            if (canonicalIndex < 0) {
                canonicalIndex = sheets.indexOf(sheet);
            }
            Iterator iterator = indices.iterator();
            while (iterator.hasNext()) {
                int ki = (Integer)iterator.next();
                result.add(canonicalIndex << 16 | ki & 0xFFFF);
            }
        }
        return result;
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
    private Map<Integer, Integer> buildKeyframeHighlights(UIFilmPanel self, UIKeyframeDopeSheet ds) {
        HashMap<Integer, Integer> highlights = new HashMap<Integer, Integer>();
        if (TogetherClientNetwork.playerCursors.isEmpty()) {
            return highlights;
        }
        List<String> allIds = ((ITogetherReplaysEditor)self.replayEditor).together_getAllSheetIds();
        List visSheets = ds.getSheets();
        HashMap<Integer, Integer> canonToVis = new HashMap<Integer, Integer>();
        for (int vi = 0; vi < visSheets.size(); ++vi) {
            int ci = allIds.indexOf(((UIKeyframeSheet)visSheets.get((int)vi)).id);
            if (ci < 0) continue;
            canonToVis.put(ci, vi);
        }
        Replay localReplay = self.replayEditor.getReplay();
        int localReplayIdx = localReplay == null || self.getData() == null ? -1 : ((Film)self.getData()).replays.getList().indexOf(localReplay);
        for (Map.Entry<UUID, TogetherClientNetwork.CursorPosition> entry : TogetherClientNetwork.playerCursors.entrySet()) {
            Integer col;
            Map<Integer, List<UUID>> selMap;
            boolean onSameReplay;
            UUID remoteUuid = entry.getKey();
            TogetherClientNetwork.CursorPosition pos = entry.getValue();
            if (pos.editorType != 1 || pos.selectedIndices.isEmpty() || localReplayIdx < 0 || !(onSameReplay = (selMap = TogetherClientNetwork.replaySelections.get(self.getData() != null ? ((Film)self.getData()).getId() : "")) != null && selMap.containsKey(localReplayIdx) && selMap.get(localReplayIdx).contains(remoteUuid)) || (col = TogetherClientNetwork.playerColors.get(remoteUuid)) == null) continue;
            int highlightColor = Colors.setA((int)(col | 0xFF000000), (float)0.85f);
            for (int encoded : pos.selectedIndices) {
                int canonIndex = encoded >> 16 & 0xFFFF;
                int kfIndex = encoded & 0xFFFF;
                Integer visIndex = (Integer)canonToVis.get(canonIndex);
                if (visIndex == null) continue;
                highlights.merge(visIndex << 16 | kfIndex, highlightColor, (a, b) -> b);
            }
        }
        return highlights;
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

    @Inject(method={"undo"}, at={@At(value="TAIL")}, remap=false)
    private void onUndo(CallbackInfo ci) {
        UIFilmPanel self = (UIFilmPanel)(Object)this;
        if (!TogetherClientNetwork.isConnected()) {
            return;
        }
        if (self.getData() == null || self.getUndoHandler() == null) {
            return;
        }
        int idx = self.getUndoHandler().getUndoManager().getCurrentUndoIndex() + 1;
        this.syncUndoAtIndex(self, idx);
    }

    @Inject(method={"redo"}, at={@At(value="TAIL")}, remap=false)
    private void onRedo(CallbackInfo ci) {
        UIFilmPanel self = (UIFilmPanel)(Object)this;
        if (!TogetherClientNetwork.isConnected()) {
            return;
        }
        if (self.getData() == null || self.getUndoHandler() == null) {
            return;
        }
        int idx = self.getUndoHandler().getUndoManager().getCurrentUndoIndex();
        this.syncUndoAtIndex(self, idx);
    }

    @Unique
    private void syncUndoAtIndex(UIFilmPanel self, int idx) {
        List undos = self.getUndoHandler().getUndoManager().getUndos();
        if (idx < 0 || idx >= undos.size()) {
            return;
        }
        IUndo entry = (IUndo)undos.get(idx);
        String filmId = ((Film)self.getData()).getId();
        ArrayList<ValueChangeUndo> changes = new ArrayList<ValueChangeUndo>();
        if (entry instanceof CompoundUndo) {
            for (Object childObj : ((CompoundUndo)entry).getUndos()) {
                IUndo child = (IUndo)childObj;
                if (!(child instanceof ValueChangeUndo)) continue;
                ValueChangeUndo vcu = (ValueChangeUndo)child;
                changes.add(vcu);
            }
        } else if (entry instanceof ValueChangeUndo) {
            ValueChangeUndo vcu = (ValueChangeUndo)entry;
            changes.add(vcu);
        }
        for (ValueChangeUndo change : changes) {
            BaseValue value = ((Film)self.getData()).findRecursively(change.name);
            if (value == null) continue;
            TogetherClientNetwork.sendSyncData(filmId, value);
        }
    }
}

