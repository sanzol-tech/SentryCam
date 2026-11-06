package ar.sanzol.sentrycam;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Pattern;

/**
 * Video file names. One recording session has one prefix (session start in UTC plus 4 random
 * hex digits) and numbered parts: {@code SentryCam_20261102_213000_a3f1_part001.mp4}. Pure Java.
 */
public final class VideoNames {

    public static final String EXTENSION = ".mp4";
    public static final Pattern PATTERN =
            Pattern.compile("SentryCam_\\d{8}_\\d{6}_[0-9a-f]{4}_part\\d{3,}\\.mp4");

    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneOffset.UTC);

    private VideoNames() {
    }

    /** The prefix shared by every part of a session. */
    public static String sessionPrefix(long epochMillis, Random random) {
        return PhotoNames.PREFIX + FORMAT.format(Instant.ofEpochMilli(epochMillis))
                + String.format(Locale.ROOT, "_%04x", random.nextInt(0x10000));
    }

    /** Part numbers start at 1. */
    public static String partName(String prefix, int part) {
        return String.format(Locale.ROOT, "%s_part%03d%s", prefix, part, EXTENSION);
    }
}
