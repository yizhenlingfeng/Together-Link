package mchorse.bbs_together.mixin;

import java.util.List;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.Recorder;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_together.BBSTogetherFix19;
import mchorse.bbs_together.network.TogetherClientNetwork;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(value=EnvType.CLIENT)
@Mixin(value={UIFilmPanel.class}, remap=false)
public class UIFilmPanelRecordingSyncFix19Mixin {
    @Inject(method={"applyRecordedKeyframes"}, at={@At(value="TAIL")}, remap=false)
    private void onApplyRecordedKeyframes(Recorder recorder, Film film, CallbackInfo callbackInfo) {
        if (recorder == null || recorder.hasNotStarted()) {
            return;
        }
        if (!TogetherClientNetwork.recordingSessionActive) {
            return;
        }
        BBSTogetherFix19.sendRecordingFinished(film, recorder);
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
}

