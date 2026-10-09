package mchorse.bbs_together;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mchorse.bbs_mod.api.client.editor.TrackCategory;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.ui.film.UIClips;
import mchorse.bbs_mod.ui.film.UIClipsPanel;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.film.clips.UIClip;
import mchorse.bbs_mod.ui.film.clips.UICurveClip;
import mchorse.bbs_mod.ui.film.clips.UIKeyframeClip;
import mchorse.bbs_mod.ui.film.clips.UIRemapperClip;
import mchorse.bbs_mod.ui.film.replays.UIReplaysEditor;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeEditor;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.graphs.IUIKeyframeGraph;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.graphs.UIKeyframeDopeSheet;
import mchorse.bbs_mod.ui.framework.elements.utils.Batcher2D;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_together.ITogetherDopeSheet;
import mchorse.bbs_together.network.TogetherClientNetwork;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;

public final class BBSTogetherFix14 {
    public static final int EDITOR_CAMERA = 0;
    public static final int EDITOR_REPLAY = 1;
    public static final int EDITOR_ACTION = 2;
    public static final int EDITOR_CAMERA_KEYFRAMES = 10;
    public static final int EDITOR_REPLAY_PLAYER = 11;
    public static final int EDITOR_REPLAY_MODEL = 12;
    public static final int EDITOR_REPLAY_POSE = 13;
    public static final int EDITOR_CAMERA_CURVE_KEYFRAMES = 14;
    public static final int EDITOR_CAMERA_REMAPPER_KEYFRAMES = 15;
    public static final int EDITOR_REPLAY_IK = 16;
    public static final int EDITOR_REPLAY_PHYSICS = 17;
    private static final Field UI_CLIPS_PANEL_PANEL = BBSTogetherFix14.findField(UIClipsPanel.class, "panel");
    private static final Field UI_CLIPS_PANEL_EMBEDDED = BBSTogetherFix14.findField(UIClips.class, "embedded");

    private BBSTogetherFix14() {
    }

    public static int replayEditorType(UIReplaysEditor uIReplaysEditor) {
        if (uIReplaysEditor == null) {
            return 1;
        }
        TrackCategory replayCategory = uIReplaysEditor.getCategory();
        if (replayCategory == TrackCategory.REPLAY || replayCategory == TrackCategory.FORM) {
            return 12;
        }
        if (replayCategory == TrackCategory.POSE) {
            return 13;
        }
        if (replayCategory == TrackCategory.IK) {
            return 16;
        }
        if (replayCategory == TrackCategory.PHYSICS) {
            return 17;
        }
        return 11;
    }

    public static boolean isReplayEditorType(int n) {
        return n == 1 || n == 11 || n == 12 || n == 13 || n == 16 || n == 17;
    }

    public static boolean isCameraKeyframeEditorType(int n) {
        return n == 10 || n == 14 || n == 15;
    }

    public static int legacyEditorType(int n) {
        return BBSTogetherFix14.isReplayEditorType(n) ? 1 : (BBSTogetherFix14.isCameraKeyframeEditorType(n) ? 0 : n);
    }

    public static boolean editorTypesMatch(int n, int n2) {
        return n == n2;
    }

    public static boolean legacyCursorTypesMatch(int n, int n2) {
        if (BBSTogetherFix14.isReplayEditorType(n) || BBSTogetherFix14.isReplayEditorType(n2) || BBSTogetherFix14.isCameraKeyframeEditorType(n) || BBSTogetherFix14.isCameraKeyframeEditorType(n2)) {
            return false;
        }
        return BBSTogetherFix14.editorTypesMatch(n, n2);
    }

    public static boolean shouldUseClipRenderer(int n, int n2) {
        return n == n2 || n == 0 && n2 == 0 || n == 2 && n2 == 2;
    }

    public static boolean shouldUseReplaySelectionFilter(Map<Integer, List<UUID>> map, int n, UUID uUID) {
        if (map == null || !map.containsKey(n)) {
            return true;
        }
        List<UUID> list = map.get(n);
        return list == null || list.contains(uUID);
    }

    public static UIKeyframeEditor getCameraKeyframeEditor(UIFilmPanel uIFilmPanel) {
        if (uIFilmPanel == null || uIFilmPanel.cameraEditor == null) {
            return null;
        }
        return BBSTogetherFix14.getCameraKeyframeEditor(uIFilmPanel.cameraEditor);
    }

