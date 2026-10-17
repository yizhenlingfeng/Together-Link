package mchorse.bbs_together.ui.commits;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.input.color.UIColorPicker;
import mchorse.bbs_mod.ui.framework.elements.utils.RowStyle;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.Scroll;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_together.commits.FilmCommit;
import mchorse.bbs_together.network.TogetherClientNetwork;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The graph itself: one column per film, and inside a film one lane per branch, like git's
 * graph. A commit sits on the lane of the branch it was made on and is connected to its parents.
 *
 * <p>All shown films share one vertical time axis, drawn like a log: the oldest commit sits on
 * top and the newest one at the bottom, so the view can stick to the bottom. Edges are drawn as
 * 2px boxes because the batcher has no line primitive.</p>
 */
public class UICommitGraph extends UIElement
{
    public static final int HEADER = 20;
    public static final int ROW_H = 18;
    public static final int NODE = 9;
    /** Horizontal distance between two branch lanes. */
    private static final int LANE = 14;
    private static final int MIN_COLUMN = 220;
    /** Edges never take the colour of a commit: they stay a quiet dark gray underneath. */
    private static final int LINE_COLOR = 0xff4a4a4a;
    /** Edges of commits the film no longer sits on: quieter still. */
    private static final int INACTIVE_LINE = 0xff303030;

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
        /** On the film's current line: the head the film sits at, or one of its ancestors. */
        public boolean active = true;
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
    private int version = -1;
    /** Everything under the header: rows and lanes scroll, the header itself stays put. */
    private final Area body = new Area();
    /** FS' own scroll view: wheel, smoothing, dragging and the scrollbar all come from it. */
    private final Scroll scroll = new Scroll(this.body, ROW_H);
    /** Whether the view sticks to the bottom (newest commit) as the history grows. */
    private boolean pinned = true;
    /** Scratch list for {@link #segment}: the bands of commits a line has to step around. */
    private final List<int[]> bands = new ArrayList<int[]>();

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

    /** Branch a new commit of {@code filmId} lands on: the selected commit's branch, else the film's. */
    public String commitBranch(String filmId)
    {
        if (this.selected != null && (filmId.isEmpty() || filmId.equals(this.selected.filmId)) && !this.selected.commit.branch.isEmpty())
        {
            return this.selected.commit.branch;
        }

        FilmCommitClient.Graph graph = FilmCommitClient.graph(filmId);

        return graph == null ? "" : graph.current;
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

            for (FilmCommit commit : commits)
            {
                Node node = new Node();

                node.filmId = filmId;
                node.commit = commit;
                node.column = column;
                node.lane = this.laneOf(commit, lanes);
                node.tip = this.isTip(graph, commit);

                all.add(node);
                byId.put(filmId + "/" + commit.id, node);
            }
        }

        all.sort((a, b) -> Long.compare(b.commit.time, a.commit.time));

        /* The list is newest first, the display is oldest first: rows count down from the
         * top of the view, so the newest commit ends up on the last row at the bottom */
        for (int i = 0; i < all.size(); i++)
        {
            all.get(i).row = all.size() - 1 - i;
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

        /* Work out which commits are still on the film's line before drawing anything: after a
         * rollback the dropped commits stay in the graph but must not look current any more */
        Map<String, Set<String>> lines = new HashMap<String, Set<String>>();

        for (String filmId : this.columnOrder)
        {
            FilmCommitClient.Graph graph = FilmCommitClient.graph(filmId);

            if (graph != null)
            {
                lines.put(filmId, this.activeIds(graph));
            }
        }

        for (Node node : all)
        {
            Set<String> line = lines.get(node.filmId);

            node.active = line == null || line.contains(node.commit.id);
        }

        this.version = FilmCommitClient.version();

        if (this.selected != null && !this.nodes.contains(this.selected))
        {
            this.selected = this.nodes.isEmpty() ? null : this.nodes.get(0);
        }

        this.updateScroll();
    }

