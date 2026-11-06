package ar.sanzol.sentrycam;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The per-entry rule used to clean pending MediaStore entries: anything uncertain is kept. */
public class IncompleteWriteRuleTest {

    private static final String PKG = "ar.sanzoltech.sentrycam";
    private static final String NAME = "SentryCam_20261101_040506_007_00af.jpg";
    private static final long NOW = 1_793_505_906L;
    private static final long MIN_AGE = PhotoStore.INCOMPLETE_MIN_AGE_MS / 1000;

    @Test
    public void oldOwnEntryIsRemoved() {
        assertTrue(PhotoStore.isAbandoned(NAME, PKG, PKG, NOW - MIN_AGE, NOW, MIN_AGE));
        assertTrue(PhotoStore.isAbandoned(NAME, PKG, PKG, NOW - 86_400, NOW, MIN_AGE));
    }

    @Test
    public void recentEntryIsKept() {
        assertFalse(PhotoStore.isAbandoned(NAME, PKG, PKG, NOW, NOW, MIN_AGE));
        assertFalse(PhotoStore.isAbandoned(NAME, PKG, PKG, NOW - MIN_AGE + 1, NOW, MIN_AGE));
        // Clock moved backwards since the insert: age is negative.
        assertFalse(PhotoStore.isAbandoned(NAME, PKG, PKG, NOW + 60, NOW, MIN_AGE));
    }

    @Test
    public void unknownDateIsKept() {
        assertFalse(PhotoStore.isAbandoned(NAME, PKG, PKG, 0, NOW, MIN_AGE));
    }

    @Test
    public void otherOrUnknownOwnerIsKept() {
        long old = NOW - 86_400;
        assertFalse(PhotoStore.isAbandoned(NAME, PKG + ".debug", PKG, old, NOW, MIN_AGE));
        assertFalse(PhotoStore.isAbandoned(NAME, "com.other.app", PKG, old, NOW, MIN_AGE));
        assertFalse(PhotoStore.isAbandoned(NAME, null, PKG, old, NOW, MIN_AGE));
        assertFalse(PhotoStore.isAbandoned(NAME, PKG, null, old, NOW, MIN_AGE));
    }

    @Test
    public void foreignNameIsKept() {
        long old = NOW - 86_400;
        assertFalse(PhotoStore.isAbandoned(null, PKG, PKG, old, NOW, MIN_AGE));
        assertFalse(PhotoStore.isAbandoned("IMG_0001.jpg", PKG, PKG, old, NOW, MIN_AGE));
        assertFalse(PhotoStore.isAbandoned("SentryCam_20261101_040506_007_00af.jpg.tmp", PKG, PKG,
                old, NOW, MIN_AGE));
    }
}
