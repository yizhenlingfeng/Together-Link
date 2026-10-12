package mchorse.bbs_together;

import java.util.concurrent.atomic.AtomicInteger;

public final class BBSTogetherFix22 {
    private static final AtomicInteger REMOTE_SYNC_APPLY_DEPTH = new AtomicInteger();

    /**
     * Client-side hook, run just before any remote sync lands on the local data tree.
     * Set during client init (BBSTogetherUndoGuard): it force-seals any still-open local
     * undo recording, so a remote change can never be swept into an entry the player
     * hasn't finished — the sealed entry's "after" snapshot then describes only the
     * player's own edit, which is what lets the server-side undo guard (s_undo_sync)
     * detect conflicts at all.
     */
    private static volatile Runnable beforeRemoteApplyHook;

    private BBSTogetherFix22() {
    }

    public static void setBeforeRemoteApplyHook(Runnable hook) {
        beforeRemoteApplyHook = hook;
    }

    public static void beginRemoteSyncApply() {
        Runnable hook = beforeRemoteApplyHook;
        if (hook != null) {
            try {
                hook.run();
            }
            catch (Throwable t) {
                t.printStackTrace();
            }
        }
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

