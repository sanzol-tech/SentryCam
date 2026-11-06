package ar.sanzol.sentrycam;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class PreviewGeometryTest {

    private static final List<Resolution> OFFERED = Arrays.asList(
            new Resolution(4080, 3072), new Resolution(1920, 1440), new Resolution(1920, 1080),
            new Resolution(1440, 1080), new Resolution(1280, 720), new Resolution(640, 480),
            new Resolution(320, 240));

    @Test
    public void picksLargestSameAspectWithinLimits() {
        // 4:3 photo: 1920x1440 has the aspect but its short edge exceeds 1080.
        Resolution r = PreviewGeometry.choosePreviewSize(OFFERED, new Resolution(4080, 3072));
        assertEquals(1440, r.width);
        assertEquals(1080, r.height);
        // 16:9 photo.
        r = PreviewGeometry.choosePreviewSize(OFFERED, new Resolution(3840, 2160));
        assertEquals(1920, r.width);
        assertEquals(1080, r.height);
    }

    @Test
    public void aspectIsOrientationFree() {
        Resolution r = PreviewGeometry.choosePreviewSize(OFFERED, new Resolution(3072, 4080));
        assertEquals(1440, r.width);
    }

    @Test
    public void fallsBackToClosestAspectThenSmallest() {
        // 1:1 photo: nothing square is offered; 1440x1080 and 640x480 tie at 4:3, larger wins.
        Resolution r = PreviewGeometry.choosePreviewSize(OFFERED, new Resolution(3000, 3000));
        assertEquals(1440, r.width);
        // Nothing within the limits: the smallest offered.
        r = PreviewGeometry.choosePreviewSize(Collections.singletonList(new Resolution(4000, 3000)),
                new Resolution(4000, 3000));
        assertEquals(4000, r.width);
    }

    @Test
    public void prefersSameAspectAboveLimitsOverAnotherAspect() {
        // 4:3 photo; the only 4:3 preview sizes are above the limits: the smallest of them wins
        // over the 16:9 size within the limits, so the preview keeps the photo's framing.
        List<Resolution> offered = Arrays.asList(new Resolution(4000, 3000), new Resolution(2560, 1920),
                new Resolution(1920, 1080), new Resolution(1280, 720));
        Resolution r = PreviewGeometry.choosePreviewSize(offered, new Resolution(4000, 3000));
        assertEquals(2560, r.width);
        assertTrue(PreviewGeometry.sameAspect(r, new Resolution(4000, 3000)));
    }

    @Test
    public void sameAspectRule() {
        assertTrue(PreviewGeometry.sameAspect(new Resolution(1440, 1080), new Resolution(3024, 4032)));
        assertFalse(PreviewGeometry.sameAspect(new Resolution(1920, 1080), new Resolution(4000, 3000)));
        assertFalse(PreviewGeometry.sameAspect(null, new Resolution(4, 3)));
    }

    @Test
    public void nothingOfferedGivesNull() {
        assertNull(PreviewGeometry.choosePreviewSize(Collections.emptyList(), new Resolution(4, 3)));
        assertNull(PreviewGeometry.choosePreviewSize(null, new Resolution(4, 3)));
    }

    @Test
    public void swapRule() {
        assertTrue(PreviewGeometry.swapsDimensions(90, 0));
        assertTrue(PreviewGeometry.swapsDimensions(270, 180));
        assertFalse(PreviewGeometry.swapsDimensions(90, 90));
        assertFalse(PreviewGeometry.swapsDimensions(270, 90));
        assertTrue(PreviewGeometry.swapsDimensions(0, 90));
    }

    @Test
    public void fitLetterboxes() {
        // Portrait 3:4 content in a wide box: height-bound.
        assertArrayEquals(new int[] {300, 400}, PreviewGeometry.fit(1080, 1440, 1000, 400));
        // Landscape 4:3 content in a tall box: width-bound.
        assertArrayEquals(new int[] {400, 300}, PreviewGeometry.fit(1440, 1080, 400, 1000));
        assertArrayEquals(new int[] {0, 0}, PreviewGeometry.fit(0, 0, 0, 0));
    }
}
