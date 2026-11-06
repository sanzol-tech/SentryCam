package ar.sanzol.sentrycam;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.hardware.camera2.CameraManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toolbar;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Operation screen: Photo | Video mode, real session state, two operational cards,
 * three context rows (camera, resolution, free storage), and Preview + Start/Stop. The mode
 * chosen here decides which service Start runs and which settings apply. The live view is a
 * separate screen ({@link PreviewActivity}).
 */
public final class MainActivity extends Activity implements SessionState.Listener {

    private static final int REQUEST_PERMISSIONS = 1;
    private static final long TICKER_MS = 1_000;
    private static final String PREF_ASKED_NOTIFICATIONS = "asked_notifications";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> new Thread(r, "ui-io"));

    private LinearLayout modeChips;
    private View statusDot;
    private TextView statusTitle;
    private TextView statusDetail;
    private TextView warning;
    private TextView card1Label;
    private TextView card1Value;
    private TextView card1Note;
    private TextView card2Label;
    private TextView card2Value;
    private TextView card2Note;
    private TextView contextCamera;
    private TextView contextResolution;
    private TextView contextFree;
    private Button previewButton;
    private Button startStop;
    private Button openAppSettings;

    private SettingsStore store;
    private Settings settings;
    private VideoSettings video;
    /** The mode the user chose; a running session shows its own mode instead (see displayMode). */
    private Settings.Mode mode = Settings.Mode.PHOTO;
    private String modeChipsShown;
    private SessionState.Data shown = new SessionState.Data();
    /** Context while stopped, read from the camera off the main thread; null until known. */
    private String idleCamera;
    private String idleResolution;
    /** Bumped by every context load, so a late result never overwrites a newer one. */
    private int contextRequest;
    private String permissionMessage;
    private boolean resumed;
    /** Between onStart and onStop: the screen is visible, so a foreground service may start. */
    private boolean visible;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!resumed) {
                return;
            }
            render(shown);
            main.postDelayed(this, TICKER_MS);
        }
    };

    // -------------------------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Ui.padForSystemBars(findViewById(R.id.root));
        bindViews();

        startStop.setOnClickListener(v -> onStartStopClicked());
        previewButton.setOnClickListener(v -> {
            if (!isActive(shown)) {
                startActivity(new Intent(this, PreviewActivity.class));
            }
        });
        if (savedInstanceState == null) {
            // Diagnostics: which back lenses this phone exposes (log tag SentryCam).
            io.execute(() -> LensProbe.logLenses(getSystemService(CameraManager.class)));
        }
        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.inflateMenu(R.menu.menu_main);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_about) {
                startActivity(new Intent(this, AboutActivity.class));
                return true;
            }
            if (item.getItemId() == R.id.action_settings) {
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
            }
            return false;
        });
        openAppSettings.setOnClickListener(v -> startActivity(new Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null))));
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        store = new SettingsStore(this);
        settings = store.load();
        video = store.loadVideo();
        mode = store.getMode();
        modeChipsShown = null;
        loadContext();
        SessionState state = SessionState.get();
        state.addListener(this);
        shown = state.restoreIfIdle(this);
        if (permissionMessage != null && hasCameraPermission()) {
            permissionMessage = null;
            openAppSettings.setVisibility(View.GONE);
        }
        main.removeCallbacks(ticker);
        main.post(ticker);
    }

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
    }

    @Override
    protected void onStop() {
        visible = false;
        super.onStop();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        SessionState.get().removeListener(this);
        main.removeCallbacks(ticker);
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onSessionChanged(SessionState.Data data) {
        boolean wasActive = isActive(shown);
        shown = data;
        if (wasActive && !isActive(data)) {
            // The session may have dropped a lens that turned out to be digital zoom.
            loadContext();
        }
        render(data);
    }

    private void bindViews() {
        modeChips = findViewById(R.id.mode_chips);
        statusDot = findViewById(R.id.status_dot);
        statusTitle = findViewById(R.id.status_title);
        statusDetail = findViewById(R.id.status_detail);
        warning = findViewById(R.id.warning);
        card1Label = findViewById(R.id.card1_label);
        card1Value = findViewById(R.id.card1_value);
        card1Note = findViewById(R.id.card1_note);
        card2Label = findViewById(R.id.card2_label);
        card2Value = findViewById(R.id.card2_value);
        card2Note = findViewById(R.id.card2_note);
        contextCamera = findViewById(R.id.context_camera);
        contextResolution = findViewById(R.id.context_resolution);
        contextFree = findViewById(R.id.context_free);
        previewButton = findViewById(R.id.preview_button);
        startStop = findViewById(R.id.start_stop);
        openAppSettings = findViewById(R.id.open_app_settings);
    }

    // -------------------------------------------------------------------------------------------
    // Start / Stop and permissions
    // -------------------------------------------------------------------------------------------

    private void onStartStopClicked() {
        // The action always matches the button label, which follows the published state.
        if (isActive(shown)) {
            // Stop goes to the service of the running session's mode.
            startService(shown.mode == Settings.Mode.VIDEO
                    ? new Intent(this, VideoService.class).setAction(VideoService.ACTION_STOP)
                    : new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_STOP));
            return;
        }
        List<String> missing = new ArrayList<>();
        if (!hasCameraPermission()) {
            missing.add(Manifest.permission.CAMERA);
        }
        // Audio is optional: if the microphone is refused, the video is recorded without it.
        if (mode == Settings.Mode.VIDEO && video.audio
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.RECORD_AUDIO);
        }
        // POST_NOTIFICATIONS exists from API 33; below it notifications need no runtime grant.
        boolean notificationsMissing = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED;
        // Notifications are optional: ask once, then start without them if declined.
        if (notificationsMissing && !getPreferences(MODE_PRIVATE).getBoolean(PREF_ASKED_NOTIFICATIONS, false)) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (missing.isEmpty()) {
            startCapture();
        } else {
            requestPermissions(missing.toArray(new String[0]), REQUEST_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_PERMISSIONS) {
            return;
        }
        for (String p : permissions) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && Manifest.permission.POST_NOTIFICATIONS.equals(p)) {
                getPreferences(MODE_PRIVATE).edit().putBoolean(PREF_ASKED_NOTIFICATIONS, true).apply();
            }
        }
        if (hasCameraPermission()) {
            permissionMessage = null;
            openAppSettings.setVisibility(View.GONE);
            startCapture();
        } else if (!shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            permissionMessage = getString(R.string.permission_camera_denied);
            openAppSettings.setVisibility(View.VISIBLE);
        } else {
            permissionMessage = getString(R.string.permission_camera_needed);
        }
        render(shown);
    }

    private boolean hasCameraPermission() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Starts a session once no Camera Preview holds the camera: coming back from the preview,
     * its camera may still be closing for a moment.
     */
    private void startCapture() {
        PreviewCamera.whenReleased(() -> {
            // If the user left the screen meanwhile, Android 12+ no longer allows starting a
            // foreground service from here, so the start is dropped (the screen still says Ready).
            if (visible && !isDestroyed()) {
                startCaptureNow();
            }
        });
    }

    private void startCaptureNow() {
        startForegroundService(mode == Settings.Mode.VIDEO
                ? new Intent(this, VideoService.class).setAction(VideoService.ACTION_START)
                : new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_START));
    }

    /** The running session's mode, or the chosen mode while stopped. */
    private Settings.Mode displayMode(SessionState.Data d) {
        return isActive(d) ? d.mode : mode;
    }

    /** Whether {@code d}'s numbers belong to the mode on screen (not the other mode's last session). */
    private boolean ownData(SessionState.Data d) {
        return d.mode == displayMode(d);
    }

    private static boolean isActive(SessionState.Data d) {
        return d.status != SessionState.Status.STOPPED;
    }

    // -------------------------------------------------------------------------------------------
    // Rendering
    // -------------------------------------------------------------------------------------------

    private void render(SessionState.Data d) {
        boolean active = isActive(d);
        long now = SystemClock.elapsedRealtime();
        renderModeChips(d, active);
        renderStatus(d, active);
        if (displayMode(d) == Settings.Mode.VIDEO) {
            renderVideoCards(ownData(d) ? d : new SessionState.Data(), active, now);
        } else {
            renderPhotoCards(ownData(d) ? d : new SessionState.Data(), active, now);
        }
        renderContext(d, active);

        previewButton.setEnabled(!active);
        startStop.setCompoundDrawablesRelativeWithIntrinsicBounds(
                active ? R.drawable.ic_stop : R.drawable.ic_play, 0, 0, 0);
        startStop.setText(active ? R.string.action_stop : R.string.action_start);
    }

    private void renderStatus(SessionState.Data d, boolean active) {
        int title;
        int color;
        switch (d.status) {
            case RUNNING:
                title = d.mode == Settings.Mode.VIDEO ? R.string.status_recording : R.string.status_running;
                color = R.color.status_running;
                break;
            case WAITING:
                title = R.string.status_waiting;
                color = R.color.status_busy;
                break;
            case STARTING:
                title = R.string.status_starting;
                color = R.color.status_busy;
                break;
            case RECOVERING:
                title = R.string.status_recovering;
                color = R.color.status_busy;
                break;
            default:
                boolean problem = isProblem(d.stopReason) || permissionMessage != null;
                title = d.stopReason == null ? R.string.status_ready : R.string.status_stopped;
                color = problem ? R.color.status_problem : R.color.status_idle;
        }
        statusTitle.setText(title);
        statusDot.setBackgroundTintList(ColorStateList.valueOf(getColor(color)));

        String detail;
        if (active) {
            detail = problemsLine(d);
        } else if (permissionMessage != null) {
            detail = permissionMessage;
        } else {
            detail = Texts.stopReason(this, d.stopReason, d.stopDetail);
        }
        statusDetail.setText(detail);
        statusDetail.setVisibility(detail == null || detail.isEmpty() ? View.GONE : View.VISIBLE);

        warning.setText(d.warning);
        warning.setVisibility(active && d.warning != null ? View.VISIBLE : View.GONE);
    }

    /** "2 skipped · 1 failed" during a session, only when there are any; null otherwise. */
    private String problemsLine(SessionState.Data d) {
        List<String> parts = new ArrayList<>();
        if (d.skipped > 0) {
            parts.add(getString(R.string.status_skipped, formatCount(d.skipped)));
        }
        if (d.failures > 0) {
            parts.add(getString(R.string.status_failed, formatCount(d.failures)));
        }
        return parts.isEmpty() ? null : String.join(" · ", parts);
    }

    private static boolean isProblem(SessionState.StopReason r) {
        return r != null && r != SessionState.StopReason.USER
                && r != SessionState.StopReason.MAX_SHOTS && r != SessionState.StopReason.MAX_DURATION;
    }

    /** Photos this session (interval in the note), and the limit or what is left of it. */
    private void renderPhotoCards(SessionState.Data d, boolean active, long now) {
        card1Label.setText(R.string.tile_photos);
        card1Value.setText(formatCount(d.shots));
        if (active && d.shots > 0 && d.startedElapsedMs > 0) {
            double actual = Math.max(0, now - d.startedElapsedMs) / 1000.0 / d.shots;
            setNote(card1Note, getString(R.string.card_actual, Texts.interval(actual)));
        } else {
            long intervalSeconds = active && d.intervalMs > 0 ? d.intervalMs / 1000 : settings.intervalSeconds;
            setNote(card1Note, getString(R.string.card_every, Texts.duration(intervalSeconds)));
        }

        if (active) {
            boolean timeLimit = d.endElapsedMs > 0;
            boolean shotLimit = d.maxShots > 0;
            long shotsLeft = Math.max(0, d.maxShots - d.shots);
            if (timeLimit) {
                card2Label.setText(R.string.tile_left);
                card2Value.setText(Texts.duration(Math.max(0, d.endElapsedMs - now) / 1000));
                setNote(card2Note, shotLimit ? getString(R.string.tile_photos_left, formatCount(shotsLeft)) : null);
            } else if (shotLimit) {
                card2Label.setText(R.string.tile_left);
                card2Value.setText(formatCount(shotsLeft));
                setNote(card2Note, null);
            } else {
                card2Label.setText(R.string.tile_elapsed);
                card2Value.setText(Texts.duration(Math.max(0, now - d.startedElapsedMs) / 1000));
                setNote(card2Note, getString(R.string.unlimited));
            }
        } else {
            card2Label.setText(R.string.tile_limit);
            if (settings.hasDurationLimit()) {
                card2Value.setText(Texts.duration(settings.maxDurationSeconds));
                setNote(card2Note, settings.hasShotLimit()
                        ? getString(R.string.tile_photos_left, formatCount(settings.maxShots)) : null);
            } else if (settings.hasShotLimit()) {
                card2Value.setText(formatCount(settings.maxShots));
                setNote(card2Note, null);
            } else {
                card2Value.setText(R.string.unlimited);
                setNote(card2Note, null);
            }
        }
    }

    /** Time recorded (files in the note), and the limit, what is left, or the delayed start. */
    private void renderVideoCards(SessionState.Data d, boolean active, long now) {
        card1Label.setText(R.string.tile_recorded);
        card1Value.setText(Texts.duration(d.recordedMsAt(now) / 1000));
        setNote(card1Note, d.files > 0
                ? Texts.plural(this, R.plurals.card_files, d.files)
                : getString(R.string.card_file_length, video.segmentMinutes));

        if (active && d.status == SessionState.Status.WAITING && d.waitUntilElapsedMs > 0) {
            card2Label.setText(R.string.tile_starts_in);
            card2Value.setText(Texts.duration((Math.max(0, d.waitUntilElapsedMs - now) + 999) / 1000));
            setNote(card2Note, null);
        } else if (active) {
            if (d.endElapsedMs > 0) {
                card2Label.setText(R.string.tile_left);
                card2Value.setText(Texts.duration(Math.max(0, d.endElapsedMs - now) / 1000));
                setNote(card2Note, null);
            } else {
                card2Label.setText(R.string.tile_elapsed);
                card2Value.setText(Texts.duration(Math.max(0, now - d.startedElapsedMs) / 1000));
                setNote(card2Note, getString(R.string.unlimited));
            }
        } else {
            card2Label.setText(R.string.tile_limit);
            card2Value.setText(video.hasDurationLimit() ? Texts.duration(video.maxDurationSeconds)
                    : getString(R.string.unlimited));
            setNote(card2Note, video.hasDelay()
                    ? getString(R.string.card_starts_after, Texts.duration(video.delaySeconds)) : null);
        }
    }

    private static void setNote(TextView note, String text) {
        note.setText(text);
        note.setVisibility(text == null ? View.INVISIBLE : View.VISIBLE);
    }

    /**
     * Camera, resolution and free storage. During a session, what the session really uses (it
     * may have fallen back); while stopped, what Start will use, read from the camera.
     */
    private void renderContext(SessionState.Data d, boolean active) {
        String camera;
        String resolution;
        if (active) {
            camera = d.lensLabel != null ? getString(R.string.detail_camera_back_factor, d.lensLabel)
                    : getString(settings.lens == Settings.Lens.FRONT ? R.string.camera_front : R.string.camera_back);
            resolution = d.resolutionLabel;
        } else {
            camera = idleCamera;
            resolution = idleResolution;
        }
        contextCamera.setText(camera != null ? camera : getString(R.string.unknown));
        contextResolution.setText(resolution != null ? resolution : getString(R.string.unknown));
        long free = PhotoStore.freeBytes();
        contextFree.setText(free >= 0 ? Formatter.formatShortFileSize(this, free) : getString(R.string.unknown));
    }

    /**
     * Reads, off the main thread, the camera and size Start will use in the chosen mode: the
     * same rule as the preview and the services (LensProbe.target, then the saved size or the
     * largest one).
     */
    private void loadContext() {
        final int req = ++contextRequest;
        final Settings s = settings;
        final VideoSettings v = video;
        final boolean videoMode = mode == Settings.Mode.VIDEO;
        final String lensKey = store.getLensKey();
        final Set<String> failed = store.getFailedLensChecks();
        final Set<String> passed = store.getPassedLensChecks();
        io.execute(() -> {
            CameraManager manager = getSystemService(CameraManager.class);
            LensProbe.Target t = LensProbe.target(manager, s.lens, lensKey, failed, passed);
            String camera = null;
            String resolution = null;
            if (t != null) {
                camera = t.choice != null && t.offered > 1
                        ? getString(R.string.detail_camera_back_factor, t.factorLabel())
                        : getString(s.lens == Settings.Lens.FRONT ? R.string.camera_front : R.string.camera_back);
                Resolution size = videoMode
                        ? Resolution.choose(VideoCaps.videoSizes(manager, t.cameraId), v.width, v.height)
                        : Resolution.choose(CameraController.jpegSizes(manager, t.cameraId), s.width, s.height);
                resolution = size == null ? null : size.shortLabel();
            }
            final String cameraLabel = camera;
            final String resolutionLabel = resolution;
            main.post(() -> {
                if (req != contextRequest || isDestroyed()) {
                    return;
                }
                idleCamera = cameraLabel;
                idleResolution = resolutionLabel;
                render(shown);
            });
        });
    }

    private static String formatCount(long n) {
        return String.format(Locale.getDefault(), "%,d", n);
    }

    // -------------------------------------------------------------------------------------------
    // Photo | Video mode: chosen while stopped; a session keeps its mode.
    // -------------------------------------------------------------------------------------------

    private void renderModeChips(SessionState.Data d, boolean active) {
        Settings.Mode selected = displayMode(d);
        String signature = selected + "|" + active;
        if (signature.equals(modeChipsShown)) {
            return;
        }
        modeChipsShown = signature;
        modeChips.removeAllViews();
        LayoutInflater inflater = getLayoutInflater();
        Settings.Mode[] modes = Settings.Mode.values();
        for (int i = 0; i < modes.length; i++) {
            Settings.Mode m = modes[i];
            if (i > 0) {
                View divider = new View(this);
                divider.setBackgroundColor(getColor(R.color.line));
                modeChips.addView(divider, new LinearLayout.LayoutParams(
                        getResources().getDimensionPixelSize(R.dimen.divider_thickness),
                        LinearLayout.LayoutParams.MATCH_PARENT));
            }
            Button chip = (Button) inflater.inflate(R.layout.item_segment_chip, modeChips, false);
            String name = getString(m == Settings.Mode.VIDEO ? R.string.mode_video : R.string.mode_photo);
            chip.setText(name);
            chip.setSelected(m == selected);
            chip.setEnabled(!active);
            chip.setContentDescription(getString(R.string.mode_description, name));
            chip.setOnClickListener(v -> selectMode(m));
            modeChips.addView(chip);
        }
    }

    private void selectMode(Settings.Mode m) {
        if (isActive(shown) || m == mode) {
            return;
        }
        mode = m;
        store.putMode(m);
        modeChipsShown = null;
        idleResolution = null;
        loadContext();
        render(shown);
    }
}
