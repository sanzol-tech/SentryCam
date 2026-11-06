package ar.sanzol.sentrycam;

import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.util.Log;
import android.util.Range;
import android.util.Size;

import java.util.ArrayList;
import java.util.List;

/**
 * What this phone can really record: video sizes the camera can stream to a recorder at the
 * nominal frame rate and the H.264 encoder accepts, the encoder's bitrate range and the
 * camera's frame-rate range. Camera-service and codec IPC: call off the main thread.
 */
final class VideoCaps {

    private static final String TAG = "SentryCam";
    private static final long FRAME_NS = 1_000_000_000L / VideoPlan.FRAME_RATE;

    private VideoCaps() {
    }

    /** Video sizes for {@code cameraId}, largest first; empty when none. */
    static List<Resolution> videoSizes(CameraManager manager, String cameraId) {
        List<Resolution> out = new ArrayList<>();
        try {
            StreamConfigurationMap map = manager.getCameraCharacteristics(cameraId)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size[] sizes = map == null ? null : map.getOutputSizes(MediaRecorder.class);
            MediaCodecInfo.VideoCapabilities encoder = avcEncoder();
            if (sizes != null) {
                for (Size s : sizes) {
                    // At the frame rate requested, with a small tolerance for 29.97-style rounding.
                    long minFrame = map.getOutputMinFrameDuration(MediaRecorder.class, s);
                    boolean fastEnough = minFrame <= FRAME_NS + FRAME_NS / 100;
                    boolean encodable = encoder == null || encoder.areSizeAndRateSupported(s.getWidth(),
                            s.getHeight(), VideoPlan.FRAME_RATE);
                    if (fastEnough && encodable) {
                        out.add(new Resolution(s.getWidth(), s.getHeight()));
                    }
                }
            }
        } catch (CameraAccessException | RuntimeException e) {
            Log.w(TAG, "Video size query failed", e);
        }
        return Resolution.sortLargestFirst(out);
    }

    /** The encoder's bitrate range {min, max} in bits per second; {0, 0} when unknown. */
    static int[] bitrateRange() {
        MediaCodecInfo.VideoCapabilities encoder = avcEncoder();
        if (encoder == null) {
            return new int[] {0, 0};
        }
        Range<Integer> r = encoder.getBitrateRange();
        return new int[] {r.getLower(), r.getUpper()};
    }

    /**
     * The auto-exposure frame-rate range to request: top at the nominal rate and the lowest
     * bottom, so the camera may slow down in the dark for a brighter picture. Null when the
     * camera lists none with that top (the camera then chooses).
     */
    static Range<Integer> fpsRange(CameraManager manager, String cameraId) {
        try {
            Range<Integer>[] ranges = manager.getCameraCharacteristics(cameraId)
                    .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            Range<Integer> best = null;
            if (ranges != null) {
                for (Range<Integer> r : ranges) {
                    if (r.getUpper() == VideoPlan.FRAME_RATE && (best == null || r.getLower() < best.getLower())) {
                        best = r;
                    }
                }
            }
            return best;
        } catch (CameraAccessException | RuntimeException e) {
            return null;
        }
    }

    /** The H.264 encoder MediaRecorder will normally use: the first hardware one. */
    private static MediaCodecInfo.VideoCapabilities avcEncoder() {
        MediaCodecInfo.VideoCapabilities fallback = null;
        try {
            for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (!info.isEncoder()) {
                    continue;
                }
                for (String type : info.getSupportedTypes()) {
                    if (!type.equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_AVC)) {
                        continue;
                    }
                    MediaCodecInfo.VideoCapabilities caps = info.getCapabilitiesForType(type).getVideoCapabilities();
                    if (caps == null) {
                        continue;
                    }
                    if (info.isHardwareAccelerated()) {
                        return caps;
                    }
                    if (fallback == null) {
                        fallback = caps;
                    }
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Encoder query failed", e);
        }
        return fallback;
    }
}
