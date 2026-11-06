package ar.sanzol.sentrycam;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Random;

import org.junit.Test;

public class VideoTest {

    @Test
    public void defaultsAreValidAndQuiet() {
        VideoSettings d = VideoSettings.defaults();
        assertTrue(d.isValid());
        assertFalse(d.audio);
        assertFalse(d.hasDelay());
        assertFalse(d.hasDurationLimit());
        assertTrue(d.usesLargestResolution());
        assertEquals(15 * 60_000L, d.segmentMs());
    }

    @Test
    public void validationRanges() {
        assertTrue(new VideoSettings(1, 60, 1920, 1080, VideoSettings.Quality.LOW, 5, true).isValid());
        assertTrue(new VideoSettings(86_400, 30 * 86_400, 0, 0, VideoSettings.Quality.HIGH, 10, false).isValid());
        assertEquals(VideoSettings.Problem.DELAY_OUT_OF_RANGE,
                new VideoSettings(86_401, 0, 0, 0, null, 15, false).validate().get(0));
        assertEquals(VideoSettings.Problem.DURATION_OUT_OF_RANGE,
                new VideoSettings(0, 59, 0, 0, null, 15, false).validate().get(0));
        assertEquals(VideoSettings.Problem.SEGMENT_INVALID,
                new VideoSettings(0, 0, 0, 0, null, 7, false).validate().get(0));
        assertEquals(VideoSettings.Problem.RESOLUTION_INVALID,
                new VideoSettings(0, 0, 1920, 0, null, 15, false).validate().get(0));
        assertEquals(VideoSettings.Quality.MEDIUM, new VideoSettings(0, 0, 0, 0, null, 15, false).quality);
    }

    @Test
    public void bitrateFollowsQualityAndEncoderRange() {
        int high = VideoPlan.bitrate(1920, 1080, 30, VideoSettings.Quality.HIGH, 0, 0);
        int medium = VideoPlan.bitrate(1920, 1080, 30, VideoSettings.Quality.MEDIUM, 0, 0);
        int low = VideoPlan.bitrate(1920, 1080, 30, VideoSettings.Quality.LOW, 0, 0);
        assertEquals(12_441_600, high);
        assertEquals(high / 2, medium);
        assertEquals(high / 4, low);
        assertEquals(10_000_000, VideoPlan.bitrate(1920, 1080, 30, VideoSettings.Quality.HIGH, 0, 10_000_000));
        assertEquals(500_000, VideoPlan.bitrate(320, 240, 30, VideoSettings.Quality.LOW, 0, 0));
        assertEquals(800_000, VideoPlan.bitrate(320, 240, 30, VideoSettings.Quality.LOW, 800_000, 0));
    }

    @Test
    public void storageEstimate() {
        // 8 Mbps video + 96 kbps audio: 1,012,000 bytes/s, 3.64 GB per hour.
        assertEquals(3_643_200_000L, VideoPlan.bytesPerHour(8_000_000, true));
        assertEquals(3_600_000_000L, VideoPlan.bytesPerHour(8_000_000, false));
        assertEquals(2.0, VideoPlan.hoursUntilReserve(8_000, 1_000, 3_500), 1e-9);
        assertEquals(0.0, VideoPlan.hoursUntilReserve(500, 1_000, 3_500), 1e-9);
        assertEquals(-1.0, VideoPlan.hoursUntilReserve(-1, 1_000, 3_500), 1e-9);
    }

    @Test
    public void segmentLengthRespectsLimit() {
        assertEquals(600_000, VideoPlan.nextSegmentMs(600_000, -1));
        assertEquals(120_000, VideoPlan.nextSegmentMs(600_000, 120_000));
        assertEquals(600_000, VideoPlan.nextSegmentMs(600_000, 900_000));
        assertEquals(0, VideoPlan.nextSegmentMs(600_000, -0L));
    }

    @Test
    public void namesShareAPrefixAndMatchThePattern() {
        String prefix = VideoNames.sessionPrefix(0L, new Random(1));
        assertTrue(prefix.startsWith("SentryCam_19700101_000000_"));
        String part = VideoNames.partName(prefix, 1);
        assertTrue(part, part.endsWith("_part001.mp4"));
        assertTrue(VideoNames.PATTERN.matcher(part).matches());
        assertTrue(VideoNames.PATTERN.matcher(VideoNames.partName(prefix, 1234)).matches());
        assertFalse(VideoNames.PATTERN.matcher("SentryCam_20261101_040506_007_00af.jpg").matches());
        assertFalse(PhotoNames.PATTERN.matcher(part).matches());
    }
}
