package mchorse.bbs_together.network;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * P5: low-overhead transfer counters plus structured egress/ingress log lines.
 *
 * <p>Normal transfers are logged at DEBUG; slow/large transfers and failures are logged at
 * INFO/WARN, and a compact summary is emitted at most once per minute. This keeps a large
 * initial asset sync from flooding the log while still making bandwidth problems visible.</p>
 */
public final class TransferLog {
    private static final Logger LOGGER = LoggerFactory.getLogger("bbs_together/transfer");

    private static final long SLOW_TRANSFER_MS = 2_000L;
    private static final long LARGE_TRANSFER_BYTES = 8L * 1024L * 1024L;
    private static final long SLOW_BUDGET_WAIT_MS = 500L;
    private static final long SUMMARY_INTERVAL_MS = 60_000L;

    private static final LongAdder EGRESS_COMPLETED = new LongAdder();
    private static final LongAdder EGRESS_FAILED = new LongAdder();
    private static final LongAdder EGRESS_BYTES = new LongAdder();
    private static final LongAdder EGRESS_BUDGET_WAIT_NS = new LongAdder();
    private static final LongAdder INGRESS_COMPLETED = new LongAdder();
    private static final LongAdder INGRESS_BYTES = new LongAdder();
    private static final AtomicLong FIRST_START_NS = new AtomicLong();
    private static final AtomicLong LAST_SUMMARY_AT = new AtomicLong(0L);
    private static final AtomicLong LAST_SUMMARY_TOTAL = new AtomicLong();

    private TransferLog() {}

    /** Marks the start of one scheduled egress transfer and returns its start timestamp. */
    public static long begin(String direction, UUID player, String path, long bytes) {
        long now = System.nanoTime();
        FIRST_START_NS.compareAndSet(0L, now);
        LOGGER.debug("transfer-start direction={} player={} path={} bytes={}", direction, player, path, bytes);
        return now;
    }

    /** Marks a scheduled egress transfer as handed to the packet crusher. */
    public static void complete(String direction, UUID player, String path, long bytes, long startNs, long budgetWaitNs) {
        EGRESS_COMPLETED.increment();
        EGRESS_BYTES.add(bytes);
        EGRESS_BUDGET_WAIT_NS.add(Math.max(0L, budgetWaitNs));

        long elapsedMs = Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
        long waitMs = Math.max(0L, budgetWaitNs / 1_000_000L);

        if (elapsedMs >= SLOW_TRANSFER_MS || bytes >= LARGE_TRANSFER_BYTES || waitMs >= SLOW_BUDGET_WAIT_MS) {
            LOGGER.info("transfer-complete direction={} player={} path={} bytes={} elapsed_ms={} budget_wait_ms={}",
                direction, player, path, bytes, elapsedMs, waitMs);
        }
        else {
            LOGGER.debug("transfer-complete direction={} player={} path={} bytes={} elapsed_ms={} budget_wait_ms={}",
                direction, player, path, bytes, elapsedMs, waitMs);
        }
        maybeSummarize();
    }

    public static void failed(String direction, UUID player, String path, long bytes, long startNs, long budgetWaitNs, Throwable error) {
        EGRESS_FAILED.increment();
        long elapsedMs = Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
        long waitMs = Math.max(0L, budgetWaitNs / 1_000_000L);
        LOGGER.warn("transfer-failed direction={} player={} path={} bytes={} elapsed_ms={} budget_wait_ms={} error={}",
            direction, player, path, bytes, elapsedMs, waitMs, error == null ? "unknown" : error.getClass().getSimpleName() + ": " + error.getMessage());
        maybeSummarize();
    }

    /** Marks the first received slice of a client upload, so ingress-only runs have a start time. */
    public static void ingressBegin() {
        FIRST_START_NS.compareAndSet(0L, System.nanoTime());
    }

    /** Records a fully accepted client upload; the upload path is chunked, so only completion is timed. */
    public static void ingress(UUID player, String path, long bytes, boolean changed) {
        INGRESS_COMPLETED.increment();
        INGRESS_BYTES.add(bytes);
        if (changed) {
            LOGGER.info("transfer-ingress player={} path={} bytes={} result=accepted", player, path, bytes);
        }
        else {
            LOGGER.debug("transfer-ingress player={} path={} bytes={} result=unchanged", player, path, bytes);
        }
        maybeSummarize();
    }

    public static long egressCompleted() {
        return EGRESS_COMPLETED.sum();
    }

    public static long egressFailed() {
        return EGRESS_FAILED.sum();
    }

    public static long ingressCompleted() {
        return INGRESS_COMPLETED.sum();
    }

    public static long budgetWaitMillis() {
        return EGRESS_BUDGET_WAIT_NS.sum() / 1_000_000L;
    }

    /** Combined egress+ingress throughput since the first transfer in this run. */
    public static long averageKiBPerSec() {
        long first = FIRST_START_NS.get();
        if (first == 0L) {
            return 0L;
        }
        long elapsedNs = System.nanoTime() - first;
        if (elapsedNs <= 0L) {
            return 0L;
        }
        long bytes = EGRESS_BYTES.sum() + INGRESS_BYTES.sum();
        return bytes * 1_000_000_000L / elapsedNs / 1024L;
    }

    public static void reset() {
        EGRESS_COMPLETED.reset();
        EGRESS_FAILED.reset();
        EGRESS_BYTES.reset();
        EGRESS_BUDGET_WAIT_NS.reset();
        INGRESS_COMPLETED.reset();
        INGRESS_BYTES.reset();
        FIRST_START_NS.set(0L);
        LAST_SUMMARY_AT.set(System.currentTimeMillis());
        LAST_SUMMARY_TOTAL.set(0L);
    }

    private static void maybeSummarize() {
        long now = System.currentTimeMillis();
        long last = LAST_SUMMARY_AT.get();
        if (now - last < SUMMARY_INTERVAL_MS || !LAST_SUMMARY_AT.compareAndSet(last, now)) {
            return;
        }
        long total = EGRESS_COMPLETED.sum() + INGRESS_COMPLETED.sum();
        if (total == LAST_SUMMARY_TOTAL.getAndSet(total)) {
            return;
        }
        LOGGER.info("transfer-summary egress_completed={} egress_failed={} ingress_completed={} bytes={} avg_kib_s={} budget_wait_ms={}",
            EGRESS_COMPLETED.sum(), EGRESS_FAILED.sum(), INGRESS_COMPLETED.sum(),
            EGRESS_BYTES.sum() + INGRESS_BYTES.sum(), averageKiBPerSec(), budgetWaitMillis());
    }
}
