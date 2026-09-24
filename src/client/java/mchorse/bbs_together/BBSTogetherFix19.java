package mchorse.bbs_together;

import java.lang.reflect.Field;
import java.util.List;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.Recorder;
import mchorse.bbs_mod.film.replays.FormProperties;
import mchorse.bbs_mod.film.replays.tracks.TrackId;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.film.replays.ReplayKeyframes;
import mchorse.bbs_mod.network.PacketCrusher;
import mchorse.bbs_mod.utils.clips.Clips;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_together.network.TogetherClientNetwork;
import mchorse.bbs_together.network.TogetherServerNetwork;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.client.MinecraftClient;

public final class BBSTogetherFix19 {
    private static final long RECORDING_SAVE_SUPPRESS_MS = 10000L;
    private static volatile long lastRecordingSyncAt;

    private BBSTogetherFix19() {
    }

    public static void sendRecordingFinished(Film film, Recorder recorder) {
        if (film == null || recorder == null) {
            return;
        }
        MapType mapType = new MapType();
        MapType mapType2 = BBSTogetherFix19.collectNonEmptyChannels(recorder.keyframes);
        MapType mapType3 = BBSTogetherFix19.collectNonEmptyProperties(null);
        if (!mapType2.isEmpty()) {
            mapType.put("keyframes", (BaseType)mapType2);
        }
        if (!mapType3.isEmpty()) {
            mapType.put("properties", (BaseType)mapType3);
        }
        BBSTogetherFix19.sendRecordingFinishedData(film.getId(), recorder.exception, (BaseType)mapType);
    }

    public static void sendRecordingFinished(Film film, int n) {
        if (film == null || n < 0 || !TogetherClientNetwork.isConnected()) {
            return;
        }
        List list = film.replays.getList();
        if (n >= list.size()) {
            return;
        }
        Replay replay = (Replay)list.get(n);
        if (replay == null) {
            return;
        }
        lastRecordingSyncAt = System.currentTimeMillis();
        TogetherClientNetwork.recordingSessionActive = false;
        TogetherClientNetwork.sendRecordingFinished(film.getId(), n, replay);
    }

    public static void sendRecordingDelta(Film film, int n, Replay replay, BaseType baseType, BaseType baseType2) {
        BBSTogetherFix19.sendRecordingDelta(film, n, replay, baseType, baseType2, null);
    }

    public static void sendRecordingDelta(Film film, int n, Replay replay, BaseType baseType, BaseType baseType2, BaseType baseType3) {
        if (film == null || replay == null) {
            return;
        }
        MapType mapType = new MapType();
        MapType mapType2 = BBSTogetherFix19.collectChangedChannels(replay.keyframes, baseType);
        MapType mapType3 = BBSTogetherFix19.collectChangedProperties(replay.properties, baseType2);
        BaseType baseType4 = BBSTogetherFix19.collectChangedActions(replay.actions, baseType3);
        if (!mapType2.isEmpty()) {
            mapType.put("keyframes", (BaseType)mapType2);
        }
        if (!mapType3.isEmpty()) {
            mapType.put("properties", (BaseType)mapType3);
        }
        if (baseType4 != null) {
            mapType.put("actions", baseType4);
        }
        BBSTogetherFix19.sendRecordingFinishedData(film.getId(), n, (BaseType)mapType);
    }

    public static void sendRecordingFinishedData(String string, int n, BaseType baseType) {
        if (string == null || n < 0 || baseType == null || !baseType.isMap() || baseType.asMap().isEmpty() || !TogetherClientNetwork.isConnected()) {
            return;
        }
        lastRecordingSyncAt = System.currentTimeMillis();
        TogetherClientNetwork.recordingSessionActive = false;
        try {
            PacketCrusher packetCrusher = BBSTogetherFix19.getClientPacketCrusher();
            if (packetCrusher == null || MinecraftClient.getInstance().player == null) {
                return;
            }
            packetCrusher.send((PlayerEntity)MinecraftClient.getInstance().player, TogetherServerNetwork.SERVER_RECORDING_FINISHED, baseType, class_25402 -> {
                class_25402.writeString(string);
                class_25402.writeInt(n);
            });
        }
        catch (ReflectiveOperationException reflectiveOperationException) {
            reflectiveOperationException.printStackTrace();
        }
    }

    public static boolean shouldSuppressFullFilmSaveAfterRecording() {
        return TogetherClientNetwork.isConnected() && System.currentTimeMillis() - lastRecordingSyncAt < 10000L;
    }

    private static PacketCrusher getClientPacketCrusher() throws ReflectiveOperationException {
        Field field = TogetherClientNetwork.class.getDeclaredField("crusher");
        field.setAccessible(true);
        return (PacketCrusher)field.get(null);
    }

    private static MapType collectNonEmptyChannels(ReplayKeyframes replayKeyframes) {
        MapType mapType = new MapType();
        if (replayKeyframes == null) {
            return mapType;
        }
        for (KeyframeChannel keyframeChannel : replayKeyframes.getChannels()) {
            if (keyframeChannel.isEmpty()) continue;
            mapType.put(keyframeChannel.getId(), keyframeChannel.toData());
        }
        return mapType;
    }

    private static MapType collectChangedChannels(ReplayKeyframes replayKeyframes, BaseType baseType) {
        MapType mapType;
        MapType mapType2 = new MapType();
        MapType mapType3 = mapType = baseType != null && baseType.isMap() ? baseType.asMap() : new MapType();
        if (replayKeyframes == null) {
            return mapType2;
        }
        for (KeyframeChannel keyframeChannel : replayKeyframes.getChannels()) {
            BaseType baseType2;
            BaseType baseType3;
            if (keyframeChannel.isEmpty() || BaseType.equals((BaseType)(baseType3 = keyframeChannel.toData()), (BaseType)(baseType2 = mapType.get(keyframeChannel.getId())))) continue;
            mapType2.put(keyframeChannel.getId(), baseType3);
        }
        return mapType2;
    }

    private static MapType collectNonEmptyProperties(FormProperties formProperties) {
        MapType mapType = new MapType();
        if (formProperties == null) {
            return mapType;
        }
        for (KeyframeChannel keyframeChannel : formProperties.tracks.values()) {
            if (keyframeChannel.isEmpty()) continue;
            mapType.put(keyframeChannel.getId(), keyframeChannel.toData());
        }
        return mapType;
    }

    private static MapType collectChangedProperties(FormProperties formProperties, BaseType baseType) {
        MapType mapType;
        MapType mapType2 = new MapType();
        MapType mapType3 = mapType = baseType != null && baseType.isMap() ? baseType.asMap() : new MapType();
        if (formProperties == null) {
            return mapType2;
        }
        for (KeyframeChannel keyframeChannel : formProperties.tracks.values()) {
            BaseType baseType2;
            BaseType baseType3;
            if (keyframeChannel.isEmpty() || BaseType.equals((BaseType)(baseType3 = keyframeChannel.toData()), (BaseType)(baseType2 = mapType.get(keyframeChannel.getId())))) continue;
            mapType2.put(keyframeChannel.getId(), baseType3);
        }
        return mapType2;
    }

    private static BaseType collectChangedActions(Clips clips, BaseType baseType) {
        if (clips == null) {
            return null;
        }
        BaseType baseType2 = clips.toData();
        return BaseType.equals((BaseType)baseType2, (BaseType)baseType) ? null : baseType2;
    }
}