    public static UIKeyframeEditor getCameraKeyframeEditor(UIClipsPanel uIClipsPanel) {
        UIClip<?> uIClip = BBSTogetherFix14.getClipPanel(uIClipsPanel);
        if (uIClip instanceof UIKeyframeClip) {
            UIKeyframeClip clip = (UIKeyframeClip)uIClip;
            if (BBSTogetherFix14.isVisibleEmbeddedKeyframeEditor(uIClipsPanel, clip.keyframes)) {
                return clip.keyframes;
            }
        }
        if (uIClip instanceof UICurveClip) {
            UICurveClip clip = (UICurveClip)uIClip;
            if (BBSTogetherFix14.isVisibleEmbeddedKeyframeEditor(uIClipsPanel, clip.keyframes)) {
                return clip.keyframes;
            }
        }
        if (uIClip instanceof UIRemapperClip) {
            UIRemapperClip clip = (UIRemapperClip)uIClip;
            if (BBSTogetherFix14.isVisibleEmbeddedKeyframeEditor(uIClipsPanel, clip.keyframes)) {
                return clip.keyframes;
            }
        }
        return null;
    }

    public static int cameraKeyframeEditorType(UIFilmPanel uIFilmPanel) {
        if (uIFilmPanel == null || uIFilmPanel.cameraEditor == null) {
            return -1;
        }
        return BBSTogetherFix14.cameraKeyframeEditorType(uIFilmPanel.cameraEditor);
    }

    public static int cameraKeyframeEditorType(UIClipsPanel uIClipsPanel) {
        UIClip<?> uIClip = BBSTogetherFix14.getClipPanel(uIClipsPanel);
        if (uIClip instanceof UIKeyframeClip) {
            UIKeyframeClip clip = (UIKeyframeClip)uIClip;
            if (BBSTogetherFix14.isVisibleEmbeddedKeyframeEditor(uIClipsPanel, clip.keyframes)) {
                return 10;
            }
        }
        if (uIClip instanceof UICurveClip) {
            UICurveClip clip = (UICurveClip)uIClip;
            if (BBSTogetherFix14.isVisibleEmbeddedKeyframeEditor(uIClipsPanel, clip.keyframes)) {
                return 14;
            }
        }
        if (uIClip instanceof UIRemapperClip) {
            UIRemapperClip clip = (UIRemapperClip)uIClip;
            if (BBSTogetherFix14.isVisibleEmbeddedKeyframeEditor(uIClipsPanel, clip.keyframes)) {
                return 15;
            }
        }
        return -1;
    }

    public static boolean hasCameraKeyframeEditor(UIFilmPanel uIFilmPanel) {
        return BBSTogetherFix14.getCameraKeyframeEditor(uIFilmPanel) != null;
    }

    public static int currentEditorType(UIFilmPanel uIFilmPanel) {
        int n = BBSTogetherFix14.cameraKeyframeEditorType(uIFilmPanel);
        if (n >= 0) {
            return n;
        }
        if (uIFilmPanel != null && uIFilmPanel.cameraEditor != null && uIFilmPanel.cameraEditor.isVisible()) {
            return 0;
        }
        if (uIFilmPanel != null && uIFilmPanel.replayEditor != null && uIFilmPanel.replayEditor.isVisible()) {
            return BBSTogetherFix14.replayEditorType(uIFilmPanel.replayEditor);
        }
        if (uIFilmPanel != null && uIFilmPanel.actionEditor != null && uIFilmPanel.actionEditor.isVisible()) {
            return 2;
        }
        return -1;
    }

    public static boolean shouldSkipLegacyCursorRenderer(UIFilmPanel uIFilmPanel) {
        return BBSTogetherFix14.hasCameraKeyframeEditor(uIFilmPanel);
    }

    public static boolean shouldSkipLegacyPlayheadRenderer(UIFilmPanel uIFilmPanel) {
        return BBSTogetherFix14.hasCameraKeyframeEditor(uIFilmPanel);
    }

    public static void setCameraKeyframeHighlights(UIFilmPanel uIFilmPanel) {
        UIKeyframeDopeSheet uIKeyframeDopeSheet;
        UIKeyframes uIKeyframes;
        UIKeyframeEditor uIKeyframeEditor = BBSTogetherFix14.getCameraKeyframeEditor(uIFilmPanel);
        UIKeyframes uIKeyframes2 = uIKeyframes = uIKeyframeEditor == null ? null : uIKeyframeEditor.view;
        if (!BBSTogetherFix14.isCameraKeyframeEditorActive(uIFilmPanel, uIKeyframeEditor) || uIKeyframes == null) {
            return;
        }
        IUIKeyframeGraph iUIKeyframeGraph = uIKeyframes.getGraph();
        if (iUIKeyframeGraph instanceof UIKeyframeDopeSheet && (uIKeyframeDopeSheet = (UIKeyframeDopeSheet)iUIKeyframeGraph) instanceof ITogetherDopeSheet) {
            ITogetherDopeSheet iTogetherDopeSheet = (ITogetherDopeSheet)uIKeyframeDopeSheet;
            iTogetherDopeSheet.together_setExternalHighlights(BBSTogetherFix14.buildCameraKeyframeHighlights(uIFilmPanel, uIKeyframeDopeSheet));
        }
    }

