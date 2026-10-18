package mchorse.bbs_together.ui.commits;

import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.utils.UISplitter;
import mchorse.bbs_mod.ui.utils.context.ContextMenuManager;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_together.network.TogetherClientNetwork;
import mchorse.bbs_together.network.TogetherEditSyncQueue;

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
    public static final int MIN_INFO_H = 60;

    private final Supplier<String> filmId;
    private final UICommitGraph graph;
    private final UICommitInfo info;
    private final UICommitActions actions;
    private final UISplitter infoHandle;

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

        /* The details pane's top edge is a handle: the pending changes can be tall, so the dock
         * lets the user trade graph room for details room */
        this.infoHandle = UISplitter.pixels("bbs_together.commits.info", INFO_H, MIN_INFO_H, 800).vertical().fromEnd();
        this.infoHandle.measure(this.info).onChange(this::layouts);
        this.infoHandle.range(MIN_INFO_H, () -> (float) Math.max(MIN_INFO_H, this.area.h - this.actionsHeight() - MIN_GRAPH_H));
        this.infoHandle.relative(this.info).x(0.5F).y(0).w(40).h(6).anchor(0.5F, 0.5F);

        this.graph.listener = (node) -> {
            this.info.show(this.filmId.get(), node);
            this.actions.update();
        };
        this.graph.context(this::fillGraphMenu);

        this.add(this.graph, this.info, this.actions, this.infoHandle);
    }

    private int actionsHeight()
    {
        return this.area.h >= ACTIONS_H * 2 ? ACTIONS_H : 0;
    }

    private int infoHeight()
    {
        if (!this.info.hasContent())
        {
            return 0;
        }

        int available = Math.max(0, this.area.h - this.actionsHeight() - MIN_GRAPH_H);

        return Math.min(Math.max(MIN_INFO_H, this.infoHandle.getPixels()), available);
    }

    private void layouts()
    {
        if (this.area.h > 0)
        {
            this.resize();
        }
    }

    /**
     * A dock column can be short, so the parts give way in order — buttons first, then the
     * details — and the graph always keeps a usable slice.
     */
    @Override
    protected void afterResizeApplied()
    {
        int h = this.area.h;
        int actions = this.actionsHeight();
        int info = this.infoHeight();
        int graph = Math.max(0, h - actions - info);

        this.graph.relative(this).x(0).y(0).w(1F).h(graph);
        this.info.relative(this).x(0).y(graph).w(1F).h(info);
        this.actions.relative(this).x(0).y(graph + info).w(1F).h(actions);

        this.info.setVisible(info > 0);
        this.actions.setVisible(actions > 0);
        this.infoHandle.setVisible(info > 0);
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

        if (CommitUi.canRevert(node))
        {
            menu.action(Icons.UNDO, L10n.lang("bbs_together.commits.revert"), () -> CommitUi.confirmRevert(context, node.filmId, node.commit.id));
        }

        if (CommitUi.canSquash(node))
        {
            menu.action(Icons.TREE, L10n.lang("bbs_together.commits.squash").format(node.commit.shortId()), () -> CommitUi.confirmSquash(context, node.filmId, node.commit.id));
        }

        menu.action(Icons.ADD, L10n.lang("bbs_together.commits.branch_here").format(node.commit.shortId()), () -> this.actions.wantBranchAt(node.commit.id));

        if (!node.commit.branch.isEmpty())
        {
            menu.action(Icons.RIGHTLOAD, L10n.lang("bbs_together.commits.branch_checkout_here").format(node.commit.branch), () -> {
                TogetherEditSyncQueue.flushNow(CommitUi.filmPanel());
                TogetherClientNetwork.sendBranchCheckout(node.filmId, node.commit.branch);
            });
        }

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
            this.info.show(id, this.graph.selected());
            this.actions.update();
            this.resize();
            this.seen = version;
        }
        else if (version != this.seen)
        {
            this.seen = version;
            this.graph.rebuild();
            this.info.film(id);
            this.info.refresh();
            this.actions.update();
        }

        super.render(context);
    }
}
