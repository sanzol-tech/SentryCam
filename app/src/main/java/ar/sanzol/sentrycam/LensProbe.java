package ar.sanzol.sentrycam;

import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.util.SizeF;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads what Camera2 reports for every camera: the back-lens choices ({@link LensCatalog}), the
 * {@link Target} that Preview and capture both open, and a diagnostics log under the
 * {@code SentryCam} tag. Read-only: no camera is opened here.
 */
final class LensProbe {

    private static final String TAG = "SentryCam";

    private LensProbe() {
    }

    /** What Camera2 reports: listed cameras in Android's order and physical members by id. */
    static final class Snapshot {
        final List<LensCatalog.CameraInfo> listed = new ArrayList<>();
        final Map<String, LensCatalog.CameraInfo> physical = new HashMap<>();
    }

    /**
     * What Preview and capture open for the configured camera. Both use this one rule, so the lens
     * shown by Preview is the lens that takes the photo.
     */
    static final class Target {
        final String cameraId;
        /** Zoom ratio to request on every frame (1 = none). */
        final float zoomRatio;
        /** Physical module to confirm before this lens is trusted; null when not needed. */
        final String checkPhysicalId;
        /** The back-lens choice; null for the front camera. */
        final LensCatalog.Choice choice;
        /** The saved lens is not offered on this phone now; the main back lens is used. */
        final boolean fellBack;
        /** How many back lenses are offered (chips shown when more than one). */
        final int offered;

        Target(String cameraId, float zoomRatio, String checkPhysicalId, LensCatalog.Choice choice,
                boolean fellBack, int offered) {
            this.cameraId = cameraId;
            this.zoomRatio = zoomRatio;
            this.checkPhysicalId = checkPhysicalId;
            this.choice = choice;
            this.fellBack = fellBack;
            this.offered = offered;
        }

        /** Chip text of the lens ("0.5"), or null for the front camera. */
        String factorLabel() {
            return choice == null ? null : LensCatalog.formatFactor(choice.factor);
        }

        @Override
        public String toString() {
            return "camera " + cameraId + (zoomRatio != 1f ? " zoom " + zoomRatio : "")
                    + (choice != null ? " lens " + choice.key : "")
                    + (checkPhysicalId != null ? " (check " + checkPhysicalId + ")" : "")
                    + (fellBack ? " (saved lens not offered, main lens used)" : "");
        }
    }

    /** The target for the saved settings. Camera-service IPC: call off the main thread. */
    static Target target(CameraManager manager, Settings.Lens lens, SettingsStore store) {
        return target(manager, lens, store.getLensKey(), store.getFailedLensChecks(),
                store.getPassedLensChecks());
    }

    /**
     * Front: the first front camera, as before. Back: the saved lens choice, or the main back
     * lens when it is no longer offered ({@link Target#fellBack}). Null when there is no camera.
     */
    static Target target(CameraManager manager, Settings.Lens lens, String lensKey, Set<String> failedChecks,
            Set<String> passedChecks) {
        if (lens == Settings.Lens.BACK) {
            List<LensCatalog.Choice> choices = choices(manager, failedChecks);
            LensCatalog.Choice c = LensCatalog.resolve(choices, lensKey);
            if (c != null) {
                return new Target(c.openId, c.zoomRatio, LensCatalog.pendingCheck(c, passedChecks), c,
                        !c.key.equals(lensKey), choices.size());
            }
        }
        String id = CameraController.findCameraId(manager, lens);
        return id == null ? null : new Target(id, 1f, null, null, false, 1);
    }

    /** The back-lens choices for this phone. Camera-service IPC: call off the main thread. */
    static List<LensCatalog.Choice> choices(CameraManager manager, Set<String> failedChecks) {
        Snapshot s = snapshot(manager, false);
        return LensCatalog.choices(s.listed, s.physical, failedChecks);
    }

    /** Logs the lens report. Does camera-service IPC: call off the main thread. */
    static void logLenses(CameraManager manager) {
        Snapshot s = snapshot(manager, true);
        List<LensCatalog.Lens> lenses = LensCatalog.backLenses(s.listed, s.physical);
        Log.i(TAG, "Lenses on " + Build.MANUFACTURER + " " + Build.MODEL + " (Android "
                + Build.VERSION.RELEASE + "): " + lenses.size() + " back, "
                + LensCatalog.offerable(lenses).size() + " offerable");
        for (LensCatalog.Lens lens : lenses) {
            Log.i(TAG, "Lens " + lens);
        }
    }

    private static Snapshot snapshot(CameraManager manager, boolean log) {
        Snapshot snap = new Snapshot();
        List<LensCatalog.CameraInfo> listed = snap.listed;
        Map<String, LensCatalog.CameraInfo> physical = snap.physical;
        try {
            String[] ids = manager.getCameraIdList();
            for (String id : ids) {
                LensCatalog.CameraInfo info = read(manager, id, log);
                if (info == null) {
                    continue;
                }
                listed.add(info);
                if (log) {
                    Log.i(TAG, "Camera " + id + (info.back ? " back" : " front/other")
                            + ", focal " + info.focalMm + " mm, sensor " + info.sensorLongMm + " mm, fov "
                            + String.format(Locale.ROOT, "%.1f",
                                    LensCatalog.fovDegrees(info.focalMm, info.sensorLongMm))
                            + ", zoom " + info.zoomMin + "-" + info.zoomMax
                            + ", largest JPEG " + info.largestJpeg
                            + (info.isLogical() ? ", logical of " + info.physicalIds : ""));
                }
                for (String pid : info.physicalIds) {
                    if (!physical.containsKey(pid)) {
                        LensCatalog.CameraInfo p = read(manager, pid, log);
                        if (p != null) {
                            physical.put(pid, p);
                        }
                    }
                }
            }
        } catch (CameraAccessException | RuntimeException e) {
            Log.w(TAG, "Lens probe failed", e);
        }
        return snap;
    }

    private static LensCatalog.CameraInfo read(CameraManager manager, String id, boolean log) {
        try {
            CameraCharacteristics c = manager.getCameraCharacteristics(id);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            // A logical camera may list several focal lengths (one per module); the first is taken
            // as its default and the whole list is logged, so device logs can confirm the rule.
            float[] focals = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            float focal = focals != null && focals.length > 0 ? focals[0] : 0;
            if (log && focals != null && focals.length > 1) {
                Log.i(TAG, "Camera " + id + " focal lengths " + Arrays.toString(focals));
            }
            SizeF sensor = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            float sensorLong = sensor == null ? 0 : Math.max(sensor.getWidth(), sensor.getHeight());
            Range<Float> zoom = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
            Resolution largest = null;
            StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size[] jpeg = map == null ? null : map.getOutputSizes(ImageFormat.JPEG);
            if (jpeg != null) {
                for (Size s : jpeg) {
                    if (largest == null || (long) s.getWidth() * s.getHeight() > largest.pixels()) {
                        largest = new Resolution(s.getWidth(), s.getHeight());
                    }
                }
            }
            return new LensCatalog.CameraInfo(id,
                    facing != null && facing == CameraCharacteristics.LENS_FACING_BACK,
                    focal, sensorLong, new ArrayList<>(c.getPhysicalCameraIds()),
                    zoom == null ? 1f : zoom.getLower(), zoom == null ? 1f : zoom.getUpper(), largest);
        } catch (CameraAccessException | RuntimeException e) {
            Log.w(TAG, "Camera " + id + ": characteristics not readable", e);
            return null;
        }
    }
}
