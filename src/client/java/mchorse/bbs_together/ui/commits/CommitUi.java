package mchorse.bbs_together.ui.commits;

import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIMessageOverlayPanel;
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

    /** The dashboard's film editor panel, opened or not. Null until a film was opened once. */
    public static UIFilmPanel filmPanel()
    {
        UIDashboard dashboard = CommitUi.dashboard;

        return dashboard == null ? null : dashboard.getPanels().getPanel(UIFilmPanel.class);
    }

    /**
     * The editor's live film, saved or not, when it is the one with this id. The full screen
     * tab draws the same bar without the panel being an ancestor, hence the fallback.
     */
    public static Film liveFilm(UIElement from, String filmId)
    {
        if (filmId == null || filmId.isEmpty())
        {
            return null;
        }

        UIFilmPanel panel = from == null ? null : from.getAncestor(UIFilmPanel.class);

        if (panel == null)
        {
            panel = filmPanel();
        }

        Film film = panel == null ? null : (Film) panel.getData();

        return film != null && filmId.equals(film.getId()) ? film : null;
    }

    /** Opens the commits tab of the film editor; the film id only focuses the graph. */
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

    /** Asks whether the commit disappears from the history, or stays while the branch moves back. */
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

    /** Asks whether the commits after it are dropped, or the branch head only moves back. */
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

    /** Show why a commit request produced no commit instead of leaving the button silent. */
    public static void showRejected(int reason)
    {
        UIFilmPanel films = CommitUi.filmPanel();
        UIContext context = films == null ? null : films.getContext();

        if (context == null)
        {
            return;
        }

        UIOverlay.addOverlay(context, new UIMessageOverlayPanel(
            L10n.lang("bbs_together.commits.reject_title"),
            L10n.lang(reason == 0 ? "bbs_together.commits.reject_none" : "bbs_together.commits.reject_failed")
        ));
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
