package mchorse.bbs_together.network;

import java.util.ArrayList;
import java.util.List;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.film.utils.undo.ValueChangeUndo;
import mchorse.bbs_mod.utils.undo.CompoundUndo;
import mchorse.bbs_mod.utils.undo.IUndo;
import mchorse.bbs_together.UndoPathResolver;

/**
 * Undo/redo broadcast with a server-side optimistic lock (s_undo_sync).
 *
 * <p>An undo entry holds whole-subtree snapshots. If a peer edited the same subtree
 * between the snapshot and the Ctrl+Z, replaying the entry would wipe that edit — so the
 * packet carries, per touched subtree, the state the server should still hold. The server
 * refuses the rollback when its authoritative copy has moved on and pushes the current
 * data back ({@code c_undo_rejected}), instead of relaying the wipe to everyone.</p>
 *
 * <p>Called from the UIFilmPanel undo/redo injections; all the logic lives here so the
 * mixin stays a one-liner.</p>
 */
public final class TogetherUndoSync {
    private TogetherUndoSync() {
    }

    public static void broadcast(UIFilmPanel self, boolean redo) {
        if (!TogetherClientNetwork.isConnected()) {
            return;
        }
        if (self.getData() == null || self.getUndoHandler() == null) {
            return;
        }
        int idx = self.getUndoHandler().getUndoManager().getCurrentUndoIndex() + (redo ? 0 : 1);
        syncUndoAtIndex(self, idx, redo);
    }

    private static void syncUndoAtIndex(UIFilmPanel self, int idx, boolean redo) {
        List undos = self.getUndoHandler().getUndoManager().getUndos();
        if (idx < 0 || idx >= undos.size()) {
            return;
        }
        IUndo entry = (IUndo)undos.get(idx);
        Film film = (Film)self.getData();
        ArrayList<ValueChangeUndo> changes = new ArrayList<ValueChangeUndo>();
        if (entry instanceof CompoundUndo) {
            for (Object childObj : ((CompoundUndo)entry).getUndos()) {
                IUndo child = (IUndo)childObj;
                if (child instanceof ValueChangeUndo) {
                    changes.add((ValueChangeUndo)child);
                }
            }
        } else if (entry instanceof ValueChangeUndo) {
            changes.add((ValueChangeUndo)entry);
        }
        String filmId = film.getId();
        ArrayList<TogetherClientNetwork.UndoChangePayload> payloads = new ArrayList<TogetherClientNetwork.UndoChangePayload>();
        ArrayList<BaseValue> claimed = new ArrayList<BaseValue>();
        for (ValueChangeUndo change : changes) {
            List<String> path = new ArrayList<String>(change.name.strings);
            /* Film.findRecursively() only resolves the first segment, so a nested path would send
             * the whole replays group instead of the value that actually changed. */
            BaseValue value = UndoPathResolver.resolve(film, path);
            BaseType postImage = redo ? change.newValue : change.oldValue;
            BaseType preImage = redo ? change.oldValue : change.newValue;
            if (postImage == null || preImage == null) {
                continue;
            }
            boolean tracked = path.size() >= 2 && "replays".equals(path.get(0));
            if (tracked && value != null && TogetherEditSyncQueue.contains(value)) {
                /* The edit never left this client, so send its post-edit state first or the
                 * server would validate the rollback against a stale baseline. */
                TogetherClientNetwork.sendSyncDataPayload(filmId, path, preImage);
            }
            if (value != null) {
                claimed.add(value);
            }
            payloads.add(new TogetherClientNetwork.UndoChangePayload(path, postImage, preImage));
        }
        if (payloads.isEmpty()) {
            return;
        }
        /* The undo packet now owns these subtrees: dropping them from the queue stops the
         * next flush from re-sending their rolled-back data as if it were a fresh edit. */
        TogetherEditSyncQueue.removeAll(claimed);
        TogetherClientNetwork.sendUndoSync(filmId, payloads);
    }
}
