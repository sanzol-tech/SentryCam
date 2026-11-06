package ar.sanzol.sentrycam;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.ForegroundServiceStartNotAllowedException;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.text.format.Formatter;
import android.util.Log;
import android.view.OrientationEventListener;

import java.io.IOException;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Foreground service for one continuous video session (Video mode): optional delayed
 * start, files of the configured length, duration limit, free-space reserve, low battery and
 * heat, recovery after camera errors. Recording itself is {@link VideoRecorder}; files are
 * {@link VideoStore}. Never deletes a finished file.
 *
 * <p>Threads: session state on the single "video" {@link HandlerThread}; publishing finished
 * files runs on one disk thread (except at the end of a session, done before stopping).
 */
public final class VideoService extends Service implements VideoRecorder.Callback {

    static final String ACTION_START = "ar.sanzol.sentrycam.action.VIDEO_START";
    static final String ACTION_STOP = "ar.sanzol.sentrycam.action.VIDEO_STOP";
    private static final String TAG = "SentryCam";

    private static final String CHANNEL_ID = "capture";
    private static final int NOTIFICATION_ID = 1;
    private static final int RESULT_NOTIFICATION_ID = 2;
    /** How often free space and the duration limit are checked. */
    private static final long POLL_MS = 5_000;
    private static final long SHUTDOWN_DRAIN_MS = 6_000;
    private static final long SHUTDOWN_MARGIN_MS = 500;
    private static final long SHUTDOWN_POLL_MS = 20;
    private static final long[] REOPEN_DELAYS_MS = {1_000, 2_000, 4_000, 8_000, 16_000};

    private HandlerThread thread;
    private Handler camera;
    private Handler main;
    private ExecutorService disk;
    private VideoRecorder recorder;
    private VideoStore store;
    private NotificationManager notifications;
    private PowerManager powerManager;
    private PowerManager.WakeLock wakeLock;
    private OrientationEventListener orientationListener;
    /** Battery and heat limits, shared with photo sessions. */
    private PowerGuard powerGuard;
    private volatile int deviceQuadrant = 0;
    /** Audio for the next session: wanted in the settings and the microphone permission granted. */
    private volatile boolean audioAllowed;
    private int handledStartId;

    // ---- Session state: video thread only ----
    private final Random random = new Random();
    private final SessionState.Data data = new SessionState.Data();
    private boolean active;
    private int sessionId;
    private Settings settings;
    private VideoSettings video;
    private LensProbe.Target target;
    private VideoRecorder.Format format;
    private int sensorOrientation;
    private String prefix;
    private int part;
    /** The file being recorded (or prepared); null between files. */
    private VideoStore.Output current;
    private long currentStartWallMs;
    private SessionLimits limits;
    private long recordingStartWallMs;
    private int reopenAttempt;
    private long baseStoredFiles = -1;
    private String resolutionWarning;
    private String lensWarning;
    private String audioWarning;
    private String thermalWarning;
    private String recoveryWarning;

    private final Runnable beginRecording = this::beginRecording;
    private final Runnable rollover = this::rollover;
    private final Runnable reopen = this::reopenRecorder;
    private final Runnable poll = this::poll;

