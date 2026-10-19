package mchorse.bbs_together.commits;

import mchorse.bbs_mod.BBSMod;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Where the files of one film live.
 *
 * <p>BBS stores a film as {@code <films>/<path>.dat} and keeps its automatic backups in a
 * sibling folder named {@code _<name>} (see {@code BaseManager#save}). That folder is already
 * invisible to BBS' film list because {@code FolderManager} skips directories that start with
 * an underscore. This class puts the collaboration sidecar in that same folder:</p>
 *
 * <pre>
 * &lt;films&gt;/&lt;path&gt;/&lt;film&gt;.dat          # the vanilla-compatible film (default branch)
 * &lt;films&gt;/&lt;path&gt;/_&lt;film&gt;/              # BBS backups and everything below
 * ├─ &lt;film&gt;.2026_10_19_22.dat          # BBS' own hourly backup
 * └─ _together/
 *    ├─ index.bin                       # commit graph
 *    ├─ s/&lt;commit&gt;.bin, d/&lt;commit&gt;.bin # snapshots and diffs
 *    ├─ w/&lt;branch hash&gt;.bin            # branch working copies
 *    └─ players.bin                     # branch chosen by each player
 * </pre>
 *
 * <p>Keeping the sidecar next to the film (instead of in a global {@code _commits} tree) means
 * renaming or moving a film folder carries its history with it, and a BBS installation without
 * Together only ever sees the single {@code .dat} file.</p>
 */
public final class FilmFolders
{
    /** Prefix of BBS' per-film backup folder, and of the folder that holds our sidecar. */
    private static final String SIDECAR_PREFIX = "_";

    /** Name of the collaboration folder inside the per-film folder. */
    public static final String TOGETHER = "_together";

    /** Legacy location: {@code <films>/_commits/<film>/}. */
    public static final String LEGACY = "_commits";

    private FilmFolders()
    {}

    /** The film file BBS reads and writes, or {@code null} when no film folder is available. */
    private static File filmFile(String filmId)
    {
        if (filmId == null || filmId.isEmpty())
        {
            return null;
        }

        try
        {
            return BBSMod.getFilms().getFile(filmId);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /**
     * The per-film folder that BBS uses for backups: {@code _<name>} next to {@code <name>.dat}.
     * It is also where this mod keeps its commit and branch data.
     */
    public static File sidecarFolder(String filmId)
    {
        File film = filmFile(filmId);

        if (film == null)
        {
            return null;
        }

        String name = film.getName();

        if (name.endsWith(".dat"))
        {
            name = name.substring(0, name.length() - 4);
        }
        else
        {
            int dot = name.lastIndexOf('.');

            if (dot > 0)
            {
                name = name.substring(0, dot);
            }
        }

        return new File(film.getParentFile(), SIDECAR_PREFIX + name);
    }

    /** The folder that holds commits and branch workspaces for one film. */
    public static File togetherFolder(String filmId)
    {
        File sidecar = sidecarFolder(filmId);

        return sidecar == null ? null : new File(sidecar, TOGETHER);
    }

    /** The old global history folder, used only to migrate existing worlds. */
    public static File legacyFolder(String filmId)
    {
        if (filmId == null || filmId.isEmpty())
        {
            return null;
        }

        try
        {
            File films = BBSMod.getFilms().getFolder();

            if (films == null)
            {
                return null;
            }

            return new File(new File(films, LEGACY), filmId.replace('/', File.separatorChar));
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /**
     * Derive the film id from a {@code _together} folder, given the films root. Returns
     * {@code null} when the folder is not inside a {@code _<name>} sidecar.
     */
    public static String filmIdOfTogetherFolder(File filmsRoot, File together)
    {
        if (filmsRoot == null || together == null || !TOGETHER.equals(together.getName()))
        {
            return null;
        }

        File sidecar = together.getParentFile();

        if (sidecar == null || !sidecar.getName().startsWith(SIDECAR_PREFIX))
        {
            return null;
        }

        String name = sidecar.getName().substring(SIDECAR_PREFIX.length());

        if (name.isEmpty())
        {
            return null;
        }

        try
        {
            String parent = filmsRoot.toPath().toAbsolutePath().normalize()
                .relativize(sidecar.getParentFile().toPath().toAbsolutePath().normalize())
                .toString()
                .replace(File.separatorChar, '/');

            return parent.isEmpty() ? name : parent + "/" + name;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /** Remove now-empty folders from {@code folder} upwards, stopping at {@code stop} (exclusive). */
    public static void deleteEmptyParents(File folder, File stop)
    {
        if (stop == null)
        {
            return;
        }

        File current = folder;

        while (current != null && !current.equals(stop))
        {
            File[] children = current.listFiles();

            if (children == null || children.length > 0 || !current.delete())
            {
                return;
            }

            current = current.getParentFile();
        }
    }

    /** Best-effort recursive delete. */
    public static void deleteRecursively(File file)
    {
        if (file == null || !file.exists())
        {
            return;
        }

        File[] children = file.listFiles();

        if (children != null)
        {
            for (File child : children)
            {
                deleteRecursively(child);
            }
        }

        file.delete();
    }

    /** Move a file or folder, creating the target's parents. */
    public static boolean move(File from, File to)
    {
        if (from == null || to == null || !from.exists())
        {
            return false;
        }

        try
        {
            File parent = to.getParentFile();

            if (parent != null)
            {
                Files.createDirectories(parent.toPath());
            }

            Files.move(from.toPath(), to.toPath());
            return true;
        }
        catch (IOException e)
        {
            System.err.println("[BBSTogether] Failed to move " + from + " to " + to + ": " + e);

            return false;
        }
    }
}
