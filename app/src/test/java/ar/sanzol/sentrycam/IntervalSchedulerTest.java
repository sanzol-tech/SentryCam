package ar.sanzol.sentrycam;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class IntervalSchedulerTest {

    @Test
    public void firstTickIsImmediate() {
        IntervalScheduler s = new IntervalScheduler(1000, 500);
        assertEquals(1000, s.nextTickTime());
        IntervalScheduler.Decision d = s.onTick(1000, false);
        assertTrue(d.capture);
        assertEquals(0, d.skipped);
        assertEquals(1500, s.nextTickTime());
    }

    @Test
    public void onTimeTicksCaptureWithoutDrift() {
        IntervalScheduler s = new IntervalScheduler(0, 1000);
        for (int i = 0; i < 100; i++) {
            long now = i * 1000L + 7; // always a little late
            IntervalScheduler.Decision d = s.onTick(now, false);
            assertTrue(d.capture);
            assertEquals(0, d.skipped);
            assertEquals((i + 1) * 1000L, s.nextTickTime());
        }
    }

    @Test
    public void busyTickIsSkippedAndCounted() {
        IntervalScheduler s = new IntervalScheduler(0, 1000);
        s.onTick(0, false);
        IntervalScheduler.Decision d = s.onTick(1000, true);
        assertFalse(d.capture);
        assertEquals(1, d.skipped);
        assertEquals(2000, s.nextTickTime());
    }

    @Test
    public void lateWakeUpCountsMissedTicks() {
        IntervalScheduler s = new IntervalScheduler(0, 1000);
        s.onTick(0, false);
        // Woke at 4.2 s: ticks 1, 2 and 3 were missed, tick 4 is captured.
        IntervalScheduler.Decision d = s.onTick(4200, false);
        assertTrue(d.capture);
        assertEquals(3, d.skipped);
        assertEquals(5000, s.nextTickTime());
    }

    @Test
    public void lateAndBusyCountsAll() {
        IntervalScheduler s = new IntervalScheduler(0, 1000);
        s.onTick(0, false);
        IntervalScheduler.Decision d = s.onTick(3100, true);
        assertFalse(d.capture);
        assertEquals(3, d.skipped);
    }

    @Test
    public void earlyWakeUpDoesNothing() {
        IntervalScheduler s = new IntervalScheduler(0, 1000);
        s.onTick(0, false);
        IntervalScheduler.Decision d = s.onTick(999, false);
        assertTrue(d.early);
        assertFalse(d.capture);
        assertEquals(0, d.skipped);
        assertEquals(1000, s.nextTickTime());
        assertEquals(1, s.delayUntilNext(999));
        assertEquals(0, s.delayUntilNext(5000));
    }

    @Test
    public void beforeStartIsEarly() {
        IntervalScheduler s = new IntervalScheduler(10_000, 1000);
        assertTrue(s.onTick(5_000, false).early);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsZeroInterval() {
        new IntervalScheduler(0, 0);
    }
}
