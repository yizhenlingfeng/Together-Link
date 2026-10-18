package mchorse.bbs_together.ui.commits;

import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_together.commits.FilmCommit;
import mchorse.bbs_together.network.TogetherClientNetwork;
import mchorse.bbs_together.network.TogetherEditSyncQueue;

import java.util.function.Supplier;

/**
 * The message field and the buttons that change the history: commit, branch off, check a branch
 * out, and revert the selected commit. Lives in the film editor's dock under the commit graph.
 */
public class UICommitActions extends UIElement
{
    private final Supplier<UICommitGraph> graphs;
    private final UITextbox message;
    private final UIButton commit;
    private final UITextbox branch;
    private final UIButton branchCreate;
    private final UIButton branchCheckout;
    private final UIButton revert;

    private String branchBase = "";

    public UICommitActions(Supplier<UICommitGraph> graphs)
    {
        this.graphs = graphs;

        this.message = new UITextbox(FilmCommit.MAX_MESSAGE, (t) -> {});
        this.message.placeholder(L10n.lang("bbs_together.commits.message"));

        this.commit = new UIButton(L10n.lang("bbs_together.commits.commit"), (b) -> this.commit());
        this.branch = new UITextbox(FilmCommit.MAX_BRANCH, (t) -> {});
        this.branch.placeholder(L10n.lang("bbs_together.commits.branch"));
        this.branchCreate = new UIButton(L10n.lang("bbs_together.commits.branch_create"), (b) -> this.createBranch());
        this.branchCheckout = new UIButton(L10n.lang("bbs_together.commits.branch_checkout"), (b) -> this.checkout());
        this.revert = new UIButton(L10n.lang("bbs_together.commits.revert_selected"), (b) -> this.revert());

        this.message.relative(this).x(4).y(4).w(1F, -8).h(18);
        this.commit.relative(this).x(4).y(26).w(1F, -8).h(18);
        this.branch.relative(this).x(4).y(48).w(1F, -8).h(18);
        this.branchCreate.relative(this).x(4).y(70).w(0.5F, -6).h(18);
        this.branchCheckout.relative(this).x(0.5F).y(70).w(0.5F, -6).h(18);
        this.revert.relative(this).x(4).y(92).w(1F, -8).h(18);

        this.add(this.message, this.commit, this.branch, this.branchCreate, this.branchCheckout, this.revert);

        this.update();
    }

    /** Remember which commit a new branch should start at, and suggest a name. */
    public void wantBranchAt(String commitId)
    {
        this.branchBase = commitId == null ? "" : commitId;

        if (commitId != null)
        {
            FilmCommit commit = FilmCommitClient.commit(this.graphs.get().actionFilm(), commitId);

            if (commit != null && !commit.branch.isEmpty())
            {
                this.branch.setText(commit.branch + "-2");
            }
        }

        this.update();
    }

    public void update()
    {
        UICommitGraph graph = this.graphs.get();
        String film = graph.actionFilm();
        String name = this.branchText();
        UICommitGraph.Node node = graph.selected();
        String target = film.isEmpty() ? "" : graph.commitBranch(film);

        this.commit.setEnabled(!film.isEmpty());
        this.commit.label = target.isEmpty()
            ? L10n.lang("bbs_together.commits.commit")
            : L10n.lang("bbs_together.commits.commit_to").format(target);
        this.branchCreate.setEnabled(!film.isEmpty() && !name.isEmpty());
        this.branchCheckout.setEnabled(!film.isEmpty() && !name.isEmpty());

        boolean canRevert = CommitUi.canRevert(node);

        this.revert.setEnabled(canRevert);

        if (node != null && !canRevert)
        {
            this.revert.tooltip(L10n.lang("bbs_together.commits.revert_same_branch"));
        }
        else
        {
            this.revert.removeTooltip();
        }
    }

    private String branchText()
    {
        String text = this.branch.getText();

        return text == null ? "" : text.trim();
    }

    private void commit()
    {
        UICommitGraph graph = this.graphs.get();
        String film = graph.actionFilm();

        if (film.isEmpty())
        {
            return;
        }

        /* The editor's unsaved state goes along with the request, not what the server last
         * managed to write to disk */
        Film data = CommitUi.liveFilm(this, film);

        TogetherClientNetwork.sendCommitCreate(film, this.message.getText(), graph.commitBranch(film), data);
        TogetherEditSyncQueue.markCommitted();
        this.message.setText("");
    }

    private void createBranch()
    {
        String film = this.graphs.get().actionFilm();
        String name = this.branchText();

        if (film.isEmpty() || name.isEmpty())
        {
            return;
        }

        TogetherClientNetwork.sendBranchCreate(film, name, this.branchBase);
    }

    private void checkout()
    {
        String film = this.graphs.get().actionFilm();
        String name = this.branchText();

        if (film.isEmpty() || name.isEmpty())
        {
            return;
        }

        /* Push still-queued edits of the old branch before the film is replaced with the new
         * branch, otherwise they would later be applied to the wrong branch on the server. */
        TogetherEditSyncQueue.flushNow(CommitUi.filmPanel());
        TogetherClientNetwork.sendBranchCheckout(film, name);
    }

    private void revert()
    {
        UICommitGraph.Node node = this.graphs.get().selected();
        UIContext context = this.getContext();

        if (node != null && context != null)
        {
            CommitUi.confirmRevert(context, node.filmId, node.commit.id);
        }
    }
}
