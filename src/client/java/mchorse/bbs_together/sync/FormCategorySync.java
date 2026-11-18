package mchorse.bbs_together.sync;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.forms.FormCategories;
import mchorse.bbs_mod.forms.sections.UserFormSection;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Shares the user form categories ("类别" folders in the form palette) through the asset folder.
 *
 * <p>BBS keeps them in {@code config/bbs/settings/forms/N.json}, which no part of the asset sync
 * ever looks at, so a category authored on one machine never reached anybody else. The files are
 * self-contained JSON named by index, so pointing BBS at {@code assets/form_categories/N.json}
 * hands them the whole existing pipeline: sha1 comparison, mtime arbitration, sliced transfer,
 * upload permission and the sync watcher.</p>
 *
 * <p>The redirect lives in {@code UserFormSectionCategoriesPathMixin}; this class covers what that
 * pipeline does not: moving existing categories over once, and re-reading them after a download.</p>
 */
public final class FormCategorySync
{
    /** Folder under {@code config/bbs/assets} that holds the shared categories. */
    public static final String FOLDER = "form_categories";

    /** BBS reads at most this many category files ({@code UserFormSection.loadNewCategories}). */
    private static final int MAX_CATEGORIES = 420;

    /** Coalesces a burst of downloaded files (a reorder rewrites every category) into one reload. */
    private static final long RELOAD_DEBOUNCE_MS = 1500L;

    private static boolean migrated;
    private static long reloadAt;

    /** Category count the server reported last time, or -1 before the first manifest. */
    private static int lastServerCount = -1;

    private FormCategorySync()
    {}

    /** The shared category folder, or {@code null} while BBS has no asset folder yet. */
    public static File directory()
    {
        File assets = BBSMod.getAssetsFolder();

        return assets == null ? null : new File(assets, FOLDER);
    }

    /**
     * The file BBS would use for the category at {@code index}, in its shared location.
     *
     * <p>Returns {@code null} when the asset folder is unavailable, which makes the mixin leave
     * BBS's own path alone rather than break category saving.</p>
     */
    public static File file(int index)
    {
        File directory = directory();

        return directory == null ? null : new File(directory, index + ".json");
    }

