package mchorse.bbs_together.network;

import mchorse.bbs_together.TogetherSettings;
import mchorse.bbs_together.utils.TokenBucket;

/**
 * One shared bandwidth budget for asset uploads received by the server.
 *
 * <p>Client-side {@code asset_upload_kbps} throttles a cooperative client, but a modified client
 * can ignore it. This budget is the server-side backstop: every uploaded slice reserves its wire
 * bytes here before the write worker touches the disk, so all players share the same ingress
 * ceiling. Default 0 keeps the old unlimited behaviour.</p>
 */
public final class AssetIngressBudget {
    private static final TokenBucket BUCKET = new TokenBucket(0L);
    private static int lastKbps = -1;

    private AssetIngressBudget() {}

    /** Re-reads {@code asset_ingress_kbps}; cheap enough to call once per upload slice. */
    private static void sync() {
        int kbps = readKbps();
        if (kbps == lastKbps) {
            return;
        }
        lastKbps = kbps;
        BUCKET.setRate((long)kbps * 1024L);
    }

    private static int readKbps() {
        try {
            return TogetherSettings.getAssetIngressKbps();
        }
        catch (Throwable t) {
            return TogetherSettings.DEFAULT_ASSET_INGRESS_KBPS;
        }
    }

    /** @return nanoseconds to wait before writing {@code bytes}, 0 when it may go now. */
    public static long reserve(int bytes) {
        sync();
        return BUCKET.reserve(bytes);
    }

    public static void reset() {
        lastKbps = -1;
        BUCKET.reset();
        sync();
    }
}
