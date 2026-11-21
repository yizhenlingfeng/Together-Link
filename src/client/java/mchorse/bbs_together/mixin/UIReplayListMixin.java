package mchorse.bbs_together.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import mchorse.bbs_mod.blocks.entities.ModelBlockEntity;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.settings.values.core.ValueForm;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.film.replays.ReplayListEntry;
import mchorse.bbs_mod.ui.film.replays.UIReplayList;
import mchorse.bbs_mod.ui.forms.UIFormPalette;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.IUIElement;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.events.UIRemovedEvent;
import mchorse.bbs_together.ReplayStructureRecovery;
import mchorse.bbs_together.bbsop.BbsOpClientState;
import mchorse.bbs_together.network.TogetherClientNetwork;
import mchorse.bbs_together.ui.UIPlayerFaceRenderer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replay list collaboration sync (structure broadcast, form-editor lock, selection heads)
 * merged with the read-only (level 0) permission lock: adding, duplicating, removing,
 * pasting, importing from camera/model-block, editing category and opening the form
 * editor are all short-circuited for players who may not edit films. Selecting and
 * browsing replays stays available. The server still refuses the resulting packets,
 * so the permission check here is just the client-side UX.
 */
@Environment(EnvType.CLIENT)
@Mixin(value={UIReplayList.class}, remap=false)
public class UIReplayListMixin {
    private String together_lockFilmId = null;
    private int together_lockIndex = -1;
    private List<Replay> together_replaysBeforeStructureChange;

    /** Read-only (level 0) gate: cancel the operation when the player may not edit films.
     * Also blocks structure changes while a cooperative recording session has the player —
     * claims are addressed by replay index, and a delete/add mid-session would scramble
     * everyone's picks. */
    private static boolean together_mayEditFilm() {
        return BbsOpClientState.canEdit("film") && !mchorse.bbs_together.network.CoopClient.isInSession();
    }

    @Inject(method={"pasteReplay"}, at={@At(value="HEAD")}, cancellable=true, remap=false)
    private void togetherBeforePasteReplay(MapType mapType, CallbackInfo callbackInfo) {
        if (!together_mayEditFilm()) {
            callbackInfo.cancel();
            return;
        }
        this.together_captureReplayStructure();
    }

    @Inject(method={"pasteReplay"}, at={@At(value="TAIL")}, remap=false)
    private void togetherPasteReplay(MapType mapType, CallbackInfo callbackInfo) {
        this.together_sendReplayStructure();
    }

    @Inject(method={"fromModelBlock(Lmchorse/bbs_mod/blocks/entities/ModelBlockEntity;)V"}, at={@At(value="HEAD")}, cancellable=true, remap=false)
    private void togetherBeforeFromModelBlock(ModelBlockEntity modelBlockEntity, CallbackInfo callbackInfo) {
        if (!together_mayEditFilm()) {
            callbackInfo.cancel();
            return;
        }
        this.together_captureReplayStructure();
    }

    @Inject(method={"fromModelBlock(Lmchorse/bbs_mod/blocks/entities/ModelBlockEntity;)V"}, at={@At(value="TAIL")}, remap=false)
    private void togetherFromModelBlock(ModelBlockEntity modelBlockEntity, CallbackInfo callbackInfo) {
        this.together_sendReplayStructure();
    }

    @Inject(method={"addReplay(Lorg/joml/Vector3d;FF)V"}, at={@At(value="HEAD")}, cancellable=true, remap=false)
    private void togetherBeforeAddReplay(Vector3d vector3d, float f, float f2, CallbackInfo callbackInfo) {
        if (!together_mayEditFilm()) {
            callbackInfo.cancel();
            return;
        }
        this.together_captureReplayStructure();
    }

    @Inject(method={"addReplay(Lorg/joml/Vector3d;FF)V"}, at={@At(value="TAIL")}, remap=false)
    private void togetherAddReplay(Vector3d vector3d, float f, float f2, CallbackInfo callbackInfo) {
        this.together_sendReplayStructure();
    }

    @Inject(method={"fromCamera"}, at={@At(value="HEAD")}, cancellable=true, remap=false)
    private void togetherBeforeFromCamera(int n, CallbackInfo callbackInfo) {
        if (!together_mayEditFilm()) {
            callbackInfo.cancel();
            return;
        }
        this.together_captureReplayStructure();
    }

    @Inject(method={"fromCamera"}, at={@At(value="TAIL")}, remap=false)
    private void togetherFromCamera(int n, CallbackInfo callbackInfo) {
        this.together_sendReplayStructure();
    }

