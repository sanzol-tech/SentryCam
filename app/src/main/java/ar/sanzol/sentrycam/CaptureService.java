package ar.sanzol.sentrycam;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.ForegroundServiceStartNotAllowedException;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.camera2.CameraManager;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.text.format.DateFormat;
import android.text.format.Formatter;
import android.util.Log;
import android.view.OrientationEventListener;

import java.io.IOException;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Foreground service that runs one capture session: scheduling, limits, wake lock, notification
 * and recovery. Camera2 details live in {@link CameraController}; storage in {@link PhotoStore}.
 *
 * <p>Threads: all session state is owned by the single "camera" {@link HandlerThread}; MediaStore
 * writes run on one disk thread and post their results back. Nothing blocking runs on the main
 * thread.
 */
public final class CaptureService extends Service implements CameraController.Callback {

    static final String ACTION_START = "ar.sanzol.sentrycam.action.START";
    static final String ACTION_STOP = "ar.sanzol.sentrycam.action.STOP";
    private static final String TAG = "SentryCam";

    private static final String CHANNEL_ID = "capture";
    private static final int NOTIFICATION_ID = 1;
    private static final int RESULT_NOTIFICATION_ID = 2;
    private static final long NOTIFICATION_MIN_INTERVAL_MS = 2_000;
    /** At or below this interval the metering stream runs continuously so stills are immediate. */
    private static final long CONTINUOUS_METERING_MAX_INTERVAL_MS = 10_000;
    private static final int MAX_CONSECUTIVE_CAPTURE_FAILURES = 3;
    private static final int MAX_CONSECUTIVE_SAVE_FAILURES = 3;
    /** How long onDestroy lets an in-flight photo write finish and be recorded. */
    private static final long SHUTDOWN_DRAIN_MS = 3_000;
    private static final long SHUTDOWN_MARGIN_MS = 500;
    private static final long SHUTDOWN_POLL_MS = 20;
    private static final long[] REOPEN_DELAYS_MS = {1_000, 2_000, 4_000, 8_000, 16_000};

    private HandlerThread cameraThread;
    private Handler camera;
    private ExecutorService disk;
    private Handler main;
    private CameraController controller;
    private PhotoStore store;
    private NotificationManager notifications;
    private PowerManager powerManager;
    private PowerManager.WakeLock wakeLock;
    private OrientationEventListener orientationListener;
    /** Battery and heat limits, shared with video sessions. */
    private PowerGuard powerGuard;
    private volatile int deviceQuadrant = 0;
    /**
     * Start id of the last command handled on the camera thread. Stopping with it never cancels
     * a command that arrived later (stopSelf(int) is a no-op when a newer start id exists).
     */
    private int handledStartId;

    // ---- Session state: camera thread only ----
    private final Random random = new Random();
    private final SessionState.Data data = new SessionState.Data();
    private boolean active;
    private int sessionId;
    private Settings settings;
    /** Camera, zoom ratio and lens: the same rule as Preview (LensProbe.target). */
    private LensProbe.Target target;
    private String cameraId;
    /** Module still to confirm on the next open; null once confirmed or when not needed. */
    private String lensCheck;
    private Resolution size;
    private int sensorOrientation;
    private IntervalScheduler scheduler;
    private SessionLimits limits;
    private boolean captureInFlight;
    private boolean saving;
    private int consecutiveCaptureFailures;
    private int consecutiveSaveFailures;
    private int reopenAttempt;
    private boolean reopenPending;
    private long baseStoredPhotos = -1;
    private long lastNotificationMs;
    private String resolutionWarning;
    private String lensWarning;
    private String thermalWarning;
    private String recoveryWarning;

    private final Runnable tick = this::onTick;
    private final Runnable reopen = this::reopenCamera;

