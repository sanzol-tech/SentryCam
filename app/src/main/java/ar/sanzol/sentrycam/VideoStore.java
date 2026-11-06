package ar.sanzol.sentrycam;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Video files in the shared {@code Movies/SentryCam} folder through MediaStore. Each file is
 * inserted as pending, written by the recorder through a file descriptor, and published only
 * after the recorder has finalized it. A file the recorder could not finalize is unplayable
 * (an MP4 without its index) and is removed as an incomplete write of this app; a published
 * file is never deleted.
 *
 * <p>Provider I/O: never call on the main thread.
 */
final class VideoStore {

    private static final String TAG = "SentryCam";

    static final String RELATIVE_PATH = Environment.DIRECTORY_MOVIES + "/SentryCam/";

    /** A pending file open for writing. */
    static final class Output {
        final Uri uri;
        final ParcelFileDescriptor pfd;
        final String name;

        Output(Uri uri, ParcelFileDescriptor pfd, String name) {
            this.uri = uri;
            this.pfd = pfd;
            this.name = name;
        }
    }

    private final ContentResolver resolver;
    private final Uri collection;
    private final String packageName;

    VideoStore(Context context) {
        resolver = context.getApplicationContext().getContentResolver();
        collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        packageName = context.getPackageName();
    }

    /** Inserts a pending entry and opens it for writing. */
    Output create(String displayName, long takenWallMs) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, displayName);
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH, RELATIVE_PATH);
        values.put(MediaStore.Video.Media.DATE_TAKEN, takenWallMs);
        values.put(MediaStore.Video.Media.IS_PENDING, 1);
        Uri uri;
        try {
            uri = resolver.insert(collection, values);
        } catch (RuntimeException e) {
            throw new IOException("MediaStore insert failed", e);
        }
        if (uri == null) {
            throw new IOException("MediaStore insert returned null");
        }
        try {
            ParcelFileDescriptor pfd = resolver.openFileDescriptor(uri, "rw");
            if (pfd == null) {
                throw new IOException("Could not open output for " + uri);
            }
            return new Output(uri, pfd, displayName);
        } catch (IOException | RuntimeException e) {
            discard(uri);
            throw e instanceof IOException io ? io : new IOException("Could not open " + uri, e);
        }
    }

    /**
     * Closes the descriptor and publishes a file the recorder finalized. Returns its size in
     * bytes, or -1 if publishing failed (the entry is then left pending, never deleted here).
     */
    long publish(Output out) {
        long size = -1;
        try {
            size = out.pfd.getStatSize();
            out.pfd.getFileDescriptor().sync();
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Video sync failed for " + out.name, e);
        }
        closeQuietly(out.pfd);
        try {
            ContentValues publish = new ContentValues();
            publish.put(MediaStore.Video.Media.IS_PENDING, 0);
            if (resolver.update(out.uri, publish, null, null) == 1) {
                return Math.max(0, size);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Video publish failed for " + out.name, e);
        }
        return -1;
    }

    /** Closes and removes a file the recorder could not finalize (our own unfinished write). */
    void discardOutput(Output out) {
        closeQuietly(out.pfd);
        discard(out.uri);
    }

    private void discard(Uri uri) {
        try {
            resolver.delete(uri, null, null);
        } catch (RuntimeException e) {
            // MediaStore expires abandoned pending entries on its own.
            Log.w(TAG, "Could not remove incomplete video " + uri, e);
        }
    }

    private static void closeQuietly(ParcelFileDescriptor pfd) {
        try {
            pfd.close();
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Video descriptor close failed", e);
        }
    }

    /** Published SentryCam videos visible to this app, or -1 when unknown. */
    long countVideos() {
        Bundle args = new Bundle();
        args.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, MediaStore.Video.Media.RELATIVE_PATH + " = ?");
        args.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, new String[] {RELATIVE_PATH});
        try (Cursor c = resolver.query(collection, new String[] {MediaStore.Video.Media._ID}, args, null)) {
            return c == null ? -1 : c.getCount();
        } catch (RuntimeException e) {
            Log.w(TAG, "Video count failed", e);
            return -1;
        }
    }

    /**
     * Removes pending video entries left by a session that ended without finalizing them (the
     * process was killed). Same conservative rule as photos ({@link PhotoStore#isAbandoned}):
     * pending, our folder, our name pattern, owned by this app, at least 10 minutes old.
     */
    int removeIncompleteWrites() {
        Bundle args = new Bundle();
        args.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_ONLY);
        args.putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
                MediaStore.Video.Media.RELATIVE_PATH + " = ? AND " + MediaStore.Video.Media.IS_PENDING + " = 1");
        args.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, new String[] {RELATIVE_PATH});
        String[] projection = {
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.OWNER_PACKAGE_NAME,
                MediaStore.Video.Media.DATE_ADDED,
        };
        long nowSeconds = System.currentTimeMillis() / 1000;
        long minAgeSeconds = PhotoStore.INCOMPLETE_MIN_AGE_MS / 1000;
        List<Uri> incomplete = new ArrayList<>();
        try (Cursor c = resolver.query(collection, projection, args, null)) {
            if (c == null) {
                return 0;
            }
            int idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
            int nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME);
            int ownerCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.OWNER_PACKAGE_NAME);
            int addedCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED);
            while (c.moveToNext()) {
                long added = c.isNull(addedCol) ? 0 : c.getLong(addedCol);
                if (PhotoStore.isAbandoned(VideoNames.PATTERN, c.getString(nameCol), c.getString(ownerCol),
                        packageName, added, nowSeconds, minAgeSeconds)) {
                    incomplete.add(Uri.withAppendedPath(collection, Long.toString(c.getLong(idCol))));
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Pending video query failed", e);
            return 0;
        }
        int removed = 0;
        for (Uri uri : incomplete) {
            try {
                removed += resolver.delete(uri, null, null);
            } catch (RuntimeException e) {
                Log.w(TAG, "Could not remove incomplete video " + uri, e);
            }
        }
        return removed;
    }
}
