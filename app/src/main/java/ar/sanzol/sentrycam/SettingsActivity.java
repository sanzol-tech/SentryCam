package ar.sanzol.sentrycam;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.hardware.camera2.CameraManager;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.Formatter;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Toolbar;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Settings and appearance, one screen for both modes: it shows the settings of the
 * mode chosen on the main screen. Photo and Video settings are stored apart; camera, free-space
 * reserve and theme are shared. While a session runs the settings are read-only; the theme can
 * always be changed. Valid settings are saved when leaving.
 */
public final class SettingsActivity extends Activity implements SessionState.Listener {

    private static final long[] INTERVAL_UNITS = {Settings.SECOND, Settings.MINUTE, Settings.HOUR};
    private static final long[] DURATION_UNITS = {Settings.MINUTE, Settings.HOUR, Settings.DAY};
    private static final long[] DELAY_UNITS = {Settings.SECOND, Settings.MINUTE, Settings.HOUR};

    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> new Thread(r, "settings-io"));

    private SettingsStore store;
    /** The mode whose settings this screen edits; fixed while the screen is open. */
    private Settings.Mode mode;

    private TextView locked;
    private TextView modeNote;
    private TextView sectionTitle;
    private View photoIntervalGroup;
    private View photoQualityGroup;
    private View photoShotsGroup;
    private View videoGroup;
    private EditText intervalValue;
    private Spinner intervalUnit;
    private RadioGroup cameraGroup;
    private RadioButton cameraBack;
    private RadioButton cameraFront;
    private Spinner resolutionSpinner;
    private TextView resolutionNote;
    private SeekBar quality;
    private TextView qualityValue;
    private RadioGroup videoQuality;
    private RadioGroup fileLength;
    private Switch videoAudio;
    private Switch videoDelay;
    private View delayRow;
    private EditText delayValue;
    private Spinner delayUnit;
    private Switch limitShots;
    private EditText maxShots;
    private Switch limitDuration;
    private View durationRow;
    private EditText durationValue;
    private Spinner durationUnit;
    private EditText minFree;
    private TextView estimate;
    private RadioGroup themeGroup;

    private final Map<Settings.Lens, List<Resolution>> photoSizesByLens = new EnumMap<>(Settings.Lens.class);
    private final Map<Settings.Lens, List<Resolution>> videoSizesByLens = new EnumMap<>(Settings.Lens.class);
    /** Sizes of the current mode and camera, as listed after "Largest". */
    private List<Resolution> currentSizes = new ArrayList<>();
    /** The chosen back lens ("0.5") when the phone offers several; null otherwise. */
    private String backLensFactor;
    /** H.264 encoder bitrate range, for the video estimate. */
    private int[] encoderRange = {0, 0};
    private boolean camerasLoaded;
    private Settings saved;
    private VideoSettings savedVideo;
    private boolean editable = true;
    private boolean suppress;

    // -------------------------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        Ui.padForSystemBars(findViewById(R.id.root));
        store = new SettingsStore(this);
        mode = store.getMode();
        bindViews();

        setupSpinner(intervalUnit, new int[] {R.string.unit_seconds, R.string.unit_minutes, R.string.unit_hours});
        setupSpinner(durationUnit, new int[] {R.string.unit_minutes, R.string.unit_hours, R.string.unit_days});
        setupSpinner(delayUnit, new int[] {R.string.unit_seconds, R.string.unit_minutes, R.string.unit_hours});
        int[] lengthIds = {R.id.file_length_5, R.id.file_length_10, R.id.file_length_15};
        for (int i = 0; i < lengthIds.length; i++) {
            ((RadioButton) findViewById(lengthIds[i])).setText(
                    getString(R.string.file_length_minutes, VideoSettings.SEGMENT_MINUTES[i]));
        }
        applyModeVisibility();

        saved = store.load();
        savedVideo = store.loadVideo();
        // The form keeps its own state across recreation (e.g. a theme change).
        if (savedInstanceState == null) {
            fillForm(saved, savedVideo);
        }
        attachListeners();
        selectTheme(store.getTheme());
        ((Toolbar) findViewById(R.id.toolbar)).setNavigationOnClickListener(v -> finish());
        loadCameras();
    }

    @Override
    protected void onResume() {
        super.onResume();
        SessionState.get().addListener(this);
        qualityValue.setText(formatNumber(quality.getProgress()));
        applyEditable(!isSessionActive(SessionState.get().current()));
        updateEstimate();
    }

    @Override
    protected void onPause() {
        super.onPause();
        SessionState.get().removeListener(this);
        saveIfEditable();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onSessionChanged(SessionState.Data data) {
        boolean canEdit = !isSessionActive(data);
        if (canEdit != editable) {
            applyEditable(canEdit);
            updateEstimate();
        }
    }

    private static boolean isSessionActive(SessionState.Data d) {
        return d.status != SessionState.Status.STOPPED || SessionState.get().isServiceAlive();
    }

    private boolean videoMode() {
        return mode == Settings.Mode.VIDEO;
    }

    private void bindViews() {
        locked = findViewById(R.id.settings_locked);
        modeNote = findViewById(R.id.mode_note);
        sectionTitle = findViewById(R.id.section_capture_title);
        photoIntervalGroup = findViewById(R.id.photo_interval_group);
        photoQualityGroup = findViewById(R.id.photo_quality_group);
        photoShotsGroup = findViewById(R.id.photo_shots_group);
        videoGroup = findViewById(R.id.video_group);
        intervalValue = findViewById(R.id.interval_value);
        intervalUnit = findViewById(R.id.interval_unit);
        cameraGroup = findViewById(R.id.camera);
        cameraBack = findViewById(R.id.camera_back);
        cameraFront = findViewById(R.id.camera_front);
        resolutionSpinner = findViewById(R.id.resolution);
        resolutionNote = findViewById(R.id.resolution_note);
        quality = findViewById(R.id.quality);
        qualityValue = findViewById(R.id.quality_value);
        videoQuality = findViewById(R.id.video_quality);
        fileLength = findViewById(R.id.file_length);
        videoAudio = findViewById(R.id.video_audio);
        videoDelay = findViewById(R.id.video_delay);
        delayRow = findViewById(R.id.delay_row);
        delayValue = findViewById(R.id.delay_value);
        delayUnit = findViewById(R.id.delay_unit);
        limitShots = findViewById(R.id.limit_shots);
        maxShots = findViewById(R.id.max_shots);
        limitDuration = findViewById(R.id.limit_duration);
        durationRow = findViewById(R.id.duration_row);
        durationValue = findViewById(R.id.duration_value);
        durationUnit = findViewById(R.id.duration_unit);
        minFree = findViewById(R.id.min_free);
        estimate = findViewById(R.id.estimate);
        themeGroup = findViewById(R.id.theme);
    }

    /** Shows only the current mode's groups; shared items stay. */
    private void applyModeVisibility() {
        boolean video = videoMode();
        modeNote.setText(video ? R.string.settings_mode_video : R.string.settings_mode_photo);
        sectionTitle.setText(video ? R.string.section_recording : R.string.section_capture);
        int photoVisibility = video ? View.GONE : View.VISIBLE;
        photoIntervalGroup.setVisibility(photoVisibility);
        photoQualityGroup.setVisibility(photoVisibility);
        photoShotsGroup.setVisibility(photoVisibility);
        videoGroup.setVisibility(video ? View.VISIBLE : View.GONE);
    }

    // -------------------------------------------------------------------------------------------
    // Read-only mode during a session
    // -------------------------------------------------------------------------------------------

    private void applyEditable(boolean canEdit) {
        if (!canEdit) {
            // Show exactly what the running session uses; unsaved edits are dropped.
            saved = store.load();
            savedVideo = store.loadVideo();
            fillForm(saved, savedVideo);
            if (camerasLoaded) {
                populateResolutions(selectedLens(), savedWidth(), savedHeight(), true);
            }
        }
        editable = canEdit;
        locked.setVisibility(canEdit ? View.GONE : View.VISIBLE);
        for (View v : new View[] {intervalValue, intervalUnit, cameraBack, cameraFront, resolutionSpinner,
                quality, limitShots, maxShots, limitDuration, durationValue, durationUnit, minFree,
                videoAudio, videoDelay, delayValue, delayUnit}) {
            v.setEnabled(canEdit);
        }
        for (RadioGroup g : new RadioGroup[] {videoQuality, fileLength}) {
            for (int i = 0; i < g.getChildCount(); i++) {
                g.getChildAt(i).setEnabled(canEdit);
            }
        }
        if (canEdit) {
            refreshCameraButtons();
            updateLimitFields();
        }
    }

    // -------------------------------------------------------------------------------------------
    // Form <-> settings
    // -------------------------------------------------------------------------------------------

    private int savedWidth() {
        return videoMode() ? savedVideo.width : saved.width;
    }

    private int savedHeight() {
        return videoMode() ? savedVideo.height : saved.height;
    }

    // Editable fields hold plain ASCII digits on purpose: Settings.parseWholeNumber reads them back,
    // and locale digits (e.g. Arabic-Indic) or grouping separators would not round-trip.
    @SuppressLint("SetTextI18n")
    private void fillForm(Settings s, VideoSettings v) {
        suppress = true;
        // Photo
        long iu = pickUnit(s.intervalSeconds, store.getUnit(SettingsStore.K_INTERVAL_UNIT, Settings.SECOND),
                INTERVAL_UNITS);
        intervalValue.setText(Long.toString(s.intervalSeconds / iu));
        intervalUnit.setSelection(indexOf(INTERVAL_UNITS, iu), false);
        quality.setProgress(s.jpegQuality);
        qualityValue.setText(formatNumber(s.jpegQuality));
        limitShots.setChecked(s.hasShotLimit());
        maxShots.setText(s.hasShotLimit() ? Integer.toString(s.maxShots) : "");

        // Video
        videoQuality.check(switch (v.quality) {
            case HIGH -> R.id.video_quality_high;
            case MEDIUM -> R.id.video_quality_medium;
            case LOW -> R.id.video_quality_low;
        });
        fileLength.check(v.segmentMinutes == 5 ? R.id.file_length_5
                : v.segmentMinutes == 10 ? R.id.file_length_10 : R.id.file_length_15);
        videoAudio.setChecked(v.audio);
        long dlu = pickUnit(v.delaySeconds, store.getUnit(SettingsStore.K_DELAY_UNIT, Settings.MINUTE), DELAY_UNITS);
        videoDelay.setChecked(v.hasDelay());
        delayValue.setText(v.hasDelay() ? Long.toString(v.delaySeconds / dlu) : "");
        delayUnit.setSelection(indexOf(DELAY_UNITS, dlu), false);

        // Shared widgets: camera and reserve; the duration limit of the current mode.
        cameraGroup.check(s.lens == Settings.Lens.FRONT ? R.id.camera_front : R.id.camera_back);
        long duration = videoMode() ? v.maxDurationSeconds : s.maxDurationSeconds;
        long du = pickUnit(duration, store.getUnit(durationUnitKey(), Settings.HOUR), DURATION_UNITS);
        limitDuration.setChecked(duration > 0);
        durationValue.setText(duration > 0 ? Long.toString(duration / du) : "");
        durationUnit.setSelection(indexOf(DURATION_UNITS, du), false);
        minFree.setText(Long.toString(s.minFreeBytes / Settings.MB));
        suppress = false;
        updateLimitFields();
    }

    private String durationUnitKey() {
        return videoMode() ? SettingsStore.K_VIDEO_DURATION_UNIT : SettingsStore.K_DURATION_UNIT;
    }

    /** Both modes' settings read from the form; the mode not shown keeps its saved values. */
    private static final class Form {
        final Settings photo;
        final VideoSettings video;

        Form(Settings photo, VideoSettings video) {
            this.photo = photo;
            this.video = video;
        }
    }

    /**
     * Reads and validates the fields of the current mode and the shared ones. Returns null when
     * invalid; with {@code showErrors} the offending fields are marked.
     */
    private Form readForm(boolean showErrors) {
        boolean ok = true;
        for (EditText e : new EditText[] {intervalValue, maxShots, durationValue, minFree, delayValue}) {
            e.setError(null);
        }

        long duration = 0;
        if (limitDuration.isChecked()) {
            long du = DURATION_UNITS[Math.max(0, durationUnit.getSelectedItemPosition())];
            long dr = Settings.parseWholeNumber(durationValue.getText().toString());
            duration = dr > 0 ? Settings.times(dr, du) : -1;
            if (duration < Settings.MIN_DURATION_SECONDS || duration > Settings.MAX_DURATION_SECONDS) {
                ok = false;
                mark(showErrors, durationValue, R.string.error_duration);
            }
        }
        long fm = Settings.parseWholeNumber(minFree.getText().toString());
        long freeBytes = fm > 0 ? Settings.times(fm, Settings.MB) : -1;
        if (freeBytes < Settings.MIN_FREE_BYTES_LOWER || freeBytes > Settings.MIN_FREE_BYTES_UPPER) {
            ok = false;
            mark(showErrors, minFree, R.string.error_min_free);
        }
        Resolution r = selectedResolution();
        int width = r == null ? 0 : r.width;
        int height = r == null ? 0 : r.height;

        if (videoMode()) {
            long delay = 0;
            if (videoDelay.isChecked()) {
                long unit = DELAY_UNITS[Math.max(0, delayUnit.getSelectedItemPosition())];
                long raw = Settings.parseWholeNumber(delayValue.getText().toString());
                delay = raw > 0 ? Settings.times(raw, unit) : -1;
                if (delay < VideoSettings.MIN_DELAY_SECONDS || delay > VideoSettings.MAX_DELAY_SECONDS) {
                    ok = false;
                    mark(showErrors, delayValue, R.string.error_delay);
                }
            }
            if (!ok) {
                return null;
            }
            int checkedLength = fileLength.getCheckedRadioButtonId();
            int minutes = checkedLength == R.id.file_length_5 ? 5 : checkedLength == R.id.file_length_10 ? 10 : 15;
            int checkedQuality = videoQuality.getCheckedRadioButtonId();
            VideoSettings.Quality q = checkedQuality == R.id.video_quality_high ? VideoSettings.Quality.HIGH
                    : checkedQuality == R.id.video_quality_low ? VideoSettings.Quality.LOW : VideoSettings.Quality.MEDIUM;
            VideoSettings v = new VideoSettings(delay, duration, width, height, q, minutes, videoAudio.isChecked());
            Settings p = new Settings(saved.intervalSeconds, saved.maxShots, saved.maxDurationSeconds, selectedLens(),
                    saved.width, saved.height, saved.jpegQuality, freeBytes);
            return v.isValid() && p.isValid() ? new Form(p, v) : null;
        }

        long iu = INTERVAL_UNITS[Math.max(0, intervalUnit.getSelectedItemPosition())];
        long ir = Settings.parseWholeNumber(intervalValue.getText().toString());
        long interval = ir > 0 ? Settings.times(ir, iu) : -1;
        if (interval < Settings.MIN_INTERVAL_SECONDS || interval > Settings.MAX_INTERVAL_SECONDS) {
            ok = false;
            mark(showErrors, intervalValue, R.string.error_interval);
        }
        int shots = 0;
        if (limitShots.isChecked()) {
            long sr = Settings.parseWholeNumber(maxShots.getText().toString());
            if (sr < Settings.MIN_SHOTS || sr > Settings.MAX_SHOTS) {
                ok = false;
                mark(showErrors, maxShots, R.string.error_shots);
            } else {
                shots = (int) sr;
            }
        }
        if (!ok) {
            return null;
        }
        Settings p = new Settings(interval, shots, duration, selectedLens(), width, height, quality.getProgress(),
                freeBytes);
        return p.isValid() ? new Form(p, savedVideo) : null;
    }

    /** Saves a valid form; an invalid one is not saved and a short message says so. */
    private void saveIfEditable() {
        if (!editable || isSessionActive(SessionState.get().current())) {
            return;
        }
        Form f = readForm(true);
        if (f == null) {
            Toast.makeText(this, R.string.error_not_saved, Toast.LENGTH_SHORT).show();
            return;
        }
        store.save(f.photo);
        store.saveVideo(f.video);
        if (videoMode()) {
            store.putUnit(SettingsStore.K_DELAY_UNIT, DELAY_UNITS[Math.max(0, delayUnit.getSelectedItemPosition())]);
        } else {
            store.putUnit(SettingsStore.K_INTERVAL_UNIT,
                    INTERVAL_UNITS[Math.max(0, intervalUnit.getSelectedItemPosition())]);
        }
        store.putUnit(durationUnitKey(), DURATION_UNITS[Math.max(0, durationUnit.getSelectedItemPosition())]);
        saved = f.photo;
        savedVideo = f.video;
    }

    private static void mark(boolean show, EditText field, int message) {
        if (show) {
            field.setError(field.getContext().getString(message));
        }
    }

    private static String formatNumber(int n) {
        return String.format(Locale.getDefault(), "%d", n);
    }

    /** Keeps the preferred unit when the value divides evenly, otherwise the largest unit that does. */
    private static long pickUnit(long seconds, long preferred, long[] units) {
        if (seconds <= 0 || seconds % preferred == 0) {
            return indexOf(units, preferred) >= 0 ? preferred : units[0];
        }
        for (int i = units.length - 1; i >= 0; i--) {
            if (seconds % units[i] == 0) {
                return units[i];
            }
        }
        return units[0];
    }

    private static int indexOf(long[] values, long value) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == value) {
                return i;
            }
        }
        return -1;
    }

    // -------------------------------------------------------------------------------------------
    // Listeners
    // -------------------------------------------------------------------------------------------

    private void attachListeners() {
        TextWatcher watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (!suppress) {
                    updateEstimate();
                }
            }
        };
        for (EditText e : new EditText[] {intervalValue, maxShots, durationValue, minFree, delayValue}) {
            e.addTextChangedListener(watcher);
        }
        AdapterView.OnItemSelectedListener simple = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!suppress) {
                    updateEstimate();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        };
        intervalUnit.setOnItemSelectedListener(simple);
        durationUnit.setOnItemSelectedListener(simple);
        delayUnit.setOnItemSelectedListener(simple);
        resolutionSpinner.setOnItemSelectedListener(simple);

        cameraGroup.setOnCheckedChangeListener((group, id) -> {
            if (suppress) {
                return;
            }
            // Revalidate the resolution against the newly selected camera.
            Resolution keep = selectedResolution();
            populateResolutions(selectedLens(), keep == null ? 0 : keep.width, keep == null ? 0 : keep.height,
                    false);
            updateEstimate();
        });

        quality.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                qualityValue.setText(formatNumber(progress));
                if (!suppress) {
                    updateEstimate();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        RadioGroup.OnCheckedChangeListener estimateOnChange = (group, id) -> {
            if (!suppress) {
                updateEstimate();
            }
        };
        videoQuality.setOnCheckedChangeListener(estimateOnChange);
        fileLength.setOnCheckedChangeListener(estimateOnChange);
        videoAudio.setOnCheckedChangeListener((b, checked) -> {
            if (!suppress) {
                updateEstimate();
            }
        });
        for (Switch sw : new Switch[] {limitShots, limitDuration, videoDelay}) {
            sw.setOnCheckedChangeListener((b, checked) -> {
                updateLimitFields();
                if (!suppress) {
                    updateEstimate();
                }
            });
        }

        themeGroup.setOnCheckedChangeListener((group, id) -> {
            if (suppress) {
                return;
            }
            int theme = id == R.id.theme_light ? SettingsStore.THEME_LIGHT
                    : id == R.id.theme_dark ? SettingsStore.THEME_DARK : SettingsStore.THEME_SYSTEM;
            if (theme == store.getTheme()) {
                return;
            }
            store.putTheme(theme);
            // Android recreates the screens with the new theme; the form keeps its state.
            Ui.applyTheme(this, theme);
        });
    }

    private void selectTheme(int theme) {
        suppress = true;
        themeGroup.check(theme == SettingsStore.THEME_LIGHT ? R.id.theme_light
                : theme == SettingsStore.THEME_DARK ? R.id.theme_dark : R.id.theme_system);
        suppress = false;
    }

    private void updateLimitFields() {
        maxShots.setVisibility(limitShots.isChecked() ? View.VISIBLE : View.GONE);
        durationRow.setVisibility(limitDuration.isChecked() ? View.VISIBLE : View.GONE);
        delayRow.setVisibility(videoDelay.isChecked() ? View.VISIBLE : View.GONE);
        if (!limitShots.isChecked()) {
            maxShots.setError(null);
        }
        if (!limitDuration.isChecked()) {
            durationValue.setError(null);
        }
        if (!videoDelay.isChecked()) {
            delayValue.setError(null);
        }
    }

    // -------------------------------------------------------------------------------------------
    // Cameras and resolutions
    // -------------------------------------------------------------------------------------------

    /**
     * Photo and video sizes per camera, from the camera the session will open: for Back, the
     * chosen back lens. Video sizes are those the camera streams at 30 fps and the encoder takes.
     */
    private void loadCameras() {
        io.execute(() -> {
            CameraManager manager = getSystemService(CameraManager.class);
            Map<Settings.Lens, List<Resolution>> photo = new EnumMap<>(Settings.Lens.class);
            Map<Settings.Lens, List<Resolution>> videoSizes = new EnumMap<>(Settings.Lens.class);
            String factor = null;
            for (Settings.Lens lens : Settings.Lens.values()) {
                LensProbe.Target t = LensProbe.target(manager, lens, store);
                if (t != null) {
                    List<Resolution> jpeg = CameraController.jpegSizes(manager, t.cameraId);
                    if (!jpeg.isEmpty()) {
                        photo.put(lens, jpeg);
                    }
                    List<Resolution> rec = VideoCaps.videoSizes(manager, t.cameraId);
                    if (!rec.isEmpty()) {
                        videoSizes.put(lens, rec);
                    }
                    if (lens == Settings.Lens.BACK && t.offered > 1) {
                        factor = t.factorLabel();
                    }
                }
            }
            final String backFactor = factor;
            final int[] range = VideoCaps.bitrateRange();
            runOnUiThread(() -> {
                if (isDestroyed()) {
                    return;
                }
                photoSizesByLens.clear();
                photoSizesByLens.putAll(photo);
                videoSizesByLens.clear();
                videoSizesByLens.putAll(videoSizes);
                backLensFactor = backFactor;
                encoderRange = range;
                camerasLoaded = true;
                suppress = true;
                Map<Settings.Lens, List<Resolution>> sizes = videoMode() ? videoSizesByLens : photoSizesByLens;
                Settings.Lens lens = selectedLens();
                if (!sizes.containsKey(lens) && !sizes.isEmpty()) {
                    lens = sizes.keySet().iterator().next();
                    cameraGroup.check(lens == Settings.Lens.FRONT ? R.id.camera_front : R.id.camera_back);
                }
                refreshCameraButtons();
                populateResolutions(lens, savedWidth(), savedHeight(), true);
                suppress = false;
                updateEstimate();
            });
        });
    }

    /** Only cameras that exist are selectable. */
    private void refreshCameraButtons() {
        Map<Settings.Lens, List<Resolution>> sizes = videoMode() ? videoSizesByLens : photoSizesByLens;
        cameraBack.setVisibility(sizes.isEmpty() || sizes.containsKey(Settings.Lens.BACK) ? View.VISIBLE : View.GONE);
        cameraFront.setVisibility(sizes.isEmpty() || sizes.containsKey(Settings.Lens.FRONT) ? View.VISIBLE : View.GONE);
    }

    /**
     * Fills the list for {@code lens} and the current mode, selecting {@code width x height}
     * when offered, otherwise "Largest". With {@code fromSaved}, a saved size this lens does not
     * offer is named in the note, so the change to "Largest" is visible before it is saved.
     */
    private void populateResolutions(Settings.Lens lens, int width, int height, boolean fromSaved) {
        Map<Settings.Lens, List<Resolution>> sizes = videoMode() ? videoSizesByLens : photoSizesByLens;
        currentSizes = sizes.getOrDefault(lens, new ArrayList<>());
        List<String> labels = new ArrayList<>();
        if (currentSizes.isEmpty()) {
            labels.add(getString(R.string.resolution_none));
        } else {
            labels.add(getString(R.string.resolution_largest, currentSizes.get(0).label()));
            for (Resolution r : currentSizes) {
                labels.add(r.label());
            }
        }
        boolean was = suppress;
        suppress = true;
        resolutionSpinner.setAdapter(adapter(labels));
        int selection = 0;
        for (int i = 0; i < currentSizes.size(); i++) {
            Resolution r = currentSizes.get(i);
            if (r.width == width && r.height == height) {
                selection = i + 1;
                break;
            }
        }
        resolutionSpinner.setSelection(selection, false);
        suppress = was;

        List<String> notes = new ArrayList<>();
        if (lens == Settings.Lens.BACK && backLensFactor != null) {
            notes.add(getString(R.string.resolution_for_lens, backLensFactor));
        }
        if (fromSaved && selection == 0 && !(width == 0 && height == 0) && !currentSizes.isEmpty()) {
            notes.add(getString(R.string.resolution_not_offered,
                    String.format(Locale.ROOT, "%d × %d", width, height)));
        }
        resolutionNote.setText(String.join("\n", notes));
        resolutionNote.setVisibility(notes.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private Settings.Lens selectedLens() {
        return cameraGroup.getCheckedRadioButtonId() == R.id.camera_front ? Settings.Lens.FRONT : Settings.Lens.BACK;
    }

    /** Selected size, or null for "largest supported". */
    private Resolution selectedResolution() {
        int i = resolutionSpinner.getSelectedItemPosition();
        if (i >= 1 && i - 1 < currentSizes.size()) {
            return currentSizes.get(i - 1);
        }
        return null;
    }

    private ArrayAdapter<String> adapter(List<String> labels) {
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return a;
    }

    private void setupSpinner(Spinner spinner, int[] labels) {
        List<String> list = new ArrayList<>();
        for (int id : labels) {
            list.add(getString(id));
        }
        spinner.setAdapter(adapter(list));
    }

    // -------------------------------------------------------------------------------------------
    // Estimate
    // -------------------------------------------------------------------------------------------

    private void updateEstimate() {
        Form f = readForm(false);
        Resolution r = selectedResolution();
        if (r == null && !currentSizes.isEmpty()) {
            r = currentSizes.get(0);
        }
        if (f == null || r == null) {
            estimate.setText(R.string.estimate_unknown);
            return;
        }
        long free = PhotoStore.freeBytes();
        if (videoMode()) {
            VideoSettings v = f.video;
            int bitrate = VideoPlan.bitrate(r.width, r.height, VideoPlan.FRAME_RATE, v.quality, encoderRange[0],
                    encoderRange[1]);
            long perHour = VideoPlan.bytesPerHour(bitrate, v.audio);
            double hours = VideoPlan.hoursUntilReserve(free, f.photo.minFreeBytes, perHour);
            String span = hours < 0 ? getString(R.string.unknown) : Texts.duration(Math.round(hours * Settings.HOUR));
            String mbps = getString(R.string.bitrate_mbps,
                    String.format(Locale.getDefault(), "%.1f", (bitrate + (v.audio ? VideoPlan.AUDIO_BITRATE : 0)) / 1e6));
            estimate.setText(getString(R.string.estimate_video, Formatter.formatShortFileSize(this, perHour), mbps, span));
            return;
        }
        Settings s = f.photo;
        long perPhoto = StorageEstimate.bytesPerPhoto(r.width, r.height, s.jpegQuality);
        long photos = StorageEstimate.photosPerDay(s.intervalSeconds, s.maxShots, s.maxDurationSeconds);
        long perDay = perPhoto * photos;
        double days = free < 0 ? -1 : StorageEstimate.daysUntilReserve(free, s.minFreeBytes, perDay);
        String span = days < 0 ? getString(R.string.unknown) : Texts.duration(Math.round(days * Settings.DAY));
        estimate.setText(getString(R.string.estimate, String.format(Locale.getDefault(), "%,d", photos),
                Formatter.formatShortFileSize(this, perDay), span));
    }
}
