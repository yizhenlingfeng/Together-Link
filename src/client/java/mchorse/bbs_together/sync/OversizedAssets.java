package mchorse.bbs_together.sync;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side registry of server assets that are bigger than the configured sync size limit
 * ({@code asset_sync_max_mb}).
 *
 * <p>Such assets are never transferred: the client only writes an empty stub with the right
 * name and path, so BBS can still list the entry, and every UI that shows that entry paints
 * it dimmed with a "file too large" tag instead of letting the user click a file that would
 * never arrive. This class is that "which entries are too large" lookup, keyed by the same
 * relative path the manifest uses ({@code video/xxx.mp4}), and it also accepts the BBS link
 * form ({@code bbs:video/xxx.mp4}) so pickers and grids can query it with what they render.</p>
 */
public final class OversizedAssets
{
    /** Shown next to every dimmed entry. Kept in one place so grids and lists agree. */
    public static final String LABEL = "文件尺寸过大";

    private static final Map<String, Long> FILES = new ConcurrentHashMap<String, Long>();

    private OversizedAssets() {}

    public static void clear()
    {
        FILES.clear();
    }

    public static boolean isEmpty()
    {
        return FILES.isEmpty();
    }

    public static int count()
    {
        return FILES.size();
    }

    /** Records one over-size server asset. {@code size} is the real size on the server. */
    public static void mark(String relativePath, long size)
    {
        String key = normalize(relativePath);

        if (key.isEmpty() || size <= 0L)
        {
            return;
        }

        FILES.put(key, Long.valueOf(size));
    }

    /**
     * Looks a path up. Accepts a manifest path and a BBS link string ({@code bbs:video/a.mp4});
     * returns {@code null} when the entry is unknown or within the limit.
     */
    public static Long sizeOf(String linkOrPath)
    {
        if (FILES.isEmpty() || linkOrPath == null)
        {
            return null;
        }

        String key = normalize(linkOrPath);

        return key.isEmpty() ? null : FILES.get(key);
    }

    /** Human readable size for the tag: {@code 706.6 MB}, {@code 512 KB}, {@code 900 B}. */
    public static String humanSize(long bytes)
    {
        if (bytes < 1024L)
        {
            return bytes + " B";
        }

        double kb = bytes / 1024D;

        if (kb < 1024D)
        {
            return String.format(Locale.ROOT, "%.0f KB", kb);
        }

        double mb = kb / 1024D;

        if (mb < 1024D)
        {
            return String.format(Locale.ROOT, "%.1f MB", mb);
        }

        return String.format(Locale.ROOT, "%.2f GB", mb / 1024D);
    }

    /** The full tag: {@code 文件尺寸过大 · 706.6 MB}. */
    public static String label(long size)
    {
        return LABEL + " · " + humanSize(size);
    }

    /**
     * Turns a manifest path or a BBS link into the key used by {@link #FILES}.
     *
     * <p>Links look like {@code bbs:video/a.mp4} — the namespace (and any {@code assets/}
     * prefix) is not part of the assets folder layout, so it is dropped. Everything is
     * lowercased because Windows and the manifest disagree about case on the same folder
     * ({@code PBR_ALL} vs {@code pbr_all}) and a case-sensitive miss would silently show a
     * real file as if it were fine.</p>
     */
    public static String normalize(String raw)
    {
        if (raw == null)
        {
            return "";
        }

        String value = raw.trim();

        /* Link: "<source>:<path>" — drop the source when it looks like a namespace rather
         * than a drive letter or a plain path. */
        int colon = value.indexOf(':');

        if (colon > 0 && colon <= 16 && value.indexOf('/') != 0 && value.indexOf('\\') != colon + 1)
        {
            value = value.substring(colon + 1);
        }

        value = value.replace('\\', '/');

        if (value.regionMatches(true, 0, "assets/", 0, 7))
        {
            value = value.substring(7);
        }

        while (value.startsWith("/"))
        {
            value = value.substring(1);
        }

        return value.toLowerCase(Locale.ROOT);
    }
}
