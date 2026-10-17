package mchorse.bbs_together.sync;

import java.nio.file.Path;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * D1: remembers which files this client just wrote itself, so the assets folder watcher does not
 * treat a download/push as a local edit.
 *
 * <p>Without this every synced file bounced back as a local change: the watcher fired after its
 * quiet period, the client asked for a manifest again, diffed the whole tree, and reported its
 * inventory again — a self-sustaining loop that cost a full round trip per downloaded file while
 * the download was still running.</p>
 *
 * <p>Entries are kept for a short while (a single write raises CREATE and MODIFY, sometimes in
 * different watch batches) and expire on their own. Paths are compared case-insensitively because
 * Windows file systems are.</p>
 */
@Environment(EnvType.CLIENT)
public final class SelfWriteGuard {
    /** Long enough to cover the watcher's 3 s quiet window plus the write that follows it. */
    private static final long TTL_MS = 20_000L;
    private static final int MAX_ENTRIES = 4096;

    private static final ConcurrentHashMap<String, Long> WRITTEN = new ConcurrentHashMap<>();

    private SelfWriteGuard() {}

    /** Marks a path as written by us; call it right after the write lands. */
    public static void record(Path path) {
        if (path == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (WRITTEN.size() > MAX_ENTRIES) {
            prune(now);
        }
        WRITTEN.put(key(path), now);
    }

    /** True when this path was written by us recently, i.e. the watcher event is ours to ignore. */
    public static boolean isSelfWrite(Path path) {
        if (path == null) {
            return false;
        }
        String key = key(path);
        Long stamp = WRITTEN.get(key);
        if (stamp == null) {
            return false;
        }
        if (System.currentTimeMillis() - stamp > TTL_MS) {
            WRITTEN.remove(key);
            return false;
        }
        return true;
    }

    public static void reset() {
        WRITTEN.clear();
    }

    private static void prune(long now) {
        Iterator<Map.Entry<String, Long>> it = WRITTEN.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue() > TTL_MS) {
                it.remove();
            }
        }
    }

    private static String key(Path path) {
        return path.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
    }
}
