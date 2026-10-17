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
            /* "foo.bbmodel" -> "foo", "foo.geo.json" -> "foo" */
            return stripModelExtension(after);
        }

        String folder = after.substring(0, slash);

        /* Never strip anything off the folder: a folder name may legitimately contain a dot
         * ("Steve 9.0 Model"), and stripping there used to collapse unrelated model folders
         * into the same id ("PBR_ALL/Steve 9"), which then tripped the per-model size caps
         * on the wrong model and left folders holding nothing but config.json. */
        return folder;
    }

    /** Strip the model file extensions (including the double ones BBS uses). */
    private static String stripModelExtension(String file)
    {
        if (file.endsWith(".geo.json"))
        {
            return file.substring(0, file.length() - ".geo.json".length());
        }
        if (file.endsWith(".bbs.json"))
        {
            return file.substring(0, file.length() - ".bbs.json".length());
        }

        return stripExtension(file);
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
