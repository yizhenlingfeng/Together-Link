package mchorse.bbs_together.ui.commits;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.input.color.UIColorPicker;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_together.commits.FilmCommit;
import mchorse.bbs_together.network.TogetherClientNetwork;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The graph itself: one column per film, and inside a film one lane per branch, like git's
 * graph. A commit sits on the lane of the branch it was made on and is connected to its parents;
 * a branch that was started from an older commit therefore leaves the trunk and runs beside it.
 *
 * <p>All shown films share one vertical time axis (newest commit on top), which is what makes
 * two films' timelines comparable at a glance. Edges are drawn as 2px boxes because the batcher
 * has no line primitive.</p>
 */
public class UICommitGraph extends UIElement
{
    public static final int HEADER = 18;
    public static final int ROW_H = 20;
    public static final int NODE = 9;
    /** Horizontal distance between two branch lanes. */
    private static final int LANE = 14;
    private static final int MIN_COLUMN = 220;
    /** Edges never take the colour of a commit: they stay a quiet dark gray underneath. */
    private static final int LINE_COLOR = 0xff4a4a4a;

    /** One commit placed in the graph. */
    public static final class Node
    {
        public String filmId = "";
        public FilmCommit commit;
        /** Branch lane inside the film's column. */
        public int lane;
        public int row;
        public int column;
        public int x;
        public int y;
        /** Rows and lanes of the parents, resolved after every node has a row. */
        public final List<int[]> parents = new ArrayList<int[]>();
        public boolean tip;
    }

    /** Called when the user picks a node. */
    public interface SelectionListener
    {
        void onSelect(Node node);
    }

    private final Map<String, Boolean> shown = new LinkedHashMap<String, Boolean>();
    private final Map<String, Long> requested = new HashMap<String, Long>();
    private final List<String> columnOrder = new ArrayList<String>();
    private final List<Node> nodes = new ArrayList<Node>();

    private Node selected;
    private int scroll;
    private int version = -1;

    /** When set, only this film is drawn (opened from the film editor). */
    private String solo;

    public SelectionListener listener;

    /** Whether at least one commit of {@code filmId} is displayed. */
    public boolean isShown(String filmId)
    {
        if (this.solo != null)
        {
            return this.solo.equals(filmId);
        }

        return this.shown.containsKey(filmId) ? this.shown.get(filmId) : true;
    }

    /** Restrict the graph to one film; an empty id shows every film of the index. */
    public void showOnly(String filmId)
    {
        /* null, not "", means "no pin": an empty pin would make isShown() reject every film */
        this.solo = filmId == null || filmId.isEmpty() ? null : filmId;

        this.rebuild();
    }

    /** Film the actions of the panel apply to: the pinned film, else the selected commit's. */
    public String actionFilm()
    {
        if (this.solo != null)
        {
            return this.solo;
        }

        if (this.selected != null)
        {
            return this.selected.filmId;
        }

        return this.columnOrder.isEmpty() ? "" : this.columnOrder.get(0);
    }

    public void setShown(String filmId, boolean show)
    {
        this.shown.put(filmId, show);

        this.rebuild();
    }

    public Node selected()
    {
        return this.selected;
    }

    public void select(Node node)
    {
        this.selected = node;

        /* A null node means the click landed on empty space: the listener clears the details. */
        if (this.listener != null)
        {
            this.listener.onSelect(node);
        }
    }

    /** Rebuild the flattened node list from the cache; called when the cache version changed. */
    public void rebuild()
    {
        this.nodes.clear();
        this.columnOrder.clear();

        if (this.solo != null)
        {
            /* The film editor knows its film without the index: fetch that film directly */
            if (FilmCommitClient.graph(this.solo) == null)
            {
                this.request(this.solo);
            }
            else
            {
                this.columnOrder.add(this.solo);
            }
        }
        else
        {
            for (FilmCommitClient.IndexEntry entry : FilmCommitClient.index())
            {
                if (!this.isShown(entry.id))
                {
                    continue;
                }

                if (FilmCommitClient.graph(entry.id) == null)
                {
                    this.request(entry.id);

                    continue;
                }

                this.columnOrder.add(entry.id);
            }
        }

        /* Every commit of every shown film, newest first: one shared time axis */
        List<Node> all = new ArrayList<Node>();
        Map<String, Node> byId = new HashMap<String, Node>();

        for (int column = 0; column < this.columnOrder.size(); column++)
        {
            String filmId = this.columnOrder.get(column);
            FilmCommitClient.Graph graph = FilmCommitClient.graph(filmId);

            if (graph == null)
            {
                continue;
            }

            List<FilmCommit> commits = new ArrayList<FilmCommit>(graph.commits);

            commits.sort((a, b) -> Long.compare(b.time, a.time));

            Map<String, Integer> lanes = new HashMap<String, Integer>();
            Map<String, String> heads = new HashMap<String, String>();

            for (FilmCommit commit : commits)
            {
                Node node = new Node();

                node.filmId = filmId;
                node.commit = commit;
                node.column = column;
                node.lane = this.laneOf(commit, lanes, heads);
                node.tip = heads.get(commit.branch) != null && heads.get(commit.branch).equals(commit.id);

                all.add(node);
                byId.put(filmId + "/" + commit.id, node);
            }
        }

        all.sort((a, b) -> Long.compare(b.commit.time, a.commit.time));

        for (int i = 0; i < all.size(); i++)
        {
            all.get(i).row = i;
        }

        for (Node node : all)
        {
            for (String parent : node.commit.parents)
            {
                Node target = byId.get(node.filmId + "/" + parent);

                if (target != null)
                {
                    node.parents.add(new int[]{target.row, target.lane});
                }
            }
        }

        this.nodes.addAll(all);
        this.version = FilmCommitClient.version();

        if (this.selected != null && !this.nodes.contains(this.selected))
        {
            this.selected = this.nodes.isEmpty() ? null : this.nodes.get(0);
        }

        this.clampScroll();
    }

