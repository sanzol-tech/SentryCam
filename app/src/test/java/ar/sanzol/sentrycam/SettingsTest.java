package ar.sanzol.sentrycam;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public class SettingsTest {

    private static Settings with(long interval, int shots, long duration, int quality, long minFree) {
        return new Settings(interval, shots, duration, Settings.Lens.BACK, 0, 0, quality, minFree);
    }

    @Test
    public void defaultsAreValid() {
        Settings s = Settings.defaults();
        assertTrue(s.isValid());
        assertEquals(60, s.intervalSeconds);
        assertEquals(90, s.jpegQuality);
        assertEquals(1024 * Settings.MB, s.minFreeBytes);
        assertTrue(s.usesLargestResolution());
        assertFalse(s.hasShotLimit());
        assertFalse(s.hasDurationLimit());
    }

    @Test
    public void intervalBoundaries() {
        assertTrue(with(1, 0, 0, 90, 1024 * Settings.MB).isValid());
        assertTrue(with(86400, 0, 0, 90, 1024 * Settings.MB).isValid());
        assertEquals(List.of(Settings.Problem.INTERVAL_OUT_OF_RANGE),
                with(0, 0, 0, 90, 1024 * Settings.MB).validate());
        assertEquals(List.of(Settings.Problem.INTERVAL_OUT_OF_RANGE),
                with(86401, 0, 0, 90, 1024 * Settings.MB).validate());
    }

    @Test
    public void shotLimitBoundaries() {
        long free = 1024 * Settings.MB;
        assertTrue(with(60, 1, 0, 90, free).isValid());
        assertTrue(with(60, 1_000_000, 0, 90, free).isValid());
        assertTrue(with(60, 0, 0, 90, free).isValid()); // unlimited
        assertEquals(List.of(Settings.Problem.MAX_SHOTS_OUT_OF_RANGE),
                with(60, 1_000_001, 0, 90, free).validate());
        assertEquals(List.of(Settings.Problem.MAX_SHOTS_OUT_OF_RANGE),
                with(60, -5, 0, 90, free).validate());
    }

    @Test
    public void durationBoundaries() {
        long free = 1024 * Settings.MB;
        assertTrue(with(60, 0, 60, 90, free).isValid());
        assertTrue(with(60, 0, 30 * Settings.DAY, 90, free).isValid());
        assertEquals(List.of(Settings.Problem.DURATION_OUT_OF_RANGE),
                with(60, 0, 59, 90, free).validate());
        assertEquals(List.of(Settings.Problem.DURATION_OUT_OF_RANGE),
                with(60, 0, 30 * Settings.DAY + 1, 90, free).validate());
    }

    @Test
    public void qualityAndFreeSpaceBoundaries() {
        assertTrue(with(60, 0, 0, 50, 100 * Settings.MB).isValid());
        assertTrue(with(60, 0, 0, 100, 10240 * Settings.MB).isValid());
        assertEquals(List.of(Settings.Problem.QUALITY_OUT_OF_RANGE),
                with(60, 0, 0, 49, 1024 * Settings.MB).validate());
        assertEquals(List.of(Settings.Problem.QUALITY_OUT_OF_RANGE),
                with(60, 0, 0, 101, 1024 * Settings.MB).validate());
        assertEquals(List.of(Settings.Problem.MIN_FREE_OUT_OF_RANGE),
                with(60, 0, 0, 90, 99 * Settings.MB).validate());
        assertEquals(List.of(Settings.Problem.MIN_FREE_OUT_OF_RANGE),
                with(60, 0, 0, 90, 10241 * Settings.MB).validate());
    }

    @Test
    public void reportsEveryProblem() {
        Settings s = new Settings(0, -1, 5, null, 100, 0, 10, 0);
        List<Settings.Problem> p = s.validate();
        assertEquals(6, p.size());
        assertEquals(Settings.Lens.BACK, s.lens);
    }

    @Test
    public void explicitResolutionIsValid() {
        Settings s = new Settings(60, 0, 0, Settings.Lens.FRONT, 4000, 3000, 90, 1024 * Settings.MB);
        assertTrue(s.isValid());
        assertFalse(s.usesLargestResolution());
    }

    @Test
    public void parseWholeNumber() {
        assertEquals(-1, Settings.parseWholeNumber(null));
        assertEquals(-1, Settings.parseWholeNumber("   "));
        assertEquals(42, Settings.parseWholeNumber(" 42 "));
        assertEquals(0, Settings.parseWholeNumber("0"));
        assertEquals(-2, Settings.parseWholeNumber("-3"));
        assertEquals(-2, Settings.parseWholeNumber("1.5"));
        assertEquals(-2, Settings.parseWholeNumber("12a"));
        assertEquals(-2, Settings.parseWholeNumber("99999999999999999999999"));
    }

    @Test
    public void timesSaturates() {
        assertEquals(120, Settings.times(2, Settings.MINUTE));
        assertEquals(Long.MAX_VALUE, Settings.times(Long.MAX_VALUE / 2, Settings.DAY));
        assertEquals(-1, Settings.times(-1, Settings.HOUR));
    }
}
