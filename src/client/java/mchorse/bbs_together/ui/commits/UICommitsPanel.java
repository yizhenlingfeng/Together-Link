package mchorse.bbs_together.ui.commits;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.dashboard.panels.UIDashboardPanel;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.framework.elements.input.list.UIList;
import mchorse.bbs_mod.ui.framework.elements.utils.UISplitter;
import mchorse.bbs_mod.ui.utils.context.ContextMenuManager;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_together.network.TogetherClientNetwork;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The commits page of the film editor's commit tab: the films that have commits on the left, their
 * graphs on the right, and the selected commit's details underneath. Empty space in a graph is a
 * valid click target: it clears the selection.
 */
public class UICommitsPanel extends UIDashboardPanel
{
    public static final int BAR_HEIGHT = 20;
    private static final int MIN_SIDE = 130;
    private static final int MAX_SIDE = 420;
    private static final int MIN_BOTTOM = 90;
    private static final int MAX_BOTTOM = 320;

    private final UIFilmFilterList films;
    private final UICommitGraph graph;
    private final UICommitInfo info;
    private final UIIcon refresh;

    private final UISplitter leftHandle;
    private final UISplitter bottomHandle;

    private int seen = -1;
    private boolean requested;

    public UICommitsPanel(UIDashboard dashboard)
    {
        super(dashboard);

        this.graph = new UICommitGraph();
        this.films = new UIFilmFilterList((list) -> this.graph.rebuild());
        this.info = new UICommitInfo();
        this.refresh = new UIIcon(Icons.REFRESH, (b) -> this.requestAll());

        this.leftHandle = UISplitter.pixels("bbs_together.commits.left", 190, MIN_SIDE, MAX_SIDE);
        /* The details pane is measured from its bottom edge: dragging its top edge up makes
         * it taller, which is what the user expects from a bottom sheet. */
        this.bottomHandle = UISplitter.pixels("bbs_together.commits.bottom", 150, MIN_BOTTOM, MAX_BOTTOM).vertical().fromEnd();

        this.leftHandle.measure(this).onChange(this::layout).range(MIN_SIDE, () -> (float) Math.max(MIN_SIDE, this.area.w - MIN_SIDE));
        this.bottomHandle.measure(this.info).onChange(this::layout).range(MIN_BOTTOM, () -> (float) Math.max(MIN_BOTTOM, this.area.h - BAR_HEIGHT - 80));
        CommitUi.styleSplitter(this.bottomHandle);

        this.graph.listener = (node) -> this.info.show(this.graph.actionFilm(), node);
        this.graph.context(this::fillGraphMenu);
        this.films.graph = this.graph;

        this.films.relative(this).x(0).y(BAR_HEIGHT).w(this.leftHandle.getPixels()).h(1F, -BAR_HEIGHT);
        this.graph.relative(this).xy(this.leftHandle.getPixels(), BAR_HEIGHT).w(1F, -this.leftHandle.getPixels()).h(1F, -BAR_HEIGHT);
        this.info.relative(this).x(0).y(1F, -this.bottomHandle.getPixels()).w(1F).h(this.bottomHandle.getPixels());

        this.refresh.relative(this).x(2).y(2).wh(16, 16);

        this.leftHandle.relative(this.films).x(1F).y(0.5F).w(6).h(40).anchor(0.5F, 0.5F);
        /* Span the whole top edge of the details pane, not just a 40 px grip in the middle. */
        this.bottomHandle.relative(this.info).x(0).y(0).w(1F).h(8).anchor(0F, 0.5F);

        this.add(this.films, this.graph, this.info, this.refresh, this.leftHandle, this.bottomHandle);
    }

    @Override
    public boolean needsBackground()
    {
        return true;
    }

    /** Pin the graph to one film (opened from the film editor), or to all of them. */
    public void focusFilm(String filmId)
    {
        this.graph.showOnly(filmId);
        this.info.film(this.graph.actionFilm());
    }

    public void requestAll()
    {
        TogetherClientNetwork.sendCommitIndex();
    }

    private void fillGraphMenu(ContextMenuManager menu)
    {
        UIContext context = this.getContext();

        if (context == null)
        {
            return;
        }

        UICommitGraph.Node node = this.graph.nodeAt(context.mouseX, context.mouseY);

        if (node == null)
        {
            return;
        }

        final String filmId = node.filmId;
        final String commitId = node.commit.id;

        /* Every node, including commits on other branches, can have its message edited. */
        menu.action(Icons.EDIT, L10n.lang("bbs_together.commits.rename").format(node.commit.shortId()),
            () -> CommitUi.renameCommit(context, filmId, commitId));

        if (CommitUi.canRevert(node))
        {
            menu.action(Icons.UNDO, L10n.lang("bbs_together.commits.revert"), () -> CommitUi.confirmRevert(context, filmId, commitId));
        }

        if (CommitUi.canSquash(node))
        {
            menu.action(Icons.TREE, L10n.lang("bbs_together.commits.squash").format(commitId), () -> CommitUi.confirmSquash(context, filmId, commitId));
        }

        if (!node.commit.branch.isEmpty())
        {
            menu.action(Icons.RIGHTLOAD, L10n.lang("bbs_together.commits.branch_checkout_here").format(node.commit.branch),
                () -> TogetherClientNetwork.sendBranchCheckout(filmId, node.commit.branch));
        }

        menu.action(Icons.COLOR, L10n.lang("bbs_together.commits.commit_color").format(node.commit.shortId()), () -> this.graph.openCommitColor(context, filmId, commitId, node.x, node.y));
    }

