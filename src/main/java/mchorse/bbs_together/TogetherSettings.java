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
    /** B1: default shared server egress budget, KiB/s (2 MiB/s ~= 16 Mbps). */
    public static final int DEFAULT_ASSET_EGRESS_KBPS = 0;
    /** B1: default per-client upload budget, KiB/s (1 MiB/s ~= 8 Mbps). */
    public static final int DEFAULT_ASSET_UPLOAD_KBPS = 0;
    public static ValueString cdnUrl;
    public static ValueString cdnToken;
    public static ValueBoolean assetSyncDownload;
    public static ValueBoolean assetSyncUpload;
    public static ValueBoolean assetSyncReceive;
    public static ValueBoolean assetSyncProgressBar;
    public static ValueBoolean assetSyncProgressBarAlways;
    public static ValueBoolean assetAutoSync;
    public static ValueInt assetAutoSyncCooldownSec;
    public static ValueInt assetSendIntervalMs;
    public static ValueInt assetSendBatchKb;
    public static ValueInt assetUploadIntervalMs;
    public static ValueInt assetUploadBatchKb;
    /** B1: total server->client asset bandwidth budget in KiB/s (0 = unlimited, old behaviour). */
    public static ValueInt assetEgressKbps;
    /** B1: this client's upload bandwidth budget in KiB/s (0 = unlimited, old behaviour). */
    public static ValueInt assetUploadKbps;
    /** Files above this size are hidden from the asset manifest (0 = advertise everything). */
    public static ValueInt assetSyncMaxMb;
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
        /* Auto sync: react to server/local asset changes without manual resync/upload. */
        assetAutoSync = settingsBuilder.getBoolean("auto_sync", false);
        assetAutoSyncCooldownSec = settingsBuilder.getInt("auto_sync_cooldown_sec", 20, 5, 300);
        /* Server->client asset pacing: pause between slice batches. 1024 KB every 10 ms is just
         * "no artificial limit" (the link is the limit); raise the interval to slow a single
         * player down without touching everybody else. */
        assetSendIntervalMs = settingsBuilder.getInt("asset_send_interval_ms", 10, 0, 10000);
        assetSendBatchKb = settingsBuilder.getInt("asset_send_batch_kb", 1024, 30, 8192);
        /* B1: the pacing above is per player, so several clients pulling at once multiply it.
         * 0 = no shared ceiling (fastest, old behaviour). Set it, e.g. 8192, when several people
         * downloading simultaneously starts kicking clients off again. */
        assetEgressKbps = settingsBuilder.getInt("asset_egress_kbps", DEFAULT_ASSET_EGRESS_KBPS, 0, 1048576);
        /* Client->server upload pacing: pause between files and between slice batches. */
        assetUploadIntervalMs = settingsBuilder.getInt("asset_upload_interval_ms", 10, 0, 10000);
        assetUploadBatchKb = settingsBuilder.getInt("asset_upload_batch_kb", 4096, 30, 8192);
        /* B1: same idea on the sending side: an upload that saturates the local uplink is what
         * makes the game time out, and a fixed sleep between batches never actually capped the
         * rate. 4 MiB/s is a fast home uplink; 0 disables the cap. */
        assetUploadKbps = settingsBuilder.getInt("asset_upload_kbps", DEFAULT_ASSET_UPLOAD_KBPS, 0, 1048576);
        /* The single asset size ceiling, replacing the old download-only "下载资产大小上限".
         * Server side it keeps oversized files out of the manifest, so nobody downloads them in
         * the background and the initial sync stays small (one 700 MB screen recording used to
         * be pushed to every player on join and got them dropped by the read timeout). Client
         * side the same number is the download and upload ceiling, so both ends agree.
         * 0 = no ceiling: sync everything. */
        assetSyncMaxMb = settingsBuilder.getInt("asset_sync_max_mb", 256, 0, 2048);
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

    /** B1: server asset egress budget in KiB/s; 0 means "no budget" (pre-B1 fixed pacing only). */
    public static int getAssetEgressKbps()
    {
        return assetEgressKbps == null ? DEFAULT_ASSET_EGRESS_KBPS : Math.max(0, (Integer) assetEgressKbps.get());
    }

    /** B1: this client's upload budget in KiB/s; 0 means unlimited. */
    public static int getAssetUploadKbps()
    {
        return assetUploadKbps == null ? DEFAULT_ASSET_UPLOAD_KBPS : Math.max(0, (Integer) assetUploadKbps.get());
    }

    /** Server-side sync size ceiling in MiB; files above it are never advertised. 0 = no ceiling. */
    public static int getAssetSyncMaxMb()
    {
        return assetSyncMaxMb == null ? 256 : Math.max(0, (Integer) assetSyncMaxMb.get());
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

