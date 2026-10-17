package mchorse.bbs_together;

import java.util.List;
import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.FormProperties;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.film.replays.ReplayKeyframes;
import mchorse.bbs_mod.film.replays.tracks.TrackId;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.utils.clips.Clips;
import mchorse.bbs_mod.utils.factory.IFactory;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_together.commits.FilmBranchStore;
import mchorse.bbs_together.network.TogetherServerNetwork;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.MinecraftServer;

public final class BBSTogetherFix18 {
    private BBSTogetherFix18() {
    }

    public static void mergeRecordingFinished(String string, int n, BaseType baseType, MinecraftServer minecraftServer) {
        mergeRecordingFinished(string, n, baseType, minecraftServer, FilmBranchStore.defaultBranch(string));
    }

    public static void mergeRecordingFinished(String string, int n, BaseType baseType, MinecraftServer minecraftServer, String branch) {
        Film film = FilmBranchStore.working(string, branch);
        if (film == null || baseType == null || !baseType.isMap()) {
            return;
        }
        List list = film.replays.getList();
        if (n < 0 || n >= list.size()) {
            return;
        }
        BBSTogetherFix18.mergeReplayData((Replay)list.get(n), baseType.asMap());
        MapType mapType = (MapType)film.toData();
        FilmBranchStore.setWorking(string, branch, mapType);
        FilmBranchStore.save(string, branch);
        TogetherServerNetwork.broadcastFilmSaved(minecraftServer, string, branch, mapType);
    }

    public static void mergeReplayData(Replay replay, MapType mapType) {
        if (replay == null || mapType == null) {
            return;
        }
        if (mapType.has("keyframes")) {
            BBSTogetherFix18.mergeReplayKeyframes(replay.keyframes, mapType.get("keyframes"));
        }
        if (mapType.has("properties")) {
            BBSTogetherFix18.mergeFormProperties(replay.properties, mapType.get("properties"));
        }
        if (mapType.has("actions")) {
            BBSTogetherFix18.mergeActions(replay.actions, mapType.get("actions"));
        }
    }

    public static void mergeReplayKeyframes(ReplayKeyframes replayKeyframes, BaseType baseType) {
        if (replayKeyframes == null || baseType == null || !baseType.isMap()) {
            return;
        }
        ReplayKeyframes replayKeyframes2 = new ReplayKeyframes("keyframes");
        replayKeyframes2.fromData(baseType);
        replayKeyframes.copyOver(replayKeyframes2, 0);
    }

    public static void mergeFormProperties(FormProperties formProperties, BaseType baseType) {
        if (formProperties == null || baseType == null || !baseType.isMap()) {
            return;
        }
        FormProperties formProperties2 = new FormProperties("properties");
        formProperties2.fromData(baseType);
        for (KeyframeChannel keyframeChannel : formProperties2.tracks.values()) {
            if (keyframeChannel.isEmpty()) continue;
            TrackId trackId = TrackId.parse(keyframeChannel.getId());
            if (trackId == null) continue;
            KeyframeChannel keyframeChannel2 = formProperties.get(trackId);
            if (keyframeChannel2 == null || keyframeChannel2.getFactory() != keyframeChannel.getFactory()) {
                if (keyframeChannel2 != null) {
                    formProperties.remove(trackId);
                }
                keyframeChannel2 = new KeyframeChannel(keyframeChannel.getId(), keyframeChannel.getFactory());
                keyframeChannel2.copyKeyframes(keyframeChannel);
                formProperties.put(trackId, keyframeChannel2);
                continue;
            }
            keyframeChannel2.copyOver(keyframeChannel, 0);
        }
    }

    public static void mergeActions(Clips clips, BaseType baseType) {
        if (clips == null || baseType == null || !baseType.isList()) {
            return;
        }
        Clips clips2 = new Clips("actions", (IFactory)BBSMod.getFactoryActionClips());
        clips2.fromData(baseType);
        clips.copyOver(clips2, 0);
    }
}

