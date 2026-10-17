package mchorse.bbs_together.utils;

/**
 * Filename rules for the asset tree.
 *
 * <p>Multi-slice transfers write into {@code <name>.bbspart} (download) or {@code <name>.bbsup}
 * (upload) next to their target. Those files are implementation detail: if they ever leak into a
 * manifest or an inventory, both sides start hashing, advertising and transferring each other's
 * half-written temp files — which on a big asset pack costs hundreds of megabytes of pointless
 * I/O and bandwidth per join. Every walk over the assets folder must go through this class.</p>
 */
public final class AssetPaths {
    private AssetPaths() {}

    /** @return true for the transient slice files of an in-progress transfer. */
    public static boolean isTransferTemp(String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) {
            return false;
        }
        return relativePath.endsWith(".bbspart")
            || relativePath.endsWith(".bbsup")
            || relativePath.endsWith(".bbspart.tmp");
    }

    /** @return true for a path that may be published in a manifest (no temps, no escapes). */
    public static boolean isPublishable(String relativePath) {
        return relativePath != null
            && !relativePath.isEmpty()
            && !relativePath.contains("..")
            && !relativePath.startsWith("/")
            && !isTransferTemp(relativePath);
    }
}
