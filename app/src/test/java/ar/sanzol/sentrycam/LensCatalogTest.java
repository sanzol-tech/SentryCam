package ar.sanzol.sentrycam;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

public class LensCatalogTest {

    private static final Resolution JPEG = new Resolution(4000, 3000);
    // A typical main module (about 77 degrees), ultra-wide (about 118) and 2x tele.
    private static final float SENSOR = 6.4f;
    private static final float MAIN_FOCAL = 4.0f;
    private static final float WIDE_FOCAL = 1.9f;
    private static final float TELE_FOCAL = 8.0f;

    private static LensCatalog.CameraInfo cam(String id, boolean back, float focal, List<String> physical,
            float zoomMin, float zoomMax) {
        return new LensCatalog.CameraInfo(id, back, focal, SENSOR, physical, zoomMin, zoomMax, JPEG);
    }

    private static final Map<String, LensCatalog.CameraInfo> NONE = Collections.emptyMap();

    @Test
    public void fovAndFactorMath() {
        assertEquals(77.3, LensCatalog.fovDegrees(MAIN_FOCAL, SENSOR), 0.1);
        assertEquals(0, LensCatalog.fovDegrees(0, SENSOR), 0);
        double main = LensCatalog.fovDegrees(MAIN_FOCAL, SENSOR);
        assertEquals(0.5, LensCatalog.factor(main, LensCatalog.fovDegrees(WIDE_FOCAL, SENSOR)), 1e-9);
        assertEquals(2.0, LensCatalog.factor(main, LensCatalog.fovDegrees(TELE_FOCAL, SENSOR)), 1e-9);
        assertEquals(1.0, LensCatalog.factor(main, main), 1e-9);
    }

    @Test
    public void formatsChipText() {
        assertEquals("0.5", LensCatalog.formatFactor(0.5));
        assertEquals("1", LensCatalog.formatFactor(1.0));
        assertEquals("2", LensCatalog.formatFactor(2.0));
        assertEquals("2.5", LensCatalog.formatFactor(2.5));
    }

    @Test
    public void singleBackCamera() {
        List<LensCatalog.Lens> lenses = LensCatalog.backLenses(Arrays.asList(
                cam("0", true, MAIN_FOCAL, null, 1, 8), cam("1", false, 3f, null, 1, 1)), NONE);
        assertEquals(1, lenses.size());
        assertEquals(1.0, lenses.get(0).factor, 0);
        assertEquals(LensCatalog.Access.CAMERA_ID, lenses.get(0).access);
    }

    @Test
    public void separateIdsAreCameraIdLenses() {
        List<LensCatalog.Lens> lenses = LensCatalog.backLenses(Arrays.asList(
                cam("0", true, MAIN_FOCAL, null, 1, 8), cam("1", false, 3f, null, 1, 1),
                cam("2", true, WIDE_FOCAL, null, 1, 1), cam("3", true, TELE_FOCAL, null, 1, 1)), NONE);
        assertEquals(3, lenses.size());
        assertEquals(0.5, lenses.get(0).factor, 0);
        assertEquals("2", lenses.get(0).openId);
        assertEquals(1.0, lenses.get(1).factor, 0);
        assertEquals(2.0, lenses.get(2).factor, 0);
        for (LensCatalog.Lens l : lenses) {
            assertEquals(LensCatalog.Access.CAMERA_ID, l.access);
            assertNull(l.physicalId);
        }
    }

    @Test
    public void logicalMembersInRangeAreZoomOnly() {
        Map<String, LensCatalog.CameraInfo> physical = new HashMap<>();
        physical.put("4", cam("4", true, MAIN_FOCAL, null, 1, 1));
        physical.put("5", cam("5", true, WIDE_FOCAL, null, 1, 1));
        physical.put("6", cam("6", true, TELE_FOCAL, null, 1, 1));
        List<LensCatalog.Lens> lenses = LensCatalog.backLenses(Collections.singletonList(
                cam("0", true, MAIN_FOCAL, Arrays.asList("4", "5", "6"), 0.5f, 1.8f)), physical);
        // The main member duplicates factor 1 and is folded into camera 0.
        assertEquals(3, lenses.size());
        LensCatalog.Lens wide = lenses.get(0);
        assertEquals(0.5, wide.factor, 0);
        assertEquals(LensCatalog.Access.LOGICAL_ZOOM, wide.access);
        assertEquals("0", wide.openId);
        assertEquals("5", wide.physicalId);
        // 2x lies outside the 0.5-1.8 zoom range: known, not offered.
        LensCatalog.Lens tele = lenses.get(2);
        assertEquals(LensCatalog.Access.NOT_REACHABLE, tele.access);
        assertEquals(2, LensCatalog.offerable(lenses).size());
    }

    @Test
    public void listedIdWinsOverLogicalMember() {
        Map<String, LensCatalog.CameraInfo> physical = new HashMap<>();
        physical.put("5", cam("5", true, WIDE_FOCAL, null, 1, 1));
        List<LensCatalog.Lens> lenses = LensCatalog.backLenses(Arrays.asList(
                cam("0", true, MAIN_FOCAL, Arrays.asList("4", "5"), 0.5f, 8f),
                cam("2", true, WIDE_FOCAL, null, 1, 1)), physical);
        LensCatalog.Lens wide = lenses.get(0);
        assertEquals(0.5, wide.factor, 0);
        assertEquals(LensCatalog.Access.CAMERA_ID, wide.access);
        assertEquals("2", wide.openId);
        // Member "4" has no characteristics: reported, never offered.
        assertTrue(lenses.stream().anyMatch(l -> "4".equals(l.physicalId)
                && l.access == LensCatalog.Access.NOT_REACHABLE));
    }

