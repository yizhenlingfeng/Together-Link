package mchorse.bbs_together.ui.commits;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.graphics.window.Window;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.dashboard.panels.UIDashboardPanel;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.framework.elements.input.list.UIList;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.ui.framework.elements.utils.RowStyle;
import mchorse.bbs_mod.ui.framework.elements.utils.UISplitter;
import mchorse.bbs_mod.ui.utils.context.ContextMenuManager;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_together.commits.FilmCommit;
import mchorse.bbs_together.network.TogetherClientNetwork;

import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * The commits page of the film editor's commit tab: the films that have commits on the left, their
 * graphs on the right, and the selected commit's details underneath. Empty space in a graph is a
 * valid click target: it clears the selection.
 */
public class UICommitsPanel extends UIDashboardPanel
{
    public static final int BAR_HEIGHT = 28;
    private static final int MIN_SIDE = 130;
    private static final int MAX_SIDE = 420;
    private static final int MIN_BOTTOM = 90;
    private static final int MAX_BOTTOM = 320;

    private static final int SORT_NAME = 0;
    private static final int SORT_RECENT = 1;
    private static final String[] SORT_KEYS = {
        "bbs_together.commits.sort_name",
        "bbs_together.commits.sort_recent",
        "bbs_together.commits.sort_count"
    };

    private final UIFilmFilterList films;
    private final UICommitGraph graph;
    private final UICommitInfo info;
    private final UIIcon refresh;
    private final UITextbox search;
    private final FilterTabs filters;
    private final UIButton sort;

    private final UISplitter leftHandle;
    private final UISplitter bottomHandle;

    private int seen = -1;
    private boolean requested;
    private int filterMode;
    private int sortMode;

    public UICommitsPanel(UIDashboard dashboard)
    {
        super(dashboard);

        this.graph = new UICommitGraph();
        this.films = new UIFilmFilterList((list) -> this.graph.rebuild());
        this.info = new UICommitInfo();
        this.refresh = new UIIcon(Icons.REFRESH, (b) -> this.requestAll());
        this.search = new UITextbox(64, (text) -> this.applyFilter());
        this.filters = new FilterTabs(new String[] {
            "bbs_together.commits.filter_all",
            "bbs_together.commits.filter_shown",
            "bbs_together.commits.filter_current"
        }, (index) ->
        {
            this.filterMode = index;
            this.refreshFilms();
        });
        this.sort = new UIButton(L10n.lang(SORT_KEYS[SORT_NAME]), (b) -> this.cycleSort());

        this.search.placeholder(L10n.lang("bbs_together.commits.search"));

        this.leftHandle = UISplitter.pixels("bbs_together.commits.left", 210, MIN_SIDE, MAX_SIDE);
        /* The details pane is measured from its bottom edge: dragging its top edge up makes
         * it taller, which is what the user expects from a bottom sheet. */
        this.bottomHandle = UISplitter.pixels("bbs_together.commits.bottom", 180, MIN_BOTTOM, MAX_BOTTOM).vertical().fromEnd();

        this.leftHandle.measure(this).onChange(this::layout).range(MIN_SIDE, () -> (float) Math.max(MIN_SIDE, this.area.w - MIN_SIDE));
        this.bottomHandle.measure(this.info).onChange(this::layout).range(MIN_BOTTOM, () -> (float) Math.max(MIN_BOTTOM, this.area.h - BAR_HEIGHT - 80));
        CommitUi.styleSplitter(this.bottomHandle);

        this.graph.listener = (node) -> this.info.show(this.graph.actionFilm(), node);
        this.graph.context(this::fillGraphMenu);
        this.films.graph = this.graph;

        this.layoutChildren(this.leftHandle.getPixels(), this.bottomHandle.getPixels());

        this.leftHandle.relative(this.films).x(1F).y(0.5F).w(6).h(40).anchor(0.5F, 0.5F);
        /* Span the whole top edge of the details pane, not just a 40 px grip in the middle. */
        this.bottomHandle.relative(this.info).x(0).y(0).w(1F).h(8).anchor(0F, 0.5F);

        this.add(this.films, this.graph, this.info, this.search, this.filters, this.sort, this.refresh, this.leftHandle, this.bottomHandle);
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

        menu.action(Icons.COPY, L10n.lang("bbs_together.commits.copy_short").format(node.commit.shortId()),
            () -> Window.setClipboard(node.commit.shortId()));
        menu.action(Icons.COPY, L10n.lang("bbs_together.commits.copy_full").format(node.commit.shortId()),
            () -> Window.setClipboard(node.commit.id));
        menu.action(Icons.COPY, L10n.lang("bbs_together.commits.copy_message"),
            () -> Window.setClipboard(CommitText.display(node.commit.message)));

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
        menu.action(Icons.ARROW_DOWN, L10n.lang("bbs_together.commits.locate_head"), () -> this.graph.scrollToHead());
        menu.action(Icons.FILTER, L10n.lang("bbs_together.commits.hide_film").format(this.films.nameOf(node.filmId)), () ->
        {
            this.graph.setShown(node.filmId, false);
            this.refreshFilms();
        });
    }

