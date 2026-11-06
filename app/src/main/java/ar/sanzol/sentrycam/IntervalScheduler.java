package ar.sanzol.sentrycam;

/**
 * Start-to-start capture schedule. Tick {@code k} is due at {@code start + k * interval}.
 *
 * <p>When a tick fires while a capture is still in progress (or the camera is not ready),
 * the tick is skipped and counted. Ticks that were missed entirely because the thread woke
 * up late are also counted as skipped. The schedule never drifts: it always realigns to the
 * original start time. Pure Java, not thread-safe; use from a single thread.
 */
public final class IntervalScheduler {

    /** Result of handling one tick. */
    public static final class Decision {
        public final boolean capture;
        public final int skipped;
        public final boolean early;

        Decision(boolean capture, int skipped, boolean early) {
            this.capture = capture;
            this.skipped = skipped;
            this.early = early;
        }
    }

    private final long startMs;
    private final long intervalMs;
    private long nextIndex;

    public IntervalScheduler(long startMs, long intervalMs) {
        if (intervalMs <= 0) {
            throw new IllegalArgumentException("intervalMs must be positive");
        }
        this.startMs = startMs;
        this.intervalMs = intervalMs;
        this.nextIndex = 0;
    }

    /** Time at which the next tick is due. */
    public long nextTickTime() {
        return startMs + nextIndex * intervalMs;
    }

    /** Delay from {@code nowMs} until the next tick, never negative. */
    public long delayUntilNext(long nowMs) {
        return Math.max(0, nextTickTime() - nowMs);
    }

    /**
     * Handles a wake-up at {@code nowMs}.
     *
     * @param busy true if a capture is in progress or the camera cannot capture right now
     */
    public Decision onTick(long nowMs, boolean busy) {
        if (nowMs < startMs) {
            return new Decision(false, 0, true);
        }
        long dueIndex = (nowMs - startMs) / intervalMs;
        if (dueIndex < nextIndex) {
            // Woke up before the next tick (e.g. handler fired a little early).
            return new Decision(false, 0, true);
        }
        long missed = dueIndex - nextIndex;
        nextIndex = dueIndex + 1;
        int skipped = (int) Math.min(Integer.MAX_VALUE, missed + (busy ? 1 : 0));
        return new Decision(!busy, skipped, false);
    }
}