    @Test
    public void noBackCameraGivesNothing() {
        assertTrue(LensCatalog.backLenses(Collections.singletonList(
                cam("1", false, 3f, null, 1, 1)), NONE).isEmpty());
    }

    // ---- Choices ----

    /** The chips' texts, plus the selected key: the selection no longer changes the text. */
    private static String labels(List<LensCatalog.Choice> choices, String selectedKey) {
        StringBuilder sb = new StringBuilder();
        for (LensCatalog.Choice c : choices) {
            sb.append(sb.isEmpty() ? "" : " ").append(c.label());
        }
        return sb + " [" + LensCatalog.resolve(choices, selectedKey).key + "]";
    }

    @Test
    public void choicesForOneLensIsMainOnly() {
        List<LensCatalog.Choice> c = LensCatalog.choices(Collections.singletonList(
                cam("0", true, MAIN_FOCAL, null, 1, 8)), NONE, null);
        assertEquals("1× [back:1]", labels(c, LensCatalog.MAIN_KEY));
    }

    @Test
    public void wideFromZoomRangeNeedsNoCheck() {
        // A logical main camera that reports no members but zooms down to 0.6.
        List<LensCatalog.Choice> c = LensCatalog.choices(Collections.singletonList(
                cam("0", true, MAIN_FOCAL, null, 0.6f, 8)), NONE, null);
        assertEquals("0.6× 1× [back:1]", labels(c, LensCatalog.MAIN_KEY));
        assertEquals(0.6f, c.get(0).zoomRatio, 1e-6);
        assertEquals("0", c.get(0).openId);
        assertNull(c.get(0).checkPhysicalId);
    }

    @Test
    public void teleThroughLogicalNeedsCheckAndCanBeRejected() {
        Map<String, LensCatalog.CameraInfo> physical = new HashMap<>();
        physical.put("4", cam("4", true, MAIN_FOCAL, null, 1, 1));
        physical.put("5", cam("5", true, WIDE_FOCAL, null, 1, 1));
        physical.put("6", cam("6", true, TELE_FOCAL, null, 1, 1));
        List<LensCatalog.CameraInfo> listed = Collections.singletonList(
                cam("0", true, MAIN_FOCAL, Arrays.asList("4", "5", "6"), 0.5f, 10f));
        List<LensCatalog.Choice> c = LensCatalog.choices(listed, physical, null);
        assertEquals("0.5× 1× 2× [back:2]", labels(c, "back:2"));
        LensCatalog.Choice tele = c.get(2);
        assertEquals("6", tele.checkPhysicalId);
        assertEquals(2f, tele.zoomRatio, 1e-6);
        // Once the module check failed, 2 is no longer offered.
        Set<String> rejected = new HashSet<>(Collections.singletonList(tele.checkKey()));
        assertEquals("0.5× 1× [back:1]", labels(LensCatalog.choices(listed, physical, rejected), LensCatalog.MAIN_KEY));
    }

    @Test
    public void listedWideWinsOverZoomRange() {
        List<LensCatalog.Choice> c = LensCatalog.choices(Arrays.asList(
                cam("0", true, MAIN_FOCAL, null, 0.6f, 8), cam("2", true, WIDE_FOCAL, null, 1, 1)), NONE, null);
        assertEquals("0.5× 1× [back:1]", labels(c, LensCatalog.MAIN_KEY));
        assertEquals("2", c.get(0).openId);
        assertEquals(1f, c.get(0).zoomRatio, 1e-6);
    }

    @Test
    public void resolveFallsBackToMain() {
        List<LensCatalog.Choice> c = LensCatalog.choices(Collections.singletonList(
                cam("0", true, MAIN_FOCAL, null, 0.6f, 8)), NONE, null);
        assertEquals("back:0.6", LensCatalog.resolve(c, "back:0.6").key);
        assertEquals(LensCatalog.MAIN_KEY, LensCatalog.resolve(c, "back:3").key);
        assertNull(LensCatalog.resolve(Collections.emptyList(), "back:1"));
    }

    @Test
    public void pendingCheckOnlyForUnconfirmedModules() {
        LensCatalog.Choice listed = new LensCatalog.Choice("back:0.5", 0.5, "2", 1f, null);
        LensCatalog.Choice tele = new LensCatalog.Choice("back:2", 2.0, "0", 2f, "4");
        assertNull(LensCatalog.pendingCheck(listed, Collections.emptySet()));
        assertEquals("4", LensCatalog.pendingCheck(tele, Collections.emptySet()));
        assertEquals("4", LensCatalog.pendingCheck(tele, null));
        assertNull(LensCatalog.pendingCheck(tele, new HashSet<>(List.of("0:4"))));
        assertNull(LensCatalog.pendingCheck(null, Collections.emptySet()));
    }
}