    /** Place every child against the current splitter sizes. */
    private void layoutChildren(int left, int bottom)
    {
        this.films.relative(this).x(0).y(BAR_HEIGHT + 46).w(left).h(1F, -BAR_HEIGHT - 46 - bottom);
        this.graph.relative(this).xy(left, BAR_HEIGHT).w(1F, -left).h(1F, -BAR_HEIGHT - bottom);
        this.info.relative(this).x(0).y(1F, -bottom).w(1F).h(bottom);
        this.search.relative(this).x(8).y(BAR_HEIGHT + 4).w(left - 16).h(18);
        this.sort.relative(this).x(8).y(BAR_HEIGHT + 26).w(50).h(17);
        this.filters.relative(this).x(62).y(BAR_HEIGHT + 27).w(left - 68).h(15);
        this.refresh.relative(this).x(1F, -22).y((BAR_HEIGHT - 16) / 2).wh(16, 16);
    }

    private void layout()
    {
        this.layoutChildren(this.leftHandle.getPixels(), this.bottomHandle.getPixels());

        this.relayout();
    }

    /**
     * Apply the sizes above with a real resize, but only once the panel actually has an area.
     *
     * The graph's viewport is carved out of its area, and a single-pixel height (or zero width)
     * before the tab is measured would leave every row clamped against nothing. Re-applying the
     * resize on each frame until the panel is properly sized (and afterwards whenever a splitter
     * moved) is what keeps the very first open correct instead of needing a manual drag.
     */
    private void relayout()
    {
        if (this.area.w <= 0 || this.area.h <= 0)
        {
            return;
        }

        this.resize();
    }

    private void cycleSort()
    {
        this.sortMode = (this.sortMode + 1) % SORT_KEYS.length;
        this.sort.label = L10n.lang(SORT_KEYS[this.sortMode]);
        this.refreshFilms();
    }

    private void applyFilter()
    {
        this.films.filter(this.search.getText());
    }

    private void refreshFilms()
    {
        List<FilmCommitClient.IndexEntry> list = new ArrayList<>();

        for (FilmCommitClient.IndexEntry entry : FilmCommitClient.index())
        {
            if (this.filterMode == 1 && !this.graph.isShown(entry.id))
            {
                continue;
            }

            if (this.filterMode == 2 && !entry.id.equals(this.graph.actionFilm()))
            {
                continue;
            }

            list.add(entry);
        }

        if (this.sortMode == SORT_NAME)
        {
            list.sort(Comparator.comparing((FilmCommitClient.IndexEntry entry) -> this.films.nameOf(entry.id), String.CASE_INSENSITIVE_ORDER));
        }
        else if (this.sortMode == SORT_RECENT)
        {
            list.sort(Collections.reverseOrder(Comparator.comparingLong((FilmCommitClient.IndexEntry entry) -> this.latestCommit(entry.id))));
        }
        else
        {
            list.sort(Collections.reverseOrder(Comparator.comparingInt((FilmCommitClient.IndexEntry entry) -> entry.count)));
        }

        this.films.setList(list);
        this.films.update();
        this.films.filter(this.search.getText());
    }

    private long latestCommit(String filmId)
    {
        return latestCommit(FilmCommitClient.graph(filmId));
    }

