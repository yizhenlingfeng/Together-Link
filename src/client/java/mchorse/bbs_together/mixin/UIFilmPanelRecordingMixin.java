package mchorse.bbs_together.mixin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.Recorder;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.film.replays.tracks.TrackId;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.utils.CollectionUtils;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_together.network.TogetherClientNetwork;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(value=EnvType.CLIENT)
@Mixin(value={UIFilmPanel.class}, remap=false)
public class UIFilmPanelRecordingMixin {
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
}

