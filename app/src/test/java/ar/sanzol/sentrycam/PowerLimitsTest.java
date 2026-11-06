package ar.sanzol.sentrycam;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PowerLimitsTest {

    @Test
    public void stopsAtTheLimitOnlyWhenNotCharging() {
        int limit = PowerLimits.STOP_BATTERY_PERCENT;
        assertTrue(PowerLimits.batteryTooLow(limit, false));
        assertTrue(PowerLimits.batteryTooLow(0, false));
        assertFalse(PowerLimits.batteryTooLow(limit + 1, false));
        assertFalse(PowerLimits.batteryTooLow(limit, true));
        assertFalse(PowerLimits.batteryTooLow(-1, false));
    }
}
