package mchorse.bbs_together.sync;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side single source of truth for asset sync progress.
 *
 * <p>Two UI surfaces read from this tracker instead of maintaining their own counters:</p>
 * <ul>
 *     <li>the thin global bar above the dashboard task bar;</li>
 *     <li>the per-asset strip drawn under each list entry / texture tile.</li>
 * </ul>
 *
 * <p>Granularity is one file = one task (the "minimal" design): a single transfer cannot report
 * byte-level progress because crusher reassembles whole files, so while a task is ACTIVE the UI
 * draws an indeterminate shimmer. Thread-safety: callbacks fire on network / IO threads while the
 * UI reads on the render thread, so the map is concurrent and all mutable state is volatile.</p>
 */
public final class AssetSyncTracker {

    public enum Phase { IDLE, SCANNING, SYNCING, FINISHED }

    public enum Dir { DOWNLOAD, UPLOAD, PUSH }

    public enum State { PENDING, ACTIVE, DONE, ERROR }

    public static final class AssetState {
        public volatile Dir dir;
        public volatile State state;
        public volatile long updatedAt;

        AssetState(Dir dir) {
            this.dir = dir;
            this.state = State.PENDING;
            this.updatedAt = System.currentTimeMillis();
        }
    }

    /** Key -> state. Key is an assets-relative path ("models/foo.jem") or "film:&lt;id&gt;". */
    private static final Map<String, AssetState> assets = new ConcurrentHashMap<>();

    private static volatile Phase phase = Phase.IDLE;
    private static volatile long finishedAt = 0L;

    /** How long the finished bar lingers on screen before fading back to idle. */
    public static final long HOLD_AFTER_DONE_MS = 1500L;

    private AssetSyncTracker() {}

    /* ---- lifecycle ---------------------------------------------------- */

    /** Called when the manifest has arrived and local files are being hashed/compared. */
    public static void beginScan() {
        phase = Phase.SCANNING;
        finishedAt = 0L;
    }

    /** Called when scanning found nothing to do (already in sync). */
    public static void abort() {
        assets.clear();
        phase = Phase.IDLE;
        finishedAt = 0L;
    }

    public static void reset() {
        assets.clear();
        phase = Phase.IDLE;
        finishedAt = 0L;
    }

    /* ---- per-asset events --------------------------------------------- */

    /** Register a known task that still has to be transferred. */
    public static void pending(String key, Dir dir) {
        if (key == null || key.isEmpty()) return;
        assets.compute(key, (k, old) -> {
            AssetState s = old != null ? old : new AssetState(dir);
            s.dir = dir;
            s.state = State.PENDING;
            s.updatedAt = System.currentTimeMillis();
            return s;
        });
        wakeFromScan();
    }

    /** A task has started moving (a file is being written / sent). */
    public static void active(String key, Dir dir) {
        if (key == null || key.isEmpty()) return;
        AssetState s = assets.get(key);
        if (s == null) {
            s = new AssetState(dir);
            assets.put(key, s);
        }
        s.state = State.ACTIVE;
        s.updatedAt = System.currentTimeMillis();
    }

    /** A task finished successfully. */
    public static void done(String key) {
        if (key == null || key.isEmpty()) return;
        AssetState s = assets.get(key);
        if (s == null) {
            // Pushes can arrive unannounced; remember them briefly.
            s = new AssetState(Dir.PUSH);
            assets.put(key, s);
        }
        s.state = State.DONE;
        s.updatedAt = System.currentTimeMillis();
        maybeFinish();
    }

    /** A task failed. Counts as "settled" for the global progress denominator. */
    public static void error(String key) {
        if (key == null || key.isEmpty()) return;
        AssetState s = assets.get(key);
        if (s == null) {
            s = new AssetState(Dir.DOWNLOAD);
            assets.put(key, s);
        }
        s.state = State.ERROR;
        s.updatedAt = System.currentTimeMillis();
        maybeFinish();
    }

    /* ---- queries for the UI ------------------------------------------- */

    public static Phase getPhase() {
        return phase;
    }

    /** When the run finished (for fade-out), or 0 if not finished. */
    public static long getFinishedAt() {
        return finishedAt;
    }

    public static AssetState get(String key) {
        return key == null ? null : assets.get(key);
    }

    /**
     * Resolve a document-list row (an id like "foo" or "sub/foo") to the tracker entry for the
     * actual asset file. The list shows document ids without extension, while the manifest keys are
     * real relative paths ("models/foo.json", "particles/foo.json", ...), so probe exact match first
     * and fall back to prefix matching.
     */
    public static AssetState findByDocumentKey(String... prefixes) {
        for (String p : prefixes) {
            AssetState s = assets.get(p);
            if (s != null) return s;
        }
        for (Map.Entry<String, AssetState> e : assets.entrySet()) {
            String k = e.getKey();
            for (String p : prefixes) {
                if (k.startsWith(p + "/") || k.startsWith(p + ".")) return e.getValue();
            }
        }
        return null;
    }

    /**
     * Global progress over the sync tasks (download + upload, pushes excluded).
     * Returns a value in [0, 1]; when there is nothing to track it returns 0.
     */
    public static float globalProgress() {
        int total = 0;
        int settled = 0;
        for (Map.Entry<String, AssetState> e : assets.entrySet()) {
            AssetState s = e.getValue();
            if (s.dir == Dir.PUSH) continue;
            total++;
            if (s.state == State.DONE || s.state == State.ERROR) settled++;
        }
        return total == 0 ? 0F : (float) settled / (float) total;
    }

    public static boolean hasErrors() {
        for (AssetState s : assets.values()) {
            if (s.state == State.ERROR) return true;
        }
        return false;
    }

    /**
     * Called once per frame by the render side: expires the FINISHED hold and clears back to IDLE,
     * and drops long-settled push notifications so they don't linger in the map.
     * Safe to call on the render thread only.
     */
    public static void tick() {
        long now = System.currentTimeMillis();
        if (phase == Phase.FINISHED && finishedAt > 0 && now - finishedAt > HOLD_AFTER_DONE_MS) {
            assets.clear();
            phase = Phase.IDLE;
            finishedAt = 0L;
            return;
        }
        assets.entrySet().removeIf(e -> e.getValue().dir == Dir.PUSH
            && e.getValue().state == State.DONE && now - e.getValue().updatedAt > 3000L);
    }

    /* ---- internals ----------------------------------------------------- */

    private static void wakeFromScan() {
        if (phase == Phase.SCANNING || phase == Phase.IDLE) {
            phase = Phase.SYNCING;
            finishedAt = 0L;
        }
    }

    private static synchronized void maybeFinish() {
        if (phase != Phase.SYNCING) return;
        for (AssetState s : assets.values()) {
            if (s.dir == Dir.PUSH) continue;
            if (s.state == State.PENDING || s.state == State.ACTIVE) return;
        }
        phase = Phase.FINISHED;
        finishedAt = System.currentTimeMillis();
    }
}
