package mchorse.bbs_together.utils;

/**
 * Utilities to aggregate asset paths into model IDs.
 *
 * <p>A model is made of many files (<code>config.json</code>, <code>*.bbmodel</code>,
 * <code>*.bbs.json</code>, <code>textures/*.png</code>, ...). A path like
 * <code>models/foo/bar/config.json</code> belongs to the model <code>foo/bar</code>,
 * while <code>models/foo.bbmodel</code> or <code>models/foo/bar.bbmodel</code> both
 * belong to the model <code>foo</code>.</p>
 */
public class ModelPathUtils
{
    /**
     * Whether this asset path is a skin (a model's or player's skins folder)
     * and therefore shouldn't make it into the behavior log. Checks both the
     * raw path and the case where a bare directory ends at the skins folder.
     */
    public static boolean isSkinPath(String path)
    {
        if (path == null)
        {
            return false;
        }

        String lower = path.toLowerCase();

        return lower.contains("/skins/")
            || lower.contains("/skin/")
            || lower.endsWith("/skins")
            || lower.endsWith("/skins/")
            || lower.endsWith("/skin")
            || lower.endsWith("/skin/");
    }

    /**
     * Extract a model ID from a relative asset path. Returns null for paths
     * that don't live under <code>models/</code>.
     */
    public static String extractModelId(String relativePath)
    {
        if (relativePath == null || !relativePath.startsWith("models/"))
        {
            return null;
        }

        String after = relativePath.substring("models/".length());
        int slash = after.lastIndexOf('/');

        if (slash < 0)
        {
            /* "foo.bbmodel" -> "foo" */
            return stripExtension(after);
        }

        String file = after.substring(slash + 1);
        String folder = after.substring(0, slash);

        if (file.contains("."))
        {
            /* "foo/bar.bbmodel" -> "foo", "foo/bar/config.json" -> "foo/bar" (folder has no dot) */
            return stripExtension(folder);
        }

        /* "foo/bar/textures" (a folder, rare) -> "foo/bar" */
        return folder;
    }

    /**
     * Strip the last file extension, but only when it follows the last path
     * separator — so <code>foo/bar</code> and <code>my.v2/bar</code> keep their dots.
     */
    private static String stripExtension(String value)
    {
        int dot = value.lastIndexOf('.');
        int slash = value.lastIndexOf('/');

        if (dot > slash && dot > 0)
        {
            return value.substring(0, dot);
        }

        return value;
    }
}
