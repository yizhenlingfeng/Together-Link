package mchorse.bbs_together;

import java.util.concurrent.atomic.AtomicInteger;

public final class BBSTogetherFix22 {
    private static final AtomicInteger REMOTE_SYNC_APPLY_DEPTH = new AtomicInteger();

    private BBSTogetherFix22() {
    }

    public static void beginRemoteSyncApply() {
        REMOTE_SYNC_APPLY_DEPTH.incrementAndGet();
    }

    public static void endRemoteSyncApply() {
        int n = REMOTE_SYNC_APPLY_DEPTH.decrementAndGet();
        if (n < 0) {
            REMOTE_SYNC_APPLY_DEPTH.set(0);
        }
    }

    public static boolean isApplyingRemoteSync() {
        return REMOTE_SYNC_APPLY_DEPTH.get() > 0;
    }
}

