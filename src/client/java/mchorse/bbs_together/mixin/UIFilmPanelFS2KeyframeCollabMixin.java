package mchorse.bbs_together.mixin;

import java.lang.invoke.StringConcatFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.graphs.IUIKeyframeGraph;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.graphs.UIKeyframeDopeSheet;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_together.BBSTogetherFix14;
import mchorse.bbs_together.BBSTogetherFix28Client;
import mchorse.bbs_together.BBSTogetherFix45;
import mchorse.bbs_together.ITogetherDopeSheet;
import mchorse.bbs_together.ITogetherReplaysEditor;
import mchorse.bbs_together.network.TogetherClientNetwork;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value={UIFilmPanel.class}, priority=500, remap=false)
public class UIFilmPanelFS2KeyframeCollabMixin {
    @Unique
    private static final long TOGETHER_FS2_REPLAY_CURSOR_INTERVAL_MS = 33L;
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

    // TODO: BBS 2.6 中 save() 方法继承自父类，Mixin 注入失败，暂时禁用
    // @Inject(method={"save"}, at={@At(value="HEAD")}, remap=false)
    private void together_fs2Close(CallbackInfo callbackInfo) {
        if (!TogetherClientNetwork.isConnected()) {
            return;
        }
        UIFilmPanel uIFilmPanel = (UIFilmPanel)(Object)this;
        if (uIFilmPanel.getData() == null) {
            return;
        }
        String string = ((Film)uIFilmPanel.getData()).getId();
        Film film = (Film)uIFilmPanel.getData();
        BBSTogetherFix28Client.sendFilmLeaveSnapshot(string, film);
        BBSTogetherFix28Client.markCloseSaveBroadcastSuppressed(string);
        BBSTogetherFix28Client.sendFilmJoinRequest("");
        TogetherClientNetwork.sendReplaySelection(string, -1);
        TogetherClientNetwork.sendCursorPosition(string, -1, -1.0f, -1.0f, Collections.emptyList(), uIFilmPanel.getCursor());
        TogetherClientNetwork.sendFilmSelection("");
        this.together_fs2LastSentReplayFilmId = "";
        this.together_fs2LastSentReplayIndex = Integer.MIN_VALUE;
        this.together_fs2LastJoinFilmId = "";
    }

    @Inject(method={"render"}, at={@At(value="HEAD")}, remap=false)
    private void together_fs2RenderHead(UIContext uIContext, CallbackInfo callbackInfo) {
        UIFilmPanel uIFilmPanel = (UIFilmPanel)(Object)this;
        if (TogetherClientNetwork.isConnected() && uIFilmPanel.getData() != null && BBSTogetherFix14.hasCameraKeyframeEditor(uIFilmPanel)) {
            BBSTogetherFix14.setCameraKeyframeHighlights(uIFilmPanel);
            return;
        }
        UIKeyframeDopeSheet uIKeyframeDopeSheet = UIFilmPanelFS2KeyframeCollabMixin.together_fs2GetDopeSheet(uIFilmPanel);
        if (uIKeyframeDopeSheet == null || !(uIKeyframeDopeSheet instanceof ITogetherDopeSheet)) {
            return;
        }
        ITogetherDopeSheet iTogetherDopeSheet = (ITogetherDopeSheet)uIKeyframeDopeSheet;
        iTogetherDopeSheet.together_setExternalHighlights(UIFilmPanelFS2KeyframeCollabMixin.together_fs2BuildHighlights(uIFilmPanel, uIKeyframeDopeSheet));
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
        UIKeyframeDopeSheet uIKeyframeDopeSheet = UIFilmPanelFS2KeyframeCollabMixin.together_fs2GetDopeSheet(uIFilmPanel);
        if (uIKeyframeDopeSheet == null) {
            return;
        }
        UIFilmPanelFS2KeyframeCollabMixin.together_fs2RenderRemoteCursors(uIFilmPanel, uIContext, uIKeyframeDopeSheet);
        List<Integer> list = UIFilmPanelFS2KeyframeCollabMixin.together_fs2EncodeSelection(uIFilmPanel, uIKeyframeDopeSheet);
        float[] fArray = UIFilmPanelFS2KeyframeCollabMixin.together_fs2TickRowFromKeyframes(uIFilmPanel, uIFilmPanel.replayEditor.keyframeEditor.view, uIKeyframeDopeSheet, uIContext.mouseX, uIContext.mouseY);
        boolean bl2 = fArray != null;
        long l = System.currentTimeMillis();
        int n = uIFilmPanel.getCursor();
        boolean bl3 = n != this.together_fs2LastReplayCursor;
        boolean bl4 = bl = !list.equals(this.together_fs2LastSelection);
        if (!(bl2 || bl || bl3 || l - this.together_fs2LastSelectionSent >= 250L)) {
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
                int n2 = UIFilmPanelFS2KeyframeCollabMixin.together_fs2AllSheetIndex(uIFilmPanel, list, i);
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
                int n7 = UIFilmPanelFS2KeyframeCollabMixin.together_fs2VisibleSheetIndex(uIFilmPanel, list, n6 >> 16 & 0xFFFF);
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
        int n5 = UIFilmPanelFS2KeyframeCollabMixin.together_fs2AllSheetIndex(uIFilmPanel, list, n3);
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
            if (!BBSTogetherFix14.editorTypesMatch(n2, cursorPosition.editorType) || cursorPosition.currentTick < 0.0f || cursorPosition.currentRow < 0.0f || !BBSTogetherFix14.shouldUseReplaySelectionFilter(map, n, entry.getKey()) || (n3 = UIFilmPanelFS2KeyframeCollabMixin.together_fs2VisibleSheetIndex(uIFilmPanel, uIKeyframeDopeSheet.getSheets(), (int)cursorPosition.currentRow)) < 0 || (nArray = BBSTogetherFix14.screenFromKeyframes(uIFilmPanel.replayEditor.keyframeEditor.view, uIKeyframeDopeSheet, cursorPosition.currentTick, f = (float)n3 + (cursorPosition.currentRow - (float)((int)cursorPosition.currentRow)))) == null) continue;
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
            if (!string.equals(UIFilmPanelFS2KeyframeCollabMixin.together_fs2SheetKey(list.get(i)))) continue;
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
        int n2 = allIds2.indexOf(UIFilmPanelFS2KeyframeCollabMixin.together_fs2SheetKey(list.get(n)));
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
            // empty catch block
        }
        return uIKeyframeSheet.id + "\u0002" + string + "\u0002" + uIKeyframeSheet.isBoneTrack;
    }
}

