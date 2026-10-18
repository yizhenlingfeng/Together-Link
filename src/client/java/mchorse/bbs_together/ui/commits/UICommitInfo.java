package mchorse.bbs_together.ui.commits;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.ui.framework.elements.utils.RowStyle;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.Scroll;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_together.commits.FilmCommit;
import mchorse.bbs_together.commits.FilmDiffBuilder;
import mchorse.bbs_together.network.TogetherClientNetwork;
import mchorse.bbs_together.network.TogetherEditSyncQueue;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The selected commit (id, author, time, statistics, diff), or the film's uncommitted changes when
 * nothing is selected. The full screen tab also edits the commit message here; the dock leaves that
 * to its action bar, which already has a message field.
 */
public class UICommitInfo extends UIElement
{
    private static final int LINE_H = 14;
    /** Room the rename field takes at the bottom of the full screen pane. */
    private static final int FOOTER = 30;
    /** Diff sections, in the order the pane lists them. */
    private static final String[] SECTIONS = {"camera", "replays", "film", "markers", "categories"};

    /** One piece of a value, so a stat row can say "+3" green and "-1" red in one line. */
    private static final class Segment
    {
        public final String text;
        public final int color;

        public Segment(String text, int color)
        {
            this.text = text;
            this.color = color;
        }
    }

    /** One property row: a label on the left, an optional value against the right edge. */
    private static final class Line
    {
        public final String label;
        public final List<Segment> values = new ArrayList<Segment>();
        public final boolean header;

        public Line(String label, boolean header)
        {
            this.label = label;
            this.header = header;
        }

        public Line value(String text, int color)
        {
            this.values.add(new Segment(text, color));

            return this;
        }
    }

    private final UITextbox message;
    private final UIIcon apply;
    private final boolean rename;

    private final Area body = new Area();
    private final Scroll scroll = new Scroll(this.body, LINE_H);
    private final List<Line> lines = new ArrayList<Line>();
    private final Map<String, Long> requested = new HashMap<String, Long>();

    private UICommitGraph.Node node;
    /** Film whose pending changes are shown while no commit is selected. */
    private String filmId = "";
    /** Whether the pane shows the film's uncommitted changes instead of a commit. */
    private boolean working;
    /** When the pending changes were last rebuilt, so they follow the editor while it is open. */
    private long built;
    /** Edit version the current lines were built from; unchanged means no diff work needed. */
    private long builtVersion = -1L;

    public UICommitInfo()
    {
        this(true);
    }

    /**
     * @param rename whether this pane owns the commit message field and its save button.
     */
    public UICommitInfo(boolean rename)
    {
        this.rename = rename;
        this.message = new UITextbox(FilmCommit.MAX_MESSAGE, (t) -> {});
        this.message.placeholder(L10n.lang("bbs_together.commits.message"));
        this.apply = new UIIcon(Icons.SAVED, (b) -> this.applyRename());

        this.message.relative(this).x(8).y(1F, -24).w(1F, -34).h(18);
        this.apply.relative(this).x(1F, -22).y(1F, -24).wh(18, 18);

        if (rename)
        {
            this.add(this.message, this.apply);
        }

        this.setVisible(false);
    }

    /** Show one commit, or the film's uncommitted changes when {@code node} is null. */
    public void show(String filmId, UICommitGraph.Node node)
    {
        this.node = node;
        this.filmId = node != null ? node.filmId : (filmId == null ? "" : filmId);
        this.working = node == null && !this.filmId.isEmpty();
        this.setVisible(node != null || this.working);

        /* A different commit starts reading from the top, like opening another file's page */
        this.scroll.setScroll(0);

        if (node != null)
        {
            this.message.setText(node.commit.message);
            this.apply.active(false);

            this.requestDiff();
        }

        this.refresh();
    }

    /** Film the pending changes belong to; a selection keeps showing its commit instead. */
    public void film(String filmId)
    {
        String id = filmId == null ? "" : filmId;

        if (this.node != null || this.filmId.equals(id))
        {
            return;
        }

        this.filmId = id;
        this.working = !id.isEmpty();
        this.setVisible(this.working);

        this.refresh();
    }

