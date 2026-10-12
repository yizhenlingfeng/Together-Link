package mchorse.bbs_together;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.forms.editors.UIFormUndoHandler;

/**
 * Undo audit fix #2 — narrow the snapshot race.
 *
 * <p>Undo entries hold whole-subtree snapshots. If a remote change (another player's or a
 * server-side edit) lands while a local undo recording is still open (an auto-keyframe take
 * batches edits across seconds), the entry would eventually seal with that remote change
 * mixed into its "after" snapshot — and its "before" snapshot predates the remote change,
 * so undoing the entry would wipe it on every peer. The server-side undo guard
 * (s_undo_sync) can't catch that either: the entry's expected state would legitimately
 * match the server, remote change included.</p>
 *
 * <p>So every remote application seals whatever the player has in flight BEFORE the remote
 * data is written. The sealed entry then covers only the player's own edit; the remote
 * change stays outside of it, and a later undo attempt over that entry gets refused by the
 * server instead of silently wiping a peer's work.</p>
 */
public final class BBSTogetherUndoGuard {
    private static boolean registered;

    private BBSTogetherUndoGuard() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        BBSTogetherFix22.setBeforeRemoteApplyHook(BBSTogetherUndoGuard::sealOpenUndoRecording);
    }

    private static void sealOpenUndoRecording() {
        UIDashboard dashboard = BBSModClient.getDashboard();
        if (dashboard == null) {
            return;
        }
        UIFilmPanel panel = (UIFilmPanel)dashboard.getPanel(UIFilmPanel.class);
        if (panel == null || panel.getData() == null) {
            return;
        }
        UIFormUndoHandler handler = panel.getUndoHandler();
        if (handler == null) {
            return;
        }
        /* Force-seal what is collected right now. With nothing pending this is a no-op.
         * noMerging keeps the sealed entry from fusing with the player's next edit, so
         * each entry keeps describing exactly one stretch of the player's own work. */
        handler.submitUndo(true);
        handler.getUndoManager().markLastUndoNoMerging();
    }
}
