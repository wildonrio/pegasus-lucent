package com.thorium.preview;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Read-only, per-intent ROM bridge. The provider is not exported; launch
 * targets can only read a URI after RomLaunchActivity grants that exact URI.
 */
public final class RomFileProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    private File resolve(Uri uri) throws FileNotFoundException {
        String path = uri.getQueryParameter("path");
        if (path == null || path.isEmpty()) throw new FileNotFoundException("Missing path");
        try {
            File file = new File(path).getCanonicalFile();
            String canonical = file.getPath();
            if (!(canonical.startsWith("/storage/") || canonical.startsWith("/mnt/media_rw/")) ||
                    !file.isFile())
                throw new FileNotFoundException("ROM unavailable");
            return file;
        } catch (IOException error) {
            throw new FileNotFoundException("Invalid ROM path");
        }
    }

    @Override public String getType(Uri uri) { return "application/octet-stream"; }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        MatrixCursor cursor = new MatrixCursor(new String[]{
                OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        try {
            File file = resolve(uri);
            cursor.addRow(new Object[]{file.getName(), file.length()});
        } catch (FileNotFoundException ignored) { }
        return cursor;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode)
            throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read only");
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection,
                                String[] selectionArgs) { return 0; }
}
