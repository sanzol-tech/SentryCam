package ar.sanzol.sentrycam;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.StatFs;
import android.provider.MediaStore;
import android.util.Log;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Saves photos to the shared {@code Pictures/SentryCam} folder through MediaStore.
 *
 * <p>Writes are atomic from the user's point of view: the entry is inserted as pending, the
 * bytes are written and synced, and only then is it published. A failed write removes its own
 * pending entry. This class never deletes a published photo.
 *
 * <p>All methods do disk or provider I/O; never call them on the main thread.
 */
public final class PhotoStore {

    private static final String TAG = "PhotoStore";

    /** MediaStore stores relative paths with a trailing slash. */
    public static final String RELATIVE_PATH = Environment.DIRECTORY_PICTURES + "/SentryCam/";

    /**
     * A pending entry younger than this is never treated as abandoned. A real write takes well
     * under a second; the margin covers a write still finishing on the disk thread of a service
     * instance that is being destroyed while a new session starts.
     */
    static final long INCOMPLETE_MIN_AGE_MS = 10 * 60 * 1000L;

    private final ContentResolver resolver;
    private final Uri collection;
    private final String packageName;

    public PhotoStore(Context context) {
        resolver = context.getApplicationContext().getContentResolver();
        collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        packageName = context.getPackageName();
    }

    /** Writes and publishes one JPEG. Returns the published Uri. */
    public Uri save(String displayName, byte[] jpeg, long takenWallMs) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, displayName);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_PATH);
        values.put(MediaStore.Images.Media.DATE_TAKEN, takenWallMs);
        values.put(MediaStore.Images.Media.IS_PENDING, 1);

        Uri uri = resolver.insert(collection, values);
        if (uri == null) {
            throw new IOException("MediaStore insert returned null");
        }
        boolean published = false;
        try {
            try (ParcelFileDescriptor pfd = resolver.openFileDescriptor(uri, "w")) {
                if (pfd == null) {
                    throw new IOException("Could not open output for " + uri);
                }
                try (FileOutputStream out = new FileOutputStream(pfd.getFileDescriptor())) {
                    out.write(jpeg);
                    out.flush();
                    out.getFD().sync();
                }
            }
            ContentValues publish = new ContentValues();
            publish.put(MediaStore.Images.Media.IS_PENDING, 0);
            if (resolver.update(uri, publish, null, null) != 1) {
                throw new IOException("Could not publish " + uri);
            }
            published = true;
            return uri;
        } catch (RuntimeException e) {
            throw new IOException("MediaStore write failed", e);
        } finally {
            if (!published) {
                removeIncompleteEntry(uri);
            }
        }
    }

    /** Removes an entry this store inserted but could not complete. Never called for published photos. */
    private void removeIncompleteEntry(Uri uri) {
        try {
            resolver.delete(uri, null, null);
        } catch (RuntimeException e) {
            // MediaStore expires abandoned pending entries on its own.
            Log.w(TAG, "Could not remove incomplete entry " + uri, e);
        }
    }

    /** Counts published SentryCam photos visible to this app (one indexed query). */
    public long countPhotos() {
        Bundle args = new Bundle();
        args.putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
                MediaStore.Images.Media.RELATIVE_PATH + " = ?");
        args.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, new String[] {RELATIVE_PATH});
        try (Cursor c = resolver.query(collection, new String[] {MediaStore.Images.Media._ID}, args, null)) {
            return c == null ? -1 : c.getCount();
        } catch (RuntimeException e) {
            Log.w(TAG, "Count failed", e);
            return -1;
        }
    }

    /**
     * Removes pending (never published) SentryCam entries left behind by an interrupted write.
     *
     * <p>Conservative by design. An entry is removed only when all of these hold:
     * <ul>
     *   <li>it is still pending (published photos are never matched);</li>
     *   <li>it is in our folder and has our exact file-name pattern;</li>
     *   <li>its owner package is this app (debug and release are different owners). The
     *       platform already hides other apps' pending entries; this re-checks it explicitly;</li>
     *   <li>it was added at least {@link #INCOMPLETE_MIN_AGE_MS} ago, so a write that is still
     *       running is never touched. An entry with an unknown date is left alone.</li>
     * </ul>
     * Anything left behind is invisible in the gallery and expired by MediaStore on its own.
     *
     * @return the number of incomplete entries removed
     */
    public int removeIncompleteWrites() {
        Bundle args = new Bundle();
        args.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_ONLY);
        args.putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
                MediaStore.Images.Media.RELATIVE_PATH + " = ? AND "
                        + MediaStore.Images.Media.IS_PENDING + " = 1");
        args.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, new String[] {RELATIVE_PATH});
        String[] projection = {
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.OWNER_PACKAGE_NAME,
                MediaStore.Images.Media.DATE_ADDED,
        };
        long nowSeconds = System.currentTimeMillis() / 1000;
        long minAgeSeconds = INCOMPLETE_MIN_AGE_MS / 1000;

        List<Uri> incomplete = new ArrayList<>();
        try (Cursor c = resolver.query(collection, projection, args, null)) {
            if (c == null) {
                return 0;
            }
            int idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
            int nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME);
            int ownerCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.OWNER_PACKAGE_NAME);
            int addedCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED);
            while (c.moveToNext()) {
                String name = c.getString(nameCol);
                String owner = c.getString(ownerCol);
                long added = c.isNull(addedCol) ? 0 : c.getLong(addedCol);
                if (isAbandoned(name, owner, packageName, added, nowSeconds, minAgeSeconds)) {
                    incomplete.add(Uri.withAppendedPath(collection, Long.toString(c.getLong(idCol))));
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Pending query failed", e);
            return 0;
        }
        int removed = 0;
        for (Uri uri : incomplete) {
            try {
                removed += resolver.delete(uri, null, null);
            } catch (RuntimeException e) {
                Log.w(TAG, "Could not remove incomplete entry " + uri, e);
            }
        }
        return removed;
    }

    /**
     * The per-entry rule of {@link #removeIncompleteWrites()} for a pending entry. Pure; every
     * unknown value means "keep".
     */
    static boolean isAbandoned(String name, String owner, String ourPackage, long addedSeconds,
            long nowSeconds, long minAgeSeconds) {
        return isAbandoned(PhotoNames.PATTERN, name, owner, ourPackage, addedSeconds, nowSeconds, minAgeSeconds);
    }

    /** The same rule for any of our name patterns (photos, video files). */
    static boolean isAbandoned(Pattern pattern, String name, String owner, String ourPackage,
            long addedSeconds, long nowSeconds, long minAgeSeconds) {
        return name != null && pattern.matcher(name).matches()
                && ourPackage != null && ourPackage.equals(owner)
                && addedSeconds > 0
                && nowSeconds - addedSeconds >= minAgeSeconds;
    }

    /** Free bytes on primary shared storage, where photos are written. */
    public static long freeBytes() {
        try {
            return new StatFs(Environment.getExternalStorageDirectory().getPath()).getAvailableBytes();
        } catch (RuntimeException e) {
            return -1;
        }
    }
}
