package ar.sanzol.sentrycam;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Toolbar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Camera Preview: a full-screen live view for composing the shot, only while no
 * session runs. It shows the camera, lens and framing that Start will use (the photo size or
 * the video size of the mode chosen on the main screen), with the lens chips when the phone
 * offers more than one back lens. It never takes a photo or records.
 *
 * <p>The camera is held only while this screen is in front: it is released in onPause (back
 * to the main screen, Settings opened from here, screen off) and opened again in onResume with
 * the current saved settings. Start on the main screen waits for the release
 * ({@link PreviewCamera#whenReleased}).
 */
public final class PreviewActivity extends Activity {

    private static final int REQUEST_CAMERA = 1;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> new Thread(r, "preview-io"));

    private FrameLayout box;
    private TextureView view;
    private TextView message;
    private View note;
    private LinearLayout lensChips;

    private SettingsStore store;
    private PreviewCamera camera;
    /** The preview is opening or streaming. */
    private boolean open;
    /** Bumped by every open and close, so a late size query cannot reopen a closed preview. */
    private int request;
    /** Opens the camera once the TextureView's surface exists. */
    private Runnable pendingStart;
    /** The lens shown (null for the front camera). */
    private LensCatalog.Choice choice;
    private List<LensCatalog.Choice> lensChoices = new ArrayList<>();
    private boolean resumed;
    /** The camera permission is asked once per visit; after a refusal the screen says why. */
    private boolean permissionAsked;

    // -------------------------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_preview);
        Ui.padForSystemBars(findViewById(R.id.root));
        box = findViewById(R.id.preview_box);
        view = findViewById(R.id.preview_view);
        message = findViewById(R.id.preview_message);
        note = findViewById(R.id.preview_note);
        lensChips = findViewById(R.id.lens_chips);
        store = new SettingsStore(this);

        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.inflateMenu(R.menu.menu_preview);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_settings) {
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
            }
            return false;
        });

        camera = new PreviewCamera(this, new PreviewCamera.Listener() {
            @Override
            public void onPreviewFailed(boolean busy) {
                if (open) {
                    closePreview(null);
                    showMessage(getString(busy ? R.string.preview_busy : R.string.preview_error));
                }
            }

            @Override
            public void onLensCheck(boolean passed) {
                onPreviewLensCheck(passed);
            }
        });
        view.setSurfaceTextureListener(surfaceListener);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        // Only for a stopped app: a session owns the camera (delayed start included).
        if (SessionState.get().current().status != SessionState.Status.STOPPED) {
            finish();
            return;
        }
        box.post(this::openPreview); // after layout, so the box has its size
    }

    @Override
    protected void onPause() {
        resumed = false;
        closePreview(null);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        camera.shutdown();
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_CAMERA) {
            return;
        }
        if (hasCameraPermission()) {
            openPreview();
        } else {
            showMessage(getString(R.string.permission_camera_needed));
        }
    }

    private boolean hasCameraPermission() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    // -------------------------------------------------------------------------------------------
    // Preview
    // -------------------------------------------------------------------------------------------

    private void openPreview() {
        if (open || !resumed || isFinishing()) {
            return;
        }
        if (!hasCameraPermission()) {
            if (!permissionAsked) {
                permissionAsked = true;
                requestPermissions(new String[] {Manifest.permission.CAMERA}, REQUEST_CAMERA);
            } else {
                showMessage(getString(R.string.permission_camera_needed));
            }
            return;
        }
        open = true;
        hideMessage();
        final int req = ++request;
        final Settings s = store.load();
        final VideoSettings v = store.loadVideo();
        final boolean videoMode = store.getMode() == Settings.Mode.VIDEO;
        final String lensKey = store.getLensKey();
        final Set<String> failed = store.getFailedLensChecks();
        final Set<String> passed = store.getPassedLensChecks();
        final int rotation = getDisplay() == null ? Surface.ROTATION_0 : getDisplay().getRotation();
        io.execute(() -> {
            CameraManager manager = getSystemService(CameraManager.class);
            List<LensCatalog.Choice> choices = s.lens == Settings.Lens.BACK
                    ? LensProbe.choices(manager, failed) : Collections.emptyList();
            // The same camera, zoom ratio and size the capture or recording will use.
            LensProbe.Target t = LensProbe.target(manager, s.lens, lensKey, failed, passed);
            String id = t == null ? null : t.cameraId;
            Resolution size = null;
            boolean approximate = false;
            int sensor = 0;
            if (id != null) {
                Resolution capture = videoMode
                        ? Resolution.choose(VideoCaps.videoSizes(manager, id), v.width, v.height)
                        : Resolution.choose(CameraController.jpegSizes(manager, id), s.width, s.height);
                if (capture != null) {
                    size = PreviewGeometry.choosePreviewSize(PreviewCamera.previewSizes(manager, id), capture);
                    approximate = size != null && !PreviewGeometry.sameAspect(size, capture);
                }
                sensor = CameraController.sensorOrientation(manager, id);
            }
            final Resolution previewSize = size;
            final boolean framingApproximate = approximate;
            final int sensorOrientation = sensor;
            main.post(() -> {
                if (req != request || !open || isDestroyed()) {
                    return;
                }
                lensChoices = choices;
                renderLensChips(lensKey);
                if (t == null || previewSize == null) {
                    closePreview(null);
                    showMessage(getString(R.string.preview_no_camera));
                    return;
                }
                choice = t.choice;
                note.setVisibility(framingApproximate ? View.VISIBLE : View.GONE);
                showView(id, previewSize, sensorOrientation, rotation, req, t.zoomRatio, t.checkPhysicalId);
            });
        });
    }

    /** Sizes the view to the capture's framing, letterboxed in the box, then starts the camera. */
    private void showView(String cameraId, Resolution preview, int sensorOrientation, int rotation, int req,
            float ratio, String checkPhysicalId) {
        boolean swap = PreviewGeometry.swapsDimensions(sensorOrientation, rotation * 90);
        int contentW = swap ? preview.height : preview.width;
        int contentH = swap ? preview.width : preview.height;
        int[] fit = PreviewGeometry.fit(contentW, contentH, box.getWidth(), box.getHeight());

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) view.getLayoutParams();
        lp.width = fit[0];
        lp.height = fit[1];
        lp.gravity = Gravity.CENTER;
        view.setLayoutParams(lp);
        view.setTransform(previewTransform(fit[0], fit[1], preview, rotation));
        view.setVisibility(View.VISIBLE);

        pendingStart = () -> {
            if (req == request && open) {
                camera.open(cameraId, view.getSurfaceTexture(), preview, ratio, checkPhysicalId);
            }
        };
        if (view.isAvailable()) {
            runPendingStart();
        }
    }

    private void runPendingStart() {
        Runnable r = pendingStart;
        pendingStart = null;
        if (r != null) {
            r.run();
        }
    }

    /** Hides the image and releases the camera; {@code then} runs once it is really closed. */
    private void closePreview(Runnable then) {
        request++;
        open = false;
        pendingStart = null;
        view.setVisibility(View.GONE);
        note.setVisibility(View.GONE);
        camera.close(then);
    }

    /** Closes and opens again with the current saved settings (a new lens, or the main lens). */
    private void reopen() {
        closePreview(() -> {
            if (resumed && !isDestroyed()) {
                openPreview();
            }
        });
    }

    private void showMessage(String text) {
        message.setText(text);
        message.setVisibility(View.VISIBLE);
    }

    private void hideMessage() {
        message.setVisibility(View.GONE);
    }

    private final TextureView.SurfaceTextureListener surfaceListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
            runPendingStart();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
            // The view is sized before the camera starts; nothing to redo.
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
            if (open) {
                closePreview(null);
            }
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture texture) {
        }
    };

    /**
     * The TextureView transform for the display rotation, as in Google's Camera2Basic sample: in
     * landscape the buffer is rotated into the view; in portrait the camera's own buffer
     * transform already makes the picture upright.
     */
    private static Matrix previewTransform(int viewW, int viewH, Resolution preview, int rotation) {
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewW, viewH);
        float cx = viewRect.centerX();
        float cy = viewRect.centerY();
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            // Height and width swapped on purpose: the buffer is rotated a quarter turn here.
            RectF buffer = new RectF(0, 0, preview.height, preview.width);
            buffer.offset(cx - buffer.centerX(), cy - buffer.centerY());
            matrix.setRectToRect(viewRect, buffer, Matrix.ScaleToFit.FILL);
            float scale = Math.max((float) viewH / preview.height, (float) viewW / preview.width);
            matrix.postScale(scale, scale, cx, cy);
            matrix.postRotate(90 * (rotation - 2), cx, cy);
        } else if (rotation == Surface.ROTATION_180) {
            matrix.postRotate(180, cx, cy);
        }
        return matrix;
    }

    // -------------------------------------------------------------------------------------------
    // Back lenses: chips under the image. Preview and capture open the chosen lens.
    // -------------------------------------------------------------------------------------------

    /** "0.5×  1×  2×": every chip shows its factor; the selected one differs by style. */
    private void renderLensChips(String selectedKey) {
        boolean show = lensChoices.size() > 1;
        lensChips.removeAllViews();
        lensChips.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) {
            return;
        }
        LensCatalog.Choice selected = LensCatalog.resolve(lensChoices, selectedKey);
        LayoutInflater inflater = getLayoutInflater();
        for (int i = 0; i < lensChoices.size(); i++) {
            LensCatalog.Choice c = lensChoices.get(i);
            if (i > 0) {
                View divider = new View(this);
                divider.setBackgroundColor(getColor(R.color.line));
                lensChips.addView(divider, new LinearLayout.LayoutParams(
                        getResources().getDimensionPixelSize(R.dimen.divider_thickness),
                        LinearLayout.LayoutParams.MATCH_PARENT));
            }
            Button chip = (Button) inflater.inflate(R.layout.item_segment_chip, lensChips, false);
            chip.setText(c.label());
            chip.setSelected(selected != null && c.key.equals(selected.key));
            chip.setContentDescription(getString(R.string.lens_chip_description, LensCatalog.formatFactor(c.factor)));
            chip.setOnClickListener(v -> selectLens(c.key));
            lensChips.addView(chip);
        }
    }

    private void selectLens(String key) {
        if (key.equals(store.getLensKey())) {
            return;
        }
        store.putLensKey(key);
        renderLensChips(key);
        reopen(); // the new framing appears in place
    }

    /** The physical module check of a zoom-ratio lens finished (see PreviewCamera.ModuleCheck). */
    private void onPreviewLensCheck(boolean passed) {
        LensCatalog.Choice c = choice;
        if (c == null || c.checkKey() == null) {
            return;
        }
        store.putLensCheck(c.checkKey(), passed);
        if (passed) {
            return;
        }
        // Only digital zoom on this phone: never offered again; back to the main lens.
        store.putLensKey(LensCatalog.MAIN_KEY);
        Toast.makeText(this, getString(R.string.lens_digital_zoom, LensCatalog.formatFactor(c.factor)),
                Toast.LENGTH_LONG).show();
        reopen();
    }
}