    private void layout()
    {
        int left = this.leftHandle.getPixels();
        int bottom = this.bottomHandle.getPixels();

        this.films.relative(this).x(0).y(BAR_HEIGHT).w(left).h(1F, -BAR_HEIGHT - bottom);
        this.graph.relative(this).xy(left, BAR_HEIGHT).w(1F, -left).h(1F, -BAR_HEIGHT - bottom);
        this.info.relative(this).x(0).y(1F, -bottom).w(1F).h(bottom);

        if (this.area.w > 0)
        {
            this.resize();
        }
    }

    @Override
    public void render(UIContext context)
    {
        /* As a tab this panel is not a dashboard panel any more, so nothing calls update(): the
         * page refreshes itself while it is drawn, and asks for the index the first time */
        if (!this.requested)
        {
            this.requested = true;

            this.requestAll();
        }

        if (this.seen != FilmCommitClient.version())
        {
            this.seen = FilmCommitClient.version();

            this.films.setList(new ArrayList<FilmCommitClient.IndexEntry>(FilmCommitClient.index()));
            this.films.update();
            this.graph.rebuild();
            this.info.film(this.graph.actionFilm());
            this.info.refresh();
        }

        int chrome = BBSSettings.color(BBSSettings.chromeSurface(), Colors.A50);

        context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.y + BAR_HEIGHT, chrome);
        context.batcher.text(L10n.lang("bbs_together.commits.title").get(), this.area.x + 22, this.area.y + 6, Colors.LIGHTER_GRAY);

        context.batcher.box(this.area.x, this.area.y + BAR_HEIGHT + this.graph.area.h, this.area.ex(), this.area.y + BAR_HEIGHT + this.graph.area.h + 1, BBSSettings.dividerColor());

        super.render(context);
    }

    /** The films that have commits, each with a checkbox that toggles its column in the graph. */
    public static class UIFilmFilterList extends UIList<FilmCommitClient.IndexEntry>
    {
        /** Set by the page so a toggle immediately rebuilds the graph. */
        public UICommitGraph graph;

        public UIFilmFilterList(Consumer<List<FilmCommitClient.IndexEntry>> callback)
        {
            super(callback);

            this.scroll.scrollItemSize = 18;
        }

        public boolean isShown(FilmCommitClient.IndexEntry entry)
        {
            return this.graph == null || this.graph.isShown(entry.id);
        }

        private String name(FilmCommitClient.IndexEntry entry)
        {
            int slash = entry.id.lastIndexOf('/');

            return slash < 0 ? entry.id : entry.id.substring(slash + 1);
        }

        @Override
        protected String elementToString(UIContext context, int i, FilmCommitClient.IndexEntry entry)
        {
            return this.name(entry);
        }

        @Override
        public void renderElementPart(UIContext context, FilmCommitClient.IndexEntry entry, int i, int x, int y, boolean hover, boolean selected)
        {
            int s = this.scroll.scrollItemSize;
            int boxY = y + (s - 9) / 2;
            boolean shown = this.isShown(entry);

            context.batcher.box(x + 4, boxY, x + 13, boxY + 9, shown ? BBSSettings.primaryColor() : Colors.A25);

            int textY = y + (s - context.batcher.getFont().getHeight()) / 2;
            String count = String.valueOf(entry.count);
            int countX = x + this.area.w - 8 - context.batcher.getFont().getWidth(count);

            context.batcher.text(this.name(entry), x + 18, textY, shown ? Colors.WHITE : Colors.GRAY);
            context.batcher.text(count, countX, textY, Colors.GRAY);
        }

        @Override
        public boolean subMouseClicked(UIContext context)
        {
            if (context.mouseButton == 0 && this.area.isInside(context))
            {
                int index = this.indexAt(this.contentX(context), this.contentY(context));

                if (index >= 0 && index < this.getList().size())
                {
                    FilmCommitClient.IndexEntry entry = this.getList().get(index);

                    if (this.graph != null)
                    {
                        this.graph.setShown(entry.id, !this.isShown(entry));
                    }

                    return true;
                }
            }

            return super.subMouseClicked(context);
        }
    }
}
