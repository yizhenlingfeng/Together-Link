package mchorse.bbs_together.network;

import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_together.BBSTogetherFix22;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Pending "local edit → server" syncs, extracted from {@code UIFilmUndoHandlerMixin} so the
 * undo/redo sync path ({@code UIFilmPanelMixin#syncUndoAtIndex}) can coordinate with it.
 *
 * <p>Why the coordination matters: an undo/redo is broadcast with a server-side optimistic
 * lock (s_undo_sync — the entry's expected state travels along). A value that is about to be
 * reverted must not afterwards be re-sent from this queue with its rolled-back data as if it
 * were a fresh edit — that would bypass the guard. Conversely, if the edit that produced the
 * entry is still queued (never handed to the network), the server has to receive that
 * post-edit state BEFORE the undo packet, or the guard would compare against a stale
 * baseline and wrongly refuse the undo.</p>
 */
public final class TogetherEditSyncQueue {
    private static final Set<BaseValue> QUEUE = new HashSet<>();
    private static long lastFlush = 0L;

    private TogetherEditSyncQueue() {
    }

    public static void add(BaseValue value) {
        QUEUE.add(value);
        lastFlush = 0L;
    }

    /** Whether the value's post-edit sync is still queued (never handed to the network). */
    public static boolean contains(BaseValue value) {
        return QUEUE.contains(value);
    }

    public static void removeAll(Collection<BaseValue> values) {
        QUEUE.removeAll(values);
    }

    public static void clear() {
        QUEUE.clear();
    }

    /** Drains queued edit syncs (throttled to 100 ms), same behavior as before the move. */
    public static void flush(UIFilmPanel panel) {
        if (QUEUE.isEmpty() || !TogetherClientNetwork.isConnected() || BBSTogetherFix22.isApplyingRemoteSync()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastFlush < 100L) {
            return;
        }
        lastFlush = now;
        sendAll(panel);
    }

    /**
     * Drain now, ignoring the 100 ms throttle. Branch checkout needs every queued edit of the
     * old branch on the server before the film is replaced with the new branch.
     */
    public static void flushNow(UIFilmPanel panel) {
        if (QUEUE.isEmpty() || !TogetherClientNetwork.isConnected() || BBSTogetherFix22.isApplyingRemoteSync()) {
            return;
        }
        lastFlush = System.currentTimeMillis();
        sendAll(panel);
    }

    private static void sendAll(UIFilmPanel panel) {
        if (panel == null || panel.getData() == null) {
            QUEUE.clear();
            return;
        }
        String filmId = ((Film)panel.getData()).getId();
        HashSet<BaseValue> batch = new HashSet<>(QUEUE);
        batch.removeIf(value -> {
            for (BaseValue parent = value.getParent(); parent != null; parent = parent.getParent()) {
                if (batch.contains(parent)) {
                    return true;
                }
            }
            return false;
        });
        for (BaseValue value : batch) {
            TogetherClientNetwork.sendSyncData(filmId, value);
        }
        QUEUE.clear();
    }
}
