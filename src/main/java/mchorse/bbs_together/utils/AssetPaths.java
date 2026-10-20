package mchorse.bbs_together.utils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Filename rules for the asset tree.
 *
 * <p>Multi-slice transfers write into {@code <name>.bbspart} (download) or {@code <name>.bbsup}
 * (upload) next to their target. Those files are implementation detail: if they ever leak into a
 * manifest or an inventory, both sides start hashing, advertising and transferring each other's
 * half-written temp files — which on a big asset pack costs hundreds of megabytes of pointless
 * I/O and bandwidth per join. Every walk over the assets folder must go through this class.</p>
 *
 * <p>The resolver is deliberately strict: asset ids are wire data, so absolute paths, Windows
 * drive/UNC paths, backslashes and dot segments must never be allowed to escape the assets
 * root. {@code Path.resolve} alone is not enough because it returns an absolute argument
 * unchanged.</p>
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

    /**
     * Validate a wire asset path and resolve it under {@code root}. Returns {@code null} for
     * anything that could escape the root or is not a clean relative path.
     */
    public static Path resolveSafe(File root, String relativePath) {
        if (root == null || !isSafeRelative(relativePath)) {
            return null;
        }

        try {
            Path base = root.toPath().toAbsolutePath().normalize();
            Path target = base.resolve(relativePath).normalize();

            if (!target.startsWith(base)) {
                return null;
            }

            /* Refuse symlinked components: a link inside assets could point outside even
             * though the lexical path is under the root. */
            Path current = base;

            for (Path part : base.relativize(target)) {
                current = current.resolve(part);

                if (Files.isSymbolicLink(current)) {
                    return null;
                }
            }

            return target;
        }
        catch (Exception e) {
            return null;
        }
    }

    /** Validate a wire asset path without touching the filesystem. */
    public static boolean isSafeRelative(String relativePath) {
        if (relativePath == null || relativePath.isEmpty() || relativePath.length() > 1024) {
            return false;
        }

        if (relativePath.charAt(0) == '/' || relativePath.indexOf('\\') >= 0
            || relativePath.indexOf(':') >= 0 || relativePath.indexOf('\0') >= 0) {
            return false;
        }

        String[] parts = relativePath.split("/", -1);

        for (String part : parts) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                return false;
            }
        }

        return true;
    }

    /** @return true for a path that may be published in a manifest (no temps, no escapes). */
    public static boolean isPublishable(String relativePath) {
        return isSafeRelative(relativePath) && !isTransferTemp(relativePath);
    }

    /** Resolve and return a regular file, or null when the path is unsafe/missing/not a file. */
    public static File resolveExistingFile(File root, String relativePath) {
        Path target = resolveSafe(root, relativePath);

        if (target == null || !Files.isRegularFile(target)) {
            return null;
        }

        return target.toFile();
    }
}
