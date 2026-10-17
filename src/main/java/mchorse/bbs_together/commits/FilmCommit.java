package mchorse.bbs_together.commits;

import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.data.types.ListType;

import java.util.ArrayList;
import java.util.List;

/**
 * One commit of one film.
 *
 * <p>The id is content addressed: the snapshot, the parents, the author and the time go into the
 * hash, the message does not. Renaming a commit therefore rewrites {@link #message} only, and the
 * node keeps its place in the graph, its parents and its id. The colour works the same way: it is
 * stored next to the commit, but it is not part of the hash.</p>
 */
public final class FilmCommit
{
    /** A commit the user asked for. */
    public static final int KIND_MANUAL = 0;

    /** A commit the server recorded after a save. Only found in histories from before commits
     * became manual. */
    public static final int KIND_AUTO = 1;

    /** A baseline commit made from what the film looked like before the first recorded save. */
    public static final int KIND_IMPORT = 2;

    /** A commit that undoes an earlier one. */
    public static final int KIND_REVERT = 3;

    /** Longest accepted commit message, in characters. */
    public static final int MAX_MESSAGE = 120;

    /** Longest accepted branch name, in characters. */
    public static final int MAX_BRANCH = 40;

    /** How many hex characters of the id are shown in the UI. */
    public static final int SHORT_ID = 7;

    public String id = "";
    public String filmId = "";

    /** Zero for a root commit, one for a normal commit, two for a merge. */
    public final List<String> parents = new ArrayList<String>();

    public String branch = FilmCommitStore.BRANCH_MAIN;
    public String authorId = "";
    public String authorName = "";
    public long time;
    public String message = "";
    public int kind = KIND_AUTO;

    /** Summary of the change, as produced by {@link FilmDiffBuilder}. */
    public MapType stats = new MapType();

    /**
     * Colour of the node in the graph (ARGB), or {@code 0} when nobody has recoloured it.
     * The value lives on the server with the commit, so every player reads the same colour;
     * {@code 0} lets the client fall back to gray or to a colour saved by an older version.
     */
    public int color = 0;

    public String shortId()
    {
        return this.id.length() > SHORT_ID ? this.id.substring(0, SHORT_ID) : this.id;
    }

    public String parent()
    {
        return this.parents.isEmpty() ? "" : this.parents.get(0);
    }

    public MapType toData()
    {
        MapType map = new MapType();

        map.putString("id", this.id);
        map.putString("film", this.filmId);

        ListType parentList = new ListType();

        for (String parent : this.parents)
        {
            parentList.addString(parent);
        }

        map.put("parents", parentList);
        map.putString("branch", this.branch);
        map.putString("author", this.authorId);
        map.putString("author_name", this.authorName);
        map.putLong("time", this.time);
        map.putString("message", this.message);
        map.putInt("kind", this.kind);
        map.put("stats", this.stats);
        map.putInt("color", this.color);

        return map;
    }

    public static FilmCommit fromData(MapType map)
    {
        FilmCommit commit = new FilmCommit();

        commit.id = map.getString("id");
        commit.filmId = map.getString("film");
        commit.branch = map.getString("branch", FilmCommitStore.BRANCH_MAIN);
        commit.authorId = map.getString("author");
        commit.authorName = map.getString("author_name");
        commit.time = map.getLong("time");
        commit.message = map.getString("message");
        commit.kind = map.getInt("kind");
        commit.stats = map.getMap("stats");
        /* Index files written before colours existed simply have no key */
        commit.color = map.getInt("color", 0);

        ListType parents = map.getList("parents");

        for (int i = 0; i < parents.size(); i++)
        {
            commit.parents.add(parents.getString(i));
        }

        return commit;
    }
}
