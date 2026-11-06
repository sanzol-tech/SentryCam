package ar.sanzol.sentrycam;

import android.content.Context;

import java.util.Locale;

/** User-facing text helpers shared by the activity and the service. */
final class Texts {

    private Texts() {
    }

    /**
     * A plural string whose first format argument is {@code count}; {@code more} follow it.
     * Counts above {@link Integer#MAX_VALUE} select the plural form of that value.
     */
    static String plural(Context c, int id, long count, Object... more) {
        Object[] args = new Object[more.length + 1];
        args[0] = count;
        System.arraycopy(more, 0, args, 1, more.length);
        int quantity = (int) Math.min(count, Integer.MAX_VALUE);
        return c.getResources().getQuantityString(id, quantity, args);
    }

    static String stopReason(Context c, SessionState.StopReason reason, String detail) {
        if (reason == null) {
            return "";
        }
        String text;
        switch (reason) {
            case USER:
                text = c.getString(R.string.reason_user);
                break;
            case MAX_SHOTS:
                text = c.getString(R.string.reason_max_shots);
                break;
            case MAX_DURATION:
                text = c.getString(R.string.reason_max_duration);
                break;
            case LOW_STORAGE:
                return c.getString(R.string.reason_low_storage, detail == null ? "?" : detail);
            case PERMISSION:
                text = c.getString(R.string.reason_permission);
                break;
            case CAMERA_UNAVAILABLE:
                text = c.getString(R.string.reason_camera_unavailable);
                break;
            case CAMERA_ERROR:
                text = c.getString(R.string.reason_camera_error);
                break;
            case STORAGE_ERROR:
                text = c.getString(R.string.reason_storage_error);
                break;
            case NO_CAMERA:
                text = c.getString(R.string.reason_no_camera);
                break;
            case START_NOT_ALLOWED:
                text = c.getString(R.string.reason_start_not_allowed);
                break;
            case PROCESS_ENDED:
                text = c.getString(R.string.reason_process_ended);
                break;
            case LOW_BATTERY:
                text = c.getString(R.string.reason_low_battery);
                break;
            case TOO_HOT:
                text = c.getString(R.string.reason_too_hot);
                break;
            default:
                text = reason.name();
        }
        if (detail != null && !detail.isEmpty()) {
            return c.getString(R.string.reason_with_detail, text, detail);
        }
        return text;
    }

    /** "High", "Medium", "Low" in the UI language. */
    static String videoQuality(Context c, VideoSettings.Quality q) {
        return c.getString(switch (q) {
            case HIGH -> R.string.quality_high;
            case MEDIUM -> R.string.quality_medium;
            case LOW -> R.string.quality_low;
        });
    }

    /** Compact duration such as "45 s", "12 min", "3 h 05 min", "2 d 4 h". */
    static String duration(long seconds) {
        if (seconds < 0) {
            return "—";
        }
        if (seconds < 60) {
            return seconds + " s";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            long s = seconds % 60;
            return s == 0 ? minutes + " min" : String.format(Locale.ROOT, "%d min %02d s", minutes, s);
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return String.format(Locale.ROOT, "%d h %02d min", hours, minutes % 60);
        }
        long days = hours / 24;
        return String.format(Locale.ROOT, "%d d %d h", days, hours % 24);
    }

    /** Interval with one decimal for sub-minute values, e.g. "1.0 s", "2.4 s". */
    static String interval(double seconds) {
        if (seconds < 60) {
            return String.format(Locale.getDefault(), "%.1f s", seconds);
        }
        return duration(Math.round(seconds));
    }
}
