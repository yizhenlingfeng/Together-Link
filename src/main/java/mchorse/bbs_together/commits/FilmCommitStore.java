package mchorse.bbs_together.commits;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.data.DataStorageUtils;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per film commit history, stored next to the films themselves.
 *
 * <pre>
 * &lt;world&gt;/bbs/films/
 * ├─ &lt;film&gt;.dat                 # the film itself, written by BBS FS
 * └─ _commits/&lt;filmId&gt;/
 *    ├─ index.bin               # commits, parents, branch heads
 *    ├─ s/&lt;commitId&gt;.bin        # film snapshot (raw BaseType bytes)
 *    └─ d/&lt;commitId&gt;.bin        # change details, made by FilmDiffBuilder
 * </pre>
 *
 * <p>{@code _commits} starts with an underscore so {@code FolderManager.getKeys()} skips it - it
 * only lists real films. Everything here touches the disk, so it must be called from the server
 * thread (the save path already runs there).</p>
 */
public final class FilmCommitStore
{
    public static final String BRANCH_MAIN = "main";

    private static final String FOLDER = "_commits";
    private static final String INDEX = "index.bin";
    private static final String SNAPSHOTS = "s";
    private static final String DIFFS = "d";
    private static final String EXTENSION = ".bin";

    private static final int FORMAT = 1;

    /** How many commits one film keeps; the oldest ones fall off the end. */
    private static final int MAX_COMMITS = 300;

    private static final Map<String, Store> STORES = new HashMap<String, Store>();

    private FilmCommitStore()
    {}

    /** One film's history, cached until the server stops. */
    public static final class Store
    {
        public final String filmId;
        public final File folder;

        /** Oldest first, newest last. */
        public final List<FilmCommit> commits = new ArrayList<FilmCommit>();

        /** Branch name to the id of its head commit. */
        public final Map<String, String> branches = new HashMap<String, String>();

        /**
         * Branch that new commits are appended to, i.e. the branch the film currently is. Starts
         * at {@link #BRANCH_MAIN} and only changes when someone checks another branch out.
         */
        public String current = BRANCH_MAIN;

        Store(String filmId, File folder)
        {
            this.filmId = filmId;
            this.folder = folder;
        }

        /** Head of the branch the film is on. */
        public String currentHead()
        {
            String id = this.branches.get(this.current);

            return id == null ? this.branches.get(BRANCH_MAIN) : id;
        }

        /** Head of one branch, or {@code null} when that branch does not exist. */
        public String branchHead(String branch)
        {
            return branch == null ? null : this.branches.get(branch);
        }

        public FilmCommit find(String id)
        {
            if (id == null || id.isEmpty())
            {
                return null;
            }

            for (FilmCommit commit : this.commits)
            {
                if (commit.id.equals(id))
                {
                    return commit;
                }
            }

            return null;
        }

        public String head()
        {
            return this.currentHead();
        }
    }

    /* Loading */

