package ar.sanzol.sentrycam;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Camera2 still capture without a visible preview.
 *
 * <p>Two outputs are configured: a JPEG {@link ImageReader} for the photos and a small YUV
 * reader used only for metering, so auto-exposure, white balance and focus can converge before
 * a still is taken. Metering frames are discarded immediately and never stored.
 *
 * <ul>
 *   <li>Continuous metering (short intervals): the metering stream runs for the whole session
 *       and each still is taken immediately.</li>
 *   <li>Per-shot warm-up (long intervals): the metering stream runs only from the capture request
 *       until exposure converges, then stops, saving power between shots.</li>
 * </ul>
 *
 * <p>The camera, zoom ratio and module check come from {@link LensProbe.Target}, the same rule
 * Preview uses, and the ratio is set on every request, so the photo has Preview's framing. A lens that
 * needs a module check is confirmed on the metering stream before the session reports ready.
 *
 * <p>Every method and callback runs on the single camera {@link Handler} thread passed in.
 * Stale callbacks from a previous open are ignored using a generation counter.
 */
final class CameraController {

    enum Error {
        PERMISSION, IN_USE, DISABLED, DISCONNECTED, DEVICE, CONFIGURATION, OPEN_TIMEOUT,
        /** The requested physical module never became active: that lens is digital zoom here. */
        LENS_NOT_ACTIVE
    }

    interface Callback {
        /** Camera open and session configured; captures may be requested. */
        void onReady();

        /** A complete JPEG arrived. The bytes are not yet saved. */
        void onJpeg(byte[] jpeg);

        /** One capture failed; the camera is still open. */
        void onCaptureFailed(String reason);

        /** The camera is no longer usable; it has already been closed. */
        void onError(Error error, String detail);
    }

    private static final String TAG = "CameraController";
    private static final long OPEN_TIMEOUT_MS = 10_000;
    private static final long CAPTURE_TIMEOUT_MS = 15_000;
    private static final int WARMUP_MIN_FRAMES = 3;
    private static final int WARMUP_MAX_FRAMES = 30;
    private static final long WARMUP_MAX_MS = 2_000;
    private static final int METER_MIN_WIDTH = 320;
    /** Frames to wait for the expected physical module (about one second of preview). */
    static final int LENS_CHECK_FRAMES = 30;

    private final CameraManager manager;
    private final Handler handler;
    private final Executor executor;
    private final Callback callback;

    private int generation;
    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader jpegReader;
    private ImageReader meterReader;

    private boolean continuousMetering;
    private boolean afContinuous;
    private int jpegQuality;
    private float zoomRatio = 1f;
    private String checkPhysicalId;
    private boolean checkingLens;
    private int checkFrames;

    private boolean ready;
    private boolean capturing;
    private boolean warmingUp;
    private int warmupFrames;
    private long warmupStartMs;
    private int pendingOrientation;

    private final Runnable openTimeout = () -> fail(Error.OPEN_TIMEOUT, "Camera did not open in time");
    private final Runnable captureTimeout = this::onCaptureTimeout;

    CameraController(Context context, Handler handler, Callback callback) {
        this.manager = context.getSystemService(CameraManager.class);
        this.handler = handler;
        this.executor = handler::post;
        this.callback = callback;
    }

    boolean isReady() {
        return ready;
    }

    // -------------------------------------------------------------------------------------------
    // Static queries (no permission needed)
    // -------------------------------------------------------------------------------------------

