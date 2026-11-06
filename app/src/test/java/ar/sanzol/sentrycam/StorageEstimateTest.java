package ar.sanzol.sentrycam;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class StorageEstimateTest {

    @Test
    public void bytesPerPixelInterpolatesAndClamps() {
        assertEquals(0.08, StorageEstimate.bytesPerPixel(50), 1e-9);
        assertEquals(0.08, StorageEstimate.bytesPerPixel(10), 1e-9);
        assertEquals(0.25, StorageEstimate.bytesPerPixel(90), 1e-9);
        assertEquals(0.31, StorageEstimate.bytesPerPixel(93), 1e-9);
        assertEquals(0.70, StorageEstimate.bytesPerPixel(100), 1e-9);
        assertEquals(0.70, StorageEstimate.bytesPerPixel(120), 1e-9);
        double previous = 0;
        for (int q = 50; q <= 100; q++) {
            double b = StorageEstimate.bytesPerPixel(q);
            assertTrue("monotonic at " + q, b >= previous);
            previous = b;
        }
    }

    @Test
    public void bytesPerPhoto() {
        assertEquals(3_000_000, StorageEstimate.bytesPerPhoto(4000, 3000, 90));
        assertEquals(0, StorageEstimate.bytesPerPhoto(0, 3000, 90));
    }

    @Test
    public void photosPerDay() {
        assertEquals(1440, StorageEstimate.photosPerDay(60, 0, 0));
        assertEquals(86400, StorageEstimate.photosPerDay(1, 0, 0));
        assertEquals(12343, StorageEstimate.photosPerDay(7, 0, 0));
        assertEquals(1, StorageEstimate.photosPerDay(86400, 0, 0));
        // 60 s session at 10 s: ticks at 0..50 s -> 6 photos.
        assertEquals(6, StorageEstimate.photosPerDay(10, 0, 60));
        // 65 s session at 10 s: ticks at 0..60 s -> 7 photos.
        assertEquals(7, StorageEstimate.photosPerDay(10, 0, 65));
        assertEquals(100, StorageEstimate.photosPerDay(1, 100, 0));
        assertEquals(1, StorageEstimate.photosPerDay(3600, 0, 60));
        assertEquals(0, StorageEstimate.photosPerDay(0, 0, 0));
    }

    @Test
    public void daysUntilReserve() {
        assertEquals(-1, StorageEstimate.daysUntilReserve(10, 1, 0), 0);
        assertEquals(0, StorageEstimate.daysUntilReserve(100, 200, 10), 0);
        assertEquals(9.0, StorageEstimate.daysUntilReserve(100, 10, 10), 1e-9);
    }
}
