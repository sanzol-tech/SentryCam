package ar.sanzol.sentrycam;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.List;

import org.junit.Test;

public class ResolutionTest {

    private static final List<Resolution> SIZES = List.of(
            new Resolution(1920, 1080),
            new Resolution(8160, 6144),
            new Resolution(4000, 3000),
            new Resolution(4000, 3000),
            new Resolution(3000, 4000),
            new Resolution(0, 100));

    @Test
    public void sortsLargestFirstAndDeduplicates() {
        List<Resolution> sorted = Resolution.sortLargestFirst(SIZES);
        assertEquals(4, sorted.size());
        assertEquals(new Resolution(8160, 6144), sorted.get(0));
        assertEquals(new Resolution(4000, 3000), sorted.get(1));
        assertEquals(new Resolution(3000, 4000), sorted.get(2));
        assertEquals(new Resolution(1920, 1080), sorted.get(3));
    }

    @Test
    public void choosesRequestedWhenSupported() {
        assertEquals(new Resolution(1920, 1080), Resolution.choose(SIZES, 1920, 1080));
    }

    @Test
    public void fallsBackToLargest() {
        assertEquals(new Resolution(8160, 6144), Resolution.choose(SIZES, 1280, 720));
        assertEquals(new Resolution(8160, 6144), Resolution.choose(SIZES, 0, 0));
    }

    @Test
    public void emptyGivesNull() {
        assertNull(Resolution.choose(List.of(), 0, 0));
    }

    @Test
    public void label() {
        assertEquals("4000 × 3000 · 12 MP · 4:3", new Resolution(4000, 3000).label());
        assertEquals("8000 × 6000 · 48 MP · 4:3", new Resolution(8000, 6000).label());
        assertEquals("4032 × 3024 · 12.2 MP · 4:3", new Resolution(4032, 3024).label());
        assertEquals("640 × 480 · 0.3 MP · 4:3", new Resolution(640, 480).label());
    }

    @Test
    public void shortLabelHasNoMegapixels() {
        assertEquals("4032 × 3024 · 4:3", new Resolution(4032, 3024).shortLabel());
        assertEquals("1920 × 1088 · ≈16:9", new Resolution(1920, 1088).shortLabel());
    }

    @Test
    public void aspectExactCommonRatios() {
        assertEquals("16:9", new Resolution(1920, 1080).aspectLabel());
        assertEquals("1:1", new Resolution(3000, 3000).aspectLabel());
        assertEquals("20:9", new Resolution(2400, 1080).aspectLabel());
        assertEquals("16:10", new Resolution(1920, 1200).aspectLabel());
        assertEquals("3:2", new Resolution(6000, 4000).aspectLabel());
    }

    @Test
    public void aspectKeepsPortraitOrientation() {
        assertEquals("3:4", new Resolution(3000, 4000).aspectLabel());
        assertEquals("9:16", new Resolution(1080, 1920).aspectLabel());
    }

    @Test
    public void aspectNearCommonRatioIsMarkedApproximate() {
        // 1920 × 1088 is 30:17, 0.7 % from 16:9 (a size reported by a 50 MP phone).
        assertEquals("≈16:9", new Resolution(1920, 1088).aspectLabel());
        assertEquals("≈16:9", new Resolution(4000, 2252).aspectLabel());
    }

    @Test
    public void aspectUncommonRatios() {
        assertEquals("7:5", new Resolution(1400, 1000).aspectLabel());
        assertEquals("1.85:1", new Resolution(1998, 1080).aspectLabel());
        assertEquals("?", new Resolution(0, 10).aspectLabel());
    }
}
