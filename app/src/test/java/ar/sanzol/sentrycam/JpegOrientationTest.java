package ar.sanzol.sentrycam;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class JpegOrientationTest {

    @Test
    public void roundsToQuadrants() {
        assertEquals(0, JpegOrientation.roundToQuadrant(0));
        assertEquals(0, JpegOrientation.roundToQuadrant(44));
        assertEquals(90, JpegOrientation.roundToQuadrant(45));
        assertEquals(90, JpegOrientation.roundToQuadrant(134));
        assertEquals(180, JpegOrientation.roundToQuadrant(180));
        assertEquals(270, JpegOrientation.roundToQuadrant(300));
        assertEquals(0, JpegOrientation.roundToQuadrant(359));
        assertEquals(JpegOrientation.UNKNOWN, JpegOrientation.roundToQuadrant(JpegOrientation.UNKNOWN));
    }

    @Test
    public void backCamera() {
        // Typical back sensor mounted at 90 degrees.
        assertEquals(90, JpegOrientation.compute(90, 0, false));
        assertEquals(180, JpegOrientation.compute(90, 90, false));
        assertEquals(0, JpegOrientation.compute(90, 270, false));
        assertEquals(90, JpegOrientation.compute(90, JpegOrientation.UNKNOWN, false));
    }

    @Test
    public void frontCamera() {
        // Typical front sensor mounted at 270 degrees.
        assertEquals(270, JpegOrientation.compute(270, 0, true));
        assertEquals(180, JpegOrientation.compute(270, 90, true));
        assertEquals(0, JpegOrientation.compute(270, 270, true));
    }
}
