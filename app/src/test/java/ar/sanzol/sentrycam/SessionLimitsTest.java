package ar.sanzol.sentrycam;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SessionLimitsTest {

    @Test
    public void unlimited() {
        SessionLimits l = new SessionLimits(0, 0, 1000);
        assertFalse(l.shotsReached(Long.MAX_VALUE));
        assertFalse(l.durationReached(Long.MAX_VALUE - 1));
        assertEquals(-1, l.remainingMs(5000));
        assertEquals(-1, l.remainingShots(10));
        assertFalse(l.hasDurationLimit());
    }

    @Test
    public void shotLimitIsExact() {
        SessionLimits l = new SessionLimits(3, 0, 0);
        assertFalse(l.shotsReached(2));
        assertTrue(l.shotsReached(3));
        assertEquals(1, l.remainingShots(2));
        assertEquals(0, l.remainingShots(5));
    }

    @Test
    public void durationLimitIsExact() {
        SessionLimits l = new SessionLimits(0, 60_000, 1_000);
        assertFalse(l.durationReached(60_999));
        assertTrue(l.durationReached(61_000));
        assertEquals(30_000, l.remainingMs(31_000));
        assertEquals(0, l.remainingMs(100_000));
        assertEquals(61_000, l.endTimeMs());
    }

    @Test
    public void durationOverflowMeansUnlimited() {
        SessionLimits l = new SessionLimits(0, Long.MAX_VALUE, 10);
        assertFalse(l.hasDurationLimit());
    }
}