    public static void clearCameraKeyframeHighlights(UIFilmPanel uIFilmPanel) {
        UIKeyframes uIKeyframes;
        UIKeyframeEditor uIKeyframeEditor = BBSTogetherFix14.getCameraKeyframeEditor(uIFilmPanel);
        UIKeyframes uIKeyframes2 = uIKeyframes = uIKeyframeEditor == null ? null : uIKeyframeEditor.view;
        if (uIKeyframes == null) {
            return;
        }
        IUIKeyframeGraph iUIKeyframeGraph = uIKeyframes.getGraph();
        if (iUIKeyframeGraph instanceof ITogetherDopeSheet) {
            ITogetherDopeSheet iTogetherDopeSheet = (ITogetherDopeSheet)iUIKeyframeGraph;
            iTogetherDopeSheet.together_setExternalHighlights(null);
        }
    }

    public static float[] tickRowFromKeyframes(UIKeyframeEditor uIKeyframeEditor, int n, int n2) {
        UIKeyframes uIKeyframes;
        UIKeyframes uIKeyframes2 = uIKeyframes = uIKeyframeEditor == null ? null : uIKeyframeEditor.view;
        if (uIKeyframes == null || !BBSTogetherFix14.areaForKeyframes(uIKeyframes).isInside(n, n2)) {
            return null;
        }
        IUIKeyframeGraph iUIKeyframeGraph = uIKeyframes.getGraph();
        if (!(iUIKeyframeGraph instanceof UIKeyframeDopeSheet)) {
            return null;
        }
        UIKeyframeDopeSheet uIKeyframeDopeSheet = (UIKeyframeDopeSheet)iUIKeyframeGraph;
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
        return new float[]{(float)uIKeyframes.fromGraphX(n), (float)n3 + f};
    }

    public static int[] screenFromKeyframes(UIKeyframeEditor uIKeyframeEditor, float f, float f2) {
        UIKeyframes uIKeyframes;
        UIKeyframes uIKeyframes2 = uIKeyframes = uIKeyframeEditor == null ? null : uIKeyframeEditor.view;
        if (uIKeyframes == null) {
            return null;
        }
        IUIKeyframeGraph iUIKeyframeGraph = uIKeyframes.getGraph();
        if (!(iUIKeyframeGraph instanceof UIKeyframeDopeSheet)) {
            return null;
        }
        UIKeyframeDopeSheet uIKeyframeDopeSheet = (UIKeyframeDopeSheet)iUIKeyframeGraph;
        return BBSTogetherFix14.screenFromKeyframes(uIKeyframes, uIKeyframeDopeSheet, f, f2);
    }

    public static int[] screenFromKeyframes(UIKeyframes uIKeyframes, UIKeyframeDopeSheet uIKeyframeDopeSheet, float f, float f2) {
        int[] nArray;
        if (uIKeyframes == null || uIKeyframeDopeSheet == null) {
            return null;
        }
        int n = (int)f2;
        float f3 = f2 - (float)n;
        List list = uIKeyframeDopeSheet.getSheets();
        if (n < 0 || n >= list.size()) {
            return null;
        }
        int n2 = uIKeyframes.toGraphX((double)f);
        int n3 = uIKeyframeDopeSheet.getDopeSheetY(n) + (int)((double)f3 * uIKeyframeDopeSheet.getTrackHeight());
        Area area = BBSTogetherFix14.areaForKeyframes(uIKeyframes);
        if (area.isInside(n2, n3)) {
            int[] nArray2 = new int[2];
            nArray2[0] = n2;
            nArray = nArray2;
            nArray2[1] = n3;
        } else {
            nArray = null;
        }
        return nArray;
    }

