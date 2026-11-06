package ar.sanzol.sentrycam;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** An output size (photo, preview or video). Pure Java so the selection rules are unit-testable. */
public final class Resolution {

    public final int width;
    public final int height;

    public Resolution(int width, int height) {
        this.width = width;
        this.height = height;
    }

    public long pixels() {
        return (long) width * height;
    }

    /** "8000 × 6000 · 48 MP · 4:3": size, megapixels and aspect ratio, all derived from the size. */
    public String label() {
        return String.format(Locale.ROOT, "%d × %d · %s MP · %s", width, height, megapixels(), aspectLabel());
    }

    /** "4032 × 3024 · 4:3": size and aspect ratio, for the main screen (one line). */
    public String shortLabel() {
        return String.format(Locale.ROOT, "%d × %d · %s", width, height, aspectLabel());
    }

    /** Megapixels with one decimal, without a trailing ".0" ("48", "12.2", "0.3"). */
    public String megapixels() {
        double mp = Math.round(pixels() / 100_000.0) / 10.0;
        return mp == Math.rint(mp) ? Long.toString((long) mp) : String.format(Locale.ROOT, "%.1f", mp);
    }

    /** Common aspect ratios, named in their landscape form. */
    private static final String[] RATIO_NAMES = {"1:1", "5:4", "4:3", "3:2", "16:10", "5:3", "16:9", "2:1", "20:9", "21:9"};
    private static final double[] RATIO_VALUES = {1.0, 5 / 4.0, 4 / 3.0, 3 / 2.0, 16 / 10.0, 5 / 3.0, 16 / 9.0, 2.0,
            20 / 9.0, 21 / 9.0};
    /** A size within this relative distance of a common ratio is shown as "≈" that ratio. */
    static final double RATIO_TOLERANCE = 0.01;

    /**
     * The aspect ratio as the user knows it: a common ratio when exact ("4:3"), "≈16:9" when
     * within 1 % of one (1920 × 1088), otherwise the reduced fraction when short ("7:5"), else a
     * decimal ("1.85:1"). Portrait sizes keep their orientation ("3:4").
     */
    public String aspectLabel() {
        if (width <= 0 || height <= 0) {
            return "?";
        }
        boolean portrait = height > width;
        int longSide = Math.max(width, height);
        int shortSide = Math.min(width, height);
        double ratio = (double) longSide / shortSide;
        int bestIndex = -1;
        double bestDiff = Double.MAX_VALUE;
        for (int i = 0; i < RATIO_VALUES.length; i++) {
            double diff = Math.abs(ratio - RATIO_VALUES[i]) / RATIO_VALUES[i];
            if (diff < bestDiff) {
                bestDiff = diff;
                bestIndex = i;
            }
        }
        if (bestDiff < 1e-9) {
            return orient(RATIO_NAMES[bestIndex], portrait);
        }
        int g = gcd(longSide, shortSide);
        int a = longSide / g;
        int b = shortSide / g;
        if (bestDiff <= RATIO_TOLERANCE) {
            return "≈" + orient(RATIO_NAMES[bestIndex], portrait);
        }
        if (a <= 32 && b <= 32) {
            return orient(a + ":" + b, portrait);
        }
        String decimal = String.format(Locale.ROOT, "%.2f", ratio);
        return portrait ? "1:" + decimal : decimal + ":1";
    }

    private static String orient(String landscape, boolean portrait) {
        if (!portrait) {
            return landscape;
        }
        int colon = landscape.indexOf(':');
        return landscape.substring(colon + 1) + ":" + landscape.substring(0, colon);
    }

    private static int gcd(int a, int b) {
        while (b != 0) {
            int t = a % b;
            a = b;
            b = t;
        }
        return a;
    }

    /** Removes duplicates and sorts largest first (ties: wider first). */
    public static List<Resolution> sortLargestFirst(Collection<Resolution> sizes) {
        List<Resolution> unique = new ArrayList<>(new LinkedHashSet<>(sizes));
        unique.removeIf(r -> r.width <= 0 || r.height <= 0);
        unique.sort(Comparator.comparingLong(Resolution::pixels).reversed()
                .thenComparing(Comparator.comparingInt((Resolution r) -> r.width).reversed()));
        return unique;
    }

    /**
     * Picks the size to use. Returns the requested size when it is supported, otherwise the
     * largest supported size. Returns null only when {@code supported} is empty.
     */
    public static Resolution choose(List<Resolution> supported, int requestedWidth, int requestedHeight) {
        List<Resolution> sorted = sortLargestFirst(supported);
        if (sorted.isEmpty()) {
            return null;
        }
        for (Resolution r : sorted) {
            if (r.width == requestedWidth && r.height == requestedHeight) {
                return r;
            }
        }
        return sorted.get(0);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof Resolution other && width == other.width && height == other.height;
    }

    @Override
    public int hashCode() {
        return 31 * width + height;
    }

    @Override
    public String toString() {
        return width + "x" + height;
    }
}