    /**
     * Lane for a commit: its branch's lane, allocated on first sight. Branches keep the order in
     * which their newest commit appeared, so the branch that is being worked on stays leftmost.
     */
    private int laneOf(FilmCommit commit, Map<String, Integer> lanes, Map<String, String> heads)
    {
        Integer lane = lanes.get(commit.branch);

        if (lane == null)
        {
            lane = lanes.size();

            lanes.put(commit.branch, lane);
        }

        /* Commits arrive newest first, so the first commit of a branch is its head */
        if (!heads.containsKey(commit.branch))
        {
            heads.put(commit.branch, commit.id);
        }

        return lane;
    }

    /** Ask for a film's graph, but not more than once every few seconds. */
    private void request(String filmId)
    {
        Long last = this.requested.get(filmId);

        if (last == null || System.currentTimeMillis() - last > 3000L)
        {
            this.requested.put(filmId, System.currentTimeMillis());

            TogetherClientNetwork.sendCommitsQuery(filmId);
        }
    }

    private void clampScroll()
    {
        int content = HEADER + this.nodes.size() * ROW_H + 8;
        int max = Math.max(0, content - this.area.h);

        if (this.scroll > max)
        {
            this.scroll = max;
        }

        if (this.scroll < 0)
        {
            this.scroll = 0;
        }
    }

    private int columnWidth()
    {
        int columns = Math.max(1, this.columnOrder.size());

        return Math.max(MIN_COLUMN, this.area.w / columns);
    }

    private int columnX(int column)
    {
        return this.area.x + 10 + column * this.columnWidth();
    }

    private int laneX(Node node)
    {
        return this.columnX(node.column) + node.lane * LANE;
    }

    /** Where the commit text of a node starts: right of the film's widest lane use. */
    private int textX(Node node)
    {
        return this.columnX(node.column) + this.lanesOf(node.filmId) * LANE + 10;
    }

    private int lanesOf(String filmId)
    {
        int lanes = 1;

        for (Node node : this.nodes)
        {
            if (node.filmId.equals(filmId))
            {
                lanes = Math.max(lanes, node.lane + 1);
            }
        }

        return lanes;
    }

    private int rowY(int row)
    {
        return this.area.y + HEADER + row * ROW_H + ROW_H / 2 - this.scroll;
    }

    private void layoutNodes()
    {
        for (Node node : this.nodes)
        {
            node.x = this.laneX(node);
            node.y = this.rowY(node.row);
        }
    }

    @Override
    public void render(UIContext context)
    {
        if (this.version != FilmCommitClient.version())
        {
            this.rebuild();
        }

        this.layoutNodes();

        int header = BBSSettings.color(BBSSettings.chromeSurface(), Colors.A50);

        context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.y + HEADER, header);

        for (int i = 0; i < this.columnOrder.size(); i++)
        {
            String filmId = this.columnOrder.get(i);
            String branch = this.headBranch(filmId);
            String label = this.shortName(filmId) + (branch.isEmpty() ? "" : " · " + branch);

            context.batcher.text(label, this.columnX(i), this.area.y + 5, Colors.LIGHTER_GRAY);
        }

        if (this.nodes.isEmpty())
        {
            context.batcher.text(L10n.lang("bbs_together.commits.empty").get(), this.area.x + 10, this.area.y + HEADER + 8, Colors.GRAY);

            super.render(context);

            return;
        }

        /* Edges first, so nodes sit on top of their lines */
        for (Node node : this.nodes)
        {
            int x = node.x;

            for (int[] parent : node.parents)
            {
                int parentY = this.rowY(parent[0]);
                int parentX = this.columnX(node.column) + parent[1] * LANE;

                /* Down from the commit to the parent's row ... */
                this.line(context, x, node.y, x, parentY, LINE_COLOR);

                /* ... then across into the parent's lane */
                this.line(context, x, parentY, parentX, parentY, LINE_COLOR);
            }
        }

        int accent = BBSSettings.primaryColor();

