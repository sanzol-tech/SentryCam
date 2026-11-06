package ar.sanzol.sentrycam;

import java.util.List;

/**
 * Size rules for Camera Preview, the live view. Pure Java so they are unit-testable.
 *
 * <p>The preview uses the photo's aspect ratio, so the framing on screen is the photo's framing,
 * and stays small enough to be cheap to stream.
 */
final class PreviewGeometry {

    /** Largest preview edge streamed to the screen: full HD covers a phone screen; more only costs power. */
    static final int MAX_LONG_EDGE = 1920;
    static final int MAX_SHORT_EDGE = 1080;
    /** Two aspect ratios closer than this (relative) count as equal. */
    static final double ASPECT_TOLERANCE = 0.01;

    private PreviewGeometry() {
    }

    /**
     * Picks the preview size for a photo of {@code photo} size: the largest size within the
     * limits with the same aspect ratio; else the smallest same-aspect size above the limits
     * (framing matters more than streaming cost); else, within the limits, the closest aspect
     * ratio (larger wins a tie); else the smallest size offered. Null when nothing is offered.
     * When the result does not have the photo's aspect ({@link #sameAspect}), the preview's framing is
     * only approximate and the screen says so.
     */
    static Resolution choosePreviewSize(List<Resolution> offered, Resolution photo) {
        if (offered == null || offered.isEmpty()) {
            return null;
        }
        double target = aspect(photo);
        Resolution sameAspect = null;
        Resolution sameAspectAbove = null;
        Resolution closest = null;
        double closestDiff = Double.MAX_VALUE;
        Resolution smallest = null;
        for (Resolution r : offered) {
            if (r.width <= 0 || r.height <= 0) {
                continue;
            }
            if (smallest == null || r.pixels() < smallest.pixels()) {
                smallest = r;
            }
            double diff = Math.abs(aspect(r) - target) / target;
            if (!withinLimits(r)) {
                if (diff <= ASPECT_TOLERANCE && (sameAspectAbove == null || r.pixels() < sameAspectAbove.pixels())) {
                    sameAspectAbove = r;
                }
                continue;
            }
            if (diff <= ASPECT_TOLERANCE && (sameAspect == null || r.pixels() > sameAspect.pixels())) {
                sameAspect = r;
            }
            if (diff < closestDiff - 1e-9 || (Math.abs(diff - closestDiff) <= 1e-9
                    && closest != null && r.pixels() > closest.pixels())) {
                closest = r;
                closestDiff = diff;
            }
        }
        if (sameAspect != null) {
            return sameAspect;
        }
        if (sameAspectAbove != null) {
            return sameAspectAbove;
        }
        return closest != null ? closest : smallest;
    }

    /** True when the two sizes have the same aspect ratio, ignoring orientation. */
    static boolean sameAspect(Resolution a, Resolution b) {
        if (a == null || b == null || a.width <= 0 || a.height <= 0 || b.width <= 0 || b.height <= 0) {
            return false;
        }
        double target = aspect(b);
        return Math.abs(aspect(a) - target) / target <= ASPECT_TOLERANCE;
    }

    /**
     * True when the sensor image appears rotated a quarter turn on this display, so the
     * on-screen width is the buffer's height. Both angles in degrees (0, 90, 180, 270).
     */
    static boolean swapsDimensions(int sensorOrientation, int displayRotationDegrees) {
        return (sensorOrientation + displayRotationDegrees) % 180 != 0;
    }

    /**
     * The largest {width, height} with the aspect ratio of {@code contentW x contentH} that fits
     * in {@code boxW x boxH} (letterboxed, never cropped).
     */
    static int[] fit(int contentW, int contentH, int boxW, int boxH) {
        if (contentW <= 0 || contentH <= 0 || boxW <= 0 || boxH <= 0) {
            return new int[] {Math.max(0, boxW), Math.max(0, boxH)};
        }
        long byWidth = (long) boxW * contentH / contentW;
        if (byWidth <= boxH) {
            return new int[] {boxW, (int) byWidth};
        }
        return new int[] {(int) ((long) boxH * contentW / contentH), boxH};
    }

    private static boolean withinLimits(Resolution r) {
        int longEdge = Math.max(r.width, r.height);
        int shortEdge = Math.min(r.width, r.height);
        return longEdge <= MAX_LONG_EDGE && shortEdge <= MAX_SHORT_EDGE;
    }

    private static double aspect(Resolution r) {
        return (double) Math.max(r.width, r.height) / Math.min(r.width, r.height);
    }
}
