package ar.sanzol.sentrycam;

/**
 * Battery limit shared by Photo and Video sessions, pure Java. The heat limits are Android's
 * own thermal levels, named in {@link PowerGuard}. Fixed values, not settings: Android also
 * throttles or stops the camera on its own when the phone is hot or the battery is low, so a
 * setting here would add choices without adding control.
 */
public final class PowerLimits {

    /** A session stops at or below this battery level, unless the phone is charging. */
    public static final int STOP_BATTERY_PERCENT = 10;

    private PowerLimits() {
    }

    /** True when a session should stop for low battery; an unknown level (below 0) never stops it. */
    public static boolean batteryTooLow(int levelPercent, boolean charging) {
        return !charging && levelPercent >= 0 && levelPercent <= STOP_BATTERY_PERCENT;
    }
}
