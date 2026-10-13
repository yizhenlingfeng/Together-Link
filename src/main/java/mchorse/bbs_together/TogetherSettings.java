package mchorse.bbs_together;

import java.util.ArrayList;
import mchorse.bbs_mod.settings.SettingsBuilder;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.settings.values.core.ValueString;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;
import mchorse.bbs_mod.utils.interps.IInterp;
import mchorse.bbs_mod.utils.interps.Interpolations;
import mchorse.bbs_mod.utils.keyframes.KeyframeShape;
import mchorse.bbs_together.values.ValueKeyframeInterpolation;
import mchorse.bbs_together.values.ValueKeyframeShape;

public class TogetherSettings {
    public static ValueString cdnUrl;
    public static ValueString cdnToken;
    public static ValueBoolean assetSyncDownload;
    public static ValueBoolean assetSyncUpload;
    public static ValueBoolean assetSyncReceive;
    public static ValueBoolean assetSyncProgressBar;
    public static ValueBoolean assetSyncProgressBarAlways;
    public static ValueInt assetMaxSizeMb;
    public static ValueBoolean assetAutoSync;
    public static ValueInt assetAutoSyncCooldownSec;
    public static ValueInt assetSendIntervalMs;
    public static ValueInt assetSendBatchKb;
    public static ValueInt assetUploadIntervalMs;
    public static ValueInt assetUploadBatchKb;
    public static ValueKeyframeShape defaultKeyframeShape;
    public static ValueBoolean defaultKeyframeInterpolationEnabled;
    public static ValueKeyframeInterpolation defaultKeyframeInterpolation;
    public static ValueBoolean defaultKeyframeColorEnabled;
    public static ValueInt defaultKeyframeColor;
    public static ValueInt behaviorLogLimit;
    /** Cooperative recording: how long invitees have to accept a request (seconds). */
    public static ValueInt coopInviteSeconds;

    public static void register(SettingsBuilder settingsBuilder) {
        settingsBuilder.category("cdn");
        cdnUrl = settingsBuilder.getString("url", "");
        cdnToken = settingsBuilder.getString("token", "");
        assetSyncDownload = settingsBuilder.getBoolean("download", true);
        assetSyncUpload = settingsBuilder.getBoolean("upload", true);
        assetSyncReceive = settingsBuilder.getBoolean("receive", false);
        assetSyncProgressBar = settingsBuilder.getBoolean("progress_bar", true);
        assetSyncProgressBarAlways = settingsBuilder.getBoolean("progress_bar_always", false);
        settingsBuilder.category("server");
        assetMaxSizeMb = settingsBuilder.getInt("asset_max_size_mb", 1024, 16, 2048);
        /* Auto sync: react to server/local asset changes without manual resync/upload. */
        assetAutoSync = settingsBuilder.getBoolean("auto_sync", false);
        assetAutoSyncCooldownSec = settingsBuilder.getInt("auto_sync_cooldown_sec", 20, 5, 300);
        /* Server->client asset pacing: pause between 512 KB slice batches (40 ms ~= 12.8 MB/s). */
        assetSendIntervalMs = settingsBuilder.getInt("asset_send_interval_ms", 40, 0, 10000);
        assetSendBatchKb = settingsBuilder.getInt("asset_send_batch_kb", 512, 30, 8192);
        /* Client->server upload pacing: pause between files and between slice batches. */
        assetUploadIntervalMs = settingsBuilder.getInt("asset_upload_interval_ms", 200, 0, 10000);
        assetUploadBatchKb = settingsBuilder.getInt("asset_upload_batch_kb", 512, 30, 8192);
        settingsBuilder.category("keyframes");
        defaultKeyframeShape = new ValueKeyframeShape("default_shape", KeyframeShape.SQUARE);
        settingsBuilder.register((BaseValue)defaultKeyframeShape);
        defaultKeyframeInterpolationEnabled = settingsBuilder.getBoolean("default_interpolation_enabled", false);
        defaultKeyframeInterpolation = new ValueKeyframeInterpolation("default_interpolation", 0);
        settingsBuilder.register((BaseValue)defaultKeyframeInterpolation);
        defaultKeyframeColorEnabled = settingsBuilder.getBoolean("default_color_enabled", false);
        defaultKeyframeColor = settingsBuilder.getInt("default_color", -1).color();
        settingsBuilder.category("behaviors");
        behaviorLogLimit = settingsBuilder.getInt("behavior_log_limit", 1000, 100, 50000);
        settingsBuilder.category("coop");
        /* How long invitees have to accept a cooperative recording request. */
        coopInviteSeconds = settingsBuilder.getInt("coop_invite_seconds", 60, 10, 600);
    }

    /**
     * How many behavior entries the server keeps at most (oldest get evicted).
     * Read on the server side; safe before settings load.
     */
    public static int getBehaviorLogLimit()
    {
        return behaviorLogLimit == null ? 1000 : Math.max(100, (Integer) behaviorLogLimit.get());
    }

    public static KeyframeShape getDefaultKeyframeShape() {
        int n = defaultKeyframeShape == null ? 0 : (Integer)defaultKeyframeShape.get();
        KeyframeShape[] keyframeShapeArray = KeyframeShape.values();
        return n >= 0 && n < keyframeShapeArray.length ? keyframeShapeArray[n] : KeyframeShape.SQUARE;
    }

    public static IInterp getDefaultKeyframeInterpolation() {
        int n = defaultKeyframeInterpolation == null ? 0 : (Integer)defaultKeyframeInterpolation.get();
        ArrayList arrayList = new ArrayList(Interpolations.MAP.values());
        return n >= 0 && n < arrayList.size() ? (IInterp)arrayList.get(n) : Interpolations.LINEAR;
    }

    public static boolean shouldApplyDefaultKeyframeInterpolation() {
        return defaultKeyframeInterpolationEnabled != null && (Boolean)defaultKeyframeInterpolationEnabled.get() != false;
    }

    public static boolean shouldApplyDefaultKeyframeColor() {
        return defaultKeyframeColorEnabled != null && (Boolean)defaultKeyframeColorEnabled.get() != false;
    }

    public static int getDefaultKeyframeColor() {
        return defaultKeyframeColor == null ? -1 : (Integer)defaultKeyframeColor.get();
    }
}

