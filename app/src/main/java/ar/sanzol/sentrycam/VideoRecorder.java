package ar.sanzol.sentrycam;

import android.content.Context;
import android.graphics.ImageFormat;
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
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Continuous video with Camera2 and {@link MediaRecorder}, one file at a time.
 *
 * <p>The camera session streams into a persistent input surface
 * ({@link MediaCodec#createPersistentInputSurface}), so between two files only the recorder is
 * stopped (which finalizes the MP4) and started again on the next file; the camera session stays
 * open. That restart leaves a short gap between files (typically well under a second; it
 * depends on the phone). Camera, zoom ratio and module check come from
 * {@link LensProbe.Target}, as for photos and Preview.
 *
 * <p>Every method and callback runs on the camera {@link Handler} thread passed in; the
 * recorder is created there, so its listeners run there too. The caller owns the output files.
 */
final class VideoRecorder {

    interface Callback {
        /** The camera is open, the lens confirmed, and the first file is recording. */
        void onRecording();

        /** The current file reached the size limit; the recorder stopped it by itself. */
        void onFileFull();

        /**
         * The camera or recorder can no longer be used; everything is closed. {@code finalized}
         * says whether the file that was recording was finalized (playable).
         */
        void onError(CameraController.Error error, String detail, boolean finalized);
    }

    /** What the encoder records. */
    static final class Format {
        final Resolution size;
        final int bitrate;
        final boolean audio;
        final Range<Integer> fpsRange;

        Format(Resolution size, int bitrate, boolean audio, Range<Integer> fpsRange) {
            this.size = size;
            this.bitrate = bitrate;
            this.audio = audio;
            this.fpsRange = fpsRange;
        }
    }

    private static final String TAG = "SentryCam";
    private static final long OPEN_TIMEOUT_MS = 10_000;
    private static final int CHECK_MIN_WIDTH = 320;

    private final Context context;
    private final CameraManager manager;
    private final Handler handler;
    private final Callback callback;

    private int generation;
    private CameraDevice device;
    private CameraCaptureSession session;
    private Surface inputSurface;
    private ImageReader checkReader;
    private MediaRecorder recorder;

    private Format format;
    private float zoomRatio = 1f;
    private String checkPhysicalId;
    private int checkFrames;
    private boolean afVideo;
    private boolean recording;
    /** The recorder stopped the file by itself at the size limit (already finalized). */
    private boolean stoppedBySizeLimit;
    private long fileStartElapsed;

    private final Runnable openTimeout =
            () -> fail(CameraController.Error.OPEN_TIMEOUT, "Camera did not open in time", false);

    VideoRecorder(Context context, Handler handler, Callback callback) {
        this.context = context;
        this.manager = context.getSystemService(CameraManager.class);
        this.handler = handler;
        this.callback = callback;
    }

    boolean isRecording() {
        return recording;
    }

    /** elapsedRealtime when the current file started; 0 when not recording. */
    long fileStartElapsed() {
        return recording ? fileStartElapsed : 0;
    }

    /**
     * Opens the camera and starts recording into {@code first}. With {@code checkPhysicalId},
     * recording starts only once that module is reported active; otherwise
     * {@link CameraController.Error#LENS_NOT_ACTIVE}, with nothing recorded.
     */
    void open(LensProbe.Target target, Format format, VideoStore.Output first, int orientationHint) {
        close();
        final int gen = generation;
        this.format = format;
        this.zoomRatio = target.zoomRatio;
        this.checkPhysicalId = target.checkPhysicalId;
        try {
            CameraCharacteristics chars = manager.getCameraCharacteristics(target.cameraId);
            int[] afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            afVideo = afModes != null && Arrays.stream(afModes)
                    .anyMatch(m -> m == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            if (checkPhysicalId != null) {
                StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                Size s = map == null ? null : smallest(map.getOutputSizes(ImageFormat.YUV_420_888));
                if (s == null) {
                    fail(CameraController.Error.CONFIGURATION, "No YUV size for the lens check", false);
                    return;
                }
                checkReader = ImageReader.newInstance(s.getWidth(), s.getHeight(), ImageFormat.YUV_420_888, 2);
                checkReader.setOnImageAvailableListener(r -> {
                    Image image = r.acquireLatestImage();
                    if (image != null) {
                        image.close();
                    }
                }, handler);
            }
            inputSurface = MediaCodec.createPersistentInputSurface();
            prepare(first, orientationHint);
        } catch (CameraAccessException e) {
            fail(mapAccess(e), e.getMessage(), false);
            return;
        } catch (IOException | RuntimeException e) {
            fail(CameraController.Error.CONFIGURATION, "Recorder setup failed: " + e, false);
            return;
        }
        handler.postDelayed(openTimeout, OPEN_TIMEOUT_MS);
        try {
            manager.openCamera(target.cameraId, new DeviceCallback(gen), handler);
        } catch (CameraAccessException e) {
            fail(mapAccess(e), e.getMessage(), false);
        } catch (SecurityException e) {
            fail(CameraController.Error.PERMISSION, e.getMessage(), false);
        } catch (RuntimeException e) {
            fail(CameraController.Error.DEVICE, e.toString(), false);
        }
    }

    /**
     * Ends the current file: stops the camera stream and the recorder, which finalizes the MP4.
     * Returns true when the file is finalized (playable), false when the recorder could not
     * finalize it (for example, no frame was recorded). The camera session stays open.
     */
    boolean endFile() {
        if (!recording) {
            return false;
        }
        recording = false;
        if (session != null) {
            try {
                session.stopRepeating();
            } catch (CameraAccessException | RuntimeException e) {
                Log.w(TAG, "stopRepeating failed", e);
            }
        }
        boolean finalized;
        try {
            recorder.stop();
            finalized = true;
        } catch (RuntimeException e) {
            // After the size limit the recorder has already stopped and finalized the file.
            finalized = stoppedBySizeLimit;
            if (!finalized) {
                Log.w(TAG, "Recorder stop failed: the file is not playable", e);
            }
        }
        stoppedBySizeLimit = false;
        try {
            recorder.reset();
        } catch (RuntimeException e) {
            Log.w(TAG, "Recorder reset failed", e);
        }
        return finalized;
    }

    /** Starts the next file on the open camera session. Returns false if that failed (closed). */
    boolean beginFile(VideoStore.Output next, int orientationHint) {
        if (session == null || recording) {
            return false;
        }
        try {
            prepare(next, orientationHint);
            startRecording();
            return true;
        } catch (CameraAccessException e) {
            fail(mapAccess(e), e.getMessage(), false);
        } catch (IOException | RuntimeException e) {
            fail(CameraController.Error.DEVICE, "Recorder restart failed: " + e, false);
        }
        return false;
    }

    /** Releases everything. A file still recording is not finalized: call endFile first. */
    void close() {
        generation++;
        recording = false;
        stoppedBySizeLimit = false;
        handler.removeCallbacks(openTimeout);
        if (session != null) {
            try {
                session.close();
            } catch (RuntimeException e) {
                Log.w(TAG, "Video session close failed", e);
            }
            session = null;
        }
        if (device != null) {
            try {
                device.close();
            } catch (RuntimeException e) {
                Log.w(TAG, "Video device close failed", e);
            }
            device = null;
        }
        if (recorder != null) {
            recorder.release();
            recorder = null;
        }
        if (checkReader != null) {
            checkReader.close();
            checkReader = null;
        }
        if (inputSurface != null) {
            inputSurface.release();
            inputSurface = null;
        }
    }

    // -------------------------------------------------------------------------------------------

    private void prepare(VideoStore.Output out, int orientationHint) throws IOException {
        if (recorder == null) {
            recorder = new MediaRecorder(context);
        }
        MediaRecorder r = recorder;
        if (format.audio) {
            r.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
        }
        r.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        r.setOutputFile(out.pfd.getFileDescriptor());
        r.setMaxFileSize(VideoPlan.MAX_FILE_BYTES);
        r.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        r.setVideoSize(format.size.width, format.size.height);
        r.setVideoFrameRate(VideoPlan.FRAME_RATE);
        r.setVideoEncodingBitRate(format.bitrate);
        if (format.audio) {
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            r.setAudioChannels(1);
            r.setAudioSamplingRate(VideoPlan.AUDIO_SAMPLE_RATE);
            r.setAudioEncodingBitRate(VideoPlan.AUDIO_BITRATE);
        }
        r.setOrientationHint(orientationHint);
        r.setInputSurface(inputSurface);
        final int gen = generation;
        r.setOnErrorListener((mr, what, extra) -> {
            if (gen == generation) {
                Log.w(TAG, "Recorder error " + what + "/" + extra);
                failRecording(CameraController.Error.DEVICE, "Recorder error " + what + "/" + extra);
            }
        });
        r.setOnInfoListener((mr, what, extra) -> {
            if (gen == generation && recording && what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
                stoppedBySizeLimit = true;
                callback.onFileFull();
            }
        });
        r.prepare();
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
            failRecording(CameraController.Error.DISCONNECTED, "Camera disconnected");
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            if (gen != generation) {
                camera.close();
                return;
            }
            device = camera;
            CameraController.Error e = switch (error) {
                case ERROR_CAMERA_IN_USE, ERROR_MAX_CAMERAS_IN_USE -> CameraController.Error.IN_USE;
                case ERROR_CAMERA_DISABLED -> CameraController.Error.DISABLED;
                default -> CameraController.Error.DEVICE;
            };
            failRecording(e, "Camera device error " + error);
        }
    }

    private void createSession(int gen) {
        List<OutputConfiguration> outputs = new ArrayList<>();
        outputs.add(new OutputConfiguration(inputSurface));
        if (checkReader != null) {
            outputs.add(new OutputConfiguration(checkReader.getSurface()));
        }
        SessionConfiguration config = new SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs,
                handler::post, new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(CameraCaptureSession s) {
                        if (gen != generation) {
                            s.close();
                            return;
                        }
                        session = s;
                        try {
                            if (checkPhysicalId != null) {
                                startLensCheck();
                            } else {
                                handler.removeCallbacks(openTimeout);
                                startRecording();
                                callback.onRecording();
                            }
                        } catch (CameraAccessException e) {
                            fail(mapAccess(e), e.getMessage(), false);
                        } catch (RuntimeException e) {
                            fail(CameraController.Error.DEVICE, "Recording start failed: " + e, false);
                        }
                    }

                    @Override
                    public void onConfigureFailed(CameraCaptureSession s) {
                        if (gen == generation) {
                            fail(CameraController.Error.CONFIGURATION, "Video session configuration failed", false);
                        }
                    }
                });
        try {
            device.createCaptureSession(config);
        } catch (CameraAccessException e) {
            fail(mapAccess(e), e.getMessage(), false);
        } catch (RuntimeException e) {
            fail(CameraController.Error.DEVICE, e.toString(), false);
        }
    }

    /** Streams to the small check output only, so nothing is recorded before the check passes. */
    private void startLensCheck() throws CameraAccessException {
        checkFrames = 0;
        CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        b.addTarget(checkReader.getSurface());
        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        CameraController.applyZoom(b, zoomRatio);
        final int gen = generation;
        session.setRepeatingRequest(b.build(), new CameraCaptureSession.CaptureCallback() {
            @Override
            public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest request, TotalCaptureResult result) {
                if (gen != generation || checkPhysicalId == null) {
                    return;
                }
                checkFrames++;
                String active = result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID);
                if (checkPhysicalId.equals(active)) {
                    Log.i(TAG, "Lens check " + checkPhysicalId + ": active");
                    checkPhysicalId = null;
                    handler.removeCallbacks(openTimeout);
                    try {
                        session.stopRepeating();
                        startRecording();
                        callback.onRecording();
                    } catch (CameraAccessException e) {
                        fail(mapAccess(e), e.getMessage(), false);
                    } catch (RuntimeException e) {
                        fail(CameraController.Error.DEVICE, "Recording start failed: " + e, false);
                    }
                } else if (checkFrames >= CameraController.LENS_CHECK_FRAMES) {
                    fail(CameraController.Error.LENS_NOT_ACTIVE,
                            "Module " + checkPhysicalId + " not active (reported " + active + ")", false);
                }
            }
        }, handler);
    }

    private void startRecording() throws CameraAccessException {
        recorder.start();
        recording = true;
        stoppedBySizeLimit = false;
        fileStartElapsed = SystemClock.elapsedRealtime();
        CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
        b.addTarget(inputSurface);
        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        if (afVideo) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
        }
        if (format.fpsRange != null) {
            b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, format.fpsRange);
        }
        // Same framing as Preview: same zoom rule, and no stabilization crop.
        CameraController.applyZoom(b, zoomRatio);
        b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
        session.setRepeatingRequest(b.build(), null, handler);
    }

    /** A failure while a file may be recording: finalize it if possible, then close and report. */
    private void failRecording(CameraController.Error error, String detail) {
        boolean finalized = recording && endFileQuietly();
        fail(error, detail, finalized);
    }

    private boolean endFileQuietly() {
        try {
            return endFile();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void fail(CameraController.Error error, String detail, boolean finalized) {
        close();
        callback.onError(error, detail, finalized);
    }

    private static Size smallest(Size[] sizes) {
        Size best = null;
        if (sizes != null) {
            for (Size s : sizes) {
                if (s.getWidth() >= CHECK_MIN_WIDTH
                        && (best == null || (long) s.getWidth() * s.getHeight() < (long) best.getWidth() * best.getHeight())) {
                    best = s;
                }
            }
        }
        return best;
    }

    private static CameraController.Error mapAccess(CameraAccessException e) {
        return switch (e.getReason()) {
            case CameraAccessException.CAMERA_IN_USE, CameraAccessException.MAX_CAMERAS_IN_USE ->
                    CameraController.Error.IN_USE;
            case CameraAccessException.CAMERA_DISABLED -> CameraController.Error.DISABLED;
            case CameraAccessException.CAMERA_DISCONNECTED -> CameraController.Error.DISCONNECTED;
            default -> CameraController.Error.DEVICE;
        };
    }
}
