package mchorse.bbs_together.utils;

/**
 * A byte rate limiter (leaky bucket), shared by the asset upload and download paths.
 *
 * <p>Slicing a transfer into 512 KB batches with a fixed sleep between batches is not a rate
 * limit: it is a rate limit per sender, so N concurrent senders mean N times the intended
 * rate, and a batch that lands right after a sleep burst still hits the link at line speed.
 * A bucket instead meters bytes: callers reserve what they are about to send and are told how
 * long to wait, and everyone drawing from the same bucket shares one ceiling.</p>
 */
public final class TokenBucket {
    /** How much of the rate may be spent instantly, so small transfers do not crawl. */
    private static final long BURST_MS = 250L;

    private long rate;
    private double tokens;
    private long lastNanos = System.nanoTime();

    public TokenBucket(long bytesPerSec) {
        this.rate = Math.max(0L, bytesPerSec);
        this.tokens = capacity();
    }

    public synchronized void setRate(long bytesPerSec) {
        long wanted = Math.max(0L, bytesPerSec);
        if (wanted == this.rate) {
            return;
        }
        this.rate = wanted;
        this.tokens = capacity();
        this.lastNanos = System.nanoTime();
    }

    public synchronized long rate() {
        return this.rate;
    }

    private double capacity() {
        return this.rate <= 0L ? 0.0 : (double)this.rate * BURST_MS / 1000.0;
    }

    private void refill() {
        long now = System.nanoTime();
        long elapsed = now - this.lastNanos;
        if (elapsed <= 0L) {
            return;
        }
        this.lastNanos = now;
        this.tokens += (double)this.rate * ((double)elapsed / 1_000_000_000.0);
        double cap = capacity();
        if (this.tokens > cap) {
            this.tokens = cap;
        }
    }

    /**
     * Reserves {@code bytes}.
     *
     * @return nanoseconds the caller should sleep before sending, 0 when it may send now.
     */
    public synchronized long reserve(int bytes) {
        if (bytes <= 0) {
            return 0L;
        }
        if (this.rate <= 0L) {
            return 0L;
        }
        refill();
        this.tokens -= bytes;
        if (this.tokens >= 0.0) {
            return 0L;
        }
        return (long)(-this.tokens * 1_000_000_000.0 / (double)this.rate);
    }

    /** @return bytes available right now; may be negative while a transfer is running. */
    public synchronized long available() {
        refill();
        return (long)this.tokens;
    }

    public synchronized void reset() {
        this.tokens = capacity();
        this.lastNanos = System.nanoTime();
    }
}
