package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.dashboard.panels.UIDataDashboardPanel;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_together.BBSTogetherFix19;
import mchorse.bbs_together.BBSTogetherFix28Client;
import mchorse.bbs_together.BBSTogetherFix33Client;
import mchorse.bbs_together.network.TogetherClientNetwork;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(value=EnvType.CLIENT)
@Mixin(value={UIDataDashboardPanel.class}, remap=false)
public class UIFilmPanelSaveSyncMixin {
    @Unique
    private boolean together_periodicSaveInProgress;

    @Inject(method={"checkPeriodicSave"}, at={@At(value="HEAD")}, remap=false)
    private void onCheckPeriodicHead(UIContext uIContext, CallbackInfo callbackInfo) {
        this.together_periodicSaveInProgress = true;
    }

    @Inject(method={"checkPeriodicSave"}, at={@At(value="TAIL")}, remap=false)
    private void onCheckPeriodicTail(UIContext uIContext, CallbackInfo callbackInfo) {
        this.together_periodicSaveInProgress = false;
    }

    @Redirect(method={"checkPeriodicSave"}, at=@At(value="INVOKE", target="Lmchorse/bbs_mod/ui/framework/UIContext;notifySuccess(Lmchorse/bbs_mod/l10n/keys/IKey;)V", remap=false), remap=false)
    private void redirectNotifySuccess(UIContext uIContext, IKey iKey) {
        uIContext.notifySuccess(iKey);
    }

    @Inject(method={"forceSave"}, at={@At(value="TAIL")}, remap=false)
    private void onForceSave(CallbackInfo callbackInfo) {
        if (!TogetherClientNetwork.isConnected()) {
            return;
        }
        if (!((Object)this instanceof UIFilmPanel)) {
            return;
        }
        UIFilmPanel panel = (UIFilmPanel)(Object)this;
        Film film = (Film)panel.getData();
        if (film == null) {
            return;
        }
        if (this.together_periodicSaveInProgress) {
            BBSTogetherFix33Client.writePeriodicClientBackup(film);
            return;
        }
        if (TogetherClientNetwork.recordingSessionActive) {
            return;
        }
        if (BBSTogetherFix19.shouldSuppressFullFilmSaveAfterRecording()) {
            return;
        }
        if (BBSTogetherFix28Client.consumeCloseSaveBroadcastSuppressed(film.getId())) {
            return;
        }
        TogetherClientNetwork.sendFilmSaveSync(film.getId(), film);
    }
}