    // -------------------------------------------------------------------------------------------
    // Service lifecycle
    // -------------------------------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        main = new Handler(Looper.getMainLooper());
        thread = new HandlerThread("video");
        thread.start();
        camera = new Handler(thread.getLooper());
        disk = Executors.newSingleThreadExecutor(r -> new Thread(r, "video-disk"));
        recorder = new VideoRecorder(this, camera, this);
        store = new VideoStore(this);
        notifications = getSystemService(NotificationManager.class);
        powerManager = getSystemService(PowerManager.class);
        powerGuard = new PowerGuard(this, camera, new PowerGuard.Listener() {
            @Override
            public void onPowerWarning(String warning) {
                thermalWarning = warning;
                publish();
            }

            @Override
            public void onPowerStop(SessionState.StopReason reason, String detail) {
                stopSession(reason, detail);
            }
        });
        createChannel();
        orientationListener = new OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int degrees) {
                int quadrant = JpegOrientation.roundToQuadrant(degrees);
                if (quadrant != JpegOrientation.UNKNOWN) {
                    deviceQuadrant = quadrant;
                }
            }
        };
        SessionState.get().setServiceAlive(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_START.equals(action)) {
            boolean wanted = new SettingsStore(this).loadVideo().audio;
            audioAllowed = wanted && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED;
            boolean audioDenied = wanted && !audioAllowed;
            if (enterForeground(startId)) {
                camera.post(() -> {
                    handledStartId = startId;
                    startSession(audioDenied);
                });
            }
        } else if (ACTION_STOP.equals(action)) {
            camera.post(() -> {
                handledStartId = startId;
                stopSession(SessionState.StopReason.USER, null);
            });
        } else {
            camera.post(() -> {
                handledStartId = startId;
                if (!active) {
                    stopSelf(handledStartId);
                }
            });
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        // As CaptureService: stop on the video thread (finalizing and publishing the current
        // file), let the disk thread finish publishing earlier files, then quit. Bounded.
        final long deadline = SystemClock.uptimeMillis() + SHUTDOWN_DRAIN_MS;
        camera.post(() -> {
            if (active) {
                stopSession(SessionState.StopReason.PROCESS_ENDED, null);
            }
            recorder.close();
            disk.shutdown();
            quitWhenDiskIdle(deadline);
        });
        try {
            thread.join(SHUTDOWN_DRAIN_MS + SHUTDOWN_MARGIN_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (thread.isAlive()) {
            Log.w(TAG, "Video thread did not finish shutting down in time");
            thread.quitSafely();
        }
        disk.shutdown();
        orientationListener.disable();
        releaseWakeLock();
        SessionState.get().setServiceAlive(false);
        super.onDestroy();
    }

    private void quitWhenDiskIdle(long deadline) {
        if (disk.isTerminated() || SystemClock.uptimeMillis() >= deadline) {
            thread.quitSafely();
            return;
        }
        camera.postDelayed(() -> quitWhenDiskIdle(deadline), SHUTDOWN_POLL_MS);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private boolean enterForeground(int startId) {
        int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                | (audioAllowed ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE : 0);
        try {
            startForeground(NOTIFICATION_ID, buildNotification(), type);
            return true;
        } catch (ForegroundServiceStartNotAllowedException e) {
            camera.post(() -> {
                handledStartId = startId;
                finishWithoutSession(SessionState.StopReason.START_NOT_ALLOWED, e.getMessage());
            });
        } catch (SecurityException e) {
            camera.post(() -> {
                handledStartId = startId;
                finishWithoutSession(SessionState.StopReason.PERMISSION, e.getMessage());
            });
        }
        return false;
    }

    // -------------------------------------------------------------------------------------------
    // Session start / stop (video thread)
    // -------------------------------------------------------------------------------------------

    private void startSession(boolean audioDenied) {
        if (active) {
            return;
        }
        SettingsStore prefs = new SettingsStore(this);
        settings = prefs.load();
        video = prefs.loadVideo();
        resetSessionData();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            finishWithoutSession(SessionState.StopReason.PERMISSION, null);
            return;
        }
        PowerGuard.Block block = PowerGuard.startBlock(this);
        if (block != null) {
            finishWithoutSession(block.reason, block.detail);
            return;
        }
        CameraManager cameraManager = getSystemService(CameraManager.class);
        LensProbe.Target t = LensProbe.target(cameraManager, settings.lens, prefs);
        if (t == null) {
            finishWithoutSession(SessionState.StopReason.NO_CAMERA, null);
            return;
        }
        if (!useTarget(cameraManager, t)) {
            finishWithoutSession(SessionState.StopReason.CAMERA_ERROR, "No supported video size");
            return;
        }
        if (t.fellBack) {
            lensWarning = getString(R.string.warning_lens_fallback);
        }
        if (audioDenied) {
            audioWarning = getString(R.string.warning_audio_denied);
        }
        Log.i(TAG, "Video session start: " + target + ", " + format.size + ", " + format.bitrate + " bps"
                + (format.audio ? ", audio" : "") + ", files " + video.segmentMinutes + " min, delay "
                + video.delaySeconds + " s, max duration " + video.maxDurationSeconds + " s");

        active = true;
        sessionId++;
        long now = SystemClock.elapsedRealtime();
        data.mode = Settings.Mode.VIDEO;
        data.status = SessionState.Status.STARTING;
        data.startedWallMs = System.currentTimeMillis();
        data.startedElapsedMs = now;
        prefix = VideoNames.sessionPrefix(data.startedWallMs, random);
        part = 0;
        SessionState.persistStarted(this, data);

        acquireWakeLock();
        main.post(() -> orientationListener.enable());
        powerGuard.start();

        final int id = sessionId;
        runOnDisk(() -> {
            int removed = store.removeIncompleteWrites();
            if (removed > 0) {
                Log.i(TAG, "Removed " + removed + " unfinished video file(s) left by an earlier session");
            }
            long count = store.countVideos();
            camera.post(() -> {
                if (id == sessionId) {
                    baseStoredFiles = count;
                    data.storedPhotos = count >= 0 ? count + data.files : -1;
                    publish();
                }
            });
        });

        if (video.hasDelay()) {
            // The service, its notification and the wake lock run during the wait, so the
            // camera may open when it ends even with the screen off (Android 14+ allows the
            // camera only to a foreground service started while the app was visible).
            data.status = SessionState.Status.WAITING;
            long delayMs = Settings.times(video.delaySeconds, 1000L);
            data.waitUntilElapsedMs = now + delayMs;
            publish();
            camera.postDelayed(beginRecording, delayMs);
        } else {
            publish();
            beginRecording();
        }
        camera.postDelayed(poll, POLL_MS);
    }

    private void resetSessionData() {
        data.setFrom(new SessionState.Data());
        data.mode = Settings.Mode.VIDEO;
        target = null;
        format = null;
        current = null;
        limits = null;
        reopenAttempt = 0;
        baseStoredFiles = -1;
        resolutionWarning = null;
        lensWarning = null;
        audioWarning = null;
        thermalWarning = null;
        recoveryWarning = null;
    }

    /** Makes {@code t} the session's camera: size, bitrate and frame rate for that camera. */
    private boolean useTarget(CameraManager cameraManager, LensProbe.Target t) {
        Resolution size = Resolution.choose(VideoCaps.videoSizes(cameraManager, t.cameraId), video.width, video.height);
        if (size == null) {
            return false;
        }
        int[] range = VideoCaps.bitrateRange();
        int bitrate = VideoPlan.bitrate(size.width, size.height, VideoPlan.FRAME_RATE, video.quality, range[0], range[1]);
        target = t;
        format = new VideoRecorder.Format(size, bitrate, audioAllowed, VideoCaps.fpsRange(cameraManager, t.cameraId));
        resolutionWarning = !video.usesLargestResolution()
                && (size.width != video.width || size.height != video.height)
                ? getString(R.string.warning_video_resolution_fallback, size.shortLabel()) : null;
        sensorOrientation = CameraController.sensorOrientation(cameraManager, t.cameraId);
        data.resolutionLabel = size.shortLabel();
        data.lensLabel = t.choice != null && t.offered > 1 ? t.factorLabel() : null;
        data.videoLabel = Texts.videoQuality(this, video.quality) + " · "
                + getString(format.audio ? R.string.detail_audio_on : R.string.detail_audio_off);
        return true;
    }

    /** The delay is over (or there was none): open the camera and record the first file. */
    private void beginRecording() {
        if (!active) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        data.waitUntilElapsedMs = 0;
        data.status = SessionState.Status.STARTING;
        recordingStartWallMs = System.currentTimeMillis();
        limits = new SessionLimits(0, Settings.times(video.maxDurationSeconds, 1000L), now);
        data.endElapsedMs = limits.hasDurationLimit() ? limits.endTimeMs() : 0;
        if (lowStorage()) {
            return;
        }
        publish();
        openRecorder();
    }

    private void openRecorder() {
        VideoStore.Output out = newOutput();
        if (out == null) {
            return;
        }
        current = out;
        currentStartWallMs = System.currentTimeMillis();
        recorder.open(target, format, out, orientationHint());
    }

    /** A new pending file, or null after stopping the session when that is impossible. */
    private VideoStore.Output newOutput() {
        try {
            return store.create(VideoNames.partName(prefix, ++part), System.currentTimeMillis());
        } catch (IOException e) {
            Log.w(TAG, "Could not create video file", e);
            stopSession(lowFree() ? SessionState.StopReason.LOW_STORAGE : SessionState.StopReason.STORAGE_ERROR,
                    e.getMessage());
            return null;
        }
    }

    private int orientationHint() {
        return JpegOrientation.compute(sensorOrientation, deviceQuadrant, settings.lens == Settings.Lens.FRONT);
    }

    private void stopSession(SessionState.StopReason reason, String detail) {
        if (!active) {
            if (reason == SessionState.StopReason.USER) {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf(handledStartId);
            }
            return;
        }
        active = false;
        // Reason first: publishing the last file below persists the record, and it must say why.
        // The file being recorded is counted by finishOutput, so it no longer runs on screen.
        data.status = SessionState.Status.STOPPED;
        data.stopReason = reason;
        data.stopDetail = detail;
        data.segmentStartElapsedMs = 0;
        data.waitUntilElapsedMs = 0;
        camera.removeCallbacks(beginRecording);
        camera.removeCallbacks(rollover);
        camera.removeCallbacks(reopen);
        camera.removeCallbacks(poll);
        if (recorder.isRecording()) {
            long duration = SystemClock.elapsedRealtime() - recorder.fileStartElapsed();
            boolean finalized = recorder.endFile();
            // Published here, before the service stops, so the last file is never left pending.
            finishOutput(finalized, duration, true);
        } else if (current != null) {
            finishOutput(false, 0, true);
        }
        recorder.close();
        Log.i(TAG, "Video session stop: " + reason + (detail == null ? "" : " (" + detail + ")")
                + ", files " + data.files + ", recorded " + data.recordedMs / 1000 + " s, failures " + data.failures);
        main.post(() -> orientationListener.disable());
        powerGuard.stop();
        thermalWarning = null;
        releaseWakeLock();

        recoveryWarning = null;
        SessionState.persistStopped(this, data);
        publish();
        stopForeground(STOP_FOREGROUND_REMOVE);
        if (reason != SessionState.StopReason.USER) {
            showResultNotification(reason, detail);
        }
        stopSelf(handledStartId);
    }

    private void finishWithoutSession(SessionState.StopReason reason, String detail) {
        Log.w(TAG, "Video session not started: " + reason + (detail == null ? "" : " (" + detail + ")"));
        data.mode = Settings.Mode.VIDEO;
        data.status = SessionState.Status.STOPPED;
        data.stopReason = reason;
        data.stopDetail = detail;
        SessionState.persistStopped(this, data);
        publish();
        stopForeground(STOP_FOREGROUND_REMOVE);
        showResultNotification(reason, detail);
        stopSelf(handledStartId);
    }

    // -------------------------------------------------------------------------------------------
    // Files
    // -------------------------------------------------------------------------------------------

    @Override
    public void onRecording() {
        if (!active) {
            return;
        }
        if (target.checkPhysicalId != null && target.choice != null && target.choice.checkKey() != null) {
            new SettingsStore(this).putLensCheck(target.choice.checkKey(), true);
        }
        reopenAttempt = 0;
        recoveryWarning = null;
        data.status = SessionState.Status.RUNNING;
        data.segmentStartElapsedMs = recorder.fileStartElapsed();
        scheduleRollover();
        publish();
    }

    private void scheduleRollover() {
        camera.removeCallbacks(rollover);
        long now = SystemClock.elapsedRealtime();
        long length = VideoPlan.nextSegmentMs(video.segmentMs(), limits.remainingMs(now));
        camera.postDelayed(rollover, length);
    }

    @Override
    public void onFileFull() {
        Log.i(TAG, "Video file reached the size limit; starting the next one");
        rollover();
    }

    /** Ends the current file and starts the next one, unless a limit ends the session. */
    private void rollover() {
        camera.removeCallbacks(rollover);
        if (!active || !recorder.isRecording()) {
            return;
        }
        long duration = SystemClock.elapsedRealtime() - recorder.fileStartElapsed();
        boolean finalized = recorder.endFile();
        finishOutput(finalized, duration, false);
        data.segmentStartElapsedMs = 0;
        if (limits.durationReached(SystemClock.elapsedRealtime())) {
            stopSession(SessionState.StopReason.MAX_DURATION, null);
            return;
        }
        if (lowStorage()) {
            return;
        }
        VideoStore.Output out = newOutput();
        if (out == null) {
            return;
        }
        current = out;
        currentStartWallMs = System.currentTimeMillis();
        if (!recorder.beginFile(out, orientationHint())) {
            return; // onError already handled it
        }
        data.segmentStartElapsedMs = recorder.fileStartElapsed();
        scheduleRollover();
        publish();
    }

    /**
     * Publishes the current file when the recorder finalized it, or removes it when it is not
     * playable (our own unfinished write). {@code now}: on this thread, before returning.
     */
    private void finishOutput(boolean finalized, long durationMs, boolean now) {
        VideoStore.Output out = current;
        current = null;
        if (out == null) {
            return;
        }
        if (finalized) {
            data.recordedMs += Math.max(0, durationMs);
        }
        final int id = sessionId;
        final long wall = currentStartWallMs + Math.max(0, durationMs);
        Runnable work = () -> {
            if (finalized) {
                long size = store.publish(out);
                camera.post(() -> onFileDone(id, size, wall));
            } else {
                store.discardOutput(out);
                camera.post(() -> onFileDone(id, -2, wall));
            }
        };
        if (now || !runOnDisk(work)) {
            if (finalized) {
                onFileDone(id, store.publish(out), wall);
            } else {
                store.discardOutput(out);
                onFileDone(id, -2, wall);
            }
        }
    }

    /** {@code size}: bytes when published, -1 when publishing failed, -2 when discarded. */
    private void onFileDone(int id, long size, long wall) {
        if (id != sessionId) {
            return;
        }
        if (size >= 0) {
            data.files++;
            data.sessionBytes += size;
            data.lastCaptureWallMs = wall;
            if (baseStoredFiles >= 0) {
                data.storedPhotos = baseStoredFiles + data.files;
            }
            Log.d(TAG, "Video file " + data.files + " saved, " + size + " bytes");
        } else {
            data.failures++;
            Log.w(TAG, size == -1 ? "Video file could not be published" : "Video file was not playable; removed");
        }
        if (active) {
            SessionState.persistProgress(this, data);
        } else {
            SessionState.persistStopped(this, data);
        }
        publish();
    }

    // -------------------------------------------------------------------------------------------
    // Errors and recovery
    // -------------------------------------------------------------------------------------------

    @Override
    public void onError(CameraController.Error error, String detail, boolean finalized) {
        Log.w(TAG, "Video error " + error + ": " + detail);
        camera.removeCallbacks(rollover);
        long duration = data.segmentStartElapsedMs > 0 ? SystemClock.elapsedRealtime() - data.segmentStartElapsedMs : 0;
        finishOutput(finalized, duration, false);
        data.segmentStartElapsedMs = 0;
        if (!active) {
            return;
        }
        if (error == CameraController.Error.PERMISSION) {
            stopSession(SessionState.StopReason.PERMISSION, detail);
            return;
        }
        if (error == CameraController.Error.LENS_NOT_ACTIVE) {
            fallBackToMainLens(detail);
            return;
        }
        if (error == CameraController.Error.CONFIGURATION && data.status == SessionState.Status.STARTING
                && data.files == 0 && reopenAttempt == 0) {
            // The size or stream combination is not accepted; retrying cannot fix it.
            stopSession(SessionState.StopReason.CAMERA_ERROR, detail + " — " + format.size.label());
            return;
        }
        if (reopenAttempt >= REOPEN_DELAYS_MS.length) {
            boolean unavailable = error == CameraController.Error.IN_USE
                    || error == CameraController.Error.DISCONNECTED || error == CameraController.Error.DISABLED;
            stopSession(unavailable ? SessionState.StopReason.CAMERA_UNAVAILABLE
                    : SessionState.StopReason.CAMERA_ERROR, detail);
            return;
        }
        long delay = REOPEN_DELAYS_MS[reopenAttempt++];
        data.status = SessionState.Status.RECOVERING;
        recoveryWarning = getString(R.string.warning_recovering, error.name(), reopenAttempt, REOPEN_DELAYS_MS.length);
        publish();
        camera.postDelayed(reopen, delay);
    }

    private void reopenRecorder() {
        if (!active) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            stopSession(SessionState.StopReason.PERMISSION, null);
            return;
        }
        if (limits.durationReached(SystemClock.elapsedRealtime())) {
            stopSession(SessionState.StopReason.MAX_DURATION, null);
            return;
        }
        if (!lowStorage()) {
            openRecorder();
        }
    }

    /** As in photo mode: the lens is digital zoom here; drop it and continue on 1×. */
    private void fallBackToMainLens(String detail) {
        LensCatalog.Choice failed = target == null ? null : target.choice;
        SettingsStore prefs = new SettingsStore(this);
        if (failed != null && failed.checkKey() != null) {
            prefs.putLensCheck(failed.checkKey(), false);
        }
        prefs.putLensKey(LensCatalog.MAIN_KEY);
        CameraManager cameraManager = getSystemService(CameraManager.class);
        LensProbe.Target mainLens = LensProbe.target(cameraManager, Settings.Lens.BACK, prefs);
        if (mainLens == null || mainLens.checkPhysicalId != null || !useTarget(cameraManager, mainLens)) {
            stopSession(SessionState.StopReason.CAMERA_ERROR, detail);
            return;
        }
        lensWarning = failed == null ? null
                : getString(R.string.warning_lens_digital_zoom, LensCatalog.formatFactor(failed.factor));
        Log.i(TAG, "Lens fallback: " + target + ", " + format.size);
        publish();
        openRecorder();
    }

    // -------------------------------------------------------------------------------------------
    // Limits: storage and duration (polled); battery and heat in PowerGuard
    // -------------------------------------------------------------------------------------------

    private void poll() {
        if (!active) {
            return;
        }
        if (limits != null && limits.durationReached(SystemClock.elapsedRealtime()) && recorder.isRecording()) {
            rollover(); // ends the session at the limit
            if (!active) {
                return;
            }
        }
        if (lowStorage()) {
            return;
        }
        camera.postDelayed(poll, POLL_MS);
    }

    private boolean lowFree() {
        long free = PhotoStore.freeBytes();
        return free >= 0 && free < settings.minFreeBytes;
    }

    /** Stops the session when free space is below the reserve. Returns true if it stopped. */
    private boolean lowStorage() {
        long free = PhotoStore.freeBytes();
        if (free >= 0 && free < settings.minFreeBytes) {
            stopSession(SessionState.StopReason.LOW_STORAGE, Formatter.formatShortFileSize(this, free));
            return true;
        }
        return false;
    }

    // No timeout on purpose, as in CaptureService: released on every exit path.
    @SuppressLint("WakelockTimeout")
    private void acquireWakeLock() {
        if (wakeLock == null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SentryCam:video");
            wakeLock.setReferenceCounted(false);
        }
        wakeLock.acquire();
    }

    private void releaseWakeLock() {
        PowerManager.WakeLock lock = wakeLock;
        if (lock != null && lock.isHeld()) {
            lock.release();
        }
    }

    private boolean runOnDisk(Runnable work) {
        try {
            disk.execute(work);
            return true;
        } catch (RejectedExecutionException e) {
            return false;
        }
    }

    // -------------------------------------------------------------------------------------------
    // State and notifications
    // -------------------------------------------------------------------------------------------

    private void publish() {
        data.warning = joinWarnings();
        SessionState.get().publish(data);
        if (active) {
            notifications.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    private String joinWarnings() {
        StringBuilder sb = new StringBuilder();
        for (String w : new String[] {recoveryWarning, lensWarning, audioWarning, resolutionWarning, thermalWarning}) {
            if (w != null) {
                if (sb.length() > 0) { // not isEmpty(): a Java 15 API, absent at minSdk 31
                    sb.append('\n');
                }
                sb.append(w);
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setShowBadge(false);
        notifications.createNotificationChannel(channel);
    }

    private PendingIntent openAppIntent() {
        Intent open = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** The system draws the countdown (waiting) or the elapsed time (recording) itself. */
    private Notification buildNotification() {
        SessionState.Data d = data;
        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_capture)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .setContentIntent(openAppIntent());
        long nowWall = System.currentTimeMillis();
        if (d.status == SessionState.Status.WAITING && d.waitUntilElapsedMs > 0) {
            long left = Math.max(0, d.waitUntilElapsedMs - SystemClock.elapsedRealtime());
            b.setContentTitle(getString(R.string.notification_title_waiting))
                    .setContentText(getString(R.string.notification_text_waiting,
                            CaptureService.formatTime(this, nowWall + left)))
                    .setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
                    .setWhen(nowWall + left);
        } else if (d.status == SessionState.Status.RUNNING) {
            b.setContentTitle(getString(R.string.notification_title_recording))
                    .setContentText(Texts.plural(this, R.plurals.notification_text_files, d.files))
                    .setShowWhen(true).setUsesChronometer(true)
                    .setWhen(recordingStartWallMs > 0 ? recordingStartWallMs : nowWall);
        } else {
            b.setContentTitle(getString(d.status == SessionState.Status.RECOVERING
                            ? R.string.notification_title_recovering : R.string.notification_title_starting))
                    .setShowWhen(false);
        }
        Intent stop = new Intent(this, VideoService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 2, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        b.addAction(new Notification.Action.Builder(null, getString(R.string.action_stop), stopIntent).build());
        return b.build();
    }

    private void showResultNotification(SessionState.StopReason reason, String detail) {
        String text = Texts.stopReason(this, reason, detail);
        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_capture)
                .setContentTitle(getString(R.string.notification_title_stopped))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(openAppIntent())
                .build();
        notifications.notify(RESULT_NOTIFICATION_ID, n);
    }
}