    static String findCameraId(CameraManager manager, Settings.Lens lens) {
        int wanted = lens == Settings.Lens.FRONT
                ? CameraCharacteristics.LENS_FACING_FRONT
                : CameraCharacteristics.LENS_FACING_BACK;
        try {
            for (String id : manager.getCameraIdList()) {
                Integer facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == wanted) {
                    return id;
                }
            }
        } catch (CameraAccessException | RuntimeException e) {
            Log.w(TAG, "Camera enumeration failed", e);
        }
        return null;
    }

    /** Every JPEG size the camera reports, including high-resolution sizes, largest first. */
    static List<Resolution> jpegSizes(CameraManager manager, String cameraId) {
        List<Resolution> sizes = new ArrayList<>();
        try {
            StreamConfigurationMap map = manager.getCameraCharacteristics(cameraId)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map != null) {
                addAll(sizes, map.getOutputSizes(ImageFormat.JPEG));
                addAll(sizes, map.getHighResolutionOutputSizes(ImageFormat.JPEG));
            }
        } catch (CameraAccessException | RuntimeException e) {
            Log.w(TAG, "Size query failed", e);
        }
        return Resolution.sortLargestFirst(sizes);
    }

    /**
     * True when {@code size} is offered only by {@code getHighResolutionOutputSizes}. Such sizes
     * are outside the guaranteed stream combinations (JPEG MAXIMUM refers to
     * {@code getOutputSizes}), so the JPEG + YUV metering session may be rejected by the device.
     */
    static boolean isHighResolutionOnly(CameraManager manager, String cameraId, Resolution size) {
        try {
            StreamConfigurationMap map = manager.getCameraCharacteristics(cameraId)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                return false;
            }
            return !contains(map.getOutputSizes(ImageFormat.JPEG), size)
                    && contains(map.getHighResolutionOutputSizes(ImageFormat.JPEG), size);
        } catch (CameraAccessException | RuntimeException e) {
            return false;
        }
    }

    private static boolean contains(Size[] sizes, Resolution r) {
        if (sizes != null) {
            for (Size s : sizes) {
                if (s.getWidth() == r.width && s.getHeight() == r.height) {
                    return true;
                }
            }
        }
        return false;
    }

    static int sensorOrientation(CameraManager manager, String cameraId) {
        try {
            Integer o = manager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SENSOR_ORIENTATION);
            return o == null ? 0 : o;
        } catch (CameraAccessException | RuntimeException e) {
            return 0;
        }
    }

    private static void addAll(List<Resolution> out, Size[] sizes) {
        if (sizes == null) {
            return;
        }
        for (Size s : sizes) {
            out.add(new Resolution(s.getWidth(), s.getHeight()));
        }
    }

    // -------------------------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------------------------

    /**
     * Sets the zoom ratio on a request, exactly as Preview and capture both need it: nothing for 1
     * (the camera's default), otherwise {@code CONTROL_ZOOM_RATIO}.
     */
    static void applyZoom(CaptureRequest.Builder b, float ratio) {
        if (Math.abs(ratio - 1f) > 0.001f) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, ratio);
        }
    }

    /**
     * Opens {@code cameraId} for stills at {@code jpegSize}, requesting {@code ratio} on every
     * frame. With {@code checkPhysicalId}, the session becomes ready only once the camera
     * reports that module active; otherwise it fails with {@link Error#LENS_NOT_ACTIVE}.
     */
    void open(String cameraId, float ratio, String checkPhysicalId, Resolution jpegSize, int quality,
            boolean continuous) {
        close();
        final int gen = generation;
        continuousMetering = continuous;
        jpegQuality = quality;
        zoomRatio = ratio;
        this.checkPhysicalId = checkPhysicalId;

        Size meterSize;
        try {
            CameraCharacteristics chars = manager.getCameraCharacteristics(cameraId);
            int[] afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            afContinuous = afModes != null && Arrays.stream(afModes)
                    .anyMatch(m -> m == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            meterSize = map == null ? null : smallestMeterSize(map.getOutputSizes(ImageFormat.YUV_420_888));
        } catch (CameraAccessException e) {
            fail(mapAccess(e), e.getMessage());
            return;
        } catch (RuntimeException e) {
            fail(Error.DEVICE, e.toString());
            return;
        }
        if (meterSize == null) {
            fail(Error.CONFIGURATION, "No YUV output size");
            return;
        }

        jpegReader = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2);
        jpegReader.setOnImageAvailableListener(r -> onJpegAvailable(gen, r), handler);
        meterReader = ImageReader.newInstance(meterSize.getWidth(), meterSize.getHeight(),
                ImageFormat.YUV_420_888, 2);
        meterReader.setOnImageAvailableListener(r -> {
            Image image = r.acquireLatestImage();
            if (image != null) {
                image.close();
            }
        }, handler);

        handler.postDelayed(openTimeout, OPEN_TIMEOUT_MS);
        try {
            manager.openCamera(cameraId, new DeviceCallback(gen), handler);
        } catch (CameraAccessException e) {
            fail(mapAccess(e), e.getMessage());
        } catch (SecurityException e) {
            fail(Error.PERMISSION, e.getMessage());
        } catch (RuntimeException e) {
            fail(Error.DEVICE, e.toString());
        }
    }

    /** Releases everything. Idempotent; safe to call from any state on the camera thread. */
    void close() {
        generation++;
        ready = false;
        capturing = false;
        warmingUp = false;
        checkingLens = false;
        handler.removeCallbacks(openTimeout);
        handler.removeCallbacks(captureTimeout);
        if (session != null) {
            try {
                session.close();
            } catch (RuntimeException e) {
                Log.w(TAG, "Session close failed", e);
            }
            session = null;
        }
        if (device != null) {
            try {
                device.close();
            } catch (RuntimeException e) {
                Log.w(TAG, "Device close failed", e);
            }
            device = null;
        }
        if (jpegReader != null) {
            jpegReader.close();
            jpegReader = null;
        }
        if (meterReader != null) {
            meterReader.close();
            meterReader = null;
        }
    }

    private void fail(Error error, String detail) {
        close();
        callback.onError(error, detail);
    }

    private static Size smallestMeterSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) {
            return null;
        }
        Size best = null;
        Size smallest = null;
        for (Size s : sizes) {
            long area = (long) s.getWidth() * s.getHeight();
            if (smallest == null || area < (long) smallest.getWidth() * smallest.getHeight()) {
                smallest = s;
            }
            if (s.getWidth() >= METER_MIN_WIDTH
                    && (best == null || area < (long) best.getWidth() * best.getHeight())) {
                best = s;
            }
        }
        return best != null ? best : smallest;
    }

    private final class DeviceCallback extends CameraDevice.StateCallback {
        private final int gen;

        DeviceCallback(int gen) {
            this.gen = gen;
        }

        @Override
        public void onOpened(CameraDevice camera) {
            if (gen != generation) {
                camera.close();
                return;
            }
            device = camera;
            createSession(gen);
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            if (gen != generation) {
                camera.close();
                return;
            }
            device = camera;
            fail(Error.DISCONNECTED, "Camera disconnected");
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            if (gen != generation) {
                camera.close();
                return;
            }
            device = camera;
            fail(mapStateError(error), "Camera device error " + error);
        }
    }

    private void createSession(int gen) {
        List<OutputConfiguration> outputs = List.of(
                new OutputConfiguration(jpegReader.getSurface()),
                new OutputConfiguration(meterReader.getSurface()));
        SessionConfiguration config = new SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                outputs, executor, new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(CameraCaptureSession s) {
                        if (gen != generation) {
                            s.close();
                            return;
                        }
                        session = s;
                        if (checkPhysicalId != null) {
                            // Ready only once the module is confirmed (MeterCallback); the open
                            // timeout keeps running meanwhile.
                            checkingLens = true;
                            checkFrames = 0;
                            startMetering();
                            return;
                        }
                        handler.removeCallbacks(openTimeout);
                        if (continuousMetering && !startMetering()) {
                            return;
                        }
                        ready = true;
                        callback.onReady();
                    }

                    @Override
                    public void onConfigureFailed(CameraCaptureSession s) {
                        if (gen != generation) {
                            return;
                        }
                        fail(Error.CONFIGURATION, "Capture session configuration failed");
                    }
                });
        try {
            device.createCaptureSession(config);
        } catch (CameraAccessException e) {
            fail(mapAccess(e), e.getMessage());
        } catch (RuntimeException e) {
            fail(Error.DEVICE, e.toString());
        }
    }

    // -------------------------------------------------------------------------------------------
    // Capture
    // -------------------------------------------------------------------------------------------

    /**
     * Requests one still. Returns false when the camera is not ready or a capture is already in
     * flight; the caller treats that as a skipped tick.
     */
    boolean capture(int jpegOrientation) {
        if (!ready || capturing) {
            return false;
        }
        capturing = true;
        pendingOrientation = jpegOrientation;
        handler.postDelayed(captureTimeout, CAPTURE_TIMEOUT_MS);
        if (continuousMetering) {
            issueStill();
        } else {
            warmingUp = true;
            warmupFrames = 0;
            warmupStartMs = SystemClock.elapsedRealtime();
            startMetering();
        }
        return true;
    }

    /** Starts the repeating metering request. Returns false if the camera failed. */
    private boolean startMetering() {
        try {
            CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            b.addTarget(meterReader.getSurface());
            b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            if (afContinuous) {
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            }
            applyZoom(b, zoomRatio);
            session.setRepeatingRequest(b.build(), new MeterCallback(generation), handler);
            return true;
        } catch (CameraAccessException e) {
            fail(mapAccess(e), e.getMessage());
        } catch (RuntimeException e) {
            fail(Error.DEVICE, e.toString());
        }
        return false;
    }

    private final class MeterCallback extends CameraCaptureSession.CaptureCallback {
        private final int gen;

        MeterCallback(int gen) {
            this.gen = gen;
        }

        @Override
        public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest request, TotalCaptureResult result) {
            if (gen != generation) {
                return;
            }
            if (checkingLens) {
                onLensCheckFrame(result);
                return;
            }
            if (!warmingUp) {
                return;
            }
            warmupFrames++;
            Integer ae = result.get(CaptureResult.CONTROL_AE_STATE);
            boolean converged = ae == null
                    || ae == CaptureResult.CONTROL_AE_STATE_CONVERGED
                    || ae == CaptureResult.CONTROL_AE_STATE_LOCKED
                    || ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED;
            boolean timeUp = SystemClock.elapsedRealtime() - warmupStartMs >= WARMUP_MAX_MS;
            if ((converged && warmupFrames >= WARMUP_MIN_FRAMES) || warmupFrames >= WARMUP_MAX_FRAMES || timeUp) {
                warmingUp = false;
                if (issueStill()) {
                    stopMetering();
                }
            }
        }
    }

    /** One metering frame while confirming the physical module; ready once it is active. */
    private void onLensCheckFrame(TotalCaptureResult result) {
        checkFrames++;
        String active = result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID);
        if (checkPhysicalId.equals(active)) {
            checkingLens = false;
            handler.removeCallbacks(openTimeout);
            Log.i(TAG, "Lens check " + checkPhysicalId + ": active");
            if (!continuousMetering && !stopMetering()) {
                return;
            }
            ready = true;
            callback.onReady();
        } else if (checkFrames >= LENS_CHECK_FRAMES) {
            fail(Error.LENS_NOT_ACTIVE, "Module " + checkPhysicalId + " not active (reported " + active + ")");
        }
    }

    /** Stops the repeating metering request. Returns false if the camera failed. */
    private boolean stopMetering() {
        try {
            session.stopRepeating();
            return true;
        } catch (CameraAccessException e) {
            fail(mapAccess(e), e.getMessage());
        } catch (RuntimeException e) {
            fail(Error.DEVICE, e.toString());
        }
        return false;
    }

    /** Submits the still request. Returns false if the camera failed. */
    private boolean issueStill() {
        try {
            CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            b.addTarget(jpegReader.getSurface());
            b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            if (afContinuous) {
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            }
            applyZoom(b, zoomRatio);
            b.set(CaptureRequest.JPEG_QUALITY, (byte) jpegQuality);
            b.set(CaptureRequest.JPEG_ORIENTATION, pendingOrientation);
            final int gen = generation;
            session.capture(b.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureFailed(CameraCaptureSession s, CaptureRequest r, CaptureFailure failure) {
                    if (gen != generation || !capturing) {
                        return;
                    }
                    endCapture();
                    callback.onCaptureFailed("Capture failed (reason " + failure.getReason() + ")");
                }
            }, handler);
            return true;
        } catch (CameraAccessException e) {
            fail(mapAccess(e), e.getMessage());
        } catch (RuntimeException e) {
            fail(Error.DEVICE, e.toString());
        }
        return false;
    }

    private void onJpegAvailable(int gen, ImageReader reader) {
        byte[] bytes;
        try (Image image = reader.acquireNextImage()) {
            if (image == null) {
                return;
            }
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not read JPEG", e);
            return;
        }
        if (gen != generation || !capturing) {
            // Arrived after a timeout or a close: it was already reported as failed.
            Log.w(TAG, "Discarding late JPEG");
            return;
        }
        endCapture();
        callback.onJpeg(bytes);
    }

    private void onCaptureTimeout() {
        if (!capturing) {
            return;
        }
        boolean wasWarmingUp = warmingUp;
        endCapture();
        if (!continuousMetering && wasWarmingUp && session != null && !stopMetering()) {
            return; // the camera failed and onError was already reported
        }
        callback.onCaptureFailed("Capture timed out");
    }

    private void endCapture() {
        capturing = false;
        warmingUp = false;
        handler.removeCallbacks(captureTimeout);
    }

    // -------------------------------------------------------------------------------------------
    // Error mapping
    // -------------------------------------------------------------------------------------------

    private static Error mapAccess(CameraAccessException e) {
        return switch (e.getReason()) {
            case CameraAccessException.CAMERA_IN_USE,
                    CameraAccessException.MAX_CAMERAS_IN_USE -> Error.IN_USE;
            case CameraAccessException.CAMERA_DISABLED -> Error.DISABLED;
            case CameraAccessException.CAMERA_DISCONNECTED -> Error.DISCONNECTED;
            default -> Error.DEVICE;
        };
    }

    private static Error mapStateError(int code) {
        return switch (code) {
            case CameraDevice.StateCallback.ERROR_CAMERA_IN_USE,
                    CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> Error.IN_USE;
            case CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> Error.DISABLED;
            default -> Error.DEVICE;
        };
    }
}