    /** Whether the pane has anything to show: a selected commit or the film's pending changes. */
    public boolean hasContent()
    {
        return this.node != null || this.working;
    }

    public void refresh()
    {
        this.lines.clear();

        if (this.node != null)
        {
            this.build();
        }
        else if (this.working)
        {
            this.buildWorking();
        }

        this.built = System.currentTimeMillis();
        this.builtVersion = TogetherEditSyncQueue.editVersion();

        this.updateScroll();
    }

    private void build()
    {
        FilmCommit commit = this.node.commit;

        this.lines.add(new Line(commit.shortId() + "  " + this.kind(commit.kind) + "  " + commit.branch, true));
        this.lines.add(new Line(L10n.lang("bbs_together.commits.author").get(), false)
            .value(commit.authorName.isEmpty() ? commit.authorId : commit.authorName, Colors.LIGHTEST_GRAY));
        this.lines.add(new Line(L10n.lang("bbs_together.commits.time").get(), false)
            .value(new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(commit.time)), Colors.LIGHTEST_GRAY));

        this.lines.add(new Line("", false));
        this.lines.add(new Line(L10n.lang("bbs_together.commits.changes").get(), true));

        this.addStats(commit.stats);

        MapType diff = FilmCommitClient.diff(this.node.filmId, commit.id);

