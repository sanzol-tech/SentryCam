package ar.sanzol.sentrycam;

/**
 * Rough storage estimates shown in the settings screen. Real JPEG sizes depend on the scene,
 * so the UI also reports the actual average size measured during a session. Pure Java.
 */
public final class StorageEstimate {

    // Approximate bytes per pixel for typical camera JPEGs at a given quality.
    private static final int[] QUALITY = {50, 70, 80, 90, 95, 100};
    private static final double[] BYTES_PER_PIXEL = {0.08, 0.12, 0.16, 0.25, 0.35, 0.70};

    private StorageEstimate() {
    }

    public static double bytesPerPixel(int quality) {
        int q = Math.max(QUALITY[0], Math.min(QUALITY[QUALITY.length - 1], quality));
        for (int i = 1; i < QUALITY.length; i++) {
            if (q <= QUALITY[i]) {
                double t = (double) (q - QUALITY[i - 1]) / (QUALITY[i] - QUALITY[i - 1]);
                return BYTES_PER_PIXEL[i - 1] + t * (BYTES_PER_PIXEL[i] - BYTES_PER_PIXEL[i - 1]);
            }
        }
        return BYTES_PER_PIXEL[BYTES_PER_PIXEL.length - 1];
    }

    public static long bytesPerPhoto(int width, int height, int quality) {
        if (width <= 0 || height <= 0) {
            return 0;
        }
        return Math.round((double) width * height * bytesPerPixel(quality));
    }

    /**
     * Photos taken in the first 24 hours of a session, respecting the shot and duration limits.
     * Ticks fire at 0, interval, 2 × interval, ... strictly before the window ends, so a window W
     * holds ceil(W / interval) ticks.
     */
    public static long photosPerDay(long intervalSeconds, int maxShots, long maxDurationSeconds) {
        if (intervalSeconds <= 0) {
            return 0;
        }
        long window = maxDurationSeconds > 0 ? Math.min(maxDurationSeconds, Settings.DAY) : Settings.DAY;
        long photos = (window + intervalSeconds - 1) / intervalSeconds;
        photos = Math.max(1, photos);
        if (maxShots > 0) {
            photos = Math.min(photos, maxShots);
        }
        return photos;
    }

    /** Days of capture until free space falls to the reserve, or -1 if unknown/unbounded. */
    public static double daysUntilReserve(long freeBytes, long reserveBytes, long bytesPerDay) {
        if (bytesPerDay <= 0) {
            return -1;
        }
        long usable = freeBytes - reserveBytes;
        if (usable <= 0) {
            return 0;
        }
        return (double) usable / bytesPerDay;
    }
}
