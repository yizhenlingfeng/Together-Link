package mchorse.bbs_together.network;

import mchorse.bbs_together.TogetherSettings;
import mchorse.bbs_together.utils.TokenBucket;

/**
 * B1: one shared bandwidth budget for everything the server streams out of {@code config/bbs/assets}.
 *
 * <p>The old pacing ({@code asset_send_interval_ms} / {@code asset_send_batch_kb}) is per player:
 * 512 KB every 40 ms each. Three players pulling the same 50 MB model at once therefore pushed
 * ~38 MB/s into the server's uplink, which queues every other packet behind several seconds of
 * asset bytes — including the keepalives, which is what actually kicked people off. A per-player
 * limit cannot fix that; the link is shared, so the budget has to be shared too.</p>
 */
public final class AssetEgressBudget {
    private static final TokenBucket BUCKET = new TokenBucket(0L);
    private static int lastKbps = -1;

    private AssetEgressBudget() {}

    /** Re-reads {@code asset_egress_kbps}; cheap enough to call once per batch. */
    public static void sync() {
        int kbps = readKbps();
        if (kbps == lastKbps) {
            return;
        }
        lastKbps = kbps;
        BUCKET.setRate((long)kbps * 1024L);
    }

    private static int readKbps() {
        try {
            return TogetherSettings.getAssetEgressKbps();
        }
        catch (Throwable t) {
            return TogetherSettings.DEFAULT_ASSET_EGRESS_KBPS;
        }
    }

    /** @return nanoseconds to wait before sending {@code bytes}, 0 when it may go now. */
    public static long reserve(int bytes) {
        return BUCKET.reserve(bytes);
    }

    /** @return the budget in bytes per second, or 0 when unlimited. */
    public static long rateBytesPerSec() {
        sync();
        return BUCKET.rate();
    }

    public static long availableBytes() {
        return BUCKET.available();
    }

    public static void reset() {
        lastKbps = -1;
        BUCKET.reset();
        sync();
    }
}
