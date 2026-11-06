package ar.sanzol.sentrycam;

import android.content.Context;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.PowerManager;

/**
 * Battery and heat watch for a running session, the same for Photo and Video: one rule, one
 * place. Calls back on the session's handler thread.
 *
 * <ul>
 *   <li>Battery at or below {@link PowerLimits#STOP_BATTERY_PERCENT} and not charging: stop.</li>
 *   <li>Thermal status {@link #WARN_THERMAL} or worse: warning; {@link #STOP_THERMAL} or worse:
 *       stop. Android may already be slowing the camera down at those levels.</li>
 * </ul>
 */
final class PowerGuard {

    interface Listener {
        /** The heat warning to show, or null when there is none. */
        void onPowerWarning(String warning);

        /** The session must stop; {@code detail} is shown with the reason (e.g. "9 %"). */
        void onPowerStop(SessionState.StopReason reason, String detail);
    }

    static final int WARN_THERMAL = PowerManager.THERMAL_STATUS_SEVERE;
    static final int STOP_THERMAL = PowerManager.THERMAL_STATUS_CRITICAL;
    /** The battery level changes slowly; once every 30 s is enough. */
    static final long BATTERY_CHECK_MS = 30_000;

    private final Context context;
    private final Handler handler;
    private final Listener listener;
    private final PowerManager power;
    private PowerManager.OnThermalStatusChangedListener thermal;
    private boolean running;

    private final Runnable batteryCheck = this::checkBattery;

    /** Why a session must not start now (reason and detail), or null when it may. */
    static final class Block {
        final SessionState.StopReason reason;
        final String detail;

        Block(SessionState.StopReason reason, String detail) {
            this.reason = reason;
            this.detail = detail;
        }
    }

    /**
     * The same limits, checked once before a session opens the camera, so a phone already too
     * hot or too low on battery does not start and stop at once. Any thread.
     */
    static Block startBlock(Context context) {
        PowerManager power = context.getSystemService(PowerManager.class);
        if (power != null && power.getCurrentThermalStatus() >= STOP_THERMAL) {
            return new Block(SessionState.StopReason.TOO_HOT, null);
        }
        BatteryManager battery = context.getSystemService(BatteryManager.class);
        if (battery != null) {
            int level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            if (PowerLimits.batteryTooLow(level, battery.isCharging())) {
                return new Block(SessionState.StopReason.LOW_BATTERY, context.getString(R.string.detail_percent, level));
            }
        }
        return null;
    }

    PowerGuard(Context context, Handler handler, Listener listener) {
        this.context = context;
        this.handler = handler;
        this.listener = listener;
        this.power = context.getSystemService(PowerManager.class);
    }

    /** Starts watching; both are checked right after the caller returns. Handler thread. */
    void start() {
        if (running) {
            return;
        }
        running = true;
        // Called once at registration with the current status, then on every change.
        thermal = status -> handler.post(() -> onThermal(status));
        power.addThermalStatusListener(handler::post, thermal);
        // Posted, not called: a stop must never run in the middle of the session's own start.
        handler.post(batteryCheck);
    }

    /** Stops watching. Handler thread; idempotent. */
    void stop() {
        running = false;
        handler.removeCallbacks(batteryCheck);
        if (thermal != null) {
            power.removeThermalStatusListener(thermal);
            thermal = null;
        }
    }

    private void onThermal(int status) {
        if (!running) {
            return;
        }
        if (status >= STOP_THERMAL) {
            listener.onPowerStop(SessionState.StopReason.TOO_HOT, null);
            return;
        }
        listener.onPowerWarning(status >= WARN_THERMAL ? context.getString(R.string.warning_thermal) : null);
    }

    private void checkBattery() {
        if (!running) {
            return;
        }
        BatteryManager battery = context.getSystemService(BatteryManager.class);
        if (battery != null) {
            int level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            if (PowerLimits.batteryTooLow(level, battery.isCharging())) {
                listener.onPowerStop(SessionState.StopReason.LOW_BATTERY,
                        context.getString(R.string.detail_percent, level));
                return;
            }
        }
        handler.postDelayed(batteryCheck, BATTERY_CHECK_MS);
    }
}