    private static File baseFolder()
    {
        try
        {
            File films = BBSMod.getFilms().getFolder();

            return films == null ? null : new File(films, FOLDER);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    public static synchronized Store get(String filmId)
    {
        if (filmId == null || filmId.isEmpty())
        {
            return null;
        }

        Store store = STORES.get(filmId);

        if (store != null)
        {
            return store;
        }

        File base = baseFolder();

        if (base == null)
        {
            return null;
        }

        store = new Store(filmId, new File(base, filmId));

        load(store);
        STORES.put(filmId, store);

        return store;
    }

    private static void load(Store store)
    {
        File file = new File(store.folder, INDEX);

        if (!file.isFile())
        {
            return;
        }

        try
        {
            BaseType data = DataStorageUtils.readFromBytes(Files.readAllBytes(file.toPath()));

            if (data == null || !data.isMap())
            {
                return;
            }

            MapType map = data.asMap();
            ListType commits = map.getList("commits");

            for (int i = 0; i < commits.size(); i++)
            {
                MapType entry = commits.getMap(i);
                FilmCommit commit = FilmCommit.fromData(entry);

                if (!commit.id.isEmpty())
                {
                    store.commits.add(commit);
                }
            }

            MapType branches = map.getMap("branches");

            for (String branch : branches.keys())
            {
                store.branches.put(branch, branches.getString(branch));
            }

            if (!store.branches.containsKey(BRANCH_MAIN) && !store.commits.isEmpty())
            {
                store.branches.put(BRANCH_MAIN, store.commits.get(store.commits.size() - 1).id);
            }

            String current = map.getString("current", BRANCH_MAIN);

            store.current = store.branches.containsKey(current) ? current : BRANCH_MAIN;
        }
        catch (Exception e)
        {
            System.err.println("Failed to load film commits of \"" + store.filmId + "\": " + e);
        }
    }

    private static void save(Store store)
    {
        try
        {
            store.folder.mkdirs();

            MapType map = new MapType();

            map.putInt("v", FORMAT);

            ListType commits = new ListType();

            for (FilmCommit commit : store.commits)
            {
                commits.add(commit.toData());
            }

            map.put("commits", commits);

            MapType branches = new MapType();

            for (Map.Entry<String, String> entry : store.branches.entrySet())
            {
                branches.putString(entry.getKey(), entry.getValue());
            }

            map.put("branches", branches);
            map.putString("current", store.current);

            Files.write(new File(store.folder, INDEX).toPath(), DataStorageUtils.writeToBytes(map));
        }
        catch (IOException e)
        {
            System.err.println("Failed to save film commits of \"" + store.filmId + "\": " + e);
        }
    }

    /* Snapshots and diffs */

    private static File file(Store store, String kind, String commitId)
    {
        File folder = new File(store.folder, kind);

        return new File(folder, commitId + EXTENSION);
    }

    public static MapType snapshot(Store store, String commitId)
    {
        if (store == null || commitId == null || commitId.isEmpty())
        {
            return null;
        }

        File file = file(store, SNAPSHOTS, commitId);

        if (!file.isFile())
        {
            return null;
        }

        try
        {
            BaseType data = DataStorageUtils.readFromBytes(Files.readAllBytes(file.toPath()));

            return data == null || !data.isMap() ? null : data.asMap();
        }
        catch (Exception e)
        {
            System.err.println("Failed to read film snapshot \"" + commitId + "\": " + e);

            return null;
        }
    }

    /** Every film that has at least one commit, for the left column of the graph. */
    public static synchronized MapType index()
    {
        MapType map = new MapType();
        ListType films = new ListType();
        File base = baseFolder();

        if (base != null && base.isDirectory())
        {
            collect(base, base, films);
        }

        map.put("films", films);

        return map;
    }

    private static void collect(File base, File folder, ListType films)
    {
        File[] children = folder.listFiles();

        if (children == null)
        {
            return;
        }

        for (File child : children)
        {
            if (!child.isDirectory())
            {
                continue;
            }

            if (!new File(child, INDEX).isFile())
            {
                collect(base, child, films);

                continue;
            }

            String filmId = base.toPath().relativize(child.toPath()).toString().replace(File.separatorChar, '/');
            Store store = get(filmId);

            if (store == null || store.commits.isEmpty())
            {
                continue;
            }

            MapType entry = new MapType();

            entry.putString("id", filmId);
            entry.putInt("count", store.commits.size());
            films.add(entry);
        }
    }


    /** The details of one commit, or {@code null} when they were never written. */
    public static MapType diff(String filmId, String commitId)
    {
        Store store = get(filmId);

        if (store == null || commitId == null || commitId.isEmpty())
        {
            return null;
        }

        File file = file(store, DIFFS, commitId);

        if (!file.isFile())
        {
            return null;
        }

        try
        {
            BaseType data = DataStorageUtils.readFromBytes(Files.readAllBytes(file.toPath()));

            return data == null || !data.isMap() ? null : data.asMap();
        }
        catch (Exception e)
        {
            System.err.println("Failed to read film commit diff \"" + commitId + "\": " + e);

            return null;
        }
    }

    /* Recording */

    /**
     * Record the film as it is now, on {@code branch}. The client picks the branch from the
     * commit the author is looking at, so committing never requires a checkout first; an
     * unknown branch falls back to the branch the film is on.
     *
     * @param filmId id of the film (may contain folders, like the film files themselves)
     * @param data   the film as it should be committed
     * @param branch branch the commit goes on, {@code null} to use the branch the film is on
     * @return the new commit, or {@code null} when nothing changed since the branch head
     */
    public static synchronized FilmCommit commit(String filmId, MapType data, String authorId,
        String authorName, long now, String message, String branch)
    {
        Store store = get(filmId);

        if (store == null || data == null)
        {
            return null;
        }

        String target = branch == null ? "" : branch.trim();

        if (!store.branches.containsKey(target))
        {
            target = store.current;
        }

        FilmCommit head = store.find(store.branches.get(target));
        MapType headData = head == null ? null : snapshot(store, head.id);

        if (headData != null && BaseType.equals(headData, data))
        {
            return null;
        }

        String trimmed = message == null ? "" : message.trim();

        if (trimmed.length() > FilmCommit.MAX_MESSAGE)
        {
            trimmed = trimmed.substring(0, FilmCommit.MAX_MESSAGE);
        }

        return write(store, data, head, authorId, authorName, now, FilmCommit.KIND_MANUAL, trimmed, target);
    }

    /** Whether {@code name} can be used as a branch name. */
    public static boolean isValidBranchName(String name)
    {
        if (name == null)
        {
            return false;
        }

        String trimmed = name.trim();

        if (trimmed.isEmpty() || trimmed.length() > FilmCommit.MAX_BRANCH || trimmed.equals("HEAD"))
        {
            return false;
        }

        for (int i = 0; i < trimmed.length(); i++)
        {
            char c = trimmed.charAt(i);

            if (!(Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.' || c == '/'))
            {
                return false;
            }
        }

        return !trimmed.startsWith("-") && !trimmed.endsWith("/");
    }

    /**
     * Start a branch at one commit. The film itself does not move: a branch is a label until it
     * is checked out.
     */
    public static synchronized boolean createBranch(String filmId, String name, String commitId)
    {
        Store store = get(filmId);

        if (store == null || !isValidBranchName(name))
        {
            return false;
        }

        String trimmed = name.trim();

        if (store.branches.containsKey(trimmed))
        {
            return false;
        }

        FilmCommit commit = store.find(commitId);

        if (commit == null)
        {
            return false;
        }

        store.branches.put(trimmed, commit.id);
        save(store);

        return true;
    }

    /**
     * Put the film back to the head of a branch and make that branch the one new commits go to,
     * like checking a branch out. The caller writes the returned data into the film file.
     *
     * @return the film as it was at that branch's head, or {@code null} when there is no such branch
     */
    public static synchronized MapType checkout(String filmId, String branch)
    {
        Store store = get(filmId);

        if (store == null)
        {
            return null;
        }

        String head = store.branchHead(branch);
        MapType data = snapshot(store, head);

        if (data == null)
        {
            return null;
        }

        store.current = branch;
        save(store);

        return data;
    }


    /* How far to go back, and what to do with the commits that are left behind */

    /** Put the film back to the parent of a commit, and keep that commit in the history. */
    public static final int ROLLBACK_KEEP = 0;

    /** Put the film back to the parent of a commit, and remove that commit from the history. */
    public static final int ROLLBACK_DELETE = 1;

    /** Put the film back to one commit, and keep the commits that came after it. */
    public static final int RESET_KEEP = 2;

    /** Put the film back to one commit, and remove the commits that came after it. */
    public static final int RESET_DELETE = 3;

    /**
     * Move a branch back in time: either to the parent of {@code commitId} (undoing that commit)
     * or to the commit itself. The caller writes the returned data into the film file.
     *
     * <p>The branch head is moved, the film data is not committed anywhere: this is a rollback, not
     * a new node. When {@code mode} says to delete, the dropped commits are removed from the store
     * together with their files, and their children are re-hung on the commit the branch now
     * points at, so the graph stays connected.</p>
     *
     * @return the film as it was at the commit the branch now points at, or {@code null}
     */
    public static synchronized MapType rollback(String filmId, String commitId, int mode)
    {
        Store store = get(filmId);

        if (store == null)
        {
            return null;
        }

        FilmCommit target = store.find(commitId);

        if (target == null)
        {
            return null;
        }

        boolean reset = mode == RESET_KEEP || mode == RESET_DELETE;
        boolean drop = mode == ROLLBACK_DELETE || mode == RESET_DELETE;
        String branch = target.branch == null || target.branch.isEmpty() ? store.current : target.branch;
        String to = reset ? target.id : target.parent();

        if (to == null || to.isEmpty())
        {
            /* The first commit of a film has nothing before it, so there is nowhere to go back to */
            return null;
        }

        MapType data = snapshot(store, to);

        if (data == null)
        {
            return null;
        }

        List<String> removed = new ArrayList<String>();

        if (drop)
        {
            if (reset)
            {
                /* Walk down the branch to the target, dropping what sat above it */
                String id = store.branchHead(branch);

                while (id != null && !id.equals(target.id))
                {
                    FilmCommit commit = store.find(id);

                    if (commit == null || FilmCommitStore.heldByAnotherBranch(store, id, branch))
                    {
                        break;
                    }

                    removed.add(id);
                    id = commit.parent();
                }
            }
            else if (!FilmCommitStore.heldByAnotherBranch(store, target.id, branch))
            {
                removed.add(target.id);
            }
        }

        store.branches.put(branch, to);

        /* The film holds this branch's data now: keep the highlight and the next commit's
         * diff baseline on the same branch */
        store.current = branch;

        if (!removed.isEmpty())
        {
            for (String id : removed)
            {
                FilmCommit commit = store.find(id);

                if (commit != null)
                {
                    store.commits.remove(commit);
                }

                delete(store, id);
            }

            /* Children of a dropped commit now hang off the commit the branch points at */
            for (FilmCommit commit : store.commits)
            {
                for (int i = commit.parents.size() - 1; i >= 0; i --)
                {
                    if (removed.contains(commit.parents.get(i)))
                    {
                        commit.parents.remove(i);

                        if (!commit.parents.contains(to))
                        {
                            commit.parents.add(to);
                        }
                    }
                }
            }
        }

        save(store);

        return data;
    }

    /** Whether a branch other than {@code branch} has its head on {@code commitId}. */
    private static boolean heldByAnotherBranch(Store store, String commitId, String branch)
    {
        for (Map.Entry<String, String> entry : store.branches.entrySet())
        {
            if (!entry.getKey().equals(branch) && entry.getValue().equals(commitId))
            {
                return true;
            }
        }

        return false;
    }

    /** Author id of one commit, or {@code null} when the commit (or the film) is unknown. */
    public static synchronized String authorOf(String filmId, String commitId)
    {
        Store store = get(filmId);

        if (store == null)
        {
            return null;
        }

        FilmCommit commit = store.find(commitId);

        return commit == null ? null : commit.authorId;
    }

    /**
     * Add one commit on top of {@code head}, on {@code branch}, which becomes the branch the
     * film is on.
     *
     * @param head the branch head the commit hangs off, or {@code null} for the first commit
     */
    private static FilmCommit write(Store store, MapType data, FilmCommit head, String authorId,
        String authorName, long now, int kind, String message, String branch)
    {
        MapType before = head == null ? null : snapshot(store, head.id);
        MapType diff = FilmDiffBuilder.diff(before, data);

        FilmCommit commit = new FilmCommit();

        commit.filmId = store.filmId;
        commit.branch = branch;
        commit.authorId = authorId == null ? "" : authorId;
        commit.authorName = authorName == null ? "" : authorName;
        commit.time = now;
        commit.kind = kind;
        commit.stats = diff.getMap("stats");

        if (head != null)
        {
            commit.parents.add(head.id);
        }

        if (message == null || message.isEmpty())
        {
            commit.message = autoMessage(commit.stats, kind);
        }
        else
        {
            commit.message = message;
        }

        byte[] bytes = DataStorageUtils.writeToBytes(data);

        commit.id = hash(bytes, commit.parents, commit.authorId, commit.time);

        try
        {
            File snapshotFile = file(store, SNAPSHOTS, commit.id);
            File diffFile = file(store, DIFFS, commit.id);

            snapshotFile.getParentFile().mkdirs();
            diffFile.getParentFile().mkdirs();

            Files.write(snapshotFile.toPath(), bytes);
            Files.write(diffFile.toPath(), DataStorageUtils.writeToBytes(diff));
        }
        catch (IOException e)
        {
            System.err.println("Failed to write film commit \"" + commit.id + "\": " + e);

            return null;
        }

        store.commits.add(commit);
        store.branches.put(commit.branch, commit.id);
        store.current = commit.branch;

        trim(store);
        save(store);

        return commit;
    }

    private static void delete(Store store, String commitId)
    {
        File snapshot = file(store, SNAPSHOTS, commitId);
        File diff = file(store, DIFFS, commitId);

        if (!snapshot.delete())
        {
            /* A snapshot that cannot be deleted is harmless, the index no longer points at it. */
        }

        if (!diff.delete())
        {
            /* Same for the diff. */
        }
    }

    private static void trim(Store store)
    {
        while (store.commits.size() > MAX_COMMITS)
        {
            FilmCommit oldest = store.commits.get(0);

            if (store.branches.containsValue(oldest.id))
            {
                return;
            }

            store.commits.remove(0);
            delete(store, oldest.id);
        }
    }

    /** Rewrite the message of one commit. The id, the parents and the graph stay untouched. */
    public static synchronized boolean rename(String filmId, String commitId, String message)
    {
        Store store = get(filmId);

        if (store == null)
        {
            return false;
        }

        FilmCommit commit = store.find(commitId);

        if (commit == null)
        {
            return false;
        }

        String trimmed = message == null ? "" : message.trim();

        if (trimmed.isEmpty() || trimmed.length() > FilmCommit.MAX_MESSAGE)
        {
            return false;
        }

        commit.message = trimmed;
        save(store);

        return true;
    }

    /**
     * The whole graph of one film, ready to be put into a packet: branch heads plus every commit
     * in order. Diffs are not included, they are fetched per commit.
     */
    public static synchronized MapType list(String filmId)
    {
        Store store = get(filmId);

        if (store == null)
        {
            return null;
        }

        MapType map = new MapType();

        map.putString("film", filmId);
        map.putString("head", store.head() == null ? "" : store.head());
        map.putString("current", store.current);

        ListType commits = new ListType();

        for (FilmCommit commit : store.commits)
        {
            commits.add(commit.toData());
        }

        map.put("commits", commits);

        MapType branches = new MapType();

        for (Map.Entry<String, String> entry : store.branches.entrySet())
        {
            branches.putString(entry.getKey(), entry.getValue());
        }

        map.put("branches", branches);

        return map;
    }

    /** Drop the cache, called when the server stops. */
    public static synchronized void unload()
    {
        STORES.clear();
    }

    /* Helpers */

    private static String autoMessage(MapType stats, int kind)
    {
        int clips = count(stats, "clips", "added") + count(stats, "clips", "removed") + count(stats, "clips", "modified");
        int replays = count(stats, "replays", "added") + count(stats, "replays", "removed") + count(stats, "replays", "modified");
        int keyframes = stats.getInt("kf_added") + stats.getInt("kf_removed") + stats.getInt("kf_modified");

        if (clips == 0 && replays == 0 && keyframes == 0)
        {
            return kind == FilmCommit.KIND_IMPORT ? "初始快照" : "自动提交";
        }

        StringBuilder builder = new StringBuilder();

        if (clips > 0)
        {
            builder.append(clips).append(" 个剪辑");
        }

        if (replays > 0)
        {
            if (builder.length() > 0)
            {
                builder.append(" · ");
            }

            builder.append(replays).append(" 个回放");
        }

        if (keyframes > 0)
        {
            if (builder.length() > 0)
            {
                builder.append(" · ");
            }

            builder.append(keyframes).append(" 个关键帧");
        }

        return builder.toString();
    }

    private static int count(MapType stats, String group, String action)
    {
        MapType map = stats.getMap(group, null);

        return map == null ? 0 : map.getInt(action);
    }

    private static String hash(byte[] snapshot, List<String> parents, String author, long time)
    {
        try
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            digest.update(snapshot);

            for (String parent : parents)
            {
                digest.update(parent.getBytes(StandardCharsets.UTF_8));
            }

            digest.update(author.getBytes(StandardCharsets.UTF_8));
            digest.update(Long.toString(time).getBytes(StandardCharsets.UTF_8));

            byte[] bytes = digest.digest();
            StringBuilder builder = new StringBuilder();

            for (int i = 0; i < 5; i++)
            {
                builder.append(String.format("%02x", Integer.valueOf(bytes[i] & 0xFF)));
            }

            return builder.toString();
        }
        catch (Exception e)
        {
            return Integer.toHexString((snapshot.length + author.hashCode() + (int) time) & 0xFFFFFF);
        }
    }
}
