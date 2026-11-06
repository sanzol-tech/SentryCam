package ar.sanzol.sentrycam;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The camera behind Camera Preview: a live view of the capture camera, used only while no
 * session runs, on the same {@link LensProbe.Target} capture uses (camera id plus zoom ratio).
 * One output (the screen's {@link SurfaceTexture}), no stills, nothing written. Independent of
 * {@link CameraController}, {@link VideoRecorder} and the services.
 *
 * <p>Threads: public methods are called on the main thread; all Camera2 work runs on the
 * private "preview" thread; listener calls and close callbacks are posted back to the main
 * thread. {@link #close(Runnable)} reports only after the camera device has actually closed.
 *
 * <p>Camera hand-over: {@link #whenReleased(Runnable)} runs its action once no preview in the
 * process holds the camera. Start uses it, so a session never races a preview that is still
 * closing (the preview is a separate screen, which closes as the main screen comes back).
 */
final class PreviewCamera {

    interface Listener {
        /** The preview could not run; the device is closed. {@code busy}: in use elsewhere. */
        void onPreviewFailed(boolean busy);

        /**
         * Result of a module check: whether the camera reported the expected physical
         * module active while the preview ran at the requested zoom ratio. Called at most once per open.
         */
        void onLensCheck(boolean passed);
    }

    private static final String TAG = "SentryCam";

    // ---- Camera hand-over, process-wide (main thread only) ----
    /** Previews that may hold the camera: opened and not yet reported closed. */
    private static int holders;
    private static final List<Runnable> releaseWaiters = new ArrayList<>();

    /** Runs {@code action} on the main thread once no preview holds the camera; at once if none does. */
    static void whenReleased(Runnable action) {
        if (holders == 0) {
            new Handler(Looper.getMainLooper()).post(action);
        } else {
            releaseWaiters.add(action);
        }
    }

    private final CameraManager manager;
    private final Listener listener;
    // ---- Main thread only ----
    /** This preview counts in {@link #holders}. */
    private boolean holding;
    /** Bumped by every open, so a close that completes after a newer open does not release. */
    private int openCount;
    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread thread;
    private Handler handler;

    // ---- Preview thread only ----
    private int generation;
    private boolean opening;
    private CameraDevice device;
    private CameraCaptureSession session;
    private Surface surface;
    private final List<Runnable> closeWaiters = new ArrayList<>();
    private float zoomRatio = 1f;
    private String checkPhysicalId;

    PreviewCamera(Context context, Listener listener) {
        this.manager = context.getSystemService(CameraManager.class);
        this.listener = listener;
    }

    /** Preview sizes the camera offers for a SurfaceTexture, or an empty list. */
    static List<Resolution> previewSizes(CameraManager manager, String cameraId) {
        List<Resolution> out = new ArrayList<>();
        try {
            StreamConfigurationMap map = manager.getCameraCharacteristics(cameraId)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size[] sizes = map == null ? null : map.getOutputSizes(SurfaceTexture.class);
            if (sizes != null) {
                for (Size s : sizes) {
                    out.add(new Resolution(s.getWidth(), s.getHeight()));
                }
            }
        } catch (CameraAccessException | RuntimeException e) {
            Log.w(TAG, "Preview size query failed", e);
        }
        return out;
    }

    /**
     * Opens {@code cameraId} and streams it into {@code texture} at {@code size}, at
     * {@code ratio} (1 = no zoom request). With {@code checkPhysicalId}, reports through
     * {@link Listener#onLensCheck} whether that physical module became active. Main thread.
     */
    void open(String cameraId, SurfaceTexture texture, Resolution size, float ratio, String checkPhysicalId) {
        openCount++;
        if (!holding) {
            holding = true;
            holders++;
        }
        if (thread == null) {
            thread = new HandlerThread("preview");
            thread.start();
            handler = new Handler(thread.getLooper());
        }
        handler.post(() -> {
            if (device != null || opening || session != null) {
                // Still open from before: close first, then open again once it has closed.
                generation++;
                closeWaiters.add(() -> open(cameraId, texture, size, ratio, checkPhysicalId));
                releaseCamera();
                return;
            }
            final int gen = ++generation;
            this.zoomRatio = ratio;
            this.checkPhysicalId = checkPhysicalId;
            texture.setDefaultBufferSize(size.width, size.height);
            surface = new Surface(texture);
            opening = true;
            try {
                manager.openCamera(cameraId, new DeviceCallback(gen), handler);
            } catch (CameraAccessException e) {
                opening = false;
                fail(isBusy(e));
            } catch (SecurityException | IllegalArgumentException e) {
                opening = false;
                fail(false);
            }
        });
    }

    /**
     * Stops the preview and releases the camera. {@code onClosed} runs on the main thread once the
     * device has closed (at once if nothing was open). Main thread.
     */
    void close(Runnable onClosed) {
        final int closingOpen = openCount;
        Runnable closed = () -> {
            if (closingOpen == openCount) {
                released();
            }
            if (onClosed != null) {
                onClosed.run();
            }
        };
        if (handler == null) {
            main.post(closed);
            return;
        }
        handler.post(() -> {
            generation++;
            closeWaiters.add(closed);
            releaseCamera();
            if (device == null && !opening) {
                notifyClosed();
            }
            // Otherwise DeviceCallback.onClosed (or a late onOpened/onError) calls notifyClosed.
        });
    }

    /**
     * Ends the preview thread once the camera has really closed, so the device's onClosed (which
     * releases the Surface) is still delivered. Call from the activity's onDestroy.
     */
    void shutdown() {
        if (handler == null) {
            return;
        }
        HandlerThread t = thread;
        close(t::quitSafely);
        thread = null;
        handler = null;
    }

    /** Main thread: this preview no longer holds the camera; run the waiting starts if none does. */
    private void released() {
        if (!holding) {
            return;
        }
        holding = false;
        holders--;
        if (holders == 0 && !releaseWaiters.isEmpty()) {
            List<Runnable> run = new ArrayList<>(releaseWaiters);
            releaseWaiters.clear();
            for (Runnable r : run) {
                r.run();
            }
        }
    }

    // ---- Preview thread ----

    private void releaseCamera() {
        if (session != null) {
            try {
                session.close();
            } catch (RuntimeException e) {
                Log.w(TAG, "Preview session close failed", e);
            }
            session = null;
        }
        if (device != null) {
            CameraDevice d = device;
            device = null;
            try {
                d.close(); // onClosed follows on this thread
            } catch (RuntimeException e) {
                Log.w(TAG, "Preview device close failed", e);
                notifyClosed();
            }
        }
    }

    private void notifyClosed() {
        if (surface != null) {
            surface.release();
            surface = null;
        }
        for (Runnable r : closeWaiters) {
            main.post(r);
        }
        closeWaiters.clear();
    }

    private void fail(boolean busy) {
        generation++;
        releaseCamera();
        if (device == null && !opening) {
            notifyClosed();
        }
        main.post(() -> listener.onPreviewFailed(busy));
    }

    private final class DeviceCallback extends CameraDevice.StateCallback {
        private final int gen;

        DeviceCallback(int gen) {
            this.gen = gen;
        }

        @Override
        public void onOpened(CameraDevice camera) {
            opening = false;
            if (gen != generation) {
                camera.close(); // closed while opening; onClosed reports it
                return;
            }
            device = camera;
            createSession(gen);
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            opening = false;
            camera.close();
            if (camera == device) {
                device = null;
            }
            if (gen == generation) {
                main.post(() -> listener.onPreviewFailed(true));
                generation++;
            }
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            opening = false;
            camera.close();
            if (camera == device) {
                device = null;
            }
            if (gen == generation) {
                boolean busy = error == ERROR_CAMERA_IN_USE || error == ERROR_MAX_CAMERAS_IN_USE
                        || error == ERROR_CAMERA_DISABLED;
                main.post(() -> listener.onPreviewFailed(busy));
                generation++;
            }
        }

        @Override
        public void onClosed(CameraDevice camera) {
            if (device == null) {
                notifyClosed();
            }
        }
    }

    private void createSession(int gen) {
        try {
            SessionConfiguration config = new SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                    List.of(new OutputConfiguration(surface)), handler::post,
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession s) {
                            if (gen != generation || device == null) {
                                s.close();
                                return;
                            }
                            session = s;
                            startPreview(s);
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession s) {
                            if (gen == generation) {
                                fail(false);
                            }
                        }
                    });
            device.createCaptureSession(config);
        } catch (CameraAccessException e) {
            fail(isBusy(e));
        } catch (RuntimeException e) {
            fail(false);
        }
    }

    private void startPreview(CameraCaptureSession s) {
        try {
            CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            b.addTarget(surface);
            b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            int[] af = manager.getCameraCharacteristics(device.getId())
                    .get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            if (af != null && Arrays.stream(af)
                    .anyMatch(m -> m == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            }
            // Same zoom rule as capture, and no stabilization crop, so the preview shows the capture's framing.
            CameraController.applyZoom(b, zoomRatio);
            b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
            s.setRepeatingRequest(b.build(), checkPhysicalId == null ? null : new ModuleCheck(generation,
                    checkPhysicalId), handler);
        } catch (CameraAccessException e) {
            fail(isBusy(e));
        } catch (RuntimeException e) {
            fail(false);
        }
    }

    /** Watches which physical module the logical camera reports active, for one open. */
    private final class ModuleCheck extends CameraCaptureSession.CaptureCallback {
        private final int gen;
        private final String expected;
        private int frames;
        private boolean done;

        ModuleCheck(int gen, String expected) {
            this.gen = gen;
            this.expected = expected;
        }

        @Override
        public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest request, TotalCaptureResult result) {
            if (done || gen != generation) {
                return;
            }
            frames++;
            String active = result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID);
            boolean passed = expected.equals(active);
            if (passed || frames >= CameraController.LENS_CHECK_FRAMES) {
                done = true;
                Log.i(TAG, "Lens check " + expected + ": " + (passed ? "active" : "not active (reported "
                        + active + ")"));
                main.post(() -> listener.onLensCheck(passed));
            }
        }
    }

    private static boolean isBusy(CameraAccessException e) {
        int r = e.getReason();
        return r == CameraAccessException.CAMERA_IN_USE || r == CameraAccessException.MAX_CAMERAS_IN_USE
                || r == CameraAccessException.CAMERA_DISABLED;
    }
}