    @Inject(method={"dupeReplay"}, at={@At(value="HEAD")}, cancellable=true, remap=false)
    private void togetherBeforeDupeReplay(CallbackInfo callbackInfo) {
        if (!together_mayEditFilm()) {
            callbackInfo.cancel();
            return;
        }
        this.together_captureReplayStructure();
    }

    @Inject(method={"dupeReplay"}, at={@At(value="TAIL")}, remap=false)
    private void togetherDupeReplay(CallbackInfo callbackInfo) {
        this.together_sendReplayStructure();
    }

    @Inject(method={"removeReplay"}, at={@At(value="HEAD")}, cancellable=true, remap=false)
    private void togetherBeforeRemoveReplay(CallbackInfo callbackInfo) {
        if (!together_mayEditFilm()) {
            callbackInfo.cancel();
            return;
        }
        this.together_captureReplayStructure();
    }

    @Inject(method={"removeReplay"}, at={@At(value="TAIL")}, remap=false)
    private void togetherRemoveReplay(CallbackInfo callbackInfo) {
        this.together_sendReplayStructure();
    }

    @Inject(method={"applyReplayCategory"}, at={@At(value="HEAD")}, cancellable=true, remap=false)
    private void together_blockApplyReplayCategory(List<Replay> list, String string, CallbackInfo callbackInfo) {
        this.together_captureReplayStructure();
        if (!together_mayEditFilm()) {
            callbackInfo.cancel();
        }
    }

    @Inject(method={"applyReplayCategory"}, at={@At(value="TAIL")}, remap=false)
    private void togetherApplyReplayCategory(List<Replay> list, String string, CallbackInfo callbackInfo) {
        this.together_sendReplayStructure();
    }

    @Inject(method={"removeReplayCategory"}, at={@At(value="HEAD")}, cancellable=true, remap=false)
    private void together_blockRemoveReplayCategory(String string, CallbackInfo callbackInfo) {
        this.together_captureReplayStructure();
        if (!together_mayEditFilm()) {
            callbackInfo.cancel();
        }
    }

    @Inject(method={"removeReplayCategory"}, at={@At(value="TAIL")}, remap=false)
    private void togetherRemoveReplayCategory(String string, CallbackInfo callbackInfo) {
        this.together_sendReplayStructure();
    }

    @Inject(method={"openFormEditor"}, at={@At(value="HEAD")}, cancellable=true, remap=false)
    private void onOpenFormEditorHead(ValueForm valueForm, boolean bl, Consumer<?> consumer, CallbackInfo callbackInfo) {
        if (!together_mayEditFilm()) {
            callbackInfo.cancel();
            return;
        }
        this.together_lockFilmId = null;
        this.together_lockIndex = -1;
        if (!TogetherClientNetwork.isConnected()) {
            return;
        }
        UIReplayList uIReplayList = (UIReplayList)(Object)this;
        UIFilmPanel uIFilmPanel = uIReplayList.panel;
        if (uIFilmPanel == null || uIFilmPanel.getData() == null) {
            return;
        }
        List list = uIReplayList.getCurrent();
        if (list.isEmpty()) {
            return;
        }
        ReplayListEntry replayListEntry = (ReplayListEntry)list.get(0);
        if (replayListEntry == null || !replayListEntry.isReplay() || replayListEntry.replay == null) {
            return;
        }
        String string = ((Film)uIFilmPanel.getData()).getId();
        int n = ((Film)uIFilmPanel.getData()).replays.getList().indexOf(replayListEntry.replay);
        if (n < 0) {
            return;
        }
        this.together_lockFilmId = string;
        this.together_lockIndex = n;
        TogetherClientNetwork.sendReplayFormLock(string, n, true);
    }

