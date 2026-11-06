package ar.sanzol.sentrycam;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import org.junit.Test;

public class PhotoNamesTest {

    @Test
    public void formatIsUtcWithMillis() {
        // 2026-11-01T04:05:06.007Z
        long t = 1_793_505_906_007L;
        String name = PhotoNames.newName(t, new Random(1));
        assertTrue(name, PhotoNames.PATTERN.matcher(name).matches());
        assertTrue(name, name.startsWith("SentryCam_20261101_040506_007_"));
    }

    @Test
    public void sameMillisecondProducesDistinctNames() {
        Random random = new Random(42);
        Set<String> names = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            names.add(PhotoNames.newName(1_000L, random));
        }
        // 200 draws from 65,536 suffixes; a seeded run gives no collision.
        assertEquals(200, names.size());
    }

    @Test
    public void suffixIsAlwaysFourHexDigits() {
        Random zero = new Random() {
            @Override
            public int nextInt(int bound) {
                return 0;
            }
        };
        assertTrue(PhotoNames.newName(0, zero).endsWith("_0000.jpg"));
        Random max = new Random() {
            @Override
            public int nextInt(int bound) {
                return bound - 1;
            }
        };
        assertTrue(PhotoNames.newName(0, max).endsWith("_ffff.jpg"));
    }
}