        for (Node node : this.nodes)
        {
            if (node.y < this.area.y + HEADER - ROW_H || node.y > this.area.ey() + ROW_H)
            {
                continue;
            }

            int left = node.x - NODE / 2;
            int top = node.y - NODE / 2;
            int right = node.x + NODE / 2 + 1;
            int bottom = node.y + NODE / 2 + 1;
            int color = CommitColors.get(node.filmId, node.commit.id);

            context.batcher.box(left, top, right, bottom, color);

            /* The commit the film file currently sits at gets a glowing stroke, so its place in
             * the history can be found without reading every row */
            if (node.tip && node.commit.id.equals(this.currentCommit(node.filmId)))
            {
                context.batcher.outline(left - 2, top - 2, right + 2, bottom + 2, accent);
            }

            if (node == this.selected)
            {
                context.batcher.outline(left - 3, top - 3, right + 3, bottom + 3, Colors.WHITE);
            }

            String text = node.commit.shortId() + "  " + node.commit.message;
            int width = this.columnX(node.column) + this.columnWidth() - this.textX(node) - 8;

            context.batcher.text(this.fit(context, text, Math.max(24, width)), this.textX(node), node.y - 4, node == this.selected ? Colors.WHITE : Colors.LIGHTEST_GRAY);

            /* Branch name next to the commit that is its head, like git's decoration */
            if (node.tip)
            {
                int dot = this.textX(node) + context.batcher.getFont().getWidth(this.fit(context, text, Math.max(24, width))) + 6;

                if (dot + 40 < this.columnX(node.column) + this.columnWidth())
                {
                    context.batcher.text("[" + node.commit.branch + "]", dot, node.y - 4, Colors.GRAY);
                }
            }
        }

        super.render(context);
    }

    /** Two pixel wide line out of boxes, clipped to the graph's body. */
    private void line(UIContext context, int x1, int y1, int x2, int y2, int color)
    {
        int top = Math.max(this.area.y + HEADER, Math.min(y1, y2));
        int bottom = Math.min(this.area.ey(), Math.max(y1, y2) + 1);

        if (top > bottom)
        {
            return;
        }

        if (x1 == x2)
        {
            context.batcher.box(x1 - 1, top, x1 + 1, bottom, color);
        }
        else
        {
            int left = Math.min(x1, x2) - 1;
            int right = Math.max(x1, x2) + 1;

            context.batcher.box(left, y2 - 1, right, y2 + 1, color);
        }
    }

    private String headBranch(String filmId)
    {
        for (Node node : this.nodes)
        {
            if (node.filmId.equals(filmId) && node.tip && node.row == 0)
            {
                return node.commit.branch;
            }
        }

        for (Node node : this.nodes)
        {
            if (node.filmId.equals(filmId))
            {
                return node.commit.branch;
            }
        }

        return "";
    }

    /**
     * The full colour editor BBS opens for a colour property: HSV/RGB fields, hex input and the
     * saved colours. The context menu's "commit colour" item opens this one.
     */
    public void openCommitColor(UIContext context, String filmId, String commitId, int x, int y)
    {
        if (commitId == null || commitId.isEmpty())
        {
            return;
        }

        UIColorPicker picker = new UIColorPicker((color) -> CommitColors.set(filmId, commitId, color));

        picker.setColor(CommitColors.get(filmId, commitId));

        context.menu.overlay.add(picker);
        picker.setup(context.globalX(x), context.globalY(y));
        picker.bounds(context.menu.main, 2);
        picker.resize();
    }

    private String shortName(String filmId)
    {
        int slash = filmId.lastIndexOf('/');

        return slash < 0 ? filmId : filmId.substring(slash + 1);
    }

    /** Commit the film file is currently saved at: the tip of the branch it is on. */
    private String currentCommit(String filmId)
    {
        FilmCommitClient.Graph graph = FilmCommitClient.graph(filmId);

        if (graph == null)
        {
            return "";
        }

        String tip = graph.branches.get(graph.current);

        return tip == null ? graph.head : tip;
    }

    private String fit(UIContext context, String text, int width)
    {
        if (context.batcher.getFont().getWidth(text) <= width)
        {
            return text;
        }

        while (text.length() > 3 && context.batcher.getFont().getWidth(text + "...") > width)
        {
            text = text.substring(0, text.length() - 1);
        }

        return text + "...";
    }

    @Override
    public boolean subMouseClicked(UIContext context)
    {
        if (context.mouseButton == 0 && this.area.isInside(context))
        {
            Node hit = this.nodeAt(context.mouseX, context.mouseY);

            /* Empty space means nothing is selected: the details pane closes again. */
            this.select(hit);

            return true;
        }

        return super.subMouseClicked(context);
    }

    public Node nodeAt(int x, int y)
    {
        this.layoutNodes();

        for (Node node : this.nodes)
        {
            if (Math.abs(node.x - x) <= NODE && Math.abs(node.y - y) <= NODE)
            {
                return node;
            }
        }

        return null;
    }

    @Override
    public boolean subMouseScrolled(UIContext context)
    {
        if (this.area.isInside(context) && context.mouseWheel != 0D)
        {
            this.scroll -= (int) Math.signum(context.mouseWheel) * ROW_H * 3;
            this.clampScroll();

            return true;
        }

        return super.subMouseScrolled(context);
    }
}