    private static long latestCommit(FilmCommitClient.Graph graph)
    {
        if (graph == null)
        {
            return 0L;
        }

        long time = 0L;

        for (FilmCommit commit : graph.commits)
        {
            time = Math.max(time, commit.time);
        }

        return time;
    }

    @Override
    protected boolean subKeyPressed(UIContext context)
    {
        if (context.isPressed(GLFW.GLFW_KEY_F) && Window.isCtrlPressed())
        {
            context.focus(this.search);

            return true;
        }

        return super.subKeyPressed(context);
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

            this.refreshFilms();
            this.graph.rebuild();
            this.info.film(this.graph.actionFilm());
            this.info.refresh();
        }

        /* Make sure the children carry the current area: the panel can be opened before the tab
         * has been measured, in which case the layout never ran and the graph would be a
         * zero-sized strip until a splitter is touched. This is cheap once the sizes match. */
        this.layout();

        context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.y + BAR_HEIGHT, BBSSettings.chromeSurface());
        context.batcher.text(L10n.lang("bbs_together.commits.title").get(), this.area.x + 10, this.area.y + (BAR_HEIGHT - context.batcher.getFont().getHeight()) / 2, Colors.LIGHTEST_GRAY);

        List<FilmCommitClient.IndexEntry> index = FilmCommitClient.index();
        int total = 0;

        for (FilmCommitClient.IndexEntry entry : index)
        {
            total += entry.count;
        }

        String stats = L10n.lang("bbs_together.commits.stats").format(index.size(), total).get();
        int statsX = this.refresh.area.x - 10 - context.batcher.getFont().getWidth(stats);

        context.batcher.text(stats, statsX, this.area.y + (BAR_HEIGHT - context.batcher.getFont().getHeight()) / 2, Colors.GRAY);

        context.batcher.box(this.area.x, this.area.y + BAR_HEIGHT - 1, this.area.ex(), this.area.y + BAR_HEIGHT, BBSSettings.dividerColor());
        context.batcher.box(this.area.x, this.films.area.y, this.films.area.ex(), this.films.area.ey(), BBSSettings.baseSurface());
        context.batcher.box(this.graph.area.x, this.graph.area.y, this.graph.area.ex(), this.graph.area.ey(), BBSSettings.deepSurface());
        context.batcher.box(this.info.area.x, this.info.area.y, this.info.area.ex(), this.info.area.ey(), BBSSettings.raisedSurface());
        context.batcher.box(this.graph.area.x - 1, this.graph.area.y, this.graph.area.x, this.graph.area.ey(), BBSSettings.dividerColor());
        context.batcher.box(this.graph.area.x, this.graph.area.ey() - 1, this.graph.area.ex(), this.graph.area.ey(), BBSSettings.dividerColor());

        if (this.films.getList().isEmpty())
        {
            context.batcher.text(L10n.lang("bbs_together.commits.empty_filter").get(), this.films.area.x + 10, this.films.area.y + 14, Colors.GRAY);
        }

        super.render(context);
    }

    /** A compact segmented control used for All / Shown / Current film. */
    private static class FilterTabs extends UIElement
    {
        /** Keep language keys instead of resolved text so a language switch updates the tabs. */
        private final String[] labelKeys;
        private final IntConsumer callback;
        private int index;

        public FilterTabs(String[] labelKeys, IntConsumer callback)
        {
            this.labelKeys = labelKeys;
            this.callback = callback;
        }

        @Override
        public void render(UIContext context)
        {
            int tabWidth = this.area.w / this.labelKeys.length;

            for (int i = 0; i < this.labelKeys.length; i++)
            {
                int x = this.area.x + i * tabWidth;
                boolean active = i == this.index;
                boolean hover = context.mouseX >= x && context.mouseX < x + tabWidth && context.mouseY >= this.area.y && context.mouseY < this.area.ey();

                if (active)
                {
                    context.batcher.box(x, this.area.y, x + tabWidth, this.area.ey(), BBSSettings.primaryColor(Colors.A25));
                }
                else if (hover)
                {
                    context.batcher.box(x, this.area.y, x + tabWidth, this.area.ey(), Colors.A6);
                }

                String label = L10n.lang(this.labelKeys[i]).get();
                int textX = x + (tabWidth - context.batcher.getFont().getWidth(label)) / 2;
                int textY = this.area.y + (this.area.h - context.batcher.getFont().getHeight()) / 2;

                context.batcher.text(label, textX, textY, active ? Colors.WHITE : Colors.GRAY);

                if (active)
                {
                    context.batcher.box(x + 2, this.area.ey() - 2, x + tabWidth - 2, this.area.ey(), BBSSettings.primaryColor());
                }
            }

            super.render(context);
        }

        @Override
        public boolean subMouseClicked(UIContext context)
        {
            if (context.mouseButton == 0 && this.area.isInside(context) && this.area.w > 0)
            {
                int tabWidth = this.area.w / this.labelKeys.length;
                int index = (context.mouseX - this.area.x) / Math.max(1, tabWidth);

                if (index >= 0 && index < this.labelKeys.length)
                {
                    this.index = index;
                    this.callback.accept(index);

                    return true;
                }
            }

            return super.subMouseClicked(context);
        }
    }

    /** The films that have commits, each with a checkbox that toggles its column in the graph. */
    private static class UIFilmFilterList extends UIList<FilmCommitClient.IndexEntry>
    {
        /** Set by the page so a toggle immediately rebuilds the graph. */
        public UICommitGraph graph;

        public UIFilmFilterList(Consumer<List<FilmCommitClient.IndexEntry>> callback)
        {
            super(callback);

            this.scroll.scrollItemSize = 34;
        }

        public boolean isShown(FilmCommitClient.IndexEntry entry)
        {
            return this.graph == null || this.graph.isShown(entry.id);
        }

        public String nameOf(String id)
        {
            int slash = id.lastIndexOf('/');

            return slash < 0 ? id : id.substring(slash + 1);
        }

        private String name(FilmCommitClient.IndexEntry entry)
        {
            return this.nameOf(entry.id);
        }

        private String fit(UIContext context, String text, int width)
        {
            if (context.batcher.getFont().getWidth(text) <= width)
            {
                return text;
            }

            while (text.length() > 1 && context.batcher.getFont().getWidth(text + "...") > width)
            {
                text = text.substring(0, text.length() - 1);
            }

            return text + "...";
        }

        @Override
        protected String elementToString(UIContext context, int i, FilmCommitClient.IndexEntry entry)
        {
            FilmCommitClient.Graph graph = FilmCommitClient.graph(entry.id);
            String branch = graph == null ? "" : graph.current;

            return branch.isEmpty() ? this.name(entry) : this.name(entry) + " " + branch;
        }

        @Override
        public void renderElementPart(UIContext context, FilmCommitClient.IndexEntry entry, int i, int x, int y, boolean hover, boolean selected)
        {
            int s = this.scroll.scrollItemSize;
            boolean shown = this.isShown(entry);

            RowStyle.row(context.batcher, x, y, this.area.w, s, 0, false, hover, selected);
            context.batcher.box(x + 6, y + 9, x + 16, y + 19, shown ? BBSSettings.primaryColor() : Colors.A25);
            context.batcher.box(x + 8, y + 11, x + 14, y + 17, shown ? BBSSettings.chromeSurface() : BBSSettings.baseSurface());

            String name = this.fit(context, this.name(entry), this.area.w - 54);
            String count = String.valueOf(entry.count);
            int countX = x + this.area.w - 8 - context.batcher.getFont().getWidth(count);

            context.batcher.text(name, x + 22, y + 4, shown ? Colors.WHITE : Colors.GRAY);
            context.batcher.text(count, countX, y + 4, Colors.GRAY);

            FilmCommitClient.Graph graph = FilmCommitClient.graph(entry.id);
            long latest = latestCommit(graph);
            String branch = graph == null || graph.current == null || graph.current.isEmpty() ? "main" : graph.current;
            String branchText = this.fit(context, branch, this.area.w - 72);
            String time = latest > 0 ? CommitText.relativeTime(latest) : "";

            context.batcher.text(branchText, x + 22, y + 18, Colors.LIGHTER_GRAY);
            context.batcher.text(time, x + this.area.w - 8 - context.batcher.getFont().getWidth(time), y + 18, Colors.GRAY);
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
