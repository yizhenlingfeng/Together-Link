package mchorse.bbs_together.ui.commits;

import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_together.commits.FilmCommitStore;
import mchorse.bbs_together.network.TogetherClientNetwork;

import java.util.function.Consumer;

/**
 * Single place that knows how the commit graph is reached, so the landing menu and the film
 * editor's top bar can open it without threading a dashboard reference through the screen
 * hierarchy: the graph is a tab of the film editor panel, next to the films.
 */
public final class CommitUi
{
    /** Width the top bar reserves for the button, shared by both mixins that touch the bar. */
    public static final int BUTTON_SIZE = 18;

    /** Pseudo film id of the commit tab; it is never a real film, only a marker for the tab list. */
    public static final String TAB_ID = "bbs_together:commits";

    /** Id of the commit view inside the film editor's dock, next to the properties panel. */
    public static final String DOCK_PANEL_ID = "bbs_together_commits";

    private static UIDashboard dashboard;

    private CommitUi()
    {}

    public static void install(UIDashboard dashboard)
    {
        CommitUi.dashboard = dashboard;
    }

    public static boolean isCommitsTab(String id)
    {
        return TAB_ID.equals(id);
    }

    /**
     * Opens the commit tab of the film editor, showing every film. The film id only focuses the
     * graph; the tab itself is shared by all films, the same way the panel that holds the film tabs
     * is.
     */
    public static boolean openFilm(String filmId)
    {
        if (dashboard == null)
        {
            return false;
        }

        UIFilmPanel films = dashboard.getPanels().getPanel(UIFilmPanel.class);

        if (films == null)
        {
            return false;
        }

        dashboard.getPanels().setPanel(films);

        ((ICommitsTabHost) films).bbs$openCommitsTab();

        return true;
    }

    /**
     * Asks how to undo one commit: either it disappears from the history, or it stays there while
     * the film and the branch go back to the commit before it.
     */
    public static void confirmRevert(UIContext context, String filmId, String commitId)
    {
        UIOverlay.addOverlay(context, new UICommitRollbackOverlay(
            L10n.lang("bbs_together.commits.revert_title"),
            L10n.lang("bbs_together.commits.revert_message")
        )
            .choice(L10n.lang("bbs_together.commits.revert_delete"), () -> TogetherClientNetwork.sendCommitRevert(filmId, commitId, FilmCommitStore.ROLLBACK_DELETE))
            .choice(L10n.lang("bbs_together.commits.revert_keep"), () -> TogetherClientNetwork.sendCommitRevert(filmId, commitId, FilmCommitStore.ROLLBACK_KEEP))
            .build());
    }

    /**
     * Asks how to go back to one commit: the commits after it can be dropped from the branch, or
     * stay in the history while the branch head moves back to the commit that was picked.
     */
    public static void confirmReset(UIContext context, String filmId, String commitId)
    {
        UIOverlay.addOverlay(context, new UICommitRollbackOverlay(
            L10n.lang("bbs_together.commits.reset_title"),
            L10n.lang("bbs_together.commits.reset_message")
        )
            .choice(L10n.lang("bbs_together.commits.reset_delete"), () -> TogetherClientNetwork.sendCommitRevert(filmId, commitId, FilmCommitStore.RESET_DELETE))
            .choice(L10n.lang("bbs_together.commits.reset_keep"), () -> TogetherClientNetwork.sendCommitRevert(filmId, commitId, FilmCommitStore.RESET_KEEP))
            .build());
    }

    /**
     * The button that sits in the film editor's top bar, to the left of the film tabs. It is its own
     * class so the top bar mixin can tell whether the button was already added.
     */
    public static class UICommitsTopButton extends UIIcon
    {
        public UICommitsTopButton(Consumer<UIIcon> callback)
        {
            super(Icons.GRAPH, callback);
        }
    }
}
