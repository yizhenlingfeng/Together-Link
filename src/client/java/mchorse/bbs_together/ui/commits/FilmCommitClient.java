package mchorse.bbs_together.ui.commits;

import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_together.commits.FilmCommit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Client side cache of the commit graph: the graph itself, the per commit diffs and the index of
 * films that have commits. The network handlers write here, the panel reads and refreshes.
 */
public final class FilmCommitClient
{
    /** One film that has commits, as shown in the left column. */
    public static final class IndexEntry
    {
        public String id = "";
        public int count;
    }

    /** The graph of one film. */
    public static final class Graph
    {
        public final String filmId;

        public String head = "";
        public String current = "";
        public final List<FilmCommit> commits = new ArrayList<FilmCommit>();
        public final Map<String, String> branches = new LinkedHashMap<String, String>();

        public Graph(String filmId)
        {
            this.filmId = filmId;
        }

        public FilmCommit find(String commitId)
        {
            for (FilmCommit commit : this.commits)
            {
                if (commit.id.equals(commitId))
                {
                    return commit;
                }
            }

            return null;
        }
    }

    private static final Map<String, Graph> GRAPHS = new ConcurrentHashMap<String, Graph>();
    private static final Map<String, MapType> DIFFS = new ConcurrentHashMap<String, MapType>();
    /** Head snapshots, keyed by film id, together with the head commit they belong to. */
    private static final Map<String, MapType> HEADS = new ConcurrentHashMap<String, MapType>();
    private static final Map<String, String> HEAD_IDS = new ConcurrentHashMap<String, String>();
    private static final List<IndexEntry> INDEX = new CopyOnWriteArrayList<IndexEntry>();


    private static int version;



    /** Bumped on every change, so a panel can tell whether its cached view is stale. */
    public static int version()
    {
        return version;
    }

    public static List<IndexEntry> index()
    {
        return Collections.unmodifiableList(INDEX);
    }

    public static Graph graph(String filmId)
    {
        return filmId == null ? null : GRAPHS.get(filmId);
    }

    public static MapType diff(String filmId, String commitId)
    {
        return DIFFS.get(filmId + "/" + commitId);
    }

    public static FilmCommit commit(String filmId, String commitId)
    {
        Graph graph = graph(filmId);

        return graph == null ? null : graph.find(commitId);
    }

    public static void setIndex(MapType data)
    {
        List<IndexEntry> entries = new ArrayList<IndexEntry>();
        ListType films = data.getList("films");

        for (int i = 0; i < films.size(); i++)
        {
            MapType map = films.getMap(i);
            IndexEntry entry = new IndexEntry();

            entry.id = map.getString("id");
            entry.count = map.getInt("count");

            if (!entry.id.isEmpty())
            {
                entries.add(entry);
            }
        }

        INDEX.clear();
        INDEX.addAll(entries);

        changed();
    }

    public static void setGraph(String filmId, MapType data)
    {
        Graph graph = new Graph(filmId);
        ListType commits = data.getList("commits");

        graph.head = data.getString("head");
        graph.current = data.getString("current", "main");

        for (int i = 0; i < commits.size(); i++)
        {
            FilmCommit commit = FilmCommit.fromData(commits.getMap(i));

            if (!commit.id.isEmpty())
            {
                graph.commits.add(commit);
            }
        }

        MapType branches = data.getMap("branches");

        for (String branch : branches.keys())
        {
            graph.branches.put(branch, branches.getString(branch));
        }

        GRAPHS.put(filmId, graph);

        changed();
    }

    /** Push one commit (its diff is fetched lazily when the node is selected). */
    public static void addCommit(String filmId, MapType data)
    {
        Graph graph = GRAPHS.get(filmId);

        if (graph == null)
        {
            /* The graph was never fetched, the panel will query it on its own */
            return;
        }

        FilmCommit commit = FilmCommit.fromData(data);

        if (commit.id.isEmpty() || graph.find(commit.id) != null)
        {
            return;
        }

        graph.commits.add(commit);

        if (commit.branch != null && !commit.branch.isEmpty())
        {
            graph.branches.put(commit.branch, commit.id);
        }

        graph.head = commit.id;

        changed();
    }

    public static void renameCommit(String filmId, String commitId, String message)
    {
        Graph graph = GRAPHS.get(filmId);

        if (graph == null)
        {
            return;
        }

        FilmCommit commit = graph.find(commitId);

        if (commit != null)
        {
            commit.message = message;

            changed();
        }
    }

    public static void setDiff(String filmId, String commitId, MapType data)
    {
        DIFFS.put(filmId + "/" + commitId, data);

        changed();
    }

    /** The head snapshot the film sits at, or null while it is being fetched. */
    public static MapType head(String filmId)
    {
        Graph graph = graph(filmId);
        String head = HEAD_IDS.get(filmId);

        if (graph == null || head == null || !head.equals(graph.head))
        {
            return null;
        }

        return HEADS.get(filmId);
    }

    public static void setHead(String filmId, String head, MapType data)
    {
        HEAD_IDS.put(filmId, head);
        HEADS.put(filmId, data);

        changed();
    }


    private static void changed()
    {
        version += 1;
    }
}
