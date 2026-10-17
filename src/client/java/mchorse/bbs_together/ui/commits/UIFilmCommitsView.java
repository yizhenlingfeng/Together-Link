package mchorse.bbs_together.ui.commits;

import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.utils.context.ContextMenuManager;
import mchorse.bbs_mod.ui.utils.icons.Icons;

import java.util.function.Supplier;

/**
 * The commits view of a single film, made to live inside the film editor's dock next to the
 * properties panel: the graph, the selected commit under it, and the buttons that change the
 * history at the bottom.
 */
public class UIFilmCommitsView extends UIElement
{
    public static final int INFO_H = 150;
    public static final int ACTIONS_H = 114;
    public static final int MIN_GRAPH_H = 80;

    private final Supplier<String> filmId;
    private final UICommitGraph graph;
    private final UICommitInfo info;
    private final UICommitActions actions;

    private String shown = "";
    private int seen = -1;

    public UIFilmCommitsView(Supplier<String> filmId)
    {
        this.filmId = filmId;
        this.graph = new UICommitGraph();
        this.info = new UICommitInfo(false);
        this.actions = new UICommitActions(() -> this.graph);

        this.graph.relative(this).x(0).y(0).w(1F).h(1F, -INFO_H - ACTIONS_H);
        this.info.relative(this).x(0).y(1F, -INFO_H - ACTIONS_H).w(1F).h(INFO_H);
        this.actions.relative(this).x(0).y(1F, -ACTIONS_H).w(1F).h(ACTIONS_H);

        this.graph.listener = (node) -> {
            this.info.show(node);
            this.actions.update();
        };
        this.graph.context(this::fillGraphMenu);

        this.add(this.graph, this.info, this.actions);
    }

    /**
     * A dock column can be short. With the fixed stack the graph would get a negative height and
     * the tab would open on an empty pane, so the parts give way in order — buttons first, then
     * the details — and the graph always keeps a usable slice.
     */
    @Override
    protected void afterResizeApplied()
    {
        int h = this.area.h;
        int actions = h >= ACTIONS_H * 2 ? ACTIONS_H : 0;
        int reserved = this.info.hasSelection() ? INFO_H : 0;
        int info = Math.min(reserved, Math.max(0, h - actions - MIN_GRAPH_H));
        int graph = Math.max(0, h - actions - info);

        this.graph.relative(this).x(0).y(0).w(1F).h(graph);
        this.info.relative(this).x(0).y(graph).w(1F).h(info);
        this.actions.relative(this).x(0).y(graph + info).w(1F).h(actions);

        this.info.setVisible(info > 0);
        this.actions.setVisible(actions > 0);
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

        if (!node.commit.parent().isEmpty())
        {
            menu.action(Icons.UNDO, L10n.lang("bbs_together.commits.revert"), () -> CommitUi.confirmRevert(context, node.filmId, node.commit.id));
        }

        menu.action(Icons.REFRESH, L10n.lang("bbs_together.commits.reset"), () -> CommitUi.confirmReset(context, node.filmId, node.commit.id));
        menu.action(Icons.ADD, L10n.lang("bbs_together.commits.branch_here").format(node.commit.shortId()), () -> this.actions.wantBranchAt(node.commit.id));

        menu.action(Icons.COLOR, L10n.lang("bbs_together.commits.commit_color").format(node.commit.shortId()), () -> this.graph.openCommitColor(context, node.filmId, node.commit.id, node.x, node.y));
    }

    @Override
    public void render(UIContext context)
    {
        String id = this.filmId.get();

        if (id == null)
        {
            id = "";
        }

        int version = FilmCommitClient.version();

        if (!id.equals(this.shown))
        {
            this.shown = id;
            this.graph.showOnly(id);
            this.info.refresh();
            this.actions.update();
            this.seen = version;
        }
        else if (version != this.seen)
        {
            this.seen = version;
            this.graph.rebuild();
            this.info.refresh();
            this.actions.update();
        }

        super.render(context);
    }
}