    /**
     * Lane for a commit: its branch's lane, allocated on first sight. Branches keep the order in
     * which their newest commit appeared, so the branch that is being worked on stays leftmost.
     */
    private int laneOf(FilmCommit commit, Map<String, Integer> lanes)
    {
        Integer lane = lanes.get(commit.branch);

        if (lane == null)
        {
            lane = lanes.size();

            lanes.put(commit.branch, lane);
        }

        return lane;
    }

    /**
     * Whether this commit is its branch's head, taken from the branch table rather than from the
     * list order: a rollback that kept the later commits leaves the head below them.
     */
    private boolean isTip(FilmCommitClient.Graph graph, FilmCommit commit)
    {
        String head = graph.branches.get(commit.branch);

        return head != null && head.equals(commit.id);
    }

    /** Ids of the commits on the film's current line: its head plus all of its ancestors. */
    private Set<String> activeIds(FilmCommitClient.Graph graph)
    {
        Map<String, FilmCommit> byId = new HashMap<String, FilmCommit>();

        for (FilmCommit commit : graph.commits)
        {
            byId.put(commit.id, commit);
        }

        String head = graph.branches.get(graph.current);

        if (head == null)
        {
            head = graph.head;
        }

        Set<String> active = new HashSet<String>();
        Deque<String> pending = new ArrayDeque<String>();

        if (head != null && !head.isEmpty())
        {
            pending.push(head);
        }

        while (!pending.isEmpty())
        {
            String id = pending.pop();

            if (!active.add(id))
            {
                continue;
            }

            FilmCommit commit = byId.get(id);

            if (commit != null)
            {
                for (String parent : commit.parents)
                {
                    pending.push(parent);
                }
            }
        }

        return active;
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

    /** Keep the content height and the scroll in sync, and follow the bottom while pinned. */
    private void updateScroll()
    {
        this.scroll.scrollSize = this.nodes.size() * ROW_H + 8;
        this.scroll.clamp();

        /* Sticking to the bottom keeps the newest commit visible while the history grows */
        if (this.pinned)
        {
            this.scroll.scrollToEnd();
        }
        else if (this.atEnd())
        {
            this.pinned = true;
        }
    }

    /** Whether the view sits at the newest commit, where it attaches itself to the bottom. */
    private boolean atEnd()
    {
        return this.scroll.getScroll() + this.body.h >= this.scroll.scrollSize - 1;
    }

    @Override
    public void resize()
    {
        super.resize();

        /* Before the first layout the area is empty: Scroll.clamp() then keeps the view at
         * zero, so nothing jumps while the panel is still being built */
        this.body.set(this.area.x, this.area.y + HEADER, this.area.w, Math.max(0, this.area.h - HEADER));

        this.updateScroll();
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

    private int rowTop(int row)
    {
        return this.body.y + row * ROW_H - (int) this.scroll.getScroll();
    }

    private int rowY(int row)
    {
        return this.rowTop(row) + ROW_H / 2;
    }

    /** Whether the cursor is on this node's row: FS rows light up as a whole, not per marker. */
    private boolean isHover(UIContext context, Node node, int top)
    {
        return this.body.isInside(context.mouseX, context.mouseY)
            && context.mouseY >= top && context.mouseY < top + ROW_H
            && context.mouseX >= this.columnX(node.column) - 8
            && context.mouseX < this.columnX(node.column) + this.columnWidth() - 4;
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

        /* Smooth scrolling and scrollbar dragging are advanced here, like every FS view */
        this.scroll.drag(context.mouseX, context.mouseY);
        this.updateScroll();
        this.layoutNodes();

        int header = BBSSettings.color(BBSSettings.chromeSurface(), Colors.A50);

        context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.y + HEADER, header);
        context.batcher.box(this.area.x, this.area.y + HEADER - 1, this.area.ex(), this.area.y + HEADER, BBSSettings.dividerColor());

        for (int i = 0; i < this.columnOrder.size(); i++)
        {
            String filmId = this.columnOrder.get(i);
            String branch = this.headBranch(filmId);
            String name = this.shortName(filmId);
            int x = this.columnX(i);
            int y = this.area.y + (HEADER - context.batcher.getFont().getHeight()) / 2;

            context.batcher.textShadow(name, x, y, RowStyle.textColor(true));

            if (!branch.isEmpty())
            {
                context.batcher.textShadow("· " + branch, x + context.batcher.getFont().getWidth(name) + 5, y, Colors.GRAY);
            }
        }

        /* Rows are clipped to the body: the header carries the film labels, so a half drawn
         * node sitting on top of it would look like a bug */
        context.batcher.clip(this.body, context);

        if (this.nodes.isEmpty())
        {
            String empty = L10n.lang("bbs_together.commits.empty").get();

            context.batcher.text(empty, this.body.x + (this.body.w - context.batcher.getFont().getWidth(empty)) / 2, this.body.my() - context.batcher.getFont().getHeight() / 2, Colors.GRAY);
        }
        else
        {
            this.renderRows(context);
            this.renderEdges(context);
            this.renderNodes(context);
        }

        context.batcher.unclip(context);

        this.scroll.renderScrollbar(context.batcher);

        super.render(context);
    }

    /** Row washes, the same mark FS puts under every list row: hover, pick and its bar. */
    private void renderRows(UIContext context)
    {
        int accent = BBSSettings.primaryColor();

        for (Node node : this.nodes)
        {
            int top = this.rowTop(node.row);

            if (top + ROW_H <= this.body.y || top >= this.body.ey())
            {
                continue;
            }

            int x = this.columnX(node.column) - 8;
            int w = this.columnWidth() - 4;
            boolean selected = node == this.selected;

            /* Mark where the film sits, so it stays visible when the pick is elsewhere. Laid down
             * before the pick's own marks, so a selection on the same row outranks it */
            if (!selected && node.commit.id.equals(this.currentCommit(node.filmId)))
            {
                RowStyle.carried(context.batcher, x, top, w, ROW_H);
                context.batcher.box(x, top, x + 2, top + ROW_H, accent);
            }

            RowStyle.row(context.batcher, x, top, w, ROW_H, 0, false, this.isHover(context, node, top), selected);
        }
    }

    /** Edges first, so nodes sit on top of their lines. */
    private void renderEdges(UIContext context)
    {
        for (Node node : this.nodes)
        {
            int color = node.active ? LINE_COLOR : INACTIVE_LINE;

            for (int[] parent : node.parents)
            {
                int parentY = this.rowY(parent[0]);
                int parentX = this.columnX(node.column) + parent[1] * LANE;

                /* Up out of the commit towards the parent's row ... */
                this.segment(context, node.x, node.y, node.x, parentY, color);

                /* ... and across into the parent's lane */
                if (parentX != node.x)
                {
                    this.segment(context, node.x, parentY, parentX, parentY, color);
                }
            }
        }
    }

    /**
     * Draws a straight edge between two points, but cuts it at every commit it would otherwise
     * run through. Without this a parent that isn't the row directly above would drag a line
     * across the hollow squares of the commits in between, which reads as if those commits were
     * wired into a branch they aren't on.
     */
    private void segment(UIContext context, int x1, int y1, int x2, int y2, int color)
    {
        boolean vertical = x1 == x2;
        int fixed = vertical ? x1 : y1;
        int from = vertical ? y1 : x1;
        int to = vertical ? y2 : x2;
        int low = Math.min(from, to);
        int high = Math.max(from, to);

        List<int[]> bands = this.bands;

        bands.clear();

        for (Node node : this.nodes)
        {
            if ((vertical ? node.x : node.y) != fixed)
            {
                continue;
            }

            int center = vertical ? node.y : node.x;
            int a = center - NODE / 2;
            int b = center + NODE / 2 + 1;

            if (b > low && a < high)
            {
                bands.add(new int[] {a, b});
            }
        }

        bands.sort((a, b) -> Integer.compare(a[0], b[0]));

        int cursor = low;

        for (int[] band : bands)
        {
            if (band[0] > cursor)
            {
                this.piece(context, vertical, fixed, cursor, band[0], color);
            }

            cursor = Math.max(cursor, band[1]);
        }

        if (cursor < high)
        {
            this.piece(context, vertical, fixed, cursor, high, color);
        }
    }

    private void piece(UIContext context, boolean vertical, int fixed, int from, int to, int color)
    {
        if (vertical)
        {
            this.line(context, fixed, from, fixed, to, color);
        }
        else
        {
            this.line(context, from, fixed, to, fixed, color);
        }
    }

    private void renderNodes(UIContext context)
    {
        int accent = BBSSettings.primaryColor();

        for (Node node : this.nodes)
        {
            int rowTop = this.rowTop(node.row);

            if (rowTop + ROW_H <= this.body.y || rowTop >= this.body.ey())
            {
                continue;
            }

            boolean hover = this.isHover(context, node, rowTop);
            boolean lit = hover || node == this.selected;
            int left = node.x - NODE / 2;
            int top = node.y - NODE / 2;
            int right = node.x + NODE / 2 + 1;
            int bottom = node.y + NODE / 2 + 1;
            int color = CommitColors.get(node.filmId, node.commit.id);

            if (node.active)
            {
                context.batcher.box(left, top, right, bottom, color);
            }
            else
            {
                /* Commits the film no longer sits on stay visible, but hollow: switched off */
                context.batcher.outline(left, top, right, bottom, Colors.mulA(color, 0.35F));
            }

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

            String text = this.fit(context, node.commit.shortId() + "  " + node.commit.message, Math.max(24, this.columnX(node.column) + this.columnWidth() - this.textX(node) - 8));
            int textY = node.y - context.batcher.getFont().getHeight() / 2;

            context.batcher.textShadow(text, this.textX(node), textY, node.active ? RowStyle.textColor(lit) : RowStyle.textColor(lit, Colors.GRAY));

            /* Branch name next to the commit that is its head, like git's decoration */
            if (node.tip)
            {
                int tagX = this.textX(node) + context.batcher.getFont().getWidth(text) + 8;

                if (tagX + 40 < this.columnX(node.column) + this.columnWidth())
                {
                    context.batcher.box(tagX, node.y - 1, tagX + 3, node.y + 2, node.active ? accent : Colors.GRAY);
                    context.batcher.textShadow(node.commit.branch, tagX + 7, textY, node.active ? Colors.GRAY : RowStyle.textColor(false, Colors.GRAY));
                }
            }
        }
    }

    /** Two pixel wide line out of boxes, clipped to the graph's body. */
    private void line(UIContext context, int x1, int y1, int x2, int y2, int color)
    {
        int top = Math.max(this.body.y, Math.min(y1, y2));
        int bottom = Math.min(this.body.ey(), Math.max(y1, y2) + 1);

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

    /** The branch the film is on, shown next to the film's name in the header. */
    private String headBranch(String filmId)
    {
        FilmCommitClient.Graph graph = FilmCommitClient.graph(filmId);

        if (graph != null)
        {
            return graph.current;
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
        /* The scrollbar comes first: dragging it must not clear the selection */
        if (context.mouseButton == 0 && this.scroll.mouseClicked(context))
        {
            return true;
        }

        if (context.mouseButton == 0 && this.body.isInside(context))
        {
            Node hit = this.nodeAt(context.mouseX, context.mouseY);

            /* Empty space means nothing is selected: the details pane closes again. */
            this.select(hit);

            return true;
        }

        return super.subMouseClicked(context);
    }

    @Override
    public boolean subMouseReleased(UIContext context)
    {
        this.scroll.mouseReleased(context);

        return super.subMouseReleased(context);
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
        if (this.body.isInside(context) && context.mouseWheel != 0D)
        {
            /* Wheeling up to read older commits detaches the view from the bottom; scrolling
             * back down to the end attaches it again (atEnd() re-pins it there) */
            this.pinned = false;

            this.scroll.mouseScroll(context);

            if (this.atEnd())
            {
                this.pinned = true;
            }

            return true;
        }

        return super.subMouseScrolled(context);
    }
}
