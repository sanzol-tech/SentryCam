package ar.sanzol.sentrycam;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Random;
import java.util.regex.Pattern;

/** Photo file names: {@code SentryCam_yyyyMMdd_HHmmss_SSS_xxxx.jpg} in UTC. Pure Java. */
public final class PhotoNames {

    public static final String PREFIX = "SentryCam_";
    public static final String EXTENSION = ".jpg";
    public static final Pattern PATTERN =
            Pattern.compile("SentryCam_\\d{8}_\\d{6}_\\d{3}_[0-9a-f]{4}\\.jpg");

    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS").withZone(ZoneOffset.UTC);

    private PhotoNames() {
    }

    public static String newName(long epochMillis, Random random) {
        String stamp = FORMAT.format(Instant.ofEpochMilli(epochMillis));
        String suffix = String.format("%04x", random.nextInt(0x10000));
        return PREFIX + stamp + "_" + suffix + EXTENSION;
    }
}
