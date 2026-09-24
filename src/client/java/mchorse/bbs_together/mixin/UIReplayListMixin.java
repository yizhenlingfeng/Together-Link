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
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.settings.values.core.ValueForm;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.film.replays.ReplayListEntry;
import mchorse.bbs_mod.ui.film.replays.UIReplayList;
import mchorse.bbs_mod.ui.forms.UIFormPalette;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.events.UIRemovedEvent;
import mchorse.bbs_together.BBSTogetherFix33Client;
import mchorse.bbs_together.BBSTogetherFixReplayStructure;
import mchorse.bbs_together.network.TogetherClientNetwork;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.render.Tessellator;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value={UIReplayList.class}, remap=false)
public class UIReplayListMixin {
    private String together_lockFilmId = null;
    private int together_lockIndex = -1;
    private final Map<UUID, Integer> together_skinCache = new HashMap<UUID, Integer>();
    private int together_lastSelectionsSize = -1;
    private List<Replay> together_replaysBeforeStructureChange;

    @Inject(method={"pasteReplay"}, at={@At(value="HEAD")}, remap=false)
    private void together_fix1615BeforePasteReplay(MapType mapType, CallbackInfo callbackInfo) {
        this.together_captureReplayStructure();
    }

    @Inject(method={"pasteReplay"}, at={@At(value="TAIL")}, remap=false)
    private void together_fix133PasteReplay(MapType mapType, CallbackInfo callbackInfo) {
        this.together_sendReplayStructure();
    }

    @Inject(method={"fromModelBlock(Lmchorse/bbs_mod/blocks/entities/ModelBlockEntity;)V"}, at={@At(value="HEAD")}, remap=false)
    private void together_fix1615BeforeFromModelBlock(ModelBlockEntity modelBlockEntity, CallbackInfo callbackInfo) {
        this.together_captureReplayStructure();
    }

    @Inject(method={"fromModelBlock(Lmchorse/bbs_mod/blocks/entities/ModelBlockEntity;)V"}, at={@At(value="TAIL")}, remap=false)
    private void together_fix133FromModelBlock(ModelBlockEntity modelBlockEntity, CallbackInfo callbackInfo) {
        this.together_sendReplayStructure();
    }

    @Inject(method={"addReplay(Lorg/joml/Vector3d;FF)V"}, at={@At(value="HEAD")}, remap=false)
    private void together_fix1615BeforeAddReplay(Vector3d vector3d, float f, float f2, CallbackInfo callbackInfo) {
        this.together_captureReplayStructure();
    }

    @Inject(method={"addReplay(Lorg/joml/Vector3d;FF)V"}, at={@At(value="TAIL")}, remap=false)
    private void together_fix1615AddReplay(Vector3d vector3d, float f, float f2, CallbackInfo callbackInfo) {
        this.together_sendReplayStructure();
    }

    @Inject(method={"fromCamera"}, at={@At(value="HEAD")}, remap=false)
    private void together_fix1615BeforeFromCamera(int n, CallbackInfo callbackInfo) {
        this.together_captureReplayStructure();
    }

    @Inject(method={"fromCamera"}, at={@At(value="TAIL")}, remap=false)
    private void together_fix1615FromCamera(int n, CallbackInfo callbackInfo) {
        this.together_sendReplayStructure();
    }

    @Inject(method={"dupeReplay"}, at={@At(value="HEAD")}, remap=false)
    private void together_fix1615BeforeDupeReplay(CallbackInfo callbackInfo) {
        this.together_captureReplayStructure();
    }

    @Inject(method={"dupeReplay"}, at={@At(value="TAIL")}, remap=false)
    private void together_fix133DupeReplay(CallbackInfo callbackInfo) {
        this.together_sendReplayStructure();
    }

    @Inject(method={"removeReplay"}, at={@At(value="HEAD")}, remap=false)
    private void together_fix1615BeforeRemoveReplay(CallbackInfo callbackInfo) {
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

    @Inject(method={"applyReplayCategory"}, at={@At(value="TAIL")}, remap=false)
    private void together_fix133ApplyReplayCategory(List<Replay> list, String string, CallbackInfo callbackInfo) {
        BBSTogetherFix33Client.broadcastReplayStructureChanged((UIReplayList)(Object)this);
    }

    @Inject(method={"removeReplayCategory"}, at={@At(value="TAIL")}, remap=false)
    private void together_fix133RemoveReplayCategory(String string, CallbackInfo callbackInfo) {
        BBSTogetherFix33Client.broadcastReplayStructureChanged((UIReplayList)(Object)this);
    }

    @Inject(method={"openFormEditor"}, at={@At(value="HEAD")}, remap=false)
    private void onOpenFormEditorHead(ValueForm valueForm, boolean bl, Consumer<?> consumer, CallbackInfo callbackInfo) {
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

    @Redirect(method={"openFormEditor"}, at=@At(value="INVOKE", target="Lmchorse/bbs_mod/ui/forms/UIFormPalette;open(Lmchorse/bbs_mod/ui/framework/elements/UIElement;ZLmchorse/bbs_mod/forms/forms/Form;Ljava/util/function/Consumer;)Lmchorse/bbs_mod/ui/forms/UIFormPalette;"), remap=false)
    private UIFormPalette redirectFormPaletteOpen(UIElement uIElement, boolean bl, Form form, Consumer<Form> consumer) {
        UIFormPalette uIFormPalette = UIFormPalette.open((UIElement)uIElement, (boolean)bl, (Form)form, consumer);
        if (!TogetherClientNetwork.isConnected()) {
            return uIFormPalette;
        }
        if (this.together_lockFilmId == null || this.together_lockIndex < 0) {
            return uIFormPalette;
        }
        String string = this.together_lockFilmId;
        int n = this.together_lockIndex;
        this.together_lockFilmId = null;
        this.together_lockIndex = -1;
        if (uIFormPalette != null) {
            uIFormPalette.getEvents().register(UIRemovedEvent.class, uIRemovedEvent -> {
                TogetherClientNetwork.sendReplayFormLock(string, n, false);
                BBSTogetherFix33Client.broadcastReplayStructureChanged((UIReplayList)(Object)this);
            });
        } else {
            TogetherClientNetwork.sendReplayFormLock(string, n, false);
            BBSTogetherFix33Client.broadcastReplayStructureChanged((UIReplayList)(Object)this);
        }
        return uIFormPalette;
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

