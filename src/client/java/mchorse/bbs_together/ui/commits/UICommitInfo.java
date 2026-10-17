package mchorse.bbs_together.ui.commits;

import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_together.commits.FilmCommit;
import mchorse.bbs_together.network.TogetherClientNetwork;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything known about the selected commit: its id, author and time, the statistics of what it
 * changed and the diff. The full screen tab also edits the commit message here; the dock leaves
 * that to its action bar, which already has a message field.
 */
public class UICommitInfo extends UIElement
{
    private final UITextbox message;
    private final UIIcon apply;
    private final boolean rename;

    private UICommitGraph.Node node;
    private final List<String> lines = new ArrayList<String>();
    private final Map<String, Long> requested = new HashMap<String, Long>();

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

        this.message.relative(this).x(4).y(1F, -24).w(1F, -30).h(18);
        this.apply.relative(this).x(1F, -22).y(1F, -24).wh(18, 18);

        if (rename)
        {
            this.add(this.message, this.apply);
        }

        this.setVisible(false);
    }

    public void show(UICommitGraph.Node node)
    {
        this.node = node;
        this.setVisible(node != null);

        if (node == null)
        {
            this.lines.clear();

            return;
        }

        this.message.setText(node.commit.message);
        this.apply.active(false);

        this.requestDiff();
        this.refresh();
    }

    /** False while nothing is selected, so the dock can give the space back to the graph. */
    public boolean hasSelection()
    {
        return this.node != null;
    }

    public void refresh()
    {
        this.lines.clear();

        if (this.node == null)
        {
            return;
        }

        FilmCommit commit = this.node.commit;

        this.lines.add(commit.shortId() + "  " + this.kind(commit.kind) + "  " + commit.branch);
        this.lines.add(commit.authorName.isEmpty() ? commit.authorId : commit.authorName);
        this.lines.add(new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(commit.time)));

        this.addStats(commit);

        MapType diff = FilmCommitClient.diff(this.node.filmId, commit.id);

        if (diff == null)
        {
            this.lines.add("");
            this.lines.add(L10n.lang("bbs_together.commits.loading").get());
        }
        else
        {
            this.addDiff(diff);
        }
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
            case FilmCommit.KIND_IMPORT: return "import";
            case FilmCommit.KIND_REVERT: return "revert";
            case FilmCommit.KIND_AUTO: return "auto";
            default: return "manual";
        }
    }

    private void addStats(FilmCommit commit)
    {
        MapType clips = commit.stats.getMap("clips", null);
        int added = clips == null ? 0 : clips.getInt("added");
        int removed = clips == null ? 0 : clips.getInt("removed");
        int modified = clips == null ? 0 : clips.getInt("modified");

        this.lines.add("");
        this.lines.add(L10n.lang("bbs_together.commits.clips").get() + " +" + added + " -" + removed + " ~" + modified);
        this.lines.add(L10n.lang("bbs_together.commits.keyframes").get() + " +" + commit.stats.getInt("kf_added") + " -" + commit.stats.getInt("kf_removed") + " ~" + commit.stats.getInt("kf_modified"));

        float before = commit.stats.getFloat("duration_before");
        float after = commit.stats.getFloat("duration_after");

        if (before != after)
        {
            this.lines.add(L10n.lang("bbs_together.commits.duration").get() + " " + (int) before + " -> " + (int) after);
        }
    }

    private void addDiff(MapType diff)
    {
        String[] sections = {"camera", "replays", "film", "markers", "categories"};
        String[] labels = {
            L10n.lang("bbs_together.commits.camera").get(),
            L10n.lang("bbs_together.commits.replays").get(),
            L10n.lang("bbs_together.commits.film").get(),
            L10n.lang("bbs_together.commits.markers").get(),
            L10n.lang("bbs_together.commits.categories").get()
        };

        for (int i = 0; i < sections.length; i++)
        {
            ListType list = diff.getList(sections[i], null);

            if (list == null || list.isEmpty())
            {
                continue;
            }

            this.lines.add("");
            this.lines.add(labels[i] + " (" + list.size() + ")");

            for (int j = 0; j < Math.min(list.size(), 6); j++)
            {
                MapType entry = list.getMap(j);

                if (entry == null)
                {
                    continue;
                }

                String op = entry.getString("op", "");
                String type = entry.getString("type", entry.getString("field", ""));

                this.lines.add("  " + op + " " + type);
            }

            if (list.size() > 6)
            {
                this.lines.add("  ...");
            }
        }

        if (diff.getBool("truncated"))
        {
            this.lines.add("");
            this.lines.add(L10n.lang("bbs_together.commits.truncated").get());
        }
    }

    @Override
    public void render(UIContext context)
    {
        int font = context.batcher.getFont().getHeight() + 3;
        int y = this.area.y + 6;
        int bottom = this.rename ? 34 : 6;

        for (String line : this.lines)
        {
            if (y > this.area.ey() - bottom)
            {
                break;
            }

            if (!line.isEmpty())
            {
                context.batcher.text(line, this.area.x + 6, y, Colors.LIGHTEST_GRAY);
            }

            y += font;
        }

        super.render(context);
    }
}
