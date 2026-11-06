package ar.sanzol.sentrycam;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Observable session state shared by {@link CaptureService} and {@link MainActivity} in the same
 * process, plus a small persisted record so the real outcome is shown after process death.
 */
public final class SessionState {

    /** WAITING: a video session in its delayed start; the camera is not open yet. */
    public enum Status { STOPPED, STARTING, WAITING, RUNNING, RECOVERING }

    public enum StopReason {
        USER,
        MAX_SHOTS,
        MAX_DURATION,
        LOW_STORAGE,
        PERMISSION,
        CAMERA_UNAVAILABLE,
        CAMERA_ERROR,
        STORAGE_ERROR,
        NO_CAMERA,
        START_NOT_ALLOWED,
        PROCESS_ENDED,
        LOW_BATTERY,
        TOO_HOT
    }

    /** Plain data. Instances handed to listeners are copies and must not be modified. */
    public static final class Data {
        public Settings.Mode mode = Settings.Mode.PHOTO;
        public Status status = Status.STOPPED;
        public StopReason stopReason;
        public String stopDetail;
        public long intervalMs;
        public long shots;
        public long skipped;
        public long failures;
        public long sessionBytes;
        public long startedWallMs;
        public long startedElapsedMs;
        /** elapsedRealtime at which the duration limit ends, or 0 when unlimited. */
        public long endElapsedMs;
        public int maxShots;
        public long lastCaptureWallMs;
        public String lastPhotoUri;
        /** Total SentryCam photos visible to the app, or -1 when not yet known. */
        public long storedPhotos = -1;
        public String resolutionLabel;
        /** Back lens factor ("0.5") when the phone offers several back lenses; null otherwise. */
        public String lensLabel;
        public String warning;
        // Video mode. sessionBytes and lastCaptureWallMs are shared with photos (bytes saved,
        // time of the last completed file).
        /** Completed video files this session. */
        public long files;
        /** Recorded time in completed files. */
        public long recordedMs;
        /** elapsedRealtime when the file being recorded started; 0 when none is recording. */
        public long segmentStartElapsedMs;
        /** elapsedRealtime when a delayed start ends; 0 when not waiting. */
        public long waitUntilElapsedMs;
        /** Quality and audio, for the details line ("Medium · audio"). */
        public String videoLabel;

        /** Recorded time including the file being recorded now. */
        public long recordedMsAt(long nowElapsed) {
            return recordedMs + (segmentStartElapsedMs > 0 ? Math.max(0, nowElapsed - segmentStartElapsedMs) : 0);
        }

        public Data copy() {
            Data d = new Data();
            d.setFrom(this);
            return d;
        }

        /** Copies every field from {@code o} into this instance. */
        public void setFrom(Data o) {
            Data d = this;
            d.mode = o.mode;
            d.status = o.status;
            d.stopReason = o.stopReason;
            d.stopDetail = o.stopDetail;
            d.intervalMs = o.intervalMs;
            d.shots = o.shots;
            d.skipped = o.skipped;
            d.failures = o.failures;
            d.sessionBytes = o.sessionBytes;
            d.startedWallMs = o.startedWallMs;
            d.startedElapsedMs = o.startedElapsedMs;
            d.endElapsedMs = o.endElapsedMs;
            d.maxShots = o.maxShots;
            d.lastCaptureWallMs = o.lastCaptureWallMs;
            d.lastPhotoUri = o.lastPhotoUri;
            d.storedPhotos = o.storedPhotos;
            d.resolutionLabel = o.resolutionLabel;
            d.lensLabel = o.lensLabel;
            d.warning = o.warning;
            d.files = o.files;
            d.recordedMs = o.recordedMs;
            d.segmentStartElapsedMs = o.segmentStartElapsedMs;
            d.waitUntilElapsedMs = o.waitUntilElapsedMs;
            d.videoLabel = o.videoLabel;
        }
    }

    public interface Listener {
        void onSessionChanged(Data data);
    }

    private static final SessionState INSTANCE = new SessionState();

    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile Data current = new Data();
    /** Capture and video services alive in this process (never both in a session). */
    private final AtomicInteger servicesAlive = new AtomicInteger();

    private SessionState() {
    }

    public static SessionState get() {
        return INSTANCE;
    }

    public Data current() {
        return current;
    }

    public boolean isServiceAlive() {
        return servicesAlive.get() > 0;
    }

    /** Each service calls this with true in onCreate and false in onDestroy. */
    void setServiceAlive(boolean alive) {
        if (alive) {
            servicesAlive.incrementAndGet();
        } else {
            servicesAlive.updateAndGet(n -> Math.max(0, n - 1));
        }
    }

    /** Publishes a copy of {@code data}; listeners run on the main thread. */
    public void publish(Data data) {
        Data snapshot = data.copy();
        current = snapshot;
        main.post(() -> {
            for (Listener l : listeners) {
                l.onSessionChanged(snapshot);
            }
        });
    }

