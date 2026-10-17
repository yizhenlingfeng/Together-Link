package mchorse.bbs_together.commits;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.data.DataStorageUtils;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.Film;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-branch working copies of one film, plus the branch each player is currently on.
 *
 * <p>Before this class there was one film file and one "current branch" for the whole server:
 * checking a branch out replaced the film for everybody. Now every branch gets its own working
 * copy under {@code <films>/_commits/<film>/w/}, so two players can edit two branches of the same
 * film at the same time. The main BBS film file follows the film's <b>default</b> branch only
 * (the branch {@link FilmCommitStore.Store#current} points at); other branches live here and are
 * never written into the main file.</p>
 *
 * <p>The branch a player is on is stored per film in {@code players.bin}, so a player returns to
 * the same branch after a reconnect or a server restart.</p>
 *
 * <p>Everything touches the disk and the server's film data, so public methods are synchronized
 * and are meant to be called from the server thread. The one exception is reading branch choices,
 * which the asset/film inventory thread may do; the maps are synchronized as well.</p>
 */
public final class FilmBranchStore
{
    private static final String WORKSPACES = "w";
    private static final String PLAYERS = "players.bin";
    private static final String EXTENSION = ".bin";

    /** filmId -> branch -> working copy. */
    private static final Map<String, Map<String, Film>> WORKING = new HashMap<>();
    /** filmId -> player -> branch. */
    private static final Map<String, Map<UUID, String>> PLAYER_BRANCHES = new HashMap<>();

    private FilmBranchStore()
    {}

    /* Default branch */

    /** The branch the main BBS film file represents; new players start here. */
    public static synchronized String defaultBranch(String filmId)
    {
        FilmCommitStore.Store store = FilmCommitStore.get(filmId);

        if (store == null || store.current == null || store.current.isEmpty())
        {
            return FilmCommitStore.BRANCH_MAIN;
        }

        return store.current;
    }

    public static synchronized boolean isDefault(String filmId, String branch)
    {
        return defaultBranch(filmId).equals(branch);
    }

    /* Player branch */

    /** The branch of {@code filmId} this player is on, falling back to the default branch. */
    public static synchronized String branchOf(String filmId, UUID playerId)
    {
        if (filmId == null || filmId.isEmpty())
        {
            return FilmCommitStore.BRANCH_MAIN;
        }

        String fallback = defaultBranch(filmId);

        if (playerId == null)
        {
            return fallback;
        }

        Map<UUID, String> players = players(filmId);
        String branch = players.get(playerId);

        if (branch == null || branch.isEmpty())
        {
            return fallback;
        }

        if (!exists(filmId, branch) && !branch.equals(fallback))
        {
            /* Do not erase the saved choice: a temporarily unreadable index (or a branch
             * added while the store was loading) would otherwise permanently reset the
             * player to the default branch. */
            return fallback;
        }

        return branch;
    }

    /**
     * Put one player on a branch. The main film file and the other players are not touched.
     *
     * @return {@code false} when the branch does not exist (or is empty)
     */
    public static synchronized boolean setBranch(String filmId, UUID playerId, String branch)
    {
        if (playerId == null || branch == null || branch.isEmpty() || filmId == null || filmId.isEmpty())
        {
            return false;
        }

        String trimmed = branch.trim();

        if (!trimmed.equals(defaultBranch(filmId)) && !exists(filmId, trimmed))
        {
            return false;
        }

        Map<UUID, String> players = players(filmId);

        if (trimmed.equals(players.get(playerId)))
        {
            return true;
        }

        players.put(playerId, trimmed);
        savePlayers(filmId, players);

        return true;
    }

    private static boolean exists(String filmId, String branch)
    {
        FilmCommitStore.Store store = FilmCommitStore.get(filmId);

        return store != null && branch != null && store.branches.containsKey(branch);
    }

    private static Map<UUID, String> players(String filmId)
    {
        Map<UUID, String> players = PLAYER_BRANCHES.get(filmId);

        if (players != null)
        {
            return players;
        }

        players = new HashMap<UUID, String>();

        File folder = commitFolder(filmId);
        File file = folder == null ? null : new File(folder, PLAYERS);

        if (file != null && file.isFile())
        {
            try
            {
                BaseType data = DataStorageUtils.readFromBytes(Files.readAllBytes(file.toPath()));

                if (data != null && data.isMap())
                {
                    MapType map = data.asMap();

                    for (String key : map.keys())
                    {
                        try
                        {
                            players.put(UUID.fromString(key), map.getString(key));
                        }
                        catch (IllegalArgumentException ignored)
                        {}
                    }
                }
            }
            catch (Exception ignored)
            {}
        }

        PLAYER_BRANCHES.put(filmId, players);

        return players;
    }

    private static void savePlayers(String filmId, Map<UUID, String> players)
    {
        try
        {
            File folder = commitFolder(filmId);

            if (folder == null)
            {
                return;
            }

            folder.mkdirs();

            MapType map = new MapType();

            for (Map.Entry<UUID, String> entry : players.entrySet())
            {
                map.putString(entry.getKey().toString(), entry.getValue());
            }

            writeAtomic(new File(folder, PLAYERS), DataStorageUtils.writeToBytes(map));
        }
        catch (Exception ignored)
        {}
    }

    /* Working copies */

    /**
     * The working film of one branch. A branch with no working file starts from its head commit;
     * the default branch starts from the main film file, so edits that predate this class are
     * kept.
     */
    public static synchronized Film working(String filmId, String branch)
    {
        String actual = normalize(filmId, branch);
        Map<String, Film> branches = WORKING.computeIfAbsent(filmId, key -> new HashMap<>());
        Film film = branches.get(actual);

        if (film != null)
        {
            return film;
        }

        /* Default branch: the main .dat and w/<hash>.bin mirror each other. Prefer whichever
         * was written last, so an out-of-band BBS save or a crash between the two writes does
         * not resurrect stale data. */
        if (actual.equals(defaultBranch(filmId)))
        {
            File mainFile = BBSMod.getFilms().getFile(filmId);
            File workspaceFile = workspaceFile(filmId, actual);

            if (mainFile != null && mainFile.isFile()
                && (workspaceFile == null || !workspaceFile.isFile()
                || mainFile.lastModified() > workspaceFile.lastModified()))
            {
                MapType initial = initialData(filmId, actual);

                if (initial != null)
                {
                    film = createFrom(filmId, actual, initial);
                }
            }
        }

        if (film == null)
        {
            film = loadWorkspace(filmId, actual);
        }

        if (film == null)
        {
            film = createFrom(filmId, actual, initialData(filmId, actual));
        }

        branches.put(actual, film);

        return film;
    }

    private static Film createFrom(String filmId, String branch, MapType data)
    {
        Film film = new Film();
        film.setId(filmId);

        if (data != null)
        {
            try
            {
                film.fromData(data);
            }
            catch (Exception e)
            {
                System.err.println("[BBSTogether] Failed to read initial branch data " + filmId + " / " + branch + ": " + e);
            }
        }

        return film;
    }

    /** Serialized working film of one branch. */
    public static synchronized MapType workingData(String filmId, String branch)
    {
        return (MapType) working(filmId, branch).toData();
    }

    /** Replace a branch's working film after a full save, commit or rollback. */
    public static synchronized void setWorking(String filmId, String branch, MapType data)
    {
        if (data == null)
        {
            return;
        }

        String actual = normalize(filmId, branch);
        Film film = new Film();
        film.setId(filmId);

        try
        {
            film.fromData(data);
        }
        catch (Exception e)
        {
            System.err.println("[BBSTogether] Failed to apply branch data " + filmId + " / " + actual + ": " + e);

            return;
        }

        WORKING.computeIfAbsent(filmId, key -> new HashMap<>()).put(actual, film);
    }

    /** Write one branch's working copy to disk. */
    public static synchronized void save(String filmId, String branch)
    {
        String actual = normalize(filmId, branch);
        Map<String, Film> branches = WORKING.get(filmId);
        Film film = branches == null ? null : branches.get(actual);

        if (film == null)
        {
            return;
        }

        try
        {
            File folder = workspaceFolder(filmId);

            if (folder == null)
            {
                return;
            }

            folder.mkdirs();

            MapType data = (MapType) film.toData();

            writeAtomic(new File(folder, branchKey(actual) + EXTENSION), DataStorageUtils.writeToBytes(data));

            /* The main BBS file mirrors the default branch. Keeping this in one place means
             * every caller (debounced edit, commit, rollback, recording, shutdown) updates
             * both copies. */
            if (isDefault(filmId, actual))
            {
                for (int attempt = 0; ; attempt++)
                {
                    try
                    {
                        BBSMod.getFilms().save(filmId, data);
                        break;
                    }
                    catch (Exception e)
                    {
                        /* BBS may be writing the same .dat from another thread on Windows. */
                        if (attempt >= 2)
                        {
                            throw e;
                        }

                        try
                        {
                            Thread.sleep(100L * (attempt + 1));
                        }
                        catch (InterruptedException interrupted)
                        {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
        }
        catch (Exception e)
        {
            System.err.println("[BBSTogether] Failed to save branch working copy " + filmId + " / " + actual + ": " + e);
        }
    }

    /** Write every cached branch working copy (server shutdown). */
    public static synchronized void saveAll()
    {
        for (String filmId : new HashMap<String, Map<String, Film>>(WORKING).keySet())
        {
            Map<String, Film> branches = WORKING.get(filmId);

            if (branches == null)
            {
                continue;
            }

            for (String branch : new HashMap<String, Film>(branches).keySet())
            {
                save(filmId, branch);
            }
        }
    }

    /** Drop every cache; called when the server stops. */
    public static synchronized void unload()
    {
        WORKING.clear();
        PLAYER_BRANCHES.clear();
    }

    /** Remove one film's branch data cache (film deleted; the folder is removed by FilmCommitStore). */
    public static synchronized void forgetFilm(String filmId)
    {
        WORKING.remove(filmId);
        PLAYER_BRANCHES.remove(filmId);
    }

    /** Move the caches when BBS renames a film; the folder itself is moved by FilmCommitStore. */
    public static synchronized void renameFilm(String oldId, String newId)
    {
        Map<String, Film> branches = WORKING.remove(oldId);

        if (branches != null)
        {
            for (Film film : branches.values())
            {
                film.setId(newId);
            }

            WORKING.put(newId, branches);
        }

        Map<UUID, String> players = PLAYER_BRANCHES.remove(oldId);

        if (players != null)
        {
            PLAYER_BRANCHES.put(newId, players);
        }
    }

    private static String normalize(String filmId, String branch)
    {
        String fallback = defaultBranch(filmId);

        if (branch == null || branch.isEmpty())
        {
            return fallback;
        }

        if (!branch.equals(fallback) && !exists(filmId, branch))
        {
            return fallback;
        }

        return branch;
    }

    private static File workspaceFile(String filmId, String branch)
    {
        File folder = workspaceFolder(filmId);

        return folder == null ? null : new File(folder, branchKey(branch) + EXTENSION);
    }

    private static Film loadWorkspace(String filmId, String branch)
    {
        File file = workspaceFile(filmId, branch);

        if (file == null || !file.isFile())
        {
            return null;
        }

        try
        {
            BaseType data = DataStorageUtils.readFromBytes(Files.readAllBytes(file.toPath()));

            if (data == null || !data.isMap())
            {
                return null;
            }

            Film film = new Film();
            film.setId(filmId);
            film.fromData(data);

            return film;
        }
        catch (Exception e)
        {
            System.err.println("[BBSTogether] Failed to read branch working copy " + filmId + " / " + branch + ": " + e);

            return null;
        }
    }

    private static MapType initialData(String filmId, String branch)
    {
        FilmCommitStore.Store store = FilmCommitStore.get(filmId);

        /* The main file is the authoritative default branch: it may contain edits that were
         * never committed, so prefer it over the head snapshot. */
        if (store == null || branch.equals(store.current))
        {
            Film main = BBSMod.getFilms().load(filmId);

            if (main != null)
            {
                return (MapType) main.toData();
            }

            if (store == null)
            {
                return null;
            }
        }

        /* Other branches must start from their own head; falling back to the main file would
         * leak the default branch's content into a branch that has no data yet. */
        String head = store.branchHead(branch);

        if (head != null && !head.isEmpty())
        {
            return FilmCommitStore.snapshot(store, head);
        }

        return null;
    }

    /* Files */

    private static File commitFolder(String filmId)
    {
        try
        {
            File folder = BBSMod.getFilms().getFolder();

            if (folder == null)
            {
                return null;
            }

            return new File(new File(folder, "_commits"), filmId.replace('/', File.separatorChar));
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static File workspaceFolder(String filmId)
    {
        File folder = commitFolder(filmId);

        return folder == null ? null : new File(folder, WORKSPACES);
    }

    private static String branchKey(String branch)
    {
        try
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(branch.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();

            for (byte b : bytes)
            {
                builder.append(String.format("%02x", b & 0xFF));
            }

            return builder.toString();
        }
        catch (Exception e)
        {
            return Integer.toHexString(branch.hashCode());
        }
    }

    private static void writeAtomic(File target, byte[] bytes) throws IOException
    {
        Files.createDirectories(target.getParentFile().toPath());

        File temp = new File(target.getParentFile(), target.getName() + ".tmp");

        Files.write(temp.toPath(), bytes);
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
}