    // -------------------------------------------------------------------------------------------
    // Service lifecycle
    // -------------------------------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        main = new Handler(Looper.getMainLooper());
        cameraThread = new HandlerThread("camera");
        cameraThread.start();
        camera = new Handler(cameraThread.getLooper());
        disk = Executors.newSingleThreadExecutor(r -> new Thread(r, "disk"));
        controller = new CameraController(this, camera, this);
        store = new PhotoStore(this);
        notifications = getSystemService(NotificationManager.class);
        powerManager = getSystemService(PowerManager.class);
        powerGuard = new PowerGuard(this, camera, new PowerGuard.Listener() {
            @Override
            public void onPowerWarning(String warning) {
                thermalWarning = warning;
                publish(true);
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
            if (enterForeground(startId)) {
                camera.post(() -> {
                    handledStartId = startId;
                    startSession();
                });
            }
        } else if (ACTION_STOP.equals(action)) {
            camera.post(() -> {
                handledStartId = startId;
                stopSession(SessionState.StopReason.USER, null);
            });
        } else {
            // Unknown or null intent: there is no session to resume after a restart.
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
        // Bounded shutdown handshake, all decided on the camera thread:
        // 1. stop the session and close the camera behind every callback already queued, so a
        //    JPEG delivered just before the stop is still handed to the disk thread;
        // 2. shut the disk executor down and keep the camera looper running until it has
        //    terminated, so the onSaved/onSaveFailed it posts are applied and persisted;
        // 3. then quit the looper. The main thread waits a bounded time (no ANR risk).
        final long deadline = SystemClock.uptimeMillis() + SHUTDOWN_DRAIN_MS;
        camera.post(() -> {
            if (active) {
                stopSession(SessionState.StopReason.PROCESS_ENDED, null);
            }
            controller.close();
            disk.shutdown();
            quitWhenDiskIdle(deadline);
        });
        try {
            cameraThread.join(SHUTDOWN_DRAIN_MS + SHUTDOWN_MARGIN_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (cameraThread.isAlive()) {
            Log.w(TAG, "Camera thread did not finish shutting down in time");
            cameraThread.quitSafely();
        }
        // Normally a no-op; covers a camera thread that never ran the shutdown step.
        disk.shutdown();
        if (!disk.isTerminated()) {
            // The write continues on its own thread; only its state update can be missed.
            Log.w(TAG, "Photo write still running when the service was destroyed");
        }
        orientationListener.disable();
        releaseWakeLock();
        SessionState.get().setServiceAlive(false);
        super.onDestroy();
    }

    /** Camera thread: quits the looper once the disk thread is done, or at the deadline. */
    private void quitWhenDiskIdle(long deadline) {
        if (disk.isTerminated() || SystemClock.uptimeMillis() >= deadline) {
            // quitSafely still delivers every message already due, including a result the disk
            // thread posted just before terminating.
            cameraThread.quitSafely();
            return;
        }
        camera.postDelayed(() -> quitWhenDiskIdle(deadline), SHUTDOWN_POLL_MS);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private boolean enterForeground(int startId) {
        try {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
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
    // Session start / stop (camera thread)
    // -------------------------------------------------------------------------------------------

    private void startSession() {
        if (active) {
            return;
        }
        settings = new SettingsStore(this).load();
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
        LensProbe.Target t = LensProbe.target(cameraManager, settings.lens, new SettingsStore(this));
        if (t == null) {
            finishWithoutSession(SessionState.StopReason.NO_CAMERA, null);
            return;
        }
        if (!useTarget(cameraManager, t)) {
            finishWithoutSession(SessionState.StopReason.CAMERA_ERROR, "No supported JPEG size");
            return;
        }
        if (t.fellBack) {
            lensWarning = getString(R.string.warning_lens_fallback);
        }

        boolean highResolution = CameraController.isHighResolutionOnly(cameraManager, cameraId, size);
        Log.i(TAG, "Session start: " + target + ", " + size
                + (highResolution ? " (high-resolution list)" : "") + ", interval "
                + settings.intervalSeconds + " s, quality " + settings.jpegQuality
                + ", max shots " + settings.maxShots + ", max duration " + settings.maxDurationSeconds + " s");
        active = true;
        sessionId++;
        long nowElapsed = SystemClock.elapsedRealtime();
        data.status = SessionState.Status.STARTING;
        data.intervalMs = settings.intervalSeconds * 1000L;
        data.maxShots = settings.maxShots;
        data.startedWallMs = System.currentTimeMillis();
        data.startedElapsedMs = nowElapsed;
        limits = new SessionLimits(settings.maxShots,
                Settings.times(settings.maxDurationSeconds, 1000L), nowElapsed);
        data.endElapsedMs = limits.hasDurationLimit() ? limits.endTimeMs() : 0;
        SessionState.persistStarted(this, data);

        acquireWakeLock();
        main.post(() -> orientationListener.enable());
        powerGuard.start();

        // Runs before any photo write (single disk thread), so the count excludes this session.
        final int id = sessionId;
        runOnDisk(() -> {
            int removed = store.removeIncompleteWrites();
            if (removed > 0) {
                Log.i(TAG, "Removed " + removed + " incomplete write(s) left by an earlier session");
            }
            long count = store.countPhotos();
            camera.post(() -> {
                if (id == sessionId) {
                    baseStoredPhotos = count;
                    data.storedPhotos = count >= 0 ? count + data.shots : -1;
                    publish(true);
                }
            });
        });

        publish(true);
        openCamera();
    }

    private void resetSessionData() {
        long stored = SessionState.get().current().storedPhotos;
        data.setFrom(new SessionState.Data());
        data.storedPhotos = stored;
        scheduler = null;
        target = null;
        lensCheck = null;
        captureInFlight = false;
        saving = false;
        consecutiveCaptureFailures = 0;
        consecutiveSaveFailures = 0;
        reopenAttempt = 0;
        reopenPending = false;
        baseStoredPhotos = -1;
        resolutionWarning = null;
        lensWarning = null;
        thermalWarning = null;
        recoveryWarning = null;
    }

    /**
     * Makes {@code t} the session's camera: id, photo size for that camera (the saved size, or
     * the largest with a visible warning), sensor orientation and the labels shown on screen.
     * Returns false when the camera reports no JPEG size.
     */
    private boolean useTarget(CameraManager cameraManager, LensProbe.Target t) {
        Resolution chosen = Resolution.choose(CameraController.jpegSizes(cameraManager, t.cameraId),
                settings.width, settings.height);
        if (chosen == null) {
            return false;
        }
        target = t;
        cameraId = t.cameraId;
        lensCheck = t.checkPhysicalId;
        size = chosen;
        resolutionWarning = !settings.usesLargestResolution()
                && (size.width != settings.width || size.height != settings.height)
                ? getString(R.string.warning_resolution_fallback, size.shortLabel()) : null;
        sensorOrientation = CameraController.sensorOrientation(cameraManager, cameraId);
        data.resolutionLabel = size.shortLabel();
        data.lensLabel = t.choice != null && t.offered > 1 ? t.factorLabel() : null;
        return true;
    }

    private void stopSession(SessionState.StopReason reason, String detail) {
        if (!active) {
            if (reason == SessionState.StopReason.USER) {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf(handledStartId);
            }
            return;
        }
        Log.i(TAG, "Session stop: " + reason + (detail == null ? "" : " (" + detail + ")")
                + ", shots " + data.shots + ", skipped " + data.skipped + ", failures " + data.failures);
        active = false;
        camera.removeCallbacks(tick);
        camera.removeCallbacks(reopen);
        controller.close();
        captureInFlight = false;
        main.post(() -> orientationListener.disable());
        powerGuard.stop();
        thermalWarning = null;
        releaseWakeLock();

        data.status = SessionState.Status.STOPPED;
        data.stopReason = reason;
        data.stopDetail = detail;
        recoveryWarning = null;
        SessionState.persistStopped(this, data);
        publish(true);
        stopForeground(STOP_FOREGROUND_REMOVE);
        if (reason != SessionState.StopReason.USER) {
            showResultNotification(reason, detail);
        }
        stopSelf(handledStartId);
    }

    /** Ends a start attempt that never became a session. */
    private void finishWithoutSession(SessionState.StopReason reason, String detail) {
        Log.w(TAG, "Session not started: " + reason + (detail == null ? "" : " (" + detail + ")"));
        data.status = SessionState.Status.STOPPED;
        data.stopReason = reason;
        data.stopDetail = detail;
        SessionState.persistStopped(this, data);
        publish(true);
        stopForeground(STOP_FOREGROUND_REMOVE);
        showResultNotification(reason, detail);
        stopSelf(handledStartId);
    }

    // -------------------------------------------------------------------------------------------
    // Camera
    // -------------------------------------------------------------------------------------------

    private void openCamera() {
        boolean continuous = data.intervalMs <= CONTINUOUS_METERING_MAX_INTERVAL_MS;
        Log.i(TAG, "Opening camera " + cameraId + (continuous ? " (continuous metering)" : " (per-shot metering)"));
        controller.open(cameraId, target.zoomRatio, lensCheck, size, settings.jpegQuality, continuous);
    }

    @Override
    public void onReady() {
        if (!active) {
            controller.close();
            return;
        }
        reopenAttempt = 0;
        reopenPending = false;
        consecutiveCaptureFailures = 0;
        recoveryWarning = null;
        if (lensCheck != null) {
            // The module was confirmed: no check on later reopens, nor in Preview.
            if (target.choice != null && target.choice.checkKey() != null) {
                new SettingsStore(this).putLensCheck(target.choice.checkKey(), true);
            }
            lensCheck = null;
        }
        if (scheduler == null) {
            scheduler = new IntervalScheduler(SystemClock.elapsedRealtime(), data.intervalMs);
        }
        data.status = SessionState.Status.RUNNING;
        publish(true);
        scheduleNextTick();
    }

    /**
     * The chosen lens turned out to be digital zoom on this phone (its module never became
     * active). As in Preview: it is not offered again, the main lens becomes the choice, and the
     * session continues on it with a visible warning. No photo was taken on the wrong lens:
     * the check runs before the session is ready.
     */
    private void fallBackToMainLens(String detail) {
        LensCatalog.Choice failed = target == null ? null : target.choice;
        SettingsStore prefs = new SettingsStore(this);
        if (failed != null && failed.checkKey() != null) {
            prefs.putLensCheck(failed.checkKey(), false);
        }
        prefs.putLensKey(LensCatalog.MAIN_KEY);
        CameraManager cameraManager = getSystemService(CameraManager.class);
        LensProbe.Target main = LensProbe.target(cameraManager, Settings.Lens.BACK, prefs);
        if (main == null || main.checkPhysicalId != null || !useTarget(cameraManager, main)) {
            stopSession(SessionState.StopReason.CAMERA_ERROR, detail);
            return;
        }
        lensWarning = failed == null ? null
                : getString(R.string.warning_lens_digital_zoom, LensCatalog.formatFactor(failed.factor));
        Log.i(TAG, "Lens fallback: " + target + ", " + size);
        publish(true);
        openCamera();
    }

    private void reopenCamera() {
        reopenPending = false;
        if (!active) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            stopSession(SessionState.StopReason.PERMISSION, null);
            return;
        }
        openCamera();
    }

    private void scheduleReopen(CameraController.Error error, String detail) {
        if (!active || reopenPending) {
            return;
        }
        if (reopenAttempt >= REOPEN_DELAYS_MS.length) {
            boolean unavailable = error == CameraController.Error.IN_USE
                    || error == CameraController.Error.DISCONNECTED
                    || error == CameraController.Error.DISABLED;
            stopSession(unavailable ? SessionState.StopReason.CAMERA_UNAVAILABLE
                    : SessionState.StopReason.CAMERA_ERROR, detail);
            return;
        }
        long delay = REOPEN_DELAYS_MS[reopenAttempt++];
        reopenPending = true;
        data.status = SessionState.Status.RECOVERING;
        recoveryWarning = getString(R.string.warning_recovering, error.name(), reopenAttempt,
                REOPEN_DELAYS_MS.length);
        publish(true);
        camera.postDelayed(reopen, delay);
        // Ticks keep running so missed intervals are counted as skipped.
        if (scheduler != null) {
            scheduleNextTick();
        }
    }

    @Override
    public void onError(CameraController.Error error, String detail) {
        Log.w(TAG, "Camera error " + error + ": " + detail);
        if (captureInFlight) {
            captureInFlight = false;
            data.failures++;
        }
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
        if (error == CameraController.Error.CONFIGURATION && scheduler == null) {
            // The chosen size/stream combination is not accepted; retrying cannot fix it.
            stopSession(SessionState.StopReason.CAMERA_ERROR, detail + " — " + size.label());
            return;
        }
        scheduleReopen(error, detail);
    }

    @Override
    public void onCaptureFailed(String reason) {
        Log.w(TAG, "Capture failed: " + reason);
        captureInFlight = false;
        data.failures++;
        if (!active) {
            return;
        }
        consecutiveCaptureFailures++;
        if (consecutiveCaptureFailures >= MAX_CONSECUTIVE_CAPTURE_FAILURES) {
            consecutiveCaptureFailures = 0;
            controller.close();
            scheduleReopen(CameraController.Error.DEVICE, reason);
        } else {
            publish(false);
        }
    }

    // -------------------------------------------------------------------------------------------
    // Scheduling
    // -------------------------------------------------------------------------------------------

    private void scheduleNextTick() {
        camera.removeCallbacks(tick);
        if (!active || scheduler == null) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        long delay = scheduler.delayUntilNext(now);
        long remaining = limits.remainingMs(now);
        if (remaining >= 0) {
            delay = Math.min(delay, remaining);
        }
        camera.postDelayed(tick, delay);
    }

    private void onTick() {
        if (!active) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (limits.durationReached(now)) {
            stopSession(SessionState.StopReason.MAX_DURATION, null);
            return;
        }
        boolean busy = captureInFlight || saving || !controller.isReady();
        IntervalScheduler.Decision decision = scheduler.onTick(now, busy);
        data.skipped += decision.skipped;
        if (decision.skipped > 0) {
            Log.d(TAG, "Tick skipped " + decision.skipped + (busy ? " (busy)" : " (late)"));
        }
        if (decision.capture) {
            long free = PhotoStore.freeBytes();
            if (free >= 0 && free < settings.minFreeBytes) {
                stopSession(SessionState.StopReason.LOW_STORAGE,
                        Formatter.formatShortFileSize(this, free));
                return;
            }
            captureInFlight = true;
            int orientation = JpegOrientation.compute(sensorOrientation, deviceQuadrant,
                    settings.lens == Settings.Lens.FRONT);
            if (!controller.capture(orientation)) {
                captureInFlight = false;
                data.skipped++;
            }
        }
        if (!active) {
            return; // a synchronous camera failure stopped the session
        }
        if (decision.skipped > 0 || !decision.capture) {
            publish(false);
        }
        scheduleNextTick();
    }

    // -------------------------------------------------------------------------------------------
    // Saving
    // -------------------------------------------------------------------------------------------

    @Override
    public void onJpeg(byte[] jpeg) {
        captureInFlight = false;
        saving = true;
        final int id = sessionId;
        final long wall = System.currentTimeMillis();
        final String name = PhotoNames.newName(wall, random);
        boolean queued = runOnDisk(() -> {
            try {
                Uri uri = store.save(name, jpeg, wall);
                camera.post(() -> onSaved(id, uri, jpeg.length, wall));
            } catch (IOException e) {
                camera.post(() -> onSaveFailed(id, e));
            }
        });
        if (!queued) {
            saving = false;
            data.failures++;
        }
    }

    /**
     * Queues work on the disk thread. Returns false, instead of throwing on the camera thread,
     * if the executor has already been shut down by {@link #onDestroy()}.
     */
    private boolean runOnDisk(Runnable work) {
        try {
            disk.execute(work);
            return true;
        } catch (RejectedExecutionException e) {
            Log.w(TAG, "Disk executor already shut down; work dropped");
            return false;
        }
    }

    private void onSaved(int id, Uri uri, int length, long wall) {
        if (id != sessionId) {
            return; // photo is committed; it belonged to an earlier session
        }
        saving = false;
        consecutiveSaveFailures = 0;
        consecutiveCaptureFailures = 0;
        data.shots++;
        Log.d(TAG, "Photo " + data.shots + " saved, " + length + " bytes");
        data.sessionBytes += length;
        data.lastCaptureWallMs = wall;
        data.lastPhotoUri = uri.toString();
        if (baseStoredPhotos >= 0) {
            data.storedPhotos = baseStoredPhotos + data.shots;
        }
        if (!active) {
            // Saved after the session was stopped: record it, the photo is complete.
            SessionState.persistStopped(this, data);
            publish(true);
            return;
        }
        SessionState.persistProgress(this, data);
        if (limits.shotsReached(data.shots)) {
            stopSession(SessionState.StopReason.MAX_SHOTS, null);
            return;
        }
        publish(false);
    }

    private void onSaveFailed(int id, IOException e) {
        if (id != sessionId) {
            return;
        }
        saving = false;
        data.failures++;
        if (!active) {
            publish(true);
            return;
        }
        long free = PhotoStore.freeBytes();
        if (free >= 0 && free < settings.minFreeBytes) {
            stopSession(SessionState.StopReason.LOW_STORAGE, Formatter.formatShortFileSize(this, free));
            return;
        }
        consecutiveSaveFailures++;
        if (consecutiveSaveFailures >= MAX_CONSECUTIVE_SAVE_FAILURES) {
            stopSession(SessionState.StopReason.STORAGE_ERROR, e.getMessage());
            return;
        }
        publish(false);
    }

    // -------------------------------------------------------------------------------------------
    // Wake lock (battery and heat: PowerGuard)
    // -------------------------------------------------------------------------------------------

    // No timeout on purpose: a session may legitimately run for up to 30 days, and the lock is
    // released on every exit path (stopSession, onDestroy); the system drops it if the process dies.
    @SuppressLint("WakelockTimeout")
    private void acquireWakeLock() {
        if (wakeLock == null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SentryCam:capture");
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

    // -------------------------------------------------------------------------------------------
    // State publishing and notifications
    // -------------------------------------------------------------------------------------------

    private void publish(boolean forceNotification) {
        data.warning = joinWarnings();
        SessionState.get().publish(data);
        if (!active) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (forceNotification || now - lastNotificationMs >= NOTIFICATION_MIN_INTERVAL_MS) {
            lastNotificationMs = now;
            notifications.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    private String joinWarnings() {
        StringBuilder sb = new StringBuilder();
        for (String w : new String[] {recoveryWarning, lensWarning, resolutionWarning, thermalWarning}) {
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
        return PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification buildNotification() {
        SessionState.Data d = data;
        String title;
        if (d.status == SessionState.Status.RECOVERING) {
            title = getString(R.string.notification_title_recovering);
        } else if (d.status == SessionState.Status.RUNNING) {
            title = getString(R.string.notification_title_running);
        } else {
            title = getString(R.string.notification_title_starting);
        }
        String text = d.lastCaptureWallMs > 0 && d.shots > 0
                ? Texts.plural(this, R.plurals.notification_text, d.shots,
                        formatTime(this, d.lastCaptureWallMs))
                : getString(R.string.notification_text_none);

        Intent stop = new Intent(this, CaptureService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_capture)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .setContentIntent(openAppIntent())
                .addAction(new Notification.Action.Builder(null, getString(R.string.action_stop),
                        stopIntent).build())
                .build();
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

    /** Local time with seconds, in the user's 12/24-hour preference. */
    static String formatTime(Context context, long wallMs) {
        String skeleton = DateFormat.is24HourFormat(context) ? "Hms" : "hms";
        String pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton);
        return DateFormat.format(pattern, wallMs).toString();
    }
}
