package ar.sanzol.sentrycam;

/**
 * Video arithmetic, pure Java: bitrate for a quality, storage estimate and segment lengths.
 * Every figure here is an approximation; real files depend on the scene and the encoder.
 */
public final class VideoPlan {

    /** Nominal frame rate requested from the camera and the encoder. */
    public static final int FRAME_RATE = 30;
    /** Mono AAC; enough for speech and ambient sound. */
    public static final int AUDIO_BITRATE = 96_000;
    public static final int AUDIO_SAMPLE_RATE = 48_000;
    /**
     * Each file is closed before it reaches this size; some phones cannot write MP4 files above
     * 4 GiB. Reaching it starts the next file early.
     */
    public static final long MAX_FILE_BYTES = 3_800_000_000L;

    // Bits per pixel per frame for H.264: roughly what phone camera apps use for High, and half
    // and a quarter of it.
    private static final double BPP_HIGH = 0.20;
    private static final double BPP_MEDIUM = 0.10;
    private static final double BPP_LOW = 0.05;
    private static final int MIN_BITRATE = 500_000;

    private VideoPlan() {
    }

    /**
     * Video bitrate in bits per second for {@code quality}, clamped to the encoder's range
     * ({@code encoderMin}..{@code encoderMax}; 0 when unknown).
     */
    public static int bitrate(int width, int height, int fps, VideoSettings.Quality quality, int encoderMin,
            int encoderMax) {
        double bpp = switch (quality) {
            case HIGH -> BPP_HIGH;
            case MEDIUM -> BPP_MEDIUM;
            case LOW -> BPP_LOW;
        };
        long bps = Math.round((double) width * height * fps * bpp);
        bps = Math.max(bps, MIN_BITRATE);
        if (encoderMax > 0) {
            bps = Math.min(bps, encoderMax);
        }
        if (encoderMin > 0) {
            bps = Math.max(bps, encoderMin);
        }
        return (int) Math.min(bps, Integer.MAX_VALUE);
    }

    /** Approximate bytes per hour of recording. */
    public static long bytesPerHour(int videoBitrate, boolean audio) {
        long bits = (long) videoBitrate + (audio ? AUDIO_BITRATE : 0);
        return bits / 8 * Settings.HOUR;
    }

    /** Hours of recording until free space falls to the reserve; -1 when unknown. */
    public static double hoursUntilReserve(long freeBytes, long reserveBytes, long bytesPerHour) {
        if (freeBytes < 0 || bytesPerHour <= 0) {
            return -1;
        }
        long usable = freeBytes - reserveBytes;
        return usable <= 0 ? 0 : (double) usable / bytesPerHour;
    }

    /**
     * Length of the next file: the configured length, or what is left of the duration limit
     * when that is shorter. {@code remainingMs} below 0 means no limit.
     */
    public static long nextSegmentMs(long segmentMs, long remainingMs) {
        return remainingMs < 0 ? segmentMs : Math.max(0, Math.min(segmentMs, remainingMs));
    }
}