        if (diff == null)
        {
            this.lines.add(new Line("", false));
            this.lines.add(new Line(L10n.lang("bbs_together.commits.loading").get(), false));
        }
        else
        {
            this.addDiff(diff);
        }
    }

    /** The editor's film against its branch head: the "not committed yet" view. */
    private void buildWorking()
    {
        FilmCommitClient.Graph graph = FilmCommitClient.graph(this.filmId);
        String branch = graph == null ? "" : graph.current;
        String title = L10n.lang("bbs_together.commits.working").get();

        this.lines.add(new Line(branch.isEmpty() ? title : title + "  " + branch, true));

        Film film = CommitUi.liveFilm(this, this.filmId);

        if (film == null)
        {
            this.lines.add(new Line("", false));
            this.lines.add(new Line(L10n.lang("bbs_together.commits.working_closed").get(), false));

            return;
        }

        MapType head = this.headSnapshot();

        if (head == null)
        {
            this.lines.add(new Line("", false));
            this.lines.add(new Line(L10n.lang("bbs_together.commits.loading").get(), false));

            return;
        }

        MapType diff = FilmDiffBuilder.diff(head, (MapType) film.toData());

        if (!this.hasChanges(diff))
        {
            this.lines.add(new Line("", false));
            this.lines.add(new Line(L10n.lang("bbs_together.commits.working_none").get(), false));

            return;
        }

        this.lines.add(new Line("", false));
        this.lines.add(new Line(L10n.lang("bbs_together.commits.changes").get(), true));

        this.addStats(diff.getMap("stats", null));
        this.addDiff(diff);
    }

    /** Head snapshot of the film's branch, asked for once per head; null while it is on its way. */
    private MapType headSnapshot()
    {
        MapType head = FilmCommitClient.head(this.filmId);

        if (head != null)
        {
            return head;
        }

        String key = "head/" + this.filmId;
        Long last = this.requested.get(key);

        /* The answer can be lost; ask again, but not on every frame */
        if (last == null || System.currentTimeMillis() - last > 3000L)
        {
            this.requested.put(key, System.currentTimeMillis());

            TogetherClientNetwork.sendCommitHead(this.filmId);
        }

        return null;
    }

    private boolean hasChanges(MapType diff)
    {
        for (String section : SECTIONS)
        {
            ListType list = diff.getList(section, null);

            if (list != null && !list.isEmpty())
            {
                return true;
            }
        }

        MapType stats = diff.getMap("stats", null);

        if (stats == null)
        {
            return false;
        }

        MapType clips = stats.getMap("clips", null);

        if (clips != null && clips.getInt("added") + clips.getInt("removed") + clips.getInt("modified") > 0)
        {
            return true;
        }

        if (stats.getInt("kf_added") + stats.getInt("kf_removed") + stats.getInt("kf_modified") > 0)
        {
            return true;
        }

        return stats.getFloat("duration_before") != stats.getFloat("duration_after");
    }

    private void requestDiff()
    {
        if (this.node == null)
        {
            return;
        }

        String filmId = this.node.filmId;
        String commitId = this.node.commit.id;

        if (FilmCommitClient.diff(filmId, commitId) != null)
        {
            return;
        }

        Long last = this.requested.get(filmId + "/" + commitId);

        /* The answer can be lost; ask again, but not on every frame */
        if (last == null || System.currentTimeMillis() - last > 3000L)
        {
            this.requested.put(filmId + "/" + commitId, System.currentTimeMillis());

            TogetherClientNetwork.sendCommitDiff(filmId, commitId);
        }
    }

    private void applyRename()
    {
        if (this.node == null)
        {
            return;
        }

        String text = this.message.getText();

        if (text == null)
        {
            return;
        }

        text = text.trim();

        if (text.isEmpty() || text.equals(this.node.commit.message))
        {
            return;
        }

        TogetherClientNetwork.sendCommitRename(this.node.filmId, this.node.commit.id, text);
    }

    private String kind(int kind)
    {
        switch (kind)
        {
            case FilmCommit.KIND_IMPORT: return L10n.lang("bbs_together.commits.kind_import").get();
            case FilmCommit.KIND_REVERT: return L10n.lang("bbs_together.commits.kind_revert").get();
            case FilmCommit.KIND_AUTO: return L10n.lang("bbs_together.commits.kind_auto").get();
            default: return L10n.lang("bbs_together.commits.kind_manual").get();
        }
    }

    private void addStats(MapType stats)
    {
        if (stats == null)
        {
            return;
        }

        MapType clips = stats.getMap("clips", null);
        int added = clips == null ? 0 : clips.getInt("added");
        int removed = clips == null ? 0 : clips.getInt("removed");
        int modified = clips == null ? 0 : clips.getInt("modified");

        this.lines.add(this.stat(L10n.lang("bbs_together.commits.clips").get(), added, removed, modified));
        this.lines.add(this.stat(L10n.lang("bbs_together.commits.keyframes").get(), stats.getInt("kf_added"), stats.getInt("kf_removed"), stats.getInt("kf_modified")));

        float before = stats.getFloat("duration_before");
        float after = stats.getFloat("duration_after");

        if (before != after)
        {
            this.lines.add(new Line(L10n.lang("bbs_together.commits.duration").get(), false)
                .value((int) before + " → " + (int) after, Colors.LIGHTEST_GRAY));
        }
    }

    private Line stat(String label, int added, int removed, int modified)
    {
        return new Line(label, false)
            .value("+" + added, Colors.POSITIVE)
            .value("-" + removed, Colors.NEGATIVE)
            .value("~" + modified, Colors.LIGHTER_GRAY);
    }

    private void addDiff(MapType diff)
    {
        String[] labels = {
            L10n.lang("bbs_together.commits.camera").get(),
            L10n.lang("bbs_together.commits.replays").get(),
            L10n.lang("bbs_together.commits.film").get(),
            L10n.lang("bbs_together.commits.markers").get(),
            L10n.lang("bbs_together.commits.categories").get()
        };

        for (int i = 0; i < SECTIONS.length; i++)
        {
            ListType list = diff.getList(SECTIONS[i], null);

            if (list == null || list.isEmpty())
            {
                continue;
            }

            this.lines.add(new Line("", false));
            this.lines.add(new Line(labels[i] + " (" + list.size() + ")", true));

            for (int j = 0; j < Math.min(list.size(), 6); j++)
            {
                MapType entry = list.getMap(j);

                if (entry == null)
                {
                    continue;
                }

                Line line = new Line("  " + this.op(entry.getString("op", "")) + " " + this.subject(entry), false);
                String detail = this.detail(entry);

                if (!detail.isEmpty())
                {
                    line.value(detail, Colors.LIGHTER_GRAY);
                }

                this.lines.add(line);
            }

            if (list.size() > 6)
            {
                this.lines.add(new Line("  ...", false));
            }
        }

        if (diff.getBool("truncated"))
        {
            this.lines.add(new Line("", false));
            this.lines.add(new Line(L10n.lang("bbs_together.commits.truncated").get(), false));
        }
    }

    /** Added and removed lead green and red, like the rest of FS says more and less. */
    private String op(String op)
    {
        return op.startsWith("add") ? "+" : (op.startsWith("remov") || op.startsWith("del") ? "-" : "~");
    }

    /** What the difference is about: a clip's title, a replay's name, a field's path. */
    private String subject(MapType entry)
    {
        String[] keys = {"title", "name", "label", "k", "prop", "type", "kind", "id"};

        for (String key : keys)
        {
            String value = entry.getString(key, "");

            if (!value.isEmpty())
            {
                return value;
            }
        }

        return "";
    }

    /** For film fields the entry carries the two values, so the row can show before and after. */
    private String detail(MapType entry)
    {
        if (!entry.has("old") && !entry.has("new"))
        {
            return "";
        }

        return entry.getString("old", "-") + " → " + entry.getString("new", "-");
    }

    private void updateScroll()
    {
        this.scroll.scrollSize = this.lines.size() * LINE_H + 6;
        this.scroll.clamp();
    }

    @Override
    public void resize()
    {
        super.resize();

        int footer = this.rename ? FOOTER : 0;

        this.body.set(this.area.x, this.area.y, this.area.w, Math.max(0, this.area.h - footer));

        this.updateScroll();
    }

    @Override
    public void render(UIContext context)
    {
        this.scroll.drag(context.mouseX, context.mouseY);

        /* Follow the editor when it actually changed. Rebuilding a full-film diff every second
         * froze the render thread for about a second on large films. */
        long now = System.currentTimeMillis();
        long version = TogetherEditSyncQueue.editVersion();

        if (this.working && version != this.builtVersion && now - this.built > 1000L)
        {
            this.refresh();
        }

        context.batcher.clip(this.body, context);

        int font = context.batcher.getFont().getHeight();
        int top = this.body.y + 2 - (int) this.scroll.getScroll();

        for (Line line : this.lines)
        {
            int textY = top + (LINE_H - font) / 2;

            if (line.header)
            {
                RowStyle.row(context.batcher, this.body.x, top, this.body.w, LINE_H, 0, true, false, false);
            }

            if (!line.label.isEmpty())
            {
                context.batcher.textShadow(line.label, this.body.x + 8, textY, line.header ? Colors.WHITE : Colors.LIGHTER_GRAY);
            }

            this.renderValues(context, line, textY);

            top += LINE_H;
        }

        if (this.lines.isEmpty())
        {
            String empty = L10n.lang("bbs_together.commits.empty").get();

            context.batcher.text(empty, this.body.mx() - context.batcher.getFont().getWidth(empty) / 2, this.body.my() - font / 2, Colors.GRAY);
        }

        context.batcher.unclip(context);

        this.scroll.renderScrollbar(context.batcher);

        /* A hairline above the rename field, so the footer reads as part of the frame */
        if (this.rename)
        {
            context.batcher.box(this.area.x, this.area.ey() - FOOTER, this.area.ex(), this.area.ey() - FOOTER, BBSSettings.dividerColor());
        }

        super.render(context);
    }

    /** Values sit against the right edge, so a scan down the pane compares like with like. */
    private void renderValues(UIContext context, Line line, int textY)
    {
        int x = this.body.ex() - 10;

        for (int i = line.values.size() - 1; i >= 0; i--)
        {
            Segment segment = line.values.get(i);
            int width = context.batcher.getFont().getWidth(segment.text);

            x -= width;

            context.batcher.textShadow(segment.text, x, textY, segment.color);

            x -= 5;
        }
    }

    @Override
    public boolean subMouseScrolled(UIContext context)
    {
        if (this.body.isInside(context) && context.mouseWheel != 0D)
        {
            this.scroll.mouseScroll(context);

            return true;
        }

        return super.subMouseScrolled(context);
    }
}
