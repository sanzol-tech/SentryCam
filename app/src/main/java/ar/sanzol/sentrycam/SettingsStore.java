package ar.sanzol.sentrycam;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Persists {@link Settings}, {@link VideoSettings} and the mode in SharedPreferences. Each
 * mode's settings are stored apart, so changing one never resets the other. Invalid stored
 * values fall back to defaults.
 */
public final class SettingsStore {

    private static final String FILE = "settings";
    private static final String K_INTERVAL = "interval_seconds";
    private static final String K_MAX_SHOTS = "max_shots";
    private static final String K_MAX_DURATION = "max_duration_seconds";
    private static final String K_LENS = "lens";
    private static final String K_WIDTH = "width";
    private static final String K_HEIGHT = "height";
    private static final String K_QUALITY = "jpeg_quality";
    private static final String K_MIN_FREE = "min_free_bytes";
    // UI-only: the unit last chosen for each field, so the form shows what the user typed.
    static final String K_INTERVAL_UNIT = "interval_unit";
    static final String K_DURATION_UNIT = "duration_unit";
    private static final String K_THEME = "theme";
    // Back lens choice: a LensCatalog key, and the module checks already done.
    private static final String K_LENS_KEY = "lens_key";
    private static final String K_LENS_CHECKS_FAILED = "lens_checks_failed";
    private static final String K_LENS_CHECKS_PASSED = "lens_checks_passed";
    // Mode and Video mode settings.
    private static final String K_MODE = "mode";
    private static final String K_V_DELAY = "video_delay_seconds";
    private static final String K_V_DURATION = "video_max_duration_seconds";
    private static final String K_V_WIDTH = "video_width";
    private static final String K_V_HEIGHT = "video_height";
    private static final String K_V_QUALITY = "video_quality";
    private static final String K_V_SEGMENT = "video_segment_minutes";
    private static final String K_V_AUDIO = "video_audio";
    static final String K_DELAY_UNIT = "video_delay_unit";
    static final String K_VIDEO_DURATION_UNIT = "video_duration_unit";

    public static final int THEME_SYSTEM = 0;
    public static final int THEME_LIGHT = 1;
    public static final int THEME_DARK = 2;

    private final SharedPreferences prefs;

    public SettingsStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public Settings load() {
        Settings d = Settings.defaults();
        Settings.Lens lens;
        try {
            lens = Settings.Lens.valueOf(prefs.getString(K_LENS, d.lens.name()));
        } catch (IllegalArgumentException e) {
            lens = d.lens;
        }
        Settings s = new Settings(
                prefs.getLong(K_INTERVAL, d.intervalSeconds),
                prefs.getInt(K_MAX_SHOTS, d.maxShots),
                prefs.getLong(K_MAX_DURATION, d.maxDurationSeconds),
                lens,
                prefs.getInt(K_WIDTH, d.width),
                prefs.getInt(K_HEIGHT, d.height),
                prefs.getInt(K_QUALITY, d.jpegQuality),
                prefs.getLong(K_MIN_FREE, d.minFreeBytes));
        return s.isValid() ? s : d;
    }

    /** Saves only valid settings; invalid ones are ignored (callers validate first). */
    public void save(Settings s) {
        if (!s.isValid()) {
            return;
        }
        prefs.edit()
                .putLong(K_INTERVAL, s.intervalSeconds)
                .putInt(K_MAX_SHOTS, s.maxShots)
                .putLong(K_MAX_DURATION, s.maxDurationSeconds)
                .putString(K_LENS, s.lens.name())
                .putInt(K_WIDTH, s.width)
                .putInt(K_HEIGHT, s.height)
                .putInt(K_QUALITY, s.jpegQuality)
                .putLong(K_MIN_FREE, s.minFreeBytes)
                .apply();
    }

    public Settings.Mode getMode() {
        try {
            return Settings.Mode.valueOf(prefs.getString(K_MODE, Settings.Mode.PHOTO.name()));
        } catch (IllegalArgumentException e) {
            return Settings.Mode.PHOTO;
        }
    }

    public void putMode(Settings.Mode mode) {
        prefs.edit().putString(K_MODE, mode.name()).apply();
    }

    public VideoSettings loadVideo() {
        VideoSettings d = VideoSettings.defaults();
        VideoSettings.Quality quality;
        try {
            quality = VideoSettings.Quality.valueOf(prefs.getString(K_V_QUALITY, d.quality.name()));
        } catch (IllegalArgumentException e) {
            quality = d.quality;
        }
        VideoSettings v = new VideoSettings(
                prefs.getLong(K_V_DELAY, d.delaySeconds),
                prefs.getLong(K_V_DURATION, d.maxDurationSeconds),
                prefs.getInt(K_V_WIDTH, d.width),
                prefs.getInt(K_V_HEIGHT, d.height),
                quality,
                prefs.getInt(K_V_SEGMENT, d.segmentMinutes),
                prefs.getBoolean(K_V_AUDIO, d.audio));
        return v.isValid() ? v : d;
    }

    /** Saves only valid settings; invalid ones are ignored (callers validate first). */
    public void saveVideo(VideoSettings v) {
        if (!v.isValid()) {
            return;
        }
        prefs.edit()
                .putLong(K_V_DELAY, v.delaySeconds)
                .putLong(K_V_DURATION, v.maxDurationSeconds)
                .putInt(K_V_WIDTH, v.width)
                .putInt(K_V_HEIGHT, v.height)
                .putString(K_V_QUALITY, v.quality.name())
                .putInt(K_V_SEGMENT, v.segmentMinutes)
                .putBoolean(K_V_AUDIO, v.audio)
                .apply();
    }

    /** UI theme: one of {@link #THEME_SYSTEM}, {@link #THEME_LIGHT}, {@link #THEME_DARK}. */
    public int getTheme() {
        int t = prefs.getInt(K_THEME, THEME_SYSTEM);
        return t == THEME_LIGHT || t == THEME_DARK ? t : THEME_SYSTEM;
    }

    public void putTheme(int theme) {
        prefs.edit().putInt(K_THEME, theme).apply();
    }

    /** The chosen back lens key; the main lens when none was chosen. */
    public String getLensKey() {
        return prefs.getString(K_LENS_KEY, LensCatalog.MAIN_KEY);
    }

    public void putLensKey(String key) {
        prefs.edit().putString(K_LENS_KEY, key).apply();
    }

    /** Module checks that failed: those lenses are digital zoom on this phone and not offered. */
    public Set<String> getFailedLensChecks() {
        return new HashSet<>(prefs.getStringSet(K_LENS_CHECKS_FAILED, Collections.emptySet()));
    }

    /** Module checks that passed: those lenses need no check again. */
    public Set<String> getPassedLensChecks() {
        return new HashSet<>(prefs.getStringSet(K_LENS_CHECKS_PASSED, Collections.emptySet()));
    }

    public void putLensCheck(String checkKey, boolean passed) {
        Set<String> set = passed ? getPassedLensChecks() : getFailedLensChecks();
        set.add(checkKey);
        prefs.edit().putStringSet(passed ? K_LENS_CHECKS_PASSED : K_LENS_CHECKS_FAILED, set).apply();
    }

    public long getUnit(String key, long fallback) {
        return prefs.getLong(key, fallback);
    }

    public void putUnit(String key, long unitSeconds) {
        prefs.edit().putLong(key, unitSeconds).apply();
    }
}
