package mchorse.bbs_together.mixin;

import java.util.ArrayList;
import java.util.HashMap;
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
import mchorse.bbs_together.BBSTogetherFix33Client;
import mchorse.bbs_together.BBSTogetherFixReplayStructure;
import mchorse.bbs_together.bbsop.BbsOpClientState;
import mchorse.bbs_together.network.TogetherClientNetwork;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
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
    private final Map<UUID, Integer> together_skinCache = new HashMap<UUID, Integer>();
    private int together_lastSelectionsSize = -1;
    private List<Replay> together_replaysBeforeStructureChange;

    /** Read-only (level 0) gate: cancel the operation when the player may not edit films.
     * Also blocks structure changes while a cooperative recording session has the player —
     * claims are addressed by replay index, and a delete/add mid-session would scramble
     * everyone's picks. */
    private static boolean together_mayEditFilm() {
        return BbsOpClientState.canEdit("film") && !mchorse.bbs_together.network.CoopClient.isInSession();
    }

    @Inject(method={"pasteReplay"}, at={@At(value="HEAD")}, remap=false)
    private void together_fix1615BeforePasteReplay(MapType mapType, CallbackInfo callbackInfo) {
        if (!together_mayEditFilm()) {
            callbackInfo.cancel();
            return;
        }
        this.together_captureReplayStructure();
    }

    @Inject(method={"pasteReplay"}, at={@At(value="TAIL")}, remap=false)
    private void together_fix133PasteReplay(MapType mapType, CallbackInfo callbackInfo) {
        this.together_sendReplayStructure();
    }

    @Inject(method={"fromModelBlock(Lmchorse/bbs_mod/blocks/entities/ModelBlockEntity;)V"}, at={@At(value="HEAD")}, remap=false)
    private void together_fix1615BeforeFromModelBlock(ModelBlockEntity modelBlockEntity, CallbackInfo callbackInfo) {
        if (!together_mayEditFilm()) {
            callbackInfo.cancel();
            return;
        }
        this.together_captureReplayStructure();
    }

    @Inject(method={"fromModelBlock(Lmchorse/bbs_mod/blocks/entities/ModelBlockEntity;)V"}, at={@At(value="TAIL")}, remap=false)
    private void together_fix133FromModelBlock(ModelBlockEntity modelBlockEntity, CallbackInfo callbackInfo) {
        this.together_sendReplayStructure();
    }

    @Inject(method={"addReplay(Lorg/joml/Vector3d;FF)V"}, at={@At(value="HEAD")}, remap=false)
    private void together_fix1615BeforeAddReplay(Vector3d vector3d, float f, float f2, CallbackInfo callbackInfo) {
        if (!together_mayEditFilm()) {
            callbackInfo.cancel();
            return;
        }
        this.together_captureReplayStructure();
    }

    @Inject(method={"addReplay(Lorg/joml/Vector3d;FF)V"}, at={@At(value="TAIL")}, remap=false)
    private void together_fix1615AddReplay(Vector3d vector3d, float f, float f2, CallbackInfo callbackInfo) {
        this.together_sendReplayStructure();
    }

    @Inject(method={"fromCamera"}, at={@At(value="HEAD")}, remap=false)
    private void together_fix1615BeforeFromCamera(int n, CallbackInfo callbackInfo) {
        if (!together_mayEditFilm()) {
            callbackInfo.cancel();
            return;
        }
        this.together_captureReplayStructure();
    }

    @Inject(method={"fromCamera"}, at={@At(value="TAIL")}, remap=false)
    private void together_fix1615FromCamera(int n, CallbackInfo callbackInfo) {
        this.together_sendReplayStructure();
    }

    @Inject(method={"dupeReplay"}, at={@At(value="HEAD")}, remap=false)
    private void together_fix1615BeforeDupeReplay(CallbackInfo callbackInfo) {
        if (!together_mayEditFilm()) {
            callbackInfo.cancel();
            return;
        }
        this.together_captureReplayStructure();
    }

    @Inject(method={"dupeReplay"}, at={@At(value="TAIL")}, remap=false)
    private void together_fix133DupeReplay(CallbackInfo callbackInfo) {
        this.together_sendReplayStructure();
    }

    @Inject(method={"removeReplay"}, at={@At(value="HEAD")}, remap=false)
    private void together_fix1615BeforeRemoveReplay(CallbackInfo callbackInfo) {
        if (!together_mayEditFilm()) {
            callbackInfo.cancel();
            return;
        }
        this.together_captureReplayStructure();
    }

    @Inject(method={"removeReplay"}, at={@At(value="TAIL")}, remap=false)
    private void together_fix133RemoveReplay(CallbackInfo callbackInfo) {
        this.together_sendReplayStructure();
    }

    // TODO: BBS 2.6 中 handleSwap 已移除，暂时禁用
    // @Inject(method={"handleSwap"}, at={@At(value="HEAD")}, remap=false)
    private void together_fix1615BeforeHandleSwap(int n, int n2, CallbackInfo callbackInfo) {
        this.together_captureReplayStructure();
    }

    // TODO: BBS 2.6 中 handleSwap 已移除，暂时禁用
    // @Inject(method={"handleSwap"}, at={@At(value="TAIL")}, remap=false)
    private void together_fix133HandleSwap(int n, int n2, CallbackInfo callbackInfo) {
        this.together_sendReplayStructure();
    }

    @Inject(method={"applyReplayCategory"}, at={@At(value="HEAD")}, cancellable=true, remap=false)
    private void together_blockApplyReplayCategory(List<Replay> list, String string, CallbackInfo callbackInfo) {
        if (!together_mayEditFilm()) {
            callbackInfo.cancel();
        }
    }

    @Inject(method={"applyReplayCategory"}, at={@At(value="TAIL")}, remap=false)
    private void together_fix133ApplyReplayCategory(List<Replay> list, String string, CallbackInfo callbackInfo) {
        BBSTogetherFix33Client.broadcastReplayStructureChanged((UIReplayList)(Object)this);
    }

    @Inject(method={"removeReplayCategory"}, at={@At(value="HEAD")}, cancellable=true, remap=false)
    private void together_blockRemoveReplayCategory(String string, CallbackInfo callbackInfo) {
        if (!together_mayEditFilm()) {
            callbackInfo.cancel();
        }
    }

    @Inject(method={"removeReplayCategory"}, at={@At(value="TAIL")}, remap=false)
    private void together_fix133RemoveReplayCategory(String string, CallbackInfo callbackInfo) {
        BBSTogetherFix33Client.broadcastReplayStructureChanged((UIReplayList)(Object)this);
    }

    @Inject(method={"openFormEditor"}, at={@At(value="HEAD")}, remap=false)
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

    // BBS More Windows 的 UIReplayListFormEditorMixin 也对 openFormEditor 里的
    // UIFormPalette.open 做 @Redirect，两个 Redirect 不能共存（后应用的一方会
    // "Scanned 0 target(s)" 直接崩游戏）。这里改成 TAIL 注入：不碰那条指令，
    // 而是在方法结束后从 target 的直接子元素里找出刚打开的 UIFormPalette，
    // 无论哪一方抢先 Redirect 都能正常拿到面板。
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
                BBSTogetherFix33Client.broadcastReplayStructureChanged(uIReplayList);
            });
        } else {
            TogetherClientNetwork.sendReplayFormLock(string, n, false);
            BBSTogetherFix33Client.broadcastReplayStructureChanged(uIReplayList);
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
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayNetworkHandler networkHandler = mc.getNetworkHandler();
        if (networkHandler == null) {
            return;
        }
        int n6 = TogetherClientNetwork.replaySelections.size();
        if (n6 != this.together_lastSelectionsSize) {
            this.together_skinCache.clear();
            this.together_lastSelectionsSize = n6;
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
            int n12 = this.together_skinCache.computeIfAbsent(uUID3, arg_0 -> this.together_getSkinTextureLambda(networkHandler, mc, arg_0));
            if (n12 < 0) continue;
            int n13 = n10 + n11;
            int n14 = n3 + 1;
            Integer n15 = TogetherClientNetwork.playerColors.get(uUID3);
            if (n15 != null) {
                uIContext.batcher.box((float)(n13 - 1), (float)(n14 - 1), (float)(n13 + n7 + 1), (float)(n14 + n7 + 1), n15 | 0xFF000000);
            }
            uIContext.batcher.texturedBox(n12, -1, (float)n13, (float)n14, (float)n7, (float)n7, 8.0f, 8.0f, 16.0f, 16.0f, 64, 64);
            uIContext.batcher.texturedBox(n12, -1, (float)n13, (float)n14, (float)n7, (float)n7, 40.0f, 8.0f, 48.0f, 16.0f, 64, 64);
            n11 += n7 + n8;
        }
    }

    private int together_getSkinTexture(ClientPlayNetworkHandler networkHandler, MinecraftClient mc, UUID uUID) {
        PlayerListEntry entry = networkHandler.getPlayerListEntry(uUID);
        if (entry == null) {
            return -1;
        }
        return mc.getTextureManager().getTexture(entry.getSkinTexture()).getGlId();
    }

    private void together_captureReplayStructure() {
        this.together_replaysBeforeStructureChange = BBSTogetherFixReplayStructure.capture((UIReplayList)(Object)this);
    }

    private void together_sendReplayStructure() {
        List<Replay> list = this.together_replaysBeforeStructureChange;
        this.together_replaysBeforeStructureChange = null;
        BBSTogetherFixReplayStructure.sendIfChanged((UIReplayList)(Object)this, list);
    }

    private Integer together_getSkinTextureLambda(ClientPlayNetworkHandler networkHandler, MinecraftClient mc, UUID uUID) {
        return this.together_getSkinTexture(networkHandler, mc, uUID);
    }
}

