package ar.sanzol.sentrycam;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable Video mode configuration, independent of the photo {@link Settings}. Pure Java.
 * Shared items (camera, back lens, free-space reserve, theme) stay in {@link Settings} and
 * {@link SettingsStore}.
 *
 * <p>Zero means "no delay" for {@link #delaySeconds}, "unlimited" for
 * {@link #maxDurationSeconds} and "largest supported" for the size.
 */
public final class VideoSettings {

    /** Encoding quality in human terms; the bitrate is derived from it ({@link VideoPlan}). */
    public enum Quality { HIGH, MEDIUM, LOW }

    public enum Problem {
        DELAY_OUT_OF_RANGE,
        DURATION_OUT_OF_RANGE,
        SEGMENT_INVALID,
        RESOLUTION_INVALID
    }

    public static final long MIN_DELAY_SECONDS = 1;
    public static final long MAX_DELAY_SECONDS = 24 * Settings.HOUR;
    /** File lengths offered, in minutes. */
    public static final int[] SEGMENT_MINUTES = {5, 10, 15};

    public static final int DEFAULT_SEGMENT_MINUTES = 15;

    public final long delaySeconds;
    public final long maxDurationSeconds;
    public final int width;
    public final int height;
    public final Quality quality;
    public final int segmentMinutes;
    public final boolean audio;

    public VideoSettings(long delaySeconds, long maxDurationSeconds, int width, int height, Quality quality,
            int segmentMinutes, boolean audio) {
        this.delaySeconds = delaySeconds;
        this.maxDurationSeconds = maxDurationSeconds;
        this.width = width;
        this.height = height;
        this.quality = quality == null ? Quality.MEDIUM : quality;
        this.segmentMinutes = segmentMinutes;
        this.audio = audio;
    }

    /** No delay, unlimited, largest size, Medium, 15-minute files, no audio. */
    public static VideoSettings defaults() {
        return new VideoSettings(0, 0, 0, 0, Quality.MEDIUM, DEFAULT_SEGMENT_MINUTES, false);
    }

    public boolean hasDelay() {
        return delaySeconds > 0;
    }

    public boolean hasDurationLimit() {
        return maxDurationSeconds > 0;
    }

    public boolean usesLargestResolution() {
        return width == 0 && height == 0;
    }

    public long segmentMs() {
        return segmentMinutes * Settings.MINUTE * 1000L;
    }

    public List<Problem> validate() {
        List<Problem> problems = new ArrayList<>();
        if (delaySeconds != 0 && (delaySeconds < MIN_DELAY_SECONDS || delaySeconds > MAX_DELAY_SECONDS)) {
            problems.add(Problem.DELAY_OUT_OF_RANGE);
        }
        if (maxDurationSeconds != 0 && (maxDurationSeconds < Settings.MIN_DURATION_SECONDS
                || maxDurationSeconds > Settings.MAX_DURATION_SECONDS)) {
            problems.add(Problem.DURATION_OUT_OF_RANGE);
        }
        boolean segmentOk = false;
        for (int m : SEGMENT_MINUTES) {
            segmentOk |= m == segmentMinutes;
        }
        if (!segmentOk) {
            problems.add(Problem.SEGMENT_INVALID);
        }
        if (!usesLargestResolution() && (width <= 0 || height <= 0)) {
            problems.add(Problem.RESOLUTION_INVALID);
        }
        return Collections.unmodifiableList(problems);
    }

    public boolean isValid() {
        return validate().isEmpty();
    }
}