    public static void sendCameraKeyframeCursor(UIFilmPanel uIFilmPanel, UIContext uIContext) {
        if (uIFilmPanel == null || uIFilmPanel.getData() == null) {
            return;
        }
        UIKeyframeEditor uIKeyframeEditor = BBSTogetherFix14.getCameraKeyframeEditor(uIFilmPanel);
        int n = BBSTogetherFix14.cameraKeyframeEditorType(uIFilmPanel);
        if (n < 0 || !BBSTogetherFix14.isCameraKeyframeEditorActive(uIFilmPanel, uIKeyframeEditor)) {
            return;
        }
        float[] fArray = BBSTogetherFix14.tickRowFromKeyframes(uIKeyframeEditor, uIContext.mouseX, uIContext.mouseY);
        List<Integer> list = BBSTogetherFix14.encodeVisibleKeyframeSelection(uIKeyframeEditor);
        float f = fArray == null ? -1.0f : fArray[0];
        float f2 = fArray == null ? -1.0f : fArray[1];
        TogetherClientNetwork.sendCursorPosition(((Film)uIFilmPanel.getData()).getId(), n, f, f2, list, uIFilmPanel.getCursor());
    }

    public static void renderCameraKeyframeCursors(UIFilmPanel uIFilmPanel, UIContext uIContext) {
        if (uIFilmPanel == null || uIFilmPanel.getData() == null) {
            return;
        }
        UIKeyframeEditor uIKeyframeEditor = BBSTogetherFix14.getCameraKeyframeEditor(uIFilmPanel);
        int n = BBSTogetherFix14.cameraKeyframeEditorType(uIFilmPanel);
        if (n < 0 || !BBSTogetherFix14.isCameraKeyframeEditorActive(uIFilmPanel, uIKeyframeEditor) || TogetherClientNetwork.playerCursors.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, TogetherClientNetwork.CursorPosition> entry : TogetherClientNetwork.playerCursors.entrySet()) {
            int[] nArray;
            TogetherClientNetwork.CursorPosition cursorPosition = entry.getValue();
            if (cursorPosition.editorType != n || cursorPosition.currentTick < 0.0f || cursorPosition.currentRow < 0.0f || BBSTogetherFix14.isLocalPlayer(entry.getKey()) || (nArray = BBSTogetherFix14.screenFromKeyframes(uIKeyframeEditor, cursorPosition.currentTick, cursorPosition.currentRow)) == null) continue;
            Integer n2 = TogetherClientNetwork.playerColors.get(entry.getKey());
            int n3 = (n2 == null ? 0xFFFFFF : n2) | 0xFF000000;
            uIContext.batcher.icon(Icons.CURSOR, n3, (float)nArray[0], (float)nArray[1]);
        }
    }

    public static void renderCameraKeyframePlayheads(UIFilmPanel uIFilmPanel, UIContext uIContext) {
        UIKeyframeEditor uIKeyframeEditor = BBSTogetherFix14.getCameraKeyframeEditor(uIFilmPanel);
        UIKeyframes uIKeyframes = uIKeyframeEditor == null ? null : uIKeyframeEditor.view;
        int n = BBSTogetherFix14.cameraKeyframeEditorType(uIFilmPanel);
        if (n < 0 || !BBSTogetherFix14.isCameraKeyframeEditorActive(uIFilmPanel, uIKeyframeEditor) || uIKeyframes == null || TogetherClientNetwork.playerCursors.isEmpty()) {
            return;
        }
        Area area = BBSTogetherFix14.areaForKeyframes(uIKeyframes);
        Batcher2D batcher2D = uIContext.batcher;
        for (Map.Entry<UUID, TogetherClientNetwork.CursorPosition> entry : TogetherClientNetwork.playerCursors.entrySet()) {
            int n2;
            Integer n3;
            if (BBSTogetherFix14.isLocalPlayer(entry.getKey())) continue;
            TogetherClientNetwork.CursorPosition cursorPosition = entry.getValue();
            if (cursorPosition.editorType != n || cursorPosition.playheadTick < 0 || (n3 = TogetherClientNetwork.playerColors.get(entry.getKey())) == null || (n2 = uIKeyframes.toGraphX((double)cursorPosition.playheadTick)) < area.x || n2 > area.ex()) continue;
            batcher2D.box((float)n2, (float)area.y, (float)(n2 + 2), (float)area.ey(), Colors.setA((int)(n3 | 0xFF000000), (float)0.25f));
        }
    }