    // BBS More Windows' UIReplayListFormEditorMixin also @Redirects UIFormPalette.open
    // inside openFormEditor. Two Redirects cannot coexist (the one applied later fails
    // with "Scanned 0 target(s)" and crashes the game). This uses a TAIL injection
    // instead: it never touches that instruction, but finds the UIFormPalette the
    // method just opened among the target's direct children, so the panel is reached
    // no matter which side wins the Redirect.
    @Inject(method={"openFormEditor"}, at={@At(value="TAIL")}, remap=false)
    private void onOpenFormEditorTail(ValueForm valueForm, boolean bl, Consumer<?> consumer, CallbackInfo callbackInfo) {
        if (!TogetherClientNetwork.isConnected()) {
            return;
        }
        if (this.together_lockFilmId == null || this.together_lockIndex < 0) {
            return;
        }
        String string = this.together_lockFilmId;
        int n = this.together_lockIndex;
        this.together_lockFilmId = null;
        this.together_lockIndex = -1;
        UIReplayList uIReplayList = (UIReplayList)(Object)this;
        UIFormPalette uIFormPalette = this.together_findOpenedPalette(uIReplayList);
        if (uIFormPalette != null) {
            uIFormPalette.getEvents().register(UIRemovedEvent.class, uIRemovedEvent -> {
                TogetherClientNetwork.sendReplayFormLock(string, n, false);
            });
        } else {
            TogetherClientNetwork.sendReplayFormLock(string, n, false);
        }
    }

    private UIFormPalette together_findOpenedPalette(UIReplayList uIReplayList) {
        try {
            UIElement uIElement = uIReplayList.panel;
            if (uIReplayList.getRoot() != null) {
                uIElement = uIReplayList.getParentContainer();
            }
            if (uIElement == null) {
                return null;
            }
            UIFormPalette found = null;
            for (IUIElement child : uIElement.getChildren()) {
                if (child instanceof UIFormPalette) {
                    found = (UIFormPalette)child;
                }
            }
            return found;
        } catch (Throwable throwable) {
            return null;
        }
    }

    @Inject(method={"renderElementPart"}, at={@At(value="TAIL")}, remap=false)
    private void onRenderElementPartTail(UIContext uIContext, ReplayListEntry replayListEntry, int n, int n2, int n3, boolean bl, boolean bl2, CallbackInfo callbackInfo) {
        int n4;
        UUID uUID2;
        if (replayListEntry == null || !replayListEntry.isReplay()) {
            return;
        }
        Replay replay = replayListEntry.replay;
        if (replay == null || !TogetherClientNetwork.isConnected()) {
            return;
        }
        UIReplayList uIReplayList = (UIReplayList)(Object)this;
        if (uIReplayList.panel == null || uIReplayList.panel.getData() == null) {
            return;
        }
        String string = ((Film)uIReplayList.panel.getData()).getId();
        int n5 = ((Film)uIReplayList.panel.getData()).replays.getList().indexOf(replay);
        if (n5 < 0) {
            return;
        }
        Map<Integer, List<UUID>> map = TogetherClientNetwork.replaySelections.get(string);
        if (map == null) {
            return;
        }
        List<UUID> list = map.get(n5);
        if (list == null || list.isEmpty()) {
            return;
        }
        ArrayList<UUID> arrayList = new ArrayList<UUID>();
        for (UUID uUID2Temp : list) {
            String string2 = TogetherClientNetwork.filmSelections.get(uUID2Temp);
            if (!string.equals(string2)) continue;
            arrayList.add(uUID2Temp);
        }
        if (arrayList.isEmpty()) {
            return;
        }
        if ((n4 = arrayList.size()) <= 0) {
            return;
        }
        int n7 = 10;
        int n8 = 1;
        int n9 = n4 * n7 + (n4 - 1) * n8;
        int n10 = uIReplayList.area.ex() - n9 - 2;
        if (replay.form.get() != null) {
            n10 -= 35;
        }
        int n11 = 0;
        for (UUID uUID3 : arrayList) {
            int n12 = n10 + n11;
            int n13 = n3 + 1;
            Integer n14 = TogetherClientNetwork.playerColors.get(uUID3);
            if (n14 != null) {
                uIContext.batcher.box((float)(n12 - 1), (float)(n13 - 1), (float)(n12 + n7 + 1), (float)(n13 + n7 + 1), n14 | 0xFF000000);
            }
            /* Shared face renderer: it copes with a player whose skin is unavailable — resolving
             * a null skin identifier through the texture manager used to crash the client. */
            UIPlayerFaceRenderer.draw(uIContext.batcher, TogetherClientNetwork.playerName(uUID3), uUID3, n12, n13, n7);
            n11 += n7 + n8;
        }
    }

    private void together_captureReplayStructure() {
        this.together_replaysBeforeStructureChange = ReplayStructureRecovery.capture((UIReplayList)(Object)this);
    }

    private void together_sendReplayStructure() {
        List<Replay> list = this.together_replaysBeforeStructureChange;
        this.together_replaysBeforeStructureChange = null;
        ReplayStructureRecovery.sendIfChanged((UIReplayList)(Object)this, list);
    }
}

