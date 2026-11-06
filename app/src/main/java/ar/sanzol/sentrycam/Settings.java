package ar.sanzol.sentrycam;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable capture configuration. Pure Java: no Android dependencies, so it is unit-testable.
 *
 * <p>Zero means "unlimited" for {@link #maxShots} and {@link #maxDurationSeconds}, and
 * "largest supported" for the resolution.
 */
public final class Settings {

    public enum Lens { BACK, FRONT }

    /** Operating mode: photos at an interval, or continuous video. Never both at once. */
    public enum Mode { PHOTO, VIDEO }

    public enum Problem {
        INTERVAL_OUT_OF_RANGE,
        MAX_SHOTS_OUT_OF_RANGE,
        DURATION_OUT_OF_RANGE,
        QUALITY_OUT_OF_RANGE,
        MIN_FREE_OUT_OF_RANGE,
        RESOLUTION_INVALID
    }

    public static final long SECOND = 1;
    public static final long MINUTE = 60;
    public static final long HOUR = 3600;
    public static final long DAY = 86400;
    public static final long MB = 1024L * 1024L;

    public static final long MIN_INTERVAL_SECONDS = 1;
    public static final long MAX_INTERVAL_SECONDS = 24 * HOUR;
    public static final int MIN_SHOTS = 1;
    public static final int MAX_SHOTS = 1_000_000;
    public static final long MIN_DURATION_SECONDS = MINUTE;
    public static final long MAX_DURATION_SECONDS = 30 * DAY;
    public static final int MIN_QUALITY = 50;
    public static final int MAX_QUALITY = 100;
    public static final long MIN_FREE_BYTES_LOWER = 100 * MB;
    public static final long MIN_FREE_BYTES_UPPER = 10L * 1024L * MB;

    public static final long DEFAULT_INTERVAL_SECONDS = 60;
    public static final int DEFAULT_QUALITY = 90;
    public static final long DEFAULT_MIN_FREE_BYTES = 1024 * MB;

    public final long intervalSeconds;
    public final int maxShots;
    public final long maxDurationSeconds;
    public final Lens lens;
    public final int width;
    public final int height;
    public final int jpegQuality;
    public final long minFreeBytes;

    public Settings(long intervalSeconds, int maxShots, long maxDurationSeconds, Lens lens,
                    int width, int height, int jpegQuality, long minFreeBytes) {
        this.intervalSeconds = intervalSeconds;
        this.maxShots = maxShots;
        this.maxDurationSeconds = maxDurationSeconds;
        this.lens = lens == null ? Lens.BACK : lens;
        this.width = width;
        this.height = height;
        this.jpegQuality = jpegQuality;
        this.minFreeBytes = minFreeBytes;
    }

    public static Settings defaults() {
        return new Settings(DEFAULT_INTERVAL_SECONDS, 0, 0, Lens.BACK, 0, 0,
                DEFAULT_QUALITY, DEFAULT_MIN_FREE_BYTES);
    }

    public boolean usesLargestResolution() {
        return width == 0 && height == 0;
    }

    public boolean hasShotLimit() {
        return maxShots > 0;
    }

    public boolean hasDurationLimit() {
        return maxDurationSeconds > 0;
    }

    /** Returns every problem found; an empty list means the settings are valid. */
    public List<Problem> validate() {
        List<Problem> problems = new ArrayList<>();
        if (intervalSeconds < MIN_INTERVAL_SECONDS || intervalSeconds > MAX_INTERVAL_SECONDS) {
            problems.add(Problem.INTERVAL_OUT_OF_RANGE);
        }
        if (maxShots != 0 && (maxShots < MIN_SHOTS || maxShots > MAX_SHOTS)) {
            problems.add(Problem.MAX_SHOTS_OUT_OF_RANGE);
        }
        if (maxDurationSeconds != 0
                && (maxDurationSeconds < MIN_DURATION_SECONDS || maxDurationSeconds > MAX_DURATION_SECONDS)) {
            problems.add(Problem.DURATION_OUT_OF_RANGE);
        }
        if (jpegQuality < MIN_QUALITY || jpegQuality > MAX_QUALITY) {
            problems.add(Problem.QUALITY_OUT_OF_RANGE);
        }
        if (minFreeBytes < MIN_FREE_BYTES_LOWER || minFreeBytes > MIN_FREE_BYTES_UPPER) {
            problems.add(Problem.MIN_FREE_OUT_OF_RANGE);
        }
        boolean largest = width == 0 && height == 0;
        if (!largest && (width <= 0 || height <= 0)) {
            problems.add(Problem.RESOLUTION_INVALID);
        }
        return Collections.unmodifiableList(problems);
    }

    public boolean isValid() {
        return validate().isEmpty();
    }

    /**
     * Parses a user-entered whole number. Returns -1 for blank input and -2 for anything
     * that is not a non-negative integer that fits in a long.
     */
    public static long parseWholeNumber(String text) {
        if (text == null) {
            return -1;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c < '0' || c > '9') {
                return -2;
            }
        }
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException e) {
            return -2;
        }
    }

    /** Multiplies without overflow; saturates at Long.MAX_VALUE. */
    public static long times(long value, long unitSeconds) {
        if (value <= 0) {
            return value;
        }
        if (value > Long.MAX_VALUE / unitSeconds) {
            return Long.MAX_VALUE;
        }
        return value * unitSeconds;
    }
}