    private static Map<Integer, Integer> buildCameraKeyframeHighlights(UIFilmPanel uIFilmPanel, UIKeyframeDopeSheet uIKeyframeDopeSheet) {
        HashMap<Integer, Integer> hashMap = new HashMap<Integer, Integer>();
        if (TogetherClientNetwork.playerCursors.isEmpty() || uIFilmPanel == null || uIFilmPanel.getData() == null) {
            return hashMap;
        }
        List list = uIKeyframeDopeSheet.getSheets();
        int n3 = BBSTogetherFix14.cameraKeyframeEditorType(uIFilmPanel);
        if (n3 < 0) {
            return hashMap;
        }
        for (Map.Entry<UUID, TogetherClientNetwork.CursorPosition> entry : TogetherClientNetwork.playerCursors.entrySet()) {
            Integer n4;
            if (BBSTogetherFix14.isLocalPlayer(entry.getKey())) continue;
            TogetherClientNetwork.CursorPosition cursorPosition = entry.getValue();
            if (cursorPosition.editorType != n3 || cursorPosition.selectedIndices.isEmpty() || (n4 = TogetherClientNetwork.playerColors.get(entry.getKey())) == null) continue;
            int n5 = Colors.setA((int)(n4 | 0xFF000000), (float)0.55f);
            for (Integer n6 : cursorPosition.selectedIndices) {
                int n7 = n6 >> 16 & 0xFFFF;
                int n8 = n6 & 0xFFFF;
                if (n7 < 0 || n7 >= list.size()) continue;
                hashMap.merge(n7 << 16 | n8, n5, (n, n2) -> n2);
            }
        }
        return hashMap;
    }

    private static List<Integer> encodeVisibleKeyframeSelection(UIKeyframeEditor uIKeyframeEditor) {
        Object object;
        UIKeyframes uIKeyframes;
        UIKeyframes uIKeyframes2 = uIKeyframes = uIKeyframeEditor == null ? null : uIKeyframeEditor.view;
        if (uIKeyframes == null || !((object = uIKeyframes.getGraph()) instanceof UIKeyframeDopeSheet)) {
            return Collections.emptyList();
        }
        UIKeyframeDopeSheet uIKeyframeDopeSheet = (UIKeyframeDopeSheet)object;
        List<Integer> result = new ArrayList<Integer>();
        List list = uIKeyframeDopeSheet.getSheets();
        for (int i = 0; i < list.size(); ++i) {
            UIKeyframeSheet uIKeyframeSheet = (UIKeyframeSheet)list.get(i);
            if (uIKeyframeSheet.selection == null || uIKeyframeSheet.selection.getIndices().isEmpty()) continue;
            for (Integer n : uIKeyframeSheet.selection.getIndices()) {
                result.add(i << 16 | n & 0xFFFF);
            }
        }
        return result;
    }

    private static Area areaForKeyframes(UIKeyframes uIKeyframes) {
        return uIKeyframes.graphArea == null ? uIKeyframes.area : uIKeyframes.graphArea;
    }

    private static boolean isCameraKeyframeEditorActive(UIFilmPanel uIFilmPanel, UIKeyframeEditor uIKeyframeEditor) {
        return uIKeyframeEditor != null && uIKeyframeEditor.hasParent() && uIKeyframeEditor.canBeSeen() && uIKeyframeEditor.view != null && uIKeyframeEditor.view.hasParent() && uIKeyframeEditor.view.canBeSeen() && uIFilmPanel != null && uIFilmPanel.cameraEditor != null && BBSTogetherFix14.isVisibleEmbeddedKeyframeEditor(uIFilmPanel.cameraEditor, uIKeyframeEditor);
    }

    private static boolean isVisibleEmbeddedKeyframeEditor(UIClipsPanel uIClipsPanel, UIKeyframeEditor uIKeyframeEditor) {
        if (uIClipsPanel == null || uIClipsPanel.clips == null || uIKeyframeEditor == null || !uIKeyframeEditor.hasParent() || !uIKeyframeEditor.canBeSeen()) {
            return false;
        }
        return BBSTogetherFix14.getEmbedded(uIClipsPanel.clips) == uIKeyframeEditor;
    }

    private static UIClip<?> getClipPanel(UIClipsPanel uIClipsPanel) {
        if (uIClipsPanel == null || UI_CLIPS_PANEL_PANEL == null) {
            return null;
        }
        try {
            return (UIClip)UI_CLIPS_PANEL_PANEL.get(uIClipsPanel);
        }
        catch (Exception exception) {
            return null;
        }
    }

    private static UIElement getEmbedded(UIClips uIClips) {
        if (uIClips == null || UI_CLIPS_PANEL_EMBEDDED == null) {
            return null;
        }
        try {
            return (UIElement)UI_CLIPS_PANEL_EMBEDDED.get(uIClips);
        }
        catch (Exception exception) {
            return null;
        }
    }

    private static Field findField(Class<?> clazz, String string) {
        try {
            Field field = clazz.getDeclaredField(string);
            field.setAccessible(true);
            return field;
        }
        catch (Exception exception) {
            return null;
        }
    }

    private static boolean isLocalPlayer(UUID uUID) {
        AbstractClientPlayerEntity class_7462 = MinecraftClient.getInstance().player;
        return class_7462 != null && uUID.equals(class_7462.getUuid());
    }
}