    public void addListener(Listener l) {
        listeners.addIfAbsent(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    // ---------------------------------------------------------------------------------------
    // Persisted record of the last session (survives process death).
    // ---------------------------------------------------------------------------------------

    private static final String FILE = "session";
    private static final String K_ACTIVE = "active";
    private static final String K_REASON = "stop_reason";
    private static final String K_DETAIL = "stop_detail";
    private static final String K_SHOTS = "shots";
    private static final String K_SKIPPED = "skipped";
    private static final String K_FAILURES = "failures";
    private static final String K_BYTES = "bytes";
    private static final String K_STARTED = "started_wall";
    private static final String K_LAST_CAPTURE = "last_capture_wall";
    private static final String K_LAST_URI = "last_photo_uri";
    private static final String K_MODE = "mode";
    private static final String K_FILES = "files";
    private static final String K_RECORDED = "recorded_ms";

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // commit(), not apply(): the start and stop records must be on disk before the process can
    // die, or the next launch would misreport the outcome. Called off the main thread except
    // for the small restore write in restoreIfIdle().
    @SuppressLint("ApplySharedPref")
    static void persistStarted(Context context, Data d) {
        prefs(context).edit()
                .putBoolean(K_ACTIVE, true)
                .remove(K_REASON)
                .remove(K_DETAIL)
                .putLong(K_SHOTS, 0)
                .putLong(K_SKIPPED, 0)
                .putLong(K_FAILURES, 0)
                .putLong(K_BYTES, 0)
                .putLong(K_STARTED, d.startedWallMs)
                .putString(K_MODE, d.mode.name())
                .putLong(K_FILES, 0)
                .putLong(K_RECORDED, 0)
                .commit();
    }

    static void persistProgress(Context context, Data d) {
        prefs(context).edit()
                .putLong(K_SHOTS, d.shots)
                .putLong(K_SKIPPED, d.skipped)
                .putLong(K_FAILURES, d.failures)
                .putLong(K_BYTES, d.sessionBytes)
                .putLong(K_LAST_CAPTURE, d.lastCaptureWallMs)
                .putString(K_LAST_URI, d.lastPhotoUri)
                .putLong(K_FILES, d.files)
                .putLong(K_RECORDED, d.recordedMs)
                .apply();
    }

    @SuppressLint("ApplySharedPref")
    static void persistStopped(Context context, Data d) {
        prefs(context).edit()
                .putBoolean(K_ACTIVE, false)
                .putString(K_REASON, d.stopReason == null ? null : d.stopReason.name())
                .putString(K_DETAIL, d.stopDetail)
                .putLong(K_SHOTS, d.shots)
                .putLong(K_SKIPPED, d.skipped)
                .putLong(K_FAILURES, d.failures)
                .putLong(K_BYTES, d.sessionBytes)
                .putLong(K_LAST_CAPTURE, d.lastCaptureWallMs)
                .putString(K_LAST_URI, d.lastPhotoUri)
                .putString(K_MODE, d.mode.name())
                .putLong(K_FILES, d.files)
                .putLong(K_RECORDED, d.recordedMs)
                .commit();
    }

    /**
     * Rebuilds the state when no service runs in this process. If the record says a session was
     * active, the process ended without a clean stop; that is recorded and reported.
     */
    public Data restoreIfIdle(Context context) {
        if (isServiceAlive()) {
            return current;
        }
        SharedPreferences p = prefs(context);
        Data d = new Data();
        d.status = Status.STOPPED;
        d.shots = p.getLong(K_SHOTS, 0);
        d.skipped = p.getLong(K_SKIPPED, 0);
        d.failures = p.getLong(K_FAILURES, 0);
        d.sessionBytes = p.getLong(K_BYTES, 0);
        d.startedWallMs = p.getLong(K_STARTED, 0);
        d.lastCaptureWallMs = p.getLong(K_LAST_CAPTURE, 0);
        d.lastPhotoUri = p.getString(K_LAST_URI, null);
        d.storedPhotos = current.storedPhotos;
        try {
            d.mode = Settings.Mode.valueOf(p.getString(K_MODE, Settings.Mode.PHOTO.name()));
        } catch (IllegalArgumentException e) {
            d.mode = Settings.Mode.PHOTO;
        }
        d.files = p.getLong(K_FILES, 0);
        d.recordedMs = p.getLong(K_RECORDED, 0);
        if (p.getBoolean(K_ACTIVE, false)) {
            d.stopReason = StopReason.PROCESS_ENDED;
            persistStopped(context, d);
        } else {
            String reason = p.getString(K_REASON, null);
            try {
                d.stopReason = reason == null ? null : StopReason.valueOf(reason);
            } catch (IllegalArgumentException e) {
                d.stopReason = null;
            }
            d.stopDetail = p.getString(K_DETAIL, null);
        }
        current = d;
        return d;
    }
}
