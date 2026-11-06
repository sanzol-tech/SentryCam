package ar.sanzol.sentrycam;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Back-lens discovery rules, pure Java so they are unit-testable. Input is what Camera2
 * reports for each camera; output is one entry per lens with its zoom factor relative to the
 * main back camera and how the app could reach it. Nothing here opens a camera.
 *
 * <p>Access is classified conservatively:
 * <ul>
 *   <li>{@link Access#CAMERA_ID}: an id Android lists for apps; it can be opened on its own.</li>
 *   <li>{@link Access#LOGICAL_ZOOM}: a physical member of a logical camera whose factor lies in
 *       that camera's zoom-ratio range. The ratio is a request, not a guarantee that this
 *       exact module is used; only device evidence can confirm it.</li>
 *   <li>{@link Access#NOT_REACHABLE}: known to exist, but neither listed nor inside the zoom
 *       range. Never offered.</li>
 * </ul>
 * Fields of view come from focal length and sensor size: an approximation (crops and modes can
 * change the real framing).
 */
final class LensCatalog {

    enum Access { CAMERA_ID, LOGICAL_ZOOM, NOT_REACHABLE }

    /** What Camera2 reports for one camera id (listed, or physical member of a logical one). */
    static final class CameraInfo {
        final String id;
        final boolean back;
        /** The camera's first (default) focal length, mm; 0 when unknown. */
        final float focalMm;
        /** Longer side of the sensor's physical size, mm; 0 when unknown. */
        final float sensorLongMm;
        /** Physical member ids when this is a logical multi-camera; empty otherwise. */
        final List<String> physicalIds;
        /** CONTROL_ZOOM_RATIO_RANGE; 1..1 when not reported. */
        final float zoomMin;
        final float zoomMax;
        final Resolution largestJpeg;

        CameraInfo(String id, boolean back, float focalMm, float sensorLongMm, List<String> physicalIds,
                float zoomMin, float zoomMax, Resolution largestJpeg) {
            this.id = id;
            this.back = back;
            this.focalMm = focalMm;
            this.sensorLongMm = sensorLongMm;
            this.physicalIds = physicalIds == null ? Collections.emptyList() : physicalIds;
            this.zoomMin = zoomMin;
            this.zoomMax = zoomMax;
            this.largestJpeg = largestJpeg;
        }

        boolean isLogical() {
            return !physicalIds.isEmpty();
        }
    }

    static final class Lens {
        /** The id to open: the lens's own id, or its logical camera's id. */
        final String openId;
        /** The physical module id when reached through a logical camera; null otherwise. */
        final String physicalId;
        final double fovDegrees;
        /** Zoom factor relative to the main back camera, rounded to one decimal. */
        final double factor;
        final Access access;
        final String note;

        Lens(String openId, String physicalId, double fovDegrees, double factor, Access access, String note) {
            this.openId = openId;
            this.physicalId = physicalId;
            this.fovDegrees = fovDegrees;
            this.factor = factor;
            this.access = access;
            this.note = note;
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "%sx open=%s%s fov=%.1f %s%s", formatFactor(factor), openId,
                    physicalId == null ? "" : " physical=" + physicalId, fovDegrees, access,
                    note == null ? "" : " (" + note + ")");
        }
    }

    /** Two factors closer than this are the same lens. */
    static final double SAME_FACTOR = 0.05;

    private LensCatalog() {
    }

    /** Horizontal field of view in degrees, or 0 when the inputs are unknown. */
    static double fovDegrees(float focalMm, float sensorLongMm) {
        if (focalMm <= 0 || sensorLongMm <= 0) {
            return 0;
        }
        return Math.toDegrees(2 * Math.atan(sensorLongMm / (2.0 * focalMm)));
    }

    /** Zoom factor of a lens with {@code fov} relative to {@code mainFov}: tan ratio, one decimal. */
    static double factor(double mainFov, double fov) {
        if (mainFov <= 0 || fov <= 0) {
            return 0;
        }
        double f = Math.tan(Math.toRadians(mainFov / 2)) / Math.tan(Math.toRadians(fov / 2));
        return Math.round(f * 10) / 10.0;
    }

    /** "0.5", "1", "2", "2.5": the chip text without the ×. */
    static String formatFactor(double factor) {
        if (Math.abs(factor - Math.rint(factor)) < 1e-9) {
            return String.valueOf((long) Math.rint(factor));
        }
        return String.format(Locale.ROOT, "%.1f", factor);
    }

    /**
     * Back lenses, widest first (unknown factors last). {@code listed} are the ids Android
     * lists for apps, in its order; the first back one is the main camera (factor 1).
     * {@code physical} holds the characteristics of physical members by id (members that are
     * also listed may be absent).
     */
    static List<Lens> backLenses(List<CameraInfo> listed, Map<String, CameraInfo> physical) {
        List<Lens> lenses = new ArrayList<>();
        CameraInfo main = null;
        for (CameraInfo c : listed) {
            if (c.back) {
                main = c;
                break;
            }
        }
        if (main == null) {
            return lenses;
        }
        double mainFov = fovDegrees(main.focalMm, main.sensorLongMm);
        List<String> listedIds = new ArrayList<>();
        for (CameraInfo c : listed) {
            listedIds.add(c.id);
        }

        for (CameraInfo c : listed) {
            if (!c.back) {
                continue;
            }
            double fov = fovDegrees(c.focalMm, c.sensorLongMm);
            double f = c == main ? 1.0 : factor(mainFov, fov);
            add(lenses, new Lens(c.id, null, fov, f, Access.CAMERA_ID,
                    f == 0 ? "no focal length or sensor size" : null));
        }
        for (CameraInfo logical : listed) {
            if (!logical.back || !logical.isLogical()) {
                continue;
            }
            for (String pid : logical.physicalIds) {
                if (listedIds.contains(pid)) {
                    continue; // already a CAMERA_ID entry
                }
                CameraInfo p = physical.get(pid);
                if (p == null) {
                    add(lenses, new Lens(logical.id, pid, 0, 0, Access.NOT_REACHABLE,
                            "no characteristics"));
                    continue;
                }
                double fov = fovDegrees(p.focalMm, p.sensorLongMm);
                double f = factor(mainFov, fov);
                boolean inRange = f > 0 && f >= logical.zoomMin - SAME_FACTOR && f <= logical.zoomMax + SAME_FACTOR;
                Access access = inRange ? Access.LOGICAL_ZOOM : Access.NOT_REACHABLE;
                add(lenses, new Lens(logical.id, pid, fov, f, access,
                        inRange ? "ratio request, module not guaranteed"
                                : String.format(Locale.ROOT, "outside zoom range %.2f-%.2f",
                                        logical.zoomMin, logical.zoomMax)));
            }
        }
        // Widest first; lenses with an unknown factor at the end.
        lenses.sort(Comparator.comparingDouble((Lens l) -> l.factor > 0 ? l.factor : Double.MAX_VALUE));
        return lenses;
    }

    /** Lenses that could be offered: reachable, with a known factor. */
    static List<Lens> offerable(List<Lens> lenses) {
        List<Lens> out = new ArrayList<>();
        for (Lens l : lenses) {
            if (l.access != Access.NOT_REACHABLE && l.factor > 0) {
                out.add(l);
            }
        }
        return out;
    }

    /** Key of the main back lens, the default and the fallback. */
    static final String MAIN_KEY = "back:1";

    /** A back lens the user can pick in Camera Preview. */
    static final class Choice {
        /** Saved preference; independent of the chip text. */
        final String key;
        final double factor;
        /** Camera id to open and the zoom ratio to request on it (1 = none). */
        final String openId;
        final float zoomRatio;
        /**
         * Physical module that must be reported active before this choice is trusted; null when
         * the access path needs no check (a listed id, or a ratio below 1, which no crop of the
         * main lens can produce).
         */
        final String checkPhysicalId;

        Choice(String key, double factor, String openId, float zoomRatio, String checkPhysicalId) {
            this.key = key;
            this.factor = factor;
            this.openId = openId;
            this.zoomRatio = zoomRatio;
            this.checkPhysicalId = checkPhysicalId;
        }

        /** Chip text: the factor with × ("0.5×", "1×", "2×"); the selected chip differs by style. */
        String label() {
            return formatFactor(factor) + "×";
        }

        /** Identifies the module check, for remembering its result. */
        String checkKey() {
            return checkPhysicalId == null ? null : openId + ":" + checkPhysicalId;
        }
    }

    static String keyFor(double factor) {
        return "back:" + formatFactor(factor);
    }

    /**
     * The back lenses to offer, widest first; the main lens is always first among equals and
     * always present when a back camera exists. Rules, the same on every phone:
     * <ul>
     *   <li>another listed back id: offered as it is (opened directly);</li>
     *   <li>a zoom range below 1 on the main camera, when no listed id is already wide: a wide
     *       choice at that ratio, with no check, since only a wider module can produce it;</li>
     *   <li>a physical member above 1 inside the zoom range: offered with a module check, unless
     *       that check already failed ({@code rejectedChecks});</li>
     *   <li>nothing else (no digital-zoom levels).</li>
     * </ul>
     */
    static List<Choice> choices(List<CameraInfo> listed, Map<String, CameraInfo> physical,
            Set<String> rejectedChecks) {
        List<Choice> out = new ArrayList<>();
        CameraInfo main = null;
        for (CameraInfo c : listed) {
            if (c.back) {
                main = c;
                break;
            }
        }
        if (main == null) {
            return out;
        }
        out.add(new Choice(MAIN_KEY, 1.0, main.id, 1f, null));
        for (Lens lens : backLenses(listed, physical)) {
            if (lens.factor <= 0 || Math.abs(lens.factor - 1.0) < SAME_FACTOR) {
                continue;
            }
            if (lens.access == Access.CAMERA_ID) {
                addChoice(out, new Choice(keyFor(lens.factor), lens.factor, lens.openId, 1f, null));
            } else if (lens.access == Access.LOGICAL_ZOOM && lens.factor > 1) {
                Choice c = new Choice(keyFor(lens.factor), lens.factor, lens.openId, (float) lens.factor,
                        lens.physicalId);
                if (rejectedChecks == null || !rejectedChecks.contains(c.checkKey())) {
                    addChoice(out, c);
                }
            }
        }
        boolean hasWide = out.stream().anyMatch(c -> c.factor < 1 - SAME_FACTOR);
        if (!hasWide && main.zoomMin < 1 - SAME_FACTOR) {
            double f = Math.round(main.zoomMin * 10) / 10.0;
            addChoice(out, new Choice(keyFor(f), f, main.id, main.zoomMin, null));
        }
        out.sort(Comparator.comparingDouble((Choice c) -> c.factor));
        return out;
    }

    /** The saved choice, or the main lens when it is no longer offered. */
    static Choice resolve(List<Choice> choices, String key) {
        Choice main = null;
        for (Choice c : choices) {
            if (c.key.equals(key)) {
                return c;
            }
            if (MAIN_KEY.equals(c.key)) {
                main = c;
            }
        }
        return main;
    }

    /**
     * The physical module still to confirm for {@code c}, or null when its access path needs
     * no check or the check already passed on this phone.
     */
    static String pendingCheck(Choice c, Set<String> passedChecks) {
        if (c == null || c.checkPhysicalId == null) {
            return null;
        }
        return passedChecks != null && passedChecks.contains(c.checkKey()) ? null : c.checkPhysicalId;
    }

    // One choice per factor; the first added (listed id, then logical) wins.
    private static void addChoice(List<Choice> out, Choice c) {
        for (Choice other : out) {
            if (Math.abs(other.factor - c.factor) < SAME_FACTOR) {
                return;
            }
        }
        out.add(c);
    }

    // Keeps one entry per factor; a separately listed id wins over a logical-zoom member.
    private static void add(List<Lens> lenses, Lens lens) {
        if (lens.factor > 0 && lens.access != Access.NOT_REACHABLE) {
            for (int i = 0; i < lenses.size(); i++) {
                Lens other = lenses.get(i);
                if (other.access != Access.NOT_REACHABLE && Math.abs(other.factor - lens.factor) < SAME_FACTOR) {
                    if (other.access == Access.LOGICAL_ZOOM && lens.access == Access.CAMERA_ID) {
                        lenses.set(i, lens);
                    }
                    return;
                }
            }
        }
        lenses.add(lens);
    }
}
