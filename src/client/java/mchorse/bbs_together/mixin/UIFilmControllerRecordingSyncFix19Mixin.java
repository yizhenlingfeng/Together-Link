package mchorse.bbs_together.mixin;

import java.util.List;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.film.controller.UIFilmController;
import mchorse.bbs_together.BBSTogetherFix19;
import mchorse.bbs_together.BBSTogetherFix28Client;
import mchorse.bbs_together.BBSTogetherFix45;
import mchorse.bbs_together.bbsop.BbsOpClientState;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(value=EnvType.CLIENT)
@Mixin(value={UIFilmController.class}, remap=false)
public abstract class UIFilmControllerRecordingSyncFix19Mixin {
    @Shadow
    @Final
    public UIFilmPanel panel;
    @Unique
    private boolean together_wasRecording;
    @Unique
    private Replay together_recordingReplay;
    @Unique
    private int together_recordingReplayIndex = -1;
    @Unique
    private BaseType together_oldKeyframesData;
    @Unique
    private BaseType together_oldPropertiesData;
    @Unique
    private BaseType together_oldActionsData;

    @Shadow
    public abstract boolean isRecording();

    @Inject(method={"startRecording"}, at={@At(value="HEAD")}, remap=false)
    private void onStartRecordingHead(List<String> list, CallbackInfo callbackInfo) {
        if (this.panel == null || this.panel.getData() == null) {
            return;
        }
        Film film = (Film)this.panel.getData();
        BBSTogetherFix28Client.sendRecordingBaselineSnapshot(film.getId(), film);
    }

    @Inject(method={"stopRecording"}, at={@At(value="HEAD")}, remap=false)
    private void onStopRecordingHead(CallbackInfo callbackInfo) {
        Replay replay;
        this.together_wasRecording = this.isRecording();
        this.together_recordingReplay = null;
        this.together_recordingReplayIndex = -1;
        this.together_oldKeyframesData = null;
        this.together_oldPropertiesData = null;
        this.together_oldActionsData = null;
        if (!this.together_wasRecording || ((UIFilmController)(Object)this).getRecordingCountdown() > 0 || this.panel == null || this.panel.getData() == null) {
            return;
        }
        Replay replay2 = replay = this.panel.replayEditor == null ? null : this.panel.replayEditor.getReplay();
        if (replay == null) {
            return;
        }
        Film film = (Film)this.panel.getData();
        int n = BBSTogetherFix45.identityReplayIndex(film.replays.getList(), replay);
        if (n < 0) {
            return;
        }
        this.together_recordingReplay = replay;
        this.together_recordingReplayIndex = n;
        this.together_oldActionsData = replay.actions.toData();
        if (((UIFilmController)(Object)this).getRecordingGroups() != null && ((UIFilmController)(Object)this).getRecordingGroups().contains("transform")) {
            this.together_oldPropertiesData = replay.properties.toData();
        } else {
            this.together_oldKeyframesData = replay.keyframes.toData();
        }
    }

    @Inject(method={"stopRecording"}, at={@At(value="TAIL")}, remap=false)
    private void onStopRecordingTail(CallbackInfo callbackInfo) {
        if (!this.together_wasRecording || ((UIFilmController)(Object)this).getRecordingCountdown() > 0 || this.panel == null || this.panel.getData() == null) {
            return;
        }
        Replay replay = this.together_recordingReplay;
        if (replay == null) {
            return;
        }
        Film film = (Film)this.panel.getData();
        BBSTogetherFix19.sendRecordingDelta(film, this.together_recordingReplayIndex, replay, this.together_oldKeyframesData, this.together_oldPropertiesData, this.together_oldActionsData);
    }

    @Inject(method = "toggleControl", at = @At("HEAD"), cancellable = true, remap = false)
    private void together$blockToggleControl(CallbackInfo ci)
    {
        if (!BbsOpClientState.canEdit("film"))
        {
            ci.cancel();
        }
    }

    @Inject(method = "pickRecording", at = @At("HEAD"), cancellable = true, remap = false)
    private void together$blockPickRecording(CallbackInfo ci)
    {
        if (!BbsOpClientState.canEdit("film") || !BbsOpClientState.canEdit("record"))
        {
            ci.cancel();
        }
    }
}

