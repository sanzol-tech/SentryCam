package ar.sanzol.sentrycam;

/** Session end conditions derived from {@link Settings}. Pure Java. */
public final class SessionLimits {

    private final int maxShots;
    private final long endTimeMs;

    /**
     * @param maxShots      0 for unlimited
     * @param maxDurationMs 0 for unlimited
     * @param startMs       session start on the same clock that will be passed to the checks
     */
    public SessionLimits(int maxShots, long maxDurationMs, long startMs) {
        this.maxShots = Math.max(0, maxShots);
        if (maxDurationMs <= 0 || startMs > Long.MAX_VALUE - maxDurationMs) {
            this.endTimeMs = Long.MAX_VALUE;
        } else {
            this.endTimeMs = startMs + maxDurationMs;
        }
    }

    public boolean shotsReached(long successfulShots) {
        return maxShots > 0 && successfulShots >= maxShots;
    }

    public boolean durationReached(long nowMs) {
        return endTimeMs != Long.MAX_VALUE && nowMs >= endTimeMs;
    }

    public boolean hasDurationLimit() {
        return endTimeMs != Long.MAX_VALUE;
    }

    /** Milliseconds left, or -1 when there is no duration limit. */
    public long remainingMs(long nowMs) {
        if (!hasDurationLimit()) {
            return -1;
        }
        return Math.max(0, endTimeMs - nowMs);
    }

    /** Shots left, or -1 when there is no shot limit. */
    public long remainingShots(long successfulShots) {
        if (maxShots == 0) {
            return -1;
        }
        return Math.max(0, maxShots - successfulShots);
    }

    public long endTimeMs() {
        return endTimeMs;
    }
}