    /**
     * Moves a player's private categories into the shared folder, once.
     *
     * <p>Copied rather than moved so an older version still finds the originals, and only while
     * {@code form_categories} does not exist yet. The modification time is preserved because the
     * sync arbitrates conflicts by mtime: a fresh timestamp would let a long-forgotten category
     * overwrite the one the server actually has.</p>
     */
    public static synchronized void ensureMigrated()
    {
        if (migrated)
        {
            return;
        }

        File target = directory();

        if (target == null)
        {
            /* BBS has not resolved its asset folder yet (this also runs during start-up); leaving
             * the flag alone keeps the one migration from silently being skipped for good. */
            return;
        }

        migrated = true;

        if (target.isDirectory())
        {
            return;
        }

        File legacy = BBSMod.getSettingsPath("forms");
        File[] files = legacy == null ? null : legacy.listFiles((dir, name) -> name.endsWith(".json"));

        if (files == null || files.length == 0 || !target.mkdirs())
        {
            return;
        }

        int copied = 0;

        for (File file : files)
        {
            try
            {
                Files.copy(file.toPath(), new File(target, file.getName()).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                copied += 1;
            }
            catch (Exception e)
            {
                System.out.println("[BBS Assets] Could not migrate form category " + file.getName());
                e.printStackTrace();
            }
        }

        System.out.println("[BBS Assets] Migrated " + copied + " form category file(s) into " + target);
    }

    /**
     * Asks for the categories to be re-read once the burst of downloads settles.
     *
     * <p>Not driven by BBS's own watch dog: that one also fires for the player's own saves, and
     * rebuilding the categories in the middle of an edit loses the edit, because the pending
     * {@code flush} looks its category up by identity. Only a file that arrived is worth a reload.</p>
     */
    public static void requestReload()
    {
        synchronized (FormCategorySync.class)
        {
            reloadAt = System.currentTimeMillis() + RELOAD_DEBOUNCE_MS;
        }
    }

    /** Pumps {@link #requestReload()}; called from the client tick. */
    public static void tick()
    {
        synchronized (FormCategorySync.class)
        {
            if (reloadAt == 0L || System.currentTimeMillis() < reloadAt)
            {
                return;
            }

            reloadAt = 0L;
        }

        reload();
    }

    private static void reload()
    {
        FormCategories categories = BBSModClient.getFormCategories();

        if (categories == null)
        {
            return;
        }

        UserFormSection section = categories.getUserForms();

        if (section == null)
        {
            return;
        }

        try
        {
            section.initiate();
            categories.markDirty();

            System.out.println("[BBS Assets] Reloaded " + section.categories.size() + " shared form category(ies)");
        }
        catch (Throwable e)
        {
            /* Runs from the client tick: anything thrown here would take the game down. */
            System.out.println("[BBS Assets] Could not reload the shared form categories: " + e);
        }
    }

    /**
     * How many categories the server manifest describes.
     *
     * <p>Files are named by index, so the highest one plus one is the count. A reorder or a
     * deletion rewrites the set, and the indices always stay contiguous.</p>
     */
    public static int countServerCategories(Collection<String> manifestKeys)
    {
        int highest = -1;

        for (String key : manifestKeys)
        {
            if (key == null || !key.startsWith(FOLDER + "/"))
            {
                continue;
            }

            int index = parseIndex(key.substring(FOLDER.length() + 1));

            if (index > highest)
            {
                highest = index;
            }
        }

        return highest + 1;
    }

    /**
     * Drops local category files that the server used to have and no longer does.
     *
     * <p>Deletion is the one thing the asset pipeline cannot express: a file that is gone from the
     * server is simply not in the manifest, so a client keeps it, and it ends up sitting next to the
     * categories that shifted down into its place (and gets uploaded right back). Files are named by
     * index, so a smaller count means one went away. The pruning is deliberately narrow, since a
     * file alone cannot say whether it disappeared on the server or was created here just now:</p>
     *
     * <ul>
     * <li>only while the count goes down, so a fresh, not yet uploaded category is never touched;</li>
     * <li>only indices below the previously reported count, which is all that was ever on the server;</li>
     * <li>only when the file's sha1 is absent from the manifest too, so a shifted-down category stays;</li>
     * <li>never when the server reports zero, which just means it never had any.</li>
     * </ul>
     */
    public static synchronized void pruneTo(int serverCount, Collection<String> serverSha1)
    {
        int previous = lastServerCount;

        lastServerCount = serverCount;

        if (previous < 0 || serverCount <= 0 || serverCount >= previous || serverCount >= MAX_CATEGORIES)
        {
            return;
        }

        File directory = directory();
        File[] files = directory == null ? null : directory.listFiles();

        if (files == null)
        {
            return;
        }

        Set<String> hashes = new HashSet<>();

        if (serverSha1 != null)
        {
            for (String sha1 : serverSha1)
            {
                if (sha1 != null && !sha1.isEmpty())
                {
                    hashes.add(sha1.toLowerCase(Locale.ROOT));
                }
            }
        }

        boolean removed = false;

        for (File file : files)
        {
            int index = parseIndex(file.getName());

            if (index < serverCount || index >= previous)
            {
                continue;
            }

            String sha1 = sha1Of(file);

            if (sha1 == null || hashes.contains(sha1))
            {
                continue;
            }

            if (file.delete())
            {
                removed = true;
                System.out.println("[BBS Assets] Dropped stale form category " + file.getName());
            }
        }

        if (removed)
        {
            requestReload();
        }
    }

    private static String sha1Of(File file)
    {
        try (InputStream input = Files.newInputStream(file.toPath()))
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] buffer = new byte[8192];

            for (int read = input.read(buffer); read > 0; read = input.read(buffer))
            {
                digest.update(buffer, 0, read);
            }

            StringBuilder builder = new StringBuilder();

            for (byte value : digest.digest())
            {
                builder.append(Character.forDigit((value >> 4) & 0xF, 16));
                builder.append(Character.forDigit(value & 0xF, 16));
            }

            return builder.toString();
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static int parseIndex(String name)
    {
        if (!name.endsWith(".json"))
        {
            return -1;
        }

        try
        {
            return Integer.parseInt(name.substring(0, name.length() - 5));
        }
        catch (NumberFormatException e)
        {
            return -1;
        }
    }
}
